package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteException
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #77's transfer records through the production adapter (C6; R77-3, R77-4): append and read, the lineage a
 * JSON array that round-trips as it went in; an id already held aborts the append rather than replacing the
 * record; and there is **no foreign key** — deleting an asset keeps its records, which is what lets every
 * later backup and a Replace restore stay correct. The names and pack ids are fictional.
 */
class TransferRecordRoomTest {

    private val db = inMemoryDb()
    private val assets = RoomAssetRepository(db.assetDao())
    private val records = RoomTransferRecordRepository(db.transferRecordDao())

    @After fun close() = db.close()

    private fun recordOf(
        id: String,
        assetId: String = "a1",
        kind: TransferKind = TransferKind.OUT,
        packId: String = "pack-q",
        lineage: List<String> = emptyList(),
        at: Long = 1_758_960_000_000L,
    ) = TransferRecord(id, AssetId(assetId), kind, packId, lineage, at, "ab".repeat(32), "Example Water Heater", "Example note")

    @Test
    fun appendedRecordsReadBackInTheirOrdersAndHold() = runTest {
        val out = recordOf("r2", lineage = listOf("pack-o", "pack-p, with a comma"), at = 30L)
        val back = recordOf("r1", kind = TransferKind.IN, packId = "pack-r", lineage = listOf("pack-o", "pack-p, with a comma", "pack-q"), at = 40L)
        val other = recordOf("r3", assetId = "a2", packId = "pack-s", at = 10L)
        listOf(out, back, other).forEach { records.append(it) }

        assertEquals(listOf(back, out, other), records.all())
        assertEquals(listOf(out, back), records.forAsset(AssetId("a1")))
        assertEquals(setOf(AssetId("a2")), records.heldIds())
        assertEquals(setOf(AssetId("a2")), records.observeHeldIds().first())
    }

    /** Append-only: the same id twice is the database refusing, never an overwrite. */
    @Test
    fun anIdAlreadyHeldAbortsTheAppend() = runTest {
        records.append(recordOf("r1"))

        val refused = runCatching { records.append(recordOf("r1", packId = "pack-other")) }.exceptionOrNull()

        assertTrue("$refused", refused is SQLiteException)
        assertEquals(listOf(recordOf("r1")), records.all())
    }

    /** R77-4: `DeleteAsset` forgets the local history and keeps the records — no cascade reaches them. */
    @Test
    fun deletingTheAssetKeepsItsRecords() = runTest {
        assets.upsert(Asset(id = AssetId("a1"), name = "Example Water Heater", createdAt = 1L, updatedAt = 1L))
        records.append(recordOf("r1"))
        records.append(recordOf("r2", kind = TransferKind.WITHDRAWN))

        assets.delete(AssetId("a1"))

        assertEquals(emptyList<Asset>(), assets.all())
        assertEquals(listOf(recordOf("r1"), recordOf("r2", kind = TransferKind.WITHDRAWN)), records.all())
    }

    @Test
    fun deleteAllIsTheReplaceWipe() = runTest {
        records.append(recordOf("r1"))

        records.deleteAll()

        assertEquals(emptyList<TransferRecord>(), records.all())
        assertEquals(emptySet<AssetId>(), records.heldIds())
    }
}
