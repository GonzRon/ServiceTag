package com.loosecannon.servicetag.core.model

enum class ValueType { NUMBER, TEXT, BOOLEAN }

enum class DefinitionKind { ENTERED, DERIVED }
enum class DerivedFormula { PERCENT_DROP }          // (a − b) / a × 100; closed; add members only with a consumer
data class DerivedSpec(val formula: DerivedFormula, val sourceA: DefinitionId, val sourceB: DefinitionId)

data class MeasurementDefinition(
    val id: DefinitionId, val assetId: AssetId,
    val key: String,            // slug, unique per asset, e.g. "ph", "engine_hours"
    val label: String, val unit: String,   // unit may be "" (pH has none)
    val valueType: ValueType, val decimals: Int,
    val rangeLow: Double?, val rangeHigh: Double?,   // NUMBER only; both null = no target
    val isMeter: Boolean,       // monotonic counter (hours); informational until Phase 3
    val sortOrder: Int, val archivedAt: Long?, val createdAt: Long, val updatedAt: Long,
    val kind: DefinitionKind = DefinitionKind.ENTERED,
    val derived: DerivedSpec? = null,               // non-null iff kind == DERIVED
)

enum class EventKind { MAINTENANCE, INSPECTION, MEASUREMENT, TREATMENT, INCIDENT, REPLACEMENT,
                       SEASON_START, SEASON_END, NOTE, CUSTOM }

data class ProfileField(val id: String, val definitionId: DefinitionId, val required: Boolean, val sortOrder: Int)
data class ProfileConsumable(val id: String, val name: String, val defaultQuantity: Double?, val unit: String, val sortOrder: Int)
// Child rows carry durable ids of their own (seven tables, one identity rule): they survive backup
// verbatim and 2B edits/reorders them by id. Import never generates replacement ids.

data class EventProfile(
    val id: ProfileId, val assetId: AssetId,
    val name: String,           // "Water test" — the quick-action label
    val eventKind: EventKind, val defaultTitle: String,
    val templateKey: String?,   // provenance only: which seed produced it
    val sortOrder: Int, val archivedAt: Long?, val createdAt: Long, val updatedAt: Long,
    val fields: List<ProfileField>, val consumables: List<ProfileConsumable>,
)

/**
 * All five members are declared at once although 1.2 only ever writes the first three:
 * `enumOrCorrupt` throws `BackupCorrupt` on an unknown name, so a later archive carrying
 * `TODOIST_SYNC` would be unreadable by a 1.2 build unless the format were bumped again. Declaring
 * them now is free, and it is safe because an older build refuses a format-6 archive before it
 * reads a single row.
 */
enum class EventSource { MANUAL, IMPORT, SCHEDULE_QUICK_COMPLETE, TODOIST_SYNC, TELEMETRY }

data class Measurement(
    val id: String, val definitionId: DefinitionId,
    val valueNum: Double?, val valueText: String?,   // exactly one is set, by the definition's type
    val unit: String,           // SNAPSHOT of the definition's unit at entry
    val sortOrder: Int,
)
data class ConsumableUsage(val id: String, val name: String, val quantity: Double, val unit: String, val sortOrder: Int)

data class AssetEvent(              // aggregate root; saved and loaded with its children
    val id: EventId, val assetId: AssetId,
    val kind: EventKind, val title: String,
    val profileId: ProfileId?,
    val occurredOn: String,         // ISO-8601 calendar date "YYYY-MM-DD", required, backdating allowed
    val occurredTime: String?,      // "HH:MM" local, optional
    val tzId: String,               // zone id at entry (audit)
    val notes: String,
    val source: EventSource, val sourceRef: String?,
    val createdAt: Long, val updatedAt: Long,
    val measurements: List<Measurement>, val consumables: List<ConsumableUsage>,
    // 1.2: the occurrence link. `occurrenceOn` is stamped from the schedule's computedDueOn at
    // write time — never the postponed date — and is immutable once written. Together with
    // `scheduleId` and `assetId` it is a unique index, so a repeat completion is refused by the
    // database rather than by a check in code. Both are null on every event that is not a
    // completion, and SQLite treats NULLs as distinct, which is what makes the index inert there.
    val scheduleId: ScheduleId? = null,
    val occurrenceOn: String? = null,
    /** True only for a minimal completion against a FORM schedule: the details are still owed. */
    val detailsPending: Boolean = false,
)

/**
 * Whether a measurement's stored value matches the shape its definition's [ValueType] requires:
 * exactly one of valueNum/valueText set, and BOOLEAN's valueNum constrained to 0.0/1.0. Shared by
 * event validation and the backup round-trip check.
 */
fun Measurement.shapeMatches(type: ValueType): Boolean = when (type) {
    ValueType.NUMBER -> valueNum != null && valueText == null
    ValueType.BOOLEAN -> valueNum in setOf(0.0, 1.0) && valueText == null
    ValueType.TEXT -> !valueText.isNullOrBlank() && valueNum == null
}
