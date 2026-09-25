package com.loosecannon.servicetag.ui.dashboard

import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.ui.maintenance.DueReadModel
import com.loosecannon.servicetag.ui.maintenance.NoHealthFindings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The dashboard's state against a Room-backed [FakeGraph]. The nudge is the interesting part: it
 * is a fact about the preferences *and* about whether there is anything to lose, so these tests
 * pin all three of its states — true with data and no backup, out after an export is marked, and
 * never on an install with nothing in it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

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

    private fun viewModel() = DashboardViewModel(
        assets = graph.assets,
        schedules = graph.schedules,
        states = graph.scheduleStates,
        due = DueReadModel(
            graph.schedules, graph.assets, graph.groups,
            graph.definitions, graph.recomputeSchedules, graph.todayPort, graph.assetHealthReadModel, { null },
        ),
        attention = graph.attentionReadModel,
        assetHealth = graph.assetHealthReadModel,
        health = NoHealthFindings,
        prefs = graph.prefs,
    )

    @Test fun needsBackupIsTrueUntilPrefsSayOtherwise() = runTest {
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        graph.createAsset.run("Pool pump", "Water")
        val before = vm.state.first { it.assets.isNotEmpty() }
        assertTrue(before.needsBackup)
        assertNull(before.lastBackupAt)

        // What the backup screen does once both files of a set have landed.
        graph.prefs.markBackupExported(9_000L)
        vm.refresh()

        val after = vm.state.first { !it.needsBackup }
        assertEquals(9_000L, after.lastBackupAt)
    }

    /**
     * The nudge is computed, not defaulted. Asserting `state.value` with no collector would only
     * ever read the `stateIn` seed — which is `DashboardState()`, `needsBackup = false` — and would
     * pass even with the production rule inverted. So one collector stays up for the whole case and
     * has to see both answers: no nudge while there is nothing to lose, and the flip the moment
     * there is.
     */
    @Test fun anInstallWithNothingInItIsNotNudged() = runTest {
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        // Nothing has ever been backed up, and there is nothing to back up: no nudge. A fresh
        // install is offered the empty state, not a chore.
        assertNull(graph.prefs.lastBackupAt)
        assertFalse(vm.state.first { it.assets.isEmpty() }.needsBackup)

        // The same collector goes the other way the moment there is something to lose — which is
        // also what proves the `false` above was computed and not just the initial state.
        graph.createAsset.run("Pool pump", "Water")
        assertTrue(vm.state.first { it.needsBackup }.assets.isNotEmpty())
    }

    @Test fun assetsListedActiveOnly() = runTest {
        val pump = graph.createAsset.run("Pool pump", "Water")
        graph.createAsset.run("Mower", "Yard")
        graph.archiveAsset.run(pump.id)

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        // CURRENT is where an asset lives while it is in service; archived rows are not there.
        val state = vm.state.first { it.assets.isNotEmpty() }
        assertEquals(listOf("Mower"), state.assets.map { it.asset.name })
    }

    /**
     * A retired asset is out of service exactly as an archived one is (spec §7), so CURRENT does
     * not list it — but it is still data a phone change would lose, so the nudge rule is unchanged.
     */
    @Test fun dashboardExcludesRetired() = runTest {
        val pump = graph.createAsset.run("Pool pump", "Water")
        graph.createAsset.run("Mower", "Yard")
        graph.retireAsset.retire(pump.id, "2026-04-02")

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.assets.isNotEmpty() }
        assertEquals(listOf("Mower"), state.assets.map { it.asset.name })
        assertTrue(state.needsBackup)
    }

    /**
     * B07 — the search box left for the Assets screen; the Dashboard no longer offers one. A
     * component is still part of one of the systems and is listed on its parent's own screen, and
     * the dashboard still says where it went (#39's hint line, unmoved).
     */
    @Test fun componentsAreNotListedOnTheDashboard() = runTest {
        val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
        graph.createAsset.run(AssetCommand(name = "Circulation pump", parentAssetId = tub.id))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val hidden = vm.state.first { it.assets.isNotEmpty() }
        assertEquals(listOf("Hot tub"), hidden.assets.map { it.asset.name })
        assertEquals(1, hidden.hiddenComponents)
        assertTrue(hidden.anyInService)
        assertNull("a top-level asset is nobody's component", hidden.assets.single().parentName)
    }

    /**
     * B07 / F1 — an empty list under nothing scheduled is reachable in two ordinary actions: retire
     * the parent and its component stays in service, because `RetireAsset` does not touch children.
     * The state has to say so — something in service, nothing to list, a component accounted for.
     */
    @Test fun aRetiredParentLeavesItsComponentInServiceWithNothingToList() = runTest {
        val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
        graph.createAsset.run(AssetCommand(name = "Circulation pump", parentAssetId = tub.id))
        graph.retireAsset.retire(tub.id, "2026-04-02")

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.anyInService }
        assertTrue("the parent is retired, so there is no system to list", state.assets.isEmpty())
        assertEquals(1, state.hiddenComponents)
    }

    /**
     * The plain list is asset rows too (spec §10.2, plan decision 26): the chips select them by
     * their Asset's current condition — "Not recorded" is an asset with no condition row — a status
     * alone hides them, and a status with a matching chip lets them through (M14).
     */
    @Test fun thePlainListAnswersTheConditionChips() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.conditions.insert(conditionRow("c1", "gen", OperationalCondition.OPERATIONAL, "2026-02-01"))
        graph.assets.upsert(assetRow("mow", name = "Mower"))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        fun names(state: DashboardState) = state.assets.map { it.asset.name }.sorted()

        assertEquals(listOf("Generator", "Mower"), names(vm.state.first { it.assets.size == 2 }))

        vm.onConditionToggle(ConditionChip.NOT_RECORDED)
        assertEquals(listOf("Mower"), names(vm.state.first { it.filters.conditions == setOf(ConditionChip.NOT_RECORDED) }))

        vm.onConditionToggle(ConditionChip.NOT_RECORDED)
        vm.onConditionToggle(ConditionChip.OPERATIONAL)
        assertEquals(listOf("Generator"), names(vm.state.first { it.filters.conditions == setOf(ConditionChip.OPERATIONAL) }))

        vm.onConditionToggle(ConditionChip.DOWN)
        vm.onStatusChange(DueStatus.OVERDUE)
        val statusAndChips = vm.state.first { it.filters.status == DueStatus.OVERDUE && it.filters.conditions.size == 2 }
        assertEquals("a status with a matching chip", listOf("Generator"), names(statusAndChips))

        vm.onConditionToggle(ConditionChip.DOWN)
        vm.onConditionToggle(ConditionChip.OPERATIONAL)
        val statusOnly = vm.state.first { it.filters.status == DueStatus.OVERDUE && it.filters.conditions.isEmpty() }
        assertEquals("a status alone hides asset rows", emptyList<String>(), names(statusOnly))
    }
}
