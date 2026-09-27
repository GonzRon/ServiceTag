package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.usecase.ConditionCommand
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.core.usecase.RecordCondition
import com.loosecannon.servicetag.core.usecase.RecordConditionWithIncident
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * #82, §3 row 2 on Room (C1): the combined Save — the Incident and its linked condition row — is one
 * transaction on the real database, not only on `:core`'s fake unit of work. A condition insert that
 * throws after the event was upserted and the schedules recomputed leaves **no** `asset_event` row
 * and no condition row behind: both facts, or neither.
 */
class IncidentWorkflowRoomTest {

    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        graph = FakeGraph()
        graph.today = LocalDate.parse("2026-02-10")
    }

    @After fun tearDown() = graph.close()

    private val pump = AssetId("pump")

    private val held = ConditionCommand(OperationalCondition.DOWN, "2026-02-09", null, "UTC", "Will not start")

    private val incident = EventCommand(
        assetId = pump, profileId = null, kind = EventKind.INCIDENT, title = "Will not start",
        occurredOn = "2026-02-09", occurredTime = null, tzId = "UTC", notes = "",
        values = emptyMap(), consumables = emptyList(),
    )

    @Test fun theCombinedSaveRollsBackOnRoom() = runBlocking {
        graph.assets.upsert(assetRow("pump", name = "Pump"))
        // The graph's own Room-backed repository, except that its insert fails — as a full disk would,
        // after the Incident is already in the transaction.
        val refusing = object : ConditionRepository by graph.conditions {
            override suspend fun insert(row: AssetCondition) = throw IllegalStateException("the disk is full")
        }
        val record = RecordCondition(graph.assets, graph.events, refusing, graph.uow, graph.ids, graph.clock, graph.todayPort)
        val combined = RecordConditionWithIncident(
            graph.events, graph.definitions, graph.profiles, graph.assets, graph.uow, graph.ids, graph.clock,
            graph.recomputeSchedules, refusing, graph.todayPort, record,
        )

        val thrown = runCatching { combined.run(pump, "c-held", held, incident) }.exceptionOrNull()

        assertEquals("the insert's failure leaves the write", "the disk is full", thrown?.message)
        assertEquals("no asset_event row", 0, graph.events.all().size)
        assertEquals("no condition row", 0, graph.conditions.all().size)

        // The same Save through the graph's own wiring commits both, linked: the refusal was the only difference.
        val written = graph.recordConditionWithIncident.run(pump, "c-held", held, incident)
        assertEquals(listOf(written.incident), graph.events.all())
        assertEquals(written.incident!!.id, graph.conditions.all().single().eventId)
    }
}
