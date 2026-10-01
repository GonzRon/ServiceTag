package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.dataTreeOf
import com.loosecannon.servicetag.core.testing.editRows
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.sealed
import com.loosecannon.servicetag.core.testing.with
import com.loosecannon.servicetag.core.testing.without
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Backup format 17 (#91, B1b rows 6–9; C6; R91-1): each `assetReferences[]` row appends one key, `role`, a
 * `DocumentRole` name or an explicit `null`. No shipped writer put a role on a reference before format 17 — the key
 * did not exist — so a non-null one in an older archive was built by hand and is refused naming the reference; an
 * explicit `null` there, or no key at all, is what this build reads anyway. Only a web link may carry a role, so a
 * role on a note link or an "other" link is refused at decode whatever the format. The names are fictional.
 */
class BackupFormat17Test {

    private val heater = plainAssetOf("x1", "Example Water Heater")

    private fun reference(
        id: String,
        kind: ReferenceKind,
        uri: String,
        scheme: String,
        role: DocumentRole? = null,
        name: String = "Example Water Heater link $id",
    ) = AssetReference(
        id = ReferenceId(id), assetId = AssetId("x1"), kind = kind, uri = uri, displayName = name,
        description = "", scheme = scheme, createdAt = 1_000L, updatedAt = 2_000L, role = role,
    )

    /** Each role on a web link, http and https alike, beside a web link with none and the two other kinds with none. */
    private val receipt = reference(
        "r1", ReferenceKind.WEB_URL, "http://shop.example.invalid/orders/example", "http",
        role = DocumentRole.PURCHASE_INVOICE_OR_RECEIPT,
    )
    private val userManual = reference(
        "r2", ReferenceKind.WEB_URL, "https://manuals.example.invalid/heater/user-manual.pdf", "https",
        role = DocumentRole.USER_MANUAL,
    )
    private val serviceManual = reference(
        "r3", ReferenceKind.WEB_URL, "https://manuals.example.invalid/heater/service.pdf", "https",
        role = DocumentRole.SERVICE_MANUAL,
    )
    private val plainWeb = reference("r4", ReferenceKind.WEB_URL, "https://manuals.example.invalid/heater", "https")
    private val note = reference("r5", ReferenceKind.NOTE_LINK, "joplin://x-callback-url/openNote?id=example", "joplin")
    private val other = reference("r6", ReferenceKind.OTHER, "ftp://files.example.invalid/heater.txt", "ftp")

    private val everyShape = listOf(receipt, userManual, serviceManual, plainWeb, note, other)

    private fun data(references: List<AssetReference>) = BackupData(
        listOf(heater.toDto()), emptyList(), emptyList(),
        assetReferences = references.map { it.toDto() },
    )

    /** The reference rows of [bytes]'s `data.json`, as the writer put them down, keys and all. */
    private fun referencesWritten(bytes: ByteArray): List<JsonObject> =
        dataTreeOf(bytes).getValue("assetReferences").jsonArray.map { it.jsonObject }

    /** [bytes]'s data, resealed at [formatVersion] with [edit] applied to every reference row. */
    private fun resealed(bytes: ByteArray, formatVersion: Int, edit: (JsonObject) -> JsonObject): ByteArray =
        sealed(dataTreeOf(bytes).editRows("assetReferences", edit), formatVersion = formatVersion)

    /** [bytes]'s data with the row [id] given `"role": [role]`, resealed at [formatVersion]. */
    private fun withRoleOn(bytes: ByteArray, id: String, role: String, formatVersion: Int): ByteArray =
        resealed(bytes, formatVersion) { row ->
            if (row.getValue("id").jsonPrimitive.content == id) row.with("role", JsonPrimitive(role)) else row
        }

    // --- row 6: the round trip -------------------------------------------------------------------

    /**
     * Hazard: a role dropped in transit. Each role travels by its name, an unset one as an explicit `"role": null`
     * (never left out), as the tenth and last key of the row; and the domain rows read back equal, both ways round.
     */
    @Test
    fun eachRoleAndNoneRoundTrips() {
        val bytes = archiveOf(data(everyShape))

        val decoded = BackupCodec.decode(bytes)

        assertEquals(18, decoded.manifest.formatVersion)
        assertEquals(everyShape, decoded.data.assetReferences.map { it.toDomain() })
        val written = referencesWritten(bytes).associateBy { it.getValue("id").jsonPrimitive.content }
        assertEquals("PURCHASE_INVOICE_OR_RECEIPT", written.getValue("r1").getValue("role").jsonPrimitive.content)
        assertEquals("USER_MANUAL", written.getValue("r2").getValue("role").jsonPrimitive.content)
        assertEquals("SERVICE_MANUAL", written.getValue("r3").getValue("role").jsonPrimitive.content)
        for (id in listOf("r4", "r5", "r6")) {
            assertEquals(JsonNull, written.getValue(id)["role"], "$id: no role is written as an explicit null")
        }
        for (row in written.values) {
            assertEquals(
                listOf(
                    "id", "assetId", "kind", "uri", "displayName", "description", "scheme",
                    "createdAt", "updatedAt", "role",
                ),
                row.keys.toList(),
                "the role is appended, the nine columns keep their order",
            )
        }
        for (role in DocumentRole.entries) {
            val one = plainWeb.copy(role = role)
            assertEquals(one, one.toDto().toDomain())
        }
    }

    // --- row 7: the format ≤ 16 gate -------------------------------------------------------------

    /** The shipped shape before #91: no `role` key at all. It reads with no role, through the strict decode and the ≤ 7 upgrade. */
    @Test
    fun aFormat16ArchiveDecodesWithNoRoles() {
        val unroled = listOf(plainWeb, note, other)
        for (format in listOf(16, 10, 8, 7)) {
            val absent = resealed(archiveOf(data(unroled)), format) { it.without("role") }
            assertTrue(referencesWritten(absent).none { "role" in it }, "the fixture carries no key")

            val decoded = BackupCodec.decode(absent)

            assertEquals(format, decoded.manifest.formatVersion)
            assertEquals(unroled, decoded.data.assetReferences.map { it.toDomain() }, "format $format")
        }
    }

    /** A role in an archive older than 17 was put there by hand: refused before a row is named, naming the reference. */
    @Test
    fun aFormat16ArchiveCarryingARoleIsCorrupt() {
        for (format in listOf(16, 15, 10, 8, 7)) {
            val bytes = withRoleOn(archiveOf(data(listOf(plainWeb, note))), "r4", "USER_MANUAL", format)

            val refusal = assertFailsWith<BackupCorrupt>("format $format") { BackupCodec.decode(bytes) }

            val message = refusal.message!!
            assertTrue(message.startsWith("assetReferences:"), message)
            assertTrue("format $format" in message && "document role" in message && "r4" in message, message)
        }
    }

    /** An explicit `"role": null` in a format-16 archive is what this build's own DTO reads anyway, and is accepted. */
    @Test
    fun anExplicitNullInAFormat16ArchiveIsAccepted() {
        val unroled = listOf(plainWeb, note, other)
        val explicit = archiveOf(data(unroled), formatVersion = 16)
        assertTrue(referencesWritten(explicit).all { it["role"] == JsonNull }, "the fixture must carry an explicit null")

        val decoded = BackupCodec.decode(explicit)

        assertEquals(16, decoded.manifest.formatVersion)
        assertEquals(data(unroled), decoded.data)
        assertEquals(listOf(null, null, null), decoded.data.assetReferences.map { it.toDomain().role })
    }

    // --- row 8: eligibility and names at decode --------------------------------------------------

    /** R91-1: only a web link may carry a role. One on a note link is a hand-built archive, refused naming the reference. */
    @Test
    fun aRoleOnANoteLinkIsCorruptNamingTheReference() {
        val bytes = withRoleOn(archiveOf(data(listOf(plainWeb, note))), "r5", "SERVICE_MANUAL", formatVersion = 17)

        val refusal = assertFailsWith<BackupCorrupt> { BackupCodec.decode(bytes) }

        assertTrue("r5" in refusal.message!! && "document role" in refusal.message!!, "unhelpful: ${refusal.message}")
        // the same archive with the role on the web link is read, and the note link beside it is an ordinary one
        val onTheWebLink = withRoleOn(archiveOf(data(listOf(plainWeb, note))), "r4", "SERVICE_MANUAL", formatVersion = 17)
        assertEquals(
            listOf(plainWeb.copy(role = DocumentRole.SERVICE_MANUAL), note),
            BackupCodec.decode(onTheWebLink).data.assetReferences.map { it.toDomain() },
        )
    }

    /** The same for the third kind: an "other" link takes no role either, whichever role it is. */
    @Test
    fun aRoleOnAnOtherLinkIsCorrupt() {
        for (role in DocumentRole.entries) {
            val bytes = withRoleOn(archiveOf(data(listOf(other))), "r6", role.name, formatVersion = 17)

            val refusal = assertFailsWith<BackupCorrupt>(role.name) { BackupCodec.decode(bytes) }

            assertTrue("r6" in refusal.message!! && "document role" in refusal.message!!, "unhelpful: ${refusal.message}")
        }
    }

    /** Only the three names, on a web link too: a kind's name, a lower-case spelling and a blank are all refused. */
    @Test
    fun anUnknownRoleNameIsCorrupt() {
        for (name in listOf("WARRANTY", "WEB_URL", "user_manual", "")) {
            val bytes = withRoleOn(archiveOf(data(listOf(plainWeb))), "r4", name, formatVersion = 17)

            val refusal = assertFailsWith<BackupCorrupt>("\"$name\"") { BackupCodec.decode(bytes) }

            assertTrue("document role" in refusal.message!! && "r4" in refusal.message!!, "unhelpful: ${refusal.message}")
        }
    }

    // --- row 9: newer refused --------------------------------------------------------------------

    /** One format past this build's, over a tree no format could read: refused as newer before a row is parsed. */
    @Test
    fun aFormat19ArchiveIsRefusedAsNewer() {
        val unreadable = dataTreeOf(archiveOf(data(listOf(userManual))))
            .editRows("assetReferences") { it.with("role", JsonPrimitive("NOT_A_ROLE")) }
        val bytes = sealed(unreadable, formatVersion = 19)

        val refusal = assertFailsWith<BackupNewerFormat> { BackupCodec.decode(bytes) }

        assertEquals(19, refusal.found)
        assertEquals(18, refusal.supported)
    }
}
