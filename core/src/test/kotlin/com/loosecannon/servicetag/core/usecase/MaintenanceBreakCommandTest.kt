package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/**
 * `SetMaintenanceBreak` (spec §4.4; master plan §7.2): both or neither, real `MM-DD`s, never the whole
 * of a common or a leap year, and the strands rule for the boundary a YEAR_ROUND or MANUAL asset's
 * break provides (O-3). Today is 2026-06-10.
 */
class MaintenanceBreakCommandTest {

    private val a1 = AssetId("a1")

    private suspend fun SeasonCommandHarness.refused(cmd: BreakCommand, id: AssetId = a1): List<SeasonProblem> =
        assertFailsWith<SeasonValidation> { setMaintenanceBreak.run(id, cmd) }.problems

    @Test
    fun bothOrNeitherAndValidMonthDays() = runTest {
        val h = SeasonCommandHarness()
        val before = h.asset()

        assertEquals(listOf(SeasonProblem.BothOrNeither), h.refused(BreakCommand("12-01", null)))
        assertEquals(listOf(SeasonProblem.BothOrNeither), h.refused(BreakCommand(" ", "02-28")))
        assertEquals(listOf(SeasonProblem.BadDate("blackoutStartMmdd")), h.refused(BreakCommand("13-01", "02-28")))
        assertEquals(listOf(SeasonProblem.BadDate("blackoutEndMmdd")), h.refused(BreakCommand("12-01", "02-30")))
        assertEquals(before, h.stored())
        assertEquals(0, h.assets.upserts)

        val set = h.setMaintenanceBreak.run(a1, BreakCommand("12-01", " 02-28 "))
        assertEquals("12-01" to "02-28", set.blackoutStartMmdd to set.blackoutEndMmdd)
        assertEquals(SeasonMode.YEAR_ROUND, set.seasonMode, "the break never touches the season")
        val cleared = h.setMaintenanceBreak.run(a1, BreakCommand("", null))
        assertEquals(null to null, cleared.blackoutStartMmdd to cleared.blackoutEndMmdd)
    }

    /** Some year with no allowed day, common or leap (plan decision 8). */
    @Test
    fun aBreakCoveringTheYearIsRefused() = runTest {
        val h = SeasonCommandHarness()
        h.asset()

        for ((start, end) in listOf("03-01" to "02-28", "01-01" to "12-31", "02-29" to "02-28")) {
            assertEquals(
                listOf(SeasonProblem.BlackoutCoversTheYear),
                h.refused(BreakCommand(start, end)),
                "$start -> $end",
            )
        }
        assertEquals(null, h.stored().blackoutStartMmdd)

        // 02-28 is free in a common year, and 02-28 and 02-29 in a leap year.
        val saved = h.setMaintenanceBreak.run(a1, BreakCommand("03-01", "02-27"))
        assertEquals("03-01" to "02-27", saved.blackoutStartMmdd to saved.blackoutEndMmdd)
    }

    /** On a YEAR_ROUND (or MANUAL) asset the break's start is PRE_SERVICE's boundary: removing it is a 409. */
    @Test
    fun removingABreakAPreServiceScheduleUsesIsRefused() = runTest {
        val h = SeasonCommandHarness()
        val yearRound = h.asset(breakStart = "12-01", breakEnd = "02-28")
        h.schedule("s1", title = "Before the break")

        val refusal = assertFailsWith<BreakStrandsPolicy> { h.setMaintenanceBreak.run(a1, BreakCommand(null, null)) }
        assertEquals(a1, refusal.assetId)
        assertEquals(listOf("s1" to "Before the break"), refusal.schedules.map { it.id.value to it.title })
        assertEquals(yearRound, h.stored(), "nothing written")

        val manual = h.asset(id = "m1", mode = SeasonMode.MANUAL, breakStart = "12-01", breakEnd = "02-28")
        h.schedule("s2", assetId = "m1")
        assertFailsWith<BreakStrandsPolicy> { h.setMaintenanceBreak.run(AssetId("m1"), BreakCommand(null, null)) }
        assertEquals(manual, h.stored("m1"))
        assertEquals(0, h.assets.upserts)
    }

    /** A CALENDAR asset's boundary is its season's start, so its break may change or go. */
    @Test
    fun changingTheBreakOnACalendarAssetIsAllowed() = runTest {
        val h = SeasonCommandHarness()
        h.asset(mode = SeasonMode.CALENDAR, seasonStart = "11-15", seasonEnd = "03-31", breakStart = "12-20", breakEnd = "01-05")
        h.schedule("s1")

        val moved = h.setMaintenanceBreak.run(a1, BreakCommand("12-24", "12-26"))
        assertEquals("12-24" to "12-26", moved.blackoutStartMmdd to moved.blackoutEndMmdd)
        val removed = h.setMaintenanceBreak.run(a1, BreakCommand(null, null))
        assertEquals(null to null, removed.blackoutStartMmdd to removed.blackoutEndMmdd)

        // New dates on a break-only boundary keep its kind too.
        h.asset(id = "y1", breakStart = "12-01", breakEnd = "02-28")
        h.schedule("s2", assetId = "y1")
        assertEquals("11-20", h.setMaintenanceBreak.run(AssetId("y1"), BreakCommand("11-20", "02-15")).blackoutStartMmdd)
    }

    /** Dec. 44 (M11): NONE -> BREAK repairs a merged boundary-less PRE_SERVICE schedule. */
    @Test
    fun addingABreakWhereNoBoundaryExistedIsAllowed() = runTest {
        val h = SeasonCommandHarness()
        h.asset()
        h.schedule("s-merged")

        val saved = h.setMaintenanceBreak.run(a1, BreakCommand("12-01", "02-28"))
        assertEquals("12-01" to "02-28", saved.blackoutStartMmdd to saved.blackoutEndMmdd)
        assertEquals(saved, h.stored())
    }
}
