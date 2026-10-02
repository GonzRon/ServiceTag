package com.loosecannon.servicetag.data.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import com.loosecannon.servicetag.data.room.entities.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    /** See [AssetDao.upsert] for why this is not `@Upsert`. */
    @Transaction
    suspend fun upsert(e: AttachmentEntity) {
        if (update(e) == 0) insert(e)
    }

    @Update suspend fun update(e: AttachmentEntity): Int

    @Insert suspend fun insert(e: AttachmentEntity)

    @Query("SELECT * FROM attachment WHERE id = :id")
    suspend fun byId(id: String): AttachmentEntity?

    @Query("SELECT * FROM attachment WHERE asset_id = :assetId ORDER BY display_name COLLATE NOCASE")
    suspend fun forAsset(assetId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachment WHERE event_id = :eventId ORDER BY display_name COLLATE NOCASE")
    suspend fun forEvent(eventId: String): List<AttachmentEntity>

    /** Schema v20 (#69): a SupplyItem's own files, in [forAsset]'s order. */
    @Query("SELECT * FROM attachment WHERE supply_item_id = :supplyItemId ORDER BY display_name COLLATE NOCASE")
    suspend fun forSupplyItem(supplyItemId: String): List<AttachmentEntity>

    /** Schema v20 (#69): an installed component's own files, in [forAsset]'s order. */
    @Query("SELECT * FROM attachment WHERE installed_component_id = :installedComponentId ORDER BY display_name COLLATE NOCASE")
    suspend fun forInstalledComponent(installedComponentId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachment ORDER BY created_at")
    suspend fun all(): List<AttachmentEntity>

    @Query("SELECT COUNT(*) FROM attachment")
    suspend fun count(): Int

    @Query("SELECT * FROM attachment WHERE asset_id = :assetId ORDER BY display_name COLLATE NOCASE")
    fun observeForAsset(assetId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachment WHERE event_id = :eventId ORDER BY display_name COLLATE NOCASE")
    fun observeForEvent(eventId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachment WHERE supply_item_id = :supplyItemId ORDER BY display_name COLLATE NOCASE")
    fun observeForSupplyItem(supplyItemId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachment WHERE installed_component_id = :installedComponentId ORDER BY display_name COLLATE NOCASE")
    fun observeForInstalledComponent(installedComponentId: String): Flow<List<AttachmentEntity>>

    @Query("DELETE FROM attachment WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM attachment")
    suspend fun deleteAll()
}
