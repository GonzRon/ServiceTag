package com.loosecannon.servicetag.data.room.dao

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Relation
import androidx.room3.Transaction
import androidx.room3.Update
import com.loosecannon.servicetag.data.room.entities.InstalledComponentCompositionEntity
import com.loosecannon.servicetag.data.room.entities.InstalledComponentEntity
import kotlinx.coroutines.flow.Flow

/** An installed component row with its composition entries — the aggregate as one read. */
data class InstalledComponentWithComposition(
    @Embedded val row: InstalledComponentEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["component_id"])
    val composition: List<InstalledComponentCompositionEntity>,
)

/**
 * Schema v19's installed components (#47, C6). **Nothing here deletes a row:** a row leaves only by its Asset's
 * CASCADE, and its subtree and entries by theirs. The writes are the aggregate [insert] and [update];
 * [clearComposition] removes a row's entries, never the row, and only inside [update]'s transaction.
 */
@Dao
interface InstalledComponentDao {
    /** The row, then its entries, in one transaction. */
    @Transaction
    suspend fun insert(row: InstalledComponentEntity, composition: List<InstalledComponentCompositionEntity>) {
        insertRow(row)
        composition.forEach { insertEntry(it) }
    }

    /**
     * The row by an SQL `UPDATE` — **never** a REPLACE or a delete-and-reinsert, which would fire the `parent_id`
     * CASCADE and take the row's subtree (N-4; the [AssetDao.upsert] reasoning) — then its entries replaced
     * wholesale: cleared, then each inserted. The ids come from the caller, so a kept entry keeps its identity. A
     * row that is not stored is not inserted: the `UPDATE` matches nothing, and an entry naming it fails its
     * foreign key, rolling the whole call back.
     */
    @Transaction
    suspend fun update(row: InstalledComponentEntity, composition: List<InstalledComponentCompositionEntity>) {
        updateRow(row)
        clearComposition(row.id)
        composition.forEach { insertEntry(it) }
    }

    @Insert suspend fun insertRow(e: InstalledComponentEntity)

    @Update suspend fun updateRow(e: InstalledComponentEntity): Int

    @Insert suspend fun insertEntry(e: InstalledComponentCompositionEntity)

    @Query("DELETE FROM installed_component_composition WHERE component_id = :componentId")
    suspend fun clearComposition(componentId: String)

    @Transaction
    @Query("SELECT * FROM installed_component WHERE id = :id")
    suspend fun byId(id: String): InstalledComponentWithComposition?

    /** An Asset's rows, current and removed, by id. */
    @Transaction
    @Query("SELECT * FROM installed_component WHERE asset_id = :assetId ORDER BY id")
    suspend fun forAsset(assetId: String): List<InstalledComponentWithComposition>

    @Transaction
    @Query("SELECT * FROM installed_component ORDER BY id")
    suspend fun all(): List<InstalledComponentWithComposition>

    /** An Asset's rows, current and removed, live, by id: an entry's change is emitted too. */
    @Transaction
    @Query("SELECT * FROM installed_component WHERE asset_id = :assetId ORDER BY id")
    fun observeForAsset(assetId: String): Flow<List<InstalledComponentWithComposition>>
}
