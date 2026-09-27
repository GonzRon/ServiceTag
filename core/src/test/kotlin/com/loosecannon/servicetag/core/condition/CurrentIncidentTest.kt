package com.loosecannon.servicetag.core.condition

import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.OperationalCondition.DEGRADED
import com.loosecannon.servicetag.core.model.OperationalCondition.DOWN
import com.loosecannon.servicetag.core.model.OperationalCondition.OPERATIONAL
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.testing.HealthFixtures
import com.loosecannon.servicetag.core.testing.conditionOf
import com.loosecannon.servicetag.core.testing.dayMillis
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * #82 C4, the current Incident (R82-6): the Incident of the asset's **current failure** — the latest
 * contiguous run of DOWN and DEGRADED rows, which DEGRADED → DOWN continues and an OPERATIONAL row
 * ends. A row of the stretch naming an INCIDENT wins; otherwise an unlinked INCIDENT counts when it
 * is dated on or after the stretch's first day, or was logged after the stretch began. Completions,
 * other kinds, other assets and dangling links never count. `needsIncident` is the affordance's flag.
 */
class CurrentIncidentTest {

    /** A row recorded on its own day unless [createdOn] says it was entered later. */
    private fun row(
        id: String,
        condition: OperationalCondition,
        on: String,
        eventId: String? = null,
        createdOn: String = on,
    ): AssetCondition = conditionOf(
        id, "a1", condition, on, occurredTime = null, eventId = eventId, createdAt = dayMillis(createdOn),
    )

    /** An event logged on [loggedOn] (its own day by default), for "a1" unless [assetId] says otherwise. */
    private fun event(
        id: String,
        on: String,
        kind: EventKind = EventKind.INCIDENT,
        loggedOn: String = on,
        assetId: String = "a1",
        completion: Boolean = false,
    ): AssetEvent = HealthFixtures.eventOf(id, assetId, kind, "Pump will not start", on, createdAt = dayMillis(loggedOn) + 60_000L)
        .let { if (completion) it.copy(scheduleId = ScheduleId("s1"), occurrenceOn = on) else it }

    @Test
    fun nullWhenOperationalOrNothingRecorded() {
        val incident = event("e-1", "2026-09-21")
        assertNull(currentIncident(emptyList(), listOf(incident)), "nothing recorded")
        assertNull(
            currentIncident(listOf(row("c1", DOWN, "2026-09-10"), row("c2", OPERATIONAL, "2026-09-20")), listOf(incident)),
            "OPERATIONAL is no failure",
        )
    }

    @Test
    fun aLinkedIncidentWinsOverALaterUnlinkedOne() {
        val linked = event("e-linked", "2026-09-20")
        val later = event("e-later", "2026-09-22")

        assertEquals(linked, currentIncident(listOf(row("c1", DOWN, "2026-09-20", eventId = "e-linked")), listOf(later, linked)))
    }

    @Test
    fun anUnlinkedIncidentDatedTheStretchsFirstDayCountsTheDayBeforeDoesNot() {
        val rows = listOf(row("c1", OPERATIONAL, "2026-09-01"), row("c2", DOWN, "2026-09-20"))
        val onTheDay = event("e-day", "2026-09-20")
        val dayBefore = event("e-before", "2026-09-19")

        assertEquals(onTheDay, currentIncident(rows, listOf(onTheDay)))
        assertNull(currentIncident(rows, listOf(dayBefore)), "dated and logged before the failure began")
    }

    /** R82-6 (b): failed Monday, recorded Tuesday — the Incident logged during the stretch still counts. */
    @Test
    fun aBackdatedIncidentLoggedDuringTheStretchCounts() {
        val rows = listOf(row("c1", DOWN, "2026-09-20"))
        val backdated = event("e-monday", "2026-09-19", loggedOn = "2026-09-21")

        assertEquals(backdated, currentIncident(rows, listOf(backdated)))
    }

    @Test
    fun degradedThenDownIsOneStretch() {
        val rows = listOf(
            row("c0", OPERATIONAL, "2026-09-01"),
            row("c1", DEGRADED, "2026-09-18", eventId = "e-degraded"),
            row("c2", DOWN, "2026-09-20"),
        )
        val degraded = event("e-degraded", "2026-09-18")

        assertEquals(degraded, currentIncident(rows, listOf(degraded)), "the worsening does not demand a second Incident")
    }

    @Test
    fun anOperationalRowEndsTheStretch() {
        val rows = listOf(
            row("c1", DOWN, "2026-09-10", eventId = "e-old"),
            row("c2", OPERATIONAL, "2026-09-12"),
            row("c3", DOWN, "2026-09-20"),
        )

        assertNull(currentIncident(rows, listOf(event("e-old", "2026-09-10"))), "the last failure's Incident is not this one's")
    }

    @Test
    fun aLinkedMaintenanceADanglingLinkACompletionAndAnotherAssetsIncidentNeverCount() {
        val rows = listOf(
            row("c1", DOWN, "2026-09-20", eventId = "e-maintenance"),
            row("c2", DEGRADED, "2026-09-21", eventId = "e-gone"),
        )
        val events = listOf(
            event("e-maintenance", "2026-09-20", kind = EventKind.MAINTENANCE),
            event("e-note", "2026-09-21", kind = EventKind.NOTE),
            event("e-done", "2026-09-21", completion = true),
            event("e-other", "2026-09-21", assetId = "a2"),
        )

        assertNull(currentIncident(rows, events))
    }

    @Test
    fun theLatestOfSeveralUnlinkedIncidentsCounts() {
        val rows = listOf(row("c1", DOWN, "2026-09-20"))
        val first = event("e-1", "2026-09-20")
        val second = event("e-2", "2026-09-21")

        assertEquals(second, currentIncident(rows, listOf(second, first)))
        assertEquals(second, currentIncident(rows, listOf(first, second)))
    }

    @Test
    fun needsIncidentOnlyInServiceImpairedAndWithoutOne() {
        val down = listOf(row("c1", DOWN, "2026-09-20", eventId = "e-1"))
        val incident = event("e-1", "2026-09-20")

        assertEquals(true, needsIncident(inService = true, down, emptyList()), "DOWN with no Incident")
        assertEquals(false, needsIncident(inService = false, down, emptyList()), "retired or archived")
        assertEquals(false, needsIncident(inService = true, down, listOf(incident)), "its Incident is logged")
        assertEquals(false, needsIncident(inService = true, listOf(row("c1", OPERATIONAL, "2026-09-20")), emptyList()))
        assertEquals(false, needsIncident(inService = true, emptyList(), emptyList()), "nothing recorded")
    }
}
