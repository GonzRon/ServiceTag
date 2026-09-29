package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetSuccession
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.ui.replace.ReplaceStrings
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #86 (C16; row 16) — the detail's succession facts and its overflow: the old asset says P86-26 with the date, the new
 * one P86-27, a middle link of a chain both, each naming the other asset it opens; REPLACE sits after RETIRE or
 * UNRETIRE and before TRANSFER, iff the asset is not held and has no successor; a held endpoint still draws its line.
 * Over a Room-backed [FakeGraph]; every name is fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetSuccessionStateTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse("2026-06-15")
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private fun detailModel(id: String) = AssetDetailViewModel(
        graph.assets, graph.tags,
        graph.definitions, graph.profiles, graph.events,
        graph.schedules, graph.scheduleStates, graph.groups, graph.dueReadModel,
        graph.conditions, graph.seasonActivations, graph.healthSubjects, graph.attachments,
        graph.assetHealthReadModel, graph.getAssetSeason, graph.recordSeasonActivation,
        graph.archiveAsset, graph.retireAsset, graph.deleteAsset,
        graph.applyTemplate, graph.uow, graph.clock, graph.todayPort, AssetId(id),
        loans = graph.loans,
        transfers = graph.transferRecords,
        successions = graph.assetSuccessions,
    )

    private suspend fun TestScope.loaded(id: String): AssetDetailState {
        val vm = detailModel(id)
        backgroundScope.launch { vm.state.collect() }
        advanceUntilIdle()
        return vm.state.first { it != null }!!
    }

    private suspend fun link(id: String, old: String, new: String, on: String) = graph.assetSuccessions.append(
        AssetSuccession(id = id, predecessorAssetId = AssetId(old), successorAssetId = AssetId(new), replacedOn = on, createdAt = 1L),
    )

    /** The old heater, retired on the day it was replaced, and the new one that replaced it. */
    private suspend fun heaters() {
        graph.assets.upsert(assetRow("old", name = "Example Water Heater", retiredOn = "2026-03-01"))
        graph.assets.upsert(assetRow("new", name = "Sample Water Heater"))
        link("sc-1", "old", "new", "2026-03-01")
    }

    @Test fun thePredecessorSaysReplacedByWithItsDate() = runTest(scheduler) {
        heaters()

        val state = loaded("old")

        assertEquals(
            SuccessionLine(
                assetId = "new",
                name = "Sample Water Heater",
                replacedOn = "2026-03-01",
                line = ReplaceStrings.replacedBy("Sample Water Heater", "2026-03-01"),
            ),
            state.replacedBy,
        )
        assertNull("the old asset replaces nothing", state.replaces)
    }

    @Test fun theSuccessorSaysReplaces() = runTest(scheduler) {
        heaters()

        val state = loaded("new")

        assertEquals(
            SuccessionLine(
                assetId = "old",
                name = "Example Water Heater",
                replacedOn = "2026-03-01",
                line = ReplaceStrings.replaces("Example Water Heater"),
            ),
            state.replaces,
        )
        assertNull("the new asset is not replaced", state.replacedBy)
    }

    @Test fun aChainShowsBothLines() = runTest(scheduler) {
        graph.assets.upsert(assetRow("a", name = "Example Pool Pump", retiredOn = "2024-04-01"))
        graph.assets.upsert(assetRow("b", name = "Sample Pool Pump", retiredOn = "2026-05-02"))
        graph.assets.upsert(assetRow("c", name = "Example Pool Pump Three"))
        link("sc-ab", "a", "b", "2024-04-01")
        link("sc-bc", "b", "c", "2026-05-02")

        val state = loaded("b")

        assertEquals("a", state.replaces?.assetId)
        assertEquals(ReplaceStrings.replaces("Example Pool Pump"), state.replaces?.line)
        assertEquals("c", state.replacedBy?.assetId)
        assertEquals(ReplaceStrings.replacedBy("Example Pool Pump Three", "2026-05-02"), state.replacedBy?.line)
    }

    @Test fun theMenuOffersReplaceBetweenRetireAndTransfer() = runTest(scheduler) {
        graph.assets.upsert(assetRow("g1", name = "Example Garage Door Opener"))
        graph.assets.upsert(assetRow("r1", name = "Example Ladder", retiredOn = "2026-01-10"))
        heaters()

        assertEquals(
            listOf(
                DetailMenuItem.EDIT, DetailMenuItem.ARCHIVE, DetailMenuItem.RETIRE, DetailMenuItem.REPLACE,
                DetailMenuItem.TRANSFER, DetailMenuItem.DELETE,
            ),
            loaded("g1").menu,
        )
        assertEquals(
            listOf(
                DetailMenuItem.EDIT, DetailMenuItem.ARCHIVE, DetailMenuItem.UNRETIRE, DetailMenuItem.REPLACE,
                DetailMenuItem.TRANSFER, DetailMenuItem.DELETE,
            ),
            loaded("r1").menu,
        )
        assertTrue("a successor may itself be replaced", DetailMenuItem.REPLACE in loaded("new").menu)
    }

    @Test fun theMenuHidesReplaceWhenHeldOrReplaced() = runTest(scheduler) {
        heaters()
        graph.assets.upsert(assetRow("h1", name = "Example Drill"))
        out("h1")

        assertEquals(
            listOf(
                DetailMenuItem.EDIT, DetailMenuItem.ARCHIVE, DetailMenuItem.UNRETIRE, DetailMenuItem.TRANSFER,
                DetailMenuItem.DELETE,
            ),
            loaded("old").menu,
        )
        assertEquals("a held asset's menu stays Delete alone", listOf(DetailMenuItem.DELETE), loaded("h1").menu)
    }

    @Test fun aHeldEndpointStillDrawsItsLine() = runTest(scheduler) {
        heaters()
        out("old")

        val old = loaded("old")
        val new = loaded("new")

        assertEquals(listOf(DetailMenuItem.DELETE), old.menu)
        assertEquals(ReplaceStrings.replacedBy("Sample Water Heater", "2026-03-01"), old.replacedBy?.line)
        assertEquals(ReplaceStrings.replaces("Example Water Heater"), new.replaces?.line)
    }

    private suspend fun out(asset: String) = graph.transferRecords.append(
        TransferRecord(
            id = "out-$asset", assetId = AssetId(asset), kind = TransferKind.OUT, packId = "0f1e2d3c-pack",
            lineage = emptyList(), at = 1_790_510_400_000L, packSha256 = "ab".repeat(32),
            nameSnapshot = "Example Asset", note = "",
        ),
    )
}
