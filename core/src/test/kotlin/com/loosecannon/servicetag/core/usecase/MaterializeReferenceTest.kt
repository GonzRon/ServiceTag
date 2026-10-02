package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.fetch.FetchDocument
import com.loosecannon.servicetag.core.fetch.FetchLimits
import com.loosecannon.servicetag.core.fetch.FetchProblem
import com.loosecannon.servicetag.core.fetch.HopPolicy
import com.loosecannon.servicetag.core.fetch.TransportFailure
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentKinds
import com.loosecannon.servicetag.core.model.AttachmentMode
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.AttachmentProblem
import com.loosecannon.servicetag.core.model.AttachmentSource
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.StoreIoException
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.FakeAttachmentStorage
import com.loosecannon.servicetag.core.testing.FakeBody
import com.loosecannon.servicetag.core.testing.FakeDocumentTransport
import com.loosecannon.servicetag.core.testing.FakeHostResolver
import com.loosecannon.servicetag.core.testing.FakeStaging
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryInstalledComponentRepository
import com.loosecannon.servicetag.core.testing.InMemorySupplyItemRepository
import com.loosecannon.servicetag.core.testing.RecordingReferenceRepository
import com.loosecannon.servicetag.core.testing.RiggedFailure
import com.loosecannon.servicetag.core.testing.transferOf
import com.loosecannon.servicetag.core.transfer.AssetTransferredOut
import com.loosecannon.servicetag.core.transfer.HeldWriteGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.security.MessageDigest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rows 19–23 (#85 C13–C15; R85-1, R85-2, R85-3, R85-6, R85-8, R85-14): saving a reference's document through the
 * real fetch over the core fakes. Nothing here touches a network: the transport, the resolver and the staging are
 * in memory, and the fetch runs on the test's own dispatcher. Fixtures are fictional (`example.invalid`, the
 * documentation address `203.0.113.0/24`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MaterializeReferenceTest {

    // ---- fixtures ----

    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    private val pdf = ascii("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n")
    private val revisedPdf = ascii("%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Rev 2 >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n")

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val pump = AssetId("a1")
    private val heater = AssetId("a2")
    private val manualUri = "https://manuals.example.invalid/pool-pump/manual.pdf?lang=en"

    private fun reference(
        id: String = "ref-1",
        assetId: AssetId = pump,
        uri: String = manualUri,
        kind: ReferenceKind = ReferenceKind.WEB_URL,
        name: String = "Example Pool Pump manual",
        role: DocumentRole? = null,
    ) = AssetReference(
        id = ReferenceId(id), owner = ReferenceOwner.OfAsset(assetId), kind = kind, uri = uri, displayName = name,
        description = "Installation and care", scheme = uri.substringBefore(':').lowercase(),
        createdAt = 1L, updatedAt = 1L, role = role,
    )

    private fun review(
        name: String = "  Pool pump manual  ",
        kind: AttachmentKind = AttachmentKind.MANUAL,
        role: DocumentRole? = DocumentRole.USER_MANUAL,
        notes: String = " keep dry ",
    ) = MaterializeReview(name, kind, role, notes)

    // ---- the rig ----

    private val assets = InMemoryAssetRepository()
    private val events = InMemoryEventRepository()
    private val rows = InMemoryAttachmentRepository()
    private val reads = WatchedAttachments(rows)
    private val references = RecordingReferenceRepository()
    private val uow = FakeUnitOfWork(assets, events, rows, references)
    private val storage = FakeAttachmentStorage()
    private val store get() = storage.store
    private var now = 7_000L
    private val clock = Clock { now }
    private var seq = 0
    private val ids = IdGenerator { "att-${++seq}" }
    private val transport = FakeDocumentTransport()
    private val resolver = FakeHostResolver.of(
        "manuals.example.invalid" to listOf("203.0.113.10"),
        "cdn.example.invalid" to listOf("203.0.113.11"),
        "intranet.example.invalid" to listOf("203.0.113.12", "192.168.1.5"),
    )
    private val staging = FakeStaging()
    private var permission = true

    /** [writes] is the port `AddAttachment` writes through; the duplicate check always reads [reads]. */
    private fun materializer(writes: AttachmentRepository = rows) = MaterializeReference(
        references = references,
        attachments = reads,
        storage = storage,
        policy = LinkLaunchPolicy(),
        hops = HopPolicy(resolver),
        fetch = FetchDocument(transport, HopPolicy(resolver), staging, FetchLimits(), EmptyCoroutineContext),
        addAttachment = AddAttachment(
            writes, assets, events, InMemorySupplyItemRepository(),
            InMemoryInstalledComponentRepository(InMemorySupplyItemRepository()), storage, uow, ids, clock,
        ),
        networkPermissionGranted = { permission },
        clock = clock,
    )

    private val materialize = materializer()

    private suspend fun seed(vararg refs: AssetReference = arrayOf(reference())): List<AssetReference> {
        assets.upsert(Asset(id = pump, name = "Example Pool Pump", createdAt = 1L, updatedAt = 1L))
        assets.upsert(Asset(id = heater, name = "Sample Water Heater", createdAt = 1L, updatedAt = 1L))
        refs.forEach { references.upsert(it) }
        return refs.toList()
    }

    private fun handAdded(
        id: String,
        assetId: AssetId,
        name: String,
        bytes: ByteArray = pdf,
        createdAt: Long = 2_000L,
        mode: AttachmentMode = AttachmentMode.MANAGED,
        size: Long = bytes.size.toLong(),
    ) = Attachment(
        id = AttachmentId(id), owner = AttachmentOwner.OfAsset(assetId), kind = AttachmentKind.MANUAL, mode = mode,
        displayName = name, mimeType = "application/pdf", sizeBytes = size, sha256 = sha256(bytes),
        storageLocator = "assets/${assetId.value}/$id.pdf", capturedOn = null, createdAt = createdAt,
        updatedAt = createdAt,
    )

    private suspend fun ready(assetId: AssetId = pump, referenceId: String = "ref-1"): Prepared.Ready =
        assertIs<Prepared.Ready>(materialize.prepare(assetId, ReferenceId(referenceId)))

    /** The network was never asked: no GET, no lookup, no staging file. */
    private fun assertNoNetwork() {
        assertEquals(emptyList(), transport.requests, "the transport was called")
        assertEquals(emptyList(), resolver.asked, "the resolver was called")
        assertTrue(staging.files.isEmpty(), "a staging file was made")
    }

    /** R85-1 and C15(b): every reference exactly as seeded, no attachment row, no bytes, no commit, no staging left. */
    private fun assertNothingWritten(seeded: List<AssetReference>) {
        assertEquals(seeded.associateBy { it.id.value }, references.rows.toMap())
        assertEquals(seeded.size, references.upserts, "only the seed wrote a reference")
        assertEquals(0, references.deletes)
        assertTrue(rows.rows.isEmpty(), "an attachment row was written")
        assertTrue(store.files.isEmpty(), "bytes were left in the store")
        assertEquals(0, uow.commits)
        assertTrue(staging.files.all { it.discarded }, "staging was left behind")
    }

    // ---- row 19: the pre-checks, in order; the network is never reached ----

    @Test
    fun noSuchReferenceAndAnotherAssetsReferenceAreNoSuchReference() = runTest {
        val seeded = seed()

        assertEquals(
            Prepared.Refused(MaterializeRefusal.NoSuchReference),
            materialize.prepare(pump, ReferenceId("ref-missing")),
        )
        assertEquals(Prepared.Refused(MaterializeRefusal.NoSuchReference), materialize.prepare(heater, ReferenceId("ref-1")))
        assertNoNetwork()
        assertNothingWritten(seeded)
    }

    @Test
    fun aNoteLinkHttpUserinfoAndLocalhostAreNotEligible() = runTest {
        val seeded = seed(
            reference("ref-note", kind = ReferenceKind.NOTE_LINK, uri = "joplin://x-callback-url/openNote?id=0123abcd"),
            reference("ref-http", uri = "http://manuals.example.invalid/pool-pump/manual.pdf"),
            reference("ref-user", uri = "https://owner@manuals.example.invalid/pool-pump/manual.pdf"),
            reference("ref-local", uri = "https://localhost/pool-pump/manual.pdf"),
            reference("ref-odd", uri = "https://manuals.example.invalid\\.cdn.example.invalid/manual.pdf"),
        )

        listOf("ref-note", "ref-http", "ref-user", "ref-local", "ref-odd").forEach { id ->
            assertEquals(
                Prepared.Refused(MaterializeRefusal.NotEligible),
                materialize.prepare(pump, ReferenceId(id)),
                id,
            )
        }
        assertNoNetwork()
        assertNothingWritten(seeded)
    }

    /**
     * Review m2: a restored or merged reference can carry what `AddReference` would have refused. Its name or URI
     * must fail the source shape rule here, before a byte is fetched, never at Save. Seeded straight into the
     * repository; each is served, so a missed refusal downloads and answers `Ready`.
     */
    @Test
    fun aNameOrUriTheSourceRuleRefusesIsNotEligible() = runTest {
        val longUri = "https://manuals.example.invalid/" + "p".repeat(2_049 - 36) + ".pdf"
        assertEquals(2_049, longUri.length)
        val seeded = seed(
            reference("ref-blank", uri = "https://manuals.example.invalid/pool-pump/blank.pdf", name = "   "),
            reference("ref-long-name", uri = "https://manuals.example.invalid/pool-pump/long.pdf", name = "n".repeat(201)),
            reference("ref-long-uri", uri = longUri),
        )
        seeded.forEach { transport.serve(it.uri, pdf, "application/pdf") }
        val cases = listOf("ref-blank", "ref-long-name", "ref-long-uri")

        // All three answered before any is judged, so a failure names every case that slipped through.
        val answers = cases.associateWith { materialize.prepare(pump, ReferenceId(it)) }

        assertEquals(cases.associateWith { Prepared.Refused(MaterializeRefusal.NotEligible) }, answers)
        assertNoNetwork()
        assertNothingWritten(seeded)
    }

    @Test
    fun noStoreAndALostStoreAreStoreRefusals() = runTest {
        val seeded = seed()

        storage.state = StoreState.NotConfigured
        assertEquals(
            Prepared.Refused(MaterializeRefusal.Store(AttachmentProblem.NoStore)),
            materialize.prepare(pump, ReferenceId("ref-1")),
        )
        storage.state = StoreState.AccessLost("Attachments")
        assertEquals(
            Prepared.Refused(MaterializeRefusal.Store(AttachmentProblem.StoreUnavailable)),
            materialize.prepare(pump, ReferenceId("ref-1")),
        )
        assertNoNetwork()
        assertNothingWritten(seeded)
    }

    @Test
    fun aDeniedPermissionIsNetworkDenied() = runTest {
        val seeded = seed()
        transport.serve(manualUri, pdf, "application/pdf")
        permission = false

        assertEquals(Prepared.Refused(MaterializeRefusal.NetworkDenied), materialize.prepare(pump, ReferenceId("ref-1")))
        assertNoNetwork()
        assertNothingWritten(seeded)
    }

    /** C13's order: the first failing step answers, whatever else is also wrong. */
    @Test
    fun thePreChecksAnswerInTheirOrder() = runTest {
        val seeded = seed(
            reference(),
            reference("ref-note", kind = ReferenceKind.NOTE_LINK, uri = "joplin://x-callback-url/openNote?id=0123abcd"),
        )
        storage.state = StoreState.NotConfigured
        permission = false

        assertEquals(Prepared.Refused(MaterializeRefusal.NoSuchReference), materialize.prepare(heater, ReferenceId("ref-note")))
        assertEquals(Prepared.Refused(MaterializeRefusal.NotEligible), materialize.prepare(pump, ReferenceId("ref-note")))
        assertEquals(
            Prepared.Refused(MaterializeRefusal.Store(AttachmentProblem.NoStore)),
            materialize.prepare(pump, ReferenceId("ref-1")),
        )
        storage.state = StoreState.Ready("Attachments", "com.example.provider")
        assertEquals(Prepared.Refused(MaterializeRefusal.NetworkDenied), materialize.prepare(pump, ReferenceId("ref-1")))
        assertNoNetwork()
        assertNothingWritten(seeded)
    }

    /** Step 5: a fetch refusal is `Fetch(problem)`, except the transport's denial, which is `NetworkDenied`. */
    @Test
    fun aFetchRefusalIsFetchAndATransportDenialIsNetworkDenied() = runTest {
        val seeded = seed(
            reference(),
            reference("ref-lan", uri = "https://intranet.example.invalid/pool-pump/manual.pdf"),
            reference("ref-gone", uri = "https://manuals.example.invalid/pool-pump/gone.pdf"),
        )
        transport.fail(manualUri, TransportFailure.Kind.DENIED)
        transport.status("https://manuals.example.invalid/pool-pump/gone.pdf", 404)

        assertEquals(Prepared.Refused(MaterializeRefusal.NetworkDenied), materialize.prepare(pump, ReferenceId("ref-1")))
        assertEquals(
            Prepared.Refused(MaterializeRefusal.Fetch(FetchProblem.LocalAddress)),
            materialize.prepare(pump, ReferenceId("ref-lan")),
        )
        assertEquals(
            Prepared.Refused(MaterializeRefusal.Fetch(FetchProblem.ServerError(404))),
            materialize.prepare(pump, ReferenceId("ref-gone")),
        )
        assertNothingWritten(seeded)
    }

    // ---- row 20: success ----

    @Test
    fun oneAttachmentOnTheSameAssetWithTheSnapshot() = runTest {
        val (ref) = seed()
        // The clock moves while the body is served: `retrievedAt` is read after the fetch, never before it.
        transport.route(manualUri) {
            now = 7_500L
            FakeDocumentTransport.Served(200, contentType = "application/octet-stream", contentLength = pdf.size.toLong(), body = FakeBody(pdf))
        }

        val ready = ready()
        assertEquals(pump, ready.assetId)
        assertEquals(
            SourceSnapshot(
                manualUri, "Example Pool Pump manual", "Installation and care", "manuals.example.invalid", role = null,
            ),
            ready.snapshot,
        )
        assertEquals(7_500L, ready.retrievedAt)
        assertEquals("application/pdf", ready.fetched.mimeType)   // sniffed, never the declared octet-stream
        // The reference is renamed while the review is open: the snapshot keeps the name it had at prepare.
        references.rows[ref.id.value] = ref.copy(displayName = "Renamed manual")
        now = 9_000L

        val row = assertIs<AttachmentResult.Ok<Attachment>>(materialize.commit(ready, review())).value

        val expected = Attachment(
            id = AttachmentId("att-1"), owner = AttachmentOwner.OfAsset(pump), kind = AttachmentKind.MANUAL,
            displayName = "Pool pump manual", mimeType = "application/pdf", sizeBytes = pdf.size.toLong(),
            sha256 = sha256(pdf), storageLocator = "assets/a1/att-1.pdf", capturedOn = null, notes = "keep dry",
            createdAt = 9_000L, updatedAt = 9_000L, role = DocumentRole.USER_MANUAL,
            source = AttachmentSource(uri = manualUri, resolvedUri = null, retrievedAt = 7_500L, name = "Example Pool Pump manual"),
        )
        assertEquals(expected, row)
        assertEquals(listOf(expected), rows.forAsset(pump))
        assertContentEquals(pdf, store.files.getValue("assets/a1/att-1.pdf"))
    }

    /**
     * #91 row 30 (C16, R91-2): `prepare` snapshots the reference's role exactly — each of the three, and none — so the
     * review can start from it. Nothing else decides it: the name says "manual" on every one of them.
     */
    @Test
    fun theSnapshotCarriesTheReferencesRole() = runTest {
        val roles = listOf<DocumentRole?>(null) + DocumentRole.entries
        val refs = roles.mapIndexed { i, role ->
            reference("ref-$i", uri = "https://manuals.example.invalid/pool-pump/manual-$i.pdf", role = role)
        }
        seed(*refs.toTypedArray())
        refs.forEach { transport.serve(it.uri, pdf, "application/pdf") }

        for ((i, role) in roles.withIndex()) {
            val ready = ready(referenceId = "ref-$i")
            // The role first: `SourceSnapshot.toString` names the host only, so a whole-value failure would not say why.
            assertEquals(role, ready.snapshot.role, "the snapshot of ref-$i")
            assertEquals(
                SourceSnapshot(
                    "https://manuals.example.invalid/pool-pump/manual-$i.pdf", "Example Pool Pump manual",
                    "Installation and care", "manuals.example.invalid", role = role,
                ),
                ready.snapshot,
            )
            materialize.discard(ready)
        }
    }

    /** The kind the sheet pre-fills is the shipped inference; the review's kind is what is stored. */
    @Test
    fun theInferredKindIsTheReviewsWhenTheOwnerKeepsIt() = runTest {
        seed()
        transport.serve(manualUri, pdf, "application/pdf")
        val ready = ready()

        val inferred = AttachmentKinds.inferFrom(ready.fetched.mimeType, fromCamera = false)

        val row = assertIs<AttachmentResult.Ok<Attachment>>(
            materialize.commit(ready, review(kind = inferred, role = null, notes = "")),
        ).value
        assertEquals(AttachmentKind.DOCUMENT, row.kind)
        assertNull(row.role)
    }

    /** R85-2, R85-14: the stored destination keeps scheme, host and the ordinary path — never a query, fragment or `;` parameter. */
    @Test
    fun theResolvedUriIsTheDestinationWithoutQueryOrFragment() = runTest {
        seed()
        val destination = "https://cdn.example.invalid/files;sid=AB12/pool-pump/manual.pdf;v=3?token=abc123&exp=99#p2"
        transport.redirect(manualUri, destination)
        transport.serve(destination, pdf, "application/pdf")

        val ready = ready()
        val row = assertIs<AttachmentResult.Ok<Attachment>>(materialize.commit(ready, review())).value

        assertEquals(
            AttachmentSource(
                uri = manualUri,                                                   // verbatim, query and all
                resolvedUri = "https://cdn.example.invalid/files/pool-pump/manual.pdf",
                retrievedAt = 7_000L,
                name = "Example Pool Pump manual",
            ),
            row.source,
        )
    }

    /** A redirect that moves only the query, or only a path parameter, did not move the destination. */
    @Test
    fun anUnmovedDestinationStoresNoResolvedUri() = runTest {
        seed(reference(), reference("ref-h", assetId = heater))
        transport.redirect(manualUri, "https://manuals.example.invalid/pool-pump/manual.pdf?lang=en&session=0123")
        transport.serve("https://manuals.example.invalid/pool-pump/manual.pdf?lang=en&session=0123", pdf, "application/pdf")

        val first = assertIs<AttachmentResult.Ok<Attachment>>(materialize.commit(ready(), review())).value
        assertNull(first.source?.resolvedUri)
        assertEquals(manualUri, first.source?.uri)

        transport.redirect(manualUri, "https://manuals.example.invalid/pool-pump/manual.pdf;jsessionid=AB12")
        transport.serve("https://manuals.example.invalid/pool-pump/manual.pdf;jsessionid=AB12", pdf, "application/pdf")
        val second = assertIs<AttachmentResult.Ok<Attachment>>(materialize.commit(ready(heater, "ref-h"), review())).value
        assertNull(second.source?.resolvedUri)
    }

    /** ≤ 2,048 characters is kept; one more and the destination is dropped, the save still succeeds. */
    @Test
    fun anOverlongDestinationIsDropped() = runTest {
        seed(reference(), reference("ref-h", assetId = heater))
        val stem = "https://cdn.example.invalid/"
        val fits = stem + "p".repeat(2_048 - stem.length - 4) + ".pdf"
        val over = stem + "p".repeat(2_049 - stem.length - 4) + ".pdf"
        assertEquals(2_048, fits.length)
        assertEquals(2_049, over.length)

        transport.redirect(manualUri, fits)
        transport.serve(fits, pdf, "application/pdf")
        val kept = assertIs<AttachmentResult.Ok<Attachment>>(materialize.commit(ready(), review())).value
        assertEquals(fits, kept.source?.resolvedUri)

        transport.redirect(manualUri, over)
        transport.serve(over, pdf, "application/pdf")
        val dropped = assertIs<AttachmentResult.Ok<Attachment>>(materialize.commit(ready(heater, "ref-h"), review())).value
        assertNull(dropped.source?.resolvedUri)
        assertEquals(manualUri, dropped.source?.uri)
    }

    @Test
    fun stagingIsDiscarded() = runTest {
        seed()
        transport.serve(manualUri, pdf, "application/pdf")
        val ready = ready()
        assertFalse(staging.files.single().discarded, "prepare hands the staging over, kept")

        assertIs<AttachmentResult.Ok<Attachment>>(materialize.commit(ready, review()))

        assertTrue(staging.files.single().discarded)
    }

    @Test
    fun theReferenceIsByteEqual() = runTest {
        val seeded = seed()
        transport.serve(manualUri, pdf, "application/pdf")

        assertIs<AttachmentResult.Ok<Attachment>>(materialize.commit(ready(), review()))

        assertEquals(seeded.associateBy { it.id.value }, references.rows.toMap())
        assertEquals(1, references.upserts)   // the seed's
        assertEquals(0, references.deletes)
    }

    @Test
    fun commitsIsOne() = runTest {
        seed()
        transport.serve(manualUri, pdf, "application/pdf")
        val ready = ready()
        assertEquals(0, uow.commits, "prepare writes nothing")

        assertIs<AttachmentResult.Ok<Attachment>>(materialize.commit(ready, review()))

        assertEquals(1, uow.commits)
        assertEquals(0, uow.rollbacks)
    }

    /** A blank name is the owner's to fix: the staging stays for the next Save, and nothing else happens. */
    @Test
    fun aBlankNameKeepsTheStaging() = runTest {
        seed()
        transport.serve(manualUri, pdf, "application/pdf")
        val ready = ready()

        assertEquals(AttachmentResult.Refused(AttachmentProblem.BlankName), materialize.commit(ready, review(name = "   ")))
        assertFalse(staging.files.single().discarded)
        assertTrue(rows.rows.isEmpty())
        assertTrue(store.files.isEmpty())
        assertEquals(0, uow.commits)

        assertIs<AttachmentResult.Ok<Attachment>>(materialize.commit(ready, review()))
        assertTrue(staging.files.single().discarded)
    }

    /** `discard` drops the staging and nothing else; twice is the same as once. */
    @Test
    fun discardDropsTheStagingAndWritesNothing() = runTest {
        val seeded = seed()
        transport.serve(manualUri, pdf, "application/pdf")
        val ready = ready()

        materialize.discard(ready)
        materialize.discard(ready)

        assertTrue(staging.files.single().discarded)
        assertNothingWritten(seeded)
    }

    /**
     * Review m1: a `Ready` is spent by its first commit or discard. A second commit, or one after discard, is a
     * caller's mistake: it throws, never reads a discarded file, and never adds a second row.
     */
    @Test
    fun aReadyIsSpentByItsCommitOrDiscard() = runTest {
        seed(reference(), reference("ref-h", assetId = heater))
        transport.serve(manualUri, pdf, "application/pdf")

        val committed = ready()
        val row = assertIs<AttachmentResult.Ok<Attachment>>(materialize.commit(committed, review())).value
        assertFailsWith<IllegalStateException> { materialize.commit(committed, review(name = "Second copy")) }
        assertEquals(mapOf(row.id.value to row), rows.rows.toMap())
        assertEquals(1, uow.commits)

        val dropped = ready(heater, "ref-h")
        materialize.discard(dropped)
        assertFailsWith<IllegalStateException> { materialize.commit(dropped, review()) }
        assertEquals(mapOf(row.id.value to row), rows.rows.toMap(), "nothing was added to the heater")
        assertEquals(setOf(row.storageLocator), store.files.keys)
        assertEquals(1, uow.commits)
        assertTrue(staging.files.all { it.discarded })
    }

    /** Review NOTE 1: a stray log or assertion message never carries the URI, its query or the redirect destination. */
    @Test
    fun readyPrintsNoUri() = runTest {
        seed()
        val destination = "https://cdn.example.invalid/files/manual.pdf?token=abc123"
        transport.redirect(manualUri, destination)
        transport.serve(destination, pdf, "application/pdf")

        val printed = ready().toString()

        listOf("https", "example.invalid", "lang=en", "token", "pool-pump", "Pool Pump").forEach {
            assertFalse(it in printed, "Ready.toString() carries <$it>: $printed")
        }
    }

    // ---- row 21: duplicates, per asset only (R85-6) ----

    @Test
    fun identicalBytesOnTheAssetAreAlreadyHave() = runTest {
        val seeded = seed()
        val existing = listOf(
            handAdded("att-h2", pump, "Pump manual scan.pdf", createdAt = 3_000L),
            handAdded("att-h9", pump, "Older copy.pdf", createdAt = 2_000L),
            handAdded("att-h1", pump, "Linked copy.pdf", createdAt = 2_000L, mode = AttachmentMode.REFERENCE),
            handAdded("att-h0", pump, "Same hash, other size.pdf", createdAt = 1_000L, size = pdf.size + 1L),
        )
        existing.forEach { rows.upsert(it) }
        transport.serve(manualUri, pdf, "application/pdf")

        // The earliest by createdAt, then id, in any mode; a digest match of another size is not the same bytes.
        assertEquals(
            Prepared.Refused(MaterializeRefusal.AlreadyHave("Linked copy.pdf", AttachmentId("att-h1"))),
            materialize.prepare(pump, ReferenceId("ref-1")),
        )
        assertTrue(staging.files.single().discarded)
        assertTrue(store.files.isEmpty())
        assertEquals(existing.associateBy { it.id.value }, rows.rows.toMap())   // never retrofitted with a source
        assertEquals(0, uow.commits)
        assertEquals(seeded.associateBy { it.id.value }, references.rows.toMap())
    }

    /**
     * #92 row 22 (C17): the refusal names the earliest same-bytes row by its id — the one R85-6 already picks by
     * createdAt, then id — so a retried call over the API is recognisably already done.
     */
    @Test
    fun alreadyHaveNamesTheEarliestRowsId() = runTest {
        seed()
        listOf(
            handAdded("att-late", pump, "Later copy.pdf", createdAt = 9_000L),
            handAdded("att-early", pump, "Earlier copy.pdf", createdAt = 1_500L),
            handAdded("att-middle", pump, "Middle copy.pdf", createdAt = 4_000L),
        ).forEach { rows.upsert(it) }
        transport.serve(manualUri, pdf, "application/pdf")

        val refused = assertIs<Prepared.Refused>(materialize.prepare(pump, ReferenceId("ref-1")))
        val why = assertIs<MaterializeRefusal.AlreadyHave>(refused.why)
        assertEquals(AttachmentId("att-early"), why.attachmentId)
        assertEquals("Earlier copy.pdf", why.name)
        assertTrue(staging.files.single().discarded)
    }

    /** Always fetch: changed bytes are a second attachment; the same bytes again are refused. */
    @Test
    fun changedBytesAreASecondAttachment() = runTest {
        seed()
        transport.serve(manualUri, pdf, "application/pdf")
        val first = assertIs<AttachmentResult.Ok<Attachment>>(materialize.commit(ready(), review())).value

        transport.serve(manualUri, revisedPdf, "application/pdf")
        val second = assertIs<AttachmentResult.Ok<Attachment>>(materialize.commit(ready(), review(name = "Revised manual"))).value

        assertEquals(sha256(revisedPdf), second.sha256)
        assertEquals(listOf(first, second).sortedBy { it.id.value }, rows.forAsset(pump).sortedBy { it.id.value })
        assertEquals(2, uow.commits)
        assertEquals(
            Prepared.Refused(MaterializeRefusal.AlreadyHave("Revised manual", second.id)),
            materialize.prepare(pump, ReferenceId("ref-1")),
        )
        assertTrue(staging.files.all { it.discarded })
    }

    /** X4: no cross-asset duplicate check — the same link on two assets gives each its own document. */
    @Test
    fun theSameBytesOnAnotherAssetAreAllowed() = runTest {
        seed(reference(), reference("ref-h", assetId = heater))
        transport.serve(manualUri, pdf, "application/pdf")

        val onPump = assertIs<AttachmentResult.Ok<Attachment>>(materialize.commit(ready(pump, "ref-1"), review())).value
        val onHeater = assertIs<AttachmentResult.Ok<Attachment>>(materialize.commit(ready(heater, "ref-h"), review())).value

        assertEquals(AttachmentOwner.OfAsset(pump), onPump.owner)
        assertEquals(AttachmentOwner.OfAsset(heater), onHeater.owner)
        assertEquals(onPump.sha256, onHeater.sha256)
        assertEquals(listOf(onPump), rows.forAsset(pump))
        assertEquals(listOf(onHeater), rows.forAsset(heater))
        assertEquals(2, uow.commits)
    }

    /** Review m2: the duplicate check suspends; a cancel there discards the staging and propagates. */
    @Test
    fun cancellingDuringTheDuplicateCheckDiscards() = runTest {
        val seeded = seed()
        transport.serve(manualUri, pdf, "application/pdf")
        reads.gate = CompletableDeferred()

        val job = launch { materialize.prepare(pump, ReferenceId("ref-1")) }
        runCurrent()
        assertEquals(1, reads.waiting, "prepare never reached the duplicate check")
        assertFalse(staging.files.single().discarded)

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(staging.files.single().discarded)
        assertNothingWritten(seeded)
    }

    /** m2's other half: a throw in the duplicate check discards too, and is rethrown as itself. */
    @Test
    fun aFailingDuplicateCheckDiscardsAndRethrows() = runTest {
        val seeded = seed()
        transport.serve(manualUri, pdf, "application/pdf")
        reads.failure = RiggedFailure("rigged forAsset failure")

        assertFailsWith<RiggedFailure> { materialize.prepare(pump, ReferenceId("ref-1")) }

        assertTrue(staging.files.single().discarded)
        assertNothingWritten(seeded)
    }

    // ---- row 22: failures inside the one write ----

    @Test
    fun aPutFailureWritesNothingAndDiscards() = runTest {
        val seeded = seed()
        transport.serve(manualUri, pdf, "application/pdf")
        val ready = ready()
        store.failOnPut = "assets/a1/att-1.pdf"

        assertFailsWith<StoreIoException> { materialize.commit(ready, review()) }

        assertNothingWritten(seeded)
        assertTrue(staging.files.single().discarded)
    }

    @Test
    fun aRowWriteFailureWritesNothingAndDiscards() = runTest {
        val seeded = seed()
        transport.serve(manualUri, pdf, "application/pdf")
        val ready = ready()
        rows.failOnUpsert = 1

        assertFailsWith<RiggedFailure> { materialize.commit(ready, review()) }

        assertNothingWritten(seeded)
        assertEquals(1, uow.rollbacks)
        assertTrue(staging.files.single().discarded)
    }

    /** The guard sits inside the write: a held asset refuses there, and the cleanup still runs. */
    @Test
    fun aTransferredOutAssetWritesNothingAndDiscards() = runTest {
        val seeded = seed()
        val install = BackupInstall()
        val guard = HeldWriteGuard(
            install.transfers, install.events, install.definitions, install.profiles, install.groups,
            install.schedules, install.serviceCases, install.links, install.installedComponents,
        )
        val guarded = materializer(writes = guard.attachments(rows))
        transport.serve(manualUri, pdf, "application/pdf")
        val ready = assertIs<Prepared.Ready>(guarded.prepare(pump, ReferenceId("ref-1")))
        install.transfers.append(transferOf("out-1", assetId = pump.value, nameSnapshot = "Example Pool Pump"))

        val refused = assertFailsWith<AssetTransferredOut> { guarded.commit(ready, review()) }

        assertEquals(pump, refused.assetId)
        assertNothingWritten(seeded)
        assertTrue(staging.files.single().discarded)
    }

    // ---- row 23: independence (R85-1), through the shipped use cases ----

    @Test
    fun removingTheReferenceKeepsTheSourcedAttachment() = runTest {
        val (ref) = seed()
        transport.serve(manualUri, pdf, "application/pdf")
        val row = assertIs<AttachmentResult.Ok<Attachment>>(materialize.commit(ready(), review())).value

        assertEquals(ReferenceResult.Ok(Unit), RemoveReference(references, uow).run(ref.id))

        assertTrue(references.rows.isEmpty())
        assertEquals(row, rows.rows[row.id.value])
        assertEquals(manualUri, rows.rows.getValue(row.id.value).source?.uri)
        assertContentEquals(pdf, store.files.getValue(row.storageLocator))
    }

    @Test
    fun deletingTheAttachmentKeepsTheReference() = runTest {
        val seeded = seed()
        transport.serve(manualUri, pdf, "application/pdf")
        val row = assertIs<AttachmentResult.Ok<Attachment>>(materialize.commit(ready(), review())).value

        DeleteAttachment(rows, storage, uow).run(row.id)

        assertTrue(rows.rows.isEmpty())
        assertFalse(store.exists(row.storageLocator))
        assertEquals(seeded.associateBy { it.id.value }, references.rows.toMap())
        assertEquals(1, references.upserts)
        assertEquals(0, references.deletes)
    }
}

/**
 * The read port the duplicate check uses. [gate] parks `forAsset` until completed, so a test can cancel
 * `prepare` exactly there (review m2); [failure] makes it throw instead. Everything else is the plain repository.
 */
private class WatchedAttachments(private val inner: InMemoryAttachmentRepository) : AttachmentRepository by inner {
    var gate: CompletableDeferred<Unit>? = null
    var failure: Throwable? = null
    var waiting = 0
        private set

    override suspend fun forAsset(assetId: AssetId): List<Attachment> {
        failure?.let { throw it }
        gate?.let {
            waiting += 1
            it.await()
        }
        return inner.forAsset(assetId)
    }
}
