package com.loosecannon.servicetag.core.model

/** What a health subject tracks: the whole asset, a logical part of it, or a maintained medium. */
enum class HealthSubjectKind { ASSET, PART, MEDIUM }

/** The one thing a subject's clock counts from (spec §6.2). */
enum class HealthDriver { AGE, MAINTENANCE_OVERDUE }

/** How an asset composes its subjects into one health (spec §6.5). */
enum class HealthAggregation { TRACK_ONE, AVERAGE, WEIGHTED, WORST }

/**
 * One health subject: **configuration**, never a stored health value (spec §6.1, §6.7). Health is
 * computed at read time from this row and history; nothing here or anywhere else holds a score, a
 * band or an aggregate.
 *
 * [scheduleId] is a hard link (CASCADE) and drives a `MAINTENANCE_OVERDUE` subject;
 * [baselineProfileId] is a soft link an `AGE` subject counts from. A subject is upserted and
 * archived, never deleted: only its asset's or its schedule's CASCADE removes the row.
 */
data class HealthSubject(
    val id: HealthSubjectId,
    val assetId: AssetId,
    val name: String,
    val kind: HealthSubjectKind,
    val driver: HealthDriver,
    val scheduleId: ScheduleId?,
    val baselineProfileId: ProfileId?,
    val nominalUntilDays: Int,
    val warningFromDays: Int,
    val criticalFromDays: Int,
    val weight: Int,
    val sortOrder: Int,
    val archivedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)
