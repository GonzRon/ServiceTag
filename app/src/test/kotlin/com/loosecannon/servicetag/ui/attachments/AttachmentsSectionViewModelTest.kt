package com.loosecannon.servicetag.ui.attachments

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentLocator
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.ports.StoreIoException
import com.loosecannon.servicetag.core.ports.AttachmentStore
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.ports.StoredBytes
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.usecase.AddAttachment
import com.loosecannon.servicetag.core.usecase.AddAttachmentCommand
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.AttachmentResult
import com.loosecannon.servicetag.core.usecase.DeleteAttachment
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.core.usecase.UpdateAttachment
import com.loosecannon.servicetag.core.usecase.UpdateAttachmentCommand
import com.loosecannon.servicetag.testing.FakeGraph
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.properties.Delegates
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
 * The DOCUMENTS section's one ViewModel, against a Room-backed [FakeGraph] so the observe flow,
 * the mapper and the use cases are the production ones. `viewModelScope` dispatches on
 * `Dispatchers.Main`, so the main dispatcher is an unconfined test one for the length of each
 * test; every assertion waits for a state rather than reading `value` after a write.
 *
 * The model's own work — the scan, the three writes, the folder re-read — runs on a
 * `StandardTestDispatcher` over the same [scheduler], so all of it happens on this test's thread
 * and in this test's time. Every case that builds a model ends with [clearModels], which cancels
 * that work and lets the cancellation finish before `runTest` returns (#65).
 *
 * `messages` has no replay by design, so a test that wants a line subscribes before the call
 * that produces it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentsSectionViewModelTest {

    private companion object {
        val READY = StoreState.Ready("Attachments", "com.example.provider")

        /** `AttachmentsSectionViewModel`'s own `SharingStarted.WhileSubscribed` window. */
        const val SUBSCRIPTION_GRACE_MS = 5_000L
    }

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    /**
     * Set by [hotTub], the first line of every case that needs one (all but the pure one).
     * `AssetId` is a value class, so `lateinit` itself is not allowed on it — this delegate is the
     * same "unset reads throw" contract without that restriction.
     */
    private var assetId: AssetId by Delegates.notNull()

    /**
     * Every model the test builds lives in here, so [clearModels] can clear it: `viewModelScope` is
     * cancelled by the store in production and by nothing at all in a plain JVM test, which left
     * the scan and the `stateIn` sharer running past `graph.close()` and `resetMain()`. `tearDown`
     * clears it again, as a safety net that by then has nothing left to cancel.
     */
    private val store = ViewModelStore()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        store.clear()
        graph.close()
        Dispatchers.resetMain()
    }

    /**
     * The one asset every case but the pure one needs, seeded inside the test's own scope rather
     * than `@Before`: a `runBlocking` there would queue this write on [scheduler] and nothing
     * outside a `TestScope` ever drives that scheduler forward, so it would never actually run.
     * Called as the first line of each `runTest { }` body, the same way every other assertion in
     * this class already awaits a state instead of reading one cold.
     */
    private suspend fun TestScope.hotTub(): Asset {
        val asset = graph.createAsset.run(AssetCommand(name = "Hot tub"))
        assetId = asset.id
        return asset
    }

    private fun model(
        owner: AttachmentOwner = AttachmentOwner.OfAsset(assetId),
        today: () -> String = { "2026-09-16" },
        updateAttachment: UpdateAttachment = graph.updateAttachment,
        deleteAttachment: DeleteAttachment = graph.deleteAttachment,
        storage: AttachmentStorage = graph.attachmentStorage,
        addAttachment: AddAttachment = graph.addAttachment,
    ): AttachmentsSectionViewModel {
        val factory = viewModelFactory {
            initializer {
                AttachmentsSectionViewModel(
                    owner, graph.attachments, storage, addAttachment,
                    updateAttachment, deleteAttachment, graph.thumbnails,
                    today = today,
                    // The scheduler Main and the database already share: no pool thread under test.
                    io = StandardTestDispatcher(scheduler),
                )
            }
        }
        return ViewModelProvider.create(store, factory)[
            AttachmentLocator.dirFor(owner),
            AttachmentsSectionViewModel::class,
        ]
    }

    /**
     * The last line of every case that builds a model. A signal a case waits for is never the end
     * of the work behind it — a save's `refresh` bump and the scan pass it starts come after — so
     * the models are cleared here, inside `runTest`, and the cancellation that starts is drained on
     * the test's own clock before the body returns. Clearing only in `tearDown` came after
     * `runTest` had stopped draining, and left that cancellation to finish wherever it could.
     */
    private fun TestScope.clearModels() {
        store.clear()
        advanceUntilIdle()
    }

    /**
     * A transaction that will not commit. Closing the database would do it too, but that also
     * kills the row flow the section is collecting, which is a second failure the test is not
     * about.
     */
    private val brokenUow = object : UnitOfWork {
        override suspend fun <T> write(block: suspend () -> T): T = throw IOException("no disk")
        override suspend fun <T> read(block: suspend () -> T): T = block()
    }

    private fun picked(name: String, mime: String = "application/pdf", body: String = "x") =
        PickedFile(name, mime, body.length.toLong()) { body.toByteArray().inputStream() }

    /** A pick whose bytes cannot be read: the store's `put` throws while copying it. */
    private fun broken(name: String) =
        PickedFile(name, "application/pdf", 1L) { throw IOException("the provider went away") }

    @Test fun aFreshInstallSaysTheStoreIsNotConfiguredAndListsNothing() = runTest {
        hotTub()
        graph.attachmentStorage.state = StoreState.NotConfigured
        val vm = model()
        backgroundScope.launch { vm.state.collect() }

        val initial = vm.state.first()
        assertEquals(StoreState.NotConfigured, initial.store)
        assertEquals(emptyList<AttachmentRowState>(), initial.rows)
        assertNull(initial.progress)

        val said = async(Dispatchers.Main) { vm.messages.first() }
        vm.add(listOf(picked("Guide.pdf")))
        assertEquals("Choose an attachment folder in Settings first", said.await())
        assertEquals(emptyList<AttachmentRowState>(), vm.state.value.rows)
        assertTrue(graph.attachmentStorage.store.files.isEmpty())
        clearModels()
    }

    @Test fun withAFolderChosenAddedFilesAppearAsRowsOrderedByName() = runTest {
        hotTub()
        val vm = model()
        backgroundScope.launch { vm.state.collect() }

        vm.add(listOf(picked("Zebra.pdf")))
        vm.state.first { it.rows.size == 1 }
        vm.add(listOf(picked("Apple.pdf")))

        val state = vm.state.first { it.rows.size == 2 }
        assertEquals(READY, state.store)
        assertEquals(listOf("Apple.pdf", "Zebra.pdf"), state.rows.map { it.displayName })
        assertEquals(
            listOf(AttachmentKind.DOCUMENT, AttachmentKind.DOCUMENT),
            state.rows.map { it.kind },
        )
        assertEquals(listOf(1L, 1L), state.rows.map { it.sizeBytes })
        assertEquals(listOf(false, false), state.rows.map { it.isImage })
        // The bytes really are in the store under each row's own locator, which is what
        // `present` claims: the flag itself is proved false by `aRowWhoseBytesAreGone...`.
        assertEquals(
            state.rows.map { it.locator }.sorted(),
            graph.attachmentStorage.store.files.keys.sorted(),
        )
        assertEquals(listOf(true, true), state.rows.map { it.present })
        assertNull(state.progress)
        clearModels()
    }

    @Test fun aScanThatCannotReachTheFolderSaysSoAndKeepsTheRowsAndTheCollectorAlive() = runTest {
        hotTub()
        // Seed one row through a healthy model, drop that model (the provider caches by owner),
        // then watch the same owner through a storage whose presence check throws: the row
        // stays, the section says so, and once the store behaves a refresh recovers — so the
        // collector did not die with the first failed pass.
        val healthy = model()
        backgroundScope.launch { healthy.state.collect() }
        healthy.add(listOf(picked("Guide.pdf")))
        healthy.state.first { it.rows.size == 1 }
        clearModels()

        val flaky = FlakyExistsStorage(graph.attachmentStorage)
        val vm = model(storage = flaky)
        val said = async(Dispatchers.Main) { vm.messages.first() }
        backgroundScope.launch { vm.state.collect() }
        assertEquals(SCAN_FAILED, said.await())
        assertEquals("Guide.pdf", vm.state.first { it.rows.size == 1 }.rows.single().displayName)

        flaky.healthy = true
        vm.refreshStore()
        assertEquals(true, vm.state.first { it.rows.singleOrNull()?.present == true }.rows.single().present)
        clearModels()
    }

    @Test fun theProgressLineNamesTheFileNumberAndTheTotal() {
        assertEquals("Adding 3 of 8…", addingProgressLine(3, 8))
    }

    @Test fun addingSeveralFilesReportsProgressAndKeepsGoingPastAFailure() = runTest {
        hotTub()
        // The first file's bytes are held at the store until the test has seen the first progress
        // line: otherwise all three adds could finish before the collector ever observes a
        // non-null progress (the conflated state flow keeps only the latest). The gate suspends
        // and never blocks — the add runs on this test's own thread now, and a blocking wait
        // there would stop the only thread that could ever open it.
        val gated = GatedPutStorage(graph.attachmentStorage)
        val vm = model(
            addAttachment = AddAttachment(
                graph.attachments, graph.assets, graph.events, gated,
                graph.uow, graph.ids, graph.clock,
            ),
        )
        val seen = mutableListOf<AttachmentsSectionState>()
        backgroundScope.launch { vm.state.collect { seen += it } }
        val said = mutableListOf<String>()
        backgroundScope.launch(Dispatchers.Main) { vm.messages.collect { said += it } }

        vm.add(listOf(picked("First.pdf"), broken("Middle.pdf"), picked("Last.pdf")))
        vm.state.first { it.progress == "Adding 1 of 3…" }
        // The first copy is parked at the gate, and the section is saying so.
        gated.reached.await()
        assertEquals("Adding 1 of 3…", vm.state.value.progress)
        gated.gate.complete(Unit)

        val state = vm.state.first { it.rows.size == 2 && it.progress == null }
        assertEquals(listOf("First.pdf", "Last.pdf"), state.rows.map { it.displayName })
        assertEquals(listOf("Could not add Middle.pdf"), said)
        assertNull(state.progress)
        // Nothing was left behind for the file that failed mid-copy.
        assertEquals(2, graph.attachmentStorage.store.files.size)

        val progressLines = seen.mapNotNull { it.progress }.distinct()
        assertTrue("the gated first file's progress line was observed", "Adding 1 of 3…" in progressLines)
        assertEquals(
            emptyList<String>(),
            progressLines - setOf("Adding 1 of 3…", "Adding 2 of 3…", "Adding 3 of 3…"),
        )
        clearModels()
    }

    @Test fun anAccessLostStoreRefusesAddAndSaysWhyOnce() = runTest {
        hotTub()
        val vm = model()
        backgroundScope.launch { vm.state.collect() }
        vm.add(listOf(picked("Guide.pdf")))
        val before = vm.state.first { it.rows.size == 1 }.rows

        graph.attachmentStorage.state = StoreState.AccessLost("Attachments")
        val said = async(Dispatchers.Main) { vm.messages.first() }
        vm.add(listOf(picked("Another.pdf")))

        assertEquals("The attachment folder is not available", said.await())
        // The refusal is news about the folder too: the section re-reads it and flips over.
        val after = vm.state.first { it.store is StoreState.AccessLost }
        assertEquals(StoreState.AccessLost("Attachments"), after.store)
        assertEquals(before.map { it.id }, after.rows.map { it.id })
        assertEquals(1, graph.attachmentStorage.store.files.size)
        clearModels()
    }

    @Test fun aRowWhoseBytesAreGoneIsMarkedNotPresent() = runTest {
        hotTub()
        val owner = AttachmentOwner.OfAsset(assetId)
        val added = graph.addAttachment.run(
            owner,
            AddAttachmentCommand(displayName = "Guide.pdf", mimeType = "application/pdf"),
            ByteSource { "x".byteInputStream() },
        )
        val row = (added as AttachmentResult.Ok).value
        // Behind the store's back: the sync tool, or the owner's file manager, removed the file.
        graph.attachmentStorage.store.files.remove(row.storageLocator)

        val vm = model(owner)
        backgroundScope.launch { vm.state.collect() }

        val rows = vm.state.first { it.rows.size == 1 && !it.rows.single().present }.rows
        assertEquals("Guide.pdf", rows.single().displayName)
        assertFalse(rows.single().present)
        assertNull(rows.single().thumbnail)
        clearModels()
    }

    @Test fun savingRenamesTheRowAndLeavesTheLocatorAlone() = runTest {
        hotTub()
        val vm = model()
        backgroundScope.launch { vm.state.collect() }
        vm.add(listOf(picked("guide.pdf")))
        val before = vm.state.first { it.rows.size == 1 }.rows.single()

        vm.save(
            before.id,
            UpdateAttachmentCommand("Installation guide", AttachmentKind.MANUAL, "2026-09-14", ""),
        )

        val after = vm.state
            .first { it.rows.singleOrNull()?.displayName == "Installation guide" }
            .rows.single()
        assertEquals(AttachmentKind.MANUAL, after.kind)
        assertEquals("2026-09-14", after.capturedOn)
        assertEquals(before.locator, after.locator)
        assertTrue(before.locator in graph.attachmentStorage.store.files)
        clearModels()
    }

    @Test fun savingNothingIsSilent() = runTest {
        hotTub()
        val vm = model()
        backgroundScope.launch { vm.state.collect() }
        vm.add(listOf(picked("guide.pdf")))
        val row = vm.state.first { it.rows.size == 1 }.rows.single()

        val said = mutableListOf<String>()
        backgroundScope.launch(Dispatchers.Main) { vm.messages.collect { said += it } }
        // Both `saved` signals are awaited, not counted after the fact. The two saves no longer
        // race on a pool thread — they run on this test's scheduler — but the order it
        // interleaves them in is still not this case's to assume: the rename's state change is
        // **not** a barrier for the `Unchanged` one. Awaiting exactly two — both subscribed
        // before either call, `saved` having no replay — is the barrier that says both have been
        // the whole way through the path, whatever order they took. **How this fails:** if only
        // one signal ever lands, `await()` never returns, so the RED shape is a `runTest` timeout
        // and not an assertion message — the same shape, for the same reason, as the cold-launch
        // badge case in `ReminderHealthViewModelTest`.
        val closed = async(Dispatchers.Main) { vm.saved.take(2).toList() }

        vm.save(row.id, UpdateAttachmentCommand(row.displayName, row.kind, row.capturedOn, row.notes))
        vm.save(row.id, UpdateAttachmentCommand("Renamed.pdf", row.kind, row.capturedOn, row.notes))

        // Silent, but not stuck: nothing to write still closes the sheet.
        assertEquals(listOf(row.id, row.id), closed.await())
        vm.state.first { it.rows.singleOrNull()?.displayName == "Renamed.pdf" }
        assertEquals(emptyList<String>(), said)
        clearModels()
    }

    @Test fun deletingRemovesTheRowAndTheBytes() = runTest {
        hotTub()
        val vm = model()
        backgroundScope.launch { vm.state.collect() }
        vm.add(listOf(picked("guide.pdf")))
        val row = vm.state.first { it.rows.size == 1 }.rows.single()

        // Subscribed before the call: `deleted` fires once the row and the bytes are both gone,
        // which is the only barrier that says the sweep after the transaction has run.
        val gone = async(Dispatchers.Main) { vm.deleted.first() }
        vm.delete(row.id)

        assertEquals(row.id, gone.await())
        assertEquals(emptyList<AttachmentRowState>(), vm.state.first { it.rows.isEmpty() }.rows)
        assertFalse(row.locator in graph.attachmentStorage.store.files)
        clearModels()
    }

    @Test fun anEventOwnerSeesOnlyItsOwnFiles() = runTest {
        hotTub()
        val event = graph.logEvent.run(
            EventCommand(
                assetId = assetId,
                profileId = null,
                kind = EventKind.MAINTENANCE,
                title = "Filter change",
                occurredOn = "2026-09-14",
                occurredTime = null,
                tzId = "UTC",
                notes = "",
                values = emptyMap(),
                consumables = emptyList(),
            ),
        )
        val assetVm = model()
        val eventVm = model(AttachmentOwner.OfEvent(event.id))
        backgroundScope.launch { assetVm.state.collect() }
        backgroundScope.launch { eventVm.state.collect() }

        assetVm.add(listOf(picked("Asset.pdf")))
        assetVm.state.first { it.rows.size == 1 }
        eventVm.add(listOf(picked("Event.pdf")))

        assertEquals(
            listOf("Event.pdf"),
            eventVm.state.first { it.rows.size == 1 }.rows.map { it.displayName },
        )
        assertEquals(listOf("Asset.pdf"), assetVm.state.value.rows.map { it.displayName })
        clearModels()
    }

    @Test fun capturedOnDefaultsToTodayForAPickedFile() = runTest {
        hotTub()
        val vm = model(today = { "2026-09-16" })
        backgroundScope.launch { vm.state.collect() }
        vm.add(listOf(picked("guide.pdf")))
        assertEquals("2026-09-16", vm.state.first { it.rows.size == 1 }.rows.single().capturedOn)
        clearModels()
    }

    @Test fun comingBackFromSettingsWithAFolderChosenFlipsTheSectionOver() = runTest {
        hotTub()
        graph.attachmentStorage.state = StoreState.NotConfigured
        val vm = model()
        backgroundScope.launch { vm.state.collect() }
        assertEquals(StoreState.NotConfigured, vm.state.first().store)

        // Exactly what the status block asked for, and nothing else: no add, no save, no delete.
        graph.attachmentStorage.state = READY
        vm.refreshStore()

        assertEquals(READY, vm.state.first { it.store is StoreState.Ready }.store)
        clearModels()
    }

    @Test fun aResubscriptionAlsoReReadsTheFolder() = runTest {
        hotTub()
        graph.attachmentStorage.state = StoreState.NotConfigured
        val vm = model()
        val watching = launch { vm.state.collect() }
        assertEquals(StoreState.NotConfigured, vm.state.first().store)

        // The screen goes away (Settings is pushed over it) for longer than the sharing grace.
        watching.cancelAndJoin()
        advanceTimeBy(SUBSCRIPTION_GRACE_MS * 2)
        graph.attachmentStorage.state = READY

        backgroundScope.launch { vm.state.collect() }
        assertEquals(READY, vm.state.first { it.store is StoreState.Ready }.store)
        clearModels()
    }

    @Test fun aSaveThatCannotBeWrittenSaysSoAndLeavesTheSheetOpen() = runTest {
        hotTub()
        // The write throws rather than refusing: an escaping exception used to take the process.
        val vm = model(updateAttachment = UpdateAttachment(graph.attachments, brokenUow, graph.clock))
        backgroundScope.launch { vm.state.collect() }
        graph.addAttachment.run(
            AttachmentOwner.OfAsset(assetId),
            AddAttachmentCommand(displayName = "guide.pdf", mimeType = "application/pdf"),
            ByteSource { "x".byteInputStream() },
        )
        val row = vm.state.first { it.rows.size == 1 }.rows.single()

        val closed = mutableListOf<String>()
        backgroundScope.launch(Dispatchers.Main) { vm.saved.collect { closed += it } }
        val said = async(Dispatchers.Main) { vm.messages.first() }

        vm.save(row.id, UpdateAttachmentCommand("Installation guide", row.kind, row.capturedOn, ""))

        assertEquals("Could not save Installation guide", said.await())
        assertEquals(emptyList<String>(), closed)
        clearModels()
    }

    @Test fun aRefusedSaveKeepsTheSheetOpenAndAGoodOneClosesIt() = runTest {
        hotTub()
        val vm = model()
        backgroundScope.launch { vm.state.collect() }
        vm.add(listOf(picked("guide.pdf")))
        val row = vm.state.first { it.rows.size == 1 }.rows.single()

        val closed = mutableListOf<String>()
        backgroundScope.launch(Dispatchers.Main) { vm.saved.collect { closed += it } }
        val said = async(Dispatchers.Main) { vm.messages.first() }

        vm.save(row.id, UpdateAttachmentCommand("   ", row.kind, row.capturedOn, row.notes))
        assertEquals("Give the file a name", said.await())
        assertEquals(emptyList<String>(), closed)
        assertEquals("guide.pdf", vm.state.value.rows.single().displayName)

        vm.save(row.id, UpdateAttachmentCommand("Renamed.pdf", row.kind, row.capturedOn, row.notes))
        vm.state.first { it.rows.singleOrNull()?.displayName == "Renamed.pdf" }
        assertEquals(listOf(row.id), closed)
        clearModels()
    }

    @Test fun aDeleteThatCannotBeWrittenSaysSoInsteadOfCrashing() = runTest {
        hotTub()
        val vm = model(
            deleteAttachment = DeleteAttachment(graph.attachments, graph.attachmentStorage, brokenUow),
        )
        backgroundScope.launch { vm.state.collect() }
        graph.addAttachment.run(
            AttachmentOwner.OfAsset(assetId),
            AddAttachmentCommand(displayName = "guide.pdf", mimeType = "application/pdf"),
            ByteSource { "x".byteInputStream() },
        )
        val row = vm.state.first { it.rows.size == 1 }.rows.single()

        val gone = mutableListOf<String>()
        backgroundScope.launch(Dispatchers.Main) { vm.deleted.collect { gone += it } }
        val said = async(Dispatchers.Main) { vm.messages.first() }
        vm.delete(row.id)

        assertEquals("Could not delete that file", said.await())
        assertEquals(emptyList<String>(), gone)
        // The bytes are still there, because the row that names them is still there.
        assertTrue(row.locator in graph.attachmentStorage.store.files)
        clearModels()
    }

    /**
     * The mime is deliberately not an image's: `fromCamera` alone decides the kind, so this
     * proves the flag travels from the pick to the command rather than re-proving the inference.
     * A JVM test cannot hold an image row anyway — the thumbnail pass needs `BitmapFactory`.
     */
    @Test fun aCameraCaptureIsAPhotoWhateverElseItLooksLike() = runTest {
        hotTub()
        val vm = model()
        backgroundScope.launch { vm.state.collect() }
        vm.add(
            listOf(
                PickedFile("shot.pdf", "application/pdf", 1L, fromCamera = true) {
                    "x".byteInputStream()
                },
            ),
        )
        val row = vm.state.first { it.rows.size == 1 }.rows.single()
        assertEquals(AttachmentKind.PHOTO, row.kind)
        assertFalse(row.isImage)
        clearModels()
    }

    /**
     * #65: nothing the model does reaches the folder or a transaction on any thread but this
     * test's. The seams sit on the model's five hops — the folder re-read a subscription starts,
     * the scan pass, add, save and delete — so a hop left on a real dispatcher shows up here as a
     * pool thread, by name.
     */
    @Test fun everyPathRunsOnTheTestsThread() = runTest {
        val testThread = Thread.currentThread()
        hotTub()
        val seams = ThreadSeams(graph.attachmentStorage, graph.uow)
        val vm = model(
            storage = seams.storage,
            addAttachment = AddAttachment(
                graph.attachments, graph.assets, graph.events, seams.storage,
                seams.uow, graph.ids, graph.clock,
            ),
            updateAttachment = UpdateAttachment(graph.attachments, seams.uow, graph.clock),
            deleteAttachment = DeleteAttachment(graph.attachments, seams.storage, seams.uow),
        )
        // A subscription, and the folder re-read it starts.
        val watching = launch { vm.state.collect() }

        // An add, and the scan pass behind it.
        vm.add(listOf(picked("Guide.pdf")))
        val row = vm.state.first { it.rows.size == 1 }.rows.single()

        val closed = async(Dispatchers.Main) { vm.saved.first() }
        vm.save(row.id, UpdateAttachmentCommand("Renamed.pdf", row.kind, row.capturedOn, row.notes))
        closed.await()

        val gone = async(Dispatchers.Main) { vm.deleted.first() }
        vm.delete(row.id)
        gone.await()

        // A resubscription after the grace: only its re-read can see what changed meanwhile.
        watching.cancelAndJoin()
        advanceTimeBy(SUBSCRIPTION_GRACE_MS * 2)
        graph.attachmentStorage.state = StoreState.AccessLost("Attachments")
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.store is StoreState.AccessLost }
        clearModels()

        val elsewhere = seams.seen
            .filter { it.thread !== testThread }
            .map { "${it.what} on ${it.thread.name}" }
        assertEquals("work that ran off the test's thread", emptyList<String>(), elsewhere)
        // Every seam was reached, so the empty list above is not an empty test.
        val reached = seams.seen.map { it.what }.toSet()
        val expected = setOf("state", "store", "exists", "put", "delete", "write")
        assertEquals(expected, expected.filter { it in reached }.toSet())
    }

    /**
     * #65: a signal a case waits for is not the end of the work behind it — a save's and a
     * delete's `refresh` bump, and the scan pass each starts, come after — so [clearModels] must
     * leave the model's scope finished, not merely cancelled, before `runTest` returns.
     */
    @Test fun clearingInsideTheTestLeavesNoWorkRunning() = runTest {
        hotTub()
        val vm = model()
        backgroundScope.launch { vm.state.collect() }
        vm.add(listOf(picked("guide.pdf")))
        val row = vm.state.first { it.rows.size == 1 }.rows.single()

        val closed = async(Dispatchers.Main) { vm.saved.first() }
        vm.save(row.id, UpdateAttachmentCommand("Renamed.pdf", row.kind, row.capturedOn, row.notes))
        closed.await()
        val gone = async(Dispatchers.Main) { vm.deleted.first() }
        vm.delete(row.id)
        gone.await()
        vm.refreshStore()

        val scope = vm.viewModelScope.coroutineContext.job
        clearModels()
        assertTrue("the model's scope finished inside the test", scope.isCompleted)
    }

    /** A storage whose store answers `exists` with an IO failure until told to behave. */
    private class FlakyExistsStorage(private val real: AttachmentStorage) : AttachmentStorage {
        @Volatile var healthy = false
        override fun state() = real.state()
        override fun store(): AttachmentStore? = real.store()?.let { inner ->
            object : AttachmentStore by inner {
                override suspend fun exists(locator: String): Boolean {
                    if (!healthy) throw StoreIoException("rigged presence failure")
                    return inner.exists(locator)
                }
            }
        }
    }

    /**
     * A storage whose store's `put` says so on [reached] and then waits at [gate] until the test
     * opens it. Both suspend: the add that gets here runs on the test's own thread, which has to
     * stay free to open the gate.
     */
    private class GatedPutStorage(private val real: AttachmentStorage) : AttachmentStorage {
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        override fun state() = real.state()
        override fun store(): AttachmentStore? = real.store()?.let { inner ->
            object : AttachmentStore by inner {
                override suspend fun put(locator: String, source: ByteSource): StoredBytes {
                    reached.complete(Unit)
                    gate.await()
                    return inner.put(locator, source)
                }
            }
        }
    }

    /**
     * Notes the thread every call into the folder or a transaction arrives on: the storage the
     * scan and the re-read ask and add and delete write through, and the unit of work add, save
     * and delete commit through.
     */
    private class ThreadSeams(
        private val real: AttachmentStorage,
        private val realUow: UnitOfWork,
    ) {
        class Seen(val what: String, val thread: Thread)

        val seen = CopyOnWriteArrayList<Seen>()

        private fun note(what: String) {
            seen += Seen(what, Thread.currentThread())
        }

        val storage = object : AttachmentStorage {
            override fun state(): StoreState {
                note("state")
                return real.state()
            }

            override fun store(): AttachmentStore? {
                note("store")
                return real.store()?.let { inner ->
                    object : AttachmentStore by inner {
                        override suspend fun put(locator: String, source: ByteSource): StoredBytes {
                            note("put")
                            return inner.put(locator, source)
                        }

                        override suspend fun exists(locator: String): Boolean {
                            note("exists")
                            return inner.exists(locator)
                        }

                        override suspend fun delete(locator: String) {
                            note("delete")
                            inner.delete(locator)
                        }
                    }
                }
            }
        }

        val uow = object : UnitOfWork {
            override suspend fun <T> write(block: suspend () -> T): T {
                note("write")
                return realUow.write(block)
            }

            override suspend fun <T> read(block: suspend () -> T): T {
                note("read")
                return realUow.read(block)
            }
        }
    }
}
