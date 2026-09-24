package com.loosecannon.servicetag.ui.attachments

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.usecase.AddAttachmentCommand
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.AttachmentResult
import com.loosecannon.servicetag.core.usecase.DeleteAttachment
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.core.usecase.UpdateAttachment
import com.loosecannon.servicetag.core.usecase.UpdateAttachmentCommand
import com.loosecannon.servicetag.testing.FakeGraph
import java.io.IOException
import kotlin.properties.Delegates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
     * Every model the test builds lives in here, so `tearDown` can clear it: `viewModelScope` is
     * cancelled by the store in production and by nothing at all in a plain JVM test, which left
     * the scan and the `stateIn` sharer running past `graph.close()` and `resetMain()`.
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
    ): AttachmentsSectionViewModel {
        val factory = viewModelFactory {
            initializer {
                AttachmentsSectionViewModel(
                    owner, graph.attachments, storage, graph.addAttachment,
                    updateAttachment, deleteAttachment, graph.thumbnails,
                    today = today,
                )
            }
        }
        return ViewModelProvider.create(store, factory)[
            AttachmentLocator.dirFor(owner),
            AttachmentsSectionViewModel::class,
        ]
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
        store.clear()

        val flaky = FlakyExistsStorage(graph.attachmentStorage)
        val vm = model(storage = flaky)
        val said = async(Dispatchers.Main) { vm.messages.first() }
        backgroundScope.launch { vm.state.collect() }
        assertEquals(SCAN_FAILED, said.await())
        assertEquals("Guide.pdf", vm.state.first { it.rows.size == 1 }.rows.single().displayName)

        flaky.healthy = true
        vm.refreshStore()
        assertEquals(true, vm.state.first { it.rows.singleOrNull()?.present == true }.rows.single().present)
    }

    @Test fun theProgressLineNamesTheFileNumberAndTheTotal() {
        assertEquals("Adding 3 of 8…", addingProgressLine(3, 8))
    }

    @Test fun addingSeveralFilesReportsProgressAndKeepsGoingPastAFailure() = runTest {
        hotTub()
        val vm = model()
        val seen = mutableListOf<AttachmentsSectionState>()
        backgroundScope.launch { vm.state.collect { seen += it } }
        val said = mutableListOf<String>()
        backgroundScope.launch(Dispatchers.Main) { vm.messages.collect { said += it } }

        // The first file's bytes are handed over only once the test has seen the first progress
        // line: on a fast IO thread all three adds would otherwise finish before the collector
        // ever observes a non-null progress (the conflated state flow keeps only the latest).
        val gate = java.util.concurrent.CountDownLatch(1)
        val first = PickedFile("First.pdf", "application/pdf", 1L) {
            gate.await()
            "x".toByteArray().inputStream()
        }
        vm.add(listOf(first, broken("Middle.pdf"), picked("Last.pdf")))
        vm.state.first { it.progress == "Adding 1 of 3…" }
        gate.countDown()

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
    }

    @Test fun savingNothingIsSilent() = runTest {
        hotTub()
        val vm = model()
        backgroundScope.launch { vm.state.collect() }
        vm.add(listOf(picked("guide.pdf")))
        val row = vm.state.first { it.rows.size == 1 }.rows.single()

        val said = mutableListOf<String>()
        backgroundScope.launch(Dispatchers.Main) { vm.messages.collect { said += it } }
        // Both `saved` signals are awaited, not counted after the fact. The two saves run
        // through Room's own executor, so which of them finishes first is not ours to order:
        // the rename's state change is **not** a barrier for the `Unchanged` one, and reading
        // a list afterwards dropped whichever had not landed yet. Awaiting exactly two — both
        // subscribed before either call, `saved` having no replay — is the barrier that says
        // both have been the whole way through the path, whatever order they took. **How this
        // fails:** if only one signal ever lands, `await()` never returns, so the RED shape is a
        // `runTest` timeout and not an assertion message — the same shape, for the same reason,
        // as the cold-launch badge case in `ReminderHealthViewModelTest`.
        val closed = async(Dispatchers.Main) { vm.saved.take(2).toList() }

        vm.save(row.id, UpdateAttachmentCommand(row.displayName, row.kind, row.capturedOn, row.notes))
        vm.save(row.id, UpdateAttachmentCommand("Renamed.pdf", row.kind, row.capturedOn, row.notes))

        // Silent, but not stuck: nothing to write still closes the sheet.
        assertEquals(listOf(row.id, row.id), closed.await())
        vm.state.first { it.rows.singleOrNull()?.displayName == "Renamed.pdf" }
        assertEquals(emptyList<String>(), said)
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
    }

    @Test fun capturedOnDefaultsToTodayForAPickedFile() = runTest {
        hotTub()
        val vm = model(today = { "2026-09-16" })
        backgroundScope.launch { vm.state.collect() }
        vm.add(listOf(picked("guide.pdf")))
        assertEquals("2026-09-16", vm.state.first { it.rows.size == 1 }.rows.single().capturedOn)
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
}
