package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.schedule.ScheduleRecompute
import com.loosecannon.servicetag.core.schedule.statusOf
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * #16 (B3a; C14, AC7; row 32) — an HA transition is a manual START or END through the shipped operation, so every
 * policy and the break behave as for a tap. F5, the hot tub (spec §7.2), linked: HA's answers write its END of
 * 16 Apr and its START of 10 Oct, and the shipped evaluator reads them as it reads the spec's timeline.
 */
class SeasonSyncPolicyTest {
    private val tub = "tub"
    private val water = SeasonFixtures.hotTubSchedule()

    private fun day(d: String) = LocalDate.parse(d)

    /** F5 as a linked asset: its schedule (or [schedule]), the completion of 11 Apr and the spring START. */
    private suspend fun linkedTub(
        schedule: MaintenanceSchedule = water,
        asset: Asset = SeasonFixtures.hotTubAsset(),
    ): SeasonSyncHarness {
        val h = SeasonSyncHarness("2026-04-15")
        h.raw.assets.rows[asset.id.value] = asset
        h.raw.schedules.rows[schedule.id.value] = schedule
        val done = SeasonFixtures.hotTubCompletedOnApril11()
        h.raw.events.rows[done.id.value] = done
        val spring = SeasonFixtures.hotTubActivations().first()
        h.raw.activations.rows[spring.id] = spring
        h.link(tub)
        return h
    }

    /** HA answers on ([inSeason]) or off on [date], read and recorded that day. */
    private suspend fun SeasonSyncHarness.ha(date: String, inSeason: Boolean) {
        on(date)
        result(HaReadOutcome.Observed(if (inSeason) HaSwitchState.ON else HaSwitchState.OFF, null), assetId = tub)
    }

    /** The state the recompute stored for [schedule] after the latest write. */
    private fun SeasonSyncHarness.stored(schedule: MaintenanceSchedule = water): ScheduleState =
        states.rows.getValue(schedule.id.value)

    /** The shipped evaluator over the rows the applier wrote, on [date]. */
    private fun SeasonSyncHarness.stateOn(date: String, schedule: MaintenanceSchedule = water): ScheduleState =
        ScheduleRecompute.rebuild(
            schedule, raw.events.rows.values.toList(), emptyList(), emptyList(), day(date), ZoneOffset.UTC,
            raw.assets.rows.getValue(tub).seasonInputs(rows(tub)),
        )

    @Test
    fun inServiceGoesDormantAndReentersAcrossAnHaEndThenStart() = runTest {
        val h = linkedTub()

        h.ha("2026-04-16", inSeason = false)
        assertEquals(PolicyPhase.DORMANT, h.stored().policyPhase, "dormant from HA's END")
        assertEquals(DueStatus.INACTIVE_SEASON, statusOf(water, h.stateOn("2026-07-01"), day("2026-07-01")))

        h.ha("2026-10-10", inSeason = true)
        val state = h.stored()
        assertEquals(PolicyPhase.ACTIVE, state.policyPhase)
        assertEquals("2026-10-10", state.actionableDueOn, "re-entered at HA's START")
        assertEquals(
            SeasonFixtures.hotTubActivations().map { it.action to it.occurredOn },
            h.dated(tub),
            "HA wrote the spec's own timeline",
        )
    }

    @Test
    fun continuousIgnoresIt() = runTest {
        val continuous = water.copy(servicePolicy = ServicePolicy.CONTINUOUS, policyOffsetDays = null)
        val h = linkedTub(schedule = continuous)

        h.ha("2026-04-16", inSeason = false)

        assertEquals(SeasonAction.END to "2026-04-16", h.dated(tub).last())
        assertEquals(PolicyPhase.ACTIVE, h.stored(continuous).policyPhase)
        assertEquals(PolicyReason.NONE, h.stored(continuous).policyReason)
        val july = h.stateOn("2026-07-01", continuous)
        assertEquals(PolicyPhase.ACTIVE, july.policyPhase, "out of season, CONTINUOUS still counts")
        assertTrue(statusOf(continuous, july, day("2026-07-01")) != DueStatus.INACTIVE_SEASON)
    }

    @Test
    fun noBacklogAcrossAnHaDormancy() = runTest {
        val h = linkedTub()
        var date = day("2026-04-16")
        while (date < day("2026-10-10")) {
            h.ha(date.toString(), inSeason = false)
            val state = h.stateOn(date.toString())
            assertEquals("2026-04-18", state.computedDueOn, "one occurrence on $date")
            assertEquals(PolicyPhase.DORMANT, state.policyPhase, "dormant on $date")
            date = date.plusWeeks(1)
        }

        h.ha("2026-10-10", inSeason = true)

        val state = h.stored()
        assertEquals("2026-04-18", state.computedDueOn, "still the one occurrence")
        assertEquals(DueStatus.DUE, statusOf(water, state, day("2026-10-10")), "DUE, not a backlog of OVERDUE")
        assertEquals(1, ScheduleRecompute.terminations(water, h.raw.events.rows.values.toList(), emptyList(), emptyList()).size)
        assertEquals(3, h.rows(tub).size, "one END and one START; the weekly off answers wrote nothing")
    }

    @Test
    fun theBreakStillHolds() = runTest {
        val withBreak = SeasonFixtures.assetOf(
            id = tub, name = "Hot tub", mode = SeasonMode.MANUAL,
            breakStart = SeasonFixtures.WINTER_BREAK_START, breakEnd = SeasonFixtures.WINTER_BREAK_END,
        )
        val h = linkedTub(asset = withBreak)
        h.ha("2026-04-16", inSeason = false)

        h.ha("2026-12-10", inSeason = true)

        assertEquals(SeasonAction.START to "2026-12-10", h.dated(tub).last())
        assertTrue(h.stored().quiet, "an HA START inside the break is still quiet")
        assertFalse(h.stateOn("2027-03-01").quiet, "and the break ends as it always did")
    }
}
