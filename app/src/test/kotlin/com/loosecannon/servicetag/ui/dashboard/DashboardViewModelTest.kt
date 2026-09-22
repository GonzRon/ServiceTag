package com.loosecannon.servicetag.ui.dashboard

import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.testing.FakeGraph
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
            graph.schedules, graph.scheduleStates, graph.assets, graph.groups,
            graph.definitions, graph.recomputeSchedules, graph.todayPort, { null },
        ),
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
     * #39 — the dashboard lists systems. A component is part of one of them and is listed there,
     * on its parent's own screen; listing it here again as though it were a system of its own is
     * what the owner asked to stop. It is still counted, so the screen can say where it went.
     */
    @Test fun componentsAreNotListedUntilTheyAreSearchedFor() = runTest {
        val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
        graph.createAsset.run(AssetCommand(name = "Circulation pump", parentAssetId = tub.id))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val hidden = vm.state.first { it.assets.isNotEmpty() }
        assertEquals(listOf("Hot tub"), hidden.assets.map { it.asset.name })
        assertEquals(1, hidden.hiddenComponents)
        assertTrue(hidden.anyInService)
        assertNull("a top-level asset is nobody's component", hidden.assets.single().parentName)

        // One keystroke away, and the hit names the system it belongs to.
        vm.onQueryChange("circ")
        val found = vm.state.first { it.query == "circ" && it.assets.isNotEmpty() }
        assertEquals(listOf("Circulation pump"), found.assets.map { it.asset.name })
        assertEquals("Hot tub", found.assets.single().parentName)
    }

    /**
     * #39 — the six fields a query reaches are the ones an owner types: the name, the category the
     * Assets list already shows, and the four they read off the machine itself. The prose fields
     * are deliberately out, because a row showing a name and its parent could not explain a hit
     * buried in a paragraph.
     */
    @Test fun theQueryReachesTheFieldsSomeoneWouldType() = runTest {
        graph.createAsset.run(
            AssetCommand(
                name = "Circulation pump",
                category = "Water",
                manufacturer = "Hayward",
                model = "SP2610X15",
                serialNumber = "A1B2C3",
                location = "Pool shed",
                notes = "the impeller was replaced last spring",
            ),
        )

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.assets.isNotEmpty() }

        // Case-insensitive substring, on every one of the six.
        for (typed in listOf("circ", "WATER", "hayward", "sp2610", "b2c3", "pool shed")) {
            vm.onQueryChange(typed)
            assertEquals(
                "typing \"$typed\" should find the pump",
                1,
                vm.state.first { it.query == typed }.assets.size,
            )
        }

        // And not on the prose.
        vm.onQueryChange("impeller")
        val none = vm.state.first { it.query == "impeller" }
        assertTrue("notes are not searched", none.assets.isEmpty())
        assertTrue("but the install is not empty either", none.anyInService)
    }

    /** #39 — clearing the box puts the components back out of sight, in one call. */
    @Test fun clearingTheQueryPutsTheComponentsBackOutOfSight() = runTest {
        val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
        graph.createAsset.run(AssetCommand(name = "Circulation pump", parentAssetId = tub.id))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.assets.isNotEmpty() }

        vm.onQueryChange("circ")
        assertEquals(listOf("Circulation pump"), vm.state.first { it.query == "circ" }.assets.map { it.asset.name })

        vm.clearQuery()
        val cleared = vm.state.first { it.query.isEmpty() && it.assets.isNotEmpty() }
        assertEquals(listOf("Hot tub"), cleared.assets.map { it.asset.name })
    }

    /**
     * #39 — "nothing here yet" and "nothing matches that" are different facts. The screen can only
     * tell them apart if the state says whether there is anything in service at all.
     */
    @Test fun aSearchThatFindsNothingIsNotAFirstRun() = runTest {
        graph.createAsset.run("Mower", "Yard")

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.assets.isNotEmpty() }

        vm.onQueryChange("zzz")
        val nothing = vm.state.first { it.query == "zzz" }
        assertTrue(nothing.assets.isEmpty())
        assertTrue(nothing.anyInService)
    }

    /**
     * #39 / F1 — an empty list under an empty box is reachable in two ordinary actions: retire the
     * parent and its component stays in service, because `RetireAsset` does not touch children. The
     * state has to say so — something in service, nothing to list, a component accounted for — so
     * the screen can keep "Nothing matches that." for a query somebody actually typed.
     */
    @Test fun aRetiredParentLeavesItsComponentInServiceWithNothingToList() = runTest {
        val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
        graph.createAsset.run(AssetCommand(name = "Circulation pump", parentAssetId = tub.id))
        graph.retireAsset.retire(tub.id, "2026-04-02")

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.anyInService }
        assertTrue("the parent is retired, so there is no system to list", state.assets.isEmpty())
        assertEquals("nobody typed anything", "", state.query)
        assertEquals(1, state.hiddenComponents)
    }

    /**
     * #39 / F5 — the query is trimmed for matching and kept verbatim in the state. Trimming the
     * stored string instead would fight the text field over what it holds; not trimming at all
     * would make a trailing space from an IME suggestion look like a query that found nothing.
     */
    @Test fun theQueryIsTrimmedButKeptVerbatim() = runTest {
        graph.createAsset.run(AssetCommand(name = "Circulation pump", category = "Water"))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.assets.isNotEmpty() }

        vm.onQueryChange(" circ ")
        val hit = vm.state.first { it.query == " circ " }
        assertEquals(listOf("Circulation pump"), hit.assets.map { it.asset.name })
        assertEquals(" circ ", hit.query)
    }

    /**
     * #39 / F5 — the query is the screen's, not the store's. It is an independent arm of the
     * `combine`, so a row arriving recomputes the rows against the same query; folding the query
     * into the repository flow would pass every other case in this class and fail this one.
     */
    @Test fun aRowArrivingDoesNotDisturbTheQuery() = runTest {
        graph.createAsset.run(AssetCommand(name = "Circulation pump", category = "Water"))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.assets.isNotEmpty() }

        vm.onQueryChange("pump")
        vm.state.first { it.query == "pump" }

        graph.createAsset.run("Pool pump", "Water")
        val after = vm.state.first { it.assets.size == 2 }
        assertEquals("pump", after.query)
    }

    /**
     * #39 / F3 — what the box draws itself from answers synchronously, with no collector and no
     * scheduler turn. `state.query` says the same thing eventually, through `combine` and
     * `stateIn`; a text field that had to wait for that round trip is a text field that drops
     * characters typed fast.
     */
    @Test fun theBoxSeesItsOwnKeystrokeWithoutWaitingForTheList() = runTest {
        val vm = viewModel()

        assertEquals("", vm.query.value)
        vm.onQueryChange("circ")
        assertEquals("circ", vm.query.value)
        vm.clearQuery()
        assertEquals("", vm.query.value)
    }
}
