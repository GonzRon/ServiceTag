package com.loosecannon.servicetag.ui.references

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.loosecannon.servicetag.core.fetch.DocumentTransport
import com.loosecannon.servicetag.core.fetch.FetchDocument
import com.loosecannon.servicetag.core.fetch.FetchProblem
import com.loosecannon.servicetag.core.fetch.HopPolicy
import com.loosecannon.servicetag.core.fetch.HostResolver
import com.loosecannon.servicetag.core.fetch.TransportResponse
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentProblem
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.MaterializeReference
import com.loosecannon.servicetag.core.usecase.MaterializeRefusal
import com.loosecannon.servicetag.core.usecase.MaterializeReview
import com.loosecannon.servicetag.core.usecase.SourceSnapshot
import com.loosecannon.servicetag.fetch.CacheStagingArea
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.ui.references.MaterializeState.Closed
import com.loosecannon.servicetag.ui.references.MaterializeState.Done
import com.loosecannon.servicetag.ui.references.MaterializeState.Downloading
import com.loosecannon.servicetag.ui.references.MaterializeState.Refused
import com.loosecannon.servicetag.ui.references.MaterializeState.Review
import com.loosecannon.servicetag.ui.references.MaterializeState.Saving
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.properties.Delegates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #85 row 30 (C21) and the owner's designation invariant: the Save-as-document sheet's one view model, over the
 * production [MaterializeReference] wired as `FakeGraph` wires it (B4), except that the staging directory is one this
 * test can list — so "discarded" is a file that is gone, not a call that was made — and the transport can be parked
 * to hold a download in flight. Nothing here reaches a network: the fake transport serves the bytes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MaterializeViewModelTest {

    private companion object {
        const val URI = "https://manuals.example.invalid/pool-pump/manual.pdf"
        const val HOST = "manuals.example.invalid"
        const val NAME = "Example Pool Pump manual"
        const val DESCRIPTION = "Filter and seal section"
        val PDF = "%PDF-1.7 Example Pool Pump manual %%EOF".toByteArray()
        const val PACK = "0f1e2d3c-4b5a-4968-8776-655443322110"
        const val NETWORK_DENIED = "ServiceTag is not allowed to use the network, so it cannot download this file. " +
            "Allow network access in the app settings, then close ServiceTag and open it again."
    }

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph
    private lateinit var staging: File
    private val store = ViewModelStore()
    private var assetId: AssetId by Delegates.notNull()

    /** True: the transport never answers, so a download stays in flight until it is cancelled. */
    private var parked = false
    private var parkedGets = 0

    /** True: the reference read throws, as a database that will not answer does. */
    private var brokenRead = false

    /** Run once, right after `prepare`'s duplicate check — its last suspension before it answers `Ready`. */
    private var afterDuplicateCheck: (() -> Unit)? = null

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        staging = createTempDirectory("materialize-vm").toFile()
    }

    @After fun tearDown() {
        store.clear()
        graph.close()
        staging.deleteRecursively()
        Dispatchers.resetMain()
    }

    /** The last line of every case that builds a model (the #65 shape). */
    private fun TestScope.clearModels() {
        store.clear()
        advanceUntilIdle()
    }

    private fun materialize(): MaterializeReference {
        val hops = HopPolicy(HostResolver { listOf(byteArrayOf(203.toByte(), 0, 113, 10)) })
        val transport = object : DocumentTransport {
            override suspend fun get(url: String): TransportResponse =
                if (parked) { parkedGets++; awaitCancellation() } else graph.documentTransport.get(url)
        }
        val references = object : ReferenceRepository by graph.references {
            override suspend fun get(id: ReferenceId): AssetReference? =
                if (brokenRead) throw IllegalStateException("the read failed") else graph.references.get(id)
        }
        val attachments = object : AttachmentRepository by graph.attachments {
            override suspend fun forAsset(assetId: AssetId): List<Attachment> =
                graph.attachments.forAsset(assetId).also { afterDuplicateCheck?.invoke() }
        }
        return MaterializeReference(
            references, attachments, graph.attachmentStorage, LinkLaunchPolicy(), hops,
            FetchDocument(transport, hops, CacheStagingArea(staging, graph.ids), io = StandardTestDispatcher(scheduler)),
            graph.addAttachment, { graph.networkGranted }, graph.clock,
        )
    }

    /** The asset, its one web reference, and the reference's PDF served. */
    private suspend fun poolPump(name: String = NAME, description: String = DESCRIPTION) {
        assetId = graph.createAsset.run(AssetCommand(name = "Example Pool Pump")).id
        graph.uow.write {
            graph.references.upsert(
                AssetReference(
                    id = ReferenceId("ref-1"), assetId = assetId, kind = ReferenceKind.WEB_URL, uri = URI,
                    displayName = name, description = description, scheme = "https", createdAt = 10L, updatedAt = 10L,
                ),
            )
        }
        graph.documentTransport.serve(URI, PDF, "application/pdf")
    }

    private fun model(referenceId: String = "ref-1"): MaterializeViewModel {
        val factory = viewModelFactory {
            initializer {
                MaterializeViewModel(
                    assetId, ReferenceId(referenceId), URI, materialize(), io = StandardTestDispatcher(scheduler),
                )
            }
        }
        return ViewModelProvider.create(store, factory)["materialize-$referenceId", MaterializeViewModel::class]
    }

    private fun staged(): List<String> = staging.listFiles().orEmpty().map { it.name }

    private suspend fun rows() = graph.attachments.forAsset(assetId)

    @Test fun downloadsFirstThenReviews() = runTest {
        poolPump()
        val vm = model()
        assertEquals("the download starts at once", Downloading(HOST, 0L, null), vm.state.value)

        val review = vm.state.first { it is Review }

        assertEquals(
            "prefill: the reference's name and description, the kind the type implies, no role",
            Review(HOST, "PDF · ${PDF.size} B", NAME, AttachmentKind.DOCUMENT, null, DESCRIPTION, null),
            review,
        )
        assertEquals(listOf(URI), graph.documentTransport.requests)
        assertTrue("nothing is written before Save", rows().isEmpty())
        assertEquals("the download waits in staging for the review", 1, staged().size)
        clearModels()
    }

    /**
     * The owner's designation invariant: a reference carries no role today, so the review starts with none,
     * whatever its words say; the kind comes from the proven type, never from the text.
     */
    @Test fun theReviewNeverGuessesARoleFromTheText() {
        assertEquals(
            MaterializeReview("Service manual", AttachmentKind.DOCUMENT, null, "Invoice"),
            reviewPrefill(SourceSnapshot(URI, "Service manual", "Invoice", HOST), "application/pdf"),
        )
        assertEquals(
            MaterializeReview("User manual", AttachmentKind.PHOTO, null, "Purchase invoice or receipt"),
            reviewPrefill(SourceSnapshot(URI, "User manual", "Purchase invoice or receipt", HOST), "image/png"),
        )
    }

    /** B2c/B3: `onProgress` carries the declared length, which a server can understate. */
    @Test fun progressFlowsIntoDownloading() = runTest {
        assertEquals(Downloading(HOST, 5L, 10L), downloading(HOST, 5L, 10L))
        assertEquals("a total the bytes outran is dropped", Downloading(HOST, 12L, null), downloading(HOST, 12L, 10L))
        assertEquals(Downloading(HOST, 3L, null), downloading(HOST, 3L, null))

        poolPump()
        graph.documentTransport.serve(URI, PDF, "application/pdf", contentLength = 4L)
        val seen = mutableListOf<MaterializeState>()
        val vm = model()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { seen += it } }

        vm.state.first { it is Review }

        val downloading = seen.filterIsInstance<Downloading>()
        assertEquals(Downloading(HOST, PDF.size.toLong(), null), downloading.last())
        assertTrue("never more done than a total shown", downloading.all { d -> d.total.let { it == null || d.done <= it } })
        clearModels()
    }

    /** C21's list, one line each; P85-10 alone offers the app's settings; two refusals close without a line. */
    @Test fun everyRefusalMapsToItsLine() = runTest {
        val table = listOf(
            MaterializeRefusal.NoSuchReference to Closed,
            MaterializeRefusal.NotEligible to Closed,
            MaterializeRefusal.Store(AttachmentProblem.NoStore) to
                Refused("Choose an attachment folder in Settings first", false),
            MaterializeRefusal.Store(AttachmentProblem.StoreUnavailable) to
                Refused("The attachment folder is not available", false),
            MaterializeRefusal.NetworkDenied to Refused(NETWORK_DENIED, true),
            MaterializeRefusal.Fetch(FetchProblem.NetworkDenied) to Refused(NETWORK_DENIED, true),
            MaterializeRefusal.Fetch(FetchProblem.Unreachable) to
                Refused("Could not reach manuals.example.invalid. Check the connection and try again.", false),
            MaterializeRefusal.Fetch(FetchProblem.TimedOut) to
                Refused("The download took too long and was stopped.", false),
            MaterializeRefusal.Fetch(FetchProblem.NotADocument) to
                Refused("That link did not lead to a supported document type. It stays a link.", false),
            MaterializeRefusal.Fetch(FetchProblem.NeedsSignIn) to
                Refused("That file needs a sign-in, so ServiceTag cannot download it. It stays a link.", false),
            MaterializeRefusal.Fetch(FetchProblem.ServerError(503)) to
                Refused("The server did not send the file (error 503).", false),
            MaterializeRefusal.Fetch(FetchProblem.RedirectRefused) to
                Refused("That link redirects somewhere ServiceTag will not follow. It stays a link.", false),
            MaterializeRefusal.Fetch(FetchProblem.Interrupted) to
                Refused("The download could not be completed. Try again.", false),
            MaterializeRefusal.Fetch(FetchProblem.LocalAddress) to Refused(
                "That link resolves to a local-network address, so ServiceTag will not download it. It stays a link.",
                false,
            ),
            MaterializeRefusal.Fetch(FetchProblem.TooLarge) to Refused("That file is larger than 256 MB", false),
            MaterializeRefusal.Fetch(FetchProblem.Empty) to Refused("That file is empty", false),
            MaterializeRefusal.Fetch(FetchProblem.NotHttps) to Closed,
            MaterializeRefusal.Fetch(FetchProblem.HasCredentials) to Closed,
            MaterializeRefusal.AlreadyHave(NAME) to
                Refused("This asset already has this file: Example Pool Pump manual.", false),
        )
        val answered = table.associate { (why, _) -> why to refusalState(why, HOST) }
        assertEquals(table.toMap(), answered)

        // Wired: the permission off answers P85-10 with the settings button, and nothing is fetched or written.
        poolPump()
        graph.networkGranted = false
        val vm = model()
        assertEquals(Refused(NETWORK_DENIED, true), vm.state.first { it !is Downloading })
        assertTrue(graph.documentTransport.requests.isEmpty())
        assertTrue(rows().isEmpty())
        clearModels()
    }

    /** Controller ruling: an unexpected throw out of `prepare` says P85-18, never a silent close. */
    @Test fun anUnexpectedFailureSaysTheInterruptedLine() = runTest {
        poolPump()
        brokenRead = true
        val vm = model()

        assertEquals(
            Refused("The download could not be completed. Try again.", false),
            vm.state.first { it !is Downloading },
        )
        assertTrue(graph.documentTransport.requests.isEmpty())
        assertTrue(rows().isEmpty())
        assertTrue(staged().isEmpty())
        clearModels()
    }

    /** Saving is set before the first suspension, so the second tap finds it and does nothing. */
    @Test fun aDoubleTapCommitsOnce() = runTest {
        poolPump()
        val vm = model()
        vm.state.first { it is Review }

        vm.save()
        vm.save()
        assertEquals(Saving, vm.state.value)

        assertEquals(Done, vm.state.first { it !is Saving })
        advanceUntilIdle()
        assertEquals(Done, vm.state.value)
        assertEquals(1, rows().size)
        clearModels()
    }

    @Test fun saveGoesDoneOnce() = runTest {
        poolPump()
        val vm = model()
        vm.state.first { it is Review }
        vm.rename("Pool pump manual")
        vm.chooseKind(AttachmentKind.MANUAL)
        vm.chooseRole(DocumentRole.USER_MANUAL)
        vm.editNotes("Keep with the filter spares")

        vm.save()
        vm.state.first { it == Done }

        val row = rows().single()
        assertEquals("Pool pump manual", row.displayName)
        assertEquals(AttachmentKind.MANUAL, row.kind)
        assertEquals(DocumentRole.USER_MANUAL, row.role)
        assertEquals("Keep with the filter spares", row.notes)
        assertEquals(URI, row.source?.uri)
        assertEquals(NAME, row.source?.name)
        assertTrue("the host takes Done once", vm.handOffDone())
        assertFalse("and never twice", vm.handOffDone())
        assertEquals(Closed, vm.state.value)
        assertTrue(staged().isEmpty())
        clearModels()
    }

    @Test fun aBlankNameStaysInReviewWithTheReusedLine() = runTest {
        poolPump()
        val vm = model()
        vm.state.first { it is Review }
        vm.rename("   ")

        vm.save()
        val refused = vm.state.first { it !is Saving } as Review

        assertEquals("Give the file a name", refused.nameError)
        assertEquals("   ", refused.name)
        assertTrue(rows().isEmpty())
        assertEquals("the staging is kept for the next Save", 1, staged().size)

        vm.rename("Pool pump manual")
        assertEquals(null, (vm.state.value as Review).nameError)
        vm.save()
        assertEquals(Done, vm.state.first { it !is Saving })
        assertEquals("Pool pump manual", rows().single().displayName)
        clearModels()
    }

    @Test fun cancelInDownloadingAndReviewWritesNothingAndDiscards() = runTest {
        poolPump()
        val vm = model()
        vm.state.first { it is Review }
        assertEquals(1, staged().size)

        vm.cancel()

        assertEquals(Closed, vm.state.value)
        assertEquals("Cancel in Review discards the staging", emptyList<String>(), staged())

        // Downloading: the GET never answers until Cancel stops it.
        parked = true
        vm.start()
        runCurrent()
        assertTrue(vm.state.value is Downloading)
        assertEquals("the GET is in flight", 1, parkedGets)

        vm.cancel()
        runCurrent()

        assertEquals(Closed, vm.state.value)
        assertEquals(emptyList<String>(), staged())
        assertTrue(rows().isEmpty())
        clearModels()
    }

    /**
     * Review MINOR 1: a Cancel that lands after the download is in hand but before `prepare` answers. `prepare`
     * returns `Ready` into a cancelled job, and the sheet must discard it, not leave it in staging.
     */
    @Test fun aDownloadThatLandsAfterCancelIsDiscarded() = runTest {
        poolPump()
        lateinit var vm: MaterializeViewModel
        afterDuplicateCheck = { afterDuplicateCheck = null; vm.cancel() }
        vm = model()

        vm.state.first { it == Closed }
        advanceUntilIdle()

        assertEquals(listOf(URI), graph.documentTransport.requests)
        assertEquals(Closed, vm.state.value)
        assertEquals("the late Ready is discarded", emptyList<String>(), staged())
        assertTrue(rows().isEmpty())
        clearModels()
    }

    /** Review MINOR 2: the folder's access lost between Review and Save says the reused store line. */
    @Test fun aStoreLostBeforeSaveSaysTheShippedLine() = runTest {
        poolPump()
        val vm = model()
        vm.state.first { it is Review }
        graph.attachmentStorage.state = StoreState.AccessLost("Attachments")

        vm.save()

        assertEquals(Refused("The attachment folder is not available", false), vm.state.first { it !is Saving })
        assertTrue(rows().isEmpty())
        assertTrue(staged().isEmpty())
        clearModels()
    }

    /** Review MINOR 2: the asset gone between Review and Save (`OwnerMissing`) says the reused "Could not save". */
    @Test fun anAssetGoneBeforeSaveSaysCouldNotSave() = runTest {
        poolPump()
        val vm = model()
        vm.state.first { it is Review }
        graph.uow.write { graph.assets.delete(assetId) }

        vm.save()

        assertEquals(Refused("Could not save Example Pool Pump manual", false), vm.state.first { it !is Saving })
        assertTrue(rows().isEmpty())
        assertTrue(staged().isEmpty())
        clearModels()
    }

    @Test fun clearingTheModelDiscards() = runTest {
        poolPump()
        val vm = model()
        vm.state.first { it is Review }
        assertEquals(1, staged().size)

        clearModels()

        assertEquals(emptyList<String>(), staged())
        assertTrue(rows().isEmpty())
    }

    @Test fun aTransferredOutAssetSaysTheShippedLine() = runTest {
        poolPump()
        val vm = model()
        vm.state.first { it is Review }
        graph.transferRecords.append(
            TransferRecord(
                id = "out-pool-pump", assetId = assetId, kind = TransferKind.OUT, packId = PACK,
                lineage = emptyList(), at = 5_000L, packSha256 = "ab".repeat(32),
                nameSnapshot = "Example Pool Pump", note = "",
            ),
        )

        vm.save()

        assertEquals(Refused("This asset was transferred out.", false), vm.state.first { it !is Saving })
        assertTrue(rows().isEmpty())
        assertTrue(staged().isEmpty())
        clearModels()
    }
}
