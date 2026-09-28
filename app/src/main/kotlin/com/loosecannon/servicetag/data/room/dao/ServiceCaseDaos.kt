package com.loosecannon.servicetag.data.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import com.loosecannon.servicetag.data.room.entities.ServiceCaseEntity
import com.loosecannon.servicetag.data.room.entities.ServiceCaseEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * #79. A case's header: upsert and query. **No delete** — a case leaves only by its asset's CASCADE, and
 * [deleteAll] is the replace import's wipe. Lists by asset order by `(opened_on DESC, id)`.
 */
@Dao
interface ServiceCaseDao {
    /** See [AssetDao.upsert] for why this is not `@Upsert`. */
    @Transaction
    suspend fun upsert(e: ServiceCaseEntity) {
        if (update(e) == 0) insert(e)
    }

    @Update suspend fun update(e: ServiceCaseEntity): Int

    @Insert suspend fun insert(e: ServiceCaseEntity)

    @Query("SELECT * FROM service_case WHERE id = :id")
    suspend fun byId(id: String): ServiceCaseEntity?

    @Query("SELECT * FROM service_case WHERE asset_id = :assetId ORDER BY opened_on DESC, id")
    suspend fun forAsset(assetId: String): List<ServiceCaseEntity>

    @Query("SELECT * FROM service_case ORDER BY id")
    suspend fun all(): List<ServiceCaseEntity>

    @Query("DELETE FROM service_case")
    suspend fun deleteAll()

    @Query("SELECT * FROM service_case WHERE asset_id = :assetId ORDER BY opened_on DESC, id")
    fun observeForAsset(assetId: String): Flow<List<ServiceCaseEntity>>
}

/**
 * #79. **Insert and query only** (R79-8), the [AssetConditionDao] shape: an entry is an immutable fact.
 * `@Insert` aborts on an id already held — it never replaces a row — and there is no update and no
 * single-row delete anywhere in here; [deleteAll] is the replace import's wipe. Every list orders by
 * `(occurred_on, occurred_time, created_at, id)`, SQLite putting a null time first: the timeline order.
 */
@Dao
interface ServiceCaseEntryDao {
    @Insert suspend fun insert(e: ServiceCaseEntryEntity)

    @Query(
        "SELECT * FROM service_case_entry WHERE case_id = :caseId " +
            "ORDER BY occurred_on, occurred_time, created_at, id",
    )
    suspend fun forCase(caseId: String): List<ServiceCaseEntryEntity>

    @Query("SELECT * FROM service_case_entry ORDER BY occurred_on, occurred_time, created_at, id")
    suspend fun all(): List<ServiceCaseEntryEntity>

    @Query("DELETE FROM service_case_entry")
    suspend fun deleteAll()

    @Query(
        "SELECT * FROM service_case_entry WHERE case_id = :caseId " +
            "ORDER BY occurred_on, occurred_time, created_at, id",
    )
    fun observeForCase(caseId: String): Flow<List<ServiceCaseEntryEntity>>
}
