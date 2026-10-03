package com.loosecannon.servicetag.data.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import com.loosecannon.servicetag.data.room.entities.SeasonSyncBindingEntity
import kotlinx.coroutines.flow.Flow

/**
 * #16 (C9, C11): the device-local bindings, at most one per asset. There is no delete: a binding leaves only by its
 * asset's or its connection's CASCADE (R16-14). Nothing here is exported, merged or packed.
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
            "applied_at = :appliedAt, created_at = :createdAt, updated_at = :updatedAt " +
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
        createdAt: Long,
        updatedAt: Long,
    ): Int

    @Query("SELECT * FROM season_sync_binding WHERE asset_id = :assetId")
    suspend fun byAsset(assetId: String): SeasonSyncBindingEntity?

    @Query("SELECT * FROM season_sync_binding ORDER BY asset_id")
    suspend fun all(): List<SeasonSyncBindingEntity>

    @Query("SELECT * FROM season_sync_binding WHERE asset_id = :assetId")
    fun observeByAsset(assetId: String): Flow<SeasonSyncBindingEntity?>

    @Query("SELECT EXISTS(SELECT 1 FROM season_sync_binding WHERE enabled = 1)")
    suspend fun anyEnabled(): Boolean
}
