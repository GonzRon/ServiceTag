package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.model.TimeBasis
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The schedule editor's raw input, before [SaveSchedule] turns it into a stored row.
 *
 * The two target ids are separate optional fields because that is the shape the wire and the form
 * both have; exactly one of them is set, and the domain's sealed `ScheduleTarget` is what makes the
 * other states unrepresentable once validation has run. `postponedDueOn` is **not** here: the
 * postponement is an operation with its own entry point, not a field of the rule, which is exactly
 * the distinction that stops a postpone from quietly becoming a reschedule.
 *
 * `status`, `createdAt`, `updatedAt` and every derived field are absent for the shipped reason a
 * request shape is a subset of a response shape.
 */
data class ScheduleCommand(
    val targetAssetId: AssetId?,
    val targetGroupId: GroupId?,
    val title: String,
    val description: String = "",
    val timeInterval: Int? = null,
    val timeUnit: RecurrenceUnit? = null,
    val timeBasis: TimeBasis = TimeBasis.FIXED,
    val anchorOn: String? = null,
    val leadDays: Int = 0,
    val meterDefinitionId: DefinitionId? = null,
    val meterInterval: Double? = null,
    val anchorMeter: Double? = null,
    val meterLead: Double? = null,
    val seasonBehavior: SeasonBehavior = SeasonBehavior.IGNORE,
    val seasonReentry: String? = null,
    val seasonReentryOffsetDays: Int? = null,
    val completionMode: CompletionMode = CompletionMode.QUICK,
    val profileId: ProfileId? = null,
    val remindersEnabled: Boolean = true,
    val providers: List<ScheduleProviderRow> = emptyList(),
)

/**
 * One completion of one occurrence: when the work was done, and whatever the profile form
 * collected. `occurrenceOn` is **not** here — it is stamped from the schedule's own computed due
 * date at write time, never sent by a caller and never the postponed date, which is what keeps an
 * occurrence key immutable and a postponement out of the idempotence index.
 *
 * [assetId] names the member who did the work. For an asset-targeted schedule it is absent or the
 * schedule's own Asset; for a group-targeted one it is required, and validating it as a required
 * member of the occurrence is the groups brief's.
 */
data class CompletionCommand(
    val occurredOn: String,
    val occurredTime: String? = null,
    val tzId: String,
    val notes: String = "",
    val values: Map<DefinitionId, String> = emptyMap(),
    val consumables: List<ConsumableInput> = emptyList(),
    val assetId: AssetId? = null,
)

/**
 * One thing wrong with a [ScheduleCommand], or with a postpone aimed at a schedule that has no date
 * to move. **Every member is a bad-rule refusal** — what was asked for describes a schedule that
 * cannot exist — which is why they all answer 422 on the wire and never 409; 409 is reserved for
 * refusals about *state*. The names are the domain's own, so the wire can render them without a
 * second vocabulary.
 */
sealed interface ScheduleProblem {
    /** Both targets set, or neither. A schedule is for exactly one Asset or exactly one group. */
    data object TargetInvalid : ScheduleProblem

    /** Neither a time rule nor a meter rule: there is nothing for the engine to evaluate. */
    data object NoRuleSide : ScheduleProblem

    /** A meter is a reading of one Asset's definition, so a group target can carry no meter rule. */
    data object MeterRuleOnGroupTarget : ScheduleProblem

    /** A season window lives on an Asset, so a group target is `IGNORE` only. */
    data object SeasonFollowsAssetOnGroupTarget : ScheduleProblem

    /** A profile is one Asset's quick action, so a group target carries no `profileId`. */
    data object ProfileOnGroupTarget : ScheduleProblem

    /** The profile belongs to a different Asset, or to none. */
    data class ForeignProfile(val profileId: ProfileId) : ScheduleProblem

    /** The meter definition belongs to a different Asset, or to none. */
    data class ForeignMeterDefinition(val definitionId: DefinitionId) : ScheduleProblem

    /** A definition that is not a meter cannot be a meter rule's counter. */
    data class MeterDefinitionNotAMeter(val definitionId: DefinitionId) : ScheduleProblem

    /** `timeInterval` must be at least 1: "every 0 months" is not a cadence. */
    data object TimeIntervalNotPositive : ScheduleProblem

    /** A negative lead would make a schedule due before it is due. */
    data object NegativeLeadDays : ScheduleProblem

    /** A time rule with no anchor has no series to lie on. */
    data object AnchorRequired : ScheduleProblem

    /** The anchor is not an ISO `YYYY-MM-DD` date. */
    data object BadAnchorDate : ScheduleProblem

    /** A time rule needs a unit as well as an interval. */
    data object TimeUnitRequired : ScheduleProblem

    /**
     * A meter rule with no interval has no threshold, so the schedule would report `NO_DATA` for
     * ever and adding an `anchorMeter` would not repair it — a third no-data state the gate does
     * not allow, and the reason this is refused at the command rather than tolerated by the engine.
     */
    data object MeterIntervalRequired : ScheduleProblem

    /**
     * A meter interval of zero or less puts the threshold at or below the baseline, which makes the
     * schedule instantly and permanently due. Not a number is refused the same way.
     */
    data object MeterIntervalNotPositive : ScheduleProblem

    /**
     * A postpone was aimed at a schedule with no time rule. There is no occurrence date to move —
     * `computedDueOn` is null for a meter-only schedule — and writing `postponed_due_on` anyway
     * would give the sort key a date the schedule does not have, against invariant 10. Clearing a
     * postponement is always allowed, so a row that arrived with one set can still be cleaned up.
     */
    data object PostponeNeedsTimeRule : ScheduleProblem
}

/** Validation failed; every problem found, collected once rather than fail-fast. */
class ScheduleValidation(val problems: List<ScheduleProblem>) :
    IllegalArgumentException("invalid schedule: $problems")

/** The schedule this operation was aimed at is no longer there. */
class NoSuchSchedule(id: ScheduleId) : IllegalArgumentException("no schedule ${id.value}")

/** The group a schedule names does not exist. */
class NoSuchGroup(id: GroupId) : IllegalArgumentException("no group ${id.value}")

/** A completion was aimed at an archived schedule: a refusal about state, not about the rule. */
class ScheduleArchived(val id: ScheduleId) :
    IllegalStateException("schedule ${id.value} is archived")

/**
 * A completion was aimed at a group-targeted schedule. Which member did the work, and whether that
 * member is required for this occurrence, is derived from the membership windows and the previous
 * occurrence's terminating rows — the groups brief's derivation. Refusing here is deliberate: the
 * alternative would be to write an event against an asset this brief cannot prove is a member.
 */
class GroupCompletionNotSupported(val id: ScheduleId) :
    IllegalStateException("schedule ${id.value} targets a group")

/** A date field was not an ISO `YYYY-MM-DD` date. */
class BadScheduleDate(val value: String) : IllegalArgumentException("not a date: $value")

private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

internal fun parseDate(value: String): LocalDate? {
    if (!DATE.matches(value)) return null
    return try {
        LocalDate.parse(value)
    } catch (e: DateTimeParseException) {
        null
    }
}

/**
 * Everything wrong with [cmd], collected. The target's own shape is checked first because three of
 * the other rules are *about* a group target, and reporting "a meter rule on a group target" for a
 * command that names no target at all would send an editor chasing the wrong field.
 *
 * [profile] and [meter] are the rows the caller already resolved by id, or null when the id names
 * nothing at all — which is the same answer as "belongs to another Asset" as far as this command is
 * concerned, and is reported as such.
 */
internal fun scheduleProblems(
    cmd: ScheduleCommand,
    profileAssetId: AssetId?,
    meter: MeasurementDefinition?,
): List<ScheduleProblem> {
    val problems = mutableListOf<ScheduleProblem>()
    val target = cmd.target()
    if (target == null) problems += ScheduleProblem.TargetInvalid

    val hasTime = cmd.timeInterval != null
    val hasMeter = cmd.meterDefinitionId != null
    if (!hasTime && !hasMeter) problems += ScheduleProblem.NoRuleSide

    if (hasMeter) {
        val interval = cmd.meterInterval
        when {
            interval == null -> problems += ScheduleProblem.MeterIntervalRequired
            !interval.isFinite() || interval <= 0.0 -> problems += ScheduleProblem.MeterIntervalNotPositive
        }
    }

    if (hasTime) {
        if (cmd.timeInterval < 1) problems += ScheduleProblem.TimeIntervalNotPositive
        if (cmd.timeUnit == null) problems += ScheduleProblem.TimeUnitRequired
        val anchor = cmd.anchorOn
        if (anchor == null) problems += ScheduleProblem.AnchorRequired
        else if (parseDate(anchor) == null) problems += ScheduleProblem.BadAnchorDate
    }
    if (cmd.leadDays < 0) problems += ScheduleProblem.NegativeLeadDays

    if (target is ScheduleTarget.GroupTarget) {
        if (hasMeter) problems += ScheduleProblem.MeterRuleOnGroupTarget
        if (cmd.profileId != null) problems += ScheduleProblem.ProfileOnGroupTarget
        if (cmd.seasonBehavior == SeasonBehavior.FOLLOW_ASSET) {
            problems += ScheduleProblem.SeasonFollowsAssetOnGroupTarget
        }
    }

    val assetId = (target as? ScheduleTarget.AssetTarget)?.assetId
    cmd.profileId?.let { id ->
        if (assetId != null && profileAssetId != assetId) problems += ScheduleProblem.ForeignProfile(id)
    }
    cmd.meterDefinitionId?.let { id ->
        when {
            assetId == null -> Unit    // already reported as a group or an invalid target
            meter == null || meter.assetId != assetId -> problems += ScheduleProblem.ForeignMeterDefinition(id)
            !meter.isMeter -> problems += ScheduleProblem.MeterDefinitionNotAMeter(id)
        }
    }
    return problems
}

/** The one place the two optional target ids become one value, or nothing at all. */
internal fun ScheduleCommand.target(): ScheduleTarget? = when {
    targetAssetId != null && targetGroupId == null -> ScheduleTarget.AssetTarget(targetAssetId)
    targetGroupId != null && targetAssetId == null -> ScheduleTarget.GroupTarget(targetGroupId)
    else -> null
}
