package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.AssetSuccession
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.dataTreeOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.sealed
import com.loosecannon.servicetag.core.testing.successionOf
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
 * Backup format 15 (#86, B1 row 3; C3; R86-1, R86-20): one new list, `assetSuccessions`, its own rows sorted by id,
 * closing `data.json` after #77's records; the manifest counts gain the key (26). No shipped writer put a succession
 * into a format ≤ 14 archive — the key did not exist — so one carrying a row was built by hand and is refused after
 * the strict decode; an empty list there, or no key at all, is what this build reads anyway. The names are fictional.
 */
class BackupFormat15Test {

    private val heater = plainAssetOf("x1", "Example Water Heater")
    private val newHeater = plainAssetOf("x2", "Example Water Heater, second")
    private val thirdHeater = plainAssetOf("x3", "Example Water Heater, third")

    /** The three first lists by position — the assets, then no tags and no 2.6 tombstones. */
    private fun data(successions: List<AssetSuccession> = emptyList()) = BackupData(
        listOf(heater, newHeater, thirdHeater).map { it.toDto() }, emptyList(), emptyList(),
        assetSuccessions = successions.map { it.toDto() },
    )

    /** A chain, A → B → C, as two rows (I3), each with its own date. */
    private val first = successionOf("s2", predecessor = "x1", successor = "x2", replacedOn = "2025-04-01", createdAt = 1_743_500_000_000L)
    private val second = successionOf("s1", predecessor = "x2", successor = "x3", replacedOn = "2026-09-20")

    @Test
    fun successionsRoundTripFieldForFieldSortedByIdClosingTheData() {
        val bytes = archiveOf(data(listOf(first, second)))

        val decoded = BackupCodec.decode(bytes)

        assertEquals(18, decoded.manifest.formatVersion)
        assertEquals(listOf("s1", "s2"), decoded.data.assetSuccessions.map { it.id }, "sorted by id")
        assertEquals(listOf(second, first), decoded.data.assetSuccessions.map { it.toDomain() })
        val tree = dataTreeOf(bytes)
        assertEquals("assetSuccessions", tree.keys.toList().dropLast(2).last(), "the list follows the records; format 18's two lists close data.json")
        assertEquals("transferRecords", tree.keys.toList().dropLast(3).last(), "after #77's records")
        val row = tree.getValue("assetSuccessions").jsonArray.first().jsonObject
        assertEquals(listOf("id", "predecessorAssetId", "successorAssetId", "replacedOn", "createdAt"), row.keys.toList())
    }

    @Test
    fun theManifestCountsCarryAssetSuccessions() {
        val manifest = BackupCodec.decode(archiveOf(data(listOf(first, second)))).manifest

        assertEquals(2, manifest.counts["assetSuccessions"])
        assertEquals(29, manifest.counts.size, "twenty-five through format 14, the successions, and format 18's three")
        assertEquals(0, BackupCodec.decode(archiveOf(data())).manifest.counts["assetSuccessions"], "present at zero")
    }

    /** Equal content is equal bytes: the list is written sorted by id whatever order it was handed in. */
    @Test
    fun aShuffledArchiveEncodesToTheSameBytes() {
        val third = successionOf("s0", predecessor = "x3", successor = "x1", replacedOn = "2026-01-01")
        val sorted = archiveOf(data(listOf(first, second)))
        assertContentEquals(dataJson(sorted), dataJson(archiveOf(data(listOf(second, first)))))
        // Byte equality is the encoder's, not the checks': a three-row order is equal bytes too, cycle or not.
        val a = BackupCodec.encode(data(listOf(third, first, second)), "1.4.1", 15, 1L, "set-1")
        val b = BackupCodec.encode(data(listOf(second, third, first)), "1.4.1", 15, 1L, "set-1")
        assertContentEquals(dataJson(a), dataJson(b))
    }

    /** Through the strict decode (14 … 8) and the ≤ 7 upgrade alike; the refusal names the list and the format. */
    @Test
    fun aFormat14ArchiveCarryingASuccessionIsRefused() {
        for (format in listOf(14, 13, 9, 8, 7)) {
            val bytes = archiveOf(data(listOf(first)), formatVersion = format)

            val refusal = assertFailsWith<BackupCorrupt>("format $format") { BackupCodec.decode(bytes) }

            assertTrue(refusal.message!!.startsWith("assetSuccessions:") && "format $format" in refusal.message!!, refusal.message)
        }
    }

    /** Explicit empty lists in a format-14 archive restore, and so does the shipped shape with no key at all. */
    @Test
    fun aFormat14ArchiveWithoutSuccessionsRestores() {
        val explicit = archiveOf(data(), formatVersion = 14)
        assertEquals(JsonArray(emptyList()), dataTreeOf(explicit)["assetSuccessions"], "the fixture carries an explicit empty list")
        val absent = sealed(dataTreeOf(explicit).without("assetSuccessions"), formatVersion = 14)
        assertTrue("assetSuccessions" !in dataTreeOf(absent), "the fixture carries no key")

        for (bytes in listOf(explicit, absent)) {
            val decoded = BackupCodec.decode(bytes)
            assertEquals(14, decoded.manifest.formatVersion)
            assertEquals(data(), decoded.data)
        }
    }

    private fun dataJson(bytes: ByteArray): ByteArray = zipEntries(bytes).getValue(BackupCodec.DATA_ENTRY)
}
