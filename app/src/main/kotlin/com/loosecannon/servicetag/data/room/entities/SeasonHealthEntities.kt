package com.loosecannon.servicetag.data.room.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

// Schema v8's three new tables (spec §3.3, §5.1, §6.1; master plan §3.4). The v6 rules hold: TEXT
// enum names, ISO dates as TEXT, instants as epoch-millisecond INTEGER, no `CHECK` anywhere (Room
// cannot declare one, so the value rules are use-case rules with tests).
//
// **The two fact tables carry no `updated_at`**, because nothing ever moves a row — a correction is
// a new row — and their DAOs are insert and query only. A fact leaves only by its asset's CASCADE.
// Their `event_id` is a **soft link** with no foreign key: a fact never belongs to the event that
// prompted it, and deleting that event leaves the fact exactly as it was (inv. 109).

/** One manual season activation: START or END on a day. Immutable. */
@Entity(
    tableName = "asset_season_activation",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["asset_id", "occurred_on"])],
)
data class AssetSeasonActivationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    val action: String,
    @ColumnInfo(name = "occurred_on") val occurredOn: String,
    @ColumnInfo(name = "event_id") val eventId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * One operational condition fact. Immutable; the current condition is derived from the rows, and
 * no column anywhere stores it. `condition` holds one of three names and never a fourth.
 */
@Entity(
    tableName = "asset_condition",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["asset_id", "occurred_on"])],
)
data class AssetConditionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    val condition: String,
    @ColumnInfo(name = "occurred_on") val occurredOn: String,
    @ColumnInfo(name = "occurred_time") val occurredTime: String?,
    @ColumnInfo(name = "tz_id") val tzId: String,
    val reason: String,
    @ColumnInfo(name = "event_id") val eventId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * One health subject: configuration, never a health value (inv. 111). `schedule_id` is a hard link
 * (CASCADE: a deleted schedule takes its subject); `baseline_profile_id` is a soft link with no
 * foreign key. There is no delete path: archiving is `archived_at`, and the two CASCADEs are the
 * only way a row leaves.
 */
@Entity(
    tableName = "health_subject",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MaintenanceScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("asset_id"), Index("schedule_id")],
)
data class HealthSubjectEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    val name: String,
    val kind: String,
    val driver: String,
    @ColumnInfo(name = "schedule_id") val scheduleId: String?,
    @ColumnInfo(name = "baseline_profile_id") val baselineProfileId: String?,
    @ColumnInfo(name = "nominal_until_days") val nominalUntilDays: Int,
    @ColumnInfo(name = "warning_from_days") val warningFromDays: Int,
    @ColumnInfo(name = "critical_from_days") val criticalFromDays: Int,
    @ColumnInfo(defaultValue = "1") val weight: Int = 1,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
