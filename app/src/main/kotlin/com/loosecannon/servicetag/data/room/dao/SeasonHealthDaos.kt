package com.loosecannon.servicetag.data.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import com.loosecannon.servicetag.data.room.entities.AssetConditionEntity
import com.loosecannon.servicetag.data.room.entities.AssetSeasonActivationEntity
import com.loosecannon.servicetag.data.room.entities.HealthSubjectEntity
import kotlinx.coroutines.flow.Flow

/**
 * **Insert and query only** (inv. 89), the [OccurrenceClosureDao] shape: an activation row is an
 * immutable fact. There is no update method, no delete method and no statement that changes a row
 * anywhere in here; a row leaves only by its asset's CASCADE. Every list orders by
 * `(occurred_on, created_at, id)`.
 */
@Dao
interface SeasonActivationDao {
    @Insert suspend fun insert(e: AssetSeasonActivationEntity)

    @Query(
        "SELECT * FROM asset_season_activation WHERE asset_id = :assetId " +
            "ORDER BY occurred_on, created_at, id",
    )
    suspend fun forAsset(assetId: String): List<AssetSeasonActivationEntity>

    @Query("SELECT * FROM asset_season_activation ORDER BY occurred_on, created_at, id")
    suspend fun all(): List<AssetSeasonActivationEntity>

    @Query(
        "SELECT * FROM asset_season_activation WHERE asset_id = :assetId " +
            "ORDER BY occurred_on, created_at, id",
    )
    fun observeForAsset(assetId: String): Flow<List<AssetSeasonActivationEntity>>
}

/**
 * **Insert and query only** (inv. 107): a condition row is an immutable fact, and a correction is a
 * new row. Every list orders by `(occurred_on, occurred_time, created_at, id)` — SQLite's ascending
 * order puts a null `occurred_time` first, which is the rule — so the last row is the current one.
 */
@Dao
interface AssetConditionDao {
    @Insert suspend fun insert(e: AssetConditionEntity)

    @Query(
        "SELECT * FROM asset_condition WHERE asset_id = :assetId " +
            "ORDER BY occurred_on, occurred_time, created_at, id",
    )
    suspend fun forAsset(assetId: String): List<AssetConditionEntity>

    @Query("SELECT * FROM asset_condition ORDER BY occurred_on, occurred_time, created_at, id")
    suspend fun all(): List<AssetConditionEntity>

    @Query(
        "SELECT * FROM asset_condition WHERE asset_id = :assetId " +
            "ORDER BY occurred_on, occurred_time, created_at, id",
    )
    fun observeForAsset(assetId: String): Flow<List<AssetConditionEntity>>
}

/**
 * Health configuration: upsert and query. **No delete** — a subject is archived, and only its
 * asset's or its schedule's CASCADE removes a row. Lists order by `(sort_order, id)`.
 */
@Dao
interface HealthSubjectDao {
    /** See [AssetDao.upsert] for why this is not `@Upsert`. */
    @Transaction
    suspend fun upsert(e: HealthSubjectEntity) {
        if (update(e) == 0) insert(e)
    }

    @Update suspend fun update(e: HealthSubjectEntity): Int

    @Insert suspend fun insert(e: HealthSubjectEntity)

    @Query("SELECT * FROM health_subject WHERE id = :id")
    suspend fun byId(id: String): HealthSubjectEntity?

    @Query("SELECT * FROM health_subject WHERE asset_id = :assetId ORDER BY sort_order, id")
    suspend fun forAsset(assetId: String): List<HealthSubjectEntity>

    @Query("SELECT * FROM health_subject WHERE schedule_id = :scheduleId ORDER BY sort_order, id")
    suspend fun forSchedule(scheduleId: String): List<HealthSubjectEntity>

    @Query("SELECT * FROM health_subject ORDER BY sort_order, id")
    suspend fun all(): List<HealthSubjectEntity>

    @Query("SELECT * FROM health_subject WHERE asset_id = :assetId ORDER BY sort_order, id")
    fun observeForAsset(assetId: String): Flow<List<HealthSubjectEntity>>
}
