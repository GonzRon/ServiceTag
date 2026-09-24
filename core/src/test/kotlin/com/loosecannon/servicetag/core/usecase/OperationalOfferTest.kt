package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.condition.ConditionHistory
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.OperationalCondition.DEGRADED
import com.loosecannon.servicetag.core.model.OperationalCondition.DOWN
import com.loosecannon.servicetag.core.model.OperationalCondition.OPERATIONAL
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.testing.HealthFixtures
import com.loosecannon.servicetag.core.testing.conditionOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The "Mark operational?" offer (spec §5.4; master plan §9, plan decision 51): when it is offered,
 * what accepting writes, and that declining — and the event itself — writes nothing (inv. 81).
 */
class OperationalOfferTest {

    private val h = ConditionHealthHarness(today = "2026-09-24")

    private fun eventOn(kind: EventKind, on: String = "2026-09-22", assetId: String = "a1", completion: Boolean = false) =
        HealthFixtures.eventOf("e-${kind.name.lowercase()}-$assetId", assetId, kind, "Battery replaced", on)
            .let { if (completion) it.copy(scheduleId = ScheduleId("s1"), occurrenceOn = on) else it }

    /** [event], stored, as the journal holds it before its offer is answered. */
    private fun logged(event: AssetEvent): AssetEvent = event.also { h.events.rows[it.id.value] = it }

    /**
     * Hazard: an offer where none belongs. Only a completion (whatever its kind), a MAINTENANCE or a
     * REPLACEMENT event, and only on the same asset while it is DOWN or DEGRADED; never with nothing
     * recorded, never on OPERATIONAL.
     */
    @Test
    fun anOfferOnlyAfterCompletionMaintenanceOrReplacementOnDownOrDegraded() {
        val restoring = listOf(
            eventOn(EventKind.INSPECTION, completion = true),
            eventOn(EventKind.MAINTENANCE),
            eventOn(EventKind.REPLACEMENT),
        )
        val other = EventKind.entries
            .filter { it != EventKind.MAINTENANCE && it != EventKind.REPLACEMENT }
            .map { eventOn(it) }

        for (condition in OperationalCondition.entries) {
            val current = conditionOf("c1", condition = condition, occurredOn = "2026-09-20")
            val impaired = condition == DOWN || condition == DEGRADED
            restoring.forEach { event ->
                val what = "$condition after ${event.kind} ${event.scheduleId}"
                assertEquals(impaired, operationalOfferFor(current, event), what)
            }
            other.forEach { event ->
                assertEquals(false, operationalOfferFor(current, event), "$condition after ${event.kind}")
            }
            assertEquals(false, operationalOfferFor(current, eventOn(EventKind.REPLACEMENT, assetId = "a2")), "another asset")
        }
        restoring.forEach { assertEquals(false, operationalOfferFor(null, it), "nothing recorded: nothing to end") }
    }

    /**
     * Hazard: the accepted row sorts before the condition it ends, or loses its link. It is OPERATIONAL,
     * dated the later of the event's date and the current row's, linked to the event — and on that day
     * it carries the later of the two known times, so a timed same-day DOWN cannot stay current.
     */
    @Test
    fun acceptingDatesTheRowMaxOfEventAndCurrentAndLinksTheEvent() = runBlocking<Unit> {
        h.asset("a1")
        val down = h.condition("c1", DOWN, "2026-09-20", reason = "Battery failed — replacement pending")

        val replaced = logged(eventOn(EventKind.REPLACEMENT, on = "2026-09-22"))
        val later = h.acceptOperationalOffer.run(AssetId("a1"), replaced)
        assertEquals(OPERATIONAL, later.condition)
        assertEquals("2026-09-22", later.occurredOn, "the event is later than the current row")
        assertEquals(EventId("e-replacement-a1"), later.eventId)
        assertEquals(listOf(down, later), h.rows(), "the DOWN row stays as it was")
        assertEquals(later, ConditionHistory.of(h.rows()).current)

        h.asset("a2", name = "Battery pack")
        h.condition("c2", DEGRADED, "2026-09-21", assetId = "a2")
        val backdated = h.acceptOperationalOffer.run(
            AssetId("a2"), logged(eventOn(EventKind.MAINTENANCE, on = "2026-09-10", assetId = "a2")),
        )
        assertEquals("2026-09-21", backdated.occurredOn, "a backdated event takes the current row's date")
        assertEquals(backdated, ConditionHistory.of(h.rows("a2")).current)

        h.asset("a3", name = "Generator")
        h.condition("c3", DOWN, "2026-09-23", time = "14:00", assetId = "a3")
        val sameDay = h.acceptOperationalOffer.run(
            AssetId("a3"), logged(eventOn(EventKind.MAINTENANCE, on = "2026-09-23", assetId = "a3", completion = true)),
        )
        assertEquals("2026-09-23" to "14:00", sameDay.occurredOn to sameDay.occurredTime)
        assertEquals(sameDay, ConditionHistory.of(h.rows("a3")).current, "the accepted row is current")
    }

    /**
     * Hazard: the offer applies itself. Logging the event that prompts the offer, and asking the
     * predicate, write no condition row; "Not yet" is the absence of a call, so nothing is written.
     */
    @Test
    fun decliningWritesNothing() = runBlocking<Unit> {
        h.asset("a1")
        h.condition("c1", DOWN, "2026-09-20", reason = "Battery failed — replacement pending")
        val before = h.rows()

        val logged: AssetEvent = h.logEvent.run(
            EventCommand(
                assetId = AssetId("a1"), profileId = null, kind = EventKind.REPLACEMENT, title = "Battery replaced",
                occurredOn = "2026-09-23", occurredTime = null, tzId = "UTC", notes = "", values = emptyMap(),
                consumables = emptyList(),
            ),
        )
        assertEquals(before, h.rows(), "the logged event wrote no condition row")
        assertTrue(operationalOfferFor(ConditionHistory.of(h.rows()).current, logged), "the offer is made")

        // "Not yet": no call at all.
        assertEquals(before, h.rows(), "declined: the history is as it was")
        assertEquals(DOWN, ConditionHistory.of(h.rows()).current?.condition)
    }
}
