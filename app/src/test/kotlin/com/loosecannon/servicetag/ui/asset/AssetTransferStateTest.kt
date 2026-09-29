package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.ui.maintenance.ReminderReconcile
import com.loosecannon.servicetag.ui.transfer.TransferStrings
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #77 (C19, C23; rows 32, 33 and §14's row-25 case) — a transferred-out asset on the phone, keyed on its **held**
 * records and never on ARCHIVED: the detail opens with the P77-32 block and offers no write but Delete; a
 * held-but-ACTIVE row is listed only with the Archived control on, as P77-31; an ordinary archived asset keeps every
 * action (AC 11); the withdrawal asks, then writes one WITHDRAWN and sweeps once; Delete keeps the records; a write
 * a stale screen still reaches says P77-35. Over a Room-backed [FakeGraph]; every name is fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetTransferStateTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph
    private var sweeps = 0

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse("2026-09-28")
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
        serviceCases = graph.serviceCases,
        loans = graph.loans,
        transfers = graph.transferRecords,
        withdrawTransfer = WithdrawTransferRecordFor(graph),
        reconcile = ReminderReconcile { sweeps += 1 },
    )

    private fun TestScope.listModel() = AssetsViewModel(
        graph.assets, graph.categories, graph.seasonActivations, graph.tags, graph.assetHealthReadModel, graph.todayPort,
        loans = graph.loans, transfers = graph.transferRecords,
    ).also { vm -> backgroundScope.launch { vm.state.collect() } }

    private suspend fun TestScope.loaded(vm: AssetDetailViewModel): AssetDetailState {
        backgroundScope.launch { vm.state.collect() }
        advanceUntilIdle()
        return vm.state.first { it != null }!!
    }

    private suspend fun out(asset: String, pack: String = PACK, note: String = "") = graph.transferRecords.append(
        TransferRecord(
            id = "out-$asset-$pack", assetId = AssetId(asset), kind = TransferKind.OUT, packId = pack,
            lineage = emptyList(), at = AT, packSha256 = "ab".repeat(32), nameSnapshot = "Example Water Heater", note = note,
        ),
    )

    /** The heater as marking leaves it: archived, with one OUT. */
    private suspend fun heldHeater() {
        graph.assets.upsert(assetRow("h1", name = "Example Water Heater", status = AssetStatus.ARCHIVED))
        out("h1", note = "Keys are in the drawer")
    }

    @Test fun aHeldAssetDrawsTheBlockAndOffersNoWriteButDelete() = runTest(scheduler) {
        heldHeater()

        val state = loaded(detailModel("h1"))

        assertEquals(
            listOf(
                TransferOutRow(
                    packId = PACK,
                    on = "Transferred on " + TransferStrings.day(AT, ZoneId.systemDefault()),
                    pack = "Transfer Pack 0f1e2d3c",
                    note = "Keys are in the drawer",
                    withdrawTitle = "Withdraw the record for Transfer Pack 0f1e2d3c?",
                ),
            ),
            state.transferredOut,
        )
        assertTrue(state.held)
        assertFalse("no write", state.offersWrites)
        assertEquals("the overflow but Delete is hidden", listOf(DetailMenuItem.DELETE), state.menu)
        assertFalse(state.offersLogIncident)
        assertFalse(state.offersMarkOperational)
        assertFalse(state.offersNewServiceCase)
        assertFalse("no lending", state.loans.offersLendOut)
        assertTrue("P77-31 on the plate", PlateFact.Transferred in state.plate)
        assertTrue("never Archived beside it", state.plate.none { it is PlateFact.Archived })
    }

    @Test fun heldButActiveIsHiddenWithoutTheArchivedControl() = runTest(scheduler) {
        graph.assets.upsert(assetRow("h1", name = "Example Water Heater"))
        graph.assets.upsert(assetRow("g1", name = "Sample Garage Door Opener"))
        out("h1") // merged history: the row is still ACTIVE here
        val list = listModel()
        advanceUntilIdle()

        assertEquals(listOf("g1"), list.state.value.items.map { it.asset.id.value })
        list.toggleArchived()
        advanceUntilIdle()
        val rows = list.state.value.items
        assertEquals(listOf("g1", "h1"), rows.map { it.asset.id.value })
        assertEquals(listOf(false, true), rows.map { it.transferred })
        assertNull("a held row draws no health", rows.last().health)

        val detail = loaded(detailModel("h1"))
        assertFalse("held-but-ACTIVE is read-only all the same", detail.offersWrites)
        assertEquals(listOf(DetailMenuItem.DELETE), detail.menu)
    }

    @Test fun anArchivedAssetKeepsEveryAction() = runTest(scheduler) {
        graph.assets.upsert(assetRow("x1", name = "Example Ladder", status = AssetStatus.ARCHIVED))
        val list = listModel()

        val state = loaded(detailModel("x1"))

        assertFalse(state.held)
        assertTrue(state.offersWrites)
        assertEquals(
            listOf(
                DetailMenuItem.EDIT, DetailMenuItem.UNARCHIVE, DetailMenuItem.RETIRE, DetailMenuItem.TRANSFER,
                DetailMenuItem.DELETE,
            ),
            state.menu,
        )
        assertTrue(state.plate.any { it is PlateFact.Archived })
        assertTrue(PlateFact.Transferred !in state.plate)
        list.toggleArchived()
        advanceUntilIdle()
        assertEquals(listOf(false), list.state.value.items.map { it.transferred })
    }

    @Test fun withdrawAsksThenWritesThenSweepsOnce() = runTest(scheduler) {
        heldHeater()
        val vm = detailModel("h1")
        loaded(vm)

        vm.askWithdraw(PACK)
        assertEquals(DetailPrompt.Withdraw(PACK, "Withdraw the record for Transfer Pack 0f1e2d3c?"), vm.prompt.value)
        assertEquals("nothing is written before the confirm", 1, graph.transferRecords.all().size)
        assertEquals(0, sweeps)

        vm.withdraw()
        advanceUntilIdle()

        assertEquals(listOf(TransferKind.OUT, TransferKind.WITHDRAWN), graph.transferRecords.all().map { it.kind }.sorted())
        assertEquals("one sweep, after the write", 1, sweeps)
        assertNull(vm.prompt.value)
        assertEquals("it stays archived", AssetStatus.ARCHIVED, graph.assets.get(AssetId("h1"))!!.status)
        assertFalse("no longer held", vm.state.value!!.held)
    }

    @Test fun withdrawalIsOfferedOnlyForAnOpenOut() = runTest(scheduler) {
        graph.assets.upsert(assetRow("x1", name = "Example Ladder", status = AssetStatus.ARCHIVED))
        val vm = detailModel("x1")
        loaded(vm)

        vm.askWithdraw(PACK)

        assertNull(vm.prompt.value)
        assertEquals(emptyList<TransferRecord>(), graph.transferRecords.all())
    }

    @Test fun deleteAssetOnAHeldAssetKeepsTheRecords() = runTest(scheduler) {
        heldHeater()
        val vm = detailModel("h1")
        loaded(vm)

        vm.askDelete()
        vm.delete()
        advanceUntilIdle()

        assertNull("the asset is gone", graph.assets.get(AssetId("h1")))
        assertEquals("its transfer record survives the delete", listOf("out-h1-$PACK"), graph.transferRecords.all().map { it.id })
    }

    @Test fun aWriteAStaleScreenStillReachesSaysP77_35() = runTest(scheduler) {
        graph.assets.upsert(assetRow("h1", name = "Example Water Heater"))
        graph.tags.upsert(
            TagBinding(
                id = TagId("t1"), payloadFormat = PayloadFormat.V1, payloadKey = "TEST-0001",
                target = TagTarget.AssetTarget(AssetId("h1")), createdAt = 100L, updatedAt = 100L,
            ),
        )
        val vm = detailModel("h1")
        loaded(vm)
        val said = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { vm.messages.toList(said) }
        out("h1") // the asset leaves while this screen is open

        vm.editTagLabel(TagId("t1"), "Example Basement")
        advanceUntilIdle()
        vm.archive()
        advanceUntilIdle()

        assertEquals(listOf("This asset was transferred out.", "This asset was transferred out."), said)
        assertNull(graph.tags.get(TagId("t1"))!!.label)
        assertEquals(0, sweeps)
    }

    private companion object {
        const val PACK = "0f1e2d3c-4b5a-4968-8776-655443322110"
        const val AT = 1_790_510_400_000L // 27 Sep 2026, 12:00 UTC
    }
}

/** The production withdrawal over [graph]'s own records, unit of work, ids and clock. */
@Suppress("FunctionName")
private fun WithdrawTransferRecordFor(graph: FakeGraph) =
    com.loosecannon.servicetag.core.usecase.WithdrawTransferRecord(graph.transferRecords, graph.uow, graph.ids, graph.clock)
