package com.loosecannon.servicetag.data.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import com.loosecannon.servicetag.data.room.entities.SeasonSyncBindingEntity
import kotlinx.coroutines.flow.Flow

/**
 * #16 (C9, C11): the device-local bindings, at most one per asset. A binding leaves by its asset's or its connection's
 * CASCADE (R16-14), or, since #129, by [delete], Remove link's one row. Nothing here is exported, merged or packed.
 */
@Dao
interface SeasonSyncBindingDao {
    /** A plain insert: a second binding for one asset is refused by the primary key. */
    @Insert suspend fun insert(e: SeasonSyncBindingEntity)

    /**
     * The compare-and-set (C11, C13): one statement writes every column but the key, and only while the stored row is
     * still at [expectedRevision]. Answers the number of rows written, 1 or 0.
     */
    @Query(
        "UPDATE season_sync_binding SET connection_id = :connectionId, entity_id = :entityId, mode = :mode, " +
            "enabled = :enabled, revision = :revision, observed_state = :observedState, " +
            "observed_changed_at = :observedChangedAt, last_success_at = :lastSuccessAt, " +
            "last_attempt_at = :lastAttemptAt, error_kind = :errorKind, error_detail = :errorDetail, " +
            "error_at = :errorAt, applied_action = :appliedAction, applied_on = :appliedOn, " +
            "applied_at = :appliedAt, last_applied_source = :lastAppliedSource, created_at = :createdAt, " +
            "updated_at = :updatedAt " +
            "WHERE asset_id = :assetId AND revision = :expectedRevision",
    )
    suspend fun updateAtRevision(
        assetId: String,
        expectedRevision: Long,
        connectionId: String,
        entityId: String,
        mode: String,
        enabled: Boolean,
        revision: Long,
        observedState: String?,
        observedChangedAt: String?,
        lastSuccessAt: Long?,
        lastAttemptAt: Long?,
        errorKind: String?,
        errorDetail: String?,
        errorAt: Long?,
        appliedAction: String?,
        appliedOn: String?,
        appliedAt: Long?,
        lastAppliedSource: String?,
        createdAt: Long,
        updatedAt: Long,
    ): Int

    @Query("SELECT * FROM season_sync_binding WHERE asset_id = :assetId")
    suspend fun byAsset(assetId: String): SeasonSyncBindingEntity?

    @Query("SELECT * FROM season_sync_binding ORDER BY asset_id")
    suspend fun all(): List<SeasonSyncBindingEntity>

    @Query("SELECT * FROM season_sync_binding WHERE asset_id = :assetId")
    fun observeByAsset(assetId: String): Flow<SeasonSyncBindingEntity?>

    /** #113/#114 (C24, rev 6): every binding, emitted again on each write to the table. */
    @Query("SELECT * FROM season_sync_binding ORDER BY asset_id")
    fun observeAll(): Flow<List<SeasonSyncBindingEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM season_sync_binding WHERE enabled = 1)")
    suspend fun anyEnabled(): Boolean

    /** #129 (L-C2): one asset's binding, no other row; a missing row deletes nothing. */
    @Query("DELETE FROM season_sync_binding WHERE asset_id = :assetId")
    suspend fun delete(assetId: String)
}
