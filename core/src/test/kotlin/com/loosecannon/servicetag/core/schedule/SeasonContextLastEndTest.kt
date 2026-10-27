package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.testing.SeasonFixtures.activationOf
import com.loosecannon.servicetag.core.testing.SeasonFixtures.seasonOf
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #114 (C8): the day an asset's season last ended, per mode (rows 1 and 2). */
class SeasonContextLastEndTest {

    private fun on(date: String) = LocalDate.parse(date)

    private fun manual(vararg rows: Pair<SeasonAction, String>): SeasonContext = SeasonContext.of(
        seasonOf(
            SeasonMode.MANUAL,
            activations = rows.mapIndexed { i, (action, day) -> activationOf("act-$i", "a1", action, day) },
        ),
    )

    // Row 1 — MANUAL and YEAR_ROUND.

    @Test
    fun manualAnswersTheLatestEndOnOrBefore() {
        val ctx = manual(
            SeasonAction.START to "2025-10-01",
            SeasonAction.END to "2026-04-20",
            SeasonAction.START to "2026-10-01",
            SeasonAction.END to "2027-04-18",
        )
        assertEquals(on("2026-04-20"), ctx.lastSeasonEndOnOrBefore(on("2026-04-20")), "an END on the day itself counts")
        assertEquals(on("2026-04-20"), ctx.lastSeasonEndOnOrBefore(on("2026-10-05")), "a later START is not an end")
        assertEquals(on("2026-04-20"), ctx.lastSeasonEndOnOrBefore(on("2027-04-17")))
        assertEquals(on("2027-04-18"), ctx.lastSeasonEndOnOrBefore(on("2027-04-18")))
        assertNull(ctx.lastSeasonEndOnOrBefore(on("2026-04-19")), "no END yet on or before the day")
    }

    @Test
    fun manualStartOnlyAnswersNull() {
        val ctx = manual(SeasonAction.START to "2026-03-01", SeasonAction.START to "2026-09-01")
        assertNull(ctx.lastSeasonEndOnOrBefore(on("2026-10-01")))
        assertNull(manual().lastSeasonEndOnOrBefore(on("2026-10-01")), "no history at all")
    }

    @Test
    fun anEndAfterAnEndAnswersTheLater() {
        // END, END is valid history (a merge or a switch row, inv. 92): the later boundary is the conservative one.
        val ctx = manual(SeasonAction.END to "2026-04-20", SeasonAction.END to "2026-05-02")
        assertEquals(on("2026-05-02"), ctx.lastSeasonEndOnOrBefore(on("2026-09-01")))
        assertEquals(on("2026-04-20"), ctx.lastSeasonEndOnOrBefore(on("2026-05-01")))
    }

    @Test
    fun yearRoundAnswersNull() {
        val ctx = SeasonContext.of(
            seasonOf(
                SeasonMode.YEAR_ROUND,
                breakStart = "12-01",
                breakEnd = "02-28",
                activations = listOf(activationOf("act-1", "a1", SeasonAction.END, "2026-04-20")),
            ),
        )
        assertNull(ctx.lastSeasonEndOnOrBefore(on("2026-09-01")), "a YEAR_ROUND asset never had a season to end")
    }

    // Row 2 — CALENDAR (O-1): the first out-of-season day after the latest window end strictly before the day.

    @Test
    fun calendarAnswersTheFirstOutOfSeasonDayOfTheLatestFinishedWindow() {
        val ctx = SeasonContext.of(seasonOf(SeasonMode.CALENDAR, seasonStart = "10-01", seasonEnd = "04-20"))
        assertEquals(on("2026-04-21"), ctx.lastSeasonEndOnOrBefore(on("2026-04-21")), "the first day out of season")
        assertEquals(on("2025-04-21"), ctx.lastSeasonEndOnOrBefore(on("2026-04-20")), "the last day in season: last year's")
        assertEquals(on("2026-04-21"), ctx.lastSeasonEndOnOrBefore(on("2026-09-30")))
        assertEquals(on("2026-04-21"), ctx.lastSeasonEndOnOrBefore(on("2027-01-15")), "across New Year")
    }

    @Test
    fun aWrappingWindowAndFeb29Resolve() {
        // 02-29 reads as 02-28 in a common year (#14 AC 6), so the first out-of-season day is 03-01 either way.
        val leap = SeasonContext.of(seasonOf(SeasonMode.CALENDAR, seasonStart = "11-01", seasonEnd = "02-29"))
        assertEquals(on("2027-03-01"), leap.lastSeasonEndOnOrBefore(on("2027-03-01")))
        assertEquals(on("2027-03-01"), leap.lastSeasonEndOnOrBefore(on("2028-02-29")), "02-29 is in season in 2028")
        assertEquals(on("2028-03-01"), leap.lastSeasonEndOnOrBefore(on("2028-03-01")))
        assertEquals(on("2027-03-01"), leap.lastSeasonEndOnOrBefore(on("2027-12-25")))

        // A window that does not wrap: a summer one.
        val summer = SeasonContext.of(seasonOf(SeasonMode.CALENDAR, seasonStart = "06-01", seasonEnd = "08-31"))
        assertEquals(on("2025-09-01"), summer.lastSeasonEndOnOrBefore(on("2026-05-20")))
        assertEquals(on("2025-09-01"), summer.lastSeasonEndOnOrBefore(on("2026-08-31")))
        assertEquals(on("2026-09-01"), summer.lastSeasonEndOnOrBefore(on("2026-09-01")))

        // A window ending on the last day of the year ends on 01-01.
        val toYearEnd = SeasonContext.of(seasonOf(SeasonMode.CALENDAR, seasonStart = "06-01", seasonEnd = "12-31"))
        assertEquals(on("2027-01-01"), toYearEnd.lastSeasonEndOnOrBefore(on("2027-01-01")))
        assertEquals(on("2026-01-01"), toYearEnd.lastSeasonEndOnOrBefore(on("2026-12-31")))
    }

    @Test
    fun aMalformedWindowAnswersNull() {
        val malformed = SeasonContext.of(seasonOf(SeasonMode.CALENDAR, seasonStart = "13-40", seasonEnd = "04-20"))
        assertNull(malformed.lastSeasonEndOnOrBefore(on("2026-09-01")))
        val missing = SeasonContext.of(seasonOf(SeasonMode.CALENDAR, seasonStart = "10-01", seasonEnd = null))
        assertNull(missing.lastSeasonEndOnOrBefore(on("2026-09-01")))
        // A CALENDAR asset's activation rows are history nobody reads (inv. 90).
        val withRows = SeasonContext.of(
            seasonOf(
                SeasonMode.CALENDAR,
                seasonStart = "13-40",
                seasonEnd = "04-20",
                activations = listOf(activationOf("act-1", "a1", SeasonAction.END, "2026-05-01")),
            ),
        )
        assertNull(withRows.lastSeasonEndOnOrBefore(on("2026-09-01")))
    }
}
