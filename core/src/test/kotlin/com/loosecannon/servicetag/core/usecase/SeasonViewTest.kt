package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `SeasonView.of` (master plan §7.2, plan decision 9): the one season read every surface draws. Pure,
 * so every case is a value and a day.
 */
class SeasonViewTest {

    private fun day(s: String) = LocalDate.parse(s)

    @Test
    fun nextBoundaryPerMode() {
        val mower = SeasonFixtures.assetOf("mow", "Mower", SeasonMode.CALENDAR, seasonStart = "04-15", seasonEnd = "10-31")
        val inSeason = SeasonView.of(mower, emptyList(), day("2026-06-10"))
        assertEquals(SeasonPhase.IN_SEASON to day("2026-10-31"), inSeason.phase to inSeason.nextBoundaryOn, "in season: the span's end")
        val outOfSeason = SeasonView.of(mower, emptyList(), day("2026-12-01"))
        assertEquals(SeasonPhase.OUT_OF_SEASON to day("2027-04-15"), outOfSeason.phase to outOfSeason.nextBoundaryOn, "out: the next start")

        val snowblower = SeasonFixtures.snowblowerAsset()
        assertEquals(day("2027-03-31"), SeasonView.of(snowblower, emptyList(), day("2027-01-10")).nextBoundaryOn, "a wrapping span")

        val tub = SeasonFixtures.hotTubAsset()
        val started = SeasonView.of(tub, SeasonFixtures.hotTubActivationsUpTo("2026-01-03"), day("2026-02-01"))
        assertEquals(SeasonPhase.IN_SEASON to null, started.phase to started.nextBoundaryOn, "MANUAL: an END is never predicted")
        val ended = SeasonView.of(tub, SeasonFixtures.hotTubActivationsUpTo("2026-04-16"), day("2026-06-10"))
        assertEquals(SeasonPhase.OUT_OF_SEASON to null, ended.phase to ended.nextBoundaryOn, "MANUAL: nor a START (Q-6)")

        val generator = SeasonFixtures.generatorAsset()
        val yearRound = SeasonView.of(generator, emptyList(), day("2026-06-10"))
        assertEquals(SeasonPhase.IN_SEASON to null, yearRound.phase to yearRound.nextBoundaryOn)
        assertEquals(day("2026-06-10"), yearRound.computedForOn)
        assertEquals(SeasonMode.YEAR_ROUND, yearRound.seasonMode)
        assertEquals("12-01" to "02-28", yearRound.blackoutStartMmdd to yearRound.blackoutEndMmdd)
    }

    @Test
    fun activationsAreOrdered() {
        val tub = SeasonFixtures.hotTubAsset()
        val rows = listOf(
            SeasonFixtures.activationOf("z", "tub", SeasonAction.END, "2026-04-16", createdAt = 5L),
            SeasonFixtures.activationOf("b", "tub", SeasonAction.START, "2026-04-16", createdAt = 3L),
            SeasonFixtures.activationOf("a", "tub", SeasonAction.START, "2026-04-16", createdAt = 3L),
            SeasonFixtures.activationOf("m", "tub", SeasonAction.START, "2026-01-03", createdAt = 9L),
        )

        val view = SeasonView.of(tub, rows, day("2026-06-10"))

        assertEquals(listOf("m", "a", "b", "z"), view.activations.map { it.id }, "(occurredOn, createdAt, id)")
        assertEquals(SeasonPhase.OUT_OF_SEASON, view.phase, "the latest row, END, decides")
    }

    @Test
    fun inBreakFollowsTheBreakPredicate() {
        val generator = SeasonFixtures.generatorAsset()    // break 12-01 -> 02-28, wrapping the year
        fun inBreak(on: String) = SeasonView.of(generator, emptyList(), day(on)).inBreak

        assertEquals(true, inBreak("2026-12-01"), "inclusive start")
        assertEquals(true, inBreak("2027-01-15"), "across the new year")
        assertEquals(true, inBreak("2027-02-28"), "inclusive end")
        assertEquals(false, inBreak("2028-02-29"), "a leap day after a 02-28 end")
        assertEquals(false, inBreak("2026-11-30"))
        assertEquals(false, inBreak("2026-03-01"))

        val noBreak = SeasonFixtures.assetOf("p", "Pump", SeasonMode.YEAR_ROUND)
        assertEquals(false, SeasonView.of(noBreak, emptyList(), day("2026-12-15")).inBreak)
    }
}
