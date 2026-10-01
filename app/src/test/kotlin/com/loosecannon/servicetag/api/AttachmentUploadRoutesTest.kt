package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.AttachmentDto
import com.loosecannon.servicetag.core.fetch.StagedReader
import com.loosecannon.servicetag.core.fetch.StagingArea
import com.loosecannon.servicetag.core.fetch.StagingFile
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.InMemoryAttachmentStore
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TOKEN = "ABCD2345"
private const val SHAPE = "/v1/assets/%s/attachments"

/**
 * #92 (B1b) — the upload, `POST /v1/assets/{id}/attachments` (C9–C13), over the production router and the Room-backed
 * [FakeGraph], in process. Rows 9, 10, 12–16 of the plan's matrix. The request's body is a [CountingStream], so a case
 * can say how much of it was read; "staging" is the real `CacheStagingArea` over the graph's temporary directory.
 * Fixtures are fictional.
 */
class AttachmentUploadRoutesTest {

    private val graph = FakeGraph()
    private val client = V1Client(graph, TOKEN)
    private val store: InMemoryAttachmentStore get() = graph.attachmentStorage.store
    private val payload = "Example Water Heater — installation manual, page 1 of 1".toByteArray()

    @After fun close() = graph.close()

    // --- helpers ----------------------------------------------------------------------------------------------------

    /** The upload's header: unpadded base64url of the metadata's UTF-8 JSON, keys as given. */
    private fun header(json: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    private fun meta(
        key: String = "op-1",
        name: String = "Example Water Heater manual",
        sha: String = sha(payload),
        kind: String? = null,
        role: String? = null,
        capturedOn: String? = null,
    ): String = header(
        buildString {
            append("""{"operationKey":"$key","displayName":"$name","sha256":"$sha"""")
            if (kind != null) append(""","kind":"$kind"""")
            if (role != null) append(""","role":"$role"""")
            if (capturedOn != null) append(""","capturedOn":"$capturedOn"""")
            append("}")
        },
    )

    private fun sha(bytes: ByteArray): String = InMemoryAttachmentStore.sha256Hex(bytes)

    /** A body stream that says how many of its bytes were read, and whether it was read to its end. */
    private class CountingStream(bytes: ByteArray) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        var read = 0L
            private set
        val atEnd: Boolean get() = inner.available() == 0

        override fun read(): Int = inner.read().also { if (it >= 0) read++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) read += it }
    }

    private class Sent(val response: ApiResponse, val body: CountingStream)

    private fun upload(
        assetId: String,
        bytes: ByteArray = payload,
        metadata: String? = meta(),
        type: String? = "application/pdf",
        token: String = TOKEN,
        router: ApiRouter = client.router(),
    ): Sent {
        val body = CountingStream(bytes)
        val headers = buildMap {
            put("host", "127.0.0.1")
            put("authorization", "Bearer $token")
            put("content-length", bytes.size.toString())
            if (type != null) put("content-type", type)
            if (metadata != null) put(UPLOAD_METADATA_HEADER, metadata)
        }
        return Sent(router.handle(ApiRequest("POST", SHAPE.format(assetId), headers, ByteArray(0), body)), body)
    }

    private fun Sent.row(): AttachmentDto =
        ApiJson.decodeFromString(AttachmentResponse.serializer(), response.bodyText()).attachment

    private fun stagingIsEmpty(): Boolean = graph.materializeStagingDir.listFiles().orEmpty().isEmpty()

    private fun rows(assetId: String) = runBlocking { graph.attachments.forAsset(AssetId(assetId)) }

    /** The production router with this suite's own attachment collaborator swapped in. */
    private fun routerWith(attachmentRoutes: AttachmentHandlers): ApiRouter = ApiRouter(
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
            attachmentRoutes,
            replaceHandlersFor(graph),
            supplyHandlersFor(graph),
            appVersion = "1.4.0",
            schemaVersion = AppGraph.SCHEMA_VERSION,
        ),
        TOKEN,
    )

    private fun handlers(
        staging: StagingArea = graph.materializeStaging,
        deadlineMillis: Long = UPLOAD_DEADLINE_MILLIS,
        nanoTime: () -> Long = System::nanoTime,
    ) = AttachmentHandlers(
        attachments = graph.attachments,
        assets = graph.assets,
        storage = graph.attachmentStorage,
        updateAttachment = graph.updateAttachment,
        installation = graph.installationIdentity,
        transfers = graph.transferRecords,
        addAttachment = graph.addAttachment,
        staging = staging,
        apiLongWrites = graph.apiLongWrites,
        uploadDeadlineMillis = deadlineMillis,
        nanoTime = nanoTime,
    )

    private fun assertRefused(sent: Sent, status: Int, code: String, field: String? = null) {
        assertEquals(sent.response.bodyText(), status, sent.response.status)
        assertEquals(code, sent.response.errorDetail().code)
        if (field != null) assertEquals(field, sent.response.errorDetail().field)
    }

    /** Row 12's four facts for a refusal: no bytes stored, staging empty, no write, the body read to its end. */
    private fun assertNothingWritten(sent: Sent, commitsBefore: Int) {
        assertTrue(store.files.toString(), store.files.isEmpty())
        assertTrue(stagingIsEmpty())
        assertEquals(commitsBefore, graph.commits)
        assertTrue("the body was not read to its end", sent.body.atEnd)
    }

    // --- row 9: C10, the token before any body byte -----------------------------------------------------------------

    @Test fun aWrongTokenUploadIs401WithZeroBodyBytesReadAndNoStaging() {
        val heater = client.asset("Example Water Heater")
        for (metadata in listOf(meta(), "!!! not even base64 !!!")) {
            val sent = upload(heater, metadata = metadata, token = "NOPENOPE")
            assertEquals(401, sent.response.status)
            assertEquals(0, sent.response.body.size)
            assertEquals("a body byte was read before the token", 0L, sent.body.read)
            assertTrue(stagingIsEmpty())
            assertTrue(store.files.isEmpty())
        }
        assertTrue(rows(heater).isEmpty())
    }

    // --- row 10: C12, the happy path --------------------------------------------------------------------------------

    @Test fun anUploadLandsOneRowWithTheStoresDigestAndSize() {
        val heater = client.asset("Example Water Heater")
        val before = graph.commits
        val sent = upload(heater, metadata = meta(capturedOn = " 2026-09-30 "))
        assertEquals(sent.response.bodyText(), 201, sent.response.status)
        val row = sent.row()
        val stored = rows(heater).single()
        assertEquals(stored.id.value, row.id)
        assertEquals(attachmentOperationId(graph.installationIdentity.id(), AssetId(heater), "op-1").value, row.id)
        assertEquals(sha(payload), row.sha256)
        assertEquals(payload.size.toLong(), row.sizeBytes)
        assertEquals("application/pdf", row.mimeType)
        assertEquals("DOCUMENT", row.kind)
        assertEquals("2026-09-30", row.capturedOn)
        assertNull(row.sourceUri)
        assertTrue(store.files[stored.storageLocator].contentEquals(payload))
        assertTrue("staging was left behind", stagingIsEmpty())
        assertEquals(1, graph.commits - before)
        assertTrue(sent.body.atEnd)
    }

    // --- row 12: C12, the refusals leave nothing --------------------------------------------------------------------

    @Test fun noFolderIs409BeforeStagingAndNoRow() {
        val heater = client.asset("Example Water Heater")
        val before = graph.commits
        graph.attachmentStorage.state = StoreState.NotConfigured
        upload(heater).also {
            assertRefused(it, 409, "ATTACHMENT_STORE_NOT_CONFIGURED")
            assertNothingWritten(it, before)
        }
        graph.attachmentStorage.state = StoreState.AccessLost("Attachments")
        upload(heater).also {
            assertRefused(it, 409, "store_unavailable")
            assertNothingWritten(it, before)
        }
        assertTrue(rows(heater).isEmpty())
    }

    @Test fun aTransferredOutAssetIs409BeforeStaging() {
        val heater = client.asset("Example Water Heater")
        runBlocking {
            graph.transferRecords.append(
                TransferRecord(
                    id = "out-1", assetId = AssetId(heater), kind = TransferKind.OUT, packId = "0f1e2d3c-pack",
                    lineage = emptyList(), at = 1_758_960_000_000L, packSha256 = "ab".repeat(32),
                    nameSnapshot = "Example Water Heater", note = "",
                ),
            )
        }
        val before = graph.commits
        val sent = upload(heater)
        assertRefused(sent, 409, "asset_transferred_out")
        assertNothingWritten(sent, before)
        assertTrue(rows(heater).isEmpty())
    }

    @Test fun anEmptyBodyIs422() {
        val heater = client.asset("Example Water Heater")
        val before = graph.commits
        val sent = upload(heater, bytes = ByteArray(0), metadata = meta(sha = sha(ByteArray(0))))
        assertRefused(sent, 422, "ATTACHMENT_EMPTY")
        assertNothingWritten(sent, before)
    }

    @Test fun aMalformedDigestIs422Invalid() {
        val heater = client.asset("Example Water Heater")
        val before = graph.commits
        for (bad in listOf("abc", sha(payload).uppercase(), sha(payload) + "0", "g".repeat(64))) {
            val sent = upload(heater, metadata = meta(sha = bad))
            assertRefused(sent, 422, "ATTACHMENT_SHA256_INVALID", "sha256")
            assertNothingWritten(sent, before)
        }
    }

    @Test fun aDigestMismatchIs422AndStoresNothing() {
        val heater = client.asset("Example Water Heater")
        val before = graph.commits
        val sent = upload(heater, metadata = meta(sha = sha("other bytes".toByteArray())))
        assertRefused(sent, 422, "ATTACHMENT_SHA256_MISMATCH", "sha256")
        assertNothingWritten(sent, before)
        assertTrue(rows(heater).isEmpty())
    }

    @Test fun aMissingOrBadHeaderIs400() {
        val heater = client.asset("Example Water Heater")
        val before = graph.commits
        val padded = Base64.getUrlEncoder().encodeToString("""{"operationKey":"k","displayName":"x","sha256":"${sha(payload)}"}""".toByteArray())
        val bad = listOf(
            null,
            "!!!",
            padded.takeIf { it.endsWith("=") } ?: "$padded=",
            header("not json"),
            header("""{"operationKey":"k","displayName":"x","sha256":"${sha(payload)}","source":"https://example.invalid/"}"""),
            header("""{"operationKey":"k","displayName":"x","sha256":"${sha(payload)}","kind":"BROCHURE"}"""),
            header("""{"displayName":"x","sha256":"${sha(payload)}"}"""),
            Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(0xc3.toByte(), 0x28)),
        )
        for (metadata in bad) {
            val sent = upload(heater, metadata = metadata)
            assertRefused(sent, 400, "bad_request")
            assertNothingWritten(sent, before)
        }
    }

    @Test fun anUnknownAssetIs404() {
        val before = graph.commits
        val sent = upload("no-such-asset")
        assertRefused(sent, 404, "no_such_asset")
        assertNothingWritten(sent, before)
    }

    @Test fun aBlankNameOrABadDateIs422BeforeStaging() {
        val heater = client.asset("Example Water Heater")
        val before = graph.commits
        upload(heater, metadata = meta(name = "   ")).also {
            assertRefused(it, 422, "ATTACHMENT_NAME_REQUIRED", "displayName")
            assertNothingWritten(it, before)
        }
        upload(heater, metadata = meta(capturedOn = "30/09/2026")).also {
            assertRefused(it, 422, "ATTACHMENT_BAD_DATE", "capturedOn")
            assertNothingWritten(it, before)
        }
    }

    // --- row 13: R92-5, a role at creation, never inferred ----------------------------------------------------------

    @Test fun aRoleGivenAtCreationIsStored() {
        val heater = client.asset("Example Water Heater")
        val sent = upload(heater, metadata = meta(role = "USER_MANUAL"))
        assertEquals(201, sent.response.status)
        assertEquals("USER_MANUAL", sent.row().role)
        assertEquals("USER_MANUAL", rows(heater).single().role?.name)
    }

    @Test fun noRoleIsInferred() {
        val heater = client.asset("Example Water Heater")
        val sent = upload(heater, metadata = meta(name = "manual.pdf", kind = "MANUAL"), type = "application/pdf")
        assertEquals(201, sent.response.status)
        assertEquals("MANUAL", sent.row().kind)
        assertNull(sent.row().role)
        assertNull(rows(heater).single().role)
    }

    // --- row 14: C13, R92-6, the operation key ----------------------------------------------------------------------

    @Test fun anExactReplayIs200TheSameRowAndWritesNothing() {
        val heater = client.asset("Example Water Heater")
        val first = upload(heater, metadata = meta(name = "  Example Water Heater manual "))
        assertEquals(201, first.response.status)
        val commits = graph.commits
        val files = store.files.mapValues { it.value.toList() }
        val deletes = store.deletes
        val replay = upload(heater, metadata = meta(name = "  Example Water Heater manual "))
        assertEquals(replay.response.bodyText(), 200, replay.response.status)
        assertEquals(first.row(), replay.row())
        assertEquals(commits, graph.commits)
        assertEquals(files, store.files.mapValues { it.value.toList() })
        assertEquals(deletes, store.deletes)
        assertTrue(stagingIsEmpty())
        assertTrue(replay.body.atEnd)
        assertEquals(1, rows(heater).size)
    }

    @Test fun aKeyReusedWithOtherBytesIs409() {
        val heater = client.asset("Example Water Heater")
        val id = upload(heater).row().id
        val commits = graph.commits
        val other = "a different page".toByteArray()
        val sent = upload(heater, bytes = other, metadata = meta(sha = sha(other)))
        assertRefused(sent, 409, "OPERATION_KEY_REUSED", "operationKey")
        assertEquals(listOf("OperationKeyReused(attachmentId=$id)"), sent.response.errorDetail().problems)
        assertEquals(commits, graph.commits)
        assertEquals(1, rows(heater).size)
        assertTrue(sent.body.atEnd)
    }

    @Test fun aKeyReusedWithOtherMetadataIs409() {
        val heater = client.asset("Example Water Heater")
        val id = upload(heater).row().id
        val commits = graph.commits
        for (metadata in listOf(meta(name = "Another name"), meta(kind = "MANUAL"), meta(role = "SERVICE_MANUAL"))) {
            val sent = upload(heater, metadata = metadata)
            assertRefused(sent, 409, "OPERATION_KEY_REUSED", "operationKey")
            assertEquals(listOf("OperationKeyReused(attachmentId=$id)"), sent.response.errorDetail().problems)
        }
        // The resolved kind is compared, so the same bytes under another type (another inferred kind) differ too.
        assertRefused(upload(heater, type = "image/png"), 409, "OPERATION_KEY_REUSED")
        assertEquals(commits, graph.commits)
        assertEquals(1, rows(heater).size)
    }

    @Test fun identicalBytesUnderANewKeyAre201ANewRow() {
        val heater = client.asset("Example Water Heater")
        val first = upload(heater, metadata = meta(key = "op-1")).row()
        val second = upload(heater, metadata = meta(key = "op-2"))
        assertEquals(201, second.response.status)
        assertNotEquals(first.id, second.row().id)
        assertEquals(first.sha256, second.row().sha256)
        assertEquals(2, rows(heater).size)
    }

    @Test fun aReplayAfterADeleteCreatesAgain() {
        val heater = client.asset("Example Water Heater")
        val id = upload(heater).row().id
        runBlocking { graph.deleteAttachment.run(AttachmentId(id)) }
        assertTrue(rows(heater).isEmpty())
        val again = upload(heater)
        assertEquals(201, again.response.status)
        assertEquals(id, again.row().id)
        assertEquals(1, rows(heater).size)
    }

    @Test fun oneKeyOnTwoInstallationsDerivesTwoIds() {
        val heater = client.asset("Example Water Heater")
        val other = FakeGraph()
        try {
            val otherHeater = V1Client(other, TOKEN).asset("Example Water Heater")
            assertEquals("the fixture's two assets share an id", heater, otherHeater)
            assertNotEquals(graph.installationIdentity.id(), other.installationIdentity.id())
            val here = upload(heater).row().id
            val there = upload(otherHeater, router = V1Client(other, TOKEN).router()).row().id
            assertNotEquals(here, there)
            assertEquals(attachmentOperationId(other.installationIdentity.id(), AssetId(heater), "op-1").value, there)
        } finally {
            other.close()
        }
    }

    @Test fun aBadKeyIs422() {
        val heater = client.asset("Example Water Heater")
        val before = graph.commits
        for (key in listOf("", "has space", "k".repeat(129), "slash/key", "é")) {
            val sent = upload(heater, metadata = meta(key = key))
            assertRefused(sent, 422, "OPERATION_KEY_INVALID", "operationKey")
            assertNothingWritten(sent, before)
        }
        assertEquals(201, upload(heater, metadata = meta(key = "A-z_0.9~:" + "k".repeat(119))).response.status)
    }

    // --- row 14b: R92-7, strict replay after an edit ----------------------------------------------------------------

    private fun renameAndSetRole(id: String) {
        graph.now += 60_000L // an edit made later than the upload, as on the phone
        val patched = client.call(
            "PATCH", "/v1/attachments/$id",
            """{"displayName":"Renamed manual","kind":"DOCUMENT","capturedOn":null,"notes":"","role":"USER_MANUAL"}""",
        )
        assertEquals(patched.bodyText(), 200, patched.status)
    }

    @Test fun aReplayWithStaleMetadataAfterAnEditIs409() {
        val heater = client.asset("Example Water Heater")
        val id = upload(heater).row().id
        renameAndSetRole(id)
        val edited = rows(heater).single()
        val commits = graph.commits
        val sent = upload(heater)
        assertRefused(sent, 409, "OPERATION_KEY_REUSED", "operationKey")
        assertEquals(listOf("OperationKeyReused(attachmentId=$id)"), sent.response.errorDetail().problems)
        assertEquals(commits, graph.commits)
        assertEquals(edited, rows(heater).single())
    }

    @Test fun aReplayMatchingTheCurrentMetadataIs200() {
        val heater = client.asset("Example Water Heater")
        val id = upload(heater).row().id
        renameAndSetRole(id)
        val edited = rows(heater).single()
        val commits = graph.commits
        val sent = upload(heater, metadata = meta(name = "Renamed manual", role = "USER_MANUAL"))
        assertEquals(sent.response.bodyText(), 200, sent.response.status)
        assertEquals(id, sent.row().id)
        assertEquals("Renamed manual", sent.row().displayName)
        assertEquals(commits, graph.commits)
        assertEquals(edited, rows(heater).single())
    }

    // --- row 15: C12, a staging-side failure ------------------------------------------------------------------------

    /** A staging area whose file refuses every write, as a full cache would. */
    private class FullStaging : StagingArea {
        var discarded = 0
        override fun create(): StagingFile = object : StagingFile {
            override fun output(): OutputStream = object : OutputStream() {
                override fun write(b: Int) = throw IOException("no space left on device")
            }
            override fun source(): ByteSource = error("never staged")
            override fun reader(): StagedReader = error("never staged")
            override fun discard() { discarded++ }
        }
    }

    @Test fun aStagingFailureIs409UploadNotStaged() {
        val heater = client.asset("Example Water Heater")
        val before = graph.commits
        val full = FullStaging()
        val sent = upload(heater, router = routerWith(handlers(staging = full)))
        assertRefused(sent, 409, "UPLOAD_NOT_STAGED")
        assertEquals(1, full.discarded)
        assertNothingWritten(sent, before)
        val unwritable = object : StagingArea {
            override fun create(): StagingFile = throw IOException("cache unwritable")
        }
        upload(heater, router = routerWith(handlers(staging = unwritable))).also {
            assertRefused(it, 409, "UPLOAD_NOT_STAGED")
            assertNothingWritten(it, before)
        }
        assertTrue(rows(heater).isEmpty())
    }

    // --- row 16: C12, a dying peer ----------------------------------------------------------------------------------

    @Test fun aShortBodyIs400AndWritesNothing() {
        val heater = client.asset("Example Water Heater")
        val before = graph.commits
        val raw = "POST ${SHAPE.format(heater)} HTTP/1.1\r\nAuthorization: Bearer $TOKEN\r\n" +
            "Content-Type: application/pdf\r\n$UPLOAD_METADATA_HEADER: ${meta()}\r\nContent-Length: 1000\r\n\r\n"
        val request = parseRequest(ByteArrayInputStream(raw.toByteArray() + payload), client.router()::bodyCapFor)
        val response = client.router().handle(request)
        assertEquals(400, response.status)
        assertEquals("bad_request", response.errorDetail().code)
        assertTrue(store.files.isEmpty())
        assertTrue(stagingIsEmpty())
        assertEquals(before, graph.commits)
    }

    @Test fun aBodyPastTheDeadlineWritesNothing() {
        val heater = client.asset("Example Water Heater")
        val big = ByteArray(300 * 1024) { (it % 251).toByte() }
        val existing = upload(heater, bytes = big, metadata = meta(key = "kept", sha = sha(big)))
        assertEquals(201, existing.response.status)
        val before = graph.commits
        val files = store.files.keys.toSet()
        var clock = 0L
        val late = routerWith(handlers(deadlineMillis = 1, nanoTime = { clock += 1_000_000_000L; clock }))
        // Staging, the replay's hashing sink and a refusal's counting sink: each stops at the deadline, with no answer.
        for (sent in listOf(
            { upload(heater, bytes = big, metadata = meta(key = "new", sha = sha(big)), router = late) },
            { upload(heater, bytes = big, metadata = meta(key = "kept", sha = sha(big)), router = late) },
            { upload("no-such-asset", bytes = big, metadata = meta(sha = sha(big)), router = late) },
        )) {
            assertThrows(RequestStreamFailed::class.java) { sent() }
            assertTrue(stagingIsEmpty())
            assertEquals(before, graph.commits)
            assertEquals(files, store.files.keys.toSet())
        }
        assertEquals(1, rows(heater).size)
    }

    @Test fun aStalledPeerPastTheSocketTimeoutWritesNothingAndIsNot409() {
        val heater = client.asset("Example Water Heater")
        val before = graph.commits
        val stalled = object : InputStream() {
            private var given = false
            override fun read(): Int = throw SocketTimeoutException("Read timed out")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (given) throw SocketTimeoutException("Read timed out")
                given = true
                b[off] = 1
                return 1
            }
        }
        val headers = mapOf(
            "authorization" to "Bearer $TOKEN", "content-type" to "application/pdf",
            "content-length" to "1000", UPLOAD_METADATA_HEADER to meta(),
        )
        val request = ApiRequest("POST", SHAPE.format(heater), headers, ByteArray(0), stalled)
        assertThrows(RequestStreamFailed::class.java) { client.router().handle(request) }
        assertTrue(store.files.isEmpty())
        assertTrue(stagingIsEmpty())
        assertEquals(before, graph.commits)
        assertTrue(rows(heater).isEmpty())
    }
}
