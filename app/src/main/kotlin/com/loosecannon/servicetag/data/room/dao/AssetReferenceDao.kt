package com.loosecannon.servicetag.data.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import com.loosecannon.servicetag.data.room.entities.AssetReferenceEntity
import kotlinx.coroutines.flow.Flow

/**
 * Schema v7's table. **`upsert` is the only write**: there is no `@Query` here that sets a column
 * on its own, so no caller can move a saved `uri` (I-1) or re-parent a row (I-6) even by accident.
 *
 * [findByUri] is the keyed read behind the duplicate refusal, so it asks `UNIQUE(asset_id, uri)`
 * the question directly rather than scanning [forAsset].
 */
@Dao
interface AssetReferenceDao {
    /** See [AssetDao.upsert] for why this is not `@Upsert`. */
    @Transaction
    suspend fun upsert(e: AssetReferenceEntity) {
        if (update(e) == 0) insert(e)
    }

    @Update suspend fun update(e: AssetReferenceEntity): Int

    @Insert suspend fun insert(e: AssetReferenceEntity)

    @Query("SELECT * FROM asset_reference WHERE id = :id")
    suspend fun byId(id: String): AssetReferenceEntity?

    @Query("SELECT * FROM asset_reference WHERE asset_id = :assetId ORDER BY display_name COLLATE NOCASE, id")
    suspend fun forAsset(assetId: String): List<AssetReferenceEntity>

    @Query("SELECT * FROM asset_reference WHERE asset_id = :assetId AND uri = :uri")
    suspend fun findByUri(assetId: String, uri: String): AssetReferenceEntity?

    @Query("SELECT * FROM asset_reference ORDER BY id")
    suspend fun all(): List<AssetReferenceEntity>

    @Query("SELECT * FROM asset_reference WHERE asset_id = :assetId ORDER BY display_name COLLATE NOCASE, id")
    fun observeForAsset(assetId: String): Flow<List<AssetReferenceEntity>>

    @Query("DELETE FROM asset_reference WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM asset_reference")
    suspend fun deleteAll()
}
