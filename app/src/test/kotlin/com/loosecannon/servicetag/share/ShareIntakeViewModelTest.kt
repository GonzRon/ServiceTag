package com.loosecannon.servicetag.share

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.MAX_ATTACHMENT_BYTES
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.model.asAttachmentOwner
import com.loosecannon.servicetag.ui.maintenance.ReminderReconcile
import com.loosecannon.servicetag.ui.transfer.`import`.TransferPackAppFixtures
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_DESCRIPTION_CHARS
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_NAME_CHARS
import com.loosecannon.servicetag.core.references.StreamSourcePolicy
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.usecase.AddAssetSupplyCommand
import com.loosecannon.servicetag.core.usecase.AddAttachment
import com.loosecannon.servicetag.core.usecase.AddReference
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.InstallComponentCommand
import com.loosecannon.servicetag.core.usecase.InstalledComponentResult
import com.loosecannon.servicetag.core.usecase.SupplyItemCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.ui.asset.AssetsViewModel
import com.loosecannon.servicetag.ui.asset.EmptyReason
import com.loosecannon.servicetag.testing.assetRow
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Collections
import kotlin.properties.Delegates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

    /** The fixture's name: #93 (C4) hands the save the tapped row's `(id, name)`, so every choice names it. */
    private val mowerName = "Cub Cadet XT1"

    private suspend fun mower(): String {
        val asset = graph.createAsset.run(AssetCommand(name = mowerName))
        assetId = asset.id
        return asset.id.value
    }

    private val addReference: AddReference by lazy {
        AddReference(
            graph.references, graph.assets, graph.supplyItems, graph.installedComponents, LinkLaunchPolicy(), graph.uow,
            graph.ids, graph.clock,
        )
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
                    // #69 (C30, C-5): the held set, the supplies and the components, as the activity passes them.
                    heldIds = { graph.transferRecords.heldIds() },
                    supplyItems = { graph.supplyItems.all() },
                    installedComponents = { graph.installedComponents.all() },
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

    /** #93's tapped asset row is #69's [ShareDestination.Asset] (C29): the shipped cases choose by id and name. */
    private fun ShareIntakeViewModel.choose(id: String, name: String) = choose(ShareDestination.Asset(id, name))

    private suspend fun references() = graph.references.forOwner(ReferenceOwner.OfAsset(assetId)).size
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

        vm.choose(id, mowerName)
        vm.saveAndSettle()

        assertEquals("Saved to Cub Cadet XT1", vm.state.value.saved)
        assertEquals(1, references())
        assertEquals(manualUrl, graph.references.forOwner(ReferenceOwner.OfAsset(assetId)).single().uri)
    }

    @Test fun aDuplicateLinkSaysSoAndWritesNothingMore() = runTest(scheduler) {
        val id = mower()
        model(link(manualUrl)).also { it.choose(id, mowerName); it.saveAndSettle() }

        val second = model(link(manualUrl, name = "Again"))
        second.choose(id, mowerName)
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

        vm.choose(id, mowerName)
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

        vm.choose(id, mowerName)
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

        vm.choose(id, mowerName)
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

        vm.choose(id, mowerName)
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

            vm.choose(id, mowerName)
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

        vm.choose(id, mowerName)
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

        vm.choose(id, mowerName)
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

        vm.choose(id, mowerName)
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

        vm.choose(id, mowerName)
        vm.saveAndSettle()

        assertEquals("Could not read what was shared", vm.state.value.message)
        assertEquals(0, attachments())
        assertTrue(graph.attachmentStorage.store.files.isEmpty())
    }

    @Test fun theNameAndTheDescriptionBothReachTheCommand() = runTest(scheduler) {
        val id = mower()
        val vm = model(bytes(), source = { "pdf".byteInputStream() })

        vm.choose(id, mowerName)
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

    /**
     * #67, C7 (R67-9): a byte share starts on "No role", and the role chosen in the Role control
     * reaches `AddAttachmentCommand.role` and lands on the one row the save writes.
     */
    @Test fun aBytesShareCarriesTheChosenRole() = runTest(scheduler) {
        val id = mower()
        val vm = model(bytes(), source = { "pdf".byteInputStream() })
        assertNull(vm.state.value.role)

        vm.choose(id, mowerName)
        vm.role(DocumentRole.USER_MANUAL)
        vm.saveAndSettle()

        val row = graph.attachments.forOwner(AttachmentOwner.OfAsset(assetId)).single()
        assertEquals(DocumentRole.USER_MANUAL, row.role)
        assertEquals("Saved to Cub Cadet XT1", vm.state.value.saved)
    }

    /**
     * #91, C21 (R91-4, amending #67's R67-9): a web-link share offers the Role control, and the role
     * picked there reaches `AddReferenceCommand.role` and lands on the one reference the save writes.
     */
    @Test fun aWebLinkShareCarriesTheChosenRole() = runTest(scheduler) {
        val id = mower()
        val vm = model(link(manualUrl))

        vm.choose(id, mowerName)
        vm.role(DocumentRole.USER_MANUAL)
        assertEquals(DocumentRole.USER_MANUAL, vm.state.value.role)
        vm.saveAndSettle()

        assertEquals("Saved to Cub Cadet XT1", vm.state.value.saved)
        assertEquals(DocumentRole.USER_MANUAL, graph.references.forOwner(ReferenceOwner.OfAsset(assetId)).single().role)
        assertEquals(0, attachments())
    }

    /** #91, C21 (R91-4): an http or https share is offered the Role control on "No role". */
    @Test fun aWebLinkShareStartsWithNoRole() = runTest(scheduler) {
        mower()
        val https = model(link(manualUrl)).state.value
        val http = model(link("http://example-mower.invalid/xt1/parts")).state.value

        listOf(https, http).forEach { state ->
            assertTrue(state.linkTakesRole)
            assertTrue(state.roleOffered)
            assertNull(state.role)
        }
    }

    /**
     * #91, C21 (R91-4): only a web link takes a role, so a note link is offered no Role control,
     * a pick is not recorded, and the save writes a reference with no role; an unfamiliar scheme
     * is the same. A note share is a journal entry and takes none either.
     */
    @Test fun aNoteLinkShareOffersNoRoleAndRecordsNone() = runTest(scheduler) {
        val id = mower()
        val vm = model(link("joplin://x-callback-url/openNote?id=example"))
        assertFalse(vm.state.value.linkTakesRole)
        assertFalse(vm.state.value.roleOffered)

        vm.choose(id, mowerName)
        vm.role(DocumentRole.USER_MANUAL)
        assertNull(vm.state.value.role)
        vm.saveAndSettle()

        assertEquals("Saved to Cub Cadet XT1", vm.state.value.saved)
        assertNull(graph.references.forOwner(ReferenceOwner.OfAsset(assetId)).single().role)
        assertEquals(0, attachments())

        assertFalse(model(link("zotero://select/items/0")).state.value.roleOffered)

        val note = model(ShareContent.PlainText("Replaced the drive belt, took an hour"))
        assertFalse(note.state.value.roleOffered)
        note.role(DocumentRole.SERVICE_MANUAL)
        assertNull(note.state.value.role)
    }

    /** #91, C25: a shared page title fills the name and nothing else; the role stays "No role". */
    @Test fun aSharedTitleNeverBecomesARole() = runTest(scheduler) {
        val id = mower()
        val vm = model(link(manualUrl, name = "Service manual"))
        assertEquals("Service manual", vm.state.value.name)
        assertNull(vm.state.value.role)

        vm.choose(id, mowerName)
        vm.saveAndSettle()

        val row = graph.references.forOwner(ReferenceOwner.OfAsset(assetId)).single()
        assertEquals("Service manual", row.displayName)
        assertNull(row.role)
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
        byteShare.choose(id, mowerName)
        byteShare.saveAndSettle()

        assertTrue(byteShare.state.value.noFolder)
        assertFalse(byteShare.state.value.saveEnabled)
        assertEquals(0, opened)
        assertEquals(0, attachments())
        assertTrue(graph.attachmentStorage.store.files.isEmpty())

        val uriShare = model(link(manualUrl))
        uriShare.choose(id, mowerName)

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

        vm.choose(id, mowerName)
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

        vm.choose(id, mowerName)
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

        model(link(manualUrl)).also { it.choose(id, mowerName); it.cancel() }

        model(link(manualUrl)).also { it.choose(id, mowerName); it.name("Typed a name"); it.cancel() }

        model(link("zotero://select/items/0")).also {
            it.choose(id, mowerName)
            it.saveAndSettle()
            assertEquals("zotero", it.state.value.confirming)
            it.cancel()
        }

        model(bytes(), source = { error("cancelled intake must never open a stream") }).also {
            it.choose(id, mowerName)
            it.cancel()
        }

        // #93 (R93-4): a choice, then Change (Back on the form) back to the picker, then cancel there.
        model(bytes(), source = { error("cancelled intake must never open a stream") }).also {
            it.choose(id, mowerName)
            it.name("Typed a name")
            it.changeAsset()
            it.cancel()
        }

        store.clear()

        assertEquals(0, references())
        assertEquals(0, attachments())
        assertEquals(0, events())
        assertTrue(graph.attachmentStorage.store.files.isEmpty())
    }

    // --- #93 (B2; C4–C6): the tapped row, the two steps, Change and Back -------------------------

    /**
     * C4: the save receives the tapped row's `(id, name)` — the picker's live row — and never a name looked up in the
     * read-time snapshot: renamed after the read, the asset is saved to under the name the person tapped.
     */
    @Test fun theChosenRowsIdAndNameReachTheSave() = runTest(scheduler) {
        val id = mower()
        val vm = model(link(manualUrl))
        graph.updateAsset.run(assetId, AssetCommand(name = "Cub Cadet XT1 Ultimate"))

        vm.choose(id, "Cub Cadet XT1 Ultimate")
        vm.saveAndSettle()

        assertEquals("Saved to Cub Cadet XT1 Ultimate", vm.state.value.saved)
        assertEquals(1, references())
        assertEquals(manualUrl, graph.references.forOwner(ReferenceOwner.OfAsset(assetId)).single().uri)
    }

    /** Audit §0.1: an asset the read-time snapshot lacks is saved to, not refused with the no-assets sentence. */
    @Test fun anAssetAddedAfterTheReadIsSavedToNotRefused() = runTest(scheduler) {
        mower()
        val vm = model(link(manualUrl))
        val added = graph.createAsset.run(AssetCommand(name = "Example Snow Blower"))

        vm.choose(added.id.value, "Example Snow Blower")
        vm.saveAndSettle()

        assertNull(vm.state.value.deadEnd)
        assertEquals("Saved to Example Snow Blower", vm.state.value.saved)
        assertEquals(1, graph.references.forOwner(ReferenceOwner.OfAsset(added.id)).size)
        assertEquals(0, references())
    }

    /** C5: the picker is drawn only on a loaded, live share with nothing chosen. */
    @Test fun pickingIsTrueOnlyWithNoChoiceOnALoadedLiveIntake() = runTest(scheduler) {
        val id = mower()
        val vm = model(link(manualUrl))
        assertTrue("a loaded share with nothing chosen draws the picker", vm.state.value.picking)
        vm.choose(id, mowerName)
        assertFalse("a choice draws the form", vm.state.value.picking)
        assertFalse(
            "a refused share is a dead end, never the picker",
            model(ShareContent.Refused(IntakeRefusal.SCHEME_BLOCKED)).state.value.picking,
        )

        val live = ShareIntakeState(loading = false)
        assertTrue(live.picking)
        assertFalse("loading", ShareIntakeState().picking)
        assertFalse("a dead end", live.copy(deadEnd = IntakeStrings.NO_ASSETS).picking)
        assertFalse("saved", live.copy(saved = IntakeStrings.savedTo(mowerName)).picking)
        assertFalse("a Transfer Pack", live.copy(path = IntakePath.TRANSFER_PACK).picking)
        assertFalse("chosen", live.copy(destination = ShareDestination.Asset(id, mowerName)).picking)
    }

    /** C6: Change clears the choice and the refusal and keeps what came from the share — Name, Description, Type, Role. */
    @Test fun changeAssetClearsTheChoiceAndMessageAndKeepsNameDescriptionTypeAndRole() = runTest(scheduler) {
        val id = mower()
        val vm = model(bytes(size = 0L), source = { "".byteInputStream() })
        vm.choose(id, mowerName)
        vm.name("Typed a name")
        vm.describe("Typed a description")
        vm.kind(AttachmentKind.MANUAL)
        vm.role(DocumentRole.SERVICE_MANUAL)
        vm.saveAndSettle()
        assertEquals("That file is empty", vm.state.value.message)

        vm.changeAsset()

        val after = vm.state.value
        assertNull(after.destination)
        assertNull(after.message)
        assertTrue(after.picking)
        assertEquals("Typed a name", after.name)
        assertEquals("Typed a description", after.description)
        assertEquals(AttachmentKind.MANUAL, after.kind)
        assertEquals(DocumentRole.SERVICE_MANUAL, after.role)
        assertEquals(0, attachments())
    }

    /** R93-4: Back on the form changes the asset — never while saving, once saved, on a dead end or under the dialog. */
    @Test fun backChangesAssetIsFalseWhileSavingAfterSavedOnADeadEndAndWhileConfirming() = runTest(scheduler) {
        val id = mower()
        val vm = model(link(manualUrl))
        assertFalse("on the picker, Back cancels as today", vm.state.value.backChangesAsset)
        vm.choose(id, mowerName)
        assertTrue("on the form, Back returns to the picker", vm.state.value.backChangesAsset)

        vm.save()
        assertTrue("the write is still running", vm.state.value.saving)
        assertFalse("mid-save, Back finishes the activity as today", vm.state.value.backChangesAsset)
        scheduler.advanceUntilIdle()
        assertEquals("Saved to Cub Cadet XT1", vm.state.value.saved)
        assertFalse("saved", vm.state.value.backChangesAsset)

        val asked = model(link("zotero://select/items/0"))
        asked.choose(id, mowerName)
        asked.saveAndSettle()
        assertEquals("zotero", asked.state.value.confirming)
        assertFalse("the dialog takes Back", asked.state.value.backChangesAsset)

        val form = ShareIntakeState(loading = false, destination = ShareDestination.Asset(id, mowerName))
        assertTrue(form.backChangesAsset)
        assertFalse("loading", form.copy(loading = true).backChangesAsset)
        assertFalse("saving", form.copy(saving = true).backChangesAsset)
        assertFalse("a dead end", form.copy(deadEnd = IntakeStrings.NO_ASSETS).backChangesAsset)
    }

    @Test fun changeAssetIsANoOpThen() = runTest(scheduler) {
        val id = mower()
        val chosen = ShareDestination.Asset(id, mowerName)

        val vm = model(link(manualUrl))
        vm.choose(id, mowerName)
        vm.save()
        vm.changeAsset()
        assertEquals("mid-save the choice stays", chosen, vm.state.value.destination)
        scheduler.advanceUntilIdle()
        assertEquals("Saved to Cub Cadet XT1", vm.state.value.saved)
        vm.changeAsset()
        assertEquals("saved, the choice stays", chosen, vm.state.value.destination)
        assertEquals(1, references())

        val asked = model(link("zotero://select/items/0"))
        asked.choose(id, mowerName)
        asked.saveAndSettle()
        asked.changeAsset()
        assertEquals("zotero", asked.state.value.confirming)
        assertEquals("under the dialog the choice stays", chosen, asked.state.value.destination)

        val refused = model(ShareContent.Refused(IntakeRefusal.SCHEME_BLOCKED))
        val before = refused.state.value
        refused.changeAsset()
        assertEquals("a dead end is left as it is", before, refused.state.value)
    }

    /** R93-10: the note path's sentence shows on the picker, a choice clears it, and a Change does not restore it. */
    @Test fun theNotALinkSentenceIsThereBeforeAChoiceAndGoneAfterAChange() = runTest(scheduler) {
        val id = mower()
        val vm = model(ShareContent.PlainText("Replaced the drive belt, took an hour"))
        assertTrue(vm.state.value.picking)
        assertEquals("That is not a link.", vm.state.value.message)

        vm.choose(id, mowerName)
        assertNull(vm.state.value.message)

        vm.changeAsset()
        assertTrue(vm.state.value.picking)
        assertNull("a Change does not bring the sentence back", vm.state.value.message)
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

        vm.choose(id, mowerName)
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

        vm.choose(id, mowerName)
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
            "Description (optional)", "Type", "Role", "Save", "Cancel", "Close", "Save as a note",
            "That is not a link.", "Add an asset in ServiceTag first, then share this again.",
            "Choose an attachment folder in ServiceTag Settings, then share this again.",
            "That file cannot be accepted from the app that shared it.",
            "Could not read what was shared", "That link is too long to save.",
            "ServiceTag will not save that kind of link.",
            "That link is already on this asset", "That file is empty",
            "That file is larger than 256 MB", "Give the file a name",
            "Give the reference a name", "Save this link?",
            // #93 (R93-5, G1): the form's Change action.
            "Change",
        )
        val drawn = listOf(
            IntakeStrings.TITLE, IntakeStrings.RECEIVED, IntakeStrings.ATTACH_TO,
            IntakeStrings.CHOOSE_ASSET, IntakeStrings.NAME, IntakeStrings.DESCRIPTION,
            IntakeStrings.TYPE, IntakeStrings.ROLE, IntakeStrings.SAVE, IntakeStrings.CANCEL, IntakeStrings.CLOSE,
            IntakeStrings.SAVE_AS_NOTE, IntakeStrings.NOT_A_LINK, IntakeStrings.NO_ASSETS,
            IntakeStrings.NO_FOLDER, IntakeStrings.STREAM_REFUSED, IntakeStrings.UNREADABLE,
            IntakeStrings.URI_TOO_LONG, IntakeStrings.SCHEME_BLOCKED,
            IntakeStrings.DUPLICATE_URI, IntakeStrings.EMPTY_FILE, IntakeStrings.TOO_LARGE,
            IntakeStrings.BLANK_FILE_NAME, IntakeStrings.BLANK_REFERENCE_NAME,
            IntakeStrings.CONFIRM_TITLE, IntakeStrings.CHANGE,
        )

        assertEquals(ratified, drawn.toSet())
        assertEquals(
            "ServiceTag does not recognise \"zotero\" links. It will be saved as written and " +
                "opened with whatever app claims it.",
            IntakeStrings.confirmBody("zotero"),
        )
        assertEquals("Saved to Cub Cadet XT1", IntakeStrings.savedTo("Cub Cadet XT1"))
    }

    // --- #69 (B7; C29, C30, row 53c): the destination, its saves, the save form's lines and the dead end ---------------

    private val batteryName = "Example 12 V Battery"

    /** A SupplyItem through the production save; "Example Power Co." makes it. */
    private suspend fun battery(name: String = batteryName): SupplyItem = graph.saveSupplyItem.run(
        null, SupplyItemCommand(name, "", "Example Power Co.", "EB-12", "EB-12", "", "", emptyList()),
    ).item

    /** "Example Battery Tray", installed on the mower through the production install. */
    private suspend fun tray(): InstalledComponent = (
        graph.installComponent.run(
            InstallComponentCommand(assetId, null, "Example Battery Tray", null, emptyList(), "", "2026-01-05", "", null),
        ) as InstalledComponentResult.Ok
        ).row

    private fun supplyOf(item: SupplyItem) =
        ShareDestination.Supply(item.id.value, item.name, "Example Power Co. · EB-12")

    private fun componentOf(row: InstalledComponent) =
        ShareDestination.Component(row.assetId.value, row.id.value, listOf(mowerName, row.name))

    private suspend fun linksOn(owner: ReferenceOwner) = graph.references.forOwner(owner).size
    private suspend fun filesOn(owner: ReferenceOwner) = graph.attachments.forOwner(owner.asAttachmentOwner()).size

    /** Each destination's link is saved once, on that destination's own owner, and said so by its saved line. */
    @Test fun aLinkIsSavedOnEachDestinationsOwnOwner() = runTest(scheduler) {
        val id = mower()
        val item = battery()
        val row = tray()

        listOf(
            ShareDestination.Asset(id, mowerName) to "Saved to Cub Cadet XT1",
            componentOf(row) to "Saved to Cub Cadet XT1 › Example Battery Tray",
            supplyOf(item) to "Saved to supply Example 12 V Battery.",
        ).forEachIndexed { index, (destination, saved) ->
            val uri = "https://example.invalid/manual-$index.pdf"
            val vm = model(link(uri))
            vm.choose(destination)
            vm.saveAndSettle()

            assertEquals(saved, vm.state.value.saved)
            val written = graph.references.forOwner(destination.owner).single()
            assertEquals(destination.owner, written.owner)
            assertEquals(uri, written.uri)
        }
        assertEquals(1, references())
    }

    /** Row 53c: counted RED — the bytes arm maps a Component destination to its asset (`OfAsset`). */
    @Test fun aFileIsSavedOnEachDestinationsOwnOwner() = runTest(scheduler) {
        val id = mower()
        val item = battery()
        val row = tray()

        listOf(ShareDestination.Asset(id, mowerName), componentOf(row), supplyOf(item)).forEach { destination ->
            val vm = model(bytes(), source = { "pdf".byteInputStream() })
            vm.choose(destination)
            vm.saveAndSettle()
            assertEquals(destination.savedLine, vm.state.value.saved)
        }

        assertEquals("the asset's own file, and no other", 1, attachments())
        assertEquals(1, filesOn(componentOf(row).owner))
        assertEquals("stored once, on the catalog item", 1, filesOn(supplyOf(item).owner))
    }

    /**
     * Ownership is the final selection: a destination carries no route, so a supply picked from its list and the same
     * supply picked while browsing are one value, and their saves write rows that differ only in id, bytes' place and time.
     */
    @Test fun aSupplyReachedDirectlyAndOneReachedWhileBrowsingWriteIdenticalRows() = runTest(scheduler) {
        mower()
        val item = battery()
        val direct = ShareDestination.Supply(item.id.value, item.name, "Example Power Co. · EB-12")
        val browsed = ShareDestination.Supply(item.id.value, item.name, "Example Power Co. · EB-12")
        assertEquals(direct, browsed)

        listOf(direct, browsed).forEach { destination ->
            val vm = model(bytes(), source = { "pdf".byteInputStream() })
            vm.choose(destination)
            vm.saveAndSettle()
        }

        val rows = graph.attachments.forOwner(direct.owner.asAttachmentOwner())
        assertEquals(2, rows.size)
        assertEquals(1, rows.map { listOf(it.owner, it.kind, it.displayName, it.mimeType, it.sizeBytes, it.sha256, it.role) }.distinct().size)
    }

    /** The save form is the confirmation (no dialog): its destination line, and under a supply its product line and P69-21. */
    @Test fun theFormsDestinationLinesPerKind() {
        val asset = ShareDestination.Asset("a1", "Example Generator")
        val component = ShareDestination.Component(
            "a1", "c2", listOf("Example Generator", "Example Battery Tray", "Example 12 V Battery"),
        )
        val supply = ShareDestination.Supply("s1", "Example 12 V Battery", "Example Power Co. · EB-12")
        val onSupply = "This will be saved on the supply Example 12 V Battery and available wherever that supply is used."

        assertEquals("Example Generator", asset.label)
        assertEquals(emptyList<String>(), asset.notes)
        assertEquals("Example Generator › Example Battery Tray › Example 12 V Battery", component.label)
        assertEquals(emptyList<String>(), component.notes)
        assertEquals("Example 12 V Battery", supply.label)
        assertEquals(listOf("Example Power Co. · EB-12", onSupply), supply.notes)
        assertEquals("no product line, no empty line", listOf(onSupply), supply.copy(productLine = "").notes)

        assertEquals("Saved to Example Generator", asset.savedLine)
        assertEquals("Saved to Example Generator › Example Battery Tray › Example 12 V Battery", component.savedLine)
        assertEquals("Saved to supply Example 12 V Battery.", supply.savedLine)
    }

    /** P69-20…22 and P69-26, each from its one home, in the owner's words. */
    @Test fun theDestinationWordsAreTheRatifiedOnes() {
        assertEquals("No active assets", IntakeStrings.NO_ACTIVE_ASSETS)
        assertEquals("Example Generator › Example Battery Tray", IntakeStrings.pathOf(listOf("Example Generator", "Example Battery Tray")))
        assertEquals("Example Generator", IntakeStrings.pathOf(listOf("Example Generator")))
        assertEquals(
            "This will be saved on the supply Example 12 V Battery and available wherever that supply is used.",
            IntakeStrings.onSupply("Example 12 V Battery"),
        )
        assertEquals("Saved to supply Example 12 V Battery.", IntakeStrings.savedToSupply("Example 12 V Battery"))
        assertEquals("Attach to", IntakeStrings.ATTACH_TO)
    }

    /** C28's twin per owner kind: the same link twice on a supply or a component says that owner's sentence. */
    @Test fun aDuplicateLinkSaysTheOwnersOwnSentence() = runTest(scheduler) {
        mower()
        val item = battery()
        val row = tray()

        listOf(
            supplyOf(item) to "That link is already on this supply",
            componentOf(row) to "That link is already on this installed component",
        ).forEach { (destination, sentence) ->
            model(link(manualUrl)).also { it.choose(destination); it.saveAndSettle() }
            val again = model(link(manualUrl, name = "Again"))
            again.choose(destination)
            again.saveAndSettle()

            assertEquals(sentence, again.state.value.message)
            assertNull(again.state.value.saved)
            assertEquals(1, linksOn(destination.owner))
        }
        assertEquals("one URI on two other owners is not the asset's", 0, references())
    }

    /** Prose is a journal note, which belongs to an asset: a component or a supply is never taken on that path. */
    @Test fun proseSavesANoteOnTheAssetOnly() = runTest(scheduler) {
        val id = mower()
        val item = battery()
        val row = tray()
        val vm = model(ShareContent.PlainText("Replaced the drive belt, took an hour"))

        vm.choose(supplyOf(item))
        assertNull(vm.state.value.destination)
        vm.choose(componentOf(row))
        assertNull(vm.state.value.destination)
        assertTrue("still on the picker", vm.state.value.picking)
        vm.saveAndSettle()
        assertEquals(0, events())

        vm.choose(id, mowerName)
        vm.saveAndSettle()

        assertEquals("Saved to Cub Cadet XT1", vm.state.value.saved)
        assertEquals(1, events())
        assertEquals(0, linksOn(supplyOf(item).owner) + linksOn(componentOf(row).owner))
        assertEquals(0, filesOn(supplyOf(item).owner) + filesOn(componentOf(row).owner))
    }

    /** I-8 for the new owners: choosing, typing, Change and Cancel write nothing on a component or a supply. */
    @Test fun cancelOnAComponentOrSupplyDestinationWritesNothing() = runTest(scheduler) {
        mower()
        val item = battery()
        val row = tray()

        listOf(componentOf(row), supplyOf(item)).forEach { destination ->
            model(link(manualUrl)).also { it.choose(destination); it.name("Typed a name"); it.cancel() }
            model(bytes(), source = { error("cancelled intake must never open a stream") }).also {
                it.choose(destination)
                it.changeAsset()
                assertTrue("Change goes back to the picker", it.state.value.picking)
                assertNull(it.state.value.destination)
                it.cancel()
            }
        }
        store.clear()

        listOf(componentOf(row).owner, supplyOf(item).owner).forEach { owner ->
            assertEquals(0, linksOn(owner))
            assertEquals(0, filesOn(owner))
        }
        assertTrue(graph.attachmentStorage.store.files.isEmpty())
    }

    /** Every save meets the guard: a component whose asset is transferred out once chosen is refused with P77-35. */
    @Test fun aComponentOfAnAssetTransferredOutIsRefusedAndNothingIsWritten() = runTest(scheduler) {
        mower()
        val row = tray()
        val vm = model(link(manualUrl))
        vm.choose(componentOf(row))
        graph.transferRecords.append(heldOut(assetId))

        vm.saveAndSettle()

        assertEquals("This asset was transferred out.", vm.state.value.message)
        assertNull(vm.state.value.saved)
        assertEquals(0, linksOn(componentOf(row).owner))
    }

    /**
     * C-5: with no asset maintained here — archived, retired, held — prose dead-ends at NO_ASSETS; a link or a file does
     * too unless an unarchived supply exists, which keeps it on the picker.
     */
    @Test fun proseAndLinkSharesWithNoActiveAssetDeadEndAtNoAssets() = runTest(scheduler) {
        graph.assets.upsert(assetRow("ladder", name = "Sample Ladder", status = AssetStatus.ARCHIVED))
        graph.assets.upsert(assetRow("generator", name = "Example Generator", retiredOn = "2026-03-01"))
        graph.assets.upsert(assetRow("heater", name = "Example Water Heater"))
        graph.transferRecords.append(heldOut(AssetId("heater")))
        graph.archiveSupplyItem.run(battery().id, archived = true)
        val prose = ShareContent.PlainText("Checked the anode")

        listOf(prose, link(manualUrl), bytes()).forEach { content ->
            val state = model(content, source = { "pdf".byteInputStream() }).state.value
            assertEquals("$content", IntakeStrings.NO_ASSETS, state.deadEnd)
            assertTrue(state.assets.isEmpty())
            assertFalse(state.picking)
        }

        battery(name = "Example Anode")
        listOf(link(manualUrl), bytes()).forEach { content ->
            val state = model(content, source = { "pdf".byteInputStream() }).state.value
            assertNull("an unarchived supply: $content", state.deadEnd)
            assertTrue(state.picking)
        }
        assertEquals("prose still needs an asset", IntakeStrings.NO_ASSETS, model(prose).state.value.deadEnd)
    }

    // --- #69 (B7c2; C30 steps 1–4, row 53b): the type control, the one query and the direct lists, wired ----------

    /** The hosted Assets list, built as the activity builds it (`activeOnly`); its query is the one query (C30). */
    private fun TestScope.hostedPicker() = AssetsViewModel(
        graph.assets, graph.categories, graph.seasonActivations, graph.tags, graph.assetHealthReadModel,
        graph.todayPort, loans = graph.loans, transfers = graph.transferRecords, activeOnly = true,
    ).also { vm -> backgroundScope.launch { vm.state.collect() } }

    private fun <T> ShareTargetList<T>.rows(): List<T> = (this as ShareTargetList.Rows).rows

    /** The type control's three labels are the shipped words, imported; P69-24/-25 are the ratified hints. */
    @Test fun theTypeLabelsAndTheSearchHintsAreTheRatifiedWords() {
        assertEquals(listOf("Assets", "Installed components", "Supplies"), ShareTargetType.entries.map { it.label })
        assertEquals("Search installed components", IntakeStrings.SEARCH_INSTALLED_COMPONENTS)
        assertEquals("Search supplies", IntakeStrings.SEARCH_SUPPLIES)
        assertEquals("Assets is the default", ShareTargetType.ASSETS, ShareIntakeState().type)
    }

    /** A component row and a supply row are each the final selection: the form opens on it, and nothing is written. */
    @Test fun aComponentOrSupplyPickOpensTheFormDirectly() = runTest(scheduler) {
        mower()
        val tray = tray()
        val item = battery()
        val vm = model(link(manualUrl))
        assertTrue(vm.state.value.typeOffered)
        assertEquals(ShareTargetType.ASSETS, vm.state.value.type)

        vm.chooseType(ShareTargetType.INSTALLED_COMPONENTS)
        val component = vm.state.value.componentRows("").rows().single()
        vm.choose(component.destination)
        assertFalse(vm.state.value.picking)
        assertEquals(componentOf(tray), vm.state.value.destination)
        assertEquals("$mowerName › Example Battery Tray", vm.state.value.destination?.label)

        vm.changeAsset()
        assertTrue(vm.state.value.picking)
        assertEquals("Change keeps the list", ShareTargetType.INSTALLED_COMPONENTS, vm.state.value.type)

        vm.chooseType(ShareTargetType.SUPPLIES)
        val supply = vm.state.value.supplyRows("").rows().single()
        vm.choose(supply.destination)
        assertFalse(vm.state.value.picking)
        assertEquals(supplyOf(item), vm.state.value.destination)
        assertEquals(0, linksOn(componentOf(tray).owner) + linksOn(supplyOf(item).owner) + references())
    }

    /**
     * C-5 (the counted RED): a link share with no asset maintained here and one unarchived supply reaches the picker —
     * Assets chosen and empty for P69-26's reason, no installed component, and the supply selectable under Supplies.
     */
    @Test fun aLinkShareWithNoActiveAssetAndOneUnarchivedSupplyReachesThePicker() = runTest(scheduler) {
        graph.assets.upsert(assetRow("ladder", name = "Sample Ladder", status = AssetStatus.ARCHIVED))
        val item = battery()
        val picker = hostedPicker()
        val vm = model(link(manualUrl))
        advanceUntilIdle()

        assertNull(vm.state.value.deadEnd)
        assertTrue(vm.state.value.picking)
        assertEquals(ShareTargetType.ASSETS, vm.state.value.type)
        assertEquals("P69-26's slot", EmptyReason.NO_ASSETS, picker.state.value.emptyReason)
        assertEquals(ShareTargetList.NoneEligible, vm.state.value.componentRows(""))

        vm.chooseType(ShareTargetType.SUPPLIES)
        vm.choose(vm.state.value.supplyRows("").rows().single().destination)
        assertEquals(supplyOf(item), vm.state.value.destination)
        assertTrue(vm.state.value.saveEnabled)
    }

    /** Prose is an asset's note: no type control, and a type choice is not taken; a link and a file offer it. */
    @Test fun aProseShareHasNoTypeControl() = runTest(scheduler) {
        mower()
        tray()
        battery()
        val prose = model(ShareContent.PlainText("Checked the anode"))
        assertFalse(prose.state.value.typeOffered)
        prose.chooseType(ShareTargetType.SUPPLIES)
        assertEquals(ShareTargetType.ASSETS, prose.state.value.type)

        assertTrue(model(bytes(), source = { "pdf".byteInputStream() }).state.value.typeOffered)
        assertTrue(model(link(manualUrl)).state.value.typeOffered)
    }

    /** C-6, C-7: one query — the hosted Assets list's — narrows each list, and a type switch leaves it as it was. */
    @Test fun theOneQueryNarrowsEachListAndSurvivesATypeSwitch() = runTest(scheduler) {
        mower()
        val tray = tray()
        val item = battery()
        val picker = hostedPicker()
        val vm = model(link(manualUrl))

        picker.onQueryChange("tray")
        vm.chooseType(ShareTargetType.INSTALLED_COMPONENTS)
        assertEquals(listOf(tray.id), vm.state.value.componentRows(picker.query.value).rows().map { it.componentId })
        vm.chooseType(ShareTargetType.SUPPLIES)
        assertEquals("the switch leaves the query", "tray", picker.query.value)
        assertEquals(ShareTargetList.NothingMatches, vm.state.value.supplyRows(picker.query.value))

        picker.onQueryChange("power co")
        assertEquals(listOf(item.id), vm.state.value.supplyRows(picker.query.value).rows().map { it.supplyId })
        assertEquals(ShareTargetList.NothingMatches, vm.state.value.componentRows(picker.query.value))
        vm.chooseType(ShareTargetType.ASSETS)
        assertEquals("power co", picker.query.value)
    }

    /**
     * The lists read what the dead end reads: a held asset's component is not offered, nor an archived supply; a supply
     * taken by an asset and one taken by none are both offered (Supplies holds every unarchived SupplyItem).
     */
    @Test fun theListsOfferCurrentComponentsOfActiveAssetsAndEveryUnarchivedSupply() = runTest(scheduler) {
        mower()
        val tray = tray()
        val heater = graph.createAsset.run(AssetCommand(name = "Example Water Heater"))
        graph.installComponent.run(
            InstallComponentCommand(heater.id, null, "Example Anode Rod", null, emptyList(), "", "2026-01-05", "", null),
        )
        graph.transferRecords.append(heldOut(heater.id))
        val taken = battery()
        val loose = battery(name = "Example Alternator Belt")
        graph.archiveSupplyItem.run(battery(name = "Example Old Belt").id, archived = true)
        graph.addAssetSupply.run(AddAssetSupplyCommand(assetId, taken.id, "Battery"))

        val state = model(bytes(), source = { "pdf".byteInputStream() }).state.value

        assertEquals(listOf(tray.id), state.componentRows("").rows().map { it.componentId })
        assertEquals(listOf(taken.id, loose.id), state.supplyRows("").rows().map { it.supplyId })
    }

    // --- #77 (B3; C16, row 25): a shared Transfer Pack, and a held asset --------------------------------------

    /** A model with #77's two collaborators: the held set, and the inbox a shared pack is copied into. */
    private fun packModel(content: ShareContent, source: ByteSource?): ShareIntakeViewModel {
        val factory = viewModelFactory {
            initializer {
                ShareIntakeViewModel(
                    readShare = { SharedShare(content, streamUri = null, bytes = source) },
                    assets = graph.assets,
                    storage = graph.attachmentStorage,
                    addReference = addReference,
                    addAttachment = graph.addAttachment,
                    logEvent = graph.logEvent,
                    today = { "2026-09-23" },
                    zoneId = { "UTC" },
                    io = StandardTestDispatcher(scheduler),
                    heldIds = { graph.transferRecords.heldIds() },
                    packInbox = graph.transferPackInbox,
                )
            }
        }
        val vm = ViewModelProvider.create(store, factory)["model-${models++}", ShareIntakeViewModel::class]
        scheduler.advanceUntilIdle()
        return vm
    }

    /** A pack made by the production creation on another installation: "Example Water Heater" and its manual. */
    private suspend fun heaterPack(): ByteArray {
        val sender = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        TransferPackAppFixtures.seedHeater(sender)
        return TransferPackAppFixtures.seal(sender, TransferPackAppFixtures.HEATER).bytes.also { sender.close() }
    }

    private fun zipShare(bytes: ByteArray, name: String) =
        ShareContent.Bytes(name, "application/zip", bytes.size.toLong()) to ByteSource { bytes.inputStream() }

    private fun heldOut(asset: AssetId) = TransferRecord(
        id = "out-${asset.value}", assetId = asset, kind = TransferKind.OUT, packId = "pack-elsewhere",
        lineage = emptyList(), at = 1_758_960_000_000L, packSha256 = "ab".repeat(32),
        nameSnapshot = "Example Water Heater", note = "",
    )

    @Test fun aManifestFirstZipRoutesToImport() = runTest(scheduler) {
        mower()
        val (content, source) = zipShare(heaterPack(), "Download.zip")

        val vm = packModel(content, source)

        assertEquals(IntakePath.TRANSFER_PACK, vm.state.value.path)
        assertTrue(vm.state.value.packCopy != null)
        assertNull(vm.state.value.deadEnd)
        assertFalse(vm.state.value.saveEnabled)
    }

    /** Named like a pack, but its first entry is not the manifest: it stays an ordinary document. */
    @Test fun otherZipsKeepTheByteForm() = runTest(scheduler) {
        mower()
        val other = TransferPackAppFixtures.zipOf(listOf("readme.txt" to "Example".toByteArray(), "transfer-manifest.json" to "{}".toByteArray()))
        val (content, source) = zipShare(other, "servicetag-transfer-2026-09-27-abcdef12.zip")

        val vm = packModel(content, source)

        assertEquals(IntakePath.BYTES, vm.state.value.path)
        assertNull(vm.state.value.packCopy)
    }

    @Test fun thePackIsCopiedBeforeFinish() = runTest(scheduler) {
        val pack = heaterPack()
        val (content, source) = zipShare(pack, "Download.zip")

        val vm = packModel(content, source)

        val copy = vm.state.value.packCopy!!
        assertFalse(vm.state.value.finished)
        assertTrue(copy.isFile)
        assertTrue(pack.contentEquals(copy.readBytes()))
    }

    @Test fun noFolderSaysTheIntakesSentence() = runTest(scheduler) {
        val (content, source) = zipShare(heaterPack(), "Download.zip")
        graph.attachmentStorage.state = StoreState.NotConfigured
        val vm = packModel(content, source)

        val import = shareTransferImport(
            graph.importTransferPack, graph.transferPackInbox, vm.state.value.packCopy, ReminderReconcile {}, StandardTestDispatcher(scheduler),
        )
        advanceUntilIdle()

        assertEquals(IntakeStrings.NO_FOLDER, import.state.value.refusal)
    }

    /** R77-IMPORT-SWEEP: the share door's import sweeps once too, after the write. */
    @Test fun successShowsTheLineThenCloseFinishes() = runTest(scheduler) {
        val (content, source) = zipShare(heaterPack(), "Download.zip")
        val vm = packModel(content, source)
        var sweeps = 0
        val import = shareTransferImport(
            graph.importTransferPack, graph.transferPackInbox, vm.state.value.packCopy, ReminderReconcile { sweeps += 1 },
            StandardTestDispatcher(scheduler),
        )
        advanceUntilIdle()

        import.import()
        advanceUntilIdle()
        assertEquals("Transfer Pack imported: 1 asset, 1 NFC tag, 1 document or photo", import.state.value.done)
        assertEquals(1, sweeps)
        assertFalse(import.state.value.finished)
        import.close()

        assertTrue(import.state.value.finished)
        assertEquals("Example Water Heater", graph.assets.get(AssetId(TransferPackAppFixtures.HEATER))?.name)
    }

    @Test fun attachToNeverOffersAHeldAsset() = runTest(scheduler) {
        val id = mower()
        val held = graph.createAsset.run(AssetCommand(name = "Example Water Heater"))
        graph.transferRecords.append(heldOut(held.id))

        val vm = packModel(link(manualUrl), null)

        assertEquals(listOf(id), vm.state.value.assets.map { it.assetId })
    }

    /** Listed, then transferred out before Save: the write is refused with P77-35 and nothing is written. */
    @Test fun aHeldTargetRefusalSaysP77_35() = runTest(scheduler) {
        val id = mower()
        val vm = packModel(link(manualUrl), null)
        vm.choose(id, mowerName)
        graph.transferRecords.append(heldOut(AssetId(id)))

        vm.saveAndSettle()

        assertEquals("This asset was transferred out.", vm.state.value.message)
        assertNull(vm.state.value.saved)
        assertEquals(0, references())
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
