package com.loosecannon.servicetag.core.transfer

import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.testing.transferOf
import com.loosecannon.servicetag.core.transfer.TransferFixtures.HEATER
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

/**
 * #77 (C1, AC 2, AC 18) — every list an archive carries has a transfer class, and the class decides:
 * a list nobody classified fails here, so a future table needs a decision before it can ship.
 */
class TransferTableClassificationTest {

    /** The archive's own list names, read from its serializer: no list can be added and forgotten. */
    @Test
    fun everyBackupDataListIsClassified() {
        val lists = (0 until BackupData.serializer().descriptor.elementsCount)
            .map { BackupData.serializer().descriptor.getElementName(it) }
            .toSet()

        assertEquals(lists, TransferTables.CLASSES.keys, "every BackupData list, and nothing else, is classified")
        assertEquals(TransferTableClass.SENDER_ONLY, TransferTables.CLASSES["assetLoans"])
        assertEquals(TransferTableClass.TOMBSTONE, TransferTables.CLASSES["externalLinks"])
        assertEquals(TransferTableClass.CROSS_ASSET, TransferTables.CLASSES["maintenanceGroups"])
        assertEquals(TransferTableClass.GLOBAL_IN_USE, TransferTables.CLASSES["assetCategories"])
    }

    /** R77-6: a returned loan is the sender's history, and it stays with the sender. */
    @Test
    fun loansNeverTravel() {
        val estate = TransferFixtures.estate()
        check(estate.assetLoans.any { it.assetId == HEATER && it.returnedOn != null })

        val selected = assertIs<TransferSelection.Selected>(TransferGraph.select(estate, listOf(AssetId(HEATER))))

        assertEquals(emptyList(), selected.data.assetLoans)
    }

    /** The 2.6 link naming the heater and the tag on it are tombstones: neither travels. */
    @Test
    fun linkTombstonesNeverTravel() {
        val estate = TransferFixtures.estate()
        check(estate.externalLinks.single().assetId == HEATER)

        val selected = assertIs<TransferSelection.Selected>(TransferGraph.select(estate, listOf(AssetId(HEATER))))

        assertEquals(emptyList(), selected.data.externalLinks)
        assertEquals(emptyList(), selected.data.nfcTags.filter { it.linkId != null })
        assertEquals(listOf("t1", "t2", "t6"), selected.data.nfcTags.map { it.id })
    }

    /**
     * #77 (B2a, C1): the transfer records are the sender's own facts — SENDER_ONLY, like the loans — so a pack
     * never carries one, and `retain` keeps every one (C9 exports them all).
     */
    @Test
    fun transferRecordsNeverTravelAndAlwaysStay() {
        val records = listOf(transferOf("r2", assetId = "a9", packId = "pack-q"), transferOf("r1", assetId = "a8", packId = "pack-p"))
        val estate = TransferFixtures.estate().copy(transferRecords = records.map { it.toDto() })

        assertEquals(TransferTableClass.SENDER_ONLY, TransferTables.CLASSES["transferRecords"])
        val selected = assertIs<TransferSelection.Selected>(TransferGraph.select(estate, listOf(AssetId(HEATER))))
        assertEquals(emptyList(), selected.data.transferRecords)
        val retained = assertIs<TransferRetention.Retained>(TransferGraph.retain(estate, setOf(AssetId(HEATER), AssetId("h2"))))
        assertEquals(estate.transferRecords, retained.data.transferRecords)
    }
}
