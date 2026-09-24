package com.loosecannon.servicetag.data.room.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

// Schema v6, the maintenance tables. Same rules as the phase-1 and phase-2 tables: column names are
// snake_case, enums are stored as TEXT holding the Kotlin enum name, booleans as INTEGER 0/1, ISO
// dates as TEXT, instants as epoch-millisecond INTEGER, meter values as REAL. Every top-level row
// and every child row carries a durable id of its own, so a backup round-trips it verbatim rather
// than minting a replacement.
//
// **No `CHECK` constraint is declared anywhere here**, and that is deliberate rather than an
// oversight: Room cannot declare one on an `@Entity`, so a CHECK written only into the migration
// would exist on upgraded databases and not on fresh ones — two different schemas for one version,
// against the one-source rule `Migrations.kt` itself states. The two shape rules a CHECK would have
// carried (exactly one of a schedule's two targets; at least one of its two rule sides) are
// use-case invariants with tests instead, as the at-most-one-open membership rule already is —
// a partial unique index is not expressible in Room either.
//
// The unique indices below **are** real constraints, and they are what make idempotence provable
// rather than hoped for: `UNIQUE(group_id, asset_id, added_at)`, `UNIQUE(schedule_id,
// occurrence_on)` on closures, and `UNIQUE(schedule_id, occurrence_on, asset_id)` on `asset_event`.

/**
 * A group of Assets one schedule can target. `name` is indexed for lookup and is **never**
 * identity; archiving is [archivedAt] and there is no delete.
 */
@Entity(
    tableName = "maintenance_group",
    indices = [Index("name")],
)
data class MaintenanceGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * One membership window. `UNIQUE(group_id, asset_id, added_at)` — **not** `(group_id, asset_id)`,
 * which could not represent remove-then-re-add without rewriting the window a past occurrence's
 * required set keys off. `added_at` is never edited and `removed_at` is never cleared; re-adding an
 * asset inserts a new row with a new durable id.
 */
@Entity(
    tableName = "maintenance_group_member",
    foreignKeys = [
        ForeignKey(
            entity = MaintenanceGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["group_id", "asset_id", "added_at"], unique = true),
        Index("group_id"),
        Index("asset_id"),
    ],
)
data class MaintenanceGroupMemberEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "removed_at") val removedAt: Long?,
)

/**
 * The schedule row. Both target columns are nullable because exactly one of them is set, and
 * `anchor_on` is nullable because a meter-only schedule has no series to anchor — the only shape
 * that represents both without a sentinel date.
 *
 * The meter definition is **RESTRICT**: a definition a schedule measures against is not something a
 * settings screen may delete out from under it. The profile is **SET NULL**, as `asset_event`'s
 * already is. `status` is the lifecycle column and never the derived status word, which is computed
 * at read time and stored nowhere.
 *
 * Schema v8 (the 12-step recreate of `MIGRATION_7_8`) replaced the three 1.3 season columns with
 * `service_policy` and `policy_offset_days`, and added `rule_changed_at`: the pin's floor, written
 * only by a create or a rule change (#64). The four foreign keys and four indices are unchanged.
 */
@Entity(
    tableName = "maintenance_schedule",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MaintenanceGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MeasurementDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["meter_definition_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = EventProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["asset_id", "status"]),
        Index(value = ["group_id", "status"]),
        Index("meter_definition_id"),
        Index("profile_id"),
    ],
)
data class MaintenanceScheduleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "asset_id") val assetId: String?,
    @ColumnInfo(name = "group_id") val groupId: String?,
    val title: String,
    val description: String,
    @ColumnInfo(name = "time_interval") val timeInterval: Int?,
    @ColumnInfo(name = "time_unit") val timeUnit: String?,
    @ColumnInfo(name = "time_basis") val timeBasis: String,
    @ColumnInfo(name = "anchor_on") val anchorOn: String?,
    @ColumnInfo(name = "lead_days") val leadDays: Int,
    @ColumnInfo(name = "meter_definition_id") val meterDefinitionId: String?,
    @ColumnInfo(name = "meter_interval") val meterInterval: Double?,
    @ColumnInfo(name = "anchor_meter") val anchorMeter: Double?,
    @ColumnInfo(name = "meter_lead") val meterLead: Double?,
    @ColumnInfo(name = "service_policy") val servicePolicy: String,
    @ColumnInfo(name = "policy_offset_days") val policyOffsetDays: Int?,
    @ColumnInfo(name = "completion_mode") val completionMode: String,
    @ColumnInfo(name = "profile_id") val profileId: String?,
    @ColumnInfo(name = "reminders_enabled") val remindersEnabled: Boolean,
    val status: String,
    @ColumnInfo(name = "postponed_due_on") val postponedDueOn: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "rule_changed_at") val ruleChangedAt: Long,
)

/**
 * The enabled providers for one schedule — a **set**, not an enum column, so a second provider is
 * a row and not a schema change. The composite primary key is the constraint; it also indexes
 * `schedule_id` as its prefix, which is why there is no separate index for the foreign key.
 */
@Entity(
    tableName = "schedule_provider",
    primaryKeys = ["schedule_id", "provider"],
    foreignKeys = [
        ForeignKey(
            entity = MaintenanceScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ScheduleProviderEntity(
    @ColumnInfo(name = "schedule_id") val scheduleId: String,
    val provider: String,
    val enabled: Boolean,
)

/**
 * The closure fact: one append-only row per round the owner explicitly closed, sparse by
 * construction. **Immutable** — there is no `updated_at`, because a stamp nothing ever moves would
 * be a lie, and nothing in the app issues an UPDATE or a DELETE against this table. The only way a
 * row leaves is the CASCADE when its schedule is deleted.
 *
 * `UNIQUE(schedule_id, occurrence_on)` is what refuses a second close of one round, and it is also
 * the row's second identity in a merge, independently of its id.
 */
@Entity(
    tableName = "occurrence_closure",
    foreignKeys = [
        ForeignKey(
            entity = MaintenanceScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["schedule_id", "occurrence_on"], unique = true),
        Index("schedule_id"),
    ],
)
data class OccurrenceClosureEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "schedule_id") val scheduleId: String,
    @ColumnInfo(name = "occurrence_on") val occurrenceOn: String,
    @ColumnInfo(name = "closed_on") val closedOn: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Derived due state, one row per schedule, keyed by the schedule itself. Every column is
 * recomputable, the recompute function is the only write path into it, and it is **never exported**
 * and never merged. `actionable_due_on` is indexed because it is the sort key (schema v8; it was
 * `effective_due_on` through v7). `MIGRATION_7_8` recreates the table empty: it fills on the next
 * recompute.
 */
@Entity(
    tableName = "schedule_state",
    foreignKeys = [
        ForeignKey(
            entity = MaintenanceScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("actionable_due_on")],
)
data class ScheduleStateEntity(
    @PrimaryKey @ColumnInfo(name = "schedule_id") val scheduleId: String,
    @ColumnInfo(name = "last_completed_on") val lastCompletedOn: String?,
    @ColumnInfo(name = "last_completion_event_id") val lastCompletionEventId: String?,
    @ColumnInfo(name = "last_completed_meter") val lastCompletedMeter: Double?,
    @ColumnInfo(name = "current_meter") val currentMeter: Double?,
    @ColumnInfo(name = "computed_due_meter") val computedDueMeter: Double?,
    @ColumnInfo(name = "last_termination_effective_on") val lastTerminationEffectiveOn: String?,
    @ColumnInfo(name = "last_termination_kind") val lastTerminationKind: String,
    @ColumnInfo(name = "computed_due_on") val computedDueOn: String?,
    @ColumnInfo(name = "effective_due_on") val effectiveDueOn: String?,
    @ColumnInfo(name = "policy_phase") val policyPhase: String,
    @ColumnInfo(name = "actionable_due_on") val actionableDueOn: String?,
    @ColumnInfo(name = "policy_reason") val policyReason: String,
    val quiet: Boolean,
    @ColumnInfo(name = "computed_for_on") val computedForOn: String,
    @ColumnInfo(name = "computed_at") val computedAt: Long,
)

/**
 * Device-local notification bookkeeping: **never exported, never merged**. Nothing in it is
 * canonical, losing it costs at most one repeated notification, and the delivery path must behave
 * correctly — at worst noisily — when it is empty. The nonce is persisted rather than held in
 * process so a notification action still works after the process dies.
 */
@Entity(
    tableName = "schedule_local_delivery",
    foreignKeys = [
        ForeignKey(
            entity = MaintenanceScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ScheduleLocalDeliveryEntity(
    @PrimaryKey @ColumnInfo(name = "schedule_id") val scheduleId: String,
    @ColumnInfo(name = "snoozed_until_at") val snoozedUntilAt: Long?,
    @ColumnInfo(name = "last_notified_at") val lastNotifiedAt: Long?,
    @ColumnInfo(name = "first_entry_seen") val firstEntrySeen: Boolean,
    @ColumnInfo(name = "action_nonce") val actionNonce: String?,
    @ColumnInfo(name = "nonce_issued_at") val nonceIssuedAt: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
