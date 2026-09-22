package com.loosecannon.servicetag.data.room.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

// Schema v3, spec §7-8. Enums are stored as TEXT holding the Kotlin enum name and booleans as
// INTEGER 0/1 (Room's own `Boolean` mapping), exactly as the phase-1 tables already do. Every
// child row carries a durable id of its own — the seven tables are seven identities, so a backup
// round-trips them verbatim rather than minting replacements.

/**
 * A measurement definition. v3 adds the DERIVED shape: `kind` names it, and a DERIVED row carries
 * `formula` plus the two source definitions it reads. The sources are foreign keys into this same
 * table — the only self-reference in the schema — and they are **RESTRICT**, so a source cannot be
 * deleted while something derives from it. A derived value is never stored: the columns say how to
 * compute it, `:core`'s `Derived` does the computing.
 */
@Entity(
    tableName = "measurement_definition",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MeasurementDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_a_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MeasurementDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_b_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("asset_id"),
        Index(value = ["asset_id", "key"], unique = true),
        Index("source_a_id"),
        Index("source_b_id"),
    ],
)
data class MeasurementDefinitionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    val key: String,
    val label: String,
    val unit: String,
    @ColumnInfo(name = "value_type") val valueType: String,
    val decimals: Int,
    @ColumnInfo(name = "range_low") val rangeLow: Double?,
    @ColumnInfo(name = "range_high") val rangeHigh: Double?,
    @ColumnInfo(name = "is_meter") val isMeter: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "kind") val kind: String,
    val formula: String?,
    @ColumnInfo(name = "source_a_id") val sourceAId: String?,
    @ColumnInfo(name = "source_b_id") val sourceBId: String?,
)

@Entity(
    tableName = "event_profile",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("asset_id")],
)
data class EventProfileEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    val name: String,
    @ColumnInfo(name = "event_kind") val eventKind: String,
    @ColumnInfo(name = "default_title") val defaultTitle: String,
    @ColumnInfo(name = "template_key") val templateKey: String?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "profile_field",
    foreignKeys = [
        ForeignKey(
            entity = EventProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MeasurementDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["definition_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["profile_id", "definition_id"], unique = true),
        Index("definition_id"),
    ],
)
data class ProfileFieldEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "definition_id") val definitionId: String,
    val required: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

@Entity(
    tableName = "profile_consumable",
    foreignKeys = [
        ForeignKey(
            entity = EventProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profile_id")],
)
data class ProfileConsumableEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    val name: String,
    @ColumnInfo(name = "default_quantity") val defaultQuantity: Double?,
    val unit: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

/**
 * v6 gives this table the occurrence link: `schedule_id`, `occurrence_on` and `details_pending`.
 *
 * The schedule key is **SET NULL**, so archiving a schedule keeps the link and deleting one only
 * nulls it — a completion is history and outlives its rule. `UNIQUE(schedule_id, occurrence_on,
 * asset_id)` is what makes occurrence idempotence a database constraint rather than a discipline;
 * SQLite treats NULLs as distinct, so the index is **inert for every event that is not a
 * completion**. The shipped `UNIQUE(source, source_ref)` is deliberately *not* overloaded for
 * occurrence identity — `source_ref` stays free for sync provenance.
 */
@Entity(
    tableName = "asset_event",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = EventProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = MaintenanceScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(
            value = ["asset_id", "occurred_on", "created_at"],
            orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC],
        ),
        Index(value = ["source", "source_ref"], unique = true),
        Index("profile_id"),
        Index(value = ["schedule_id", "occurrence_on", "asset_id"], unique = true),
        Index(
            value = ["schedule_id", "occurred_on"],
            orders = [Index.Order.ASC, Index.Order.DESC],
        ),
    ],
)
data class AssetEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    val kind: String,
    val title: String,
    @ColumnInfo(name = "profile_id") val profileId: String?,
    @ColumnInfo(name = "occurred_on") val occurredOn: String,
    @ColumnInfo(name = "occurred_time") val occurredTime: String?,
    @ColumnInfo(name = "tz_id") val tzId: String,
    val notes: String,
    val source: String,
    @ColumnInfo(name = "source_ref") val sourceRef: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "schedule_id") val scheduleId: String? = null,
    @ColumnInfo(name = "occurrence_on") val occurrenceOn: String? = null,
    @ColumnInfo(name = "details_pending", defaultValue = "0") val detailsPending: Boolean = false,
)

@Entity(
    tableName = "measurement",
    foreignKeys = [
        ForeignKey(
            entity = AssetEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        // RESTRICT, not CASCADE: a definition with readings against it is history, and history is
        // not something a settings screen may delete out from under the journal.
        ForeignKey(
            entity = MeasurementDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["definition_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["definition_id", "event_id"]),
        Index("event_id"),
    ],
)
data class MeasurementEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "definition_id") val definitionId: String,
    @ColumnInfo(name = "value_num") val valueNum: Double?,
    @ColumnInfo(name = "value_text") val valueText: String?,
    val unit: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

@Entity(
    tableName = "consumable_usage",
    foreignKeys = [
        ForeignKey(
            entity = AssetEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("event_id")],
)
data class ConsumableUsageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    val name: String,
    val quantity: Double,
    val unit: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)
