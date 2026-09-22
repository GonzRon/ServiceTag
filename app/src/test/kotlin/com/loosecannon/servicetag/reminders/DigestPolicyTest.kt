package com.loosecannon.servicetag.reminders

import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.ports.ScheduleLocalDelivery
import com.loosecannon.servicetag.core.reminders.ContentHash
import com.loosecannon.servicetag.core.reminders.ReminderSubject
import com.loosecannon.servicetag.core.reminders.RuleFacts
import com.loosecannon.servicetag.core.reminders.SubjectKey
import com.loosecannon.servicetag.core.reminders.SubjectState
import com.loosecannon.servicetag.core.schedule.DueStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
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
        inputs: List<DeliveryInput>,
        standing: Set<String> = emptySet(),
        standingSummary: String? = null,
        now: Long = Fixture.NOW,
        muted: Set<String> = emptySet(),
    ) = DigestPolicy.decide(inputs, standing, standingSummary, now) { it !in muted }

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

    /** The counters, as a value, so a report can be compared in one assertion. */
    private data class ReconcileCounters(val posted: Int, val cleared: Int, val unchanged: Int)

    private fun com.loosecannon.servicetag.core.reminders.ReconcileReport.counters() =
        ReconcileCounters(posted, cleared, unchanged)
}
