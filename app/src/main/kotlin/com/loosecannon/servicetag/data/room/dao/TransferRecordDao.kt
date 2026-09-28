package com.loosecannon.servicetag.data.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.loosecannon.servicetag.data.room.entities.TransferRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * #77. The transfer records: insert and query only. **No update and no delete of a row** — [insert] aborts on
 * an id already held, and [deleteAll] is the replace import's wipe. [all] orders by id; [forAsset] by
 * `(at, id)`.
 */
@Dao
interface TransferRecordDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(e: TransferRecordEntity)

    @Query("SELECT * FROM asset_transfer ORDER BY id")
    suspend fun all(): List<TransferRecordEntity>

    @Query("SELECT * FROM asset_transfer WHERE asset_id = :assetId ORDER BY at, id")
    suspend fun forAsset(assetId: String): List<TransferRecordEntity>

    @Query("DELETE FROM asset_transfer")
    suspend fun deleteAll()

    @Query("SELECT * FROM asset_transfer ORDER BY id")
    fun observeAll(): Flow<List<TransferRecordEntity>>
}
