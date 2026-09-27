package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.dataTreeOf
import com.loosecannon.servicetag.core.testing.editRows
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.sealed
import com.loosecannon.servicetag.core.testing.with
import com.loosecannon.servicetag.core.testing.without
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Backup format 11 (#79, C18): the asset carries the warranty reminder's lead, written as its number
 * or, unset, as an explicit `"warrantyReminderLeadDays": null` (never left out). No shipped writer put
 * a lead into a format ≤ 10 archive — the key did not exist — so one that carries a non-null lead was
 * built by hand and is refused after the strict decode; an explicit null there, or no key at all, is
 * what this build reads anyway. The names are fictional.
 */
class BackupFormat11Test {

    private val heater = plainAssetOf("a1", "Example Heater").copy(warrantyExpiresOn = "2027-03-01")

    private fun data(vararg assets: com.loosecannon.servicetag.core.model.Asset) = BackupData(
        assets = assets.map { it.toDto() }, nfcTags = emptyList(), externalLinks = emptyList(),
    )

    /** The asset objects as the writer put them down, keys and all. */
    private fun assetsWritten(bytes: ByteArray) = dataTreeOf(bytes).getValue("assets").jsonArray.map { it.jsonObject }

    @Test
    fun aLeadRoundTrips() {
        val led = heater.copy(warrantyReminderLeadDays = 30)
        val bytes = archiveOf(data(led, plainAssetOf("a2", "Example Heater two").copy(warrantyExpiresOn = "2030-12-31", warrantyReminderLeadDays = Int.MAX_VALUE)))

        val decoded = BackupCodec.decode(bytes)

        assertEquals(11, decoded.manifest.formatVersion)
        assertEquals(listOf(30, Int.MAX_VALUE), decoded.data.assets.map { it.warrantyReminderLeadDays })
        assertEquals(led, decoded.data.assets.first().toDomain(), "onto the domain row, field for field")
        assertEquals(30, assetsWritten(bytes).first().getValue("warrantyReminderLeadDays").jsonPrimitive.content.toInt())
        assertEquals(led.toDto(), led.toDto().toDomain().toDto(), "both ways round")
    }

    @Test
    fun anUnsetLeadIsWrittenAsNull() {
        val bytes = archiveOf(data(heater))

        val written = assetsWritten(bytes).single()

        assertTrue("warrantyReminderLeadDays" in written, "the key is written, not left out: ${written.keys}")
        assertEquals(JsonNull, written.getValue("warrantyReminderLeadDays"))
        assertEquals(null, BackupCodec.decode(bytes).data.assets.single().toDomain().warrantyReminderLeadDays)
    }

    /**
     * Through the strict decode (10, 9, 8) and through the ≤ 7 upgrade alike, before a row is named:
     * the refusal names the table, the format and the asset.
     */
    @Test
    fun aFormat10ArchiveWithANonNullLeadIsRefused() {
        for (format in listOf(10, 9, 8, 7)) {
            val bytes = archiveOf(data(heater.copy(warrantyReminderLeadDays = 30)), formatVersion = format)

            val refusal = assertFailsWith<BackupCorrupt>("format $format") { BackupCodec.decode(bytes) }

            assertTrue(refusal.message!!.startsWith("assets:"), refusal.message)
            assertTrue(
                "format $format" in refusal.message!! && "lead" in refusal.message!! && "a1" in refusal.message!!,
                refusal.message,
            )
        }
    }

    /** An explicit null in a format-10 archive restores, and so does the shipped shape with no key at all. */
    @Test
    fun aFormat10ArchiveWithAnExplicitNullRestores() {
        val explicit = archiveOf(data(heater), formatVersion = 10)
        assertEquals(JsonNull, assetsWritten(explicit).single()["warrantyReminderLeadDays"], "the fixture carries an explicit null")
        val absent = sealed(dataTreeOf(explicit).editRows("assets") { it.without("warrantyReminderLeadDays") }, formatVersion = 10)
        assertTrue(assetsWritten(absent).none { "warrantyReminderLeadDays" in it }, "the fixture carries no key")

        for (bytes in listOf(explicit, absent)) {
            val decoded = BackupCodec.decode(bytes)

            assertEquals(10, decoded.manifest.formatVersion)
            assertEquals(data(heater), decoded.data)
            assertEquals(null, decoded.data.assets.single().toDomain().warrantyReminderLeadDays)
        }
        // And the same row at this build's own format with the lead's key sealed in by hand reads as written.
        val handSealed = sealed(dataTreeOf(explicit).editRows("assets") { it.with("warrantyReminderLeadDays", JsonPrimitive(7)) }, formatVersion = 11)
        assertEquals(7, BackupCodec.decode(handSealed).data.assets.single().warrantyReminderLeadDays)
    }
}
