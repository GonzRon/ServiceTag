package com.loosecannon.servicetag.reminders

import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.ports.DeadlineLocalDelivery
import com.loosecannon.servicetag.core.ports.ScheduleLocalDelivery
import com.loosecannon.servicetag.core.reminders.DeadlineKind
import com.loosecannon.servicetag.core.reminders.DeadlineRepeat
import com.loosecannon.servicetag.core.reminders.ReconcileReport
import com.loosecannon.servicetag.core.reminders.ReminderSubject
import com.loosecannon.servicetag.core.reminders.SubjectKey
import com.loosecannon.servicetag.core.reminders.SubjectState
import com.loosecannon.servicetag.core.reminders.isCleared
import com.loosecannon.servicetag.core.schedule.DueStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The digest policy: given the subjects, what is already showing, the delivery rows and *now*, what
 * to post, what to clear and what to leave alone (D-5, spec §5.7, master plan §12 and §17.1e).
 *
 * Pure, and the reason every timing row in this brief's matrix is a deterministic test rather than
 * an overnight observation. It holds no platform type: `now` arrives as a parameter, what is
 * standing arrives as a set of tags, and the decision it returns is a value the provider executes.
 *
 * **Fatigue controls are #26, not this.** The four rules below are exactly D-5's and nothing more:
 * one summary per run, per-item notifications only for unsnoozed DUE and OVERDUE, DUE SOON
 * announced once on first entry, and an overdue subject re-announced every three days.
 */
object DigestPolicy {

    /** D-5: an overdue subject is announced again after three days, and not before. */
    const val RENOTIFY_MILLIS: Long = 3L * 24L * 60L * 60L * 1000L

    /** RATIFIED verbatim (master plan §17.1e). `<n>` is the count of the items the body represents. */
    internal const val SUMMARY_TITLE_SUFFIX = " maintenance items need attention"

    /** RATIFIED verbatim (master plan §17). B07 attaches them; the constants live where they are built. */
    const val ACTION_DONE = "Done"
    const val ACTION_SNOOZE_ONE_DAY = "Snooze 1 day"
    const val ACTION_OPEN = "Open"

    /** RATIFIED verbatim (master plan §17): the two status words this provider ever delivers for. */
    const val WORD_DUE = "DUE"
    const val WORD_OVERDUE = "OVERDUE"

    /** #79, P79-11 (RATIFIED verbatim): a warranty warning's status word, its `setSubText` as DUE's. */
    const val WORD_EXPIRES_SOON = "EXPIRES SOON"

    /**
     * #72, P72-42 and P72-43 (RATIFIED verbatim, R72-7, R72-23): a loan reminder's status word
     * through its due day, and after it. Neither is the bare `OVERDUE` (AC 13), so a loan never
     * takes that word's icon or accent.
     */
    const val WORD_DUE_BACK = "DUE BACK"
    const val WORD_NOT_RETURNED = "NOT RETURNED"

    /** The shipped display-date shape (`AssetDetailScreen.kt:821`, `EventDetailScreen.kt:177`). */
    private val DISPLAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM uuuu")

    /**
     * The decision.
     *
     * [standingTags] and [standingSummaryTag] are what the platform says is showing **right now**,
     * not something this object or the provider remembered. That is what makes invariant 44 true
     * without a projection table and invariant 45 true across process death: the tag carries the
     * subject's content hash, so "already showing, in this exact form" is a question the
     * notification shade itself answers.
     *
     * #79 (C7): [inputs] carry both families, and a deadline is routed to its own branch by type.
     * [bootCount] is the platform's boot count, or null when it cannot be read (R79-14c).
     */
    fun decide(
        inputs: List<DigestInput>,
        standingTags: Set<String>,
        standingSummaryTag: String?,
        nowMillis: Long,
        bootCount: Int? = null,
        channelDelivers: (String) -> Boolean = { true },
    ): DigestDecision {
        val shown = mutableListOf<ItemPost>()
        val posts = mutableListOf<ItemPost>()
        val rows = mutableListOf<ScheduleLocalDelivery>()
        val deadlineRows = mutableListOf<DeadlineLocalDelivery>()
        val deadlineForgotten = mutableListOf<SubjectKey.Deadline>()
        var overdue = 0
        var due = 0
        var dueSoon = 0
        var unchanged = 0
        var posted = 0

        inputs.forEach { input ->
            // #79 (C7): a deadline has its own branch and never reaches the schedule rules below —
            // it is not counted, snoozed, nonced or re-announced by them.
            val scheduleInput = when (input) {
                is DeadlineInput -> {
                    when (val step = deadlineStep(input, standingTags, nowMillis, bootCount, channelDelivers)) {
                        DeadlineStep.Forget -> if (input.row != null) deadlineForgotten += input.key
                        DeadlineStep.Quiet -> Unit
                        is DeadlineStep.Standing -> {
                            shown += step.post
                            unchanged++
                        }
                        is DeadlineStep.Announce -> {
                            shown += step.post
                            posts += step.post
                            posted++
                            deadlineRows += step.stamp
                        }
                        // #72 (C11, R72-7): a standing post re-posted in place so it alerts again. It
                        // was already showing, so it is `unchanged`, never `posted` — the identity
                        // `posted + unchanged` = what is held survives the re-alert.
                        is DeadlineStep.Realert -> {
                            shown += step.post
                            posts += step.post
                            unchanged++
                            deadlineRows += step.stamp
                        }
                    }
                    return@forEach
                }
                is DeliveryInput -> input
            }
            val subject = scheduleInput.subject
            val row = scheduleInput.delivery
            val facts = scheduleInput.facts
            val status = facts?.status

            // Three ways a subject earns no notification and no count at all, folded because the
            // instruction is identical: stop showing it, and forget the bookkeeping so a later
            // re-entry announces again rather than being suppressed by a stale stamp.
            //
            // `facts == null` is a schedule that has gone between the list being built and this
            // decision; a Withdrawn or Completed subject is one the owner retired (its nonce is
            // cleared with it, D-21); a Parked one is paused or out of season, and leaving its
            // notification up is a paused schedule still nagging with nothing to act on
            // (invariant 47, and invariant 22 for the status words that never notify).
            if (facts == null || subject.state.isCleared || subject.state is SubjectState.Parked ||
                status?.notifies != true
            ) {
                row?.let { rows += it.forgotten(nowMillis) }
                return@forEach
            }

            // A snooze suppresses **this provider** and nothing else (invariant 20): no date moves,
            // no event is written, the status stays whatever it is, and the row is left exactly as
            // it stands — overwriting it here is how a snooze loses its own instant. A missing row
            // is not a snooze.
            val snoozedUntil = row?.snoozedUntilAt
            if (snoozedUntil != null && snoozedUntil > nowMillis) return@forEach

            when (status) {
                DueStatus.DUE_SOON -> {
                    // Announced once per entry, in the summary only, and never as a per-item
                    // notification: a schedule due tomorrow with a fortnight's lead would otherwise
                    // produce a notification every run for a fortnight.
                    if (row?.firstEntrySeen != true) {
                        dueSoon++
                        rows += (row ?: blankRow(scheduleInput.key, nowMillis)).copy(
                            firstEntrySeen = true,
                            updatedAt = nowMillis,
                        )
                    }
                }
                DueStatus.DUE, DueStatus.OVERDUE -> {
                    // Counted first, and counted even when the channel it would go on is muted: the
                    // obligation is real, the summary rides a different channel, and a digest that
                    // quietly under-counted because of a system setting would be a lie about how
                    // much needs attention.
                    if (status == DueStatus.OVERDUE) overdue++ else due++
                    val post = itemPost(subject, facts)
                    if (!channelDelivers(post.channelId)) {
                        // The platform will not show this one. Nothing is posted, nothing is stamped
                        // — a `last_notified_at` for an announcement that never reached anybody
                        // would suppress the real one for three days once the owner un-muted — and
                        // nothing is held, so a standing notification from before the mute is
                        // cancelled rather than left behind as a thing this provider claims to
                        // maintain (fix round 1, finding 2).
                        return@forEach
                    }
                    shown += post
                    val standing = post.tag in standingTags
                    if (standing) unchanged++
                    // A standing notification for this subject in some **other** form: its content
                    // moved, so the stale one is about to be cancelled and the new one has to take
                    // its place. That is a replacement, not a re-announcement, and the three-day
                    // rule must not swallow it or the owner is left with nothing in the shade.
                    val replaced = !standing && standingTags.any { keyOfTag(it) == post.key }
                    val lastNotifiedAt = row?.lastNotifiedAt
                    // D-5, and the brief's matrix row: the three-day gate is driven by
                    // `last_notified_at` **whether or not the tag is still standing** (fix round 1,
                    // finding 3). A notification the owner swiped away leaves `activeNotifications`,
                    // and re-posting it on the next run would be the every-run fatigue #21 promises
                    // not to cause. DUE is unchanged: it is due for one day and then it is overdue.
                    val shouldPost = if (status == DueStatus.OVERDUE) {
                        replaced || lastNotifiedAt == null || nowMillis - lastNotifiedAt >= RENOTIFY_MILLIS
                    } else {
                        !standing
                    }
                    if (shouldPost) {
                        posts += post
                        // `posted` counts what is now being shown **in a form it was not being
                        // shown in before** — the port's own definition. A three-day re-notify of an
                        // already-standing tag is handed to the platform but was already showing, so
                        // it is `unchanged`; a subject the three-day gate suppressed while nothing was
                        // standing is in neither, because nothing of it is showing. Counting
                        // `shown.size - unchanged` reported a post for that second case (fix round 2,
                        // finding 15), which broke the identity `posted + unchanged` = what is held.
                        if (!standing) posted++
                        rows += (row ?: blankRow(scheduleInput.key, nowMillis)).copy(
                            lastNotifiedAt = nowMillis,
                            updatedAt = nowMillis,
                        )
                    }
                }
                // `notifies` is true for exactly the three above; the rest were folded out already.
                else -> Unit
            }
        }

        // The kept set covers both families: a standing warning or loan reminder is kept by its own
        // key, so the schedule rules above can never take one down (#79, K3; #72).
        val keptKeys = shown.map { it.key }.toSet()
        val keptTags = shown.map { it.tag }.toSet()
        val cancelTags = standingTags.filterNot { it in keptTags }
        val cleared = standingTags.count { keyOfTag(it) !in keptKeys }

        val total = overdue + due + dueSoon
        val summaryTag = if (total == 0) null else "$total|$overdue|$due|$dueSoon"
        // The summary rides `maintenance_due`, so muting that channel loses the digest — Android's
        // own doing, and not a reason to widen the silence to the OVERDUE channel (finding 2).
        val summaryDelivers = channelDelivers(NotificationChannels.DUE)
        val summary = if (total == 0 || !summaryDelivers || summaryTag == standingSummaryTag) {
            null
        } else {
            SummaryPost(
                tag = summaryTag!!,
                title = "$total$SUMMARY_TITLE_SUFFIX",
                body = summaryBody(overdue, due, dueSoon),
            )
        }

        return DigestDecision(
            posts = posts,
            shown = shown,
            summary = summary,
            cancelTags = cancelTags,
            // A standing summary whose tag is not the one this run wants has to go, whether that is
            // because there is nothing left to announce or because the counts moved: the tag is
            // part of the notification's identity, so posting a new one beside a stale one would
            // leave the owner two summaries disagreeing about how much needs attention.
            cancelSummary = standingSummaryTag != null && (standingSummaryTag != summaryTag || !summaryDelivers),
            rows = rows.distinctBy { it.scheduleId.value },
            deadlineRows = deadlineRows,
            deadlineForgotten = deadlineForgotten,
            report = ReconcileReport(
                posted = posted,
                cleared = cleared,
                unchanged = unchanged,
                problems = emptyList(),
            ),
        )
    }

    /**
     * RATIFIED verbatim (master plan §17.1e): **"\<n\> overdue, \<n\> due, \<n\> due soon."**, with
     * **a zero-count clause omitted** — a run with nothing due soon reads "2 overdue, 1 due."
     */
    internal fun summaryBody(overdue: Int, due: Int, dueSoon: Int): String = listOfNotNull(
        "$overdue overdue".takeIf { overdue > 0 },
        "$due due".takeIf { due > 0 },
        "$dueSoon due soon".takeIf { dueSoon > 0 },
    ).joinToString(", ", postfix = ".")

    /**
     * One per-item notification, in the ratified forms (master plan §17.1e): the title
     * **"\<asset\> — \<title\>"**, and the body **"Due \<date\>."**, **"Overdue since \<date\>."**
     * or **"Due at \<n\> \<unit\>, now \<n\>."** when a meter threshold is what came due.
     *
     * `ReminderSubject.body` is deliberately **not** rendered: it is the port's own display line,
     * composed of a status word and a group's progress, and the ratified per-item body is a
     * sentence. A group's progress reaches the owner through the checklist the "Open" action opens.
     */
    private fun itemPost(subject: ReminderSubject, facts: DeliveryFacts): ItemPost {
        val overdue = facts.status == DueStatus.OVERDUE
        val meter = facts.meter
        val dueOn = subject.dueOn
        val body = when {
            meter != null -> meterBody(meter)
            dueOn == null -> ""
            overdue -> "Overdue since ${dueOn.display()}."
            else -> "Due ${dueOn.display()}."
        }
        return ItemPost(
            key = subject.key,
            tag = itemTag(subject.key, subject.contentHash),
            channelId = if (overdue) NotificationChannels.OVERDUE else NotificationChannels.DUE,
            title = "${facts.ownerName} — ${subject.title}",
            body = body,
            // The distinction survives with colour removed, because it is carried by this word and
            // by the body's own first word — never by an accent colour and never by an icon alone
            // (#11's and #21's Visual design sections).
            statusWord = if (overdue) WORD_OVERDUE else WORD_DUE,
            // A fact, not an inference. The icon used to be chosen by matching the body against the
            // ratified meter wording, which would have silently reverted to the clock if §17.1e were
            // ever reworded (fix round 1, nit 7).
            meter = meter != null,
            // D-7: "Done" on a group either completes nothing or falsely completes everyone, so a
            // group-targeted schedule's notification offers "Open" and nothing else.
            actions = if (facts.groupTargeted) {
                listOf(ACTION_OPEN)
            } else {
                listOf(ACTION_DONE, ACTION_SNOOZE_ONE_DAY, ACTION_OPEN)
            },
        )
    }

    /**
     * #79 (C7, R79-14): one deadline, by its repeat fact. `null` is unreachable — a
     * `ReminderSubject` refuses a deadline without one — and is read as the gone subject it would be.
     *
     * #72 (C11): routed by **kind first**, then repeat, so each kind keeps its own steps: a warranty
     * goes to the shipped [once] and nowhere else, a loan to [loanOnce] or [untilCleared]. Any other
     * pairing is not a subject either kind produces, and is forgotten.
     */
    private fun deadlineStep(
        input: DeadlineInput,
        standingTags: Set<String>,
        nowMillis: Long,
        bootCount: Int?,
        channelDelivers: (String) -> Boolean,
    ): DeadlineStep = when (input.key.kind) {
        DeadlineKind.WARRANTY_EXPIRY -> when (input.subject.repeat) {
            DeadlineRepeat.ONCE -> once(input, standingTags, nowMillis, bootCount, channelDelivers)
            DeadlineRepeat.UNTIL_CLEARED, null -> DeadlineStep.Forget
        }
        DeadlineKind.LOAN_DUE_BACK -> when (input.subject.repeat) {
            DeadlineRepeat.ONCE -> loanOnce(input, standingTags, nowMillis, bootCount, channelDelivers)
            DeadlineRepeat.UNTIL_CLEARED -> untilCleared(input, standingTags, nowMillis, bootCount, channelDelivers)
            null -> DeadlineStep.Forget
        }
    }

    /**
     * R79-14a: **once per content, on entering the window** — the window being the lead's days
     * before the expiry through the expiry day itself.
     *
     * - Its asset gone, or today outside the window: nothing is shown, and the stamp is forgotten, so
     *   a later entry announces again. A standing warning is taken down by the kept set, because it
     *   is not in it.
     * - Standing in exactly this form: held, and counted unchanged.
     * - Stamped with this content in this boot: nothing. The owner swiped it, and "once" means once.
     *   A stamp from **another** boot is a warning a restart took down, so it is posted once more
     *   (R79-14c) — which also brings back, once, a warning the owner swiped before the restart (the
     *   disclosed consequence). A count that cannot be read, then or now, is the same boot.
     * - Its channel muted: nothing, and **no stamp** — a stamp for a warning nobody received would
     *   suppress the real one once the owner un-muted.
     * - Otherwise it is posted and stamped. A moved date or lead is new content: its old tag is not
     *   in the kept set, so it is cancelled before the new one is posted.
     *
     * Never counted in the maintenance summary, never snoozed, never nonced (K3).
     */
    private fun once(
        input: DeadlineInput,
        standingTags: Set<String>,
        nowMillis: Long,
        bootCount: Int?,
        channelDelivers: (String) -> Boolean,
    ): DeadlineStep {
        val subject = input.subject
        val facts = input.facts ?: return DeadlineStep.Forget
        val dueOn = subject.dueOn ?: return DeadlineStep.Forget
        if (subject.state != SubjectState.Active) return DeadlineStep.Forget
        val opens = dueOn.minusDays(subject.leadDays.toLong())
        if (facts.today.isBefore(opens) || facts.today.isAfter(dueOn)) return DeadlineStep.Forget

        val post = deadlinePost(input, facts, dueOn)
        if (post.tag in standingTags) return DeadlineStep.Standing(post)
        val row = input.row
        if (row != null && row.announcedHash == subject.contentHash && sameBoot(row.announcedBoot, bootCount)) {
            return DeadlineStep.Quiet
        }
        if (!channelDelivers(post.channelId)) return DeadlineStep.Quiet
        return DeadlineStep.Announce(
            post = post,
            stamp = DeadlineLocalDelivery(
                kind = input.key.kind.name,
                subjectId = input.key.subjectId,
                announcedHash = subject.contentHash,
                announcedBoot = bootCount,
                updatedAt = nowMillis,
            ),
        )
    }

    /** R79-14c: an unreadable count — when the stamp was written, or now — is the same boot. */
    private fun sameBoot(announced: Int?, current: Int?): Boolean =
        announced == null || current == null || announced == current

    /**
     * The warranty warning (R79-17): `<asset> — Warranty`, P79-10's body, P79-11's word on the
     * `warranty_reminders` channel, "Open" alone, and — being neither a meter nor OVERDUE — the
     * clock icon and DUE's accent from `Notifications.kt`'s shipped `else` branches.
     */
    private fun deadlinePost(input: DeadlineInput, facts: DeadlineFacts, dueOn: LocalDate): ItemPost = ItemPost(
        key = input.key,
        tag = itemTag(input.key, input.subject.contentHash),
        channelId = NotificationChannels.WARRANTY,
        title = "${facts.ownerName} — ${input.subject.title}",
        body = warrantyBody(dueOn),
        statusWord = WORD_EXPIRES_SOON,
        meter = false,
        actions = listOf(ACTION_OPEN),
    )

    /** #79, P79-10 (RATIFIED verbatim), in the shipped display-date shape. */
    private fun warrantyBody(expiresOn: LocalDate): String = "Warranty expires ${expiresOn.display()}."

    /**
     * #72 (C11, R72-6 a): a loan's Once — **strictly once per due occurrence**. It opens at the owner's
     * digest hour on the due day; the first sweep at or after it posts, or a later one if the phone was
     * off — but never a sweep before **that day's** digest hour (R72-7, fix round 1): the midnight
     * `DATE_CHANGED` sweep, the backstop at night or a boot at 00:30 is quiet for a loan. It has no
     * end: a loan is a subject until it is returned, and a return makes it absent.
     *
     * - No facts (the loan returned or gone, its asset gone), not Active, or before its due day:
     *   nothing shows and the stamp is forgotten, so a re-dated loan announces again when its own day
     *   comes.
     * - From the due day on, before the day's digest hour ([DeadlineFacts.dayOpensAt]): **held** — a
     *   standing post stays, anything else waits — and nothing is stamped or forgotten, so neither a
     *   midnight sweep nor a zone moved west on the due day can take a post down and announce it twice.
     * - Standing in exactly this form: held, and counted unchanged — after the due day too.
     * - Stamped with this content **from any boot**: nothing. The owner swiped it, or a restart took it
     *   down, and "once" means once — the plate, the list and the Dashboard still say so. The boot is
     *   written into the stamp and not read here (R79-14c's restart rule is the warranty's alone).
     * - Its channel muted: nothing, and **no stamp**, so un-muting announces it.
     * - Otherwise it is posted and stamped. A re-date or a mode change is new content, so a new
     *   occurrence; so is a None → Once round trip, whose stamp went with the subject.
     *
     * Never counted in the maintenance summary, never snoozed, never nonced.
     */
    private fun loanOnce(
        input: DeadlineInput,
        standingTags: Set<String>,
        nowMillis: Long,
        bootCount: Int?,
        channelDelivers: (String) -> Boolean,
    ): DeadlineStep {
        val subject = input.subject
        val facts = input.facts ?: return DeadlineStep.Forget
        val dueOn = subject.dueOn ?: return DeadlineStep.Forget
        if (subject.state != SubjectState.Active) return DeadlineStep.Forget
        if (facts.today.isBefore(dueOn)) return DeadlineStep.Forget
        val dayOpensAt = facts.dayOpensAt ?: return DeadlineStep.Forget

        val post = loanPost(input, facts, dueOn)
        val standing = post.tag in standingTags
        if (nowMillis < dayOpensAt) return if (standing) DeadlineStep.Standing(post) else DeadlineStep.Quiet
        if (standing) return DeadlineStep.Standing(post)
        val row = input.row
        if (row != null && row.announcedHash == subject.contentHash) return DeadlineStep.Quiet
        if (!channelDelivers(post.channelId)) return DeadlineStep.Quiet
        return DeadlineStep.Announce(post, loanStamp(input, bootCount, nowMillis))
    }

    /**
     * #72 (C11, R72-7): a loan's Until returned — announced again **once each period**, a period
     * running from one of the owner's digest hours to the next ([DeadlineFacts.cadenceSince] is the
     * latest one at or before now). It opens as [loanOnce] does, has no end, and is quiet at every
     * sweep before the day's digest hour (fix round 1): no first post, no re-alert after a missed
     * period and no restart re-post happens between local midnight and that hour, so the midnight
     * `DATE_CHANGED` sweep never alerts. Past that hold, the period began at the day's digest hour.
     *
     * - No facts, not Active, or before its due day: nothing shows and the stamp is forgotten.
     * - From the due day on, before the day's digest hour: held if standing, else nothing; nothing is
     *   stamped or forgotten.
     * - **Announced this period** — a stamp with this content, from this boot, written at or after
     *   the period began: held if its post is standing, else nothing (a swipe is quiet until the next
     *   digest hour).
     * - Its channel muted: held if its post is standing — a mute never takes a post down — else
     *   nothing, and no stamp.
     * - Otherwise it is announced and stamped: posted if nothing of it is standing (the first time, a
     *   swipe, or a restart that took it down — once more in the period), or **re-posted in place** if
     *   yesterday's post still stands, so it alerts again ([DeadlineStep.Realert]).
     *
     * Never counted in the maintenance summary, never snoozed, never nonced.
     */
    private fun untilCleared(
        input: DeadlineInput,
        standingTags: Set<String>,
        nowMillis: Long,
        bootCount: Int?,
        channelDelivers: (String) -> Boolean,
    ): DeadlineStep {
        val subject = input.subject
        val facts = input.facts ?: return DeadlineStep.Forget
        val dueOn = subject.dueOn ?: return DeadlineStep.Forget
        if (subject.state != SubjectState.Active) return DeadlineStep.Forget
        if (facts.today.isBefore(dueOn)) return DeadlineStep.Forget
        val dayOpensAt = facts.dayOpensAt ?: return DeadlineStep.Forget
        val cadenceSince = facts.cadenceSince ?: return DeadlineStep.Forget

        val post = loanPost(input, facts, dueOn)
        val standing = post.tag in standingTags
        if (nowMillis < dayOpensAt) return if (standing) DeadlineStep.Standing(post) else DeadlineStep.Quiet
        val row = input.row
        val announcedThisPeriod = row != null &&
            row.announcedHash == subject.contentHash &&
            sameBoot(row.announcedBoot, bootCount) &&
            row.updatedAt >= cadenceSince
        if (announcedThisPeriod || !channelDelivers(post.channelId)) {
            return if (standing) DeadlineStep.Standing(post) else DeadlineStep.Quiet
        }
        val stamp = loanStamp(input, bootCount, nowMillis)
        return if (standing) DeadlineStep.Realert(post, stamp) else DeadlineStep.Announce(post, stamp)
    }

    /**
     * A loan's device-local stamp, in the shipped table and shape: the content announced, the boot it
     * was announced in and when. [untilCleared] reads all three; [loanOnce] reads the content alone,
     * so its boot is written and ignored (R72-6 a) — no column is added for either.
     */
    private fun loanStamp(input: DeadlineInput, bootCount: Int?, nowMillis: Long): DeadlineLocalDelivery = DeadlineLocalDelivery(
        kind = input.key.kind.name,
        subjectId = input.key.subjectId,
        announcedHash = input.subject.contentHash,
        announcedBoot = bootCount,
        updatedAt = nowMillis,
    )

    /**
     * #72 (C11; R72-7, R72-9): a loan reminder, chosen by its kind — `<asset> — Due back` on the
     * `loan_reminders` channel, P72-40 and P72-42 through the due day and P72-41 and P72-43 after it,
     * "Open" alone, and, being neither a meter nor OVERDUE, the clock icon and DUE's accent from
     * `Notifications.kt`'s shipped `else` branches. The borrower comes from the facts, never the
     * subject, so a relink moves no hash and posts nothing twice.
     */
    private fun loanPost(input: DeadlineInput, facts: DeadlineFacts, dueOn: LocalDate): ItemPost {
        val borrower = facts.borrower.orEmpty()
        val afterDueDay = facts.today.isAfter(dueOn)
        return ItemPost(
            key = input.key,
            tag = itemTag(input.key, input.subject.contentHash),
            channelId = NotificationChannels.LOANS,
            title = "${facts.ownerName} — ${input.subject.title}",
            body = if (afterDueDay) loanBodyAfter(borrower, dueOn) else loanBodyThrough(borrower, dueOn),
            statusWord = if (afterDueDay) WORD_NOT_RETURNED else WORD_DUE_BACK,
            meter = false,
            actions = listOf(ACTION_OPEN),
        )
    }

    /** #72, P72-40 (RATIFIED verbatim), in the shipped display-date shape. */
    private fun loanBodyThrough(borrower: String, dueOn: LocalDate): String = "Lent to $borrower. Due back ${dueOn.display()}."

    /** #72, P72-41 (RATIFIED verbatim, R72-7), in the shipped display-date shape. */
    private fun loanBodyAfter(borrower: String, dueOn: LocalDate): String = "Lent to $borrower. Was due back ${dueOn.display()}."

    /** What the deadline branch decided for one subject; [decide] turns it into posts, stamps and counts. */
    private sealed interface DeadlineStep {
        /** Gone, or outside its window: nothing shows, and its stamp is forgotten. */
        data object Forget : DeadlineStep

        /**
         * Nothing shows, and nothing is written: a warning swiped in this boot, a loan's Once already
         * announced, an Until already announced this period, a muted channel, or a loan held before
         * the day's digest hour with nothing standing.
         */
        data object Quiet : DeadlineStep

        /** Showing in exactly this form. */
        data class Standing(val post: ItemPost) : DeadlineStep

        /** Posted now, and stamped. */
        data class Announce(val post: ItemPost, val stamp: DeadlineLocalDelivery) : DeadlineStep

        /**
         * #72 (C11, R72-7): standing, and re-posted now in place under the same tag so it alerts
         * again, and stamped. It was already showing, so it is counted unchanged.
         */
        data class Realert(val post: ItemPost, val stamp: DeadlineLocalDelivery) : DeadlineStep
    }

    /** A meter with no unit at all (a pH definition) leaves the `<unit>` slot empty rather than doubling a space. */
    private fun meterBody(meter: MeterReading): String {
        val at = if (meter.unit.isBlank()) meter.dueAt else "${meter.dueAt} ${meter.unit}"
        return "Due at $at, now ${meter.now}."
    }

    private fun LocalDate.display(): String = format(DISPLAY_DATE)

    private fun blankRow(key: SubjectKey.Schedule, nowMillis: Long) = ScheduleLocalDelivery(
        scheduleId = key.scheduleId,
        snoozedUntilAt = null,
        lastNotifiedAt = null,
        firstEntrySeen = false,
        actionNonce = null,
        nonceIssuedAt = null,
        updatedAt = nowMillis,
    )

    /**
     * The bookkeeping a subject that is no longer being shown must not keep: the nonce, because
     * there is nothing standing for it to authorise (D-21, invariant 56), and the two stamps,
     * because they exist to suppress a repeat of an announcement this subject is no longer making.
     * The snooze is **kept** — a snoozed schedule that goes out of season and comes back is still
     * snoozed until its instant passes.
     */
    private fun ScheduleLocalDelivery.forgotten(nowMillis: Long): ScheduleLocalDelivery = copy(
        lastNotifiedAt = null,
        firstEntrySeen = false,
        actionNonce = null,
        nonceIssuedAt = null,
        updatedAt = nowMillis,
    )
}

/**
 * The notification tag: the schedule id and the subject's content hash, separated by a character
 * neither can contain.
 *
 * **#79 (C4, K2): one codec for both families.** A schedule's tag is written byte-for-byte as 1.2
 * wrote it, `<scheduleId>|<hash16>`, so no standing maintenance reminder moves on upgrade; a
 * deadline's is `<KIND>:<subjectId>|<hash16>`, whose kind prefix is what keeps it from ever being
 * read as a schedule's. [keyOfTag] is the only reader, and every question about a standing tag —
 * which key it is, whether it is kept, whose nonce it held — goes through it.
 *
 * Putting the hash in the **tag** is what makes this provider stateless. "Is this subject already
 * showing, in this exact form?" is then answered by the notification shade rather than by anything
 * the provider persisted, so a cleared app rebuilds its whole posted set from schedule state alone
 * (invariant 44) and a second reconcile of the same list is inert even in a fresh process
 * (invariant 45). The hash is truncated because a tag is an identity, not a checksum, and 64 bits
 * of a SHA-256 is more than enough to distinguish two versions of one subject.
 */
internal fun itemTag(key: SubjectKey, contentHash: String): String = when (key) {
    is SubjectKey.Schedule -> "${key.scheduleId.value}$TAG_SEPARATOR${contentHash.take(16)}"
    is SubjectKey.Deadline -> "${key.kind.name}$KIND_SEPARATOR${key.subjectId}$TAG_SEPARATOR${contentHash.take(16)}"
}

/**
 * The key a standing tag was written from, or null for a tag this codec never wrote. The hash is
 * hex, so the key is everything before the **last** separator.
 *
 * It assumes no schedule id begins with `<DeadlineKind>:` — a schedule id literally beginning
 * `WARRANTY_EXPIRY:` would be read as a warranty tag. Every producer mints UUIDs, so only a
 * hand-edited archive could reach it; a schedule tag must stay byte-identical, so no namespace is
 * added here.
 */
internal fun keyOfTag(tag: String): SubjectKey? {
    if (TAG_SEPARATOR !in tag) return null
    val head = tag.substringBeforeLast(TAG_SEPARATOR)
    val kind = DeadlineKind.entries.firstOrNull { head.startsWith("${it.name}$KIND_SEPARATOR") }
        ?: return SubjectKey.Schedule(ScheduleId(head))
    return SubjectKey.Deadline(kind, head.removePrefix("${kind.name}$KIND_SEPARATOR"))
}

private const val TAG_SEPARATOR = '|'
private const val KIND_SEPARATOR = ':'

/** A meter threshold and the reading that crossed it, already formatted to the definition's decimals. */
data class MeterReading(val dueAt: String, val now: String, val unit: String)

/**
 * What a notification cannot be built from a [ReminderSubject] alone.
 *
 * The port carries the schedule's title and not its asset's name, and it carries no status word —
 * deliberately, because a provider with a recurrence engine of its own would derive both itself.
 * The local provider has no engine, so it reads these off the same schedule row and derived state
 * the engine wrote, through [statusOf][com.loosecannon.servicetag.core.schedule.statusOf] and never
 * by re-deriving due-ness from `dueOn`: a meter-only subject arrives Active with **no date at all**
 * and must still notify when its counter crosses (#21 AC 6).
 */
data class DeliveryFacts(
    /** The asset's name, or the group's: the `<asset>` slot of the ratified per-item title. */
    val ownerName: String,
    /** The derived status, from `statusOf`. */
    val status: DueStatus,
    /** Non-null when a meter threshold is what came due, which selects the meter body. */
    val meter: MeterReading?,
    /** D-7: a group-targeted schedule's notification offers "Open" only. */
    val groupTargeted: Boolean,
)

/** One subject the digest decides on: a schedule's, or — #79 (C7) — a deadline's. */
sealed interface DigestInput {
    val subject: ReminderSubject
}

/** One subject, the facts behind it, and its delivery row — which is very often absent. */
data class DeliveryInput(
    override val subject: ReminderSubject,
    val facts: DeliveryFacts?,
    val delivery: ScheduleLocalDelivery?,
) : DigestInput {
    /** The subject's key, which a schedule input's is by construction. */
    val key: SubjectKey.Schedule = requireNotNull(subject.key as? SubjectKey.Schedule) {
        "a schedule input carries a schedule subject"
    }
}

/**
 * #79 (C7): a deadline subject, what its warning needs that the port does not carry, and its
 * device-local stamp — which is absent until it is first announced.
 */
data class DeadlineInput(
    override val subject: ReminderSubject,
    val facts: DeadlineFacts?,
    val row: DeadlineLocalDelivery?,
) : DigestInput {
    /** The subject's key, which a deadline input's is by construction. */
    val key: SubjectKey.Deadline = requireNotNull(subject.key as? SubjectKey.Deadline) {
        "a deadline input carries a deadline subject"
    }
}

/**
 * #79 (C6): what a deadline's warning needs that the port does not carry — the asset's name for
 * the `<asset>` slot of the title, and the day the window is measured on.
 *
 * #72 (C10): a loan's more, each null for a warranty. They ride here and **never** in the subject's
 * body or hash, so a relink moves no tag and posts nothing twice.
 */
data class DeadlineFacts(
    val ownerName: String,
    val today: LocalDate,
    /** The loan's borrower, its display-name snapshot, for the body's `Lent to <name>`. */
    val borrower: String? = null,
    /** Epoch millis of the latest digest-hour instant at or before now: Until returned's period began here. */
    val cadenceSince: Long? = null,
    /**
     * Fix round 1 (R72-7): epoch millis of **today** at the owner's digest hour, in the device zone.
     * A loan posts, re-alerts or re-posts only at a sweep at or after it; before it, a loan is held.
     */
    val dayOpensAt: Long? = null,
)

/** One per-item notification. [tag] is its identity in the shade; [actions] are B07's to wire. */
data class ItemPost(
    val key: SubjectKey,
    val tag: String,
    val channelId: String,
    val title: String,
    val body: String,
    val statusWord: String,
    /** Whether a crossed meter threshold is what came due, which selects the icon. */
    val meter: Boolean,
    val actions: List<String>,
)

/** The one summary a run posts. [tag] changes exactly when the counts do. */
data class SummaryPost(val tag: String, val title: String, val body: String)

/**
 * What one run should do, as a value: the provider executes it and writes nothing else.
 *
 * [shown] is every subject that should have a standing per-item notification afterwards — the
 * keep-set [cancelTags] is derived from — and [posts] is the subset that has to be handed to the
 * platform on this run. The difference between the two has **two** cases, not one:
 *
 *  - a subject **already showing in exactly this form**, which is the whole of invariant 45, and
 *    which is what [ReconcileReport.unchanged] counts;
 *  - an OVERDUE subject the **three-day gate suppressed while nothing of it was standing**,
 *    because the owner swiped the last one away. It is kept — the obligation has not gone — but
 *    it is in neither count, because nothing of it is showing.
 *
 * Keeping the second case out of `unchanged` is what preserves the identity
 * `posted + unchanged` = what is held.
 */
data class DigestDecision(
    val posts: List<ItemPost>,
    val shown: List<ItemPost>,
    val summary: SummaryPost?,
    val cancelTags: List<String>,
    val cancelSummary: Boolean,
    val rows: List<ScheduleLocalDelivery>,
    /** #79 (C7): the deadline stamps to write, one per warning announced on this run. */
    val deadlineRows: List<DeadlineLocalDelivery>,
    /** #79 (C7): the deadline stamps to forget — a warning that left its window or its asset. */
    val deadlineForgotten: List<SubjectKey.Deadline>,
    val report: ReconcileReport,
)
