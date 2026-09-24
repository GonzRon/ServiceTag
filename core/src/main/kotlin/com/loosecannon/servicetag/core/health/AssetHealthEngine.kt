package com.loosecannon.servicetag.core.health

import com.loosecannon.servicetag.core.journal.EventChronology
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.schedule.PolicyInputs
import com.loosecannon.servicetag.core.schedule.SeasonContext
import com.loosecannon.servicetag.core.schedule.ServicePolicyEngine
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A MAINTENANCE_OVERDUE subject's linked schedule as the engine reads it: the row, for its target,
 * lifecycle, time rule and title, and [inputs], the occurrence as the policy sees it
 * (`ScheduleRecompute.policyInputsOf` over the schedule's `readState`). [inputs] is the engine's
 * **only** view of the occurrence — its raw due, its postponement and the day it opened.
 */
data class LinkedSchedule(val schedule: MaintenanceSchedule, val inputs: PolicyInputs)

/** Why a subject has no value (spec §6.3, §6.4, §6.8, §7.1). Never stored; never a 100. */
enum class NotTrackedReason {
    /** AGE: no REPLACEMENT event qualifies. */
    NO_REPLACEMENT,

    /** AGE: the baseline quick action is gone, so no filter is left to apply (inv. 113). */
    PROFILE_REMOVED,

    /** MAINTENANCE_OVERDUE: a MEDIUM subject whose IN_SERVICE schedule is dormant today (inv. 114). */
    OUT_OF_SEASON,

    /** MAINTENANCE_OVERDUE: the linked schedule is paused, and a pause has no dated history. */
    SCHEDULE_PAUSED,

    /** MAINTENANCE_OVERDUE: no link, or one to a group, to another asset or to a schedule with no time rule. */
    LINK_INVALID,

    /** MAINTENANCE_OVERDUE: the linked schedule is archived (merge-only); there is no driver line for it. */
    SCHEDULE_ARCHIVED,
}

/** One subject's value: a score in a band, or NOT TRACKED with its reason. */
sealed interface SubjectValue {
    /**
     * [trackedDays] is the `x` behind [score] — the age in days for AGE, the counted late days for
     * MAINTENANCE_OVERDUE. An aggregate has no `x` of its own, so on [AssetHealthResult.aggregate]
     * it is always null.
     */
    data class Scored(val score: Int, val band: HealthBand, val trackedDays: Long?) : SubjectValue

    data class NotTracked(val reason: NotTrackedReason) : SubjectValue
}

/**
 * The **data** of a driver line (spec §6.6); the words are drawn from it elsewhere, never here. Each
 * member names the one string it feeds.
 */
sealed interface DriverLine {
    /** S99: the AGE baseline's date and the days since it. */
    data class Replaced(val on: LocalDate, val ageDays: Long) : DriverLine

    /** S100. */
    data object NoReplacement : DriverLine

    /** S101. */
    data object ProfileRemoved : DriverLine

    /** S102: the linked schedule's title and the counted late days. */
    data class Overdue(val title: String, val days: Long) : DriverLine

    /** S104: shown under [Overdue] while the counted days are within `t1`. */
    data class Grace(val days: Int) : DriverLine

    /** S103. */
    data class UpToDate(val title: String) : DriverLine

    /** S143: the occurrence is postponed to [to] and is not late against its actionable date. */
    data class Postponed(val title: String, val to: LocalDate) : DriverLine

    /** S105. */
    data object NotTrackedOutOfSeason : DriverLine

    /** S106. */
    data object NotTrackedPaused : DriverLine

    /** S142. */
    data object NotTrackedLink : DriverLine
}

/** One non-archived subject, its value and its driver lines, in the order they are read. */
data class SubjectHealth(val subject: HealthSubject, val value: SubjectValue, val lines: List<DriverLine>)

/**
 * An asset's health for one day.
 *
 * [subjects] are the non-archived subjects in `(sortOrder, id)` order. [aggregate] is null when
 * nothing contributes — NOT TRACKED, never a 100. [fallback] is true when TRACK_ONE's primary is
 * missing or archived and WORST was taken instead (S138), and **only when WORST found an aggregate**:
 * with nothing to show there is no worst subject to name, so a NOT TRACKED result never carries it.
 * [critical] lists **every** CRITICAL contributor whatever the aggregation, so nothing hides behind
 * an average (inv. 119).
 */
data class AssetHealthResult(
    val subjects: List<SubjectHealth>,
    val aggregate: SubjectValue.Scored?,
    val fallback: Boolean,
    val critical: List<SubjectHealth>,
)

/**
 * Asset health (spec §6, §7; master plan §10.2): a **pure function** of the asset's configuration,
 * its history and `T`, computed at read time and stored nowhere (inv. 82, 111).
 *
 * It holds no port and writes nothing. Schedules reach it only as [LinkedSchedule], read through the
 * policy engine, so the clock follows the actionable date and never a raw one; the postponement
 * reaches it only as `P` inside [PolicyInputs], and the snooze — device-local delivery state — has
 * no way in at all (O-6, inv. 131). [profileExists] is a function rather than a repository for the
 * same reason: the caller answers it from the asset's profiles.
 */
object AssetHealthEngine {

    fun evaluate(
        asset: Asset,
        subjects: List<HealthSubject>,
        links: Map<ScheduleId, LinkedSchedule>,
        events: List<AssetEvent>,
        profileExists: (ProfileId) -> Boolean,
        season: SeasonContext?,
        today: LocalDate,
    ): AssetHealthResult {
        val live = subjects
            .filter { it.archivedAt == null }
            .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
            .map { subject ->
                when (subject.driver) {
                    HealthDriver.AGE -> age(asset, subject, events, profileExists, today)
                    HealthDriver.MAINTENANCE_OVERDUE -> maintenanceOverdue(asset, subject, links, season, today)
                }
            }
        val contributors = live.filter { it.value is SubjectValue.Scored }
        val (aggregate, fallback) = aggregate(asset, subjects, live, contributors)
        return AssetHealthResult(
            subjects = live,
            aggregate = aggregate,
            fallback = fallback,
            critical = contributors.filter { it.scored().band == HealthBand.CRITICAL },
        )
    }

    /**
     * AGE (spec §6.2, §6.4): `x` is the days from the latest REPLACEMENT on the asset — by
     * [EventChronology], logged with the baseline quick action when one is named — to `T`. Season,
     * policy and pause play no part. A named quick action that no longer exists leaves the subject
     * NOT TRACKED; it is **never** read as "any replacement" (inv. 113).
     */
    private fun age(
        asset: Asset,
        subject: HealthSubject,
        events: List<AssetEvent>,
        profileExists: (ProfileId) -> Boolean,
        today: LocalDate,
    ): SubjectHealth {
        val profile = subject.baselineProfileId
        if (profile != null && !profileExists(profile)) {
            return SubjectHealth(subject, SubjectValue.NotTracked(NotTrackedReason.PROFILE_REMOVED), listOf(DriverLine.ProfileRemoved))
        }
        val baseline = events
            .filter { it.assetId == asset.id && it.kind == EventKind.REPLACEMENT }
            .filter { profile == null || it.profileId == profile }
            .maxWithOrNull(EventChronology)
            ?: return SubjectHealth(subject, SubjectValue.NotTracked(NotTrackedReason.NO_REPLACEMENT), listOf(DriverLine.NoReplacement))
        val replacedOn = LocalDate.parse(baseline.occurredOn)
        val x = ChronoUnit.DAYS.between(replacedOn, today)
        return SubjectHealth(subject, scored(subject, x), listOf(DriverLine.Replaced(replacedOn, x)))
    }

    /**
     * MAINTENANCE_OVERDUE (spec §6.2, §7.1): NOT TRACKED first, in the order an invalid link, an
     * archived schedule, a paused one, then a MEDIUM subject whose IN_SERVICE schedule is dormant
     * today; otherwise `x` is [HealthClock.countedDays].
     */
    private fun maintenanceOverdue(
        asset: Asset,
        subject: HealthSubject,
        links: Map<ScheduleId, LinkedSchedule>,
        season: SeasonContext?,
        today: LocalDate,
    ): SubjectHealth {
        val link = subject.scheduleId?.let { links[it] }
        if (link == null || !link.isValidFor(asset)) return notTracked(subject, NotTrackedReason.LINK_INVALID, DriverLine.NotTrackedLink)
        val schedule = link.schedule
        when (schedule.status) {
            ScheduleStatus.ARCHIVED -> return notTracked(subject, NotTrackedReason.SCHEDULE_ARCHIVED, line = null)
            ScheduleStatus.PAUSED -> return notTracked(subject, NotTrackedReason.SCHEDULE_PAUSED, DriverLine.NotTrackedPaused)
            ScheduleStatus.ACTIVE -> Unit
        }
        val now = ServicePolicyEngine.evaluate(link.inputs, season, at = today)
        if (subject.kind == HealthSubjectKind.MEDIUM && link.inputs.policy.isInService() && now.phase == PolicyPhase.DORMANT) {
            return notTracked(subject, NotTrackedReason.OUT_OF_SEASON, DriverLine.NotTrackedOutOfSeason)
        }

        val x = HealthClock.countedDays(link.inputs, season, subject.kind, today)
        val postponedTo = link.inputs.postponedDueOn
        val lateToday = now.actionableOn?.let { today > it } == true
        val lines = when {
            x > 0 && x <= subject.nominalUntilDays ->
                listOf(DriverLine.Overdue(schedule.title, x), DriverLine.Grace(subject.nominalUntilDays))
            x > 0 -> listOf(DriverLine.Overdue(schedule.title, x))
            postponedTo != null && !lateToday -> listOf(DriverLine.Postponed(schedule.title, postponedTo))
            else -> listOf(DriverLine.UpToDate(schedule.title))
        }
        return SubjectHealth(subject, scored(subject, x), lines)
    }

    /** Targets this asset, and has a time rule for the clock to count against. */
    private fun LinkedSchedule.isValidFor(asset: Asset): Boolean =
        schedule.target == ScheduleTarget.AssetTarget(asset.id) &&
            schedule.timeInterval != null && schedule.timeUnit != null && schedule.anchorOn != null

    private fun notTracked(subject: HealthSubject, reason: NotTrackedReason, line: DriverLine?): SubjectHealth =
        SubjectHealth(subject, SubjectValue.NotTracked(reason), listOfNotNull(line))

    private fun scored(subject: HealthSubject, x: Long): SubjectValue.Scored {
        val score = HealthScore.score(x, subject.nominalUntilDays, subject.warningFromDays, subject.criticalFromDays)
        return SubjectValue.Scored(score, HealthScore.band(score), x)
    }

    private fun SubjectHealth.scored(): SubjectValue.Scored = value as SubjectValue.Scored

    /**
     * Spec §6.5, over the non-archived subjects **with a value** — an untracked subject is left out,
     * never counted as 100 (inv. 118). WORST is the minimum; TRACK_ONE the primary's value, or NOT
     * TRACKED when the primary has none, or WORST when the primary is missing or archived — flagged
     * as a fallback only when WORST found an aggregate, since S138 says the worst subject is shown;
     * AVERAGE and WEIGHTED are floored, so an aggregate is never rounded into a better band. No
     * contributor is NOT TRACKED, with no fallback.
     */
    private fun aggregate(
        asset: Asset,
        all: List<HealthSubject>,
        live: List<SubjectHealth>,
        contributors: List<SubjectHealth>,
    ): Pair<SubjectValue.Scored?, Boolean> {
        val scores = contributors.map { it.scored().score }
        fun worst(): SubjectValue.Scored? = scores.minOrNull()?.let(::aggregateOf)
        return when (asset.healthAggregation) {
            HealthAggregation.WORST -> worst() to false
            HealthAggregation.TRACK_ONE -> {
                val primaryId = asset.healthPrimarySubjectId
                val primary = all.firstOrNull { it.id == primaryId }
                if (primary == null || primary.archivedAt != null) {
                    worst().let { it to (it != null) }
                } else {
                    val value = live.first { it.subject.id == primary.id }.value
                    (value as? SubjectValue.Scored)?.let { aggregateOf(it.score) } to false
                }
            }
            HealthAggregation.AVERAGE ->
                (if (scores.isEmpty()) null else aggregateOf(Math.floorDiv(scores.sum(), scores.size))) to false
            HealthAggregation.WEIGHTED -> {
                val totalWeight = contributors.sumOf { it.subject.weight.toLong() }
                val weighted = contributors.sumOf { it.subject.weight.toLong() * it.scored().score }
                (if (contributors.isEmpty()) null else aggregateOf(Math.floorDiv(weighted, totalWeight).toInt())) to false
            }
        }
    }

    private fun aggregateOf(score: Int): SubjectValue.Scored = SubjectValue.Scored(score, HealthScore.band(score), null)
}
