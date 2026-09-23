package com.loosecannon.servicetag.share

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.MAX_ATTACHMENT_BYTES
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.usecase.AddReference
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.testing.FakeGraph
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Collections
import kotlin.properties.Delegates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The intake state machine, against a Room-backed [FakeGraph] so the use cases, the table and the
 * refusals are the production ones. Every case that writes asserts the row counts as well as the
 * sentence, because I-8's whole content is that a refusal leaves nothing behind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareIntakeViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph
    private val store = ViewModelStore()
    private var assetId: AssetId by Delegates.notNull()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        store.clear()
        graph.close()
        Dispatchers.resetMain()
    }

    private suspend fun TestScope.mower(): String {
        val asset = graph.createAsset.run(AssetCommand(name = "Cub Cadet XT1"))
        assetId = asset.id
        return asset.id.value
    }

    private val addReference: AddReference by lazy {
        AddReference(graph.references, graph.assets, LinkLaunchPolicy(), graph.uow, graph.ids, graph.clock)
    }

    private fun model(
        content: ShareContent,
        source: ByteSource? = null,
    ): ShareIntakeViewModel {
        val factory = viewModelFactory {
            initializer {
                ShareIntakeViewModel(
                    content = content,
                    source = source,
                    assets = graph.assets,
                    storage = graph.attachmentStorage,
                    addReference = addReference,
                    addAttachment = graph.addAttachment,
                    logEvent = graph.logEvent,
                    today = { "2026-09-23" },
                    zoneId = { "UTC" },
                )
            }
        }
        val vm = ViewModelProvider.create(store, factory)[
            "${content.hashCode()}-${store.keys().size}",
            ShareIntakeViewModel::class,
        ]
        // The asset list is read on Room's own dispatcher, so the state is only settled once the
        // scheduler this test drives has run it: a `value` read before that is the loading one.
        scheduler.advanceUntilIdle()
        return vm
    }

    /** Save, then let the write and its answer land, exactly as the screen's collector would. */
    private fun ShareIntakeViewModel.saveAndSettle() {
        save()
        scheduler.advanceUntilIdle()
    }

    private fun ShareIntakeViewModel.confirmAndSettle() {
        confirmUnknownScheme()
        scheduler.advanceUntilIdle()
    }

    private suspend fun references() = graph.references.forAsset(assetId).size
    private suspend fun attachments() =
        graph.attachments.forOwner(AttachmentOwner.OfAsset(assetId)).size
    private suspend fun events() = graph.events.forAsset(assetId).size

    private fun link(uri: String, name: String? = "OEM parts lookup") =
        ShareContent.Link(uri, name)

    private fun bytes(
        name: String = "manual.pdf",
        mime: String = "application/pdf",
        size: Long? = 3L,
    ) = ShareContent.Bytes(name, mime, size)

    private val MANUAL = "https://example-mower.invalid/xt1/manual.pdf"

    // --- the link path ------------------------------------------------------------------------

    @Test fun aLinkSavesAndSaysWhichAssetItWentTo() = runTest(scheduler) {
        val id = mower()
        val vm = model(link(MANUAL))

        vm.choose(id)
        vm.saveAndSettle()

        assertEquals("Saved to Cub Cadet XT1", vm.state.value.saved)
        assertEquals(1, references())
        assertEquals(MANUAL, graph.references.forAsset(assetId).single().uri)
    }

    @Test fun aDuplicateLinkSaysSoAndWritesNothingMore() = runTest(scheduler) {
        val id = mower()
        model(link(MANUAL)).also { it.choose(id); it.saveAndSettle() }

        val second = model(link(MANUAL, name = "Again"))
        second.choose(id)
        second.saveAndSettle()

        assertEquals("That link is already on this asset", second.state.value.message)
        assertNull(second.state.value.saved)
        assertEquals(1, references())
    }

    @Test fun anOverLongUriSaysSoAndWritesNothing() = runTest(scheduler) {
        val id = mower()
        val uri = "https://example-mower.invalid/" +
            "a".repeat(2_049 - "https://example-mower.invalid/".length)
        val vm = model(link(uri))

        vm.choose(id)
        vm.saveAndSettle()

        assertEquals("That link is too long to save.", vm.state.value.message)
        assertEquals(0, references())
    }

    /**
     * The confirmation exists so that an unfamiliar scheme is never saved silently: the flag is set
     * only by the person's own answer, so a screen that passed `true` unconditionally would remove
     * the very thing the policy is for.
     */
    @Test fun anUnknownSchemeIsAskedAboutByNameBeforeAnythingIsWritten() = runTest(scheduler) {
        val id = mower()
        val vm = model(link("zotero://select/items/0", name = "A paper"))

        vm.choose(id)
        vm.saveAndSettle()

        assertEquals("zotero", vm.state.value.confirming)
        assertEquals(0, references())

        vm.confirmAndSettle()

        assertNull(vm.state.value.confirming)
        assertEquals("Saved to Cub Cadet XT1", vm.state.value.saved)
        assertEquals(1, references())
    }

    @Test fun dismissingTheConfirmationWritesNothing() = runTest(scheduler) {
        val id = mower()
        val vm = model(link("zotero://select/items/0"))

        vm.choose(id)
        vm.saveAndSettle()
        vm.dismissConfirmation()
        vm.cancel()

        assertEquals(0, references())
        assertNull(vm.state.value.saved)
    }

    @Test fun aBlockedSchemeIsADeadEndWithNothingToSave() = runTest(scheduler) {
        mower()
        val vm = model(ShareContent.Refused(IntakeRefusal.SCHEME_BLOCKED))

        assertEquals("ServiceTag will not save that kind of link.", vm.state.value.deadEnd)
        assertFalse(vm.state.value.saveEnabled)
    }

    @Test fun aRefusedStreamIsADeadEndWithItsOwnSentence() = runTest(scheduler) {
        mower()
        val vm = model(ShareContent.Refused(IntakeRefusal.STREAM_NOT_ACCEPTED))

        assertEquals(
            "That file cannot be accepted from the app that shared it.",
            vm.state.value.deadEnd,
        )
    }

    // --- the byte path -----------------------------------------------------------------------

    @Test fun anEmptyFileIsRefusedInTheIntakeLayerAndTheStoreIsNeverAsked() = runTest(scheduler) {
        val id = mower()
        var opened = 0
        val vm = model(bytes(size = 0L), source = { opened += 1; "".byteInputStream() })

        vm.choose(id)
        vm.saveAndSettle()

        assertEquals("That file is empty", vm.state.value.message)
        assertEquals(0, opened)
        assertEquals(0, attachments())
        assertTrue(graph.attachmentStorage.store.files.isEmpty())
    }

    @Test fun anOverCapFileIsRefusedBeforeTheCopy() = runTest(scheduler) {
        val id = mower()
        var opened = 0
        val vm = model(
            bytes(size = MAX_ATTACHMENT_BYTES + 1),
            source = { opened += 1; "x".byteInputStream() },
        )

        vm.choose(id)
        vm.saveAndSettle()

        assertEquals("That file is larger than 256 MB", vm.state.value.message)
        assertEquals(0, opened)
        assertEquals(0, attachments())
        assertTrue(graph.attachmentStorage.store.files.isEmpty())
    }

    /** A read failure and a security refusal are different facts and get different sentences. */
    @Test fun aReadFailureIsNotTheRefusedStreamSentence() = runTest(scheduler) {
        val id = mower()
        val vm = model(bytes(), source = { throw IOException("the provider is gone") })

        vm.choose(id)
        vm.saveAndSettle()

        assertEquals("Could not read what was shared", vm.state.value.message)
        assertEquals(0, attachments())
        assertTrue(graph.attachmentStorage.store.files.isEmpty())
    }

    /**
     * The shipped `AddAttachment` only cleans up on its over-size arm, so a source that dies
     * mid-copy is the case where a half-written file could outlive the failure. Nothing survives:
     * no row, nothing at the locator, and a read-failure sentence rather than a crash.
     */
    @Test fun aFailureMidCopyLeavesNoRowAndNothingAtTheLocator() = runTest(scheduler) {
        val id = mower()
        val vm = model(bytes(size = null), source = { halfAStream() })

        vm.choose(id)
        vm.saveAndSettle()

        assertEquals("Could not read what was shared", vm.state.value.message)
        assertEquals(0, attachments())
        assertTrue(graph.attachmentStorage.store.files.isEmpty())
    }

    @Test fun theNameAndTheDescriptionBothReachTheCommand() = runTest(scheduler) {
        val id = mower()
        val vm = model(bytes(), source = { "pdf".byteInputStream() })

        vm.choose(id)
        vm.name("Deck belt diagram")
        vm.describe("Page 14 of the operator manual")
        vm.kind(AttachmentKind.MANUAL)
        vm.saveAndSettle()

        val row = graph.attachments.forOwner(AttachmentOwner.OfAsset(assetId)).single()
        assertEquals("Deck belt diagram", row.displayName)
        assertEquals("Page 14 of the operator manual", row.notes)
        assertEquals(AttachmentKind.MANUAL, row.kind)
        assertEquals("Saved to Cub Cadet XT1", vm.state.value.saved)
    }

    @Test fun theTypeControlIsPrefilledFromTheDeclaredType() = runTest(scheduler) {
        mower()

        assertEquals(AttachmentKind.PHOTO, model(bytes(mime = "image/jpeg")).state.value.kind)
        assertEquals(AttachmentKind.DOCUMENT, model(bytes()).state.value.kind)
        assertEquals(IntakePath.LINK, model(link(MANUAL)).state.value.path)
    }

    // --- the two storage-shaped dead ends ------------------------------------------------------

    @Test fun withNoAssetsNothingIsOfferedToSaveInto() = runTest(scheduler) {
        val vm = model(link(MANUAL))

        assertEquals(
            "Add an asset in ServiceTag first, then share this again.",
            vm.state.value.deadEnd,
        )
        assertFalse(vm.state.value.saveEnabled)
        assertTrue(vm.state.value.assets.isEmpty())
    }

    /** D-20: the folder gates the byte path only — a reference needs no folder. */
    @Test fun withNoFolderABytesShareCannotSaveButAUriShareStillCan() = runTest(scheduler) {
        val id = mower()
        graph.attachmentStorage.state = StoreState.NotConfigured

        var opened = 0
        val byteShare = model(bytes(), source = { opened += 1; "x".byteInputStream() })
        byteShare.choose(id)
        byteShare.saveAndSettle()

        assertTrue(byteShare.state.value.noFolder)
        assertFalse(byteShare.state.value.saveEnabled)
        assertEquals(0, opened)
        assertEquals(0, attachments())
        assertTrue(graph.attachmentStorage.store.files.isEmpty())

        val uriShare = model(link(MANUAL))
        uriShare.choose(id)

        assertFalse(uriShare.state.value.noFolder)
        assertTrue(uriShare.state.value.saveEnabled)

        uriShare.saveAndSettle()

        assertEquals("Saved to Cub Cadet XT1", uriShare.state.value.saved)
        assertEquals(1, references())
    }

    // --- Save's own guard ---------------------------------------------------------------------

    @Test fun saveIsDisabledUntilAnAssetIsChosenAndTheNameIsNotBlank() = runTest(scheduler) {
        val id = mower()
        val vm = model(link(MANUAL))

        assertFalse("no asset chosen yet", vm.state.value.saveEnabled)

        vm.choose(id)
        assertTrue(vm.state.value.saveEnabled)

        vm.name("   ")
        assertFalse("a blank name never draws a sentence here, it disables Save", vm.state.value.saveEnabled)
        assertNull(vm.state.value.message)

        vm.saveAndSettle()
        assertEquals(0, references())
    }

    // --- prose, and I-8 -------------------------------------------------------------------------

    @Test fun proseBecomesAJournalNoteAndNeverAReference() = runTest(scheduler) {
        val id = mower()
        val vm = model(ShareContent.PlainText("Replaced the drive belt, took an hour"))

        assertEquals("That is not a link.", vm.state.value.message)
        assertEquals(IntakePath.NOTE, vm.state.value.path)

        vm.choose(id)
        vm.saveAndSettle()

        assertEquals("Saved to Cub Cadet XT1", vm.state.value.saved)
        assertEquals(0, references())
        assertEquals(1, events())
        val event = graph.events.forAsset(assetId).single()
        assertEquals(EventKind.NOTE, event.kind)
        assertTrue(event.notes.contains("Replaced the drive belt, took an hour"))
    }

    /**
     * I-8, one case per step: before choosing, after choosing, after typing, and at the
     * confirmation dialog. Nothing is written, and clearing the store — which is what the
     * activity's own disposal does — writes nothing either.
     */
    @Test fun cancelAtEveryStepWritesNothing() = runTest(scheduler) {
        val id = mower()

        model(link(MANUAL)).cancel()

        model(link(MANUAL)).also { it.choose(id); it.cancel() }

        model(link(MANUAL)).also { it.choose(id); it.name("Typed a name"); it.cancel() }

        model(link("zotero://select/items/0")).also {
            it.choose(id)
            it.saveAndSettle()
            assertEquals("zotero", it.state.value.confirming)
            it.cancel()
        }

        model(bytes(), source = { error("cancelled intake must never open a stream") }).also {
            it.choose(id)
            it.cancel()
        }

        store.clear()

        assertEquals(0, references())
        assertEquals(0, attachments())
        assertEquals(0, events())
        assertTrue(graph.attachmentStorage.store.files.isEmpty())
    }

    /** Some bytes, then a failure: the shape the store has to survive without leaving a file. */
    private fun halfAStream(): InputStream = SequenceInputStream(
        Collections.enumeration(
            listOf<InputStream>(
                ByteArrayInputStream(ByteArray(1_024) { 7 }),
                object : InputStream() {
                    override fun read(): Int = throw IOException("the provider died mid-copy")
                    override fun read(b: ByteArray, off: Int, len: Int): Int =
                        throw IOException("the provider died mid-copy")
                },
            ),
        ),
    )
}
