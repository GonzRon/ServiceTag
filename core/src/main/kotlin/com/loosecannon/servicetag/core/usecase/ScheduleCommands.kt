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
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.reminders.ProviderId
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
    val servicePolicy: ServicePolicy = ServicePolicy.CONTINUOUS,
    val policyOffsetDays: Int? = null,
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

    /** A season lives on an Asset, so a group target is CONTINUOUS only. */
    data object SeasonFollowsAssetOnGroupTarget : ScheduleProblem

    /** A profile is one Asset's quick action, so a group target carries no `profileId`. */
    data object ProfileOnGroupTarget : ScheduleProblem

    /**
     * A group target is **QUICK-only** (D-12). It carries no `profileId` — the rule above — so
     * `FORM` names a form with nothing to collect and no profile to collect it against, and the
     * member-completion path is left picking an event source for a completion that has no form
     * behind it. `CompleteGroupMembers` records that gap in a comment and names the schedule
     * command as its owner; this is the rule it was waiting for.
     */
    data object FormCompletionOnGroupTarget : ScheduleProblem

    /**
     * The group has no open membership, so the schedule's very first round would oblige nobody: it
     * would report `NO_DATA`, could not be completed and could not be closed, and no later
     * membership change would rescue it — a member added afterwards joins the round *after* the one
     * already open, and that round can never terminate. Refused at the command, where the editor can
     * say "add a member first", rather than stored as a schedule the engine cannot evaluate
     * (spec §2.4, invariants 74, 77).
     */
    data object EmptyGroupTarget : ScheduleProblem

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
     * A **negative** meter lead, which would warn *after* the threshold rather than before it: the
     * lead subtracts from the due reading (`current >= due - lead`), so below zero it moves the
     * warning past the moment the schedule is already due and the "due soon" state can never be
     * reached. Not a number is refused the same way, as the interval's is.
     *
     * Refused rather than clamped, so the value a caller sent is never quietly turned into a
     * different one — and so an editor can mark the field instead of dropping what was typed.
     */
    data object NegativeMeterLead : ScheduleProblem

    /**
     * A postpone was aimed at a schedule with no time rule. There is no occurrence date to move —
     * `computedDueOn` is null for a meter-only schedule — and writing `postponed_due_on` anyway
     * would give the sort key a date the schedule does not have, against invariant 10. Clearing a
     * postponement is always allowed, so a row that arrived with one set can still be cleaned up.
     */
    data object PostponeNeedsTimeRule : ScheduleProblem

    /**
     * A `schedule_provider` row naming something that is not a [ProviderId].
     *
     * The column is TEXT — it has to be, because a provider set grows without a migration — and the
     * subject builder matches it against `ProviderId` **by name**. A row naming anything else is a
     * schedule with reminders switched on and no provider that will ever read it: silently
     * undeliverable, and surfacing later as a health finding the owner has to chase rather than a
     * refusal where the value was typed. In 1.2 the known set is `LOCAL` alone (decision 8).
     */
    data class UnknownProvider(val provider: String) : ScheduleProblem
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
 * A completion was aimed at a group-targeted schedule through the operation that names no member.
 * Which member did the work, and whether that member is required for this occurrence, is derived
 * from the membership windows and the previous occurrence's terminating rows — so a group round is
 * completed through [CompleteGroupMembers], which takes that member list. Refusing here is
 * permanent, not provisional: the alternative would be to write an event against an asset this
 * operation cannot name.
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
 * Everything wrong with [cmd], collected. The target's own shape is checked first because four of
 * the other rules are *about* a group target, and reporting "a meter rule on a group target" for a
 * command that names no target at all would send an editor chasing the wrong field.
 *
 * [profileAssetId] and [meter] are the rows the caller already resolved by id, or null when the id
 * names nothing at all — which is the same answer as "belongs to another Asset" as far as this
 * command is concerned, and is reported as such. [groupOpenMembers] is how many open membership
 * windows the named group holds, or null when there is no group or it does not exist.
 */
internal fun scheduleProblems(
    cmd: ScheduleCommand,
    profileAssetId: AssetId?,
    meter: MeasurementDefinition?,
    groupOpenMembers: Int? = null,
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
    // Checked whatever the rule side: a lead is only *read* beside a meter rule, but a negative one
    // is never legal and refusing it wherever it arrives is what lets every caller — the editor,
    // the API — be told which field is wrong rather than having the value silently altered.
    cmd.meterLead?.let { lead ->
        if (!lead.isFinite() || lead < 0.0) problems += ScheduleProblem.NegativeMeterLead
    }

    if (hasTime) {
        if (cmd.timeInterval < 1) problems += ScheduleProblem.TimeIntervalNotPositive
        if (cmd.timeUnit == null) problems += ScheduleProblem.TimeUnitRequired
        val anchor = cmd.anchorOn
        if (anchor == null) problems += ScheduleProblem.AnchorRequired
        else if (parseDate(anchor) == null) problems += ScheduleProblem.BadAnchorDate
    }
    if (cmd.leadDays < 0) problems += ScheduleProblem.NegativeLeadDays

    cmd.providers.forEach { row ->
        if (ProviderId.entries.none { it.name == row.provider }) {
            problems += ScheduleProblem.UnknownProvider(row.provider)
        }
    }

    if (target is ScheduleTarget.GroupTarget) {
        if (hasMeter) problems += ScheduleProblem.MeterRuleOnGroupTarget
        if (cmd.profileId != null) problems += ScheduleProblem.ProfileOnGroupTarget
        if (cmd.completionMode == CompletionMode.FORM) {
            problems += ScheduleProblem.FormCompletionOnGroupTarget
        }
        if (cmd.servicePolicy != ServicePolicy.CONTINUOUS) {
            problems += ScheduleProblem.SeasonFollowsAssetOnGroupTarget
        }
        // Null means the caller could not resolve the group at all, which is reported as
        // `NoSuchGroup` and is not this function's to guess at.
        if (groupOpenMembers == 0) problems += ScheduleProblem.EmptyGroupTarget
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
