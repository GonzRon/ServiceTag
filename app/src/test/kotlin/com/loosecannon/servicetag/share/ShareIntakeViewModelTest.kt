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
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_DESCRIPTION_CHARS
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_NAME_CHARS
import com.loosecannon.servicetag.core.references.StreamSourcePolicy
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.usecase.AddAttachment
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

    private suspend fun mower(): String {
        val asset = graph.createAsset.run(AssetCommand(name = "Cub Cadet XT1"))
        assetId = asset.id
        return asset.id.value
    }

    private val addReference: AddReference by lazy {
        AddReference(graph.references, graph.assets, LinkLaunchPolicy(), graph.uow, graph.ids, graph.clock)
    }

    /** A unique key per model, so two models in one case do not share an instance. */
    private var models = 0

    private fun model(
        content: ShareContent,
        source: ByteSource? = null,
        addAttachment: AddAttachment = graph.addAttachment,
    ): ShareIntakeViewModel {
        val factory = viewModelFactory {
            initializer {
                ShareIntakeViewModel(
                    // The activity hands over a suspending read; here it is already decided.
                    readShare = { SharedShare(content, streamUri = null, bytes = source) },
                    assets = graph.assets,
                    storage = graph.attachmentStorage,
                    addReference = addReference,
                    addAttachment = addAttachment,
                    logEvent = graph.logEvent,
                    today = { "2026-09-23" },
                    zoneId = { "UTC" },
                    io = StandardTestDispatcher(scheduler),
                )
            }
        }
        val vm = ViewModelProvider.create(store, factory)[
            "model-${models++}",
            ShareIntakeViewModel::class,
        ]
        // The asset list is read on Room's own dispatcher, so the state is only settled once the
        // scheduler this test drives has run it: a `value` read before that is the loading one.
        scheduler.advanceUntilIdle()
        return vm
    }

    /** A model whose read fails the way a stranger's provider fails: on the way in. */
    private fun modelThatCannotRead(failure: Throwable): ShareIntakeViewModel {
        val factory = viewModelFactory {
            initializer {
                ShareIntakeViewModel(
                    readShare = { throw failure },
                    assets = graph.assets,
                    storage = graph.attachmentStorage,
                    addReference = addReference,
                    addAttachment = graph.addAttachment,
                    logEvent = graph.logEvent,
                    today = { "2026-09-23" },
                    zoneId = { "UTC" },
                    io = StandardTestDispatcher(scheduler),
                )
            }
        }
        val vm = ViewModelProvider.create(store, factory)[
            "model-${models++}",
            ShareIntakeViewModel::class,
        ]
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

    private val manualUrl = "https://example-mower.invalid/xt1/manual.pdf"

    // --- the link path ------------------------------------------------------------------------

    @Test fun aLinkSavesAndSaysWhichAssetItWentTo() = runTest(scheduler) {
        val id = mower()
        val vm = model(link(manualUrl))

        vm.choose(id)
        vm.saveAndSettle()

        assertEquals("Saved to Cub Cadet XT1", vm.state.value.saved)
        assertEquals(1, references())
        assertEquals(manualUrl, graph.references.forAsset(assetId).single().uri)
    }

    @Test fun aDuplicateLinkSaysSoAndWritesNothingMore() = runTest(scheduler) {
        val id = mower()
        model(link(manualUrl)).also { it.choose(id); it.saveAndSettle() }

        val second = model(link(manualUrl, name = "Again"))
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

    /**
     * The same rule where the provider declared **no** size, which `OpenableColumns.SIZE` is
     * allowed to leave out. There is no declared zero to test, so intake asks the stream for one
     * byte and the EOF is the refusal: `opened` is **1** — the probe and nothing else — and the
     * store is still never asked.
     */
    @Test fun anUndeclaredEmptyFileIsRefusedTheSameWayAndTheStoreIsStillNeverAsked() =
        runTest(scheduler) {
            val id = mower()
            var opened = 0
            val vm = model(bytes(size = null), source = { opened += 1; "".byteInputStream() })

            vm.choose(id)
            vm.saveAndSettle()

            assertEquals("That file is empty", vm.state.value.message)
            assertEquals(1, opened)
            assertEquals(0, attachments())
            assertTrue(graph.attachmentStorage.store.files.isEmpty())
        }

    /**
     * And the probe must not eat the copy. A `ByteSource` is re-openable by construction — the
     * share's own one calls `ContentResolver.openInputStream` on every `open()` — so an undeclared
     * *non*-empty share opens the source exactly **twice**, once to probe and once for the store's
     * copy, and all three bytes still land at the locator.
     */
    @Test fun anUndeclaredNonEmptyFileIsCopiedWholeAndOpensTheSourceTwice() = runTest(scheduler) {
        val id = mower()
        var opened = 0
        val vm = model(bytes(size = null), source = { opened += 1; "pdf".byteInputStream() })

        vm.choose(id)
        vm.saveAndSettle()

        assertNull(vm.state.value.message)
        assertEquals(2, opened)
        val row = graph.attachments.forOwner(AttachmentOwner.OfAsset(assetId)).single()
        assertEquals(3L, row.sizeBytes)
        assertEquals("pdf", graph.attachmentStorage.store.files.values.single().decodeToString())
    }

    /**
     * The intake layer keeps **no** copy of the over-cap rule: the shipped `AddAttachment` refuses
     * a declared over-size before it opens the source, with the same ratified sentence. What this
     * guards is that intake does not pre-open the stream on its way to that refusal — `opened` is
     * the source's own counter and stays at zero.
     */
    @Test fun anOverCapFileReachesTheShippedRefusalWithoutTheSourceBeingOpened() = runTest(scheduler) {
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
        assertEquals(IntakePath.LINK, model(link(manualUrl)).state.value.path)
    }

    // --- the two storage-shaped dead ends ------------------------------------------------------

    @Test fun withNoAssetsNothingIsOfferedToSaveInto() = runTest(scheduler) {
        val vm = model(link(manualUrl))

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

        val uriShare = model(link(manualUrl))
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
        val vm = model(link(manualUrl))

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

        model(link(manualUrl)).cancel()

        model(link(manualUrl)).also { it.choose(id); it.cancel() }

        model(link(manualUrl)).also { it.choose(id); it.name("Typed a name"); it.cancel() }

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


    // --- the read itself: one answer, whatever the process did before it -----------------------

    /**
     * The read now happens inside `viewModelScope`, where a throw has nowhere to go: unguarded it
     * would leave the blank loading screen with no Close on it. Both halves are here.
     *
     * The first two are what a read can still legitimately fail with on its way out — they are the
     * arms `readGuarded` keeps. The third is the provider actually dying: an
     * `IllegalArgumentException` from the resolver, thrown at the provider-facing call it stands
     * for, which the reader's own guard around `stream.facts()` turns into `Refused(UNREADABLE)`.
     * The state machine draws the same ratified sentence for all three, and nothing is staged.
     */
    @Test fun aReadThatCannotFinishIsAReadFailureWithAWayOut() = runTest(scheduler) {
        mower()

        val byTheGuardInTheViewModel = listOf(
            java.io.IOException("no bytes"),
            SecurityException("the grant is gone"),
        ).map { failure -> failure.toString() to modelThatCannotRead(failure) }

        // Not thrown from above the reader: thrown *by the provider*, at the one call that asks it
        // anything, exactly as `ContentResolver.query` throws for a URI it cannot resolve.
        val byTheGuardInTheReader = "the provider threw from query" to model(
            decideShare(
                declaredType = "application/pdf",
                stream = object : SharedStream {
                    override val scheme = "content"
                    override val authority = "com.android.providers.downloads.documents"
                    override fun facts(): StreamFacts =
                        throw IllegalArgumentException("Unknown URL content://nowhere/x")
                    override fun readAtMost(limit: Int): ByteArray =
                        throw IllegalArgumentException("Unknown URL content://nowhere/x")
                },
                text = null,
                subject = null,
                title = null,
                streamPolicy = StreamSourcePolicy(setOf("com.loosecannon.servicetag")),
                linkPolicy = LinkLaunchPolicy(),
            ),
        )

        (byTheGuardInTheViewModel + byTheGuardInTheReader).forEach { (what, vm) ->
            assertEquals(what, "Could not read what was shared", vm.state.value.deadEnd)
            assertFalse(what, vm.state.value.loading)
            assertFalse(what, vm.state.value.saveEnabled)
        }

        assertEquals(0, references())
        assertEquals(0, attachments())
        assertEquals(0, events())
        assertTrue(graph.attachmentStorage.store.files.isEmpty())
    }

    /**
     * The intent read does provider IPC and may pull up to 64 KiB of stream, so it may not happen
     * while the model is being built — it belongs on the IO context, with the screen drawing
     * nothing actionable until it lands. Nothing is read until the scheduler runs it.
     */
    @Test fun theIntentIsNotReadWhileTheModelIsBeingBuilt() = runTest(scheduler) {
        mower()
        var reads = 0
        val factory = viewModelFactory {
            initializer {
                ShareIntakeViewModel(
                    readShare = {
                        reads += 1
                        SharedShare(link(manualUrl), streamUri = null, bytes = null)
                    },
                    assets = graph.assets,
                    storage = graph.attachmentStorage,
                    addReference = addReference,
                    addAttachment = graph.addAttachment,
                    logEvent = graph.logEvent,
                    today = { "2026-09-23" },
                    zoneId = { "UTC" },
                    io = StandardTestDispatcher(scheduler),
                )
            }
        }
        val vm = ViewModelProvider.create(store, factory)[
            "model-${models++}",
            ShareIntakeViewModel::class,
        ]

        assertEquals("nothing may be read on the way to the first frame", 0, reads)
        assertTrue(vm.state.value.loading)
        assertFalse(vm.state.value.saveEnabled)

        scheduler.advanceUntilIdle()

        assertEquals(1, reads)
        assertFalse(vm.state.value.loading)
    }

    /**
     * Brief matrix, "cold and warm starts differ": the state is a pure function of what arrived,
     * the asset list and the store's state, so two reads of the same share are equal field for
     * field. A default that came from a field one process had set and another had not would show
     * up here.
     */
    @Test fun theSameShareProducesTheSameStateFieldForField() = runTest(scheduler) {
        mower()

        val cold = model(bytes()).state.value
        val warm = model(bytes()).state.value

        assertEquals(cold, warm)
        assertFalse(cold.loading)
    }

    /**
     * Brief matrix, "a process recreation loses the grant": the source is opened lazily, at save
     * time, so a grant that died with the sharing task surfaces as a `SecurityException` there.
     * It is a read failure — not the I-9 refusal, which is about a URI that was never acceptable —
     * and it leaves no row, no file and no partial file.
     */
    @Test fun aLostGrantAtSaveTimeIsAReadFailureAndLeavesNothing() = runTest(scheduler) {
        val id = mower()
        val vm = model(bytes(size = null), source = { throw SecurityException("the grant is gone") })

        vm.choose(id)
        vm.saveAndSettle()

        assertEquals("Could not read what was shared", vm.state.value.message)
        assertNull(vm.state.value.deadEnd)
        assertEquals(0, attachments())
        assertTrue(graph.attachmentStorage.store.files.isEmpty())
    }

    // --- the two fields stop where spec §10 says they stop ---------------------------------------

    @Test fun theNameFieldStopsAtItsCap() = runTest(scheduler) {
        mower()
        val vm = model(link(manualUrl))

        vm.name("a".repeat(MAX_REFERENCE_NAME_CHARS + 50))

        assertEquals(MAX_REFERENCE_NAME_CHARS, vm.state.value.name.length)
    }

    @Test fun theDescriptionFieldStopsAtItsCap() = runTest(scheduler) {
        mower()
        val vm = model(link(manualUrl))

        vm.describe("b".repeat(MAX_REFERENCE_DESCRIPTION_CHARS + 500))

        assertEquals(MAX_REFERENCE_DESCRIPTION_CHARS, vm.state.value.description.length)
    }

    /**
     * The other half of the `.txt` row: `MimeTypes.EXTENSIONS` maps `text/plain`, so a shared text
     * file is stored as one and the locator says so. The shared name deliberately carries **no**
     * extension of its own — `AttachmentLocator` prefers the name's when there is one, and this
     * case is about the declared type reaching the locator.
     */
    @Test fun aSharedTextFileIsStoredWithATxtLocator() = runTest(scheduler) {
        val id = mower()
        val vm = model(
            bytes(name = "service log", mime = "text/plain", size = 3L),
            source = { "log".byteInputStream() },
        )

        vm.choose(id)
        vm.saveAndSettle()

        val row = graph.attachments.forOwner(AttachmentOwner.OfAsset(assetId)).single()
        assertTrue(row.storageLocator, row.storageLocator.endsWith(".txt"))
        assertTrue(graph.attachmentStorage.store.files.keys.single().endsWith(".txt"))
    }

    /**
     * Spec §10, as a set. Moved off the emulator: it renders nothing and compares strings, so the
     * device run has no more to say about it than the JVM does.
     */
    @Test fun everyIntakeSentenceIsOneSpecTenRatifies() {
        val ratified = setOf(
            "Save to ServiceTag", "Received", "Attach to", "Choose asset", "Name",
            "Description (optional)", "Type", "Save", "Cancel", "Close", "Save as a note",
            "That is not a link.", "Add an asset in ServiceTag first, then share this again.",
            "Choose an attachment folder in ServiceTag Settings, then share this again.",
            "That file cannot be accepted from the app that shared it.",
            "Could not read what was shared", "That link is too long to save.",
            "ServiceTag will not save that kind of link.",
            "That link is already on this asset", "That file is empty",
            "That file is larger than 256 MB", "Give the file a name",
            "Give the reference a name", "Save this link?",
        )
        val drawn = listOf(
            IntakeStrings.TITLE, IntakeStrings.RECEIVED, IntakeStrings.ATTACH_TO,
            IntakeStrings.CHOOSE_ASSET, IntakeStrings.NAME, IntakeStrings.DESCRIPTION,
            IntakeStrings.TYPE, IntakeStrings.SAVE, IntakeStrings.CANCEL, IntakeStrings.CLOSE,
            IntakeStrings.SAVE_AS_NOTE, IntakeStrings.NOT_A_LINK, IntakeStrings.NO_ASSETS,
            IntakeStrings.NO_FOLDER, IntakeStrings.STREAM_REFUSED, IntakeStrings.UNREADABLE,
            IntakeStrings.URI_TOO_LONG, IntakeStrings.SCHEME_BLOCKED,
            IntakeStrings.DUPLICATE_URI, IntakeStrings.EMPTY_FILE, IntakeStrings.TOO_LARGE,
            IntakeStrings.BLANK_FILE_NAME, IntakeStrings.BLANK_REFERENCE_NAME,
            IntakeStrings.CONFIRM_TITLE,
        )

        assertEquals(ratified, drawn.toSet())
        assertEquals(
            "ServiceTag does not recognise \"zotero\" links. It will be saved as written and " +
                "opened with whatever app claims it.",
            IntakeStrings.confirmBody("zotero"),
        )
        assertEquals("Saved to Cub Cadet XT1", IntakeStrings.savedTo("Cub Cadet XT1"))
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
