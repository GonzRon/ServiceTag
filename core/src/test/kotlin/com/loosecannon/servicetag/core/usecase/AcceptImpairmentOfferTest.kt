package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.condition.ConditionHistory
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.OperationalCondition.DEGRADED
import com.loosecannon.servicetag.core.model.OperationalCondition.DOWN
import com.loosecannon.servicetag.core.model.OperationalCondition.OPERATIONAL
import com.loosecannon.servicetag.core.testing.HealthFixtures
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * #82 C3, accepting Workflow B (R82-5): "Mark down" or "Mark degraded" writes one row through
 * `RecordCondition`, dated `max(the Incident's date, the current row's date)` with the later known time
 * that day, an empty reason, the Incident's zone and its id — once, however often it is accepted.
 */
class AcceptImpairmentOfferTest {

    private val h = ConditionHealthHarness(today = "2026-09-24")

    /** An INCIDENT stored as the journal holds it before its offer is answered. */
    private fun logged(id: String, on: String, time: String? = null, assetId: String = "a1"): AssetEvent =
        HealthFixtures.eventOf(id, assetId, EventKind.INCIDENT, "Only producing reduced output", on)
            .copy(occurredTime = time, tzId = "Etc/GMT-2")
            .also { h.events.rows[it.id.value] = it }

    private fun current(assetId: String): AssetCondition? = ConditionHistory.of(h.rows(assetId)).current

    /**
     * Hazard: the accepted row sorts before the condition it follows, loses its link, or carries the
     * Incident's text. Four assets: a later Incident, a backdated one, nothing recorded, and a same-day
     * pair whose later known time wins; DOWN and DEGRADED both.
     */
    @Test
    fun acceptingDatesTheRowMaxOfIncidentAndCurrentWithTheLaterTimeAndLinksIt() = runBlocking<Unit> {
        h.asset("a1")
        h.condition("c1", OPERATIONAL, "2026-09-20", time = "08:00")
        val later = h.acceptImpairmentOffer.run(AssetId("a1"), logged("e-1", "2026-09-22"), DEGRADED)
        assertEquals(
            AssetCondition(
                id = "id-001", assetId = AssetId("a1"), condition = DEGRADED, occurredOn = "2026-09-22", occurredTime = null,
                tzId = "Etc/GMT-2", reason = "", eventId = later.eventId, createdAt = h.now,
            ),
            later,
        )
        assertEquals("e-1", later.eventId?.value)
        assertEquals(later, current("a1"))

        h.asset("a2", name = "Generator")
        h.condition("c2", OPERATIONAL, "2026-09-23", time = "14:00", assetId = "a2")
        val backdated = h.acceptImpairmentOffer.run(AssetId("a2"), logged("e-2", "2026-09-21", "09:00", "a2"), DOWN)
        assertEquals("2026-09-23" to "14:00", backdated.occurredOn to backdated.occurredTime, "the current row's day and time")
        assertEquals(backdated, current("a2"))

        h.asset("a3", name = "Sump pump")
        val first = h.acceptImpairmentOffer.run(AssetId("a3"), logged("e-3", "2026-09-24", "10:30", "a3"), DOWN)
        assertEquals("2026-09-24" to "10:30", first.occurredOn to first.occurredTime, "nothing recorded: the Incident's own")

        h.asset("a4", name = "Well pump")
        h.condition("c4", OPERATIONAL, "2026-09-22", time = "16:00", assetId = "a4")
        val sameDay = h.acceptImpairmentOffer.run(AssetId("a4"), logged("e-4", "2026-09-22", "09:15", "a4"), DEGRADED)
        assertEquals("2026-09-22" to "16:00", sameDay.occurredOn to sameDay.occurredTime, "the later known time that day")
        assertEquals(sameDay, current("a4"))
    }

    /**
     * Hazard: a second tap, or a second screen, writes a second row. A row already naming the Incident
     * is returned as it is — even for the other answer — and nothing is written.
     */
    @Test
    fun aSecondAcceptWritesNothing() = runBlocking<Unit> {
        h.asset("a1")
        h.condition("c1", OPERATIONAL, "2026-09-20")
        val event = logged("e-1", "2026-09-22")
        val first = h.acceptImpairmentOffer.run(AssetId("a1"), event, DOWN)
        h.now += 60_000L

        val again = h.acceptImpairmentOffer.run(AssetId("a1"), event, DOWN)
        val other = h.acceptImpairmentOffer.run(AssetId("a1"), event, DEGRADED)

        assertEquals(first, again)
        assertEquals(first, other)
        assertEquals(2, h.rows().size, "the OPERATIONAL row and one DOWN row")
    }

    /**
     * Hazard (inv. 81, 83): the accept writes more than its row — an event, an asset column, derived
     * state. One condition row, and every other store as it was.
     */
    @Test
    fun itWritesOneRowAndNothingElse() = runBlocking<Unit> {
        h.asset("a1")
        h.schedule("s1")
        h.condition("c1", OPERATIONAL, "2026-09-20")
        val event = logged("e-1", "2026-09-22")
        val events = h.events.rows.toMap()

        h.acceptImpairmentOffer.run(AssetId("a1"), event, DOWN)

        assertEquals(listOf("c1", "id-001"), h.rows().map { it.id })
        assertEquals(events, h.events.rows.toMap())
        assertEquals(0, h.assets.upserts)
        assertTrue(h.states.rows.isEmpty(), "no recompute")
        assertTrue(h.activations.rows.isEmpty() && h.healthSubjects.rows.isEmpty() && h.closures.rows.isEmpty())
    }

    /** Hazard: "No change" or a caller's mistake writes OPERATIONAL. Refused before any write. */
    @Test
    fun refusesOperational() = runBlocking<Unit> {
        h.asset("a1")
        val event = logged("e-1", "2026-09-22")

        val thrown = assertFailsWith<IllegalArgumentException> {
            h.acceptImpairmentOffer.run(AssetId("a1"), event, OPERATIONAL)
        }

        assertEquals(IllegalArgumentException::class, thrown::class)
        assertTrue(h.rows().isEmpty())
        assertEquals(0, h.uow.commits)
    }
}
