package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.completionOf
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Spec §7.2–§7.4's worked timelines, the **schedule** column of each (the health column is B05's),
 * day by day as the owner would see them. "Notifies" below means the status may be delivered and the
 * day is not quiet: DUE SOON, DUE or OVERDUE outside the break.
 */
class WorkedTimelinesTest {

    private fun on(date: String) = LocalDate.parse(date)

    private class Row(val state: ScheduleState, val status: DueStatus) {
        val notifies: Boolean get() = status.notifies && !state.quiet
    }

    private fun at(
        date: String,
        schedule: MaintenanceSchedule,
        asset: Asset,
        events: List<AssetEvent>,
        activations: List<SeasonActivation> = emptyList(),
    ): Row {
        val state = ScheduleRecompute.rebuild(
            schedule, events, emptyList(), emptyList(), on(date), ZoneOffset.UTC, asset.seasonInputs(activations),
        )
        return Row(state, statusOf(schedule, state, on(date)))
    }

    /** §7.2 — MANUAL, IN_SERVICE_AT_START offset 0, lead 1 (H.1c + H.1b; #14 AC 8, #60 AC 9). */
    @Test
    fun hotTubAcrossEndAndStart() {
        val asset = SeasonFixtures.hotTubAsset()
        val schedule = SeasonFixtures.hotTubSchedule()
        val done = listOf(SeasonFixtures.hotTubCompletedOnApril11())
        fun day(date: String, events: List<AssetEvent> = done) =
            at(date, schedule, asset, events, SeasonFixtures.hotTubActivationsUpTo(date))

        assertEquals("2026-04-18", day("2026-04-11").state.computedDueOn, "Sat 11 Apr: completed, R = Sat 18 Apr")

        val ended = day("2026-04-16")
        assertEquals(PolicyPhase.DORMANT, ended.state.policyPhase, "Thu 16 Apr: END")
        assertEquals(DueStatus.INACTIVE_SEASON, ended.status)
        assertNull(ended.state.actionableDueOn, "no START is predicted, so nothing to park until")
        assertEquals(PolicyReason.AWAITING_START, ended.state.policyReason)

        val july = day("2026-07-01")
        assertEquals("2026-04-18", july.state.computedDueOn, "1 Jul: one occurrence, R 18 Apr")
        assertEquals(DueStatus.INACTIVE_SEASON, july.status)

        val started = day("2026-10-10")
        assertEquals("2026-10-10", started.state.actionableDueOn, "Sat 10 Oct: START, A = 10 Oct")
        assertEquals(PolicyReason.SEASON_START, started.state.policyReason)
        assertEquals(DueStatus.DUE, started.status)

        for (date in listOf("2026-10-13", "2026-10-15", "2026-10-17", "2026-10-24")) {
            assertEquals(DueStatus.OVERDUE, day(date).status, date)
        }

        val alt = done + completionOf("e-tub-2", occurredOn = "2026-10-13", occurrenceOn = "2026-04-18", assetId = "tub", scheduleId = "s-tub")
        assertEquals("2026-10-17", day("2026-10-13", alt).state.computedDueOn, "Tue 13 Oct (alt.): completed, R = Sat 17 Oct")
    }

    /** §7.3 — CALENDAR, PRE_SERVICE (Q-4, RB-1): the snowblower's statuses and notifications, and the mower's. */
    @Test
    fun snowblowerAndMower() {
        val snowAsset = SeasonFixtures.snowblowerAsset()
        val snow = SeasonFixtures.snowblowerSchedule()
        val snowDone = listOf(SeasonFixtures.snowblowerLastDone())
        fun snowOn(date: String) = at(date, snow, snowAsset, snowDone)

        assertEquals(DueStatus.OK, snowOn("2026-09-24").status, "F4 at T = 2026-09-24")
        assertEquals("2026-11-01", snowOn("2026-09-24").state.actionableDueOn)
        assertEquals(PolicyReason.BEFORE_SEASON, snowOn("2026-09-24").state.policyReason)
        assertEquals(DueStatus.OK, snowOn("2026-10-17").status)
        snowOn("2026-10-18").let { assertEquals(DueStatus.DUE_SOON, it.status); assertEquals(true, it.notifies, "18 Oct: first entry") }
        snowOn("2026-11-01").let { assertEquals(DueStatus.DUE, it.status); assertEquals(true, it.notifies) }
        for (date in listOf("2026-11-02", "2026-11-15", "2026-11-30")) {
            snowOn(date).let { assertEquals(DueStatus.OVERDUE, it.status, date); assertEquals(true, it.notifies, date) }
        }
        for (date in listOf("2026-12-01", "2026-12-16", "2027-01-20", "2027-02-28")) {
            snowOn(date).let {
                assertEquals(DueStatus.OVERDUE, it.status, date)
                assertEquals(true, it.state.quiet, date)
                assertEquals(false, it.notifies, "$date: none (H.3c)")
                assertEquals("2026-11-01", it.state.actionableDueOn, "the season start resets nothing")
            }
        }
        snowOn("2027-03-01").let { assertEquals(DueStatus.OVERDUE, it.status); assertEquals(true, it.notifies, "1 Mar: resumes") }
        snowOn("2027-05-15").let { assertEquals(DueStatus.OVERDUE, it.status); assertEquals(true, it.notifies) }

        // The mower, R = 12 Jan 2027 → A = 1 Apr 2027 (AFTER_BREAK; #60 AC 2).
        val mowAsset = SeasonFixtures.mowerAsset()
        val mow = SeasonFixtures.mowerSchedule()
        val mowDone = listOf(SeasonFixtures.mowerJanuaryDone())
        fun mowOn(date: String, asset: Asset = mowAsset, schedule: MaintenanceSchedule = mow) = at(date, schedule, asset, mowDone)

        assertEquals(DueStatus.OK, mowOn("2026-12-28").status)
        for (date in listOf("2026-12-29", "2027-01-12", "2027-03-31")) {
            mowOn(date).let {
                assertEquals(DueStatus.DEFERRED, it.status, date)
                assertEquals("2027-04-01", it.state.actionableDueOn, "held until 1 Apr")
                assertEquals(false, it.notifies, date)
            }
        }
        assertEquals(DueStatus.DUE, mowOn("2027-04-01").status)
        mowOn("2027-04-15").let {
            assertEquals(DueStatus.OVERDUE, it.status, "15 Apr: the season starts and resets nothing")
            assertEquals("2027-04-01", it.state.actionableDueOn)
        }
        assertEquals(DueStatus.OVERDUE, mowOn("2027-05-16").status)
        assertEquals(DueStatus.OVERDUE, mowOn("2027-07-30").status)

        // The autumn mower's R = 5 Nov 2026 is kept, and is OK at F4's T.
        val autumn = at("2026-09-24", SeasonFixtures.mowerAutumnSchedule(), mowAsset, listOf(SeasonFixtures.mowerAutumnDone()))
        assertEquals("2026-11-05", autumn.state.actionableDueOn)
        assertEquals(DueStatus.OK, autumn.status)

        // Variant: a break to 04-05 — DEFERRED from 29 Dec, DUE on 6 Apr.
        val longBreak = SeasonFixtures.mowerAsset(breakEnd = "04-05")
        assertEquals(DueStatus.OK, mowOn("2026-12-28", asset = longBreak).status)
        assertEquals(DueStatus.DEFERRED, mowOn("2026-12-29", asset = longBreak).status)
        assertEquals(DueStatus.DEFERRED, mowOn("2027-04-05", asset = longBreak).status)
        assertEquals(DueStatus.DUE, mowOn("2027-04-06", asset = longBreak).status)

        // Variant: an IN_SERVICE_AT_START mower is OUT OF SEASON until 15 Apr and DUE on it (#60 AC 3).
        val inService = mow.copy(servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0)
        assertEquals(DueStatus.INACTIVE_SEASON, mowOn("2027-01-12", schedule = inService).status)
        assertEquals(DueStatus.INACTIVE_SEASON, mowOn("2027-04-14", schedule = inService).status)
        assertEquals(DueStatus.DUE, mowOn("2027-04-15", schedule = inService).status)
    }

    /** §7.4 — YEAR_ROUND with the winter break, IN_SERVICE_AT_START ("After the maintenance break"). */
    @Test
    fun generatorBreak() {
        val asset = SeasonFixtures.generatorAsset()
        val schedule = SeasonFixtures.generatorSchedule()
        val completed = listOf(SeasonFixtures.generatorCompletedInOctober())
        fun open(date: String) = at(date, schedule, asset, emptyList())
        fun after(date: String) = at(date, schedule, asset, completed)

        open("2026-06-20").let {
            assertEquals("2026-06-20", it.state.computedDueOn, "the pin from rule_changed_at: first R 20 Jun")
            assertEquals("2026-06-20", it.state.actionableDueOn)
            assertEquals(DueStatus.DUE, it.status)
        }
        for (date in listOf("2026-08-04", "2026-09-24", "2026-10-18")) {
            assertEquals(DueStatus.OVERDUE, open(date).status, date)
        }

        after("2026-10-20").let {
            assertEquals("2026-12-20", it.state.computedDueOn, "20 Oct: completed, R = 20 Dec")
            assertEquals("2027-03-01", it.state.actionableDueOn, "inside the break → A = 1 Mar 2027")
            assertEquals(PolicyReason.AFTER_BREAK, it.state.policyReason)
            assertEquals(DueStatus.OK, it.status)
        }
        after("2026-12-05").let { assertEquals(DueStatus.OK, it.status); assertEquals(true, it.state.quiet) }
        for (date in listOf("2026-12-06", "2027-01-15", "2027-02-28")) {
            after(date).let {
                assertEquals(DueStatus.DEFERRED, it.status, date)
                assertEquals("2027-03-01", it.state.actionableDueOn, "$date: held until 1 Mar")
            }
        }
        assertEquals(DueStatus.DUE, after("2027-03-01").status)
        assertEquals(DueStatus.OVERDUE, after("2027-04-15").status)
    }
}
