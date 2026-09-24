package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.SeasonFixtures.seasonOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `ServicePolicyEngine.evaluate` against spec §4.3's rule with the literal values of spec §7.3 and
 * §12.1: the pre-service point, the opened-before guard, AT_START and RESUME_CLAMPED, #14 AC 4, and
 * the absence of inference (inv. 97, 98, 100).
 */
class ServicePolicyEvaluatorTest {

    private fun on(date: String) = LocalDate.parse(date)

    private val snowblower = SeasonContext.of(SeasonFixtures.snowblowerAsset().seasonInputs(emptyList()))
    private val mower = SeasonContext.of(SeasonFixtures.mowerAsset().seasonInputs(emptyList()))

    private fun preService(raw: String, opened: String, margin: Int = 14, postponed: String? = null) = PolicyInputs(
        policy = ServicePolicy.PRE_SERVICE,
        offsetDays = -margin,
        rawDueOn = on(raw),
        postponedDueOn = postponed?.let(::on),
        openedOn = on(opened),
    )

    private fun evaluate(inputs: PolicyInputs, season: SeasonContext, at: String = "2026-09-24") =
        ServicePolicyEngine.evaluate(inputs, season, on(at))

    /** §7.3: `s` = 15 Nov 2026, `W` = 1 Mar – 14 Nov, the latest day of `W` by 1 Nov is 1 Nov, and O < 1 Nov. */
    @Test
    fun snowblowerIsActionableOnFirstNovember() {
        val outcome = evaluate(preService(raw = "2026-12-20", opened = "2024-11-10"), snowblower)
        assertEquals(on("2026-11-01"), outcome.actionableOn)
        assertEquals(PolicyReason.BEFORE_SEASON, outcome.reason)
        assertEquals(PolicyPhase.ACTIVE, outcome.phase, "PRE_SERVICE is never dormant")
    }

    /** §7.3: R = 5 Nov 2026 is allowed and before 1 Apr 2027 — the autumn habit is kept. */
    @Test
    fun mowerAutumnDueIsKept() {
        val outcome = evaluate(preService(raw = "2026-11-05", opened = "2025-11-05"), mower)
        assertEquals(on("2026-11-05"), outcome.actionableOn)
        assertEquals(PolicyReason.NONE, outcome.reason)
    }

    /** §7.3: R = 12 Jan 2027 is in the break; `s` = 15 Apr 2027, `W` = 1 Mar – 14 Apr, the point is 1 Apr. */
    @Test
    fun mowerJanuaryDueGoesToFirstApril() {
        val outcome = evaluate(preService(raw = "2027-01-12", opened = "2026-03-02"), mower)
        assertEquals(on("2027-04-01"), outcome.actionableOn)
        assertEquals(PolicyReason.AFTER_BREAK, outcome.reason, "a point later than R applies unconditionally")
    }

    /** §7.3's first variant: a break to 04-05 leaves `W` = 6–14 Apr, none of it by 1 Apr: its first day. */
    @Test
    fun aLongerBreakTakesTheWindowsFirstDay() {
        val longBreak = SeasonContext.of(SeasonFixtures.mowerAsset(breakEnd = "04-05").seasonInputs(emptyList()))
        val outcome = evaluate(preService(raw = "2027-01-12", opened = "2026-03-02"), longBreak)
        assertEquals(on("2027-04-06"), outcome.actionableOn)
        assertEquals(PolicyReason.AFTER_BREAK, outcome.reason)
    }

    /** §7.3's second variant: 20 Apr 2027, inside the season, is pulled to 1 Apr only when O < 1 Apr. */
    @Test
    fun anInSeasonDueIsPulledOnlyWhenOpenedBefore() {
        val pulled = evaluate(preService(raw = "2027-04-20", opened = "2026-04-20"), mower)
        assertEquals(on("2027-04-01"), pulled.actionableOn)
        assertEquals(PolicyReason.BEFORE_SEASON, pulled.reason)

        val kept = evaluate(preService(raw = "2027-04-20", opened = "2027-04-02"), mower)
        assertEquals(on("2027-04-20"), kept.actionableOn)
        assertEquals(PolicyReason.NONE, kept.reason)
    }

    /**
     * §7.3's third variant (O-4): only a break that fills the whole gap up to 14 Apr leaves `W` empty,
     * and only then is the work pulled to the day before that break — here 31 Oct, in the previous
     * season — with BEFORE_BREAK, guard permitting; otherwise it waits for the first allowed day.
     */
    @Test
    fun aWholeGapBreakPullsToTheDayBeforeIt() {
        val wholeGap = SeasonContext.of(
            seasonOf(SeasonMode.CALENDAR, seasonStart = "04-15", seasonEnd = "10-31", breakStart = "11-01", breakEnd = "04-14"),
        )
        val pulled = evaluate(preService(raw = "2027-01-12", opened = "2026-03-02"), wholeGap)
        assertEquals(on("2026-10-31"), pulled.actionableOn)
        assertEquals(PolicyReason.BEFORE_BREAK, pulled.reason)

        val tooLate = evaluate(preService(raw = "2027-01-12", opened = "2026-10-31"), wholeGap)
        assertEquals(on("2027-04-15"), tooLate.actionableOn, "opened on the point: not pulled, moved past the break")
        assertEquals(PolicyReason.AFTER_BREAK, tooLate.reason)

        // A window of even one day wins over the pull (O-4).
        val oneDayLeft = SeasonContext.of(
            seasonOf(SeasonMode.CALENDAR, seasonStart = "04-15", seasonEnd = "10-31", breakStart = "11-01", breakEnd = "04-13"),
        )
        val windowWins = evaluate(preService(raw = "2027-01-12", opened = "2026-03-02"), oneDayLeft)
        assertEquals(on("2027-04-14"), windowWins.actionableOn)
        assertEquals(PolicyReason.AFTER_BREAK, windowWins.reason)
    }

    /** §4.3's guard values with the snowblower (deadline 1 Nov). */
    @Test
    fun theGuardValuesOfSection4_3() {
        val completedAfter = evaluate(preService(raw = "2026-12-05", opened = "2026-11-05"), snowblower)
        assertEquals(on("2027-03-01"), completedAfter.actionableOn)
        assertEquals(PolicyReason.AFTER_BREAK, completedAfter.reason)

        val openedBefore = evaluate(preService(raw = "2026-11-20", opened = "2026-10-20"), snowblower)
        assertEquals(on("2026-11-01"), openedBefore.actionableOn)
        assertEquals(PolicyReason.BEFORE_SEASON, openedBefore.reason)

        val openedAfter = evaluate(preService(raw = "2026-11-20", opened = "2026-11-02"), snowblower)
        assertEquals(on("2026-11-20"), openedAfter.actionableOn)
        assertEquals(PolicyReason.NONE, openedAfter.reason)
    }

    /**
     * O-5, inv. 97: AT_START moves a date only when it is earlier than `s + offset`, and never drags a
     * later one back; RESUME_CLAMPED gives `max(date, s)`. On a MANUAL asset `s` is the recorded START.
     */
    @Test
    fun atStartMovesOnlyAnEarlierDateAndResumeClampsToTheStart() {
        val winter = SeasonContext.of(seasonOf(SeasonMode.CALENDAR, seasonStart = "10-15", seasonEnd = "04-15"))
        fun inService(policy: ServicePolicy, raw: String, offset: Int?, at: String) = ServicePolicyEngine.evaluate(
            PolicyInputs(policy, offset, on(raw), null, on("2026-01-01")), winter, on(at),
        )

        val earlier = inService(ServicePolicy.IN_SERVICE_AT_START, raw = "2026-04-20", offset = 10, at = "2026-10-20")
        assertEquals(on("2026-10-25"), earlier.actionableOn)
        assertEquals(PolicyReason.SEASON_START, earlier.reason)

        val later = inService(ServicePolicy.IN_SERVICE_AT_START, raw = "2026-11-30", offset = 10, at = "2026-10-20")
        assertEquals(on("2026-11-30"), later.actionableOn, "a later raw due is never dragged back to the start")
        assertEquals(PolicyReason.NONE, later.reason)

        val clamped = inService(ServicePolicy.IN_SERVICE_RESUME_CLAMPED, raw = "2026-04-20", offset = null, at = "2026-10-20")
        assertEquals(on("2026-10-15"), clamped.actionableOn)
        assertEquals(PolicyReason.SEASON_START, clamped.reason)

        val ownDate = inService(ServicePolicy.IN_SERVICE_RESUME_CLAMPED, raw = "2026-11-30", offset = null, at = "2026-10-20")
        assertEquals(on("2026-11-30"), ownDate.actionableOn)
        assertEquals(PolicyReason.NONE, ownDate.reason)

        // A null start on YEAR_ROUND keeps the date; on an ended MANUAL asset there is none to wait for.
        val yearRound = ServicePolicyEngine.evaluate(
            PolicyInputs(ServicePolicy.IN_SERVICE_AT_START, 0, on("2026-06-20"), null, on("2026-01-01")),
            SeasonContext.of(seasonOf(SeasonMode.YEAR_ROUND)),
            on("2026-06-01"),
        )
        assertEquals(on("2026-06-20"), yearRound.actionableOn)
        assertEquals(PolicyReason.NONE, yearRound.reason)
        val ended = ServicePolicyEngine.evaluate(
            PolicyInputs(ServicePolicy.IN_SERVICE_AT_START, 0, on("2026-04-18"), null, on("2026-04-11")),
            SeasonContext.of(SeasonFixtures.hotTubAsset().seasonInputs(SeasonFixtures.hotTubActivationsUpTo("2026-07-01"))),
            on("2026-07-01"),
        )
        assertEquals(null, ended.actionableOn)
        assertEquals(PolicyReason.AWAITING_START, ended.reason)
        assertEquals(PolicyPhase.DORMANT, ended.phase)
    }

    /**
     * #14 AC 4: a CALENDAR 10-15 → 04-15 asset, every 3 days, done 13 Apr — INACTIVE_SEASON on 1 Jul
     * and DUE (not OVERDUE) on 15 Oct. The policy reads the cycle of `T`, never of `R`: the second
     * history, done 11 Apr, leaves `R` inside the season that just ended, where only reading `T`'s
     * cycle re-enters it at the new start.
     */
    @Test
    fun issue14Ac4() {
        val season = seasonOf(SeasonMode.CALENDAR, seasonStart = "10-15", seasonEnd = "04-15")
        val schedule = scheduleOf(
            timeInterval = 3,
            timeUnit = RecurrenceUnit.DAY,
            timeBasis = TimeBasis.COMPLETION,
            anchorOn = "2026-04-01",
            leadDays = 1,
            servicePolicy = ServicePolicy.IN_SERVICE_AT_START,
            policyOffsetDays = 0,
            createdOn = "2026-03-01",
        )
        fun statusOn(doneOn: String, at: String): DueStatus {
            val events = listOf(completionOf("e1", occurredOn = doneOn, occurrenceOn = "2026-04-01"))
            val state = ScheduleRecompute.rebuild(schedule, events, emptyList(), emptyList(), on(at), ZoneOffset.UTC, season)
            return statusOf(schedule, state, on(at))
        }
        assertEquals(DueStatus.INACTIVE_SEASON, statusOn(doneOn = "2026-04-13", at = "2026-07-01"))
        assertEquals(DueStatus.DUE, statusOn(doneOn = "2026-04-13", at = "2026-10-15"))
        assertEquals(DueStatus.DUE, statusOn(doneOn = "2026-04-11", at = "2026-10-15"))
    }

    /**
     * No inference (inv. 98, Q-4): two schedules differing only in title, on assets differing only
     * in name, category and template, agree on every derived policy field on every day of a year.
     */
    @Test
    fun theRuleReadsNoNameCategoryOrTemplate() {
        val first = SeasonFixtures.snowblowerSchedule()
        val second = first.copy(title = "Snow clearing")
        val firstAsset = SeasonFixtures.assetOf(
            id = "snow", name = "Snowblower", mode = SeasonMode.CALENDAR, seasonStart = "11-15", seasonEnd = "03-31",
            breakStart = "12-01", breakEnd = "02-28", category = "Garden", templateKey = "snow_equipment",
        )
        val secondAsset = firstAsset.copy(name = "Blower", category = "Vehicles", templateKey = null)
        val events = listOf(SeasonFixtures.snowblowerLastDone())
        var day = on("2026-06-01")
        while (day <= on("2027-06-01")) {
            val a = ScheduleRecompute.rebuild(
                first, events, emptyList(), emptyList(), day, ZoneOffset.UTC, firstAsset.seasonInputs(emptyList()),
            )
            val b = ScheduleRecompute.rebuild(
                second, events, emptyList(), emptyList(), day, ZoneOffset.UTC, secondAsset.seasonInputs(emptyList()),
            )
            assertEquals(a, b, "differ on $day")
            day = day.plusDays(5)
        }
    }
}
