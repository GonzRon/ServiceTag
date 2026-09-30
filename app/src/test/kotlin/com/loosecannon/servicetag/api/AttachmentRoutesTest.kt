package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentLocator
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.AttachmentSource
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.testing.FakeGraph
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #92 B1a (C5–C8; rows 1–6) — an asset's attachments listed, one row read, and the full-command edit, over the
 * production router and the Room-backed [FakeGraph]. Every fixture is fictional.
 */
class AttachmentRoutesTest {

    private val graph = FakeGraph()
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    private fun event(assetId: String): String = api.ok(
        EventResponse.serializer(), "POST", "/v1/events",
        """{"assetId":"$assetId","kind":"INSPECTION","title":"Anode checked","occurredOn":"2026-03-01","tzId":"UTC"}""",
        status = 201,
    ).event.id

    private fun seed(
        id: String,
        owner: AttachmentOwner,
        name: String,
        createdAt: Long = 100L,
        role: DocumentRole? = null,
        source: AttachmentSource? = null,
    ): Attachment {
        val row = Attachment(
            id = AttachmentId(id), owner = owner, kind = AttachmentKind.DOCUMENT, displayName = name,
            mimeType = "application/pdf", sizeBytes = 2048, sha256 = "ab".repeat(32),
            storageLocator = AttachmentLocator.forOwner(owner, AttachmentId(id), name, "application/pdf"),
            capturedOn = "2026-02-01", notes = "", createdAt = createdAt, updatedAt = createdAt,
            role = role, source = source,
        )
        runBlocking { graph.attachments.upsert(row) }
        return row
    }

    private fun stored(id: String): Attachment? = runBlocking { graph.attachments.get(AttachmentId(id)) }

    private fun patch(id: String, body: String): ApiResponse = api.call("PATCH", "/v1/attachments/$id", body)

    private fun list(assetId: String): AttachmentListResponse =
        api.ok(AttachmentListResponse.serializer(), "GET", "/v1/assets/$assetId/attachments")

    // --- row 1: the list ---------------------------------------------------------------------

    @Test fun theListIsThisAssetsOwnRowsOldestFirst() {
        val heater = api.asset("Example Water Heater")
        val other = api.asset("Example Softener")
        val onHeater = AttachmentOwner.OfAsset(AssetId(heater))
        seed("att-c", onHeater, "Alpha receipt", createdAt = 300L)
        seed("att-b", onHeater, "Zeta manual", createdAt = 200L)
        seed("att-a", onHeater, "Beta photo", createdAt = 300L)
        seed("att-e", AttachmentOwner.OfEvent(EventId(event(heater))), "Anode photo", createdAt = 50L)
        seed("att-o", AttachmentOwner.OfAsset(AssetId(other)), "Softener manual", createdAt = 10L)

        val answer = list(heater)

        assertEquals(listOf("att-b", "att-a", "att-c"), answer.attachments.map { it.id })
        assertEquals(listOf(heater), answer.attachments.map { it.assetId }.distinct())
    }

    @Test fun theFolderIsItsStateNameNeverItsName() {
        val heater = api.asset("Example Water Heater")
        for ((state, name) in listOf(
            StoreState.Ready("Example Folder", "com.example.provider") to AttachmentFolder.READY,
            StoreState.NotConfigured to AttachmentFolder.NOT_CONFIGURED,
            StoreState.AccessLost("Example Folder") to AttachmentFolder.ACCESS_LOST,
        )) {
            graph.attachmentStorage.state = state
            val response = api.call("GET", "/v1/assets/$heater/attachments")
            assertEquals(200, response.status)
            assertEquals(name, ApiJson.decodeFromString(AttachmentListResponse.serializer(), response.bodyText()).folder)
            assertFalse(response.bodyText(), "Example Folder" in response.bodyText())
            assertFalse(response.bodyText(), "com.example.provider" in response.bodyText())
        }
    }

    // --- row 2: the list's verbs -------------------------------------------------------------

    @Test fun anUnknownAssetIs404NoSuchAsset() {
        val response = api.call("GET", "/v1/assets/no-such-asset/attachments")
        assertEquals(404, response.status)
        assertEquals("no_such_asset", response.errorDetail().code)
    }

    @Test fun aDeleteOnTheListIs404() {
        val heater = api.asset("Example Water Heater")
        val response = api.call("DELETE", "/v1/assets/$heater/attachments")
        assertEquals(404, response.status)
        assertEquals("not_found", response.errorDetail().code)
    }

    @Test fun aDeleteOnOneRowIs405AndRemovesNothing() {
        val heater = api.asset("Example Water Heater")
        seed("att-1", AttachmentOwner.OfAsset(AssetId(heater)), "Manual")
        val response = api.call("DELETE", "/v1/attachments/att-1")
        assertEquals(405, response.status)
        assertEquals("method_not_allowed", response.errorDetail().code)
        assertEquals("Manual", stored("att-1")?.displayName)
    }

    // --- row 3: one row ----------------------------------------------------------------------

    @Test fun getAnswersAnEventOwnedRow() {
        val heater = api.asset("Example Water Heater")
        val eventId = event(heater)
        val row = seed("att-e", AttachmentOwner.OfEvent(EventId(eventId)), "Anode photo")

        val answer = api.ok(AttachmentResponse.serializer(), "GET", "/v1/attachments/att-e").attachment

        assertEquals(row.toDto(), answer)
        assertEquals(eventId, answer.eventId)
        assertNull(answer.assetId)
    }

    @Test fun aMissingRowIsNoSuchAttachment() {
        for ((method, body) in listOf("GET" to "", "PATCH" to """{"displayName":"x","kind":"OTHER","role":null}""")) {
            val response = api.call(method, "/v1/attachments/no-such-row", body)
            assertEquals(method, 404, response.status)
            assertEquals(method, "NO_SUCH_ATTACHMENT", response.errorDetail().code)
            assertEquals(method, "no such attachment", response.errorDetail().message)
        }
    }

    // --- row 4: the full command -------------------------------------------------------------

    @Test fun aPatchWithoutTheRoleKeyIs400() {
        val heater = api.asset("Example Water Heater")
        seed("att-1", AttachmentOwner.OfAsset(AssetId(heater)), "Manual", role = DocumentRole.USER_MANUAL)
        val before = graph.commits

        val response = patch("att-1", """{"displayName":"Owner manual","kind":"MANUAL"}""")

        assertEquals(response.bodyText(), 400, response.status)
        assertEquals("bad_request", response.errorDetail().code)
        assertEquals(DocumentRole.USER_MANUAL, stored("att-1")?.role)
        assertEquals("Manual", stored("att-1")?.displayName)
        assertEquals(before, graph.commits)
    }

    @Test fun aNullRoleClears() {
        val heater = api.asset("Example Water Heater")
        val row = seed("att-1", AttachmentOwner.OfAsset(AssetId(heater)), "Manual", role = DocumentRole.USER_MANUAL)
        graph.now = 5_000L

        val answer = api.ok(
            AttachmentResponse.serializer(), "PATCH", "/v1/attachments/att-1",
            """{"displayName":"Manual","kind":"DOCUMENT","capturedOn":"2026-02-01","notes":"","role":null}""",
        ).attachment

        assertNull(answer.role)
        assertNull(stored("att-1")?.role)
        assertEquals(5_000L, answer.updatedAt)
        assertEquals(row.storageLocator, answer.storageLocator)
        assertEquals(stored("att-1")?.toDto(), answer)
    }

    @Test fun aFullCommandRenamesSetsTheRoleAndKeepsTheBytes() {
        val heater = api.asset("Example Water Heater")
        val row = seed("att-1", AttachmentOwner.OfAsset(AssetId(heater)), "scan.pdf")

        val answer = api.ok(
            AttachmentResponse.serializer(), "PATCH", "/v1/attachments/att-1",
            """{"displayName":"  Owner manual  ","kind":"MANUAL","capturedOn":null,"notes":"Shelf 2","role":"USER_MANUAL"}""",
        ).attachment

        assertEquals("Owner manual", answer.displayName)
        assertEquals("MANUAL", answer.kind)
        assertEquals("USER_MANUAL", answer.role)
        assertEquals("Shelf 2", answer.notes)
        assertNull(answer.capturedOn)
        assertEquals(row.storageLocator, answer.storageLocator)
        assertEquals(row.sha256, answer.sha256)
        assertEquals(stored("att-1")?.toDto(), answer)
    }

    @Test fun aNoOpPatchIs200TheStoredRowAndWritesNothing() {
        val heater = api.asset("Example Water Heater")
        val row = seed("att-1", AttachmentOwner.OfAsset(AssetId(heater)), "Manual", role = DocumentRole.SERVICE_MANUAL)
        graph.now = 9_000L
        val before = graph.commits

        val answer = api.ok(
            AttachmentResponse.serializer(), "PATCH", "/v1/attachments/att-1",
            """{"displayName":"Manual","kind":"DOCUMENT","capturedOn":"2026-02-01","notes":"","role":"SERVICE_MANUAL"}""",
        ).attachment

        assertEquals(row.toDto(), answer)
        assertEquals(row, stored("att-1"))
        assertEquals(0, graph.commits - before)
    }

    // --- row 5: the refusals ------------------------------------------------------------------

    @Test fun aRoleOnAnEventRowIs422AndWritesNothing() {
        val heater = api.asset("Example Water Heater")
        val row = seed("att-e", AttachmentOwner.OfEvent(EventId(event(heater))), "Anode photo")
        val before = graph.commits

        val response = patch(
            "att-e", """{"displayName":"Anode photo","kind":"DOCUMENT","capturedOn":"2026-02-01","role":"USER_MANUAL"}""",
        )

        assertEquals(response.bodyText(), 422, response.status)
        val error = response.errorDetail()
        assertEquals("ATTACHMENT_ROLE_NOT_ALLOWED", error.code)
        assertEquals("role", error.field)
        assertEquals("a document role belongs on an asset's attachment", error.message)
        assertEquals(row, stored("att-e"))
        assertEquals(before, graph.commits)
    }

    @Test fun aBlankNameIs422() {
        val heater = api.asset("Example Water Heater")
        val row = seed("att-1", AttachmentOwner.OfAsset(AssetId(heater)), "Manual")
        val before = graph.commits

        val response = patch("att-1", """{"displayName":"   ","kind":"DOCUMENT","role":null}""")

        assertEquals(response.bodyText(), 422, response.status)
        val error = response.errorDetail()
        assertEquals("ATTACHMENT_NAME_REQUIRED", error.code)
        assertEquals("displayName", error.field)
        assertEquals("an attachment needs a name", error.message)
        assertEquals(listOf("BlankName"), error.problems)
        assertEquals(row, stored("att-1"))
        assertEquals(before, graph.commits)
    }

    @Test fun aBadCapturedOnIs422() {
        val heater = api.asset("Example Water Heater")
        val row = seed("att-1", AttachmentOwner.OfAsset(AssetId(heater)), "Manual")
        val before = graph.commits

        for (day in listOf("2026-13-01", "01/02/2026", "")) {
            val response = patch("att-1", """{"displayName":"Manual","kind":"DOCUMENT","capturedOn":"$day","role":null}""")
            assertEquals(day, 422, response.status)
            val error = response.errorDetail()
            assertEquals(day, "ATTACHMENT_BAD_DATE", error.code)
            assertEquals(day, "capturedOn", error.field)
            assertEquals(day, "capturedOn is not a YYYY-MM-DD day", error.message)
        }
        assertEquals(row, stored("att-1"))
        assertEquals(before, graph.commits)
    }

    // --- row 6: provenance on the wire --------------------------------------------------------

    @Test fun aSourcedRowReadsExactlyAsItsArchiveRow() {
        val heater = api.asset("Example Water Heater")
        val row = seed(
            "att-s", AttachmentOwner.OfAsset(AssetId(heater)), "Example manual", role = DocumentRole.USER_MANUAL,
            source = AttachmentSource(
                uri = "https://manuals.example.invalid/heater.pdf?token=fictional",
                resolvedUri = "https://cdn.example.invalid/files/heater.pdf",
                retrievedAt = 1_700_000_000_000L,
                name = "Example heater manual",
            ),
        )

        val one = api.ok(AttachmentResponse.serializer(), "GET", "/v1/attachments/att-s").attachment
        val listed = list(heater).attachments.single()

        assertEquals(row.toDto(), one)
        assertEquals(row.toDto(), listed)
        assertEquals("https://manuals.example.invalid/heater.pdf?token=fictional", one.sourceUri)
        assertEquals("https://cdn.example.invalid/files/heater.pdf", one.sourceResolvedUri)
        assertEquals(1_700_000_000_000L, one.sourceRetrievedAt)
        assertEquals("Example heater manual", one.sourceName)
    }
}
