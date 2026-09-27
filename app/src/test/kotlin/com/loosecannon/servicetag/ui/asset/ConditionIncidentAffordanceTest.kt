package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.testing.dayMillis
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #82 (C10, R82-7; §3 row 20): asset detail's "Log incident" on the detail state, over a Room-backed
 * [FakeGraph]. It is offered on **every** asset in service and on none retired or archived; it leads
 * the Condition section only while the asset is DOWN or DEGRADED and its current failure has no
 * Incident — the whole DOWN/DEGRADED stretch, not the `since` run (R82-6). Kept apart from #67's
 * `AssetViewModelsTest` on purpose.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConditionIncidentAffordanceTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private fun detailModel(id: AssetId) = AssetDetailViewModel(
        graph.assets, graph.tags,
        graph.definitions, graph.profiles, graph.events,
        graph.schedules, graph.scheduleStates, graph.groups, graph.dueReadModel,
        graph.conditions, graph.seasonActivations, graph.healthSubjects, graph.attachments,
        graph.assetHealthReadModel, graph.getAssetSeason, graph.recordSeasonActivation,
        graph.archiveAsset, graph.retireAsset, graph.deleteAsset,
        graph.applyTemplate, graph.uow, graph.clock, graph.todayPort, id,
    )

    private suspend fun TestScope.loaded(id: String): AssetDetailState {
        val vm = detailModel(AssetId(id))
        backgroundScope.launch { vm.state.collect() }
        return vm.state.first { it != null }!!
    }

    /** A standalone INCIDENT, logged the day it is dated. */
    private fun incident(id: String, assetId: String, on: String) = AssetEvent(
        id = EventId(id), assetId = AssetId(assetId), kind = EventKind.INCIDENT, title = "Will not start",
        profileId = null, occurredOn = on, occurredTime = null, tzId = "UTC", notes = "",
        source = EventSource.MANUAL, sourceRef = null, createdAt = dayMillis(on), updatedAt = dayMillis(on),
        measurements = emptyList(), consumables = emptyList(),
    )

    /** Four in-service assets: OPERATIONAL, nothing recorded, DOWN and DEGRADED — none with an Incident. */
    private suspend fun fourInService() {
        graph.assets.upsert(assetRow("ups", name = "UPS"))
        graph.conditions.insert(conditionRow("c-ups", "ups", OperationalCondition.OPERATIONAL, "2026-02-01"))
        graph.assets.upsert(assetRow("mower", name = "Mower"))
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.conditions.insert(conditionRow("c-gen", "gen", OperationalCondition.DOWN, "2026-02-01"))
        graph.assets.upsert(assetRow("fan", name = "Fan"))
        graph.conditions.insert(conditionRow("c-fan", "fan", OperationalCondition.DEGRADED, "2026-02-01"))
    }

    @Test fun logIncidentOnEveryInServiceAsset() = runTest {
        fourInService()

        for (id in listOf("ups", "mower", "gen", "fan")) assertTrue(id, loaded(id).offersLogIncident)
    }

    @Test fun itLeadsWhenImpairedWithoutACurrentIncident() = runTest {
        fourInService()

        assertEquals(
            mapOf("ups" to false, "mower" to false, "gen" to true, "fan" to true),
            listOf("ups", "mower", "gen", "fan").associateWith { loaded(it).leadsWithLogIncident },
        )
    }

    /**
     * R82-9 and C4 (b): a standalone Incident logged during the failure is its Incident, so the lead
     * drops — on the page already open, from the journal the page already observes.
     */
    @Test fun aStandaloneIncidentInTheStretchStopsItLeading() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.conditions.insert(conditionRow("c-gen", "gen", OperationalCondition.DOWN, "2026-02-01"))
        val vm = detailModel(AssetId("gen"))
        backgroundScope.launch { vm.state.collect() }
        assertTrue("DOWN with no Incident leads", vm.state.first { it != null }!!.leadsWithLogIncident)

        graph.events.upsert(incident("e-seized", "gen", "2026-02-02"))
        scheduler.advanceUntilIdle()

        val page = vm.state.value!!
        assertFalse("an Incident of the current failure stops the lead", page.leadsWithLogIncident)
        assertTrue("still offered, after Change condition", page.offersLogIncident)
    }

    /**
     * The failure is the stretch, not the `since` run: DEGRADED on 1 Feb names the Incident, DOWN on
     * 5 Feb (the worsening) moves `since` but not the failure, so the Incident still answers for it.
     */
    @Test fun aLinkedIncidentStopsItLeading() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.events.upsert(incident("e-smoke", "gen", "2026-02-01"))
        graph.conditions.insert(
            conditionRow("c-1", "gen", OperationalCondition.DEGRADED, "2026-02-01", eventId = "e-smoke"),
        )
        graph.conditions.insert(conditionRow("c-2", "gen", OperationalCondition.DOWN, "2026-02-05"))

        val page = loaded("gen")

        assertEquals("the fixture: since is the DOWN row", LocalDate.parse("2026-02-05"), page.condition?.since)
        assertFalse("the stretch's linked Incident is the current one", page.leadsWithLogIncident)
        assertTrue(page.offersLogIncident)
    }

    /** R82-7: a retired or archived asset gets no "Log incident" at all, however DOWN it is. */
    @Test fun neverOutOfService() = runTest {
        graph.assets.upsert(assetRow("old", name = "Old pack", retiredOn = "2026-01-01"))
        graph.conditions.insert(conditionRow("c-old", "old", OperationalCondition.DOWN, "2026-02-01"))
        graph.assets.upsert(assetRow("fan", name = "Fan", status = AssetStatus.ARCHIVED))
        graph.conditions.insert(conditionRow("c-fan", "fan", OperationalCondition.DEGRADED, "2026-02-01"))
        graph.assets.upsert(assetRow("shed", name = "Shed heater", retiredOn = "2026-01-01"))

        for (id in listOf("old", "fan", "shed")) {
            val page = loaded(id)
            assertFalse("$id offers none", page.offersLogIncident)
            assertFalse("$id never leads", page.leadsWithLogIncident)
        }
    }
}
