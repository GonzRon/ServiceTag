package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.AttachmentDto
import com.loosecannon.servicetag.core.fetch.DocumentTransport
import com.loosecannon.servicetag.core.fetch.FetchDocument
import com.loosecannon.servicetag.core.fetch.FetchProblem
import com.loosecannon.servicetag.core.fetch.HopPolicy
import com.loosecannon.servicetag.core.fetch.HostResolver
import com.loosecannon.servicetag.core.fetch.TransportFailure
import com.loosecannon.servicetag.core.fetch.TransportResponse
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentProblem
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.AttachmentStore
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.StoreIoException
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.ports.StoredBytes
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.usecase.AddAttachment
import com.loosecannon.servicetag.core.usecase.MaterializeReference
import com.loosecannon.servicetag.core.usecase.MaterializeRefusal
import com.loosecannon.servicetag.core.usecase.MaterializeReview
import com.loosecannon.servicetag.core.usecase.SourceSnapshot
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.InMemoryAttachmentStore
import com.loosecannon.servicetag.ui.references.reviewPrefill
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TOKEN = "ABCD2345"
private const val URI = "https://manuals.example.invalid/water-heater/manual.pdf"
private const val HOST = "manuals.example.invalid"
private const val NAME = "Example Water Heater manual"
private const val DESCRIPTION = "Installation and care, from the maker"
private val PDF = "%PDF-1.7 Example Water Heater manual %%EOF".toByteArray()

/**
 * #92 (B2) — save as document over the API, `POST /v1/references/{id}/materialize` (C14–C17, R92-3; B2-pre's BC1–BC8),
 * over the production router, the Room-backed [FakeGraph] and a real [MaterializeReference] whose transport is this
 * class's [ScriptedTransport] — never a network. Rows 18–23b of the plan's matrix; the cancellation rows run over a
 * real loopback [LoopbackApiServer] on the JVM, because `stop()` is what they are about. "Staging" is the real
 * `CacheStagingArea` over the graph's temporary directory. Fixtures are fictional.
 */
class MaterializeRoutesTest {

    private val graph = FakeGraph()
    private val client = V1Client(graph, TOKEN)
    private val transport = ScriptedTransport()
    private var server: LoopbackApiServer? = null
    private val cleanups = CopyOnWriteArrayList<() -> Unit>()

    @After fun close() {
        cleanups.forEach { it() }
        server?.stop()
        graph.close()
    }

    // --- wiring -----------------------------------------------------------------------------------------------------

    private fun materializeOver(folder: AttachmentStorage): MaterializeReference {
        val hops = HopPolicy(HostResolver { listOf(byteArrayOf(203.toByte(), 0, 113, 10)) })
        val add = AddAttachment(graph.attachments, graph.assets, graph.events, folder, graph.uow, graph.ids, graph.clock)
        return MaterializeReference(
            graph.references, graph.attachments, folder, LinkLaunchPolicy(), hops,
            FetchDocument(transport, hops, graph.materializeStaging), add, { graph.networkGranted }, graph.clock,
        )
    }

    private fun router(
        folder: AttachmentStorage = graph.attachmentStorage,
        prefill: (SourceSnapshot, String) -> MaterializeReview = { snapshot, type -> reviewPrefill(snapshot, type) },
    ): ApiRouter = ApiRouter(
        ApiHandlers(
            graph.assets, graph.tags, graph.links, graph.definitions, graph.profiles,
            graph.events, graph.attachments, graph.categories, graph.transferRecords, graph.assetSuccessions,
            graph.createAsset, graph.updateAsset, graph.retireAsset, graph.archiveAsset,
            graph.saveDefinition, graph.archiveDefinition, graph.saveProfile, graph.archiveProfile,
            graph.logEvent, graph.updateEvent, graph.deleteEvent, graph.importBackupMerge,
            maintenanceHandlersFor(graph),
            referenceHandlersFor(graph),
            seasonHealthHandlersFor(graph),
            warrantyHandlersFor(graph),
            serviceCaseHandlersFor(graph),
            loanHandlersFor(graph),
            AttachmentHandlers(
                attachments = graph.attachments, assets = graph.assets, storage = folder,
                updateAttachment = graph.updateAttachment, installation = graph.installationIdentity,
                transfers = graph.transferRecords,
                addAttachment = AddAttachment(
                    graph.attachments, graph.assets, graph.events, folder, graph.uow, graph.ids, graph.clock,
                ),
                staging = graph.materializeStaging, apiLongWrites = graph.apiLongWrites,
                references = graph.references, materializeReference = materializeOver(folder), prefill = prefill,
            ),
            replaceHandlersFor(graph),
            appVersion = "1.4.0",
            schemaVersion = AppGraph.SCHEMA_VERSION,
        ),
        TOKEN,
    )

    private fun post(router: ApiRouter, referenceId: String, body: String = "{}"): ApiResponse = router.handle(
        ApiRequest(
            "POST", "/v1/references/$referenceId/materialize",
            mapOf("host" to "127.0.0.1", "authorization" to "Bearer $TOKEN", "content-type" to "application/json"),
            body.toByteArray(),
        ),
    )

    // --- fixtures ---------------------------------------------------------------------------------------------------

    private class Seeded(val asset: String, val reference: String)

    /**
     * The asset and its one web reference, given [role] over the shipped `POST /v1/references` (#91) when one is named;
     * the reference's PDF served unless [serve] is false.
     */
    private fun seed(uri: String = URI, serve: Boolean = true, role: DocumentRole? = null): Seeded {
        val asset = client.asset("Example Water Heater")
        val roleKey = role?.let { ""","role":"${it.name}"""" }.orEmpty()
        val reference = client.ok(
            ReferenceResponse.serializer(), "POST", "/v1/references",
            """{"assetId":"$asset","uri":"$uri","displayName":"$NAME","description":"$DESCRIPTION"$roleKey}""",
            status = 201,
        ).reference.id
        assertEquals("the seeded reference's role", role, referenceRow(reference)?.role)
        if (serve) transport.serve(uri, PDF)
        return Seeded(asset, reference)
    }

    private fun referenceRow(id: String) = runBlocking { graph.references.get(ReferenceId(id)) }

    private fun rows(asset: String) = runBlocking { graph.attachments.forAsset(AssetId(asset)) }

    private fun stagingIsEmpty(): Boolean = graph.materializeStagingDir.listFiles().orEmpty().isEmpty()

    private fun attachmentOf(response: ApiResponse): AttachmentDto =
        ApiJson.decodeFromString(AttachmentResponse.serializer(), response.bodyText()).attachment

    private fun assertRefused(response: ApiResponse, status: Int, code: String) {
        assertEquals(response.bodyText(), status, response.status)
        assertEquals(response.bodyText(), code, response.errorDetail().code)
    }

    private fun transferOut(asset: String) = runBlocking {
        graph.transferRecords.append(
            TransferRecord(
                id = "out-1", assetId = AssetId(asset), kind = TransferKind.OUT, packId = "0f1e2d3c-pack",
                lineage = emptyList(), at = 1_758_960_000_000L, packSha256 = "ab".repeat(32),
                nameSnapshot = "Example Water Heater", note = "",
            ),
        )
    }

    private fun awaitTrue(what: String, seconds: Long = 10, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            if (System.nanoTime() > until) throw AssertionError("timed out waiting: $what")
            Thread.sleep(10)
        }
    }

    // --- the real listener, for the rows about stop() --------------------------------------------------------------

    private fun listen(router: ApiRouter): LoopbackApiServer =
        LoopbackApiServer(router, networkPermissionGranted = { true }, port = 0).also {
            assertEquals(StartOutcome.Bound, it.start())
            server = it
        }

    /** One materialize over a fresh connection; [answer] is the whole reply, or "" for a connection closed without one. */
    private class InFlight(val socket: Socket, val reader: Thread) {
        @Volatile var answer: String? = null
    }

    private fun sendOverSocket(port: Int, referenceId: String, body: String = "{}"): InFlight {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 30_000
        val bytes = body.toByteArray()
        socket.getOutputStream().apply {
            write(
                ("POST /v1/references/$referenceId/materialize HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Authorization: Bearer $TOKEN\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\n\r\n").toByteArray(),
            )
            write(bytes)
            flush()
        }
        lateinit var flight: InFlight
        val reader = Thread {
            flight.answer = try {
                socket.getInputStream().readBytes().decodeToString()
            } catch (e: IOException) {
                ""
            }
        }.apply { isDaemon = true }
        flight = InFlight(socket, reader)
        reader.start()
        cleanups += { runCatching { socket.close() } }
        return flight
    }

    /** The handler let go of the process-wide lock and the staging directory is empty: the request has unwound. */
    private fun awaitUnwound() = awaitTrue("the materialize to unwind (the lock free, staging empty)") {
        !graph.apiLongWrites.isLocked && stagingIsEmpty()
    }

    // --- row 18: C14 the happy path ---------------------------------------------------------------------------------

    @Test fun aWebReferenceBecomesOneSourcedRow() {
        val seeded = seed()
        val before = referenceRow(seeded.reference)
        val commits = graph.commits

        val response = post(router(), seeded.reference)

        assertEquals(response.bodyText(), 201, response.status)
        val row = attachmentOf(response)
        assertEquals(seeded.asset, row.assetId)
        assertEquals(URI, row.sourceUri)
        assertEquals(NAME, row.sourceName)
        assertTrue((row.sourceRetrievedAt ?: 0L) > 0L)
        assertEquals(InMemoryAttachmentStore.sha256Hex(PDF), row.sha256)
        assertEquals(listOf(row.id), rows(seeded.asset).map { it.id.value })
        assertTrue(PDF.contentEquals(graph.attachmentStorage.store.files.getValue(row.storageLocator)))
        assertEquals("the reference was written", before, referenceRow(seeded.reference))
        assertEquals(commits + 1, graph.commits)
        assertEquals(listOf(URI), transport.requests)
        assertTrue(stagingIsEmpty())
    }

    // --- row 19: C14 the designation --------------------------------------------------------------------------------

    @Test fun absentFieldsTakeThePrefill() {
        val seeded = seed()
        val row = attachmentOf(post(router(), seeded.reference, "{}").also { assertEquals(201, it.status) })
        assertEquals(NAME, row.displayName)
        assertEquals(AttachmentKind.DOCUMENT.name, row.kind)
        assertEquals(null, row.role)
        assertEquals(DESCRIPTION, row.notes)
    }

    @Test fun givenFieldsWin() {
        val seeded = seed()
        val body = """{"displayName":"Water heater manual (2025)","kind":"MANUAL","role":"USER_MANUAL",""" +
            """"notes":"Kept for the annual flush"}"""
        val row = attachmentOf(post(router(), seeded.reference, body).also { assertEquals(201, it.status) })
        assertEquals("Water heater manual (2025)", row.displayName)
        assertEquals(AttachmentKind.MANUAL.name, row.kind)
        assertEquals(DocumentRole.USER_MANUAL.name, row.role)
        assertEquals("Kept for the annual flush", row.notes)
    }

    // --- #91 row 32 (C17, R91-2): the role is three-state; the other keys keep "absent or null = the prefill" -------

    @Test fun anAbsentRoleCopiesTheSourceRole() {
        val seeded = seed(role = DocumentRole.SERVICE_MANUAL)
        val row = attachmentOf(post(router(), seeded.reference, "{}").also { assertEquals(it.bodyText(), 201, it.status) })
        assertEquals(DocumentRole.SERVICE_MANUAL.name, row.role)
        assertEquals("the kind is the proven type's, never the role's (R91-9)", AttachmentKind.DOCUMENT.name, row.kind)
        assertEquals(NAME, row.displayName)
        assertEquals(DESCRIPTION, row.notes)
    }

    @Test fun aGivenRoleWins() {
        val seeded = seed(role = DocumentRole.SERVICE_MANUAL)
        val response = post(router(), seeded.reference, """{"role":"PURCHASE_INVOICE_OR_RECEIPT"}""")
        val row = attachmentOf(response.also { assertEquals(it.bodyText(), 201, it.status) })
        assertEquals(DocumentRole.PURCHASE_INVOICE_OR_RECEIPT.name, row.role)
        assertEquals(AttachmentKind.DOCUMENT.name, row.kind)
    }

    @Test fun anExplicitNullRoleIsNoRole() {
        val seeded = seed(role = DocumentRole.SERVICE_MANUAL)
        val row = attachmentOf(
            post(router(), seeded.reference, """{"role":null}""").also { assertEquals(it.bodyText(), 201, it.status) },
        )
        assertEquals("an explicit null is no role, never the source's", null, row.role)
        assertEquals(NAME, row.displayName)
        assertEquals(AttachmentKind.DOCUMENT.name, row.kind)
        assertEquals(DESCRIPTION, row.notes)
        assertEquals("the source keeps its role", DocumentRole.SERVICE_MANUAL, referenceRow(seeded.reference)?.role)
    }

    @Test fun aNullNameStillTakesThePrefill() {
        val seeded = seed(role = DocumentRole.USER_MANUAL)
        val body = """{"displayName":null,"kind":null,"notes":null}"""
        val row = attachmentOf(post(router(), seeded.reference, body).also { assertEquals(it.bodyText(), 201, it.status) })
        assertEquals(NAME, row.displayName)
        assertEquals(AttachmentKind.DOCUMENT.name, row.kind)
        assertEquals(DESCRIPTION, row.notes)
        assertEquals("no role key: the source's", DocumentRole.USER_MANUAL.name, row.role)
    }

    /**
     * The presence read goes through the shipped 400 path (B2a's re-review R-1): a key with no value, and a missing
     * comma the lenient typed decoder takes but the element parser refuses, are each the 400 — never a 500, never a
     * materialized row.
     */
    @Test fun aMalformedBodyIs400AndNothingIsMaterialized() {
        val seeded = seed(role = DocumentRole.SERVICE_MANUAL)
        val commits = graph.commits
        for (body in listOf("""{"role":}""", """{"displayName":"a" "role":"USER_MANUAL"}""")) {
            assertRefused(post(router(), seeded.reference, body), 400, "bad_request")
        }
        assertEquals(emptyList<String>(), transport.requests)
        assertTrue(rows(seeded.asset).isEmpty())
        assertTrue(stagingIsEmpty())
        assertEquals(commits, graph.commits)
    }

    // --- row 20 and BC1: no fetch on a caller's mistake, and no URL from the wire ----------------------------------

    @Test fun aBlankGivenNameIs422WithNoTransportCall() {
        val seeded = seed()
        val response = post(router(), seeded.reference, """{"displayName":"   "}""")
        assertRefused(response, 422, "ATTACHMENT_NAME_REQUIRED")
        assertEquals("displayName", response.errorDetail().field)
        assertEquals(emptyList<String>(), transport.requests)
        assertTrue(rows(seeded.asset).isEmpty())
        assertTrue(stagingIsEmpty())
    }

    @Test fun aTransferredOutAssetIs409WithNoTransportCall() {
        val seeded = seed()
        transferOut(seeded.asset)
        val response = post(router(), seeded.reference)
        assertRefused(response, 409, "asset_transferred_out")
        assertEquals(emptyList<String>(), transport.requests)
        assertTrue(rows(seeded.asset).isEmpty())
        assertTrue(stagingIsEmpty())
    }

    @Test fun aUrlOrUriKeyInTheBodyIs400WithNoTransportCall() {
        val seeded = seed()
        transport.serve("https://elsewhere.example.invalid/other.pdf", PDF)
        for (key in listOf("url", "uri")) {
            val response = post(router(), seeded.reference, """{"$key":"https://elsewhere.example.invalid/other.pdf"}""")
            assertRefused(response, 400, "bad_request")
        }
        assertEquals(emptyList<String>(), transport.requests)
        assertTrue(rows(seeded.asset).isEmpty())
    }

    /** BC4: the materialize POST stays in the 64 KiB pre-auth tier: its body is four review fields, never a file. */
    @Test fun theMaterializeBodyKeepsThe64KiBTier() {
        assertEquals(MAX_BODY_BYTES, router().bodyCapFor("POST", "/v1/references/r/materialize"))
    }

    @Test fun anUnknownReferenceIs404AndAnotherVerbIs405() {
        assertRefused(post(router(), "no-such-reference"), 404, "NO_SUCH_REFERENCE")
        val get = router().handle(
            ApiRequest(
                "GET", "/v1/references/r1/materialize",
                mapOf("host" to "127.0.0.1", "authorization" to "Bearer $TOKEN"), ByteArray(0),
            ),
        )
        assertRefused(get, 405, "method_not_allowed")
        assertEquals(emptyList<String>(), transport.requests)
    }

    // --- row 21: C17 the codes --------------------------------------------------------------------------------------

    @Test fun everyRefusalAndFetchProblemHasItsCode() {
        val fetchCodes = listOf(
            FetchProblem.NotHttps to "FETCH_NOT_HTTPS",
            FetchProblem.HasCredentials to "FETCH_HAS_CREDENTIALS",
            FetchProblem.LocalAddress to "FETCH_LOCAL_ADDRESS",
            FetchProblem.Unreachable to "FETCH_UNREACHABLE",
            FetchProblem.Interrupted to "FETCH_INTERRUPTED",
            FetchProblem.TimedOut to "FETCH_TIMED_OUT",
            FetchProblem.TooLarge to "FETCH_TOO_LARGE",
            FetchProblem.Empty to "FETCH_EMPTY",
            FetchProblem.NotADocument to "FETCH_NOT_A_DOCUMENT",
            FetchProblem.NeedsSignIn to "FETCH_NEEDS_SIGN_IN",
            FetchProblem.ServerError(503) to "FETCH_SERVER_ERROR",
            FetchProblem.RedirectRefused to "FETCH_REDIRECT_REFUSED",
        )
        assertEquals(12, fetchCodes.map { it.second }.toSet().size)
        for ((problem, code) in fetchCodes) {
            assertEquals(code, fetchProblemCode(problem))
            val failure = materializeRefusal(MaterializeRefusal.Fetch(problem))
            assertEquals("$problem", 502, failure.status)
            assertEquals(code, failure.code)
            assertEquals("the download was refused", failure.message)
            assertEquals(listOf(problem.toString()), failure.problems)
        }
        val serverError = materializeRefusal(MaterializeRefusal.Fetch(FetchProblem.ServerError(503)))
        assertEquals(listOf("ServerError(code=503)"), serverError.problems)

        fun expect(why: MaterializeRefusal, status: Int, code: String, problems: List<String>) {
            val failure = materializeRefusal(why)
            assertEquals("$why", status, failure.status)
            assertEquals("$why", code, failure.code)
            assertEquals("$why", problems, failure.problems)
        }
        expect(MaterializeRefusal.NoSuchReference, 404, "NO_SUCH_REFERENCE", emptyList())
        expect(MaterializeRefusal.NotEligible, 409, "REFERENCE_NOT_MATERIALIZABLE", listOf("NotEligible"))
        expect(
            MaterializeRefusal.Store(AttachmentProblem.NoStore), 409, "ATTACHMENT_STORE_NOT_CONFIGURED", listOf("NoStore"),
        )
        expect(
            MaterializeRefusal.Store(AttachmentProblem.StoreUnavailable), 409, "store_unavailable",
            listOf("StoreUnavailable"),
        )
        expect(MaterializeRefusal.NetworkDenied, 409, "NETWORK_DENIED", listOf("NetworkDenied"))
        expect(MaterializeRefusal.Fetch(FetchProblem.NetworkDenied), 409, "NETWORK_DENIED", listOf("NetworkDenied"))
        assertEquals("NETWORK_DENIED", fetchProblemCode(FetchProblem.NetworkDenied))
        val held = MaterializeRefusal.AlreadyHave("Example Water Heater manual (old)", AttachmentId("att-1"))
        expect(held, 409, "ATTACHMENT_ALREADY_HELD", listOf("AlreadyHave(attachmentId=att-1)"))
        assertFalse(materializeRefusal(held).problems.joinToString().contains("Example Water Heater"))
    }

    @Test fun theRouteAnswersEachRefusalWithItsCode() {
        val notHttps = seed(uri = "http://manuals.example.invalid/water-heater/manual.pdf")
        assertRefused(post(router(), notHttps.reference), 409, "REFERENCE_NOT_MATERIALIZABLE")
        assertEquals(emptyList<String>(), transport.requests)

        val timedOutUri = "https://slow.example.invalid/manual.pdf"
        val timedOut = seed(uri = timedOutUri, serve = false)
        transport.fail(timedOutUri, TransportFailure.Kind.TIMED_OUT)
        val slow = post(router(), timedOut.reference)
        assertRefused(slow, 502, "FETCH_TIMED_OUT")
        assertEquals(listOf("TimedOut"), slow.errorDetail().problems)

        val brokenUri = "https://broken.example.invalid/manual.pdf"
        val broken = seed(uri = brokenUri, serve = false)
        transport.serve(brokenUri, "busy".toByteArray(), status = 503)
        val busy = post(router(), broken.reference)
        assertRefused(busy, 502, "FETCH_SERVER_ERROR")
        assertEquals(listOf("ServerError(code=503)"), busy.errorDetail().problems)
        assertFalse(busy.bodyText(), busy.bodyText().contains("broken.example.invalid"))

        val noFolder = seed(uri = "https://manuals.example.invalid/other.pdf")
        graph.attachmentStorage.state = StoreState.NotConfigured
        assertRefused(post(router(), noFolder.reference), 409, "ATTACHMENT_STORE_NOT_CONFIGURED")
        assertTrue(stagingIsEmpty())
    }

    // --- row 22: C17 the id -----------------------------------------------------------------------------------------

    @Test fun theSameBytesAre409NamingTheRow() {
        val seeded = seed()
        val first = attachmentOf(post(router(), seeded.reference).also { assertEquals(201, it.status) })

        val again = post(router(), seeded.reference)

        assertRefused(again, 409, "ATTACHMENT_ALREADY_HELD")
        assertEquals(listOf("AlreadyHave(attachmentId=${first.id})"), again.errorDetail().problems)
        assertFalse("the answer named the row", again.bodyText().contains(first.displayName))
        assertFalse(again.bodyText().contains(URI))
        assertFalse(again.bodyText().contains(HOST))
        assertEquals(1, rows(seeded.asset).size)
        assertTrue(stagingIsEmpty())
    }

    // --- row 23: C16 leaving stops the download ---------------------------------------------------------------------

    @Test fun stoppingTheListenerCancelsTheDownloadAndLeavesNoStaging() {
        val seeded = seed(serve = false)
        val body = transport.hang(URI)
        cleanups += { body.close() }
        val listener = listen(router())
        val flight = sendOverSocket(listener.boundPort, seeded.reference)
        assertTrue("the download never started", body.blocked.await(10, TimeUnit.SECONDS))
        assertFalse(stagingIsEmpty())

        listener.stop()

        awaitUnwound()
        assertTrue("the response was never closed", body.closed)
        assertTrue(rows(seeded.asset).isEmpty())
        flight.reader.join(10_000)
        assertEquals("", flight.answer)
    }

    // --- row 23b: C14, C16, R87-4 — the commit is durable; the hand-off --------------------------------------------

    /** A store whose `put` writes its bytes and then parks until the test releases it, as a slow folder would. */
    private class GatedStore(val inner: InMemoryAttachmentStore = InMemoryAttachmentStore()) : AttachmentStore {
        val puts = AtomicInteger()
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)

        override suspend fun put(locator: String, source: ByteSource): StoredBytes {
            puts.incrementAndGet()
            val stored = inner.put(locator, source)
            parked.countDown()
            release.await(30, TimeUnit.SECONDS)
            return stored
        }

        override suspend fun open(locator: String) = inner.open(locator)
        override suspend fun exists(locator: String) = inner.exists(locator)
        override suspend fun delete(locator: String) = inner.delete(locator)
    }

    private fun folderOver(store: AttachmentStore) = object : AttachmentStorage {
        override fun state(): StoreState = StoreState.Ready("Attachments", "com.example.provider")
        override fun store(): AttachmentStore = store
    }

    private fun assertOneDurableRow(seeded: Seeded, gated: GatedStore, reference: Any?) {
        val row = rows(seeded.asset).single()
        assertTrue(PDF.contentEquals(gated.inner.files.getValue(row.storageLocator)))
        assertEquals("orphaned bytes in the store", setOf(row.storageLocator), gated.inner.files.keys)
        assertTrue(stagingIsEmpty())
        assertEquals(reference, referenceRow(seeded.reference))
    }

    @Test fun stopDuringTheCommitLeavesOneDurableRowAndNoOrphan() {
        val seeded = seed()
        val reference = referenceRow(seeded.reference)
        val gated = GatedStore()
        cleanups += { gated.release.countDown() }
        val listener = listen(router(folder = folderOver(gated)))
        val flight = sendOverSocket(listener.boundPort, seeded.reference)
        assertTrue("the commit never reached put", gated.parked.await(10, TimeUnit.SECONDS))

        listener.stop()
        gated.release.countDown()

        awaitUnwound()
        assertOneDurableRow(seeded, gated, reference)
        assertEquals(1, gated.puts.get())
        flight.reader.join(10_000)
        assertEquals("", flight.answer)
    }

    @Test fun aClientDisconnectDuringTheCommitLeavesOneDurableRowAndNoOrphan() {
        val seeded = seed()
        val reference = referenceRow(seeded.reference)
        val gated = GatedStore()
        cleanups += { gated.release.countDown() }
        val listener = listen(router(folder = folderOver(gated)))
        val flight = sendOverSocket(listener.boundPort, seeded.reference)
        assertTrue("the commit never reached put", gated.parked.await(10, TimeUnit.SECONDS))

        flight.socket.close()
        gated.release.countDown()

        awaitUnwound()
        awaitTrue("the row to land") { rows(seeded.asset).isNotEmpty() }
        assertOneDurableRow(seeded, gated, reference)
    }

    /** BC8: a store failure answers the shipped 409 and names neither the locator nor the folder's authority. */
    @Test fun aCommitThatFailsLeavesNoRowAndNoBytes() {
        val seeded = seed()
        val reference = referenceRow(seeded.reference)
        val tried = CopyOnWriteArrayList<String>()
        val failing = object : AttachmentStore {
            val inner = InMemoryAttachmentStore()
            override suspend fun put(locator: String, source: ByteSource): StoredBytes {
                tried += locator
                throw StoreIoException("cannot create $locator in com.example.provider")
            }
            override suspend fun open(locator: String) = inner.open(locator)
            override suspend fun exists(locator: String) = inner.exists(locator)
            override suspend fun delete(locator: String) = inner.delete(locator)
        }

        val response = post(router(folder = folderOver(failing)), seeded.reference)

        assertRefused(response, 409, "store_unavailable")
        assertEquals(1, tried.size)
        assertFalse(response.bodyText(), response.bodyText().contains(tried.single()))
        assertFalse(response.bodyText(), response.bodyText().contains("com.example.provider"))
        assertTrue(rows(seeded.asset).isEmpty())
        assertTrue(failing.inner.files.isEmpty())
        assertTrue(stagingIsEmpty())
        assertEquals(reference, referenceRow(seeded.reference))
    }

    @Test fun aStopAfterReadyBeforeCommitWritesNothing() {
        val seeded = seed()
        val reference = referenceRow(seeded.reference)
        val reviewing = CountDownLatch(1)
        val release = CountDownLatch(1)
        cleanups += { release.countDown() }
        val listener = listen(
            router(
                prefill = { snapshot, type ->
                    reviewing.countDown()
                    release.await(30, TimeUnit.SECONDS)
                    reviewPrefill(snapshot, type)
                },
            ),
        )
        val flight = sendOverSocket(listener.boundPort, seeded.reference)
        assertTrue("prepare never returned Ready", reviewing.await(10, TimeUnit.SECONDS))
        assertFalse("nothing was staged", stagingIsEmpty())

        listener.stop()
        release.countDown()

        awaitUnwound()
        assertTrue(rows(seeded.asset).isEmpty())
        assertTrue(graph.attachmentStorage.store.files.isEmpty())
        assertEquals(reference, referenceRow(seeded.reference))
        flight.reader.join(10_000)
        assertEquals("", flight.answer)
    }

    @Test fun aBlankNameReachingCommitStillDiscardsStaging() {
        val seeded = seed()
        val router = router(prefill = { _, _ -> MaterializeReview(" ", AttachmentKind.DOCUMENT, null, "") })

        val response = post(router, seeded.reference)

        assertRefused(response, 422, "ATTACHMENT_NAME_REQUIRED")
        assertTrue("the unspent download kept its staging", stagingIsEmpty())
        assertTrue(rows(seeded.asset).isEmpty())
        assertTrue(graph.attachmentStorage.store.files.isEmpty())
    }
}

/**
 * The transport behind these rows: each URL answers what [serve], [fail] or [hang] gave it, and [requests] records
 * every GET, so a row can say "no transport call" as an empty list. A URL with no answer is `UNREACHABLE`.
 */
internal class ScriptedTransport : DocumentTransport {
    val requests: MutableList<String> = CopyOnWriteArrayList()
    private val routes = ConcurrentHashMap<String, () -> TransportResponse>()

    fun serve(url: String, bytes: ByteArray, contentType: String = "application/pdf", status: Int = 200) {
        routes[url] = { TransportResponse(status, null, contentType, bytes.size.toLong(), ByteArrayInputStream(bytes)) {} }
    }

    fun fail(url: String, kind: TransportFailure.Kind) {
        routes[url] = { throw TransportFailure(kind) }
    }

    /** [url] answers 200 with a PDF head and then a body that blocks until the response is closed. */
    fun hang(url: String): HangingBody {
        val body = HangingBody()
        routes[url] = { TransportResponse(200, null, "application/pdf", null, body) { body.close() } }
        return body
    }

    override suspend fun get(url: String): TransportResponse {
        requests += url
        val answer = routes[url] ?: throw TransportFailure(TransportFailure.Kind.UNREACHABLE)
        return answer()
    }
}

/** A body that yields a PDF head, then blocks on its next read until [close] — as a stalled server's would. */
internal class HangingBody : InputStream() {
    val blocked = CountDownLatch(1)
    private val release = CountDownLatch(1)
    @Volatile var closed = false
        private set
    private var headSent = false

    override fun read(): Int = throw UnsupportedOperationException("read in chunks")

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!headSent) {
            headSent = true
            val head = "%PDF-1.7 ".toByteArray()
            head.copyInto(b, off, 0, minOf(len, head.size))
            return minOf(len, head.size)
        }
        blocked.countDown()
        release.await(60, TimeUnit.SECONDS)
        throw IOException("the response was closed")
    }

    override fun close() {
        closed = true
        release.countDown()
    }
}
