package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.schedule.GroupOccurrence
import com.loosecannon.servicetag.core.schedule.SeasonContext
import com.loosecannon.servicetag.core.schedule.listedForDue
import com.loosecannon.servicetag.core.schedule.statusOf
import com.loosecannon.servicetag.core.schedule.targetInService
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import com.loosecannon.servicetag.ui.health.AssetHealthReadModel
import com.loosecannon.servicetag.ui.health.SubjectBandFact
import java.time.LocalDate

/**
 * The five dashboard sections, in the order they are drawn: D12 §10's four
 * (`12-visual-design-apollo-service-binder.md:706-707`) and 1.4's quiet **Deferred**, between CURRENT
 * and OUT OF SEASON (spec §4.5). The order is the enum's own, so "the section order" and "the first
 * key of the sort" are the same fact and cannot drift apart.
 *
 * Not every status has a section: see [DueItem.section].
 */
enum class AttentionSection { ATTENTION, UPCOMING, CURRENT, DEFERRED, OUT_OF_SEASON }

/**
 * One schedule as every surface sees it: the dashboard, the Maintenance shell, B09's scan sheet and
 * B12's `/v1/due`. **Nothing here is stored** — the status is computed at read time from the row
 * plus `T` (invariant 18) and the section follows from the status, so a stale one is unrepresentable.
 *
 * [section] is **nullable, and that is the point.** Two states belong in no dashboard section at
 * all and are still in this list, because the Maintenance destination's Schedules section has to be
 * able to show them: a **PAUSED** schedule (the dashboard answers what needs attention and a paused
 * schedule needs nothing) and an **empty-required-set** `NO_DATA` (not actionable, not counted,
 * never promoted — invariant 74). Dropping either from the projection would make them invisible
 * everywhere rather than absent from one surface.
 *
 * [requiredSetEmpty] is why there is no `isActionable` on this type (decision 27). "Actionable" is
 * surface-specific — the dashboard's promotion rule means ATTENTION or UPCOMING (decision 29) while
 * the scan sheet's admission set is narrower (D-18a) — so each surface applies its own predicate to
 * the [status] and this flag. One shared boolean would be wrong for one surface the moment it was
 * right for the other, and this projection feeds four of them.
 *
 * [assetName] is the target's name: the Asset's for an asset-targeted schedule, the **group's** for
 * a group-targeted one, because a group row is one row for the group (D-15). [parentName] is only
 * ever set for a component Asset, and it is what lets a promoted component row say whose part it is.
 *
 * [membersRequired] and [membersComplete] are null for an asset target and come from
 * `RecomputeSchedules.occurrenceOf` for a group one — never from a raw membership count, which
 * would include members the round does not oblige.
 *
 * [snoozedUntil] is the device-local snooze of `schedule_local_delivery` (D-13), the instant the
 * notification's "Snooze 1 day" or the in-app "Snooze" wrote, read through [DueReadModel]'s
 * `snoozedUntilOf` and never written here. It is what "Snoozed until \<date\>" draws; it moves no
 * date and no status.
 *
 * **1.4's policy facts** (master plan §13.1), all read from the state derived for today:
 * [actionableDueOn] is the date the status word is measured against and the sort key;
 * [policyReason], [policyPhase] and [quiet] are what a why-line says about it. [seasonMode] is the
 * target Asset's (null for a group, which has no season), and [dormantUntil] is the next season
 * start of a DORMANT row on a CALENDAR Asset — a meter-only one included — and null otherwise,
 * because a MANUAL start is never predicted (plan decision 37). [health] is the band of the
 * subject this schedule drives, when that subject is tracked: overdue-driven health rides its
 * schedule's row rather than being a row of its own (spec §10.2). [assetCondition] is the target
 * Asset's **current** condition — a component's own — and null for a group target or when none is
 * recorded, so a condition filter reads it off the row (the controller's ruling on B07's
 * concern 3).
 */
data class DueItem(
    val scheduleId: ScheduleId,
    val title: String,
    val target: ScheduleTarget,
    val assetName: String,
    val parentName: String?,
    /** The target Asset's category, for F2's filter. Null for a group target: a group has none. */
    val category: String?,
    val status: DueStatus,
    val section: AttentionSection?,
    val requiredSetEmpty: Boolean,
    val effectiveDueOn: LocalDate?,
    val actionableDueOn: LocalDate?,
    val policyReason: PolicyReason,
    val policyPhase: PolicyPhase,
    val quiet: Boolean,
    val seasonMode: SeasonMode?,
    val dormantUntil: LocalDate?,
    val computedDueMeter: Double?,
    val currentMeter: Double?,
    /** The meter definition's unit, snapshotted for F3's line. Null when there is no meter rule. */
    val meterUnit: String?,
    val lastCompletedOn: LocalDate?,
    val completionMode: CompletionMode,
    /**
     * The schedule's own reminder switch, carried so a surface can ask whether there is a delivery
     * at all.
     *
     * **Added for B09 (review blocking 3), and additive:** the D-18a admission set excludes "an
     * archived or **reminders-disabled** schedule", and the scan sheet is the surface that has to
     * honour it — the archived half is already gone by `listedForDue()`, and this is the other
     * half. It is also what stops the sheet offering a **"Snooze"** that would suppress a delivery
     * that was never going to happen, which is the condition B14's schedule detail already gates
     * the same action on (`ScheduleDetailState.canSnooze`).
     *
     * It is **not** a status and it is **not** an actionability flag (decision 27): it is the
     * stored column, and each surface decides what to do with it. The dashboard deliberately does
     * not filter on it — a due obligation is due whether or not the phone will announce it.
     */
    val remindersEnabled: Boolean,
    val membersRequired: Int?,
    val membersComplete: Int?,
    val snoozedUntil: Long?,
    val health: SubjectBandFact?,
    val assetCondition: OperationalCondition?,
    val rank: Int,
) {
    /**
     * Whether this row contributes to a due total. `DUE` and `OVERDUE` do; `INACTIVE_SEASON`,
     * `PAUSED` and `DEFERRED` never do (invariants 22, 103); an empty required set never does however its status reads
     * (invariant 74); and `NO_DATA` is a repair, not an obligation.
     *
     * This is not the `isActionable` decision 27 forbids. "Counts as due" is one rule for every
     * surface — a group row counts **once** in every due total (D-15) — whereas "actionable" differs
     * per surface, which is exactly why that one is not here.
     */
    val countsAsDue: Boolean get() = status.countsAsDue && !requiredSetEmpty

    /** Whether this row is a component's, and so the row the blank-query rule would hide. */
    val isComponent: Boolean get() = parentName != null
}

/**
 * The shared due projection: schedules + derived state + membership + `T` → attention-ordered items
 * with their section and rank.
 *
 * One projection rather than a query per screen (decision 27). Spec §2.6 fixes the ordering and the
 * section placement and spec §2.8 says the scan sheet uses "§2.6's attention ordering"; one
 * projection is the only shape in which those two cannot drift, and it is what gives B12's
 * `/v1/due` its `rank`.
 *
 * **It reads derived state and never writes it** (invariants 17, 105): every state comes through
 * `RecomputeSchedules.readState` — the stored row only when it was computed for today, otherwise
 * derived in memory — and `occurrenceOf` derives without upserting. A row left behind by a day
 * boundary is never shown: a season, activation or break boundary may change a status with no
 * history change (invariant 23). The one write path into `schedule_state` stays `rebuild`, called
 * by the use cases. [today] is the same `Today` port `readState` reads (`AppGraph` hands both the
 * one instance), so the status and the state it is measured on are for the same day.
 *
 * [health] supplies `DueItem.health` — the one health computation, read once per Asset per call —
 * and `DueItem.assetCondition`, from the same condition read every health surface uses.
 *
 * [snoozedUntilOf] is the reader of B06's `schedule_local_delivery` row, and it has **no
 * default**: `AppGraph` and the test graph both hand it the real table's `snoozedUntilAt`, read and
 * never written. A defaulted seam could be forgotten in silence — `snoozedUntil` would stay null
 * for ever, the ratified "Snoozed until \<date\>" would be dead, and nothing would fail.
 */
class DueReadModel(
    private val schedules: ScheduleRepository,
    private val assets: AssetRepository,
    private val groups: GroupRepository,
    private val definitions: DefinitionRepository,
    private val recompute: RecomputeSchedules,
    private val today: Today,
    private val health: AssetHealthReadModel,
    private val snoozedUntilOf: suspend (ScheduleId) -> Long?,
) {

    /**
     * Every schedule the default listings answer for, attention-ordered with `rank` assigned.
     *
     * Two bounds, and they are different rules. `listedForDue()` drops **archived schedules** —
     * carry-forward (a): every due, projection and dashboard query starts there, so retired work
     * appears in no section and no count. The lifecycle bound on the **target** drops a schedule
     * whose Asset is archived or retired, or whose group is archived, because an out-of-service
     * thing's obligations are not what "what needs attention" means (#5 AC 3). That second bound is
     * deliberately **not** applied by [forAsset]: an archived asset opened directly keeps its
     * history.
     */
    suspend fun items(): List<DueItem> {
        val world = World.of(assets, groups)
        return project(
            schedules.all().listedForDue().filter { world.targetInService(it) },
            world,
        )
    }

    /**
     * One Asset's schedules: the ones targeting it, **plus every group schedule it is a required
     * member of** — B09's sheet and B15's asset section both ask that question, and a group
     * obligation on this Asset is work on this Asset.
     *
     * The group half is the same superset `RecomputeSchedules` rebuilds over (every group the Asset
     * has ever held a window in): which rounds actually oblige it is the occurrence derivation's
     * answer, per round, and this only has to be certain it misses nothing.
     */
    suspend fun forAsset(assetId: AssetId): List<DueItem> {
        val world = World.of(assets, groups)
        val own = schedules.forAsset(assetId)
        val throughGroups = groups.allWindowsFor(assetId).flatMap { schedules.forGroup(it.id) }
        return project(
            (own + throughGroups).distinctBy { it.id.value }.listedForDue(),
            world,
        )
    }

    private suspend fun project(rows: List<MaintenanceSchedule>, world: World): List<DueItem> {
        val t = today.localDate()
        val bands = mutableMapOf<AssetId, Map<ScheduleId, SubjectBandFact>>()
        val conditions = health.conditionHistories()
        val items = rows.map { schedule ->
            // Never a stored row from another day, and never a write (master plan §8.6).
            val state = recompute.readState(schedule)
            val occurrence = when (schedule.target) {
                is ScheduleTarget.GroupTarget -> recompute.occurrenceOf(schedule)
                is ScheduleTarget.AssetTarget -> null
            }
            val band = (schedule.target as? ScheduleTarget.AssetTarget)?.let { target ->
                bands.getOrPut(target.assetId) { health.bandsBySchedule(target.assetId) }[schedule.id]
            }
            val condition = (schedule.target as? ScheduleTarget.AssetTarget)?.let { target ->
                conditions[target.assetId]?.current?.condition
            }
            item(schedule, state, occurrence, world, t, band, condition)
        }
        // `rank` is assigned after the total order, once, so it is the position in the one order
        // every surface shares (decision 30).
        return items.sortedWith(ATTENTION_ORDER).mapIndexed { index, item -> item.copy(rank = index) }
    }

    private suspend fun item(
        schedule: MaintenanceSchedule,
        state: ScheduleState,
        occurrence: GroupOccurrence?,
        world: World,
        t: LocalDate,
        band: SubjectBandFact?,
        condition: OperationalCondition?,
    ): DueItem {
        val status = statusOf(schedule, state, t)
        // A group target's required set can be empty; an asset target's never is — it is its own
        // Asset. `occurrenceOf` answers null only for a schedule with no time rule, and a null
        // answer proves nothing about emptiness, so it is not read as empty.
        val requiredSetEmpty = schedule.target is ScheduleTarget.GroupTarget &&
            occurrence != null && occurrence.required.isEmpty()
        val asset = (schedule.target as? ScheduleTarget.AssetTarget)?.let { world.asset(it.assetId) }
        val group = (schedule.target as? ScheduleTarget.GroupTarget)?.let { world.group(it.groupId) }
        val progress = occurrence?.takeIf { schedule.target is ScheduleTarget.GroupTarget }?.progress
        return DueItem(
            scheduleId = schedule.id,
            title = schedule.title,
            target = schedule.target,
            assetName = asset?.name ?: group?.name.orEmpty(),
            parentName = asset?.parentAssetId?.let { world.asset(it)?.name },
            category = asset?.category?.takeIf { it.isNotBlank() },
            status = status,
            section = sectionOf(status, requiredSetEmpty),
            requiredSetEmpty = requiredSetEmpty,
            effectiveDueOn = state.effectiveDueOn?.let(LocalDate::parse),
            actionableDueOn = state.actionableDueOn?.let(LocalDate::parse),
            policyReason = state.policyReason,
            policyPhase = state.policyPhase,
            quiet = state.quiet,
            seasonMode = asset?.seasonMode,
            dormantUntil = asset?.takeIf {
                it.seasonMode == SeasonMode.CALENDAR && state.policyPhase == PolicyPhase.DORMANT
            }?.let { SeasonContext.of(it.seasonInputs(emptyList())).cycleStartAt(t) },
            computedDueMeter = state.computedDueMeter,
            currentMeter = state.currentMeter,
            meterUnit = schedule.meterDefinitionId?.let { definitions.get(it)?.unit }?.takeIf { it.isNotBlank() },
            lastCompletedOn = state.lastCompletedOn?.let(LocalDate::parse),
            completionMode = schedule.completionMode,
            remindersEnabled = schedule.remindersEnabled,
            membersRequired = progress?.second,
            membersComplete = progress?.first,
            snoozedUntil = snoozedUntilOf(schedule.id),
            health = band,
            assetCondition = condition,
            rank = 0,
        )
    }

    /**
     * The assets and groups a projection needs names and lifecycles from, read once per call.
     *
     * Parent names come from **every** asset, archived parents included: a component of an archived
     * machine still has to be able to say whose component it is.
     */
    private class World(
        private val assetsById: Map<String, Asset>,
        private val groupsById: Map<String, MaintenanceGroup>,
    ) {
        fun asset(id: AssetId): Asset? = assetsById[id.value]

        fun group(id: GroupId): MaintenanceGroup? = groupsById[id.value]

        /**
         * #5 AC 3's lifecycle bound, delegated to the domain's own one copy of it.
         *
         * It moved to `core/.../core/schedule/` beside `listedForDue` when B10's health findings
         * needed the same bound: decision 27's worry is exactly two copies of one predicate
         * drifting, and a bound this surface and the health screen disagreed about would put a
         * finding on the badge for a schedule no list will show.
         */
        fun targetInService(schedule: MaintenanceSchedule): Boolean =
            schedule.targetInService(::asset, ::group)

        companion object {
            suspend fun of(assets: AssetRepository, groups: GroupRepository): World = World(
                assetsById = assets.all().associateBy { it.id.value },
                groupsById = groups.all().associateBy { it.id.value },
            )
        }
    }

    private companion object {
        /**
         * Decision 30's total order: section ordinal, `actionableDueOn` ascending with nulls last,
         * `title` case-insensitively, then `scheduleId`. The key is the **actionable** date (1.4
         * spec §4.7, D-29): the one the status word is measured against, so a row the policy pulled
         * before its season sorts by the day it is actually due. Spec §2.6 fixes the sort key and
         * D12 §10 the sections, but neither makes the order total — and `rank` has to be
         * deterministic for B12's clients and for a repeatable test.
         *
         * A sectionless row (PAUSED, an empty required set) sorts after every sectioned one, so the
         * dashboard's sections and the Schedules list read the same order from the same list.
         */
        private val ATTENTION_ORDER: Comparator<DueItem> =
            compareBy<DueItem> { it.section?.ordinal ?: Int.MAX_VALUE }
                .thenBy(nullsLast()) { it.actionableDueOn }
                .thenBy { it.title.lowercase() }
                .thenBy { it.scheduleId.value }

        /**
         * §11.1's table, and every state has a home — or deliberately none.
         *
         * `NO_DATA` splits: the **repairable missing meter baseline** is actionable ("Log meter
         * reading" repairs it and #27 raises a finding for it) and takes ATTENTION, while an
         * **empty required set** is not actionable at all and takes no section (invariants 74, 77).
         * `PAUSED` takes no section either. `DEFERRED` has its own quiet section, never a due one
         * (invariant 103).
         */
        fun sectionOf(status: DueStatus, requiredSetEmpty: Boolean): AttentionSection? = when {
            requiredSetEmpty -> null
            status == DueStatus.OVERDUE || status == DueStatus.DUE -> AttentionSection.ATTENTION
            status == DueStatus.NO_DATA -> AttentionSection.ATTENTION
            status == DueStatus.DUE_SOON -> AttentionSection.UPCOMING
            status == DueStatus.OK -> AttentionSection.CURRENT
            status == DueStatus.DEFERRED -> AttentionSection.DEFERRED
            status == DueStatus.INACTIVE_SEASON -> AttentionSection.OUT_OF_SEASON
            else -> null   // PAUSED
        }
    }
}
