package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.condition.ConditionHistory
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
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * #82 C3, Workflow B's predicate (K1, K2): after a new INCIDENT that is not a completion, on an asset
 * whose current condition is OPERATIONAL or not recorded, and only while no row names that Incident.
 * Pure: it is handed the history and the event and reads nothing.
 */
class ImpairmentOfferTest {

    private fun eventOf(kind: EventKind = EventKind.INCIDENT, assetId: String = "a1", completion: Boolean = false) =
        HealthFixtures.eventOf("e-1", assetId, kind, "Only producing reduced output", "2026-09-22")
            .let { if (completion) it.copy(scheduleId = ScheduleId("s1"), occurrenceOn = "2026-09-22") else it }

    private fun row(
        id: String,
        condition: OperationalCondition,
        on: String = "2026-09-20",
        assetId: String = "a1",
        eventId: String? = null,
    ): AssetCondition = conditionOf(id, assetId, condition, on, occurredTime = null, eventId = eventId)

    private fun historyOf(vararg rows: AssetCondition) = ConditionHistory.of(rows.toList())

    /** Hazard: the question is asked where it has no place, or not asked where it does. One row per clause. */
    @Test
    fun theOfferTable() {
        val operational = historyOf(row("c1", OPERATIONAL))
        val cases: List<Triple<String, ConditionHistory, AssetEvent>> = listOf(
            Triple("an INCIDENT on OPERATIONAL", operational, eventOf()),
            Triple("an INCIDENT on nothing recorded", historyOf(), eventOf()),
        )
        val refused: List<Triple<String, ConditionHistory, AssetEvent>> = listOf(
            Triple("an INCIDENT on DEGRADED", historyOf(row("c1", OPERATIONAL, "2026-09-01"), row("c2", DEGRADED)), eventOf()),
            Triple("an INCIDENT on DOWN", historyOf(row("c1", DOWN)), eventOf()),
            Triple("an INCIDENT completion", operational, eventOf(completion = true)),
            Triple("an INCIDENT completion on nothing recorded", historyOf(), eventOf(completion = true)),
            Triple(
                "an INCIDENT a row already names",
                historyOf(row("c1", DOWN, "2026-09-18", eventId = "e-1"), row("c2", OPERATIONAL, "2026-09-21")),
                eventOf(),
            ),
            Triple("another asset's history", historyOf(row("c1", OPERATIONAL, assetId = "a2")), eventOf()),
        ) + EventKind.entries.filter { it != EventKind.INCIDENT }.flatMap { kind ->
            listOf(
                Triple("a $kind on OPERATIONAL", operational, eventOf(kind)),
                Triple("a $kind on nothing recorded", historyOf(), eventOf(kind)),
            )
        }

        cases.forEach { (what, history, event) -> assertEquals(true, impairmentOfferFor(history, event), what) }
        refused.forEach { (what, history, event) -> assertEquals(false, impairmentOfferFor(history, event), what) }
    }
}
