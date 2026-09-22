package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.TerminationKind
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.testing.closureOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.readingOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D5 §10's worked examples, as data. Where the spec corrects D5 the spec wins and the correction is
 * named in the test's own comment, because a table in a design document that disagrees with the
 * engine is exactly the kind of thing a later reader resolves the wrong way:
 *
 * - **§10.1's early completion.** D5 §5 reconstructs `prevDue` as the largest series date
 *   `<= last_completed_on`, which for a completion in March of the occurrence due in April returns
 *   January and advances to April — the occurrence that was just satisfied. Spec §2.2 reads
 *   `prevDue` off the terminating row's `occurrence_on` instead, and the answer is July.
 * - **§10.1's last row** ("delete the event → due = first series date ≥ T") is superseded by D-27:
 *   a never-terminated FIXED schedule is pinned from immutable configuration, so deleting the sole
 *   completion returns the *same* pin. That case is `ScheduleRecomputeTest`'s.
 * - **§10.2's and §10.3's "created" rows** treat `anchor_on` as "last performed" and add the
 *   interval to it. The master plan and spec §2.1 rule the other way — COMPLETION with no
 *   termination is due **at** `anchorOn`, the date the owner states it is first due — so the
 *   fixtures here anchor on the first due date and the arithmetic matches D5's numbers exactly.
 */
class D5WorkedExamplesTest {

    private fun on(date: String) = LocalDate.parse(date)

    private val quarterly = scheduleOf(
        timeInterval = 3,
        timeUnit = RecurrenceUnit.MONTH,
        timeBasis = TimeBasis.FIXED,
        anchorOn = "2026-01-01",
        createdOn = "2026-02-10",
    )

    private fun rebuild(
        schedule: com.loosecannon.servicetag.core.model.MaintenanceSchedule = quarterly,
        events: List<com.loosecannon.servicetag.core.model.AssetEvent> = emptyList(),
        closures: List<com.loosecannon.servicetag.core.model.OccurrenceClosure> = emptyList(),
        today: String,
    ) = ScheduleRecompute.rebuild(schedule, events, closures, emptyList(), on(today))

    /**
     * §10.1, the spec's headline correction. The April occurrence is completed on March 20 and the
     * event carries `occurrence_on = 2026-04-01`, so the series advances past **April**, not past
     * March: the next due date is July 1. Reconstructing `prevDue` from `occurred_on` would give
     * January, and the answer would be the April occurrence all over again (invariant 70).
     */
    @Test
    fun theEarlyCompletionAdvancesFromTheOccurrenceKeyAndNotFromTheDateItWasDone() {
        val completed = completionOf("e1", occurredOn = "2026-03-20", occurrenceOn = "2026-04-01")
        val state = rebuild(events = listOf(completed), today = "2026-03-21")

        assertEquals("2026-07-01", state.computedDueOn)
        assertEquals("2026-07-01", state.effectiveDueOn)
        assertEquals("2026-03-20", state.lastTerminationEffectiveOn)
        assertEquals(TerminationKind.COMPLETED, state.lastTerminationKind)
        assertEquals("2026-03-20", state.lastCompletedOn)
    }

    /**
     * §10.1's late and very-late rows. The late completion lands on the same next occurrence as the
     * early one (July 1, the first series date after both `D` and `E`); the very late one skips the
     * July occurrence entirely and produces **one** next occurrence, October 1. A backlog of missed
     * occurrences is never created (invariants 9, 13) — and there is exactly one due date to read,
     * because the state holds one.
     */
    @Test
    fun theLateAndVeryLateCompletionsSkipForwardWithNoBacklog() {
        val late = completionOf("e1", occurredOn = "2026-04-20", occurrenceOn = "2026-04-01")
        assertEquals("2026-07-01", rebuild(events = listOf(late), today = "2026-04-21").computedDueOn)

        val veryLate = completionOf("e1", occurredOn = "2026-07-15", occurrenceOn = "2026-04-01")
        val state = rebuild(events = listOf(veryLate), today = "2026-07-16")
        assertEquals("2026-10-01", state.computedDueOn)
        assertEquals("2026-07-15", state.lastTerminationEffectiveOn)
        assertEquals(1, ScheduleRecompute.terminations(quarterly, listOf(veryLate), emptyList(), emptyList()).size)
    }

    /**
     * §10.2, the COMPLETION basis: `nextDue = E + interval`, and with no termination the anchor
     * itself. Advancing from the occurrence key instead of the effective date would give the wrong
     * date for the early completion — this is where the two bases genuinely differ (invariant 12).
     */
    @Test
    fun theCompletionBasisAdvancesFromTheEffectiveDate() {
        val loadTest = scheduleOf(
            title = "Load test",
            timeInterval = 90,
            timeUnit = RecurrenceUnit.DAY,
            timeBasis = TimeBasis.COMPLETION,
            anchorOn = "2026-06-08",
            createdOn = "2026-06-08",
        )

        assertEquals("2026-06-08", rebuild(loadTest, today = "2026-06-08").computedDueOn)

        val onTime = completionOf("e1", occurredOn = "2026-09-13", occurrenceOn = "2026-06-08")
        assertEquals("2026-12-12", rebuild(loadTest, listOf(onTime), today = "2026-09-14").computedDueOn)

        val early = completionOf("e1", occurredOn = "2026-08-30", occurrenceOn = "2026-06-08")
        assertEquals("2026-11-28", rebuild(loadTest, listOf(early), today = "2026-08-31").computedDueOn)
    }

    /**
     * §10.3, the combined rule, in one test: both sides are computed independently, status is the
     * **worst** of the two, and one completion advances **both**. Five assertions, because
     * computing status from one side or advancing only one side breaks exactly one of them.
     *
     * The oil change is due at 170 h or on 2027-04-10, whichever comes first.
     */
    @Test
    fun theCombinedRuleTakesTheWorstOfBothSidesAndAdvancesBoth() {
        val oil = scheduleOf(
            title = "Oil change",
            timeInterval = 12,
            timeUnit = RecurrenceUnit.MONTH,
            timeBasis = TimeBasis.COMPLETION,
            anchorOn = "2027-04-10",
            meterDefinitionId = "engine_hours",
            meterInterval = 50.0,
            anchorMeter = 120.0,
            meterLead = 5.0,
            createdOn = "2026-04-10",
        )

        val created = rebuild(oil, today = "2026-04-10")
        assertEquals("2027-04-10", created.computedDueOn)
        assertEquals(170.0, created.computedDueMeter)

        val soon = listOf(readingOf("r1", "2026-08-01", "engine_hours", 165.0))
        val atSoon = rebuild(oil, soon, today = "2026-08-01")
        assertEquals(DueStatus.DUE_SOON, statusOf(oil, atSoon, on("2026-08-01")))

        val over = soon + readingOf("r2", "2026-08-15", "engine_hours", 171.0)
        assertEquals(DueStatus.DUE, statusOf(oil, rebuild(oil, over, today = "2026-08-15"), on("2026-08-15")))

        val done = over + completionOf(
            "e1", occurredOn = "2026-08-20", occurrenceOn = "2027-04-10",
            meter = "engine_hours" to 172.0,
        )
        val advanced = rebuild(oil, done, today = "2026-08-21")
        assertEquals("2027-08-20", advanced.computedDueOn)
        assertEquals(222.0, advanced.computedDueMeter)

        // no readings at all: the time side alone decides, and it is past
        val noReadings = rebuild(oil, today = "2027-04-11")
        assertEquals(DueStatus.OVERDUE, statusOf(oil, noReadings, on("2027-04-11")))
    }

    /**
     * §10.2's postpone row, which is where a rule-from-the-postponed-date implementation shows up:
     * the postponement moves the current occurrence's date and **nothing else**, so completing on
     * September 25 gives December 24 — 90 days from the completion, not from the postponed date
     * (invariant 21). The postponement itself is cleared by the operation, not by `rebuild`.
     */
    @Test
    fun aPostponementNeverFeedsTheRule() {
        val loadTest = scheduleOf(
            timeInterval = 90,
            timeUnit = RecurrenceUnit.DAY,
            timeBasis = TimeBasis.COMPLETION,
            anchorOn = "2026-06-08",
            createdOn = "2026-06-08",
            postponedDueOn = "2026-09-20",
        )
        val postponed = rebuild(loadTest, today = "2026-09-07")
        assertEquals("2026-06-08", postponed.computedDueOn)
        assertEquals("2026-09-20", postponed.effectiveDueOn)

        val done = completionOf("e1", occurredOn = "2026-09-25", occurrenceOn = "2026-06-08")
        val cleared = rebuild(loadTest.copy(postponedDueOn = null), listOf(done), today = "2026-09-26")
        assertEquals("2026-12-24", cleared.computedDueOn)
        assertEquals("2026-12-24", cleared.effectiveDueOn)
    }

    /**
     * §10.4's winter window, and the D5 §7 closure case beside it: a closure advances **both** bases
     * from `closed_on` exactly as a completion does from `occurred_on`, with no special case in the
     * arithmetic. A closed round never claims anybody did the work, so `lastCompletedOn` stays null.
     */
    @Test
    fun aClosureAdvancesTheSeriesAndClaimsNoWork() {
        val closed = closureOf("oc1", occurrenceOn = "2026-04-01", closedOn = "2026-07-15")
        val state = rebuild(closures = listOf(closed), today = "2026-07-16")

        assertEquals("2026-10-01", state.computedDueOn)
        assertEquals("2026-07-15", state.lastTerminationEffectiveOn)
        assertEquals(TerminationKind.CLOSED, state.lastTerminationKind)
        assertEquals(null, state.lastCompletedOn)
    }
}
