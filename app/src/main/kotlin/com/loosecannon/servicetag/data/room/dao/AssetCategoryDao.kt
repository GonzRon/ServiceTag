package com.loosecannon.servicetag.data.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import com.loosecannon.servicetag.data.room.entities.AssetCategoryEntity
import kotlinx.coroutines.flow.Flow

/** #74's catalog rows. Lists order by key. Nothing here reads `asset`: the catalog is never derived from it. */
@Dao
interface AssetCategoryDao {
    /** See [AssetDao.upsert] for why this is not `@Upsert`. */
    @Transaction
    suspend fun upsert(e: AssetCategoryEntity) {
        if (update(e) == 0) insert(e)
    }

    @Update suspend fun update(e: AssetCategoryEntity): Int

    @Insert suspend fun insert(e: AssetCategoryEntity)

    @Query("SELECT * FROM asset_category WHERE `key` = :key")
    suspend fun byKey(key: String): AssetCategoryEntity?

    @Query("SELECT * FROM asset_category ORDER BY `key`")
    suspend fun all(): List<AssetCategoryEntity>

    @Query("DELETE FROM asset_category WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM asset_category")
    suspend fun deleteAll()

    @Query("SELECT * FROM asset_category ORDER BY `key`")
    fun observeAll(): Flow<List<AssetCategoryEntity>>
}
