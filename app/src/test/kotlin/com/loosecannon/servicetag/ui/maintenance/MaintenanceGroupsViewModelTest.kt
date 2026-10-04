package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.groupOf
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * #103 (1.7.1): the group list's state, now its own pushed screen's. The two cases are the ones
 * `MaintenanceViewModelTest` held while the list was drawn inline on the Maintenance tab, moved with
 * the list: every group listed, archived ones marked, the count the open windows; and a phone with no
 * groups answered as **an empty list** — a read answer the screen draws the create row over — rather
 * than the "not read yet" null it waits on (C5's reachability ruling, 2026-09-22).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MaintenanceGroupsViewModelTest {

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

    /**
     * Every group is listed, **archived ones included and marked** (master plan decision 39), and
     * the count is the windows that are open now. What archiving takes away is the group's due work,
     * which the projection drops; the group itself stays findable, because #55 requires an archived
     * group to keep its maintenance history and history nobody can reach is not kept.
     */
    @Test fun everyGroupIsListedAndAnArchivedOneIsMarked() = runTest {
        val heads = (1..3).map { graph.createAsset.run(AssetCommand(name = "Sprinkler $it", category = "Irrigation")) }
        graph.groups.upsert(
            groupOf(
                "g1",
                name = "North run",
                members = heads.mapIndexed { index, asset ->
                    Triple(asset.id.value, "2026-01-01", if (index == 2) "2026-03-01" else null)
                },
            ),
        )
        graph.groups.upsert(groupOf("g2", name = "Old run", archivedAt = 5_000L))

        val vm = MaintenanceGroupsViewModel(graph.groups)
        backgroundScope.launch { vm.rows.collect() }

        val rows = vm.rows.first { !it.isNullOrEmpty() }!!
        assertEquals(listOf("North run", "Old run"), rows.map { it.name })
        assertEquals(listOf(false, true), rows.map { it.archived })
        val live = rows.single { it.name == "North run" }
        assertEquals("the removed window is history, not membership", 2, live.memberCount)
    }

    /** A phone with no groups is a list with nothing in it, never the null the screen waits on. */
    @Test fun aPhoneWithNoGroupsIsAnEmptyListRatherThanNoList() = runTest {
        val vm = MaintenanceGroupsViewModel(graph.groups)
        assertNull("nothing has been read yet", vm.rows.value)

        backgroundScope.launch { vm.rows.collect() }
        assertEquals(emptyList<MaintenanceGroupRow>(), vm.rows.first { it != null })
    }
}
