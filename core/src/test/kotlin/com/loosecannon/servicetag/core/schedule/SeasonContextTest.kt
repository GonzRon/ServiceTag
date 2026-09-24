package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.testing.SeasonFixtures.activationOf
import com.loosecannon.servicetag.core.testing.SeasonFixtures.seasonOf
import com.loosecannon.servicetag.core.testing.dayMillis
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The season context: the phase per mode, the cycle start, and PRE_SERVICE's boundary (spec §3.1, §4.3). */
class SeasonContextTest {

    private fun on(date: String) = LocalDate.parse(date)

    /**
     * CALENDAR is the shipped predicate: inclusive at both ends, wrapping the year, and a 02-29 bound
     * read as 02-28 in a common year (#14 AC 6). The cycle start and the season end are the span's
     * own first and last days, the next start when out of season.
     */
    @Test
    fun calendarPhaseWrapsAndReadsFebruary29AsFebruary28() {
        val winter = SeasonContext.of(seasonOf(SeasonMode.CALENDAR, seasonStart = "10-15", seasonEnd = "04-15"))
        assertEquals(SeasonPhase.IN_SEASON, winter.phaseAt(on("2026-10-15")), "the start day is in season")
        assertEquals(SeasonPhase.IN_SEASON, winter.phaseAt(on("2027-01-10")), "the season wraps the year")
        assertEquals(SeasonPhase.IN_SEASON, winter.phaseAt(on("2026-04-15")), "the end day is in season")
        assertEquals(SeasonPhase.OUT_OF_SEASON, winter.phaseAt(on("2026-04-16")))
        assertEquals(SeasonPhase.OUT_OF_SEASON, winter.phaseAt(on("2026-10-14")))
        assertEquals(on("2026-10-15"), winter.cycleStartAt(on("2027-01-10")))
        assertEquals(on("2026-10-15"), winter.cycleStartAt(on("2026-07-01")), "out of season: the next start")
        assertEquals(on("2027-04-15"), winter.seasonEndAt(on("2027-01-10")))
        assertNull(winter.seasonEndAt(on("2026-07-01")))
        assertEquals(on("2026-10-15"), winter.latestCycleStartOnOrBefore(on("2027-07-01")))

        val leapEnd = SeasonContext.of(seasonOf(SeasonMode.CALENDAR, seasonStart = "11-01", seasonEnd = "02-29"))
        assertEquals(SeasonPhase.IN_SEASON, leapEnd.phaseAt(on("2027-02-28")), "02-29 reads as 02-28 in 2027")
        assertEquals(SeasonPhase.OUT_OF_SEASON, leapEnd.phaseAt(on("2027-03-01")))
        assertEquals(SeasonPhase.IN_SEASON, leapEnd.phaseAt(on("2028-02-29")), "and as itself in 2028")
        assertEquals(on("2027-02-28"), leapEnd.seasonEndAt(on("2027-01-10")))
        assertEquals(on("2028-02-29"), leapEnd.seasonEndAt(on("2028-01-10")))

        val leapStart = SeasonContext.of(seasonOf(SeasonMode.CALENDAR, seasonStart = "02-29", seasonEnd = "05-31"))
        assertEquals(on("2027-02-28"), leapStart.cycleStartAt(on("2027-01-10")))
        assertEquals(on("2028-02-29"), leapStart.cycleStartAt(on("2028-03-10")))

        // The break is the same predicate: inclusive, wrapping, 02-29 as 02-28.
        val breakOnly = SeasonContext.of(seasonOf(SeasonMode.YEAR_ROUND, breakStart = "12-01", breakEnd = "02-29"))
        assertEquals(true, breakOnly.inBreak(on("2026-12-01")))
        assertEquals(true, breakOnly.inBreak(on("2027-02-28")))
        assertEquals(false, breakOnly.inBreak(on("2027-03-01")))
        assertEquals(true, breakOnly.inBreak(on("2028-02-29")))
        assertEquals(on("2027-03-01"), breakOnly.firstAllowedAfter(on("2026-12-10")))
        assertEquals(on("2028-03-01"), breakOnly.firstAllowedAfter(on("2028-01-10")))
        assertEquals(on("2026-11-30"), breakOnly.firstAllowedAfter(on("2026-11-30")), "an allowed day is itself")
    }

    /**
     * MANUAL is the latest row's action among rows dated on or before the day (inv. 90), ordered by
     * `(occurredOn, createdAt, id)`: a START and an END on the same day resolve by `createdAt`, END is
     * out of season on its own day, no row at all is out of season (totality), and rows are ignored
     * outright in CALENDAR and YEAR_ROUND.
     */
    @Test
    fun manualPhaseIsTheLatestRowOnOrBeforeTheDay() {
        val sameDay = listOf(
            // Handed over newest first, to show the order is the context's own. The END was written
            // later but carries the smaller id, so only `createdAt` puts it last: an `(occurredOn, id)`
            // order would read the START as the latest row.
            activationOf("a", "tub", SeasonAction.END, "2026-04-16", createdAt = dayMillis("2026-04-16") + 60_000),
            activationOf("b", "tub", SeasonAction.START, "2026-04-16", createdAt = dayMillis("2026-04-16")),
        )
        val manual = SeasonContext.of(seasonOf(SeasonMode.MANUAL, activations = sameDay))
        assertEquals(SeasonPhase.OUT_OF_SEASON, manual.phaseAt(on("2026-04-15")), "before any row")
        assertEquals(SeasonPhase.OUT_OF_SEASON, manual.phaseAt(on("2026-04-16")), "END was written after START")

        val history = listOf(
            activationOf("s1", "tub", SeasonAction.START, "2026-01-03"),
            activationOf("e1", "tub", SeasonAction.END, "2026-04-16"),
            activationOf("s2", "tub", SeasonAction.START, "2026-10-10"),
        )
        val tub = SeasonContext.of(seasonOf(SeasonMode.MANUAL, activations = history))
        assertEquals(SeasonPhase.IN_SEASON, tub.phaseAt(on("2026-01-03")), "START is in season on its own day")
        assertEquals(SeasonPhase.IN_SEASON, tub.phaseAt(on("2026-04-15")))
        assertEquals(SeasonPhase.OUT_OF_SEASON, tub.phaseAt(on("2026-04-16")), "END's own day is out of season")
        assertEquals(SeasonPhase.OUT_OF_SEASON, tub.phaseAt(on("2026-10-09")))
        assertEquals(SeasonPhase.IN_SEASON, tub.phaseAt(on("2026-10-10")))
        assertEquals(on("2026-01-03"), tub.cycleStartAt(on("2026-04-15")))
        assertEquals(on("2026-10-10"), tub.cycleStartAt(on("2026-10-12")))
        assertEquals(on("2026-01-03"), tub.latestCycleStartOnOrBefore(on("2026-07-01")))

        val none = SeasonContext.of(seasonOf(SeasonMode.MANUAL))
        assertEquals(SeasonPhase.OUT_OF_SEASON, none.phaseAt(on("2026-07-01")), "no row reads out of season")

        val ignored = history + activationOf("e2", "tub", SeasonAction.END, "2026-06-01")
        val calendar = SeasonContext.of(
            seasonOf(SeasonMode.CALENDAR, seasonStart = "05-01", seasonEnd = "09-30", activations = ignored),
        )
        assertEquals(SeasonPhase.IN_SEASON, calendar.phaseAt(on("2026-07-01")), "CALENDAR never reads a row")
        assertEquals(SeasonPhase.OUT_OF_SEASON, calendar.phaseAt(on("2026-04-20")))
        val yearRound = SeasonContext.of(seasonOf(SeasonMode.YEAR_ROUND, activations = ignored))
        assertEquals(SeasonPhase.IN_SEASON, yearRound.phaseAt(on("2026-07-01")), "YEAR_ROUND never reads a row")
        assertNull(yearRound.cycleStartAt(on("2026-07-01")))
    }

    /**
     * A MANUAL asset that is out of season has no cycle start — its next START is never predicted
     * (Q-6, O-5) — and its START is never PRE_SERVICE's boundary (inv. 99): with no break it has no
     * boundary at all, and with one the break is the boundary, as on YEAR_ROUND (O-3).
     */
    @Test
    fun aManualAssetOutOfSeasonHasNoCycleStartAndNoSeasonBoundary() {
        val history = listOf(
            activationOf("s1", "tub", SeasonAction.START, "2026-01-03"),
            activationOf("e1", "tub", SeasonAction.END, "2026-04-16"),
        )
        val tub = SeasonContext.of(seasonOf(SeasonMode.MANUAL, activations = history))
        assertNull(tub.cycleStartAt(on("2026-07-01")), "the next START is never predicted")
        assertNull(tub.preServiceBoundary(on("2026-07-01")), "a MANUAL asset with no break has no boundary")
        assertEquals(BoundaryKind.NONE, tub.boundaryKind)

        val withBreak = SeasonContext.of(
            seasonOf(SeasonMode.MANUAL, breakStart = "12-01", breakEnd = "02-28", activations = history),
        )
        assertEquals(BoundaryKind.BREAK, withBreak.boundaryKind)
        assertEquals(Boundary(on("2026-12-01"), BoundaryKind.BREAK), withBreak.preServiceBoundary(on("2026-07-01")))
        assertNull(withBreak.cycleStartAt(on("2026-07-01")))
    }

    /**
     * The boundary kind per mode, and PRE_SERVICE's boundary for a raw due: CALENDAR → the season
     * start containing or after `R`; YEAR_ROUND or MANUAL with a break → the break start containing
     * or after `R`; neither → none.
     */
    @Test
    fun boundaryKindAndPreServiceBoundaryPerMode() {
        assertEquals(BoundaryKind.SEASON, boundaryKindOf(SeasonMode.CALENDAR, hasBreak = false))
        assertEquals(BoundaryKind.SEASON, boundaryKindOf(SeasonMode.CALENDAR, hasBreak = true))
        assertEquals(BoundaryKind.BREAK, boundaryKindOf(SeasonMode.YEAR_ROUND, hasBreak = true))
        assertEquals(BoundaryKind.BREAK, boundaryKindOf(SeasonMode.MANUAL, hasBreak = true))
        assertEquals(BoundaryKind.NONE, boundaryKindOf(SeasonMode.YEAR_ROUND, hasBreak = false))
        assertEquals(BoundaryKind.NONE, boundaryKindOf(SeasonMode.MANUAL, hasBreak = false))

        val snowblower = SeasonContext.of(
            seasonOf(SeasonMode.CALENDAR, seasonStart = "11-15", seasonEnd = "03-31", breakStart = "12-01", breakEnd = "02-28"),
        )
        assertEquals(BoundaryKind.SEASON, snowblower.boundaryKind)
        assertEquals(
            Boundary(on("2026-11-15"), BoundaryKind.SEASON),
            snowblower.preServiceBoundary(on("2026-12-20")),
            "the season containing R, even inside the break",
        )
        assertEquals(Boundary(on("2026-11-15"), BoundaryKind.SEASON), snowblower.preServiceBoundary(on("2026-06-01")))

        val generator = SeasonContext.of(seasonOf(SeasonMode.YEAR_ROUND, breakStart = "12-01", breakEnd = "02-28"))
        assertEquals(BoundaryKind.BREAK, generator.boundaryKind)
        assertEquals(Boundary(on("2026-12-01"), BoundaryKind.BREAK), generator.preServiceBoundary(on("2026-06-20")))
        assertEquals(Boundary(on("2026-12-01"), BoundaryKind.BREAK), generator.preServiceBoundary(on("2027-01-15")))

        assertNull(SeasonContext.of(seasonOf(SeasonMode.YEAR_ROUND)).preServiceBoundary(on("2026-06-20")))
        assertEquals(BoundaryKind.NONE, SeasonContext.of(seasonOf(SeasonMode.YEAR_ROUND)).boundaryKind)
    }
}
