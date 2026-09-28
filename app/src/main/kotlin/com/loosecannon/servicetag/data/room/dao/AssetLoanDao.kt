package com.loosecannon.servicetag.data.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import com.loosecannon.servicetag.data.room.entities.AssetLoanEntity
import kotlinx.coroutines.flow.Flow

/**
 * #72. The loans: upsert and query. **No delete** — a loan leaves only by its asset's CASCADE, and
 * [deleteAll] is the replace import's wipe. Lists by asset order by `(lent_on DESC, id)`; every other
 * list by id. The open rows are the ones whose `open_marker` is set.
 */
@Dao
interface AssetLoanDao {
    /**
     * See [AssetDao.upsert] for why this is not `@Upsert`. An `@Insert` of a second open row for one
     * asset aborts on `index_asset_loan_asset_id_open_marker`; an `@Update` of a row being returned
     * clears its marker in place.
     */
    @Transaction
    suspend fun upsert(e: AssetLoanEntity) {
        if (update(e) == 0) insert(e)
    }

    @Update suspend fun update(e: AssetLoanEntity): Int

    @Insert suspend fun insert(e: AssetLoanEntity)

    @Query("SELECT * FROM asset_loan WHERE id = :id")
    suspend fun byId(id: String): AssetLoanEntity?

    @Query("SELECT * FROM asset_loan WHERE asset_id = :assetId ORDER BY lent_on DESC, id")
    suspend fun forAsset(assetId: String): List<AssetLoanEntity>

    @Query("SELECT * FROM asset_loan WHERE asset_id = :assetId AND open_marker IS NOT NULL")
    suspend fun openFor(assetId: String): AssetLoanEntity?

    @Query("SELECT * FROM asset_loan WHERE open_marker IS NOT NULL ORDER BY id")
    suspend fun open(): List<AssetLoanEntity>

    @Query("SELECT * FROM asset_loan ORDER BY id")
    suspend fun all(): List<AssetLoanEntity>

    @Query("DELETE FROM asset_loan")
    suspend fun deleteAll()

    @Query("SELECT * FROM asset_loan WHERE asset_id = :assetId ORDER BY lent_on DESC, id")
    fun observeForAsset(assetId: String): Flow<List<AssetLoanEntity>>

    @Query("SELECT * FROM asset_loan WHERE open_marker IS NOT NULL ORDER BY id")
    fun observeOpen(): Flow<List<AssetLoanEntity>>
}
