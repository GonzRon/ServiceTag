package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.ui.maintenance.ReminderReconcile
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * #77 (C22, R77-23; row 35) — the phone's lifecycle writes reconcile once, **after** the write: archive, unarchive,
 * retire and unretire each run exactly one sweep, which already sees the written row; a write that fails sweeps
 * nothing. The sweep records what the store holds when it runs. Fictional names only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetLifecycleSweepTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    /** What each sweep saw of the asset: its status and retirement date when the sweep ran. */
    private val seen = mutableListOf<Pair<AssetStatus, String?>>()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse("2026-09-28")
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private suspend fun TestScope.model(id: String): AssetDetailViewModel {
        val vm = AssetDetailViewModel(
            graph.assets, graph.tags,
            graph.definitions, graph.profiles, graph.events,
            graph.schedules, graph.scheduleStates, graph.groups, graph.dueReadModel,
            graph.conditions, graph.seasonActivations, graph.healthSubjects, graph.attachments,
            graph.assetHealthReadModel, graph.getAssetSeason, graph.recordSeasonActivation,
            graph.archiveAsset, graph.retireAsset, graph.deleteAsset,
            graph.applyTemplate, graph.uow, graph.clock, graph.todayPort, AssetId(id),
            serviceCases = graph.serviceCases,
            loans = graph.loans,
            transfers = graph.transferRecords,
            reconcile = ReminderReconcile {
                val row = graph.assets.get(AssetId(id))!!
                seen += row.status to row.retiredOn
            },
        )
        backgroundScope.launch { vm.state.collect() }
        advanceUntilIdle()
        vm.state.first { it != null }
        return vm
    }

    @Test fun eachLifecycleActionSweepsOnceAfterItsWrite() = runTest(scheduler) {
        graph.assets.upsert(assetRow("d1", name = "Example Drill"))
        val vm = model("d1")

        vm.archive()
        advanceUntilIdle()
        vm.unarchive()
        advanceUntilIdle()
        vm.retire("2026-09-01")
        advanceUntilIdle()
        vm.unretire()
        advanceUntilIdle()

        assertEquals(
            "one sweep per action, each seeing its own write",
            listOf(
                AssetStatus.ARCHIVED to null,
                AssetStatus.ACTIVE to null,
                AssetStatus.ACTIVE to "2026-09-01",
                AssetStatus.ACTIVE to null,
            ),
            seen,
        )
    }

    @Test fun aFailedWriteSweepsNothing() = runTest(scheduler) {
        graph.assets.upsert(assetRow("d1", name = "Example Drill"))
        val vm = model("d1")

        vm.retire("not a date")
        advanceUntilIdle()
        graph.transferRecords.append(
            TransferRecord(
                id = "out-d1", assetId = AssetId("d1"), kind = TransferKind.OUT, packId = "0f1e2d3c-pack",
                lineage = emptyList(), at = 1_790_510_400_000L, packSha256 = "ab".repeat(32),
                nameSnapshot = "Example Drill", note = "",
            ),
        )
        vm.archive()
        advanceUntilIdle()

        assertEquals("a refused write sweeps nothing", emptyList<Pair<AssetStatus, String?>>(), seen)
    }
}
