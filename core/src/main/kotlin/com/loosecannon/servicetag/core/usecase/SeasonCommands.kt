package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.Season
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.schedule.BoundaryKind
import com.loosecannon.servicetag.core.schedule.SeasonContext
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.season.isLeafChild
import com.loosecannon.servicetag.core.season.roleCoverageOf
import java.time.LocalDate
import java.time.Year

/**
 * The season-mode command (spec §3.2, §3.4; master plan §7.2). [manualPhase] is stated exactly on a
 * switch **into** MANUAL from another mode, and says whether the asset is in its season now: the
 * switch writes a START for IN_SEASON and an END for OUT_OF_SEASON, dated today.
 */
data class SeasonModeCommand(
    val seasonMode: SeasonMode,
    val seasonStartMmdd: String? = null,
    val seasonEndMmdd: String? = null,
    val manualPhase: SeasonPhase? = null,
)

/** The maintenance-break command (spec §4.4): both bounds or neither. Nothing ever defaults them. */
data class BreakCommand(val blackoutStartMmdd: String?, val blackoutEndMmdd: String?)

/**
 * One manual START or END (spec §3.3). [occurredOn] defaults to today; [eventId] is the journal event
 * that prompted it, a soft link that must name an event of the same asset.
 */
data class ActivationCommand(
    val action: SeasonAction,
    val occurredOn: String? = null,
    val eventId: EventId? = null,
)

/**
 * One thing wrong with a season, break or activation command. **Every member is a 422** — the remedy
 * is to change the body that was sent, whatever the stored state (spec §9.2's tie-break) — so they
 * are collected into one [SeasonValidation] and a form can mark every bad field on a single submit.
 */
sealed interface SeasonProblem {
    /** CALENDAR with a bound missing: a calendar season needs both `MM-DD` dates. */
    data object SeasonWindowRequired : SeasonProblem

    /** A window sent with YEAR_ROUND or MANUAL, which have none (inv. 88). */
    data object SeasonWindowForbidden : SeasonProblem

    /** A switch into MANUAL that does not say whether the season is running now (spec §3.4). */
    data object ManualPhaseRequired : SeasonProblem

    /** `manualPhase` on anything but a switch into MANUAL, MANUAL → MANUAL included (inv. 92). */
    data object ManualPhaseForbidden : SeasonProblem

    /**
     * A value that is not a real `MM-DD` (or, for [ActivationCommand.occurredOn], a real ISO date).
     * The shipped validation shape, `BadDate(field=…)`, naming the body key.
     */
    data class BadDate(val field: String) : SeasonProblem

    /** One break bound without the other. */
    data object BothOrNeither : SeasonProblem

    /** A break under which some year, common or leap, has no allowed day (master plan §7.2). */
    data object BlackoutCoversTheYear : SeasonProblem

    /** An activation dated after today, or before the latest activation's date (inv. 91). */
    data object SeasonDateOutOfRange : SeasonProblem

    /** The event an activation links to is not an event of this asset, or does not exist. */
    data class ForeignEvent(val eventId: EventId) : SeasonProblem
}

/** A season, break or activation command was refused; every problem found, collected once. */
class SeasonValidation(val problems: List<SeasonProblem>) :
    IllegalArgumentException("season command rejected: ${problems.joinToString()}")

/** A PRE_SERVICE schedule a refusal names, so the API can list it and the editor fill in its title. */
data class StrandedSchedule(val id: ScheduleId, val title: String)

/**
 * 409: the season-mode change would remove or re-kind the boundary [schedules] count back from
 * (spec §3.2, RS-4, O-3). The remedy is those schedules' policy — another row.
 */
class SeasonModeStrandsPolicy(val assetId: AssetId, val schedules: List<StrandedSchedule>) :
    IllegalStateException("season mode of ${assetId.value} would strand ${schedules.map { it.id.value }}")

/** 409: the break change would remove or re-kind the boundary [schedules] count back from (spec §4.4). */
class BreakStrandsPolicy(val assetId: AssetId, val schedules: List<StrandedSchedule>) :
    IllegalStateException("maintenance break of ${assetId.value} would strand ${schedules.map { it.id.value }}")

/** #114 (C11): what a refused change would have broken under a live Season Start role (P114-24…27, P114-43). */
enum class SeasonStartStrandCause {
    /** A covered asset's season mode would become YEAR_ROUND. */
    NEEDS_SEASONS,

    /** A covered asset would be left a child with no child row: the moved asset, or its old parent. */
    WOULD_BE_LEAF_CHILD,

    /** A role schedule's target would change (O-6): clear the role first. */
    ROLE_SCHEDULE_RETARGET,

    /** A role schedule's FORM → QUICK edit without the owner's election (O-2, F-22). */
    QUICK_NEEDS_ELECTION,

    /** A member added to a role group fails C10's asset rule. */
    MEMBER_NOT_ELIGIBLE,

    /** A member added to a role group is already covered by another role. */
    MEMBER_ALREADY_COVERED,

    /** A restore from the archive (a role schedule, or a role group) would cover an asset that fails C10's asset rule. */
    RESTORE_NOT_ELIGIBLE,

    /** A restore from the archive would cover an asset another live role already covers (C9's `TWO_ROLES`). */
    RESTORE_ALREADY_COVERED,

    /**
     * #124 (C42): a live schedule of the asset counts from its opening service, and the change would leave it no live
     * role to count from — the role cleared or archived, its group archived or the asset taken out of it — or the asset
     * would go YEAR_ROUND. The strand names those schedules (P124-8).
     */
    HAS_DEPENDENT_SCHEDULES,
}

/**
 * #114 (C11) — 409: the change would leave a Season Start role where it may not stand ([strandCause]; the plan's
 * `cause`, which `Throwable` already names). [assetId] is the asset it would strand — null when it is a group
 * schedule's own edit — and [schedules] the role schedules involved, by title then id. [refusal] is C10's asset rule's
 * answer for [SeasonStartStrandCause.RESTORE_NOT_ELIGIBLE], so the phone can say which (P114-16/17/18); null for
 * every other cause. Nothing was written.
 */
class SeasonStartStrands(
    val assetId: AssetId?,
    val strandCause: SeasonStartStrandCause,
    val schedules: List<StrandedSchedule>,
    val refusal: SeasonStartRefusal? = null,
) : IllegalStateException("the change would strand a Season Start role: $strandCause")

/** 409: an activation was aimed at an asset whose season is not MANUAL. */
class SeasonNotManual(val assetId: AssetId) :
    IllegalStateException("asset ${assetId.value} is not in MANUAL season mode")

/**
 * #16 (C2, C15; R16-1) — 409: an enabled Home Assistant binding owns this asset's season, so every other season
 * writer is refused until the owner stops its season sync. Thrown only by
 * [SeasonSyncGuard][com.loosecannon.servicetag.core.seasonsync.SeasonSyncGuard].
 */
class SeasonSyncOwnsSeason(val assetId: AssetId) :
    IllegalStateException("this asset's season follows Home Assistant; stop its season sync on the phone first")

/** 409: a START when the latest activation is already a START. */
class SeasonAlreadyStarted(val assetId: AssetId) :
    IllegalStateException("the season of ${assetId.value} has already started")

/**
 * 409: an END when the latest activation is already an END — or when there is no activation at all,
 * because a MANUAL asset with no history reads OUT_OF_SEASON (plan decision 32).
 */
class SeasonAlreadyEnded(val assetId: AssetId) :
    IllegalStateException("the season of ${assetId.value} has already ended")

/**
 * 422: a legacy input asked for a change 1.4 state cannot express — here, a different `MM-DD` pair on a
 * MANUAL asset (spec §9.3, inv. 128). Refused, never reset: nothing is written.
 */
class LegacyWriteCannotRepresent(val assetId: AssetId) :
    IllegalArgumentException("asset ${assetId.value} is MANUAL; a season pair cannot represent it")

/**
 * 409: a PRE_SERVICE schedule on an asset with neither a calendar season nor a break, so there is no
 * boundary to count back from (spec §4.3, inv. 99). The remedy is the asset.
 */
class PreServiceNeedsDates(val assetId: AssetId) :
    IllegalStateException("asset ${assetId.value} has no season or break for PRE_SERVICE to count back from")

// ------------------------------------------------------------------------------------------------
// The rules, shared by every command that reaches them: `SetSeasonMode`, `SetMaintenanceBreak`, the
// asset command's legacy pair, and the composite settings save that validates them together.
// ------------------------------------------------------------------------------------------------

/** Blank `MM-DD` text is absent, as the asset command has always read it. */
internal fun SeasonModeCommand.trimmed(): SeasonModeCommand =
    copy(seasonStartMmdd = seasonStartMmdd.blankToNull(), seasonEndMmdd = seasonEndMmdd.blankToNull())

internal fun BreakCommand.trimmed(): BreakCommand =
    copy(blackoutStartMmdd = blackoutStartMmdd.blankToNull(), blackoutEndMmdd = blackoutEndMmdd.blankToNull())

/**
 * Everything wrong with [cmd] as a change from [current], the stored mode (spec §3.2, §3.4):
 *
 * - CALENDAR needs both bounds (`SeasonWindowRequired`), each a real `MM-DD` ([SeasonProblem.BadDate]);
 * - YEAR_ROUND and MANUAL take no bound at all (`SeasonWindowForbidden`, inv. 88);
 * - `manualPhase` is required on a switch into MANUAL and forbidden everywhere else, MANUAL → MANUAL
 *   included (inv. 92).
 *
 * The `MM-DD` check is the shipped [Season.validate], so a bound this refuses is exactly one the asset
 * command has always refused.
 */
internal fun seasonModeProblems(current: SeasonMode, cmd: SeasonModeCommand): List<SeasonProblem> {
    val problems = mutableListOf<SeasonProblem>()
    val start = cmd.seasonStartMmdd
    val end = cmd.seasonEndMmdd
    if (cmd.seasonMode == SeasonMode.CALENDAR) {
        if (start == null || end == null) problems += SeasonProblem.SeasonWindowRequired
        Season.validate(start, end).forEach { problem ->
            when (problem) {
                Season.Problem.BothOrNeither -> Unit    // already SeasonWindowRequired
                is Season.Problem.BadDate -> problems += SeasonProblem.BadDate(
                    if (problem.which == "start") "seasonStartMmdd" else "seasonEndMmdd",
                )
            }
        }
    } else if (start != null || end != null) {
        problems += SeasonProblem.SeasonWindowForbidden
    }

    val intoManual = cmd.seasonMode == SeasonMode.MANUAL && current != SeasonMode.MANUAL
    if (intoManual && cmd.manualPhase == null) problems += SeasonProblem.ManualPhaseRequired
    if (!intoManual && cmd.manualPhase != null) problems += SeasonProblem.ManualPhaseForbidden
    return problems
}

/**
 * **The switch into MANUAL** (spec §3.4; inv. 92): exactly one activation, dated [today] — START when
 * [cmd] says the season is running now, END otherwise — whenever [cmd] moves the asset into MANUAL
 * from [before], even when the latest historical row already says the same (END, END is valid
 * history). Null for every other mode change, leaving MANUAL included. [SetSeasonMode] and
 * [SaveAssetSettings] both write what this returns, so the two writers cannot drift.
 */
internal fun manualSwitchActivation(
    assetId: AssetId,
    before: SeasonMode,
    cmd: SeasonModeCommand,
    today: LocalDate,
    now: Long,
    ids: IdGenerator,
): SeasonActivation? {
    if (cmd.seasonMode != SeasonMode.MANUAL || before == SeasonMode.MANUAL) return null
    return SeasonActivation(
        id = ids.newId(),
        assetId = assetId,
        action = if (cmd.manualPhase == SeasonPhase.IN_SEASON) SeasonAction.START else SeasonAction.END,
        occurredOn = today.toString(),
        eventId = null,
        createdAt = now,
    )
}

/**
 * Everything wrong with [cmd] as a break (spec §4.4): both bounds or neither, each a real `MM-DD`, and
 * never a break that leaves some year — common **or** leap — with no allowed day (master plan §7.2).
 * Both passes are needed: 03-01 → 02-28 covers every day of a common year, 02-29 → 02-28 every day of
 * a leap one.
 */
internal fun breakProblems(cmd: BreakCommand): List<SeasonProblem> {
    val problems = mutableListOf<SeasonProblem>()
    val start = cmd.blackoutStartMmdd
    val end = cmd.blackoutEndMmdd
    Season.validate(start, end).forEach { problem ->
        problems += when (problem) {
            Season.Problem.BothOrNeither -> SeasonProblem.BothOrNeither
            is Season.Problem.BadDate ->
                SeasonProblem.BadDate(if (problem.which == "start") "blackoutStartMmdd" else "blackoutEndMmdd")
        }
    }
    if (problems.isEmpty() && start != null && end != null && coversSomeYear(start, end)) {
        problems += SeasonProblem.BlackoutCoversTheYear
    }
    return problems
}

/** A common year and a leap year: between them every way a yearly `MM-DD` window can resolve. */
private val PROBE_YEARS = listOf(2025, 2024)

/**
 * Whether the break covers every day of some year. It is the **same predicate** every consumer reads
 * the break with ([Season.inSeason]: inclusive, wrapping, 02-29 read as 02-28 in a common year), asked
 * day by day, so "no allowed day" here means no allowed day to the engine too.
 */
private fun coversSomeYear(start: String, end: String): Boolean = PROBE_YEARS.any { year ->
    val first = LocalDate.of(year, 1, 1)
    (0 until Year.of(year).length()).all { Season.inSeason(start, end, first.plusDays(it.toLong())) }
}

/**
 * The boundary PRE_SERVICE counts back from on this asset, read exactly as the engine reads it (a
 * CALENDAR season's start, else the break's start, else none).
 */
internal fun Asset.boundaryKind(): BoundaryKind = SeasonContext.of(seasonInputs(emptyList())).boundaryKind

/**
 * **The strands rule** (spec §3.2, §4.4; master plan §7.2; RS-4, O-3, dec. 44): the PRE_SERVICE
 * schedules among [targeting] whose boundary the change from [before] to [after] would remove or
 * re-kind — the kind before is not NONE and the kind after differs. Empty when the change keeps the
 * kind (a new CALENDAR window, new break dates) or **adds** a boundary where there was none, which
 * repairs a merged boundary-less schedule rather than stranding it.
 *
 * [targeting] is every schedule aimed at the asset **whatever its lifecycle**: a paused or archived
 * PRE_SERVICE schedule can be resumed or restored, and the boundary-less state is one commands never
 * reach (plan decision 7).
 */
internal fun strandedBy(before: Asset, after: Asset, targeting: List<MaintenanceSchedule>): List<StrandedSchedule> {
    val kindBefore = before.boundaryKind()
    if (kindBefore == BoundaryKind.NONE || kindBefore == after.boundaryKind()) return emptyList()
    return targeting
        .filter { it.servicePolicy == ServicePolicy.PRE_SERVICE }
        .sortedWith(compareBy({ it.title }, { it.id.value }))
        .map { StrandedSchedule(it.id, it.title) }
}

/**
 * #114 (C11) — **the Season Start strand rule**, for the season-mode and parent writers (`SetSeasonMode`,
 * `UpdateAsset`, `SaveAssetSettings`), called **after** [strandedBy] so a PRE_SERVICE refusal keeps priority. Null
 * when the change from [before] to [after] leaves every live role standing; otherwise the first strand found:
 *
 * - [SeasonStartStrandCause.NEEDS_SEASONS]: a change **into** YEAR_ROUND of an asset a live role covers;
 * - [SeasonStartStrandCause.WOULD_BE_LEAF_CHILD]: a changed parent that leaves a covered asset with a parent and no
 *   child row (O-5: any child row counts) — the moved asset itself, then its old parent losing its last child;
 * - #124 (C42), last: [SeasonStartStrandCause.HAS_DEPENDENT_SCHEDULES], a change into YEAR_ROUND of an asset a live
 *   schedule of which counts from its opening service — which no YEAR_ROUND asset has — naming those schedules. A
 *   covered asset says `NEEDS_SEASONS` first.
 *
 * [assets] is the stored tree ([after] replaces its own row in it); [schedules] and [groups] are every row, read
 * through C9's coverage ([seasonStartRolesCovering]). Pure: it reads no store and throws nothing.
 */
internal fun seasonStartStrandedBy(
    before: Asset,
    after: Asset,
    assets: Collection<Asset>,
    schedules: Collection<MaintenanceSchedule>,
    groups: Collection<MaintenanceGroup>,
): SeasonStartStrands? {
    fun strands(asset: Asset, cause: SeasonStartStrandCause): SeasonStartStrands? =
        seasonStartRolesCovering(asset.id, schedules, groups)
            .takeIf { it.isNotEmpty() }
            ?.let { roles -> SeasonStartStrands(asset.id, cause, roles.map { StrandedSchedule(it.id, it.title) }) }

    val intoYearRound = before.seasonMode != SeasonMode.YEAR_ROUND && after.seasonMode == SeasonMode.YEAR_ROUND
    if (intoYearRound) strands(after, SeasonStartStrandCause.NEEDS_SEASONS)?.let { return it }
    if (before.parentAssetId != after.parentAssetId) {
        val tree = assets.filter { it.id != after.id } + after
        val oldParent = before.parentAssetId?.let { id -> tree.firstOrNull { it.id == id } }
        listOfNotNull(after, oldParent)
            .filter { isLeafChild(it, tree) }
            .firstNotNullOfOrNull { strands(it, SeasonStartStrandCause.WOULD_BE_LEAF_CHILD) }
            ?.let { return it }
    }
    if (!intoYearRound) return null
    return openingServiceDependentsOf(after.id, schedules)
        .takeIf { it.isNotEmpty() }
        ?.let { dependents -> dependentsStrand(after.id, dependents) }
}

/**
 * #124 (C42) — the live (ACTIVE or PAUSED) schedules of [assetId] that count from its opening service
 * (`IN_SERVICE_AFTER_OPENING_SERVICE`), by title then id: the dependents a Season Start role must keep covering.
 */
internal fun openingServiceDependentsOf(
    assetId: AssetId,
    schedules: Collection<MaintenanceSchedule>,
): List<MaintenanceSchedule> = schedules
    .filter { schedule ->
        schedule.servicePolicy == ServicePolicy.IN_SERVICE_AFTER_OPENING_SERVICE &&
            schedule.status != ScheduleStatus.ARCHIVED &&
            (schedule.target as? ScheduleTarget.AssetTarget)?.assetId == assetId
    }
    .sortedWith(compareBy({ it.title }, { it.id.value }))

private fun dependentsStrand(assetId: AssetId, dependents: List<MaintenanceSchedule>) = SeasonStartStrands(
    assetId,
    SeasonStartStrandCause.HAS_DEPENDENT_SCHEDULES,
    dependents.map { StrandedSchedule(it.id, it.title) },
)

/**
 * #124 (C42) — **the dependents' strand**, for a change that takes a live role away: clearing or archiving a role
 * schedule (`SetScheduleRole`, `ArchiveSchedule`), archiving a role group (`ArchiveGroup`, N2a's erratum) or taking a
 * member out of one (`SaveGroup`). Null unless one of [affected] — the first by name then id — is covered by a live role
 * before the change (C9's coverage over [schedulesBefore] and [groupsBefore]), by none after it ([schedulesAfter],
 * [groupsAfter]) and has a live schedule counting from its opening service: then
 * [SeasonStartStrandCause.HAS_DEPENDENT_SCHEDULES], naming those schedules. An asset no live role covered before is never
 * judged here (an already-broken row blocks no unrelated edit). Pure: it reads no store and throws nothing.
 */
internal fun openingServiceStrandOf(
    affected: Collection<AssetId>,
    assets: Collection<Asset>,
    schedulesBefore: Collection<MaintenanceSchedule>,
    groupsBefore: Collection<MaintenanceGroup>,
    schedulesAfter: Collection<MaintenanceSchedule>,
    groupsAfter: Collection<MaintenanceGroup>,
): SeasonStartStrands? {
    val names = assets.associate { it.id to it.name }
    return affected.distinct()
        .sortedWith(compareBy({ names[it].orEmpty() }, { it.value }))
        .firstNotNullOfOrNull { assetId ->
            val dependents = openingServiceDependentsOf(assetId, schedulesAfter)
            val lost = dependents.isNotEmpty() &&
                roleCoverageOf(assetId, schedulesBefore, groupsBefore).covering.isNotEmpty() &&
                roleCoverageOf(assetId, schedulesAfter, groupsAfter).covering.isEmpty()
            if (lost) dependentsStrand(assetId, dependents) else null
        }
}

/**
 * #124 (C42) — whether [schedule] may count from its asset's opening service: null when it may, or when it does not
 * ask to; otherwise, in order, [SeasonStartRefusal.CountsFromItself] (it is a role schedule itself),
 * [SeasonStartRefusal.YearRound] ([asset] is in use all year, P114-17) or [SeasonStartRefusal.NoSeasonStartService] (no
 * live role covers [asset], by C9's coverage over [schedules] and [groups], a group's role included). A group target is
 * not judged here: it never takes the answer (inv. 106, refused before). Pure.
 */
internal fun openingServiceRefusalOf(
    schedule: MaintenanceSchedule,
    asset: Asset?,
    schedules: Collection<MaintenanceSchedule>,
    groups: Collection<MaintenanceGroup>,
): SeasonStartRefusal? {
    if (schedule.servicePolicy != ServicePolicy.IN_SERVICE_AFTER_OPENING_SERVICE || asset == null) return null
    return if (schedule.role == ScheduleRole.SEASON_START) {
        SeasonStartRefusal.CountsFromItself
    } else if (asset.seasonMode == SeasonMode.YEAR_ROUND) {
        SeasonStartRefusal.YearRound(asset.id)
    } else if (roleCoverageOf(asset.id, schedules, groups).covering.isEmpty()) {
        SeasonStartRefusal.NoSeasonStartService(asset.id)
    } else {
        null
    }
}

/** #114 / #124 — the assets [schedule]'s target names: its asset, or its group's open members among [groups]. */
internal fun assetsAimedAt(schedule: MaintenanceSchedule, groups: Collection<MaintenanceGroup>): List<AssetId> =
    when (val target = schedule.target) {
        is ScheduleTarget.AssetTarget -> listOf(target.assetId)
        is ScheduleTarget.GroupTarget -> groups.firstOrNull { it.id == target.groupId }
            ?.openMembers().orEmpty().map { it.assetId }
    }

/**
 * #114 (C11 erratum, M-4) — **the restore rule**, for `ArchiveSchedule` and `ArchiveGroup` bringing a row back from the
 * archive. Null unless a role schedule among [restored] would cover again an asset that fails C10's asset rule
 * ([SeasonStartStrandCause.RESTORE_NOT_ELIGIBLE]) or that another live role covers too
 * ([SeasonStartStrandCause.RESTORE_ALREADY_COVERED], C9's `TWO_ROLES`): the first such asset by name then id, with
 * every role that would cover it. [schedules] and [groups] are every row **as the restore leaves them**, [assets] the
 * stored tree and [held] the transferred-out ids. Pure: it reads no store and throws nothing.
 */
internal fun seasonStartRestoreStrandOf(
    restored: Set<ScheduleId>,
    assets: Collection<Asset>,
    held: Set<AssetId>,
    schedules: Collection<MaintenanceSchedule>,
    groups: Collection<MaintenanceGroup>,
): SeasonStartStrands? {
    val aimedAt = schedules.filter { it.id in restored }.flatMapTo(mutableSetOf()) { assetsAimedAt(it, groups) }
    return assets.filter { it.id in aimedAt }
        .sortedWith(compareBy({ it.name }, { it.id.value }))
        .firstNotNullOfOrNull { asset ->
            val covering = seasonStartRolesCovering(asset.id, schedules, groups)
            val refusal = seasonStartAssetRefusal(asset, assets, held)
            val cause = if (covering.none { it.id in restored }) {
                return@firstNotNullOfOrNull null
            } else if (refusal != null) {
                SeasonStartStrandCause.RESTORE_NOT_ELIGIBLE
            } else if (covering.size > 1) {
                SeasonStartStrandCause.RESTORE_ALREADY_COVERED
            } else {
                return@firstNotNullOfOrNull null
            }
            SeasonStartStrands(asset.id, cause, covering.map { StrandedSchedule(it.id, it.title) }, refusal)
        }
}

/**
 * #114 — C9's coverage ([roleCoverageOf]) for the commands: the live `SEASON_START` schedules covering [assetId],
 * [excluding] left out, by title then id. One rule with `seasonReadiness`, so a command never admits a second role
 * that readiness would read as `TWO_ROLES`.
 */
internal fun seasonStartRolesCovering(
    assetId: AssetId,
    schedules: Collection<MaintenanceSchedule>,
    groups: Collection<MaintenanceGroup>,
    excluding: ScheduleId? = null,
): List<MaintenanceSchedule> = roleCoverageOf(assetId, schedules, groups).covering
    .filter { it.id != excluding }
    .sortedWith(compareBy({ it.title }, { it.id.value }))

/** The order every activation list is read in: `(occurredOn, createdAt, id)` (spec §3.3). */
internal val ACTIVATION_ORDER: Comparator<SeasonActivation> =
    compareBy({ it.occurredOn }, { it.createdAt }, { it.id })

/** The latest of [rows], whatever order they were handed in. */
internal fun latestOf(rows: List<SeasonActivation>): SeasonActivation? = rows.maxWithOrNull(ACTIVATION_ORDER)

/**
 * #78's count, lifted by #16 (C27): how many of [schedules] are live — ACTIVE or PAUSED, never ARCHIVED — and
 * CONTINUOUS, the ones that keep coming due out of season (P78-1a/1b). The asset editor asks with it after a save out
 * of YEAR_ROUND, and the Home Assistant setup sheet after a link or a resume out of YEAR_ROUND.
 */
fun liveContinuousCount(schedules: List<MaintenanceSchedule>): Int =
    schedules.count { it.status != ScheduleStatus.ARCHIVED && it.servicePolicy == ServicePolicy.CONTINUOUS }
