package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.health.AssetHealthResult
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.OperationalCondition.DOWN
import com.loosecannon.servicetag.core.model.OperationalCondition.OPERATIONAL
import com.loosecannon.servicetag.core.testing.HealthFixtures
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * #82, AC 5 and inv. 81 (K6): an Incident is a journal entry and nothing more. Logging one writes no
 * condition row and moves no health, whatever the asset's condition — the only paths from an
 * Incident to a condition are the owner's explicit answers (`RecordConditionWithIncident`,
 * `AcceptImpairmentOffer`).
 */
class IncidentWorkflowTest {

    private val h = ConditionHealthHarness(today = "2026-09-24")

    /** The asset's health on the harness's today, from what the stores hold: the app's read path. */
    private fun health(assetId: String): AssetHealthResult = HealthFixtures.healthOn(
        "2026-09-24",
        h.stored(assetId),
        h.healthSubjects.rows.values.filter { it.assetId == AssetId(assetId) },
        listOf(h.storedSchedule("s-$assetId")),
        h.events.rows.values.filter { it.assetId == AssetId(assetId) },
    )

    @Test
    fun loggingAnIncidentWritesNoConditionAndMovesNoHealth() = runBlocking<Unit> {
        for ((id, name) in listOf("a1" to "Sump pump", "a2" to "Well pump", "a3" to "Generator")) {
            h.asset(id, name = name)
            h.schedule("s-$id", assetId = id)
            h.subject("h-$id", scheduleId = "s-$id", assetId = id)
        }
        h.condition("c1", OPERATIONAL, "2026-09-20")
        h.condition("c2", DOWN, "2026-09-20", assetId = "a2", reason = "Pump will not start")
        val rows = h.conditions.rows.toMap()
        val before = listOf("a1", "a2", "a3").associateWith { health(it) }
        assertTrue(before.values.all { it.aggregate != null }, "every asset has a health to move")

        for (id in listOf("a1", "a2", "a3")) {
            h.logEvent.run(
                EventCommand(
                    assetId = AssetId(id), profileId = null, kind = EventKind.INCIDENT, title = "Pump will not start",
                    occurredOn = "2026-09-24", occurredTime = null, tzId = "UTC", notes = "Breaker trips on start",
                    values = emptyMap(), consumables = emptyList(),
                ),
            )
        }

        assertEquals(3, h.events.rows.size, "three Incidents logged")
        assertEquals(rows, h.conditions.rows.toMap(), "no condition row written, none changed")
        assertEquals(before, listOf("a1", "a2", "a3").associateWith { health(it) }, "no health moved")
    }
}
