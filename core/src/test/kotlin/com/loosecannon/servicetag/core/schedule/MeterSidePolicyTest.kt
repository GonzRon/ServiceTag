package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.SeasonFixtures.seasonOf
import com.loosecannon.servicetag.core.testing.readingOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The meter side under a policy (inv. 95, 103; O-7): a meter-only schedule is phase-only — dormant
 * out of season, quiet in the break, never given a date — and a crossed threshold is genuinely DUE
 * whatever the time side is doing.
 */
class MeterSidePolicyTest {

    private fun on(date: String) = LocalDate.parse(date)

    private val meterOnly = scheduleOf(
        assetId = "mow",
        title = "Blade service",
        meterDefinitionId = "hours",
        meterInterval = 50.0,
        anchorMeter = 100.0,
        meterLead = 5.0,
        servicePolicy = ServicePolicy.IN_SERVICE_AT_START,
        policyOffsetDays = 0,
    )
    private val crossed = listOf(readingOf("r1", "2026-06-01", "hours", 160.0, assetId = "mow"))

    /**
     * On the mower (CALENDAR 04-15 → 10-31, winter break): out of season it is INACTIVE_SEASON even
     * with the threshold crossed; in the break it is quiet and still DUE — quiet withholds delivery,
     * never the fact; and at no point does it carry a time-side date.
     */
    @Test
    fun meterOnlyInServiceIsPhaseOnly() {
        val season = SeasonFixtures.mowerAsset().seasonInputs(emptyList())

        val offSeason = ScheduleRecompute.rebuild(meterOnly, crossed, emptyList(), emptyList(), on("2026-11-15"), ZoneOffset.UTC, season)
        assertEquals(PolicyPhase.DORMANT, offSeason.policyPhase)
        assertNull(offSeason.actionableDueOn)
        assertEquals(DueStatus.INACTIVE_SEASON, statusOf(meterOnly, offSeason, on("2026-11-15")))

        val inBreak = ScheduleRecompute.rebuild(meterOnly, crossed, emptyList(), emptyList(), on("2027-01-10"), ZoneOffset.UTC, season)
        assertTrue(inBreak.quiet)
        assertNull(inBreak.actionableDueOn)
        assertNull(inBreak.effectiveDueOn)

        // A YEAR_ROUND asset with the break: active, quiet in the break, and the crossed meter DUE.
        val yearRound = seasonOf(SeasonMode.YEAR_ROUND, breakStart = "12-01", breakEnd = "02-28")
        val quietDue = ScheduleRecompute.rebuild(meterOnly, crossed, emptyList(), emptyList(), on("2027-01-10"), ZoneOffset.UTC, yearRound)
        assertEquals(PolicyPhase.ACTIVE, quietDue.policyPhase)
        assertTrue(quietDue.quiet)
        assertNull(quietDue.actionableDueOn)
        assertEquals(DueStatus.DUE, statusOf(meterOnly, quietDue, on("2027-01-10")))

        val inSeason = ScheduleRecompute.rebuild(meterOnly, crossed, emptyList(), emptyList(), on("2026-06-02"), ZoneOffset.UTC, season)
        assertEquals(PolicyPhase.ACTIVE, inSeason.policyPhase)
        assertEquals(false, inSeason.quiet)
        assertEquals(DueStatus.DUE, statusOf(meterOnly, inSeason, on("2026-06-02")))
    }

    /**
     * Inv. 95, 103: the January mower with a meter side. Its time side is held by the break until
     * 1 Apr; with the threshold crossed the fold reads DUE — the held date never masks the meter.
     */
    @Test
    fun aHeldTimeSideWithACrossedMeterReadsDue() {
        val season = SeasonFixtures.mowerAsset().seasonInputs(emptyList())
        val combined = SeasonFixtures.mowerSchedule().copy(
            meterDefinitionId = DefinitionId("hours"),
            meterInterval = 50.0,
            anchorMeter = 100.0,
            meterLead = 5.0,
        )
        val history = listOf(SeasonFixtures.mowerJanuaryDone())
        val held = ScheduleRecompute.rebuild(combined, history, emptyList(), emptyList(), on("2027-01-20"), ZoneOffset.UTC, season)
        assertEquals("2027-04-01", held.actionableDueOn)
        assertEquals(DueStatus.DEFERRED, statusOf(combined, held, on("2027-01-20")), "nothing crossed: held")

        val reading = readingOf("r1", "2027-01-18", "hours", 160.0, assetId = "mow")
        val crossedState = ScheduleRecompute.rebuild(
            combined, history + reading, emptyList(), emptyList(), on("2027-01-20"), ZoneOffset.UTC, season,
        )
        assertEquals(DueStatus.DUE, statusOf(combined, crossedState, on("2027-01-20")))
        assertEquals(150.0, crossedState.computedDueMeter, "the threshold itself is never moved")
    }
}
