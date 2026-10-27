package com.loosecannon.servicetag.data.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import com.loosecannon.servicetag.data.room.entities.ScheduleSeasonActivationEntity
import com.loosecannon.servicetag.data.room.entities.ScheduleSeasonSyncBindingEntity
import kotlinx.coroutines.flow.Flow

/**
 * #125 (U-C1): a schedule's own season rows, **insert and query only**, as [SeasonActivationDao] is for the asset's:
 * a row is an immutable fact and leaves only by its schedule's CASCADE. Every list orders by
 * `(occurred_on, created_at, id)`.
 */
@Dao
interface ScheduleSeasonActivationDao {
    @Insert suspend fun insert(e: ScheduleSeasonActivationEntity)

    @Query(
        "SELECT * FROM schedule_season_activation WHERE schedule_id = :scheduleId " +
            "ORDER BY occurred_on, created_at, id",
    )
    suspend fun forSchedule(scheduleId: String): List<ScheduleSeasonActivationEntity>

    @Query("SELECT * FROM schedule_season_activation ORDER BY occurred_on, created_at, id")
    suspend fun all(): List<ScheduleSeasonActivationEntity>

    @Query(
        "SELECT * FROM schedule_season_activation WHERE schedule_id = :scheduleId " +
            "ORDER BY occurred_on, created_at, id",
    )
    fun observeForSchedule(scheduleId: String): Flow<List<ScheduleSeasonActivationEntity>>
}

/**
 * #125 (U-C1): the device-local schedule bindings, at most one per schedule — #16's [SeasonSyncBindingDao] keyed by the
 * schedule, plus the one-row [delete] the unlink and the archive use. Nothing here is exported, merged or packed, and
 * nothing observes every row: no projector or #116 reader can watch a schedule's link (I-R).
 */
@Dao
interface ScheduleSeasonSyncBindingDao {
    /** A plain insert: a second binding for one schedule is refused by the primary key. */
    @Insert suspend fun insert(e: ScheduleSeasonSyncBindingEntity)

    /**
     * #16's compare-and-set: one statement writes every column but the key, and only while the stored row is still at
     * [expectedRevision]. Answers the number of rows written, 1 or 0.
     */
    @Query(
        "UPDATE schedule_season_sync_binding SET connection_id = :connectionId, entity_id = :entityId, " +
            "mode = :mode, enabled = :enabled, revision = :revision, observed_state = :observedState, " +
            "observed_changed_at = :observedChangedAt, last_success_at = :lastSuccessAt, " +
            "last_attempt_at = :lastAttemptAt, error_kind = :errorKind, error_detail = :errorDetail, " +
            "error_at = :errorAt, applied_action = :appliedAction, applied_on = :appliedOn, " +
            "applied_at = :appliedAt, last_applied_source = :lastAppliedSource, created_at = :createdAt, " +
            "updated_at = :updatedAt " +
            "WHERE schedule_id = :scheduleId AND revision = :expectedRevision",
    )
    suspend fun updateAtRevision(
        scheduleId: String,
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

    @Query("DELETE FROM schedule_season_sync_binding WHERE schedule_id = :scheduleId")
    suspend fun delete(scheduleId: String)

    @Query("SELECT * FROM schedule_season_sync_binding WHERE schedule_id = :scheduleId")
    suspend fun bySchedule(scheduleId: String): ScheduleSeasonSyncBindingEntity?

    @Query("SELECT * FROM schedule_season_sync_binding ORDER BY schedule_id")
    suspend fun all(): List<ScheduleSeasonSyncBindingEntity>

    @Query("SELECT * FROM schedule_season_sync_binding WHERE schedule_id = :scheduleId")
    fun observeBySchedule(scheduleId: String): Flow<ScheduleSeasonSyncBindingEntity?>

    @Query("SELECT EXISTS(SELECT 1 FROM schedule_season_sync_binding WHERE enabled = 1)")
    suspend fun anyEnabled(): Boolean
}
