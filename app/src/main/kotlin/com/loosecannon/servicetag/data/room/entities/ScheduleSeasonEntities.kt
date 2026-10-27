package com.loosecannon.servicetag.data.room.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Schema v22 (#125, U-C1): one row of a schedule's own season — START or END on the day it was applied. The asset
 * activation's columns without `event_id` (a schedule's season writes no journal entry). Immutable, insert and query
 * only; it leaves only by its schedule's CASCADE, so an unlink keeps it as history. It travels in backups and packs
 * with its schedule; no asset-level reader names it (constraint 3).
 */
@Entity(
    tableName = "schedule_season_activation",
    foreignKeys = [
        ForeignKey(
            entity = MaintenanceScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("schedule_id")],
)
data class ScheduleSeasonActivationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "schedule_id") val scheduleId: String,
    val action: String,
    @ColumnInfo(name = "occurred_on") val occurredOn: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Schema v22 (#125, U-C1): one schedule's link to one Home Assistant `input_boolean` — #16's [SeasonSyncBindingEntity]
 * with the **schedule** as the key in the asset's place, so two schedules of one asset each hold one; every other column
 * has #16's name, type and meaning. **Device-local, never exported, merged or packed.** Both foreign keys CASCADE: the
 * schedule's delete takes its binding, and the connection's (Forget) takes every binding on it, which leaves the
 * schedule on its own season, stranded (constraint 6). `connection_id` is indexed for its key.
 */
@Entity(
    tableName = "schedule_season_sync_binding",
    foreignKeys = [
        ForeignKey(
            entity = MaintenanceScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = HaConnectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["connection_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("connection_id")],
)
data class ScheduleSeasonSyncBindingEntity(
    @PrimaryKey @ColumnInfo(name = "schedule_id") val scheduleId: String,
    @ColumnInfo(name = "connection_id") val connectionId: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val mode: String,
    val enabled: Boolean,
    val revision: Long,
    @ColumnInfo(name = "observed_state") val observedState: String?,
    @ColumnInfo(name = "observed_changed_at") val observedChangedAt: String?,
    @ColumnInfo(name = "last_success_at") val lastSuccessAt: Long?,
    @ColumnInfo(name = "last_attempt_at") val lastAttemptAt: Long?,
    @ColumnInfo(name = "error_kind") val errorKind: String?,
    @ColumnInfo(name = "error_detail") val errorDetail: String?,
    @ColumnInfo(name = "error_at") val errorAt: Long?,
    @ColumnInfo(name = "applied_action") val appliedAction: String?,
    @ColumnInfo(name = "applied_on") val appliedOn: String?,
    @ColumnInfo(name = "applied_at") val appliedAt: Long?,
    @ColumnInfo(name = "last_applied_source") val lastAppliedSource: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
