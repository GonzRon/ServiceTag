package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Inv. 94 (spec §3.5, #14 AC 5): out-of-season time creates no occurrence and no backlog, and
 * re-entry leaves exactly one current occurrence — the one that was open when the season ended.
 */
class NoBacklogAcrossDormancyTest {

    private fun on(date: String) = LocalDate.parse(date)

    /**
     * F5, the hot tub: weekly, completed 11 Apr (R = 18 Apr), END 16 Apr, START 10 Oct. On every day
     * of the dormancy — twenty-five weeks of it — the one occurrence is still 18 Apr, no termination
     * appears, and on 1 Jul in particular it is that occurrence and no other. On the START it is
     * re-entered at 10 Oct, still the same occurrence.
     */
    @Test
    fun reEntryLeavesExactlyOneOccurrence() {
        val schedule = SeasonFixtures.hotTubSchedule()
        val events = listOf(SeasonFixtures.hotTubCompletedOnApril11())
        var day = on("2026-04-16")
        while (day <= on("2026-10-10")) {
            val season = SeasonFixtures.hotTubAsset().seasonInputs(SeasonFixtures.hotTubActivationsUpTo(day.toString()))
            val state = ScheduleRecompute.rebuild(schedule, events, emptyList(), emptyList(), day, ZoneOffset.UTC, season)
            assertEquals("2026-04-18", state.computedDueOn, "one occurrence on $day")
            assertEquals(
                "2026-04-18",
                ScheduleRecompute.currentOccurrenceOn(schedule, events, emptyList(), emptyList(), ZoneOffset.UTC),
            )
            assertEquals(1, ScheduleRecompute.terminations(schedule, events, emptyList(), emptyList()).size)
            if (day < on("2026-10-10")) {
                assertEquals(PolicyPhase.DORMANT, state.policyPhase, "dormant on $day")
                assertEquals(DueStatus.INACTIVE_SEASON, statusOf(schedule, state, day))
            } else {
                assertEquals("2026-10-10", state.actionableDueOn)
                assertEquals(DueStatus.DUE, statusOf(schedule, state, day), "DUE, not a backlog of OVERDUE")
            }
            day = day.plusDays(1)
        }
    }
}
