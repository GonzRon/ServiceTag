package com.loosecannon.servicetag.reminders

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.ports.DeadlineLocalDelivery
import com.loosecannon.servicetag.core.ports.ScheduleLocalDelivery
import com.loosecannon.servicetag.core.reminders.BuildDeadlineSubjects
import com.loosecannon.servicetag.core.reminders.ContentHash
import com.loosecannon.servicetag.core.reminders.DeadlineKind
import com.loosecannon.servicetag.core.reminders.DeadlineRepeat
import com.loosecannon.servicetag.core.reminders.ProviderId
import com.loosecannon.servicetag.core.reminders.ReminderSubject
import com.loosecannon.servicetag.core.reminders.RuleFacts
import com.loosecannon.servicetag.core.reminders.SubjectKey
import com.loosecannon.servicetag.core.reminders.SubjectState
import com.loosecannon.servicetag.core.schedule.DueStatus
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fixture vocabulary: fictional nouns only, as every fixture in this repository is. */
internal object Fixture {
    val TODAY: LocalDate = LocalDate.parse("2026-06-15")
    const val NOW = 1_781_000_000_000L
    val DISPLAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM uuuu")

    fun subject(
        id: String,
        dueOn: String?,
        title: String = "Filter change",
        state: SubjectState = SubjectState.Active,
        hasMeter: Boolean = false,
        stamp: String = "v1",
    ): ReminderSubject {
        val rule = RuleFacts(TimeBasis.FIXED, 3, RecurrenceUnit.MONTH, hasMeter = hasMeter, seasonal = false)
        val due = dueOn?.let(LocalDate::parse)
        // The body is the port's own display line; the stamp stands in for whatever moved in it, so
        // a test can move the content hash without pretending to know how the builder composes it.
        val body = if (state == SubjectState.Withdrawn) "" else stamp
        return ReminderSubject(
            key = SubjectKey.Schedule(ScheduleId(id)),
            title = title,
            body = body,
            dueOn = due,
            leadDays = 14,
            state = state,
            rule = rule,
            contentHash = ContentHash.of(title, body, due, 14, state, rule),
        )
    }

    fun facts(
        status: DueStatus,
        ownerName: String = "Pump house filter",
        meter: MeterReading? = null,
        groupTargeted: Boolean = false,
    ) = DeliveryFacts(ownerName, status, meter, groupTargeted)

    /** #79: a warranty subject exactly as `BuildDeadlineSubjects` builds one. */
    fun warranty(assetId: String, expiresOn: String, lead: Int = 30): ReminderSubject {
        val due = LocalDate.parse(expiresOn)
        return ReminderSubject(
            key = SubjectKey.Deadline(DeadlineKind.WARRANTY_EXPIRY, assetId),
            title = "Warranty",
            body = "",
            dueOn = due,
            leadDays = lead,
            state = SubjectState.Active,
            rule = null,
            contentHash = ContentHash.of("Warranty", "", due, lead, SubjectState.Active, null, DeadlineRepeat.ONCE),
            repeat = DeadlineRepeat.ONCE,
        )
    }

    fun warrantyFacts(today: LocalDate, ownerName: String = "Example Heater") = DeadlineFacts(ownerName, today)

    /** The stamp a warning leaves once it has been announced in [boot]. */
    fun stamp(subject: ReminderSubject, boot: Int? = 1) = DeadlineLocalDelivery(
        kind = "WARRANTY_EXPIRY",
        subjectId = (subject.key as SubjectKey.Deadline).subjectId,
        announcedHash = subject.contentHash,
        announcedBoot = boot,
        updatedAt = NOW,
    )

    fun row(
        id: String,
        snoozedUntilAt: Long? = null,
        lastNotifiedAt: Long? = null,
        firstEntrySeen: Boolean = false,
        actionNonce: String? = null,
    ) = ScheduleLocalDelivery(
        scheduleId = ScheduleId(id),
        snoozedUntilAt = snoozedUntilAt,
        lastNotifiedAt = lastNotifiedAt,
        firstEntrySeen = firstEntrySeen,
        actionNonce = actionNonce,
        nonceIssuedAt = actionNonce?.let { NOW },
        updatedAt = NOW,
    )
}

/**
 * D-5's four rules, the owner's ratified wording, and the arithmetic the amendment made assertable.
 *
 * Every row here is a deterministic call on a pure function: `now` is a constant, the delivery rows
 * are values, and "what is already showing" is a set of tags. Nothing waits on a wall clock, which
 * is the whole reason the policy is a separate layer from the provider.
 */
class DigestPolicyTest {

    private fun decide(
        inputs: List<DigestInput>,
        standing: Set<String> = emptySet(),
        standingSummary: String? = null,
        now: Long = Fixture.NOW,
        muted: Set<String> = emptySet(),
        boot: Int? = 1,
    ) = DigestPolicy.decide(inputs, standing, standingSummary, now, boot) { it !in muted }

    /**
     * The matrix's "the digest shouting" row, and #21 AC 1's off-device half. A schedule due
     * tomorrow with a fortnight's lead is DUE SOON: it is announced in the **summary** and nowhere
     * else, so the run produces **exactly one** notification. A per-item post beside the summary,
     * or a per-item post with no summary, both double it — and a DUE SOON schedule notified
     * per-item every run for a fortnight is the fatigue #26 exists to fix later.
     */
    @Test
    fun aDueSoonScheduleProducesExactlyOneNotificationForTheRun() {
        val decision = decide(
            listOf(
                DeliveryInput(
                    subject = Fixture.subject("s1", "2026-06-16"),
                    facts = Fixture.facts(DueStatus.DUE_SOON),
                    delivery = null,
                ),
            ),
        )

        assertEquals(emptyList<ItemPost>(), decision.posts)
        assertEquals("1 maintenance items need attention", decision.summary?.title)
        assertEquals("1 due soon.", decision.summary?.body)
        assertEquals(1, listOfNotNull(decision.summary).size + decision.posts.size)
        assertTrue("the one announcement is recorded so it is not made again", decision.rows.single().firstEntrySeen)
    }

    /**
     * The matrix's "the digest's title and body disagreeing" row, which is the owner's amendment
     * made assertable: two OVERDUE, one DUE and one first-entry DUE SOON reads
     * **"2 overdue, 1 due, 1 due soon."** with the title counting **four**, not three. A title that
     * counted only DUE and OVERDUE while the body listed three classes would contradict itself in
     * the one glance a notification gets.
     */
    @Test
    fun theTitleCountsEveryItemTheBodyRepresentsIncludingFirstEntryDueSoon() {
        val decision = decide(
            listOf(
                DeliveryInput(Fixture.subject("s1", "2026-06-01"), Fixture.facts(DueStatus.OVERDUE), null),
                DeliveryInput(Fixture.subject("s2", "2026-06-02"), Fixture.facts(DueStatus.OVERDUE), null),
                DeliveryInput(Fixture.subject("s3", "2026-06-15"), Fixture.facts(DueStatus.DUE), null),
                DeliveryInput(Fixture.subject("s4", "2026-06-20"), Fixture.facts(DueStatus.DUE_SOON), null),
            ),
        )

        assertEquals("4 maintenance items need attention", decision.summary?.title)
        assertEquals("2 overdue, 1 due, 1 due soon.", decision.summary?.body)
    }

    /** The same ratified form with the zero-count clauses omitted: "2 overdue, 1 due." */
    @Test
    fun aZeroCountClauseIsOmitted() {
        assertEquals("2 overdue, 1 due.", DigestPolicy.summaryBody(overdue = 2, due = 1, dueSoon = 0))
        assertEquals("1 due soon.", DigestPolicy.summaryBody(overdue = 0, due = 0, dueSoon = 1))
        assertEquals("3 overdue.", DigestPolicy.summaryBody(overdue = 3, due = 0, dueSoon = 0))
        assertEquals("1 overdue, 2 due, 3 due soon.", DigestPolicy.summaryBody(1, 2, 3))
    }

    /**
     * One summary per run means **one standing summary**, not one per set of counts. A summary
     * carries its counts in its tag so an unchanged one is never re-announced; the price is that a
     * changed one must cancel the standing one rather than appear beside it, or the owner is shown
     * two summaries disagreeing about how much needs attention.
     */
    @Test
    fun aSummaryWhoseCountsMovedReplacesTheStandingOneRatherThanJoiningIt() {
        val two = decide(
            listOf(
                DeliveryInput(Fixture.subject("s1", "2026-06-01"), Fixture.facts(DueStatus.OVERDUE), null),
                DeliveryInput(Fixture.subject("s2", "2026-06-15"), Fixture.facts(DueStatus.DUE), null),
            ),
        )
        val standing = two.summary!!.tag

        val one = decide(
            inputs = listOf(DeliveryInput(Fixture.subject("s1", "2026-06-01"), Fixture.facts(DueStatus.OVERDUE), null)),
            standingSummary = standing,
        )
        assertTrue("the stale summary is cancelled", one.cancelSummary)
        assertEquals("1 overdue.", one.summary?.body)

        val same = decide(
            inputs = listOf(DeliveryInput(Fixture.subject("s1", "2026-06-01"), Fixture.facts(DueStatus.OVERDUE), null)),
            standingSummary = one.summary!!.tag,
        )
        assertEquals("an unchanged summary is neither cancelled nor re-announced", false, same.cancelSummary)
        assertEquals(null, same.summary)

        val none = decide(inputs = emptyList(), standingSummary = standing)
        assertTrue(none.cancelSummary)
        assertEquals(null, none.summary)
    }

    /** The ratified per-item forms, both date shapes, verbatim (master plan §17.1e). */
    @Test
    fun theRatifiedPerItemTitleAndBodies() {
        val due = decide(
            listOf(DeliveryInput(Fixture.subject("s1", "2026-06-15"), Fixture.facts(DueStatus.DUE), null)),
        ).posts.single()
        assertEquals("Pump house filter — Filter change", due.title)
        assertEquals("Due ${LocalDate.parse("2026-06-15").format(Fixture.DISPLAY)}.", due.body)
        assertEquals(DigestPolicy.WORD_DUE, due.statusWord)
        assertEquals(NotificationChannels.DUE, due.channelId)

        val overdue = decide(
            listOf(DeliveryInput(Fixture.subject("s2", "2026-05-30"), Fixture.facts(DueStatus.OVERDUE), null)),
        ).posts.single()
        assertEquals("Overdue since ${LocalDate.parse("2026-05-30").format(Fixture.DISPLAY)}.", overdue.body)
        assertEquals(DigestPolicy.WORD_OVERDUE, overdue.statusWord)
        // Plan decision 40: OVERDUE on the HIGH channel, DUE and DUE SOON on the DEFAULT one.
        assertEquals(NotificationChannels.OVERDUE, overdue.channelId)
    }

    /**
     * The matrix's "a meter reading not noticed" row, and #21 AC 6. A meter-only schedule arrives
     * **Active and dateless**, because there is no date to give it — so due-ness comes from the
     * status the engine derived and not from `dueOn`, and the body is the ratified meter form. A
     * date-shaped policy would leave this subject silent for its whole life.
     */
    @Test
    fun aMeterOnlySubjectIsDatelessAndStillNotifies() {
        val post = decide(
            listOf(
                DeliveryInput(
                    subject = Fixture.subject("s1", dueOn = null, title = "Impeller service", hasMeter = true),
                    facts = Fixture.facts(
                        DueStatus.DUE,
                        ownerName = "Transfer pump",
                        meter = MeterReading(dueAt = "500", now = "512", unit = "hours"),
                    ),
                    delivery = null,
                ),
            ),
        ).posts.single()

        assertEquals("Transfer pump — Impeller service", post.title)
        assertEquals("Due at 500 hours, now 512.", post.body)
    }

    /**
     * The matrix's "a snoozed schedule notified" row, and invariant 20. While snoozed the subject
     * produces **nothing** — no per-item post, no count in any summary clause — and the policy
     * writes no row for it, so the snooze keeps its own instant rather than being overwritten by
     * the run that found it.
     *
     * That a snooze moves no `*_on` column and creates no event is structural rather than asserted
     * here: `DigestPolicy` can only return `ScheduleLocalDelivery` rows, and `ReminderSnooze` writes
     * one column of one device-local row, which
     * `LocalReminderProviderTest.theSnoozeAndTheNonceWriteOneDeviceLocalRowAndNothingElse` proves.
     * Two assertions that compared the fixture with itself used to stand here and read as proof;
     * they are gone (fix round 1, nit 8). The **nonce** is a separate question: when a snooze
     * cancels a standing notification, `LocalReminderProvider.clearNoncesFor` clears it, correctly,
     * per D-21.
     */
    @Test
    fun aSnoozedSubjectProducesNothingAndKeepsItsDateAndItsRow() {
        val subject = Fixture.subject("s1", "2026-05-30")
        val row = Fixture.row("s1", snoozedUntilAt = Fixture.NOW + 60_000L, actionNonce = "n1")

        val decision = decide(listOf(DeliveryInput(subject, Fixture.facts(DueStatus.OVERDUE), row)))

        assertEquals(emptyList<ItemPost>(), decision.posts)
        assertEquals(null, decision.summary)
        assertEquals("the policy writes no row, so the snooze keeps its own instant", emptyList<Any>(), decision.rows)

        // The instant passing is all it takes; nothing had to clear the column.
        val after = decide(
            listOf(DeliveryInput(subject, Fixture.facts(DueStatus.OVERDUE), row)),
            now = row.snoozedUntilAt!! + 1L,
        )
        assertEquals(1, after.posts.size)
    }

    /**
     * The matrix's "a parked schedule left standing" row, and invariant 47. A paused or out-of-season
     * subject posts nothing **and its standing notification is cleared**: leaving it up is a paused
     * schedule that keeps nagging with nothing to act on. Its bookkeeping is forgotten with it, so
     * the day it comes back it announces rather than being suppressed by a stale stamp.
     */
    @Test
    fun aParkedSubjectPostsNothingAndItsStandingNotificationIsCleared() {
        val standing = Fixture.subject("s1", "2026-05-30")
        val parked = Fixture.subject("s1", null, state = SubjectState.Parked(LocalDate.parse("2026-09-01")))

        val decision = decide(
            inputs = listOf(
                DeliveryInput(parked, Fixture.facts(DueStatus.INACTIVE_SEASON), Fixture.row("s1", lastNotifiedAt = Fixture.NOW)),
            ),
            standing = setOf(itemTag(standing.key, standing.contentHash)),
        )

        assertEquals(emptyList<ItemPost>(), decision.posts)
        assertEquals(listOf(itemTag(standing.key, standing.contentHash)), decision.cancelTags)
        assertEquals(1, decision.report.cleared)
        assertEquals(null, decision.rows.single().lastNotifiedAt)
    }

    /**
     * Carry-forward (c): an `Active` subject with **no date and no meter side** — an emptied group
     * whose round requires nobody — posts nothing and counts in no digest bucket. Due-ness comes
     * from the derived status, which for that schedule is `NO_DATA`, and `NO_DATA` does not notify.
     */
    @Test
    fun anActiveSubjectWithNeitherADateNorAMeterSidePostsNothingAndCountsNowhere() {
        val decision = decide(
            listOf(
                DeliveryInput(
                    subject = Fixture.subject("s1", dueOn = null, hasMeter = false),
                    facts = Fixture.facts(DueStatus.NO_DATA),
                    delivery = null,
                ),
            ),
        )

        assertEquals(emptyList<ItemPost>(), decision.posts)
        assertEquals(null, decision.summary)
        assertEquals(ReconcileCounters(0, 0, 0), decision.report.counters())
    }

    /**
     * The matrix's "overdue re-notification drift" row (D-5). An overdue subject re-announces at
     * **three days** and not a run before; a DUE SOON subject is announced **once** on first entry
     * and never again while it stays there. Re-notifying every run is exactly the fatigue this
     * brief promises not to cause.
     */
    @Test
    fun anOverdueSubjectReNotifiesAtThreeDaysAndDueSoonOnlyOnFirstEntry() {
        val subject = Fixture.subject("s1", "2026-05-30")
        val tag = itemTag(subject.key, subject.contentHash)
        val facts = Fixture.facts(DueStatus.OVERDUE)

        fun postsAfter(elapsed: Long) = decide(
            inputs = listOf(DeliveryInput(subject, facts, Fixture.row("s1", lastNotifiedAt = Fixture.NOW))),
            standing = setOf(tag),
            now = Fixture.NOW + elapsed,
        ).posts.size

        assertEquals("the same run again posts nothing", 0, postsAfter(0L))
        assertEquals("a day short of three posts nothing", 0, postsAfter(DigestPolicy.RENOTIFY_MILLIS - 1L))
        assertEquals("three days on it announces again", 1, postsAfter(DigestPolicy.RENOTIFY_MILLIS))

        // Swiped away: the notification has left `activeNotifications`, so nothing is standing —
        // and the three-day gate is driven by `last_notified_at`, not by what is in the shade
        // (fix round 1, finding 3). Re-posting on the very next run, i.e. within 12 h, is exactly
        // the every-run fatigue #26 exists to fix later and #21 promises not to cause now.
        val swipedAway = decide(
            inputs = listOf(DeliveryInput(subject, facts, Fixture.row("s1", lastNotifiedAt = Fixture.NOW))),
            standing = emptySet(),
            now = Fixture.NOW + DigestPolicy.RENOTIFY_MILLIS - 1L,
        )
        assertEquals("a dismissed overdue reminder does not come back before three days", 0, swipedAway.posts.size)
        assertEquals("it is still counted: the obligation has not gone away", "1 overdue.", swipedAway.summary?.body)
        // Finding 15: the counters report what happened. Nothing was posted and nothing is showing,
        // so `posted + unchanged` is zero — which is the identity the port documents ("what the
        // provider holds after the call"). `shown.size - unchanged` reported a post that did not
        // happen.
        assertEquals(0, swipedAway.report.posted)
        assertEquals(0, swipedAway.report.unchanged)

        // …and a DUE subject is untouched by the rule: it is due for one day and then it is overdue.
        val dueSwipedAway = decide(
            listOf(
                DeliveryInput(
                    Fixture.subject("s3", "2026-06-15"),
                    Fixture.facts(DueStatus.DUE),
                    Fixture.row("s3", lastNotifiedAt = Fixture.NOW),
                ),
            ),
        )
        assertEquals(1, dueSwipedAway.posts.size)

        // A subject whose content **moved** is a replacement, not a re-announcement: its stale
        // notification is about to be cancelled, so suppressing the new one would leave the owner
        // with nothing in the shade for up to three days.
        val moved = Fixture.subject("s1", "2026-05-30", stamp = "v2")
        val replaced = decide(
            inputs = listOf(DeliveryInput(moved, facts, Fixture.row("s1", lastNotifiedAt = Fixture.NOW))),
            standing = setOf(tag),
            now = Fixture.NOW + 60_000L,
        )
        assertEquals(1, replaced.posts.size)
        assertEquals(listOf(tag), replaced.cancelTags)

        val dueSoon = Fixture.subject("s2", "2026-06-20")
        val seen = decide(
            listOf(DeliveryInput(dueSoon, Fixture.facts(DueStatus.DUE_SOON), Fixture.row("s2", firstEntrySeen = true))),
        )
        assertEquals("already announced: it is not counted again", null, seen.summary)
        assertEquals(emptyList<ScheduleLocalDelivery>(), seen.rows)
    }

    /**
     * The matrix's "an empty delivery table" row (invariant 44's real meaning, spec §2.5). With no
     * row at all the policy must not crash, must not skip a due subject, and must never read the
     * absence as a snooze — the failure that makes a wiped table silence the app.
     *
     * This is the behavioural test B04 could not write, because the table did not exist for it.
     */
    @Test
    fun anEmptyDeliveryTableAnnouncesRatherThanSilences() {
        val overdue = Fixture.subject("s1", "2026-05-30")
        val dueSoon = Fixture.subject("s2", "2026-06-20")

        val decision = decide(
            listOf(
                DeliveryInput(overdue, Fixture.facts(DueStatus.OVERDUE), delivery = null),
                DeliveryInput(dueSoon, Fixture.facts(DueStatus.DUE_SOON), delivery = null),
            ),
        )

        assertEquals(1, decision.posts.size)
        assertEquals("1 overdue, 1 due soon.", decision.summary?.body)
        assertEquals(
            "the run writes the rows it had to invent, so the next one is quiet",
            listOf("s1", "s2"),
            decision.rows.map { it.scheduleId.value }.sorted(),
        )

        // The same list with a populated row is the other half of the pair: the rows are what
        // change the answer, which is what makes the empty case a distinct hazard at all.
        val populated = decide(
            listOf(
                DeliveryInput(overdue, Fixture.facts(DueStatus.OVERDUE), Fixture.row("s1", lastNotifiedAt = Fixture.NOW)),
                DeliveryInput(dueSoon, Fixture.facts(DueStatus.DUE_SOON), Fixture.row("s2", firstEntrySeen = true)),
            ),
            standing = setOf(itemTag(overdue.key, overdue.contentHash)),
        )
        assertEquals(emptyList<ItemPost>(), populated.posts)
        assertEquals("1 overdue.", populated.summary?.body)
    }

    /**
     * Muting one channel must not silence the other (fix round 1, finding 2).
     *
     * An owner who sets "Maintenance due" to *None* in system settings has silenced the DEFAULT
     * channel and nothing else, so the OVERDUE item still goes out on the un-muted HIGH channel —
     * a notification Android itself would have delivered. Spec §5.5 makes a muted channel
     * *detectable*; it does not make one muted channel a global mute, and D-22's principle is that
     * a platform refusal is surfaced and never widened.
     *
     * The muted item is still **counted** — the obligation is real and the digest must not
     * under-report it — but it is not stamped, because a `last_notified_at` for an announcement
     * nobody received would suppress the real one for three days once the owner un-muted.
     */
    @Test
    fun aMutedChannelSkipsOnlyItsOwnPosts() {
        val decision = decide(
            inputs = listOf(
                DeliveryInput(Fixture.subject("s1", "2026-05-30"), Fixture.facts(DueStatus.OVERDUE), null),
                DeliveryInput(Fixture.subject("s2", "2026-06-15"), Fixture.facts(DueStatus.DUE), null),
            ),
            muted = setOf(NotificationChannels.DUE),
        )

        assertEquals(
            "the overdue item is still posted on the un-muted channel",
            listOf(NotificationChannels.OVERDUE),
            decision.posts.map { it.channelId },
        )
        assertEquals("the summary rides the muted channel, so it is skipped", null, decision.summary)
        assertEquals(
            "only the item that was actually announced is stamped",
            listOf("s1"),
            decision.rows.map { it.scheduleId.value },
        )

        // The mirror image: muting the HIGH channel loses the overdue item and keeps the due one
        // and the digest.
        val overdueMuted = decide(
            inputs = listOf(
                DeliveryInput(Fixture.subject("s1", "2026-05-30"), Fixture.facts(DueStatus.OVERDUE), null),
                DeliveryInput(Fixture.subject("s2", "2026-06-15"), Fixture.facts(DueStatus.DUE), null),
            ),
            muted = setOf(NotificationChannels.OVERDUE),
        )
        assertEquals(listOf(NotificationChannels.DUE), overdueMuted.posts.map { it.channelId })
        assertEquals(
            "and the digest still counts both, because both still need attention",
            "1 overdue, 1 due.",
            overdueMuted.summary?.body,
        )
    }

    /**
     * The matrix's "a group notification claiming completion" row (D-7). A group-targeted
     * schedule's notification offers **"Open"** and nothing else: "Done" on a group either
     * completes nothing or falsely completes everyone.
     */
    @Test
    fun aGroupTargetedSubjectOffersOpenOnly() {
        val group = decide(
            listOf(
                DeliveryInput(
                    Fixture.subject("s1", "2026-06-15", title = "Winterise"),
                    Fixture.facts(DueStatus.DUE, ownerName = "Dock line", groupTargeted = true),
                    null,
                ),
            ),
        ).posts.single()
        assertEquals(listOf("Open"), group.actions)

        val asset = decide(
            listOf(DeliveryInput(Fixture.subject("s2", "2026-06-15"), Fixture.facts(DueStatus.DUE), null)),
        ).posts.single()
        assertEquals(listOf("Done", "Snooze 1 day", "Open"), asset.actions)
    }

    // #79 (C4, K2): one tag codec for both families, the schedule half byte-identical to 1.2's.

    /** The shipped form, `<scheduleId>|<hash16>`, exactly: a moved schedule tag re-posts every reminder. */
    @Test
    fun aScheduleTagIsByteIdenticalToTheBaseForm() {
        val hash = "0123456789abcdef0123456789abcdef"
        assertEquals("sched-1|0123456789abcdef", itemTag(SubjectKey.Schedule(ScheduleId("sched-1")), hash))
        assertEquals(
            "$UUID_1|0123456789abcdef",
            itemTag(SubjectKey.Schedule(ScheduleId(UUID_1)), hash),
        )
    }

    /** `keyOfTag` is `itemTag`'s only reader, and reads back exactly the key it was written from. */
    @Test
    fun everyKeyRoundTripsThroughItsTag() {
        listOf(
            SubjectKey.Schedule(ScheduleId(UUID_1)),
            SubjectKey.Schedule(ScheduleId("sched-1")),
            SubjectKey.Deadline(DeadlineKind.WARRANTY_EXPIRY, UUID_2),
            SubjectKey.Deadline(DeadlineKind.WARRANTY_EXPIRY, "a1"),
        ).forEach { key -> assertEquals(key, keyOfTag(itemTag(key, "fedcba9876543210ffff"))) }
    }

    /** A warning's tag carries its kind, so it is never read as a schedule's. */
    @Test
    fun aDeadlineTagNeverReadsAsASchedule() {
        val key = SubjectKey.Deadline(DeadlineKind.WARRANTY_EXPIRY, "a1")
        val tag = itemTag(key, "0123456789abcdef0123")

        assertEquals("WARRANTY_EXPIRY:a1|0123456789abcdef", tag)
        assertEquals(key, keyOfTag(tag))
        assertNull("a tag with no separator is nobody's", keyOfTag("no-separator"))
    }

    // #79 (C7, R79-14a): a warranty warning, once per content on entering its window.

    /** Lead 30: nothing the day before the window opens, one warning the day it opens, and none after. */
    @Test
    fun aWarrantyIsPostedOnceOnEnteringItsWindow() {
        val subject = Fixture.warranty("a1", "2031-06-30", lead = 30)
        var standing = emptySet<String>()
        var row: DeadlineLocalDelivery? = null

        val postsByDay = (-31..0).associateWith { offset ->
            val decision = decide(
                listOf(DeadlineInput(subject, Fixture.warrantyFacts(EXPIRY.plusDays(offset.toLong())), row)),
                standing = standing,
            )
            standing = standing - decision.cancelTags.toSet() + decision.posts.map { it.tag }
            row = decision.deadlineRows.singleOrNull() ?: row.takeUnless { subject.key in decision.deadlineForgotten }
            decision.posts.size
        }

        assertEquals(0, postsByDay.getValue(-31))
        assertEquals(1, postsByDay.getValue(-30))
        assertEquals(0, postsByDay.getValue(-29))
        assertEquals("one warning in the whole run of days", 1, postsByDay.values.sum())
        assertEquals(setOf(itemTag(subject.key, subject.contentHash)), standing)
    }

    /**
     * Standing: held and counted unchanged. Swiped in the same boot: nothing — the stamp says this
     * content was announced, and the owner took it away.
     */
    @Test
    fun aStandingOrSwipedWarningIsNotReposted() {
        val subject = Fixture.warranty("a1", "2031-06-30")
        val facts = Fixture.warrantyFacts(EXPIRY.minusDays(10))
        val tag = itemTag(subject.key, subject.contentHash)

        val standing = decide(listOf(DeadlineInput(subject, facts, Fixture.stamp(subject))), standing = setOf(tag))
        assertEquals(emptyList<ItemPost>(), standing.posts)
        assertEquals(emptyList<String>(), standing.cancelTags)
        assertEquals(ReconcileCounters(0, 0, 1), standing.report.counters())
        assertEquals(emptyList<DeadlineLocalDelivery>(), standing.deadlineRows)

        val swiped = decide(listOf(DeadlineInput(subject, facts, Fixture.stamp(subject))))
        assertEquals(emptyList<ItemPost>(), swiped.posts)
        assertEquals(ReconcileCounters(0, 0, 0), swiped.report.counters())
        assertEquals(emptyList<DeadlineLocalDelivery>(), swiped.deadlineRows)
        assertEquals(emptyList<SubjectKey.Deadline>(), swiped.deadlineForgotten)
    }

    /** A moved lead or date is new content: the old warning is cancelled and the new one posted and stamped. */
    @Test
    fun aMovedDateOrLeadReplacesIt() {
        val before = Fixture.warranty("a1", "2031-06-30", lead = 30)
        val oldTag = itemTag(before.key, before.contentHash)
        val facts = Fixture.warrantyFacts(EXPIRY.minusDays(10))

        listOf(Fixture.warranty("a1", "2031-06-30", lead = 20), Fixture.warranty("a1", "2031-07-05", lead = 30))
            .forEach { moved ->
                val decision = decide(listOf(DeadlineInput(moved, facts, Fixture.stamp(before))), standing = setOf(oldTag))

                assertEquals(listOf(itemTag(moved.key, moved.contentHash)), decision.posts.map { it.tag })
                assertEquals(listOf(oldTag), decision.cancelTags)
                assertEquals(listOf(moved.contentHash), decision.deadlineRows.map { it.announcedHash })
                assertEquals("re-shown, not let go of", ReconcileCounters(1, 0, 0), decision.report.counters())
            }
    }

    /**
     * Added beside the matrix: before its window, or with its asset gone, a warning is taken down
     * and its stamp forgotten, so a later entry announces again.
     */
    @Test
    fun outsideItsWindowOrWithoutItsAssetAWarningIsTakenDownAndForgotten() {
        val before = Fixture.warranty("a1", "2031-06-30", lead = 30)
        val oldTag = itemTag(before.key, before.contentHash)
        val shortened = Fixture.warranty("a1", "2031-06-30", lead = 5)

        listOf(
            DeadlineInput(shortened, Fixture.warrantyFacts(EXPIRY.minusDays(10)), Fixture.stamp(before)),
            DeadlineInput(before, null, Fixture.stamp(before)),
        ).forEach { input ->
            val decision = decide(listOf(input), standing = setOf(oldTag))

            assertEquals(emptyList<ItemPost>(), decision.posts)
            assertEquals(listOf(oldTag), decision.cancelTags)
            assertEquals(listOf(input.key), decision.deadlineForgotten)
            assertEquals(ReconcileCounters(0, 1, 0), decision.report.counters())
        }
    }

    /** P79-10, P79-11, P79-13's channel and C8's one action, on the warning the digest builds. */
    @Test
    fun theRatifiedTitleBodyWordChannelAndOneOpenAction() {
        val post = decide(
            listOf(DeadlineInput(Fixture.warranty("a1", "2031-06-30"), Fixture.warrantyFacts(EXPIRY.minusDays(3)), null)),
        ).posts.single()

        assertEquals(SubjectKey.Deadline(DeadlineKind.WARRANTY_EXPIRY, "a1"), post.key)
        assertEquals("Example Heater — Warranty", post.title)
        assertEquals("Warranty expires ${EXPIRY.format(Fixture.DISPLAY)}.", post.body)
        assertEquals("EXPIRES SOON", post.statusWord)
        assertEquals("warranty_reminders", post.channelId)
        assertEquals(listOf("Open"), post.actions)
        assertFalse("a date, not a meter: the clock icon", post.meter)
    }

    // #79 (C7, K3): never maintenance.

    /** A warning is never counted in the summary, alone or beside a schedule. */
    @Test
    fun aWarrantyNeverCountsInTheMaintenanceSummary() {
        val warranty = DeadlineInput(Fixture.warranty("a1", "2031-06-30"), Fixture.warrantyFacts(EXPIRY.minusDays(3)), null)

        val alone = decide(listOf(warranty))
        assertEquals(1, alone.posts.size)
        assertNull("nothing needs maintenance", alone.summary)

        val beside = decide(listOf(DeliveryInput(Fixture.subject("s1", "2026-06-15"), Fixture.facts(DueStatus.DUE), null), warranty))
        assertEquals(2, beside.posts.size)
        assertEquals("1 maintenance items need attention", beside.summary?.title)
        assertEquals("1 due.", beside.summary?.body)
    }

    /** The kept set covers both families: the schedule rules never take a standing warning down. */
    @Test
    fun theScheduleBranchNeverCancelsAStandingWarranty() {
        val warranty = Fixture.warranty("a1", "2031-06-30")
        val warrantyTag = itemTag(warranty.key, warranty.contentHash)
        val warrantyInput = DeadlineInput(warranty, Fixture.warrantyFacts(EXPIRY.minusDays(3)), Fixture.stamp(warranty))
        val schedule = Fixture.subject("s1", "2026-06-15")
        val scheduleTag = itemTag(schedule.key, schedule.contentHash)

        val both = decide(
            listOf(DeliveryInput(schedule, Fixture.facts(DueStatus.DUE), null), warrantyInput),
            standing = setOf(scheduleTag, warrantyTag),
        )
        assertEquals(emptyList<String>(), both.cancelTags)
        assertEquals(ReconcileCounters(0, 0, 2), both.report.counters())

        val scheduleGone = decide(listOf(warrantyInput), standing = setOf(scheduleTag, warrantyTag))
        assertEquals("only the schedule's own tag goes", listOf(scheduleTag), scheduleGone.cancelTags)
        assertEquals(ReconcileCounters(0, 1, 1), scheduleGone.report.counters())
    }

    // #72 (C14; K1, K2): the warranty warning is byte-identical across the loan work.

    /**
     * The warranty's whole day matrix — expiry −31 … +2, each day under five conditions — through the
     * real builder and the real `decide`, rendered field by field: the subject the builder hands over,
     * then every post's tag, channel, title, body, word, meter and actions, every tag kept, cancelled
     * or stamped, every stamp forgotten and the three counters. The expected text,
     * `warranty-day-matrix.txt`, was **recorded at #72 B2's base (9cb0b845) before any production
     * edit**; the warranty's `once`, post and window are never edited after it, so the same text at
     * any later commit is the claim that the loan work moved nothing a warranty does.
     *
     * The five conditions: nothing standing and no stamp; the warning standing (and stamped); swiped
     * but stamped in this boot; stamped in another boot (a restart took it down); and its channel
     * muted with nothing standing. The facts are built directly, not through the facts source, so
     * this test's own source need not move when that source's constructor does.
     */
    @Test
    fun theWarrantyDayMatrixIsTheBaseMatrix() = runTest {
        val actual = warrantyDayMatrix()
        val expected = DigestPolicyTest::class.java.getResource(WARRANTY_MATRIX)?.readText()
        if (expected != actual) {
            // For a reviewer's diff only; the assertion below is the verdict.
            File("build").takeIf { it.isDirectory }?.let { File(it, "warranty-day-matrix.actual.txt").writeText(actual) }
        }
        assertEquals("the warranty's day matrix is the one recorded at B2's base", expected, actual)
    }

    private suspend fun warrantyDayMatrix(): String {
        val assets = FakeAssetRepository()
        assets.upsert(
            Asset(
                id = AssetId("a1"),
                name = "Example Heater",
                createdAt = 1_000L,
                updatedAt = 1_000L,
                warrantyExpiresOn = EXPIRY.toString(),
                warrantyReminderLeadDays = 30,
            ),
        )
        val builder = BuildDeadlineSubjects(assets)
        val reference = builder.forProvider(ProviderId.LOCAL, EXPIRY).single()
        val referenceTag = itemTag(reference.key, reference.contentHash)
        val conditions = listOf(
            MatrixCondition("nothing standing", standing = false, row = null, muted = false),
            MatrixCondition("standing", standing = true, row = Fixture.stamp(reference, boot = 1), muted = false),
            MatrixCondition("stamped this boot", standing = false, row = Fixture.stamp(reference, boot = 1), muted = false),
            MatrixCondition("another boot", standing = false, row = Fixture.stamp(reference, boot = 0), muted = false),
            MatrixCondition("muted", standing = false, row = null, muted = true),
        )
        return (-31..2).flatMap { offset ->
            val today = EXPIRY.plusDays(offset.toLong())
            val subjects = builder.forProvider(ProviderId.LOCAL, today)
            conditions.map { condition ->
                val decision = decide(
                    subjects.map { DeadlineInput(it, Fixture.warrantyFacts(today), condition.row) },
                    standing = if (condition.standing) setOf(referenceTag) else emptySet(),
                    muted = if (condition.muted) setOf(NotificationChannels.WARRANTY) else emptySet(),
                    boot = 1,
                )
                buildString {
                    append("day ").append(offset).append(" (").append(today).append("), ").append(condition.name).append('\n')
                    subjects.forEach { append("  subject ").append(renderSubject(it)).append('\n') }
                    decision.posts.forEach { append("  post ").append(renderPost(it)).append('\n') }
                    append("  shown ").append(decision.shown.map { it.tag }).append('\n')
                    append("  cancel ").append(decision.cancelTags).append('\n')
                    append("  summary ").append(decision.summary).append(" cancelSummary ").append(decision.cancelSummary).append('\n')
                    append("  schedule rows ").append(decision.rows).append('\n')
                    decision.deadlineRows.forEach {
                        append("  stamp ").append(it.kind).append(' ').append(it.subjectId).append(' ').append(it.announcedHash)
                            .append(" boot ").append(it.announcedBoot).append(" at ").append(it.updatedAt).append('\n')
                    }
                    append("  forgotten ").append(decision.deadlineForgotten.map { "${it.kind.name}:${it.subjectId}" }).append('\n')
                    append("  report ").append(decision.report.posted).append('/').append(decision.report.cleared)
                        .append('/').append(decision.report.unchanged).append(' ').append(decision.report.problems)
                }
            }
        }.joinToString("\n", postfix = "\n")
    }

    private fun renderSubject(subject: ReminderSubject): String = listOf(
        subject.key.let { key -> if (key is SubjectKey.Deadline) "${key.kind.name}:${key.subjectId}" else key.toString() },
        "title=${subject.title}",
        "body=${subject.body}",
        "dueOn=${subject.dueOn}",
        "lead=${subject.leadDays}",
        "state=${subject.state}",
        "rule=${subject.rule}",
        "repeat=${subject.repeat}",
        "hash=${subject.contentHash}",
    ).joinToString(" | ")

    private fun renderPost(post: ItemPost): String = listOf(
        "tag=${post.tag}",
        "channel=${post.channelId}",
        "title=${post.title}",
        "body=${post.body}",
        "word=${post.statusWord}",
        "meter=${post.meter}",
        "actions=${post.actions}",
    ).joinToString(" | ")

    private data class MatrixCondition(
        val name: String,
        val standing: Boolean,
        val row: DeadlineLocalDelivery?,
        val muted: Boolean,
    )

    /** The counters, as a value, so a report can be compared in one assertion. */
    private data class ReconcileCounters(val posted: Int, val cleared: Int, val unchanged: Int)

    private fun com.loosecannon.servicetag.core.reminders.ReconcileReport.counters() =
        ReconcileCounters(posted, cleared, unchanged)

    private companion object {
        val EXPIRY: LocalDate = LocalDate.parse("2031-06-30")

        /** C14's record, beside this class on the test classpath. */
        const val WARRANTY_MATRIX = "warranty-day-matrix.txt"
        const val UUID_1 = "8f14e45f-ceea-467a-9575-3f1c2e5d6a7b"
        const val UUID_2 = "c9f0f895-fb98-4b91-99f5-1d5a2e7c0b3e"
    }
}
