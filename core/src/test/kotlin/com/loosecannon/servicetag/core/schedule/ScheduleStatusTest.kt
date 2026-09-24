package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonInputs
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.testing.calendarSeason
import com.loosecannon.servicetag.core.testing.readingOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The status function: the ladder, the two no-data shapes, the three "not due" words, monotonicity. */
class ScheduleStatusTest {

    private fun on(date: String) = LocalDate.parse(date)

    private val quarterly = scheduleOf(
        timeInterval = 3,
        timeUnit = RecurrenceUnit.MONTH,
        timeBasis = TimeBasis.FIXED,
        anchorOn = "2026-01-01",
        leadDays = 14,
        createdOn = "2026-02-10",
    )

    private fun statusAt(
        schedule: com.loosecannon.servicetag.core.model.MaintenanceSchedule,
        today: String,
        events: List<com.loosecannon.servicetag.core.model.AssetEvent> = emptyList(),
        season: SeasonInputs? = null,
    ): DueStatus {
        val state = ScheduleRecompute.rebuild(
            schedule, events, emptyList(), emptyList(), on(today), ZoneOffset.UTC, season,
        )
        return statusOf(schedule, state, on(today))
    }

    /**
     * The time ladder of D5 §1 against the April 1 occurrence with a 14-day lead: OK up to and
     * including March 17, DUE_SOON from March 18, DUE **all day** on April 1, OVERDUE after it.
     * The two boundaries are asserted on both sides, because an off-by-one there is what turns
     * "due today" into "overdue" a day early.
     */
    @Test
    fun theTimeLadderAndItsTwoBoundaries() {
        assertEquals(DueStatus.OK, statusAt(quarterly, "2026-03-17"))
        assertEquals(DueStatus.DUE_SOON, statusAt(quarterly, "2026-03-18"))
        assertEquals(DueStatus.DUE_SOON, statusAt(quarterly, "2026-03-31"))
        assertEquals(DueStatus.DUE, statusAt(quarterly, "2026-04-01"))
        assertEquals(DueStatus.OVERDUE, statusAt(quarterly, "2026-04-02"))
    }

    /** The postponed date is what status reads, because `effectiveDueOn` is the sort key. */
    @Test
    fun aPostponementIsWhatStatusReads() {
        val postponed = quarterly.copy(postponedDueOn = "2026-05-01")
        assertEquals(DueStatus.OK, statusAt(postponed, "2026-04-02"))
        assertEquals(DueStatus.DUE, statusAt(postponed, "2026-05-01"))
    }

    /**
     * A meter rule with **neither a completion nor an `anchorMeter`** has no baseline, so there is
     * nothing to be due against: `NO_DATA`, and a health finding elsewhere. Treating the missing
     * baseline as 0 would make the schedule instantly and permanently overdue. Supplying the
     * anchor moves it off `NO_DATA` even with no reading logged yet.
     */
    @Test
    fun aMeterRuleWithNoBaselineIsNoDataUntilAnAnchorMeterArrives() {
        val noBaseline = scheduleOf(
            title = "Oil change",
            meterDefinitionId = "engine_hours",
            meterInterval = 50.0,
            meterLead = 5.0,
        )
        assertEquals(DueStatus.NO_DATA, statusAt(noBaseline, "2026-06-01"))

        val anchored = noBaseline.copy(anchorMeter = 120.0)
        assertEquals(DueStatus.OK, statusAt(anchored, "2026-06-01"))
        assertEquals(
            DueStatus.DUE_SOON,
            statusAt(anchored, "2026-06-01", listOf(readingOf("r1", "2026-06-01", "engine_hours", 166.0))),
        )
        assertEquals(
            DueStatus.DUE,
            statusAt(anchored, "2026-06-01", listOf(readingOf("r1", "2026-06-01", "engine_hours", 170.0))),
        )
    }

    /**
     * D5 §10.6's three "not due" words side by side, plus what each of them must **not** do.
     * `PAUSED` has no due date to show and never notifies; an `ARCHIVED` schedule is out of every
     * due list, which is why `listedForDue` exists and is the one place that filter lives; and an
     * out-of-season `FOLLOW_ASSET` schedule is `INACTIVE_SEASON` rather than `OVERDUE` even though
     * its computed date is well past — which is invariant 22 and #5's second acceptance criterion.
     */
    @Test
    fun pauseArchiveAndSeasonSideBySide() {
        val paused = quarterly.copy(status = ScheduleStatus.PAUSED)
        assertEquals(DueStatus.PAUSED, statusAt(paused, "2026-09-01"))
        assertFalse(DueStatus.PAUSED.countsAsDue)
        assertFalse(DueStatus.PAUSED.notifies)

        val archived = quarterly.copy(status = ScheduleStatus.ARCHIVED)
        assertEquals(listOf(quarterly.id), listOf(quarterly, archived).listedForDue().map { it.id })
        // The word for an archived schedule is PAUSED, pinned as a decision rather than left as an
        // implementation detail: the enum has no ARCHIVED member, this function's return is
        // non-null, and PAUSED is the one word that neither counts as due nor notifies — so a
        // consumer that forgot `listedForDue()` shows nothing rather than something wrong.
        val archivedStatus = statusOf(
            archived,
            ScheduleRecompute.rebuild(
                archived, emptyList(), emptyList(), emptyList(), on("2026-09-01"), ZoneOffset.UTC,
            ),
            on("2026-09-01"),
        )
        assertEquals(DueStatus.PAUSED, archivedStatus)
        assertFalse(archivedStatus.countsAsDue)
        assertFalse(archivedStatus.notifies)

        // the winter window of D5 §10.4: Oct 15 → Apr 15, evaluated in July
        val winter = quarterly.copy(servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0)
        val window = calendarSeason("10-15", "04-15")
        assertEquals(DueStatus.INACTIVE_SEASON, statusAt(winter, "2026-07-01", season = window))
        assertFalse(DueStatus.INACTIVE_SEASON.countsAsDue)
        assertFalse(DueStatus.INACTIVE_SEASON.notifies)
        // and inside the window the same schedule is honestly overdue again
        assertEquals(DueStatus.OVERDUE, statusAt(winter, "2026-11-01", season = window))
        // a CONTINUOUS schedule -- which is all a group target may be -- never sees the window
        assertEquals(DueStatus.OVERDUE, statusAt(quarterly, "2026-07-01", season = window))
    }

    /**
     * Invariant 23: status is **monotone in `T` between history changes** — as today advances with
     * no event, closure or edit, it never walks back from OVERDUE to OK. A due date recomputed as
     * "the first series date ≥ today" would do exactly that every quarter. A season boundary moving
     * a schedule to `INACTIVE_SEASON` is asserted here as **not** a violation, which is the one
     * exception D5 §12.10 names.
     */
    @Test
    fun statusIsMonotoneInTodayBetweenHistoryChanges() {
        val order = listOf(DueStatus.OK, DueStatus.DUE_SOON, DueStatus.DUE, DueStatus.OVERDUE)
        var worst = -1
        var day = on("2026-02-10")
        while (day <= on("2027-06-01")) {
            val rank = order.indexOf(statusAt(quarterly, day.toString()))
            assertTrue(rank >= worst, "status went backwards on $day")
            worst = rank
            day = day.plusDays(11)
        }
        assertEquals(order.indexOf(DueStatus.OVERDUE), worst)

        // the named exception: crossing out of season is a move to INACTIVE_SEASON, not to OK
        val winter = quarterly.copy(servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0)
        val window = calendarSeason("10-15", "04-15")
        assertEquals(DueStatus.OVERDUE, statusAt(winter, "2026-04-15", season = window))
        assertEquals(DueStatus.INACTIVE_SEASON, statusAt(winter, "2026-04-16", season = window))
    }

    /** The worst-of order itself, asserted once so the two sides can be combined with confidence. */
    @Test
    fun theWorstOfOrderIsOverdueThenDueThenSoonThenOk() {
        val oil = scheduleOf(
            timeInterval = 12,
            timeUnit = RecurrenceUnit.MONTH,
            timeBasis = TimeBasis.COMPLETION,
            anchorOn = "2027-04-10",
            meterDefinitionId = "engine_hours",
            meterInterval = 50.0,
            anchorMeter = 120.0,
            meterLead = 5.0,
            createdOn = "2026-04-10",
        )
        // time OK, meter DUE -> DUE; time OVERDUE, meter OK -> OVERDUE
        assertEquals(
            DueStatus.DUE,
            statusAt(oil, "2026-08-15", listOf(readingOf("r1", "2026-08-15", "engine_hours", 171.0))),
        )
        assertEquals(
            DueStatus.OVERDUE,
            statusAt(oil, "2027-04-11", listOf(readingOf("r1", "2026-08-15", "engine_hours", 100.0))),
        )
    }
}
