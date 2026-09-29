package com.loosecannon.servicetag.data.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.loosecannon.servicetag.data.room.entities.AssetSuccessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * #86. The successions: insert and query only. **No update and no delete of a row** — [insert] aborts on an id, a
 * predecessor or a successor already held, a row leaves only by an endpoint's CASCADE, and [deleteAll] is the replace
 * import's wipe. Every list is by id.
 */
@Dao
interface AssetSuccessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(e: AssetSuccessionEntity)

    @Query("SELECT * FROM asset_succession ORDER BY id")
    suspend fun all(): List<AssetSuccessionEntity>

    @Query("SELECT * FROM asset_succession WHERE predecessor_asset_id = :assetId")
    suspend fun byPredecessor(assetId: String): AssetSuccessionEntity?

    @Query("SELECT * FROM asset_succession WHERE successor_asset_id = :assetId")
    suspend fun bySuccessor(assetId: String): AssetSuccessionEntity?

    @Query("DELETE FROM asset_succession")
    suspend fun deleteAll()

    @Query(
        "SELECT * FROM asset_succession WHERE predecessor_asset_id = :assetId OR successor_asset_id = :assetId ORDER BY id",
    )
    fun observeForAsset(assetId: String): Flow<List<AssetSuccessionEntity>>
}
