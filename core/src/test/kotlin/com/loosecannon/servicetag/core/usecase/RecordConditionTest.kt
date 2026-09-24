package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.condition.ConditionHistory
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.OperationalCondition.DOWN
import com.loosecannon.servicetag.core.model.OperationalCondition.OPERATIONAL
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.scheduleOf
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * `RecordCondition` (spec §5; master plan §9): one immutable row and nothing else (inv. 81, 83, 126),
 * its refusals (spec §9.2), and returning to OPERATIONAL (inv. 110). The UPS is F1: DOWN since
 * 20 Sep, "Battery failed — replacement pending".
 */
class RecordConditionTest {

    private val h = ConditionHealthHarness(today = "2026-09-24")

    private fun cmd(
        condition: OperationalCondition = DOWN,
        occurredOn: String? = null,
        occurredTime: String? = null,
        tzId: String = "UTC",
        reason: String = "",
        eventId: String? = null,
    ) = ConditionCommand(condition, occurredOn, occurredTime, tzId, reason, eventId?.let(::EventId))

    /**
     * Hazard: the write does more than insert its row. Against every store the harness holds: one
     * condition row, no asset write of any kind, no recompute (a schedule with no state row still has
     * none), and no event, activation or subject.
     */
    @Test
    fun writesOneRowAndNothingElse() = runBlocking<Unit> {
        h.asset("a1")
        h.schedules.rows["s1"] = scheduleOf(
            id = "s1", assetId = "a1", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = "2026-01-01",
        )
        val events = h.events.rows.toMap()

        val written = h.recordCondition.run(
            AssetId("a1"),
            cmd(reason = "  Battery failed — replacement pending  ", occurredTime = " ", tzId = " Etc/GMT-2 "),
        )

        val expected = AssetCondition(
            id = "id-001", assetId = AssetId("a1"), condition = DOWN, occurredOn = "2026-09-24", occurredTime = null,
            tzId = "Etc/GMT-2", reason = "Battery failed — replacement pending", eventId = null, createdAt = h.now,
        )
        assertEquals(expected, written, "today by default, the reason and zone trimmed, a blank time is none")
        assertEquals(listOf(expected), h.rows())
        assertEquals(0, h.assets.upserts, "no asset column, not even updatedAt")
        assertTrue(h.states.rows.isEmpty(), "no recompute")
        assertEquals(events, h.events.rows.toMap())
        assertTrue(h.activations.rows.isEmpty())
        assertTrue(h.healthSubjects.rows.isEmpty())
    }

    /**
     * Inv. 126, the production half (B03's review): a condition write never changes the asset row —
     * compared whole, before and after, for each of the three values; its `updatedAt` must not move.
     */
    @Test
    fun aConditionWriteLeavesTheAssetRowIdentical() = runBlocking<Unit> {
        val before = h.asset("a1")
        for ((i, condition) in OperationalCondition.entries.withIndex()) {
            h.now += 60_000L
            h.recordCondition.run(AssetId("a1"), cmd(condition = condition, reason = "row $i"))
            assertEquals(before, h.stored("a1"), "after $condition")
        }
        assertEquals(3, h.rows().size)
    }

    /**
     * Hazard: a refused body writes a row, or an allowed one is refused. The three coded refusals are
     * collected into one 422; the empty reason and a reason of exactly 500 characters are allowed; the
     * malformed values keep the shipped `Bad…(field=…)` shape; a missing asset is the shipped 404.
     */
    @Test
    fun aFutureDateTooLongAReasonAndAForeignEventAreRefused() = runBlocking<Unit> {
        h.asset("a1")
        h.asset("a2", name = "Battery pack")
        h.event("e-own", EventKind.MAINTENANCE, "2026-09-20", assetId = "a1")
        h.event("e-other", EventKind.MAINTENANCE, "2026-09-20", assetId = "a2")

        suspend fun refused(c: ConditionCommand): List<ConditionProblem> =
            assertFailsWith<ConditionValidation> { h.recordCondition.run(AssetId("a1"), c) }.problems

        assertEquals(listOf(ConditionProblem.DateInFuture), refused(cmd(occurredOn = "2026-09-25")))
        assertEquals(listOf(ConditionProblem.ReasonTooLong(500)), refused(cmd(reason = "x".repeat(501))))
        assertEquals(listOf(ConditionProblem.ForeignEvent(EventId("e-other"))), refused(cmd(eventId = "e-other")))
        assertEquals(listOf(ConditionProblem.ForeignEvent(EventId("e-gone"))), refused(cmd(eventId = "e-gone")))
        assertEquals(
            listOf(
                ConditionProblem.DateInFuture,
                ConditionProblem.ReasonTooLong(500),
                ConditionProblem.ForeignEvent(EventId("e-other")),
            ),
            refused(cmd(occurredOn = "2026-10-01", reason = "y".repeat(600), eventId = "e-other")),
            "every problem at once",
        )
        assertEquals(
            listOf(
                ConditionProblem.BadDate("occurredOn"),
                ConditionProblem.BadTime("occurredTime"),
                ConditionProblem.BadTimeZone("tzId"),
            ),
            refused(cmd(occurredOn = "2026-02-30", occurredTime = "24:00", tzId = "Nowhere/Here")),
        )
        assertTrue(h.rows().isEmpty(), "a refusal writes nothing")
        assertFailsWith<NoSuchAsset> { h.recordCondition.run(AssetId("a9"), cmd()) }

        val exactly500 = h.recordCondition.run(AssetId("a1"), cmd(reason = "z".repeat(500), eventId = "e-own"))
        assertEquals(500, exactly500.reason.length)
        assertEquals(EventId("e-own"), exactly500.eventId)
        val empty = h.recordCondition.run(
            AssetId("a1"), cmd(reason = "   ", occurredOn = "2026-09-24", occurredTime = "23:59"),
        )
        assertEquals("", empty.reason)
        assertEquals(2, h.rows().size)
    }

    /**
     * Inv. 110: returning to OPERATIONAL inserts one row, and every earlier row is byte-identical — the
     * DOWN row that it ends stays in the history as it was written.
     */
    @Test
    fun returningToOperationalInsertsOneRowAndLeavesEarlierRowsByteIdentical() = runBlocking<Unit> {
        h.asset("a1")
        h.condition("c1", OPERATIONAL, "2026-09-01")
        h.condition("c2", DOWN, "2026-09-20", reason = "Battery failed — replacement pending")
        val earlier = h.rows().map { it.copy() }

        val back = h.recordCondition.run(AssetId("a1"), cmd(condition = OPERATIONAL, occurredOn = "2026-09-24"))

        assertEquals(earlier + back, h.rows(), "the earlier rows unchanged, one row added")
        val history = ConditionHistory.of(h.rows())
        assertEquals(back, history.current)
        assertEquals(back, history.since)
        assertEquals(dayMillis("2026-09-24") + 1_000L, back.createdAt)
    }
}
