package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.Measurement
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.TerminationKind
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.testing.closureOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.readingOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** `rebuild` itself: the pin, the fallback, purity, and the three structural facts about it. */
class ScheduleRecomputeTest {

    private fun on(date: String) = LocalDate.parse(date)

    private fun quarterly(createdOn: String = "2026-02-10", updatedOn: String = createdOn) = scheduleOf(
        timeInterval = 3,
        timeUnit = RecurrenceUnit.MONTH,
        timeBasis = TimeBasis.FIXED,
        anchorOn = "2026-01-01",
        createdOn = createdOn,
        updatedOn = updatedOn,
    )

    /**
     * The D-27 pin, all four facts in one test, because each of them is a different way the same
     * mistake shows up — computing the due date as "the first series date ≥ today":
     *
     * 1. a schedule created 2026-02-10 against a past anchor is due at the pinned series date,
     *    April 1, and not at the anchor it is already past;
     * 2. it stays **OVERDUE** as `T` advances with no history change — a due date that re-floated
     *    on today would be perpetually OK, which is how a real obligation disappears;
     * 3. after its sole completion is deleted it returns to the **same** pin (D5 §10.1's last row,
     *    which said "first series date ≥ T", is superseded);
     * 4. after a recurrence edit the floor becomes the **edit date**, which is what stops
     *    re-anchoring an old schedule today from pinning it immediately overdue.
     */
    @Test
    fun theFixedPinIsImmutableAndOnlyAnEditMovesItsFloor() {
        val schedule = quarterly()

        val atCreation = ScheduleRecompute.rebuild(schedule, emptyList(), emptyList(), emptyList(), on("2026-02-10"))
        assertEquals("2026-04-01", atCreation.computedDueOn)
        assertEquals(TerminationKind.NONE, atCreation.lastTerminationKind)

        // (2) the pin does not re-float: same inputs, later T, same date — and OVERDUE stays OVERDUE
        val later = ScheduleRecompute.rebuild(schedule, emptyList(), emptyList(), emptyList(), on("2026-09-01"))
        assertEquals("2026-04-01", later.computedDueOn)
        assertEquals(DueStatus.OVERDUE, statusOf(schedule, later, on("2026-09-01")))
        val muchLater = ScheduleRecompute.rebuild(schedule, emptyList(), emptyList(), emptyList(), on("2027-01-05"))
        assertEquals("2026-04-01", muchLater.computedDueOn)
        assertEquals(DueStatus.OVERDUE, statusOf(schedule, muchLater, on("2027-01-05")))

        // (3) the sole completion, then its deletion, returns the same pin
        val done = completionOf("e1", occurredOn = "2026-07-15", occurrenceOn = "2026-04-01")
        assertEquals(
            "2026-10-01",
            ScheduleRecompute.rebuild(schedule, listOf(done), emptyList(), emptyList(), on("2026-08-01")).computedDueOn,
        )
        assertEquals(
            "2026-04-01",
            ScheduleRecompute.rebuild(schedule, emptyList(), emptyList(), emptyList(), on("2026-08-01")).computedDueOn,
        )

        // (4) a recurrence edit on 2026-06-20 moves the floor to the edit date
        val edited = quarterly(createdOn = "2026-02-10", updatedOn = "2026-06-20")
        assertEquals(
            "2026-07-01",
            ScheduleRecompute.rebuild(edited, emptyList(), emptyList(), emptyList(), on("2026-06-21")).computedDueOn,
        )
    }

    /**
     * A pre-1.2 completion, or one re-pointed to a schedule by hand, carries no `occurrence_on`.
     * The fallback is the D5 §5 reconstruction — the largest series date `<= occurred_on` — and it
     * is **approximate for exactly one case, the early completion**: the same March 20 completion
     * that yields July 1 with its key yields April 1 without one. The test asserts the approximate
     * answer deliberately: a fallback that threw would make an old row unusable, and one that used
     * today would make it wrong.
     */
    @Test
    fun aCompletionWithNoOccurrenceKeyFallsBackToTheSeriesAndIsApproximate() {
        val schedule = quarterly()
        val keyless = completionOf("e1", occurredOn = "2026-03-20", occurrenceOn = null)
        val state = ScheduleRecompute.rebuild(schedule, listOf(keyless), emptyList(), emptyList(), on("2026-03-21"))

        assertEquals("2026-04-01", state.computedDueOn)
        assertEquals("2026-03-20", state.lastTerminationEffectiveOn)

        val keyed = completionOf("e1", occurredOn = "2026-03-20", occurrenceOn = "2026-04-01")
        assertNotEquals(
            state.computedDueOn,
            ScheduleRecompute.rebuild(schedule, listOf(keyed), emptyList(), emptyList(), on("2026-03-21")).computedDueOn,
        )

        // a completion before the anchor has no series date at or before it; the anchor is the key
        val ancient = completionOf("e2", occurredOn = "2025-11-02", occurrenceOn = null)
        assertEquals(
            listOf("2026-01-01"),
            ScheduleRecompute.terminations(schedule, listOf(ancient), emptyList(), emptyList())
                .map { it.occurrenceOn },
        )
    }

    /**
     * `rebuild` is a pure, idempotent function of (config, events, closures, membership, `T`)
     * (invariants 15, 16), asserted as properties **over the function**: no repository, no clock,
     * no device. Applying it again to the same inputs gives the same state, the input lists are
     * left exactly as they were, and the order the events arrive in does not matter.
     */
    @Test
    fun rebuildIsIdempotentAndPure() {
        val schedule = quarterly()
        val events = listOf(
            completionOf("e1", occurredOn = "2026-03-20", occurrenceOn = "2026-04-01"),
            readingOf("r1", "2026-05-01", "engine_hours", 12.0),
        )
        val closures = listOf(closureOf("oc1", occurrenceOn = "2026-07-01", closedOn = "2026-07-20"))
        val before = events.toList() to closures.toList()

        val first = ScheduleRecompute.rebuild(schedule, events, closures, emptyList(), on("2026-08-01"))
        val second = ScheduleRecompute.rebuild(schedule, events, closures, emptyList(), on("2026-08-01"))
        assertEquals(first, second)
        assertEquals(before.first, events)
        assertEquals(before.second, closures)

        val shuffled = ScheduleRecompute.rebuild(
            schedule, events.asReversed(), closures.asReversed(), emptyList(), on("2026-08-01"),
        )
        assertEquals(first, shuffled)

        // nothing in the state is a clock reading: two runs a long way apart in wall-clock time
        // produce byte-identical rows for the same `T`.
        assertEquals(0L, first.computedAt)
        assertEquals("2026-08-01", first.computedForOn)
    }

    /** Invariant 10: the sort key is null only when the schedule has no time rule at all. */
    @Test
    fun aMeterOnlyScheduleHasNoDueDate() {
        val meterOnly = scheduleOf(
            title = "Oil change",
            meterDefinitionId = "engine_hours",
            meterInterval = 50.0,
            anchorMeter = 120.0,
            meterLead = 5.0,
        )
        val state = ScheduleRecompute.rebuild(meterOnly, emptyList(), emptyList(), emptyList(), on("2026-06-01"))
        assertNull(state.computedDueOn)
        assertNull(state.effectiveDueOn)
        assertEquals(170.0, state.computedDueMeter)
    }

    /**
     * A meter can be misread, and a reading lower than the last one is accepted. The engine uses
     * **the latest by date, not the maximum**: taking the maximum would let one fat-fingered
     * reading raise the current value for good, and the threshold with it.
     */
    @Test
    fun theEngineUsesTheLatestMeterReadingNotTheHighest() {
        val meterOnly = scheduleOf(
            meterDefinitionId = "engine_hours",
            meterInterval = 50.0,
            anchorMeter = 120.0,
            meterLead = 5.0,
        )
        val events = listOf(
            readingOf("r1", "2026-05-01", "engine_hours", 200.0),
            readingOf("r2", "2026-06-01", "engine_hours", 150.0),
        )
        val state = ScheduleRecompute.rebuild(meterOnly, events, emptyList(), emptyList(), on("2026-06-02"))
        assertEquals(150.0, state.currentMeter)
    }

    /**
     * An event may carry **two** measurements of one definition — nothing in the model forbids it
     * and an imported archive can hold it — and only one of them need carry a number. The reading
     * is found by asking the same question twice, `definitionId` **and** a non-null `valueNum`, so
     * a numberless measurement sitting first in the list cannot swallow the reading behind it and
     * drop the schedule back a step.
     */
    @Test
    fun aReadingIsNotLostBehindANumberlessMeasurementOfTheSameDefinition() {
        val meterOnly = scheduleOf(
            meterDefinitionId = "engine_hours",
            meterInterval = 50.0,
            anchorMeter = 120.0,
        )
        val plain = readingOf("r1", "2026-06-01", "engine_hours", 180.0)
        val noisy = plain.copy(
            measurements = listOf(
                Measurement(
                    id = "m-note",
                    definitionId = DefinitionId("engine_hours"),
                    valueNum = null,
                    valueText = "unreadable",
                    unit = "h",
                    sortOrder = 0,
                ),
            ) + plain.measurements,
        )
        val state = ScheduleRecompute.rebuild(meterOnly, listOf(noisy), emptyList(), emptyList(), on("2026-06-02"))
        assertEquals(180.0, state.currentMeter)
    }

    /**
     * The group seam, now filled: `rebuild` takes membership as a parameter and the required set of
     * a group occurrence is derived from it. The same rows that used to answer "not a termination"
     * because nobody was required now terminate the round when the member who *is* required has
     * done the work — and the negative half still holds, because membership is what decides it:
     * hand the same history no windows at all and the round obliges nobody, which is never a
     * termination (invariant 77).
     */
    @Test
    fun aGroupTargetedOccurrenceTerminatesOnItsRequiredMembership() {
        val group = scheduleOf(
            assetId = null,
            groupId = "g1",
            timeInterval = 3,
            timeUnit = RecurrenceUnit.MONTH,
            anchorOn = "2026-01-01",
            createdOn = "2026-02-10",
        )
        val done = completionOf("e1", occurredOn = "2026-03-20", occurrenceOn = "2026-04-01")
        val membership = groupOf(members = listOf(Triple("a1", "2026-01-05", null))).members

        assertEquals(
            listOf(Termination("2026-04-01", "2026-03-20", TerminationKind.COMPLETED)),
            ScheduleRecompute.terminations(group, listOf(done), emptyList(), membership),
        )
        assertEquals(
            emptyList(),
            ScheduleRecompute.terminations(group, listOf(done), emptyList(), emptyList()),
        )
    }

}
