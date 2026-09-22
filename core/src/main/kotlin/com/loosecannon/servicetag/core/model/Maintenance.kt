package com.loosecannon.servicetag.core.model

// The 1.2 maintenance domain's **shapes**, and nothing else: no recurrence arithmetic, no status
// function, no use case. The engine that reads them is B02's, the group and closure rules are
// B03's; what lives here is what the Room mappers, the backup DTOs and the merge planner all have
// to agree on before any of that can be written.
//
// Calendar dates are ISO-8601 `YYYY-MM-DD` **strings**, exactly as `AssetEvent.occurredOn` and
// `Asset.purchaseOn` already are, and exactly as the TEXT columns and the backup DTOs hold them.
// Instants are epoch milliseconds. Meter values are `Double`, as `Measurement.valueNum` is.

/**
 * A group of Assets that one schedule can target.
 *
 * A group is **not** an Asset: it is not in the Asset parent/child tree, has no serial number and
 * never receives an NFC identity. `name` and `description` are descriptive only and are **never**
 * identity — in the domain or in a merge. Archiving is [archivedAt]; there is no delete.
 */
data class MaintenanceGroup(
    val id: GroupId,
    val name: String,
    val description: String,
    val archivedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val members: List<GroupMember>,
)

/**
 * One membership window, a child row of [MaintenanceGroup] with a durable id of its own — as
 * [ProfileField] and [ProfileConsumable] are of [EventProfile], and for the same reason: it
 * survives a backup verbatim rather than being minted again on import.
 *
 * The temporal fields are **append-only**. Removing a member stamps [removedAt]; re-adding the same
 * asset later inserts a *new* row with a new id and a new [addedAt], and never clears an existing
 * [removedAt]. That is what keeps a past occurrence's required set from ever changing, and it is
 * why the unique index is `(group_id, asset_id, added_at)` and not `(group_id, asset_id)`.
 */
data class GroupMember(
    val id: String,
    val assetId: AssetId,
    val sortOrder: Int,
    val addedAt: Long,
    val removedAt: Long?,
)

/** What a schedule is for: exactly one Asset, or exactly one group. Mirrors [TagTarget]. */
sealed interface ScheduleTarget {
    data class AssetTarget(val assetId: AssetId) : ScheduleTarget
    data class GroupTarget(val groupId: GroupId) : ScheduleTarget
}

/**
 * The time side's unit. Named `RecurrenceUnit` rather than `TimeUnit`, which would collide with
 * `java.util.concurrent.TimeUnit` at every use site; the four names on the wire and in the column
 * are unchanged.
 */
enum class RecurrenceUnit { DAY, WEEK, MONTH, YEAR }

/** Whether the series is pinned to its anchor or restarts from each termination. */
enum class TimeBasis { FIXED, COMPLETION }

/** Whether the schedule follows its Asset's season window or ignores it. */
enum class SeasonBehavior { FOLLOW_ASSET, IGNORE }

/** One tap, or the profile form. */
enum class CompletionMode { QUICK, FORM }

/**
 * The schedule's **lifecycle** column — not the derived status word. Status is a pure function of
 * the row plus today and is never stored in any form, which is why the two have different names.
 */
enum class ScheduleStatus { ACTIVE, PAUSED, ARCHIVED }

/** How the latest terminated occurrence ended, or that none has. */
enum class TerminationKind { COMPLETED, CLOSED, NONE }

/** One `schedule_provider` row: a **set** of enabled providers, not an enum column. */
data class ScheduleProviderRow(val provider: String, val enabled: Boolean)

/**
 * The maintenance schedule aggregate root: configuration, plus exactly one override.
 *
 * [postponedDueOn] is the row's **only** override — the snooze is device-local delivery state and
 * is deliberately not here. [seasonReentry] and [seasonReentryOffsetDays] are **stored and never
 * read in 1.2**: they exist so the column does not have to be added later, and no 1.2 code path
 * looks at them.
 *
 * Two shape rules are use-case invariants with tests rather than SQL `CHECK` constraints, because
 * Room cannot declare one on an entity and a CHECK written only into a migration would give
 * upgraded and fresh databases two different schemas for one version: exactly one of the target's
 * two sides is set (which [target] makes unrepresentable in the domain but not on the wire), and at
 * least one of the time and meter sides is present.
 */
data class MaintenanceSchedule(
    val id: ScheduleId,
    val target: ScheduleTarget,
    val title: String,
    val description: String,
    // time side
    val timeInterval: Int?,
    val timeUnit: RecurrenceUnit?,
    val timeBasis: TimeBasis,
    /** Non-null whenever [timeInterval] is: a meter-only schedule has no series to anchor. */
    val anchorOn: String?,
    val leadDays: Int,
    // meter side
    val meterDefinitionId: DefinitionId?,
    val meterInterval: Double?,
    val anchorMeter: Double?,
    val meterLead: Double?,
    // season
    val seasonBehavior: SeasonBehavior,
    val seasonReentry: String?,
    val seasonReentryOffsetDays: Int?,
    // completion
    val completionMode: CompletionMode,
    val profileId: ProfileId?,
    val remindersEnabled: Boolean,
    val status: ScheduleStatus,
    /** The one override: a one-off replacement for the current occurrence's due date. */
    val postponedDueOn: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val providers: List<ScheduleProviderRow>,
)

/**
 * The D-8 closure fact: one append-only row per round the owner explicitly closed, sparse by
 * construction — an ordinary completed round produces none, and a schedule always finished on time
 * carries zero rows for its whole life.
 *
 * There is **no `updatedAt`**, because the row is immutable and a stamp nothing ever moves would be
 * a lie. Nothing issues an UPDATE or a DELETE against the table; the only way a row leaves is the
 * CASCADE when its schedule is deleted.
 */
data class OccurrenceClosure(
    val id: String,
    val scheduleId: ScheduleId,
    /** The occurrence key, the same value a completion event carries. */
    val occurrenceOn: String,
    /** The termination's effective date: what the recurrence advances from. */
    val closedOn: String,
    val createdAt: Long,
)

/**
 * Derived due state, materialised so the dashboard and `/v1/due` can sort on it.
 *
 * Every field is recomputable from configuration, events, closures, membership and today, and the
 * recompute function is the **only** write path into it. It is never exported and never merged, and
 * no status word appears here: [lastTerminationKind] is a fact about history, not a status.
 */
data class ScheduleState(
    val scheduleId: ScheduleId,
    /** The latest member **completion** only — never a round that was closed unfinished. */
    val lastCompletedOn: String?,
    val lastCompletionEventId: EventId?,
    val lastCompletedMeter: Double?,
    val currentMeter: Double?,
    val computedDueMeter: Double?,
    /** The termination's **effective date**, never its occurrence key. */
    val lastTerminationEffectiveOn: String?,
    val lastTerminationKind: TerminationKind,
    val computedDueOn: String?,
    /** The sort key: `postponedDueOn ?: computedDueOn`. */
    val effectiveDueOn: String?,
    val seasonActive: Boolean,
    /** The `T` this state was computed for. */
    val computedForOn: String,
    val computedAt: Long,
)
