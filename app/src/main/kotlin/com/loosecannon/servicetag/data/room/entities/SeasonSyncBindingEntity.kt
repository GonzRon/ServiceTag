package com.loosecannon.servicetag.data.room.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Schema v21 (#16, C9; R16-4, R16-10): one asset's link to one Home Assistant on/off entity — **device-local, never
 * exported, merged or packed** (R16-Q-E). The asset is the key, so an asset has at most one binding. Both foreign keys
 * CASCADE: deleting the asset (`DeleteAsset`, and the replace restore's wipe) takes its binding, and deleting the
 * connection takes every binding on it (R16-14); `connection_id` is indexed for its key. The columns are the model's
 * nineteen fields in its order; the enum columns hold the enum's name, `enabled` is 0 or 1, and `revision` moves by one
 * on every write (the conditional update, C11, C13). `last_applied_source` (C33, inside the unreleased v21) says
 * whether Home Assistant or the owner's forced season applied the last change. Whether a binding is active, stopped,
 * waiting for the owner or inert is derived when it is read (C17), never stored.
 */
@Entity(
    tableName = "season_sync_binding",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
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
data class SeasonSyncBindingEntity(
    @PrimaryKey @ColumnInfo(name = "asset_id") val assetId: String,
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
