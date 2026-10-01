package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.dataTreeOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.sealed
import com.loosecannon.servicetag.core.testing.transferOf
import com.loosecannon.servicetag.core.testing.without
import com.loosecannon.servicetag.core.testing.zipEntries
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Backup format 14 (#77, C7; R77-3, R77-12): one new list, `transferRecords`, its own rows sorted by id,
 * closing `data.json`. No shipped writer put a record into a format ≤ 13 archive — the key did not exist —
 * so one carrying a row was built by hand and is refused after the strict decode; an empty list there, or
 * no key at all, is what this build reads anyway. A record's asset is soft: the held asset is never in the
 * archive its records travel in. The names and pack ids are fictional.
 */
class BackupFormat14Test {

    private val compressor = plainAssetOf("x1", "Example Compressor")

    private fun data(records: List<TransferRecord> = emptyList()) = BackupData(
        assets = listOf(compressor.toDto()), nfcTags = emptyList(), externalLinks = emptyList(),
        transferRecords = records.map { it.toDto() },
    )

    private val outQ = transferOf("r2", assetId = "a1", packId = "pack-q", note = "Example handover note")
    private val outS = transferOf("r1", assetId = "a2", packId = "pack-s", lineage = listOf("pack-o", "pack-p"))
    private val withdrawnS = transferOf("r3", assetId = "a2", kind = TransferKind.WITHDRAWN, packId = "pack-s")
    private val inP = transferOf("r0", assetId = "x1", kind = TransferKind.IN, packId = "pack-p", lineage = listOf("pack-o"))

    @Test
    fun recordsRoundTripFieldForFieldSortedByIdClosingTheData() {
        val bytes = archiveOf(data(listOf(outQ, outS, withdrawnS, inP)))

        val decoded = BackupCodec.decode(bytes)

        assertEquals(17, decoded.manifest.formatVersion)
        assertEquals(listOf("r0", "r1", "r2", "r3"), decoded.data.transferRecords.map { it.id }, "sorted by id")
        assertEquals(listOf(inP, outS, outQ, withdrawnS), decoded.data.transferRecords.map { it.toDomain() })
        val tree = dataTreeOf(bytes)
        assertEquals("transferRecords", tree.keys.toList().dropLast(1).last(), "the list follows the loans; format 15's successions close data.json")
        val row = tree.getValue("transferRecords").jsonArray.first().jsonObject
        assertEquals(
            listOf("id", "assetId", "kind", "packId", "lineage", "at", "packSha256", "nameSnapshot", "note"),
            row.keys.toList(),
        )
    }

    @Test
    fun theManifestCountsCarryTransferRecords() {
        val manifest = BackupCodec.decode(archiveOf(data(listOf(outQ, outS, withdrawnS)))).manifest

        assertEquals(3, manifest.counts["transferRecords"])
        assertEquals(26, manifest.counts.size, "twenty-four through format 13, the records, and format 15's successions")
        assertEquals(0, BackupCodec.decode(archiveOf(data())).manifest.counts["transferRecords"], "present at zero")
    }

    /** Equal content is equal bytes: the list is written sorted by id whatever order it was handed in. */
    @Test
    fun aShuffledArchiveEncodesToTheSameBytes() {
        val sorted = archiveOf(data(listOf(inP, outS, outQ, withdrawnS)))
        for (order in listOf(listOf(withdrawnS, outQ, outS, inP), listOf(outQ, inP, withdrawnS, outS))) {
            assertContentEquals(dataJson(sorted), dataJson(archiveOf(data(order))), order.map { it.id }.toString())
        }
    }

    /** Through the strict decode (13 … 8) and the ≤ 7 upgrade alike; the refusal names the list and the format. */
    @Test
    fun aFormat13ArchiveCarryingARecordIsRefused() {
        for (format in listOf(13, 12, 9, 8, 7)) {
            val bytes = archiveOf(data(listOf(outQ)), formatVersion = format)

            val refusal = assertFailsWith<BackupCorrupt>("format $format") { BackupCodec.decode(bytes) }

            assertTrue(refusal.message!!.startsWith("transferRecords:") && "format $format" in refusal.message!!, refusal.message)
        }
    }

    /** Explicit empty lists in a format-13 archive restore, and so does the shipped shape with no key at all. */
    @Test
    fun aFormat13ArchiveWithoutRecordsRestores() {
        val explicit = archiveOf(data(), formatVersion = 13)
        assertEquals(JsonArray(emptyList()), dataTreeOf(explicit)["transferRecords"], "the fixture carries an explicit empty list")
        val absent = sealed(dataTreeOf(explicit).without("transferRecords"), formatVersion = 13)
        assertTrue("transferRecords" !in dataTreeOf(absent), "the fixture carries no key")

        for (bytes in listOf(explicit, absent)) {
            val decoded = BackupCodec.decode(bytes)
            assertEquals(13, decoded.manifest.formatVersion)
            assertEquals(data(), decoded.data)
        }
    }

    private fun dataJson(bytes: ByteArray): ByteArray = zipEntries(bytes).getValue(BackupCodec.DATA_ENTRY)
}
