package com.loosecannon.servicetag.ui.transfer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.AttachmentStore
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.core.usecase.BackupRepositories
import com.loosecannon.servicetag.core.usecase.MarkTransferredOut
import com.loosecannon.servicetag.testing.loanRow
import com.loosecannon.servicetag.transfer.TransferPackWriter
import com.loosecannon.servicetag.ui.maintenance.ReminderReconcile
import com.loosecannon.servicetag.ui.transfer.`import`.TransferPackAppFixtures
import com.loosecannon.servicetag.ui.transfer.`import`.TransferPackAppFixtures.HEATER
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #77 (C17's Create, C18, C22; rows 30, 31) — creation, the ready screen and the mark over a Room-backed [FakeGraph],
 * the production creation and marking, and a real [TransferPackWriter] in a temporary cache: P77-19 then ready; a
 * missing document or a cancellation leaves no file and no record; Mark only after creation, one write then exactly
 * one sweep; `Not now` writes nothing; P77-51 / P77-57 / P77-17 refusals; Save a copy copies every byte (P77-26) or
 * says P77-55; leaving deletes the pack; a restored screen keeps a fresh pack, and one whose file is gone offers
 * nothing (P77-60). "Example Water Heater" and its manual are fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferPackViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph
    private val cache: File = Files.createTempDirectory("transfer-vm").toFile()
    private val store = ViewModelStore()
    private var models = 0
    private var sweeps = 0
    private val reconcile = ReminderReconcile { sweeps += 1 }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.now = 1_790_467_200_000L // 27 Sep 2026, 00:00 UTC
    }

    @After fun tearDown() {
        store.clear()
        graph.close()
        cache.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun model(
        saved: SavedStateHandle = SavedStateHandle(),
        storage: AttachmentStorage = graph.attachmentStorage,
        mark: MarkTransferredOut = graph.markTransferredOut,
        root: File = cache,
    ): TransferPackViewModel {
        val factory = viewModelFactory {
            initializer {
                TransferPackViewModel(
                    graph.createTransferPack, TransferPackWriter(root, storage), mark, reconcile,
                    graph.assets, graph.groups, saved, zone = ZoneOffset.UTC, io = StandardTestDispatcher(scheduler),
                )
            }
        }
        return ViewModelProvider.create(store, factory)["model-${models++}", TransferPackViewModel::class]
    }

    private fun packs(): List<String> = File(cache, "transfer").listFiles()?.map { it.name }.orEmpty()
    private fun workFiles(): List<String> = File(cache, "transfer-work").listFiles()?.map { it.name }.orEmpty()

    private suspend fun TestScope.readyModel(saved: SavedStateHandle = SavedStateHandle()): TransferPackViewModel {
        TransferPackAppFixtures.seedHeater(graph)
        val vm = model(saved)
        vm.create(listOf(AssetId(HEATER)), "Example handover note")
        advanceUntilIdle()
        return vm
    }

    private fun TestScope.eventsOf(vm: TransferPackViewModel): List<TransferPackEvent> =
        mutableListOf<TransferPackEvent>().also { seen -> backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { vm.events.toList(seen) } }

    @Test fun progressThenReady() = runTest(scheduler) {
        TransferPackAppFixtures.seedHeater(graph)
        val vm = model()

        vm.create(listOf(AssetId(HEATER)), "")
        assertEquals("P77-19 before the first suspension", PackPhase.CREATING, vm.state.value.phase)
        assertFalse(vm.state.value.offersActions)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(PackPhase.READY, state.phase)
        assertTrue(state.offersActions)
        assertTrue(state.fileName!!, Regex("servicetag-transfer-2026-09-27-[0-9a-f-]{8}\\.zip").matches(state.fileName!!))
        assertEquals(listOf(state.fileName), packs())
        assertEquals(emptyList<String>(), workFiles())
        assertTrue(state.sizeLine!!, state.sizeLine!!.startsWith("Size: "))
    }

    @Test fun aMissingDocumentLeavesNoFileAndSaysP77_20() = runTest(scheduler) {
        TransferPackAppFixtures.seedHeater(graph)
        graph.attachmentStorage.store.files.remove(TransferPackAppFixtures.MANUAL_AT)
        val vm = model()

        vm.create(listOf(AssetId(HEATER)), "")
        advanceUntilIdle()

        assertEquals(PackPhase.IDLE, vm.state.value.phase)
        assertEquals(
            listOf("Transfer Pack not created: 1 attachment file is missing. Nothing was changed."),
            vm.state.value.errors,
        )
        assertEquals(emptyList<String>(), packs())
        assertEquals(emptyList<String>(), workFiles())
    }

    @Test fun noFolderSaysTheReusedSentence() = runTest(scheduler) {
        TransferPackAppFixtures.seedHeater(graph)
        graph.attachmentStorage.state = StoreState.NotConfigured
        val vm = model()

        vm.create(listOf(AssetId(HEATER)), "")
        advanceUntilIdle()

        assertEquals(listOf("Choose an attachment folder in Settings first"), vm.state.value.errors)
        assertEquals(emptyList<String>(), packs())
    }

    /**
     * P77-20's generic reason, ratified: a pack that cannot be written — here its cache is a file, not a folder — says
     * so, and leaves no pack behind.
     */
    @Test fun aPackThatCannotBeWrittenSaysP77_20sGenericReason() = runTest(scheduler) {
        TransferPackAppFixtures.seedHeater(graph)
        val blocked = File(cache, "blocked").apply { writeText("not a folder") }
        val vm = model(root = blocked)

        vm.create(listOf(AssetId(HEATER)), "")
        advanceUntilIdle()

        assertEquals(PackPhase.IDLE, vm.state.value.phase)
        assertEquals(listOf("Transfer Pack not created: the file could not be written. Nothing was changed."), vm.state.value.errors)
        assertEquals(emptyList<String>(), packs())
    }

    /**
     * R77-CREATE-SAFETY (MN-1): a pack whose mark would leave the estate entangled — merged history, a held pump whose
     * motor, not held, still names it as its parent — is refused at Create with P77-20's entangled reason, before any
     * file exists, so it can never be shared and then fail to mark.
     */
    @Test fun anEntangledEstateRefusesCreateWithP77_20() = runTest(scheduler) {
        pumpAndMotor()
        heldPump()
        val vm = model()

        vm.create(listOf(AssetId("g1")), "")
        advanceUntilIdle()

        assertEquals(PackPhase.IDLE, vm.state.value.phase)
        assertEquals(
            listOf("Transfer Pack not created: records on this phone still point to a transferred asset. Nothing was changed."),
            vm.state.value.errors,
        )
        assertEquals(emptyList<String>(), packs())
        assertEquals(emptyList<String>(), workFiles())
        assertEquals(listOf("out-p1"), graph.transferRecords.all().map { it.id })
    }

    /** A pump, its motor (a component) and an unrelated opener, all live. */
    private suspend fun pumpAndMotor() {
        graph.assets.upsert(com.loosecannon.servicetag.core.model.Asset(AssetId("p1"), "Example Pump", createdAt = 100L, updatedAt = 100L))
        graph.assets.upsert(
            com.loosecannon.servicetag.core.model.Asset(
                AssetId("m1"), "Example Pump Motor", parentAssetId = AssetId("p1"), createdAt = 100L, updatedAt = 100L,
            ),
        )
        graph.assets.upsert(com.loosecannon.servicetag.core.model.Asset(AssetId("g1"), "Sample Garage Door Opener", createdAt = 100L, updatedAt = 100L))
    }

    /** Merged history: the pump alone held here, so its motor, which stays, names a held parent. */
    private suspend fun heldPump() = graph.transferRecords.append(
        TransferRecord(
            id = "out-p1", assetId = AssetId("p1"), kind = TransferKind.OUT, packId = "0f1e2d3c-p1",
            lineage = emptyList(), at = 1_758_960_000_000L, packSha256 = "ab".repeat(32),
            nameSnapshot = "Example Pump", note = "",
        ),
    )

    /** B4 hand-off 2: a component that left since the review is refused at Create, before any file exists. */
    @Test fun aHeldComponentForcedInIsRefusedBeforeAnyFile() = runTest(scheduler) {
        TransferPackAppFixtures.seedHeater(graph)
        graph.assets.upsert(
            com.loosecannon.servicetag.core.model.Asset(
                id = AssetId("a1"), name = "Example Anode Rod", parentAssetId = AssetId(HEATER), createdAt = 100L, updatedAt = 100L,
            ),
        )
        graph.transferRecords.append(
            TransferRecord(
                id = "out-a1", assetId = AssetId("a1"), kind = TransferKind.OUT, packId = "0f1e2d3c-a1",
                lineage = emptyList(), at = 1_758_960_000_000L, packSha256 = "ab".repeat(32),
                nameSnapshot = "Example Anode Rod", note = "",
            ),
        )
        val vm = model()

        vm.create(listOf(AssetId(HEATER)), "")
        advanceUntilIdle()

        assertEquals(listOf("These assets are already marked transferred out."), vm.state.value.errors)
        assertEquals(emptyList<String>(), packs())
        assertEquals(emptyList<String>(), workFiles())
    }

    /** mn-2: a refused Create's line belongs to its review; changing the selection clears it before the next review. */
    @Test fun aRefusedCreatesLineDoesNotOutliveItsReview() = runTest(scheduler) {
        TransferPackAppFixtures.seedHeater(graph)
        graph.assets.upsert(
            com.loosecannon.servicetag.core.model.Asset(
                id = AssetId("a1"), name = "Example Anode Rod", parentAssetId = AssetId(HEATER), createdAt = 100L, updatedAt = 100L,
            ),
        )
        graph.transferRecords.append(
            TransferRecord(
                id = "out-a1", assetId = AssetId("a1"), kind = TransferKind.OUT, packId = "0f1e2d3c-a1",
                lineage = emptyList(), at = 1_758_960_000_000L, packSha256 = "ab".repeat(32),
                nameSnapshot = "Example Anode Rod", note = "",
            ),
        )
        val vm = model()
        vm.create(listOf(AssetId(HEATER)), "")
        advanceUntilIdle()
        assertEquals(listOf("These assets are already marked transferred out."), vm.state.value.errors)

        vm.clearErrors() // the owner goes back and changes the selection

        assertEquals(emptyList<String>(), vm.state.value.errors)
        assertEquals(PackPhase.IDLE, vm.state.value.phase)
    }

    /**
     * mn-4: marking refused because the estate left behind would be entangled says P77-70 and writes nothing. The
     * entanglement is merged history's — a held pump whose motor, not held, still names it as its parent — and it
     * lands after the pack was made (Create would refuse it, R77-CREATE-SAFETY), so this is marking's own re-check.
     */
    @Test fun anEntangledEstateIsP77_70() = runTest(scheduler) {
        pumpAndMotor()
        val vm = model()
        vm.create(listOf(AssetId("g1")), "")
        advanceUntilIdle()
        assertEquals(PackPhase.READY, vm.state.value.phase)
        heldPump()

        vm.mark()
        advanceUntilIdle()

        assertEquals(
            listOf("Could not mark these assets: records on this phone still point to a transferred asset. Nothing was changed."),
            vm.state.value.errors,
        )
        assertEquals(listOf("out-p1"), graph.transferRecords.all().map { it.id })
        assertEquals(0, sweeps)
    }

    /** mn-4: a mark that fails outright says P77-53, and nothing is written or swept. */
    @Test fun aMarkThatFailsIsP77_53() = runTest(scheduler) {
        TransferPackAppFixtures.seedHeater(graph)
        val failing = MarkTransferredOut(
            BackupRepositories(
                graph.assets, graph.groups, graph.tags, graph.links, graph.definitions, graph.profiles, graph.schedules,
                graph.closures, graph.events, graph.attachments, graph.references, graph.seasonActivations,
                graph.conditions, graph.healthSubjects, graph.categories, graph.serviceCases, graph.serviceCaseEntries,
                graph.loans, graph.transferRecords, graph.assetSuccessions,
            ),
            graph.uow, graph.ids, graph.clock,
        ) { throw IllegalStateException("the store would not take the mark") }
        val vm = model(mark = failing)
        vm.create(listOf(AssetId(HEATER)), "")
        advanceUntilIdle()

        vm.mark()
        advanceUntilIdle()

        assertEquals(listOf("Could not mark these assets. Nothing was changed."), vm.state.value.errors)
        assertEquals(emptyList<TransferRecord>(), graph.transferRecords.all())
        assertEquals(AssetStatus.ACTIVE, graph.assets.get(AssetId(HEATER))!!.status)
        assertEquals(0, sweeps)
    }

    @Test fun cancelLeavesNoFileAndNoRecord() = runTest(scheduler) {
        TransferPackAppFixtures.seedHeater(graph)
        val gate = CompletableDeferred<Unit>()
        val inner = graph.attachmentStorage.store
        val gated = object : AttachmentStorage {
            override fun state(): StoreState = graph.attachmentStorage.state()
            override fun store(): AttachmentStore = object : AttachmentStore by inner {
                override suspend fun open(locator: String): InputStream? {
                    gate.await()
                    return inner.open(locator)
                }
            }
        }
        val vm = model(storage = gated)
        vm.create(listOf(AssetId(HEATER)), "")
        advanceUntilIdle()
        assertEquals(PackPhase.CREATING, vm.state.value.phase)

        store.clear() // the owner leaves while the pack is being written
        advanceUntilIdle()

        assertEquals(emptyList<String>(), packs())
        assertEquals(emptyList<String>(), workFiles())
        assertEquals(emptyList<TransferRecord>(), graph.transferRecords.all())
    }

    @Test fun markOnlyAfterCreation() = runTest(scheduler) {
        TransferPackAppFixtures.seedHeater(graph)
        val vm = model()

        vm.mark()
        advanceUntilIdle()

        assertFalse(vm.state.value.offersActions)
        assertEquals(emptyList<TransferRecord>(), graph.transferRecords.all())
        assertEquals(AssetStatus.ACTIVE, graph.assets.get(AssetId(HEATER))!!.status)
        assertEquals(0, sweeps)
    }

    @Test fun markWritesThenSweepsOnce() = runTest(scheduler) {
        val vm = readyModel()
        val events = eventsOf(vm)

        vm.mark()
        advanceUntilIdle()

        val records = graph.transferRecords.all()
        assertEquals(listOf(TransferKind.OUT), records.map { it.kind })
        assertEquals(AssetId(HEATER), records.single().assetId)
        assertEquals(AssetStatus.ARCHIVED, graph.assets.get(AssetId(HEATER))!!.status)
        assertEquals("exactly one sweep, after the write", 1, sweeps)
        assertEquals("no event: the state ends the flow (#84 C4)", emptyList<TransferPackEvent>(), events)
        assertEquals(PackPhase.MARKED, vm.state.value.phase)
    }

    /** #84 C4 (77-2): a mark that nobody collects still ends the flow, because the screen reads `MARKED` from the state. */
    @Test fun aMarkWithNoCollectorStillEndsTheFlow() = runTest(scheduler) {
        val vm = readyModel()

        vm.mark()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(PackPhase.MARKED, state.phase)
        assertFalse(state.offersActions)
        assertEquals(emptyList<String>(), state.errors)
        assertEquals("exactly one sweep", 1, sweeps)
    }

    @Test fun notNowWritesNothing() = runTest(scheduler) {
        val vm = readyModel()
        val events = eventsOf(vm)

        vm.notNow()
        advanceUntilIdle()

        assertEquals(listOf<TransferPackEvent>(TransferPackEvent.Leave), events)
        assertEquals(emptyList<TransferRecord>(), graph.transferRecords.all())
        assertEquals(AssetStatus.ACTIVE, graph.assets.get(AssetId(HEATER))!!.status)
        assertEquals(0, sweeps)
    }

    @Test fun aChangeSinceCreationIsP77_51() = runTest(scheduler) {
        val vm = readyModel()
        val heater = graph.assets.get(AssetId(HEATER))!!
        graph.assets.upsert(heater.copy(location = "Example Basement", updatedAt = heater.updatedAt + 1))

        vm.mark()
        advanceUntilIdle()

        assertEquals(listOf("Example Water Heater changed after this Transfer Pack was made. Create it again."), vm.state.value.errors)
        assertEquals(PackPhase.READY, vm.state.value.phase)
        assertEquals(emptyList<TransferRecord>(), graph.transferRecords.all())
        assertEquals(0, sweeps)
    }

    @Test fun anAlreadyHeldAssetIsP77_57() = runTest(scheduler) {
        val vm = readyModel()
        graph.transferRecords.append(
            TransferRecord(
                id = "out-other", assetId = AssetId(HEATER), kind = TransferKind.OUT, packId = "0f1e2d3c-other",
                lineage = emptyList(), at = 1_758_960_000_000L, packSha256 = "ab".repeat(32),
                nameSnapshot = "Example Water Heater", note = "",
            ),
        )

        vm.mark()
        advanceUntilIdle()

        assertEquals(listOf("These assets are already marked transferred out."), vm.state.value.errors)
        assertEquals(1, graph.transferRecords.all().size)
        assertEquals(0, sweeps)
    }

    @Test fun aLoanSinceCreationIsP77_17() = runTest(scheduler) {
        val vm = readyModel()
        graph.loans.upsert(loanRow("l1", assetId = HEATER, lentOn = "2026-09-26", borrower = "Example Buyer"))

        vm.mark()
        advanceUntilIdle()

        assertEquals(listOf("Example Water Heater is lent out. Mark it returned first."), vm.state.value.errors)
        assertEquals(emptyList<TransferRecord>(), graph.transferRecords.all())
        assertEquals(0, sweeps)
    }

    @Test fun saveACopyCopiesEveryByteOrSaysP77_55() = runTest(scheduler) {
        val vm = readyModel()
        val events = eventsOf(vm)
        val sink = ByteArrayOutputStream()

        vm.saveCopy { sink }
        advanceUntilIdle()
        vm.saveCopy { throw IOException("the provider refused") }
        advanceUntilIdle()

        assertArrayEquals(File(File(cache, "transfer"), vm.state.value.fileName!!).readBytes(), sink.toByteArray())
        assertEquals(
            listOf<TransferPackEvent>(TransferPackEvent.Say("Saved"), TransferPackEvent.Say("Could not save a copy.")),
            events,
        )
    }

    @Test fun leavingDeletesThePack() = runTest(scheduler) {
        readyModel()
        assertEquals(1, packs().size)

        store.clear()

        assertEquals(emptyList<String>(), packs())
    }

    @Test fun aRestoredScreenKeepsAFreshPack() = runTest(scheduler) {
        val saved = SavedStateHandle()
        val first = readyModel(saved)
        val name = first.state.value.fileName

        // A process death: a new model over the same saved state, the file still in the cache.
        val restored = model(saved)
        advanceUntilIdle()

        assertEquals(PackPhase.READY, restored.state.value.phase)
        assertEquals(name, restored.state.value.fileName)
        assertTrue(restored.state.value.offersActions)
        assertNull(restored.state.value.goneLine)
    }

    @Test fun aRestoredScreenWhoseFileIsGoneOffersNothing() = runTest(scheduler) {
        val saved = SavedStateHandle()
        readyModel(saved)
        File(cache, "transfer").listFiles()!!.forEach { it.delete() }

        val restored = model(saved)
        advanceUntilIdle()
        restored.mark()
        advanceUntilIdle()

        assertFalse(restored.state.value.offersActions)
        assertEquals("This Transfer Pack is no longer on this phone. Create it again.", restored.state.value.goneLine)
        assertNull("nothing to share", restored.packFile())
        assertEquals(emptyList<TransferRecord>(), graph.transferRecords.all())
        assertEquals(0, sweeps)
    }

    /**
     * #84 C6 (77-4a, D-1): Share that finds the verified pack's file gone says P77-60 and offers nothing more; a file
     * still there answers itself and changes nothing.
     */
    @Test fun shareFindingNoFileSaysP77_60AndOffersNothing() = runTest(scheduler) {
        val vm = readyModel()
        val ready = vm.state.value

        val file = vm.packFile()
        assertEquals(ready.fileName, file?.name)
        assertEquals("a present file changes nothing", ready, vm.state.value)

        file!!.delete()
        assertNull("nothing to share", vm.packFile())
        assertEquals("This Transfer Pack is no longer on this phone. Create it again.", vm.state.value.goneLine)
        assertFalse(vm.state.value.offersActions)
    }
}
