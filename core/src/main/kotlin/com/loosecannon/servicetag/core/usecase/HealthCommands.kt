package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.health.HealthSubjectShape
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository

/**
 * One health subject as the editor or the API states it (spec §6.1; master plan §10.1). There is no
 * asset here: a create names the asset beside the command and an edit keeps the stored one, so a
 * subject cannot change asset (plan decision 34, inv. 120).
 *
 * The three thresholds are nullable **only** so that a missing one is a coded refusal
 * ([HealthProblem.ThresholdsInvalid]) instead of a type error. Nothing defaults them, here or
 * anywhere: a template fills the editor's fields after the owner confirms it, and that is all
 * (inv. 121, Q-1).
 */
data class HealthSubjectCommand(
    val name: String,
    val kind: HealthSubjectKind,
    val driver: HealthDriver,
    val scheduleId: ScheduleId? = null,
    val baselineProfileId: ProfileId? = null,
    val nominalUntilDays: Int?,
    val warningFromDays: Int?,
    val criticalFromDays: Int?,
    val weight: Int = 1,
    val sortOrder: Int? = null,
)

/**
 * How an asset combines its subjects (spec §6.5). [healthPrimarySubjectId] is named exactly with
 * TRACK_ONE, and must then be a non-archived subject of the same asset.
 */
data class HealthPolicyCommand(
    val healthAggregation: HealthAggregation,
    val healthPrimarySubjectId: HealthSubjectId? = null,
)

/**
 * One thing wrong with a subject or policy command. **Every member is a 422** (spec §9.2): the remedy
 * is the body. The members that bound a number or a length carry the bound, so the wire's message can
 * state it (the controller's ruling on I10, plan decision 33).
 */
sealed interface HealthProblem {
    /**
     * `HEALTH_SUBJECT_NAME_REQUIRED`: blank, or longer than the limit, after trimming. The spec has one
     * name code, so an over-long name is this too (plan decision 33); [limit] is what the message states.
     */
    data class NameRequired(val limit: IntRange = SUBJECT_NAME_LENGTH) : HealthProblem

    /** `HEALTH_THRESHOLDS_INVALID`: a threshold missing, or not `0 ≤ t1 < t2 < t3 ≤ 36,500`. */
    data class ThresholdsInvalid(val limit: IntRange = 0..HealthSubjectShape.MAX_THRESHOLD_DAYS) : HealthProblem

    /**
     * `HEALTH_DRIVER_MISMATCH`: an AGE subject naming a schedule, or a MAINTENANCE_OVERDUE subject with
     * no schedule or with a baseline profile.
     */
    data object DriverMismatch : HealthProblem

    /**
     * `FOREIGN_SCHEDULE`: the schedule does not exist, is a group's or another asset's, or is
     * [archived] — the spec's code set is closed, so an archived schedule is this code too, and the
     * message says so (plan decision 45).
     */
    data class ForeignSchedule(val scheduleId: ScheduleId, val archived: Boolean = false) : HealthProblem

    /** `HEALTH_SCHEDULE_NEEDS_A_TIME_RULE`: the schedule is meter-only, and the meter drives no health (Q-2). */
    data class ScheduleNeedsATimeRule(val scheduleId: ScheduleId) : HealthProblem

    /**
     * `PROFILE_NOT_A_REPLACEMENT`: the baseline profile is another asset's, missing, or not a REPLACEMENT
     * quick action — one code for the spec's one bullet (plan decision 13).
     */
    data class ProfileNotAReplacement(val profileId: ProfileId) : HealthProblem

    /** `HEALTH_WEIGHT_OUT_OF_RANGE`: the weight is outside [limit]. */
    data class WeightOutOfRange(val limit: IntRange = HealthSubjectShape.WEIGHTS) : HealthProblem

    /**
     * `HEALTH_PRIMARY_INVALID`: TRACK_ONE without a primary, or with one that is missing, another
     * asset's or archived; or a primary named with any other aggregation.
     */
    data object PrimaryInvalid : HealthProblem
}

/** A subject or policy command was refused; every problem found, collected once. */
class HealthValidation(val problems: List<HealthProblem>) :
    IllegalArgumentException("health configuration rejected: ${problems.joinToString()}")

/** 409 `HEALTH_SCHEDULE_TAKEN`: [scheduleId] already drives [heldBy], a non-archived subject (inv. 120). */
class HealthScheduleTaken(val scheduleId: ScheduleId, val heldBy: HealthSubjectId) :
    IllegalStateException("schedule ${scheduleId.value} already drives subject ${heldBy.value}")

/**
 * 409 `HEALTH_SUBJECT_IS_PRIMARY`: [subjectId] is the subject its asset's TRACK_ONE follows, so it
 * cannot be archived — directly or through a schedule's unlink — until the aggregation changes
 * (spec §6.5; plan decision 14).
 */
class HealthSubjectIsPrimary(val subjectId: HealthSubjectId) :
    IllegalStateException("subject ${subjectId.value} is the subject its asset's health follows")

/** 404 `NO_SUCH_HEALTH_SUBJECT`. */
class NoSuchHealthSubject(id: HealthSubjectId) : IllegalArgumentException("no health subject ${id.value}")

/**
 * 422 `SCHEDULE_DRIVES_HEALTH_SUBJECT` (spec §6.1, D-30; inv. 130): the schedule write would archive
 * the schedule, remove its time rule or move its target while [subjectId] ([name]) depends on it. The
 * remedy is in this body: the same request with `unlinkHealthSubject`, which archives the subject too.
 */
class ScheduleDrivesHealthSubject(val subjectId: HealthSubjectId, val name: String) :
    IllegalArgumentException("the schedule drives health subject ${subjectId.value}")

/** A subject's name, after trimming (spec §6.1). */
val SUBJECT_NAME_LENGTH: IntRange = 1..60

// ------------------------------------------------------------------------------------------------
// The rules, shared by the subject commands, the restore of an archived subject, the settings save
// and the format-8 content check.
// ------------------------------------------------------------------------------------------------

/** The name's problem, if any. [name] is already trimmed. */
internal fun subjectNameProblem(name: String): HealthProblem? =
    if (name.length in SUBJECT_NAME_LENGTH) null else HealthProblem.NameRequired()

/** The thresholds' problem, if any: one missing, or not `0 ≤ t1 < t2 < t3 ≤ 36,500` (no default, inv. 121). */
internal fun thresholdsProblem(t1: Int?, t2: Int?, t3: Int?): HealthProblem? =
    if (t1 != null && t2 != null && t3 != null && HealthSubjectShape.thresholdsValid(t1, t2, t3)) {
        null
    } else {
        HealthProblem.ThresholdsInvalid()
    }

/** The weight's problem, if any. */
internal fun weightProblem(weight: Int): HealthProblem? =
    if (HealthSubjectShape.weightValid(weight)) null else HealthProblem.WeightOutOfRange()

/** Whether [this] schedule has a time side: an interval, a unit and an anchor, as the engine reads it. */
internal fun MaintenanceSchedule.hasTimeRule(): Boolean = timeInterval != null && timeUnit != null && anchorOn != null

/**
 * **The whole link validation** (spec §6.1; master plan §10.1, plan decision 45): a MAINTENANCE_OVERDUE
 * subject names a schedule, carries no baseline profile, and its schedule exists, is aimed at
 * [assetId] itself, is not archived and has a time rule; an AGE subject names no schedule. Create,
 * update **and restore** all ask this, so no non-archived subject ever names an archived, retargeted
 * or rule-less schedule through a local write — the state the engine's NOT TRACKED covers is reached
 * only by a merge (plan decision 17).
 */
internal suspend fun linkProblems(
    assetId: AssetId,
    driver: HealthDriver,
    scheduleId: ScheduleId?,
    baselineProfileId: ProfileId?,
    schedules: ScheduleRepository,
): List<HealthProblem> = when (driver) {
    HealthDriver.AGE -> listOfNotNull(HealthProblem.DriverMismatch.takeIf { scheduleId != null })
    HealthDriver.MAINTENANCE_OVERDUE -> when {
        scheduleId == null || baselineProfileId != null -> listOf(HealthProblem.DriverMismatch)
        else -> listOfNotNull(scheduleProblem(assetId, scheduleId, schedules))
    }
}

private suspend fun scheduleProblem(assetId: AssetId, id: ScheduleId, schedules: ScheduleRepository): HealthProblem? {
    val schedule = schedules.get(id)
    return when {
        schedule == null || schedule.target != ScheduleTarget.AssetTarget(assetId) -> HealthProblem.ForeignSchedule(id)
        schedule.status == ScheduleStatus.ARCHIVED -> HealthProblem.ForeignSchedule(id, archived = true)
        !schedule.hasTimeRule() -> HealthProblem.ScheduleNeedsATimeRule(id)
        else -> null
    }
}

/** An AGE subject's baseline: a REPLACEMENT quick action of [assetId] itself (plan decision 13). */
internal suspend fun baselineProblem(
    assetId: AssetId,
    driver: HealthDriver,
    profileId: ProfileId?,
    profiles: ProfileRepository,
): HealthProblem? {
    if (driver != HealthDriver.AGE || profileId == null) return null
    val profile = profiles.get(profileId)
    val ok = profile != null && profile.assetId == assetId && profile.eventKind == EventKind.REPLACEMENT
    return if (ok) null else HealthProblem.ProfileNotAReplacement(profileId)
}

/**
 * 409 when [scheduleId] already drives a non-archived subject other than [self] (inv. 120). An
 * archived subject holds nothing.
 */
internal suspend fun requireScheduleFree(
    scheduleId: ScheduleId?,
    self: HealthSubjectId?,
    subjects: HealthSubjectRepository,
) {
    scheduleId ?: return
    val holder = subjects.forSchedule(scheduleId).firstOrNull { it.archivedAt == null && it.id != self }
    if (holder != null) throw HealthScheduleTaken(scheduleId, holder.id)
}

/** Whether [subjectId] is the subject [asset]'s TRACK_ONE follows. */
internal fun Asset.followsOnly(subjectId: HealthSubjectId): Boolean =
    healthAggregation == HealthAggregation.TRACK_ONE && healthPrimarySubjectId == subjectId

/**
 * The health policy's problems (spec §6.5): TRACK_ONE needs a primary that is a non-archived subject
 * of [assetId]; any other aggregation takes none. [assetId] is null for an asset not yet created,
 * which has no subject to follow.
 */
internal suspend fun healthPolicyProblems(
    assetId: AssetId?,
    cmd: HealthPolicyCommand,
    subjects: HealthSubjectRepository,
): List<HealthProblem> {
    val primaryId = cmd.healthPrimarySubjectId
    val valid = if (cmd.healthAggregation == HealthAggregation.TRACK_ONE) {
        val primary = primaryId?.let { subjects.get(it) }
        primary != null && assetId != null && primary.assetId == assetId && primary.archivedAt == null
    } else {
        primaryId == null
    }
    return if (valid) emptyList() else listOf(HealthProblem.PrimaryInvalid)
}

/**
 * **The schedule-side link guard** (spec §6.1, D-30, RS-3; inv. 130), for [SaveSchedule] and
 * [ArchiveSchedule]: the non-archived subjects [scheduleId] drives, which the write that is about to
 * archive it, strip its time rule or move its target would strand.
 *
 * Without [unlink] the first of them is refused by name, 422 [ScheduleDrivesHealthSubject]. With it
 * the answer is the list for [unlinkDriven] to archive in the caller's transaction; an empty list —
 * nothing depends on the schedule — makes the flag a no-op.
 */
internal suspend fun guardDriven(
    scheduleId: ScheduleId,
    unlink: Boolean,
    subjects: HealthSubjectRepository,
): List<HealthSubject> {
    val driven = subjects.forSchedule(scheduleId).filter { it.archivedAt == null }
    val first = driven.firstOrNull()
    if (first != null && !unlink) throw ScheduleDrivesHealthSubject(first.id, first.name)
    return driven
}

/**
 * Archives [driven] — the flag's half of the guard — inside the caller's `uow.write`, so the subject
 * and the schedule commit together or not at all. A subject its asset's TRACK_ONE follows is 409
 * [HealthSubjectIsPrimary], checked for every subject before any is written: the remedy is the
 * aggregation, exactly as for a direct archive (plan decision 14).
 */
internal suspend fun unlinkDriven(
    driven: List<HealthSubject>,
    assets: AssetRepository,
    subjects: HealthSubjectRepository,
    now: Long,
) {
    driven.forEach { subject ->
        if (assets.get(subject.assetId)?.followsOnly(subject.id) == true) throw HealthSubjectIsPrimary(subject.id)
    }
    driven.forEach { subjects.upsert(it.copy(archivedAt = now, updatedAt = now)) }
}
