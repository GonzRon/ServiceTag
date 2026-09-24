package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonInputs
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.testing.SeasonFixtures.activationOf
import com.loosecannon.servicetag.core.testing.SeasonFixtures.seasonOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Invariants 84, 85 and 96 as properties over generated histories, policies and season contexts.
 *
 * The generator is seeded, so a failure names a case that reproduces. Each case is a random rule, a
 * random asset season (YEAR_ROUND, CALENDAR or MANUAL, with or without a break, with or without
 * activation rows), and a history built the way the app builds one: a completion stamps the
 * `occurrence_on` the engine currently reports (inv. 85), on a random day around it.
 */
class PolicyInvarianceTest {

    private val cases = 300

    /**
     * Inv. 84 and 85: for the same history every policy yields CONTINUOUS's `computedDueOn` and
     * `effectiveDueOn` on every day — and so the key each completion claims is the same whatever the
     * policy, because the key is read off `computedDueOn`, which no policy touches.
     */
    @Test
    fun everyPolicyKeepsContinuousComputedDueOn() {
        val random = Random(1_404)
        repeat(cases) { case ->
            val base = randomSchedule(random)
            val season = randomSeason(random)
            val postponed = if (random.nextInt(4) == 0) LocalDate.parse("2026-01-01").plusDays(random.nextLong(0, 900)) else null
            val offsets = mapOf(
                ServicePolicy.CONTINUOUS to null,
                ServicePolicy.IN_SERVICE_AT_START to random.nextInt(0, 60),
                ServicePolicy.IN_SERVICE_RESUME_CLAMPED to null,
                ServicePolicy.PRE_SERVICE to -random.nextInt(1, 60),
            )
            val steps = (1..random.nextInt(2, 7)).map { random.nextLong(-40, 120) }
            val keys = offsets.map { (policy, offset) ->
                val schedule = base.copy(
                    servicePolicy = policy,
                    policyOffsetDays = offset,
                    postponedDueOn = postponed?.toString(),
                )
                policy to history(schedule, season, steps)
            }.toMap()
            val continuous = keys.getValue(ServicePolicy.CONTINUOUS)
            keys.forEach { (policy, trace) ->
                assertEquals(continuous, trace, "case $case: $policy moved the occurrence key or its sequence")
            }
        }
    }

    /**
     * Inv. 96: a CONTINUOUS schedule's actionable date is `P ?: R` whatever the season or break, its
     * reason is NONE, and it is never quiet or dormant.
     */
    @Test
    fun continuousActionableIsPostponedOrRaw() {
        val random = Random(9_606)
        repeat(cases) { case ->
            val postponed = if (random.nextBoolean()) LocalDate.parse("2026-01-01").plusDays(random.nextLong(0, 900)) else null
            val schedule = randomSchedule(random).copy(
                servicePolicy = ServicePolicy.CONTINUOUS,
                policyOffsetDays = null,
                postponedDueOn = postponed?.toString(),
            )
            val season = randomSeason(random)
            val today = LocalDate.parse("2026-01-01").plusDays(random.nextLong(0, 1_000))
            val state = ScheduleRecompute.rebuild(schedule, emptyList(), emptyList(), emptyList(), today, ZoneOffset.UTC, season)
            assertEquals(state.effectiveDueOn, state.actionableDueOn, "case $case on $today")
            assertEquals(postponed?.toString() ?: state.computedDueOn, state.actionableDueOn, "case $case on $today")
            assertEquals(PolicyReason.NONE, state.policyReason)
            assertEquals(false, state.quiet, "case $case: CONTINUOUS is never quiet")
            assertEquals(PolicyPhase.ACTIVE, state.policyPhase)
        }
    }

    /**
     * Walks the schedule through [steps] completions: each one claims the key the engine reports on
     * the day it is logged, is dated a random distance from that key, and the day advances past it.
     * Returns every day's `(computedDueOn, effectiveDueOn)` and every key claimed.
     */
    private fun history(schedule: MaintenanceSchedule, season: SeasonInputs, steps: List<Long>): List<Any?> {
        val events = mutableListOf<AssetEvent>()
        val trace = mutableListOf<Any?>()
        var today = LocalDate.parse("2026-02-01")
        steps.forEachIndexed { index, offset ->
            val state = ScheduleRecompute.rebuild(schedule, events, emptyList(), emptyList(), today, ZoneOffset.UTC, season)
            trace += state.computedDueOn to state.effectiveDueOn
            val key = state.computedDueOn ?: return trace
            val doneOn = LocalDate.parse(key).plusDays(offset)
            events += completionOf("e$index", occurredOn = doneOn.toString(), occurrenceOn = key)
            trace += key
            today = maxOf(today, doneOn).plusDays(1)
        }
        return trace
    }

    private fun randomSchedule(random: Random): MaintenanceSchedule {
        val unit = RecurrenceUnit.entries[random.nextInt(RecurrenceUnit.entries.size)]
        val interval = when (unit) {
            RecurrenceUnit.DAY -> random.nextInt(1, 60)
            RecurrenceUnit.WEEK -> random.nextInt(1, 10)
            RecurrenceUnit.MONTH -> random.nextInt(1, 7)
            RecurrenceUnit.YEAR -> random.nextInt(1, 3)
        }
        val anchor = LocalDate.parse("2025-06-01").plusDays(random.nextLong(0, 500))
        return scheduleOf(
            timeInterval = interval,
            timeUnit = unit,
            timeBasis = if (random.nextBoolean()) TimeBasis.FIXED else TimeBasis.COMPLETION,
            anchorOn = anchor.toString(),
            leadDays = random.nextInt(0, 30),
            createdOn = "2026-01-15",
        )
    }

    private fun randomSeason(random: Random): SeasonInputs {
        fun mmdd(): String {
            val month = random.nextInt(1, 13)
            val day = random.nextInt(1, if (month == 2) 30 else 29)
            return "%02d-%02d".format(month, day)
        }
        val hasBreak = random.nextBoolean()
        val breakStart = if (hasBreak) mmdd() else null
        val breakEnd = if (hasBreak) mmdd() else null
        return when (random.nextInt(3)) {
            0 -> seasonOf(SeasonMode.YEAR_ROUND, breakStart = breakStart, breakEnd = breakEnd)
            1 -> seasonOf(SeasonMode.CALENDAR, mmdd(), mmdd(), breakStart, breakEnd)
            else -> {
                var day = LocalDate.parse("2025-10-01")
                val rows = (0 until random.nextInt(0, 6)).map { index ->
                    day = day.plusDays(random.nextLong(10, 200))
                    activationOf(
                        "act$index", "a1",
                        if (index % 2 == 0) SeasonAction.START else SeasonAction.END,
                        day.toString(),
                    )
                }
                seasonOf(SeasonMode.MANUAL, breakStart = breakStart, breakEnd = breakEnd, activations = rows)
            }
        }
    }
}
