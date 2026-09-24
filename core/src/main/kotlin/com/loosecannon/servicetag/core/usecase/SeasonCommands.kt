package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
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

/** 409: an activation was aimed at an asset whose season is not MANUAL. */
class SeasonNotManual(val assetId: AssetId) :
    IllegalStateException("asset ${assetId.value} is not in MANUAL season mode")

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

/** The order every activation list is read in: `(occurredOn, createdAt, id)` (spec §3.3). */
internal val ACTIVATION_ORDER: Comparator<SeasonActivation> =
    compareBy({ it.occurredOn }, { it.createdAt }, { it.id })

/** The latest of [rows], whatever order they were handed in. */
internal fun latestOf(rows: List<SeasonActivation>): SeasonActivation? = rows.maxWithOrNull(ACTIVATION_ORDER)
