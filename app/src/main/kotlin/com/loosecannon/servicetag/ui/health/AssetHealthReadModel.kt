package com.loosecannon.servicetag.ui.health

import com.loosecannon.servicetag.core.condition.ConditionHistory
import com.loosecannon.servicetag.core.health.AssetHealthEngine
import com.loosecannon.servicetag.core.health.AssetHealthResult
import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.health.HealthSubjectShape
import com.loosecannon.servicetag.core.health.LinkedSchedule
import com.loosecannon.servicetag.core.health.NotTrackedReason
import com.loosecannon.servicetag.core.health.SubjectHealth
import com.loosecannon.servicetag.core.health.SubjectValue
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.AssetTree
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.isRetired
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.schedule.ScheduleRecompute
import com.loosecannon.servicetag.core.schedule.SeasonContext
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import java.time.LocalDate
import java.time.ZoneId

/**
 * An asset's **current** condition as every health surface shows it (spec §5.1, §10.1): the word,
 * the day the latest run of that word began ([since]), and the current row's own facts.
 *
 * [since] is `ConditionHistory.since`'s date, so a DOWN re-recorded on a later day still reads
 * "since" the first DOWN of the run. [eventExists] is false when the row names an event that has
 * since been deleted — the soft link dangles, which is never an error and never hides the row — and
 * false when the row names no event at all.
 *
 * There is deliberately **no zone here**. A restored row may carry a zone this device cannot
 * resolve (B06's content pass judges a zone by its form, never against the device), and nothing a
 * health surface shows needs one: the dates are the row's own `YYYY-MM-DD` and the time its `HH:MM`
 * text. A surface that wants the zone reads it from the row as text.
 */
data class ConditionView(
    val condition: OperationalCondition,
    val since: LocalDate,
    val reason: String,
    val occurredOn: LocalDate,
    val occurredTime: String?,
    val eventId: EventId?,
    val eventExists: Boolean,
)

/**
 * One DOWN or DEGRADED **in-service** component, at any depth under the asset whose health is shown
 * (spec §6.5: "nothing hides behind an aggregate"). [occurredOn] is the component's current row's
 * date, as [ConditionView.occurredOn] is.
 */
data class ComponentCondition(
    val assetId: AssetId,
    val name: String,
    val condition: OperationalCondition,
    val reason: String,
    val occurredOn: LocalDate,
)

/**
 * The band of the subject a schedule drives, carried on that schedule's row (master plan §13.1:
 * overdue-driven health "rides its schedule row", spec §10.2). Only a **tracked** subject has one.
 */
data class SubjectBandFact(
    val subjectId: HealthSubjectId,
    val subjectName: String,
    val band: HealthBand,
    val score: Int,
)

/**
 * Everything a health surface shows for one asset, from one read (inv. 119): the engine's
 * [result] beside the asset's current [condition] and its DOWN or DEGRADED in-service
 * [components]. `/v1/assets/{id}/health`, the scan sheet and asset detail all read this, so a
 * DOWN asset's health can never be shown without its condition and a critical subject or a broken
 * component can never be hidden by an aggregate.
 *
 * `result.aggregate` is a `Scored?` whose `trackedDays` is always null (B05): an aggregate has no
 * day count of its own, and no surface renders one for it.
 *
 * [inService] is the asset's own lifecycle — active and not retired — and false for an asset that
 * no longer exists. An asset out of service never opens the scan sheet and is never offered "Mark
 * operational" (the controller's ruling on B07's review, M1); its view still carries everything,
 * because asset detail shows the same lines.
 */
data class AssetHealthView(
    val assetId: AssetId,
    val computedForOn: LocalDate,
    val condition: ConditionView?,
    val aggregation: HealthAggregation,
    val result: AssetHealthResult,
    val components: List<ComponentCondition>,
    val inService: Boolean,
)

/**
 * The one place the health engine's result meets the asset's condition and its components
 * (master plan §13.1, spec §6.5, inv. 119).
 *
 * **It reads and never writes** (inv. 82, 105). A linked schedule's state comes through
 * [RecomputeSchedules.readState], which derives a stale or missing row in memory; nothing here
 * calls a use case, a recompute or an upsert. It reads no snooze either: the device-local
 * `schedule_local_delivery` row is not an input of any kind, so a snooze cannot move a health
 * number (O-6, inv. 131). A postponement can, because it moves the canonical due date the policy
 * reads through `policyInputsOf`.
 *
 * **Malformed subjects are screened, never thrown** (the controller's ruling on B05's concern 3).
 * `AssetHealthEngine.evaluate` works on the whole asset and refuses it outright when any
 * non-archived subject is malformed, so each subject is screened first with B05's own
 * [HealthSubjectShape.problems] — the bounds are not re-implemented here — and only well-formed ones
 * reach the engine. A screened-out subject is still listed, NOT TRACKED for
 * [NotTrackedReason.UNSCORABLE] with **no driver line** (S98 alone). Commands
 * and the format-8 content pass keep such rows out, so this is the belt that keeps one bad merged
 * row from taking a whole screen down.
 */
class AssetHealthReadModel(
    private val assets: AssetRepository,
    private val subjects: HealthSubjectRepository,
    private val schedules: ScheduleRepository,
    private val events: EventRepository,
    private val profiles: ProfileRepository,
    private val activations: SeasonActivationRepository,
    private val conditions: ConditionRepository,
    private val recompute: RecomputeSchedules,
    private val today: Today,
    /** The owner's zone, for `policyInputsOf`'s pin floor — the same seam the recompute reads. */
    private val zone: () -> ZoneId,
) {

    /**
     * One asset's health view. An asset that no longer exists reads as an empty view — no
     * condition, no subject, no component — rather than throwing into a scan or a route.
     *
     * The components are the asset's descendants at **any** depth that are themselves in service
     * (each on its own lifecycle, whatever its parent's), DOWN before DEGRADED, then by name
     * (case-insensitive) and id.
     */
    suspend fun forAsset(assetId: AssetId): AssetHealthView {
        val t = today.localDate()
        val all = assets.all()
        val asset = all.firstOrNull { it.id == assetId }
        return AssetHealthView(
            assetId = assetId,
            computedForOn = t,
            condition = conditionOf(assetId),
            aggregation = asset?.healthAggregation ?: HealthAggregation.WORST,
            result = asset?.let { resultFor(it, t) } ?: NO_HEALTH,
            components = asset?.let { componentsOf(it, all) }.orEmpty(),
            inService = asset?.inService == true,
        )
    }

    /**
     * Every asset's condition history, from one read: the current row and the row that began its
     * run (`ConditionHistory.since`). The attention list and the due projection both read condition
     * here, so every surface agrees about what "current" and "since" mean.
     */
    internal suspend fun conditionHistories(): Map<AssetId, ConditionHistory> =
        conditions.all().groupBy { it.assetId }.mapValues { (_, rows) -> ConditionHistory.of(rows) }

    /**
     * The band of the subject each of [assetId]'s schedules drives, keyed by schedule (master plan
     * §13.1, `DueItem.health`). Only a non-archived **MAINTENANCE_OVERDUE** subject drives a
     * schedule — an AGE subject's clock is the replacement history, whatever a merged row names —
     * and only a tracked one has a band, so a subject NOT TRACKED (its schedule paused, archived or
     * rule-less) leaves its schedule with none. Should a merge have left two live subjects on one
     * schedule, the first by `(sortOrder, id)` is the one it drives.
     */
    suspend fun bandsBySchedule(assetId: AssetId): Map<ScheduleId, SubjectBandFact> {
        val asset = assets.get(assetId) ?: return emptyMap()
        return resultFor(asset, today.localDate()).subjects
            .filter { it.subject.driver == HealthDriver.MAINTENANCE_OVERDUE && it.subject.scheduleId != null }
            .groupBy { it.subject.scheduleId!! }
            .mapNotNull { (scheduleId, driving) -> driving.first().bandFact()?.let { scheduleId to it } }
            .toMap()
    }

    /**
     * The engine's answer for [asset] on [t], with malformed subjects screened out first and listed
     * back as NOT TRACKED. Shared with the attention list, so every surface reads one computation.
     */
    internal suspend fun resultFor(asset: Asset, t: LocalDate): AssetHealthResult {
        val own = subjects.forAsset(asset.id)
        val live = own.filter { it.archivedAt == null }
        if (live.isEmpty()) return NO_HEALTH

        val unscorable = live.filter { HealthSubjectShape.problems(it).isNotEmpty() }
        val scorable = own - unscorable.toSet()
        val season = SeasonContext.of(
            asset.seasonInputs(if (asset.seasonMode == SeasonMode.MANUAL) activations.forAsset(asset.id) else emptyList()),
        )
        val profileIds = profiles.forAsset(asset.id).map { it.id }.toSet()
        val engine = AssetHealthEngine.evaluate(
            asset = asset,
            subjects = scorable,
            links = linksOf(scorable),
            events = events.forAsset(asset.id),
            profileExists = { it in profileIds },
            season = season,
            today = t,
        )
        if (unscorable.isEmpty()) return engine

        // A TRACK_ONE primary that is screened out still exists, and it is untracked: plan decision
        // 18 answers NOT TRACKED with no fallback. The engine, not having seen it, would read it as
        // missing and fall back to WORST, so that one case is answered here.
        val primaryUnscorable = asset.healthAggregation == HealthAggregation.TRACK_ONE &&
            unscorable.any { it.id == asset.healthPrimarySubjectId }
        return engine.copy(
            subjects = (engine.subjects + unscorable.map(::unscorableRow)).sortedWith(SUBJECT_ORDER),
            aggregate = if (primaryUnscorable) null else engine.aggregate,
            fallback = if (primaryUnscorable) false else engine.fallback,
        )
    }

    /** Every schedule a scorable subject names, with its policy inputs read for today. */
    private suspend fun linksOf(scorable: List<HealthSubject>): Map<ScheduleId, LinkedSchedule> =
        scorable.filter { it.archivedAt == null }
            .mapNotNull { it.scheduleId }
            .distinct()
            .mapNotNull { id ->
                schedules.get(id)?.let { schedule ->
                    val state = recompute.readState(schedule)
                    id to LinkedSchedule(schedule, ScheduleRecompute.policyInputsOf(schedule, state, zone()))
                }
            }
            .toMap()

    private suspend fun conditionOf(assetId: AssetId): ConditionView? {
        val history = ConditionHistory.of(conditions.forAsset(assetId))
        val current = history.current ?: return null
        return ConditionView(
            condition = current.condition,
            since = LocalDate.parse(history.since!!.occurredOn),
            reason = current.reason,
            occurredOn = LocalDate.parse(current.occurredOn),
            occurredTime = current.occurredTime,
            eventId = current.eventId,
            eventExists = current.eventId?.let { events.get(it) != null } == true,
        )
    }

    private suspend fun componentsOf(asset: Asset, all: List<Asset>): List<ComponentCondition> {
        val under = AssetTree.descendants(all, asset.id)
        if (under.isEmpty()) return emptyList()
        val histories = conditionHistories()
        return all.filter { it.id in under && it.inService }
            .mapNotNull { component ->
                histories[component.id]?.current
                    ?.takeIf { it.condition.needsAttention }
                    ?.let { component.conditionLine(it) }
            }
            .sortedWith(
                compareBy<ComponentCondition> { it.condition != OperationalCondition.DOWN }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.assetId.value },
            )
    }

    private fun Asset.conditionLine(current: AssetCondition) = ComponentCondition(
        assetId = id,
        name = name,
        condition = current.condition,
        reason = current.reason,
        occurredOn = LocalDate.parse(current.occurredOn),
    )

    private companion object {
        val NO_HEALTH = AssetHealthResult(subjects = emptyList(), aggregate = null, fallback = false, critical = emptyList())

        /**
         * A screened-out subject's row: NOT TRACKED for its own reason, [NotTrackedReason.UNSCORABLE],
         * with no driver line (S98 alone; the controller's rulings on B05's concern 3 and B07's
         * concern 2) — never a borrowed reason that would say something untrue on `/v1`.
         */
        fun unscorableRow(subject: HealthSubject) =
            SubjectHealth(subject, SubjectValue.NotTracked(NotTrackedReason.UNSCORABLE), emptyList())

        /** The engine's own subject order. */
        val SUBJECT_ORDER: Comparator<SubjectHealth> =
            compareBy<SubjectHealth> { it.subject.sortOrder }.thenBy { it.subject.id.value }
    }
}

/** The band fact of a tracked subject; null for a subject NOT TRACKED. */
internal fun SubjectHealth.bandFact(): SubjectBandFact? = (value as? SubjectValue.Scored)?.let {
    SubjectBandFact(subject.id, subject.name, it.band, it.score)
}

/** DOWN or DEGRADED: the two conditions that reach ATTENTION and may open the scan sheet (O-8). */
internal val OperationalCondition.needsAttention: Boolean
    get() = this == OperationalCondition.DOWN || this == OperationalCondition.DEGRADED

/**
 * Whether an asset or component is **in service** on its own lifecycle: active and not retired.
 * The same bound `targetInService` applies to a schedule's target Asset, for the asset-level rows.
 */
internal val Asset.inService: Boolean
    get() = status == AssetStatus.ACTIVE && !isRetired
