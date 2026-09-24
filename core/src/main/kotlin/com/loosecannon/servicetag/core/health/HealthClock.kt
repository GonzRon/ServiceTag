package com.loosecannon.servicetag.core.health

import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.schedule.PolicyInputs
import com.loosecannon.servicetag.core.schedule.SeasonContext
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.schedule.ServicePolicyEngine
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The health clock (spec §7.1, master plan §10.2): how many days a MAINTENANCE_OVERDUE subject's
 * linked occurrence has been late, counted the way the policy sees the calendar rather than the way
 * a raw date does.
 *
 * A day `d` is **counted** when all of these hold:
 * - the policy phase on `d` is ACTIVE;
 * - `d > A(d)`, where `A(d)` is `ServicePolicyEngine.evaluate(link, season, at = d).actionableOn`
 *   evaluated with **today's** configuration and postponement — never the postponement as it stood
 *   on `d` (owner ruling, 2026-09-24; inv. 116, 131);
 * - for a MEDIUM subject on an IN_SERVICE schedule only, `d` is on or after the latest cycle start at
 *   or before `T`, when there is one (inv. 114).
 *
 * `d` ranges over `min(O, P ?: R) < d ≤ T` (plan decision 16). The range is an **evaluation bound
 * only**: the policy never puts `A` before that bound, so no day at or below it could pass `d > A(d)`
 * anyway, and after a postponement nothing before the postponed actionable date contributes. It
 * starts at `R` or `P` rather than `O` because `R < O` is real — a never-terminated COMPLETION
 * schedule is due on its anchor, which can precede the rule-change date `O`.
 *
 * **Spans, not days.** The policy reads the day it is asked about only through the season's phase
 * and cycle start (master plan §8.2), so along a run of days that share both, the phase and `A` are
 * constant and the counted days of the run are one subtraction. The break is not a boundary here:
 * it moves `A` through the policy, and `A` does not depend on the day. The runs are found from the
 * season's own answers, so nothing here re-derives a season, and the day-by-day definition above is
 * kept in the tests as the oracle this must equal.
 *
 * No meter side contributes (Q-2), no snooze is an input (O-6), and nothing here reads a clock.
 */
object HealthClock {

    fun countedDays(link: PolicyInputs, season: SeasonContext?, kind: HealthSubjectKind, today: LocalDate): Long {
        val due = link.postponedDueOn ?: link.rawDueOn ?: return 0L
        val inService = link.policy.isInService()
        var from = minOf(link.openedOn, due).plusDays(1)
        if (kind == HealthSubjectKind.MEDIUM && inService) {
            season?.latestCycleStartOnOrBefore(today)?.let { restart -> from = maxOf(from, restart) }
        }

        var counted = 0L
        var spanStart = from
        while (spanStart <= today) {
            val spanEnd = if (inService && season != null) lastDayOfRun(season, spanStart, today) else today
            val outcome = ServicePolicyEngine.evaluate(link, season, at = spanStart)
            val actionable = outcome.actionableOn
            if (outcome.phase == PolicyPhase.ACTIVE && actionable != null) {
                val firstLate = maxOf(spanStart, actionable.plusDays(1))
                if (firstLate <= spanEnd) counted += ChronoUnit.DAYS.between(firstLate, spanEnd) + 1
            }
            spanStart = spanEnd.plusDays(1)
        }
        return counted
    }

    private fun ServicePolicy.isInService(): Boolean =
        this == ServicePolicy.IN_SERVICE_AT_START || this == ServicePolicy.IN_SERVICE_RESUME_CLAMPED

    /**
     * The last day, no later than [today], of the run of days that [start] belongs to. A run is the
     * days sharing one phase, one cycle start and one latest cycle start. Each run is contiguous and
     * never recurs: a calendar year's season and off-season, and each manual START-to-END span and
     * the gap after it, all name a start no other run names. So "the same run as [start]" is true up
     * to the run's last day and false from then on, and a binary search finds that day.
     */
    private fun lastDayOfRun(season: SeasonContext, start: LocalDate, today: LocalDate): LocalDate {
        val run = runOf(season, start)
        if (runOf(season, today) == run) return today
        var inside = start
        var outside = today
        while (ChronoUnit.DAYS.between(inside, outside) > 1) {
            val middle = inside.plusDays(ChronoUnit.DAYS.between(inside, outside) / 2)
            if (runOf(season, middle) == run) inside = middle else outside = middle
        }
        return inside
    }

    private fun runOf(season: SeasonContext, d: LocalDate): Run =
        Run(season.phaseAt(d), season.cycleStartAt(d), season.latestCycleStartOnOrBefore(d))

    private data class Run(val phase: SeasonPhase, val cycleStart: LocalDate?, val latestCycleStart: LocalDate?)
}
