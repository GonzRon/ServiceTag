package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.SeasonFixtures.seasonOf
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The maintenance break (spec §4.4, inv. 102): quiet, the park date, and CONTINUOUS's indifference. */
class BreakTest {

    private fun on(date: String) = LocalDate.parse(date)

    private val generatorSeason = SeasonFixtures.generatorAsset().seasonInputs(emptyList())

    private fun outcome(policy: ServicePolicy, raw: String, at: String, season: SeasonContext) = ServicePolicyEngine.evaluate(
        PolicyInputs(
            policy = policy,
            offsetDays = when (policy) {
                ServicePolicy.PRE_SERVICE -> -14
                ServicePolicy.IN_SERVICE_AT_START -> 0
                else -> null
            },
            rawDueOn = on(raw),
            postponedDueOn = null,
            openedOn = on("2026-01-05"),
        ),
        season,
        on(at),
    )

    /** `quiet` ⇔ policy ≠ CONTINUOUS ∧ `T` in the break, on the break's first and last days too. */
    @Test
    fun quietIsNonContinuousInsideTheBreak() {
        val season = SeasonContext.of(generatorSeason)
        for (policy in ServicePolicy.entries) {
            val expectQuiet = policy != ServicePolicy.CONTINUOUS
            for (at in listOf("2026-12-01", "2027-01-15", "2027-02-28")) {
                assertEquals(expectQuiet, outcome(policy, "2026-06-20", at, season).quiet, "$policy on $at")
            }
            for (at in listOf("2026-11-30", "2027-03-01", "2026-07-01")) {
                assertEquals(false, outcome(policy, "2026-06-20", at, season).quiet, "$policy on $at")
            }
        }
    }

    /**
     * F3's H.3c branch (spec §7.4): the 20 Jun 2026 occurrence left open. On 1 Dec it is still
     * OVERDUE against its own date — not moved, not deferred, still counting as due — and quiet
     * through the break, which is what stops it notifying.
     */
    @Test
    fun alreadyLateWorkStaysOverdueAndQuiet() {
        val schedule = SeasonFixtures.generatorSchedule()
        for (at in listOf("2026-12-01", "2027-01-01", "2027-02-28")) {
            val state = ScheduleRecompute.rebuild(schedule, emptyList(), emptyList(), emptyList(), on(at), ZoneOffset.UTC, generatorSeason)
            assertEquals("2026-06-20", state.computedDueOn)
            assertEquals("2026-06-20", state.actionableDueOn, "late work is not moved out of the break")
            assertEquals(PolicyReason.NONE, state.policyReason)
            assertTrue(state.quiet, "quiet on $at")
            val status = statusOf(schedule, state, on(at))
            assertEquals(DueStatus.OVERDUE, status, "on $at")
            assertTrue(status.countsAsDue)
        }
        val after = ScheduleRecompute.rebuild(schedule, emptyList(), emptyList(), emptyList(), on("2027-03-01"), ZoneOffset.UTC, generatorSeason)
        assertEquals(false, after.quiet, "the break is over on 1 Mar")
        assertEquals(DueStatus.OVERDUE, statusOf(schedule, after, on("2027-03-01")))
    }

    /** Inv. 102, inv. 96: a CONTINUOUS date inside the break stays where it is, and nothing is quiet. */
    @Test
    fun continuousIgnoresTheBreak() {
        val season = SeasonContext.of(generatorSeason)
        val inside = outcome(ServicePolicy.CONTINUOUS, raw = "2026-12-20", at = "2026-12-20", season = season)
        assertEquals(on("2026-12-20"), inside.actionableOn)
        assertEquals(PolicyReason.NONE, inside.reason)
        assertEquals(false, inside.quiet)
        assertNull(inside.quietUntil)

        val schedule = SeasonFixtures.generatorSchedule().copy(servicePolicy = ServicePolicy.CONTINUOUS, policyOffsetDays = null)
        val events = listOf(SeasonFixtures.generatorCompletedInOctober())
        val state = ScheduleRecompute.rebuild(schedule, events, emptyList(), emptyList(), on("2026-12-20"), ZoneOffset.UTC, generatorSeason)
        assertEquals("2026-12-20", state.actionableDueOn)
        assertEquals(false, state.quiet)
        assertEquals(DueStatus.DUE, statusOf(schedule, state, on("2026-12-20")))
    }

    /**
     * `quietUntil` is the first allowed day after the break the day sits in — across the year's end
     * for a wrapping break — and null whenever the item is not quiet, CONTINUOUS always included.
     */
    @Test
    fun quietUntilIsTheFirstAllowedDayAfterTheBreakAndNullOtherwise() {
        val season = SeasonContext.of(generatorSeason)
        for (at in listOf("2026-12-01", "2026-12-31", "2027-01-01", "2027-02-28")) {
            assertEquals(
                on("2027-03-01"),
                outcome(ServicePolicy.IN_SERVICE_AT_START, "2026-06-20", at, season).quietUntil,
                "on $at",
            )
            assertEquals(on("2027-03-01"), outcome(ServicePolicy.PRE_SERVICE, "2026-06-20", at, season).quietUntil)
            assertNull(outcome(ServicePolicy.CONTINUOUS, "2026-06-20", at, season).quietUntil, "CONTINUOUS is never quiet")
        }
        assertNull(outcome(ServicePolicy.IN_SERVICE_AT_START, "2026-06-20", "2026-11-30", season).quietUntil)
        assertNull(outcome(ServicePolicy.IN_SERVICE_AT_START, "2026-06-20", "2027-03-01", season).quietUntil)

        val leap = SeasonContext.of(seasonOf(SeasonMode.YEAR_ROUND, breakStart = "12-01", breakEnd = "02-29"))
        assertEquals(on("2028-03-01"), outcome(ServicePolicy.IN_SERVICE_AT_START, "2027-06-20", "2028-02-29", leap).quietUntil)
        assertEquals(on("2027-03-01"), outcome(ServicePolicy.IN_SERVICE_AT_START, "2026-06-20", "2027-02-28", leap).quietUntil)
    }
}
