package com.loosecannon.servicetag.reminders

import com.loosecannon.servicetag.core.ports.ScheduleLocalDelivery
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
     */
    fun decide(
        inputs: List<DeliveryInput>,
        standingTags: Set<String>,
        standingSummaryTag: String?,
        nowMillis: Long,
        channelDelivers: (String) -> Boolean = { true },
    ): DigestDecision {
        val shown = mutableListOf<ItemPost>()
        val posts = mutableListOf<ItemPost>()
        val rows = mutableListOf<ScheduleLocalDelivery>()
        var overdue = 0
        var due = 0
        var dueSoon = 0
        var unchanged = 0

        inputs.forEach { input ->
            val subject = input.subject
            val row = input.delivery
            val facts = input.facts
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
                        rows += (row ?: blankRow(subject.key, nowMillis)).copy(
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
                    val replaced = !standing && standingTags.any { it.scheduleIdOfTag() == post.tag.scheduleIdOfTag() }
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
                        rows += (row ?: blankRow(subject.key, nowMillis)).copy(
                            lastNotifiedAt = nowMillis,
                            updatedAt = nowMillis,
                        )
                    }
                }
                // `notifies` is true for exactly the three above; the rest were folded out already.
                else -> Unit
            }
        }

        val keptIds = shown.map { it.tag.scheduleIdOfTag() }.toSet()
        val keptTags = shown.map { it.tag }.toSet()
        val cancelTags = standingTags.filterNot { it in keptTags }
        val cleared = standingTags.count { it.scheduleIdOfTag() !in keptIds }

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
            report = ReconcileReport(
                posted = shown.size - unchanged,
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

    /** A meter with no unit at all (a pH definition) leaves the `<unit>` slot empty rather than doubling a space. */
    private fun meterBody(meter: MeterReading): String {
        val at = if (meter.unit.isBlank()) meter.dueAt else "${meter.dueAt} ${meter.unit}"
        return "Due at $at, now ${meter.now}."
    }

    private fun LocalDate.display(): String = format(DISPLAY_DATE)

    private fun blankRow(key: SubjectKey, nowMillis: Long) = ScheduleLocalDelivery(
        scheduleId = (key as SubjectKey.Schedule).scheduleId,
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
 * Putting the hash in the **tag** is what makes this provider stateless. "Is this subject already
 * showing, in this exact form?" is then answered by the notification shade rather than by anything
 * the provider persisted, so a cleared app rebuilds its whole posted set from schedule state alone
 * (invariant 44) and a second reconcile of the same list is inert even in a fresh process
 * (invariant 45). The hash is truncated because a tag is an identity, not a checksum, and 64 bits
 * of a SHA-256 is more than enough to distinguish two versions of one subject.
 */
internal fun itemTag(key: SubjectKey, contentHash: String): String =
    "${(key as SubjectKey.Schedule).scheduleId.value}$TAG_SEPARATOR${contentHash.take(16)}"

internal fun String.scheduleIdOfTag(): String = substringBefore(TAG_SEPARATOR)

private const val TAG_SEPARATOR = '|'

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

/** One subject, the facts behind it, and its delivery row — which is very often absent. */
data class DeliveryInput(
    val subject: ReminderSubject,
    val facts: DeliveryFacts?,
    val delivery: ScheduleLocalDelivery?,
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
 * [shown] is every subject that should have a standing per-item notification afterwards, and
 * [posts] is the subset that has to be handed to the platform on this run — the difference is a
 * subject already showing in exactly this form, which is the whole of invariant 45.
 */
data class DigestDecision(
    val posts: List<ItemPost>,
    val shown: List<ItemPost>,
    val summary: SummaryPost?,
    val cancelTags: List<String>,
    val cancelSummary: Boolean,
    val rows: List<ScheduleLocalDelivery>,
    val report: ReconcileReport,
)
