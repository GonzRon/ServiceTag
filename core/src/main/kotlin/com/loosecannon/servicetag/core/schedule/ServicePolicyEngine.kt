package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import java.time.LocalDate

/**
 * What the policy reads about one occurrence (master plan §8.1): the policy and its offset, the raw
 * due `R` (`computedDueOn`, the occurrence key), the postponement `P`, and `O`, the day the
 * occurrence opened — the last termination's effective date, or the rule-change date of a schedule
 * that has never terminated. Never a title, a category or a template (inv. 98).
 */
data class PolicyInputs(
    val policy: ServicePolicy,
    val offsetDays: Int?,
    val rawDueOn: LocalDate?,
    val postponedDueOn: LocalDate?,
    val openedOn: LocalDate,
)

/**
 * The policy's answer for one day (master plan §8.2). [actionableOn] is null for a meter-only
 * schedule and for a MANUAL asset waiting for its next START. [quietUntil] is the first allowed day
 * after the break the day sits in, and null whenever [quiet] is false; it is derived here and stored
 * nowhere.
 */
data class PolicyOutcome(
    val actionableOn: LocalDate?,
    val reason: PolicyReason,
    val phase: PolicyPhase,
    val quiet: Boolean,
    val quietUntil: LocalDate?,
)

/**
 * The one place a service policy is applied (spec §4.3's `policyDue`, normative; master plan §8.2).
 *
 * Pure and total: the answer is a function of the inputs, the season context and [evaluate]'s `at`,
 * and nothing here reads a clock or a repository. A null context — a group target, or an asset that
 * is gone — means no season and no break.
 *
 * The policy moves only the **time side's** date (D-20, O-7). It never touches `computedDueOn`, the
 * occurrence key (inv. 84): what it produces is the actionable date `A`, which is the status input
 * and the sort key, and nothing else.
 */
object ServicePolicyEngine {

    fun evaluate(inputs: PolicyInputs, season: SeasonContext?, at: LocalDate): PolicyOutcome {
        val inService = inputs.policy == ServicePolicy.IN_SERVICE_AT_START ||
            inputs.policy == ServicePolicy.IN_SERVICE_RESUME_CLAMPED
        val phase = if (inService && season?.phaseAt(at) == SeasonPhase.OUT_OF_SEASON) {
            PolicyPhase.DORMANT
        } else {
            PolicyPhase.ACTIVE
        }
        // Quiet belongs to the day, not to the due date: work already late when the break opens is
        // not moved, keeps its status and is quiet (inv. 102, H.3c). CONTINUOUS ignores the break.
        val quiet = inputs.policy != ServicePolicy.CONTINUOUS && season?.inBreak(at) == true
        val (actionableOn, reason) = actionable(inputs, season, at)
        return PolicyOutcome(
            actionableOn = actionableOn,
            reason = reason,
            phase = phase,
            quiet = quiet,
            quietUntil = if (quiet) season.firstAllowedAfter(at) else null,
        )
    }

    /**
     * `A` and its reason. The policy applies to `P ?: R`; a meter-only schedule has no time side, so
     * it has no actionable date at all and its phase and quiet are all the policy gives it (O-7).
     *
     * A reason that says the date **moved** is reported only when it did: a result equal to `P ?: R`
     * carries NONE (`PolicyReason`'s own contract). The strict pull in [preService] already ensures
     * this on every reachable path; the mapping here covers a break that fills the whole year, which
     * only a merge can store and under which there is no allowed day to move to.
     */
    private fun actionable(inputs: PolicyInputs, season: SeasonContext?, at: LocalDate): Pair<LocalDate?, PolicyReason> {
        val raw = inputs.rawDueOn ?: return null to PolicyReason.NONE
        val date = inputs.postponedDueOn ?: raw
        val (result, reason) = when (inputs.policy) {
            ServicePolicy.CONTINUOUS -> date to PolicyReason.NONE
            ServicePolicy.IN_SERVICE_AT_START, ServicePolicy.IN_SERVICE_RESUME_CLAMPED ->
                inService(inputs, date, season, at)
            ServicePolicy.PRE_SERVICE -> preService(inputs, raw, season)
        }
        return result to if (result == date && reason in MOVING) PolicyReason.NONE else reason
    }

    /** The reasons that claim the date moved; POLICY_INAPPLICABLE and AWAITING_START do not. */
    private val MOVING = setOf(
        PolicyReason.SEASON_START,
        PolicyReason.AFTER_BREAK,
        PolicyReason.BEFORE_SEASON,
        PolicyReason.BEFORE_BREAK,
    )

    /**
     * IN_SERVICE reads the cycle of **`at`**, not of the date (#14 AC 4): out of season that is the
     * next start, so the date re-enters there instead of reading as a backlog. AT_START moves a date
     * only when it is earlier than `s + offset`; RESUME_CLAMPED gives `max(date, s)`; neither drags a
     * later date back (inv. 97, O-5). A MANUAL asset out of season has no start to re-enter at — the
     * next START is never predicted — so it waits with no date (AWAITING_START). A date that lands in
     * the break then moves to the first allowed day after it.
     */
    private fun inService(
        inputs: PolicyInputs,
        date: LocalDate,
        season: SeasonContext?,
        at: LocalDate,
    ): Pair<LocalDate?, PolicyReason> {
        val s = season?.cycleStartAt(at)
        if (s == null && season?.mode == SeasonMode.MANUAL) return null to PolicyReason.AWAITING_START
        val reentered = when {
            s == null -> date
            inputs.policy == ServicePolicy.IN_SERVICE_AT_START -> {
                val opens = s.plusDays((inputs.offsetDays ?: 0).toLong())
                if (date < opens) opens else date
            }
            else -> maxOf(date, s)
        }
        if (season != null && season.inBreak(reentered)) {
            return season.firstAllowedAfter(reentered) to PolicyReason.AFTER_BREAK
        }
        return reentered to (if (reentered != date) PolicyReason.SEASON_START else PolicyReason.NONE)
    }

    /**
     * PRE_SERVICE (spec §4.3, Q-3, Q-4, O-4): the season is the objective and the break a constraint.
     *
     * The boundary `s` comes from the raw due `R`. A postponement is then taken as given — moved past
     * the break when it lies inside it, and **never earlier** (inv. 101). Otherwise an allowed `R` on
     * or before `s + offset` keeps its date; else the point is the latest day of the pre-season window
     * `W` on or before that deadline, else `W`'s first day, else — only when `W` is empty — the day
     * before the break. A point later than `R` applies unconditionally (R sat in the break); a point
     * earlier than `R` applies only when the occurrence opened before it (the opened-before guard),
     * and otherwise `R` is kept when allowed or moved past the break. The pull is **strict**: a point
     * equal to `R` moves nothing, and since the point is always an allowed day, `R` is kept with NONE.
     */
    private fun preService(inputs: PolicyInputs, raw: LocalDate, season: SeasonContext?): Pair<LocalDate?, PolicyReason> {
        val postponed = inputs.postponedDueOn
        // No boundary (YEAR_ROUND or MANUAL without a break): PRE_SERVICE behaves as CONTINUOUS. Only
        // a merge reaches this; every command refuses it (`PRE_SERVICE_NEEDS_DATES`).
        val inapplicable = (postponed ?: raw) to PolicyReason.POLICY_INAPPLICABLE
        if (season == null) return inapplicable
        val boundary = season.preServiceBoundary(raw) ?: return inapplicable
        if (postponed != null) {
            return if (season.inBreak(postponed)) {
                season.firstAllowedAfter(postponed) to PolicyReason.AFTER_BREAK
            } else {
                postponed to PolicyReason.NONE
            }
        }
        val s = boundary.on
        val deadline = s.plusDays((inputs.offsetDays ?: 0).toLong())
        if (raw <= deadline && season.allowed(raw)) return raw to PolicyReason.NONE

        val window = season.preSeasonWindow(s)
        val point = window?.lastOnOrBefore(deadline)
            ?: window?.first
            ?: season.dayBeforeBreakContaining(s.minusDays(1))
        if (point > raw) return point to PolicyReason.AFTER_BREAK
        return when {
            inputs.openedOn < point && point < raw -> point to
                if (window != null && point in window && boundary.kind == BoundaryKind.SEASON) {
                    PolicyReason.BEFORE_SEASON
                } else {
                    PolicyReason.BEFORE_BREAK
                }
            season.allowed(raw) -> raw to PolicyReason.NONE
            else -> season.firstAllowedAfter(raw) to PolicyReason.AFTER_BREAK
        }
    }
}
