package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.RecurrenceUnit
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The FIXED series and the calendar rules of D5 §2.3 — **one representative case per rule, not a
 * matrix** (the proportionality ruling). What each case is guarding against is written beside it,
 * because the reason a series is computed with a multiplier rather than iteratively is invisible
 * until the clamping case is read.
 */
class RecurrenceMathTest {

    private fun on(date: String) = LocalDate.parse(date)

    /**
     * Every rule of D5 §2.3 in one test. The two that carry the hazard:
     *
     * - **month-end clamping.** `seriesDate` is `anchor.plus(k·interval)`, so Jan 31 + 1 month is
     *   Feb 28 and Jan 31 + 2 months is March 31 again. An iterative `plusMonths` would take the
     *   clamped Feb 28 as its next base and give March 28, drifting off the series for good.
     * - **a leap-day anchor.** Feb 29 + 1 year is Feb 28 in a common year, and because k is
     *   multiplied against the *anchor* the series returns to Feb 29 in the next leap year. An
     *   iterative advance would land on Feb 28 and stay there forever.
     */
    @Test
    fun theCalendarRulesOfD5() {
        val jan31 = on("2026-01-31")
        assertEquals(on("2026-02-28"), RecurrenceMath.seriesDate(jan31, 1, 1, RecurrenceUnit.MONTH))
        assertEquals(on("2026-03-31"), RecurrenceMath.seriesDate(jan31, 2, 1, RecurrenceUnit.MONTH))

        val leap = on("2024-02-29")
        assertEquals(on("2025-02-28"), RecurrenceMath.seriesDate(leap, 1, 1, RecurrenceUnit.YEAR))
        assertEquals(on("2028-02-29"), RecurrenceMath.seriesDate(leap, 4, 1, RecurrenceUnit.YEAR))

        // weeks and days are plain arithmetic
        assertEquals(on("2026-04-13"), RecurrenceMath.seriesDate(on("2026-03-02"), 3, 2, RecurrenceUnit.WEEK))
        assertEquals(on("2026-03-11"), RecurrenceMath.seriesDate(on("2026-03-02"), 3, 3, RecurrenceUnit.DAY))

        // a year wrap needs no special case
        assertEquals(on("2027-02-20"), RecurrenceMath.seriesDate(on("2026-12-20"), 2, 1, RecurrenceUnit.MONTH))
    }

    /** k = 0 is the anchor itself, and the multiplier is `k · interval`, not `k`. */
    @Test
    fun theSeriesStartsAtTheAnchorAndStepsByTheInterval() {
        val anchor = on("2026-01-01")
        assertEquals(anchor, RecurrenceMath.seriesDate(anchor, 0, 3, RecurrenceUnit.MONTH))
        assertEquals(on("2026-04-01"), RecurrenceMath.seriesDate(anchor, 1, 3, RecurrenceUnit.MONTH))
        assertEquals(on("2027-01-01"), RecurrenceMath.seriesDate(anchor, 4, 3, RecurrenceUnit.MONTH))
    }

    /**
     * The three lookups the engine actually calls. `firstSeriesDateAfter` is **strictly** after —
     * that is what makes a completion on the due date advance to the next occurrence rather than
     * returning the one just satisfied.
     */
    @Test
    fun theThreeLookupsAroundASeries() {
        val anchor = on("2026-01-01")
        val q = 3 to RecurrenceUnit.MONTH

        assertEquals(on("2026-04-01"), RecurrenceMath.firstSeriesDateAtOrAfter(anchor, on("2026-02-10"), q.first, q.second))
        assertEquals(on("2026-04-01"), RecurrenceMath.firstSeriesDateAtOrAfter(anchor, on("2026-04-01"), q.first, q.second))
        assertEquals(anchor, RecurrenceMath.firstSeriesDateAtOrAfter(anchor, on("2025-06-01"), q.first, q.second))

        assertEquals(on("2026-07-01"), RecurrenceMath.firstSeriesDateAfter(anchor, on("2026-04-01"), q.first, q.second))
        assertEquals(on("2026-04-01"), RecurrenceMath.firstSeriesDateAfter(anchor, on("2026-03-20"), q.first, q.second))

        assertEquals(on("2026-01-01"), RecurrenceMath.largestSeriesDateAtOrBefore(anchor, on("2026-03-20"), q.first, q.second))
        assertEquals(on("2026-04-01"), RecurrenceMath.largestSeriesDateAtOrBefore(anchor, on("2026-04-01"), q.first, q.second))
        assertNull(RecurrenceMath.largestSeriesDateAtOrBefore(anchor, on("2025-12-31"), q.first, q.second))
    }

    /** The lookups keep working across a clamp, which is the whole point of the multiplier. */
    @Test
    fun theLookupsSurviveAClamp() {
        val anchor = on("2026-01-31")
        assertEquals(on("2026-02-28"), RecurrenceMath.firstSeriesDateAfter(anchor, on("2026-02-01"), 1, RecurrenceUnit.MONTH))
        assertEquals(on("2026-03-31"), RecurrenceMath.firstSeriesDateAfter(anchor, on("2026-02-28"), 1, RecurrenceUnit.MONTH))
        assertEquals(on("2026-02-28"), RecurrenceMath.largestSeriesDateAtOrBefore(anchor, on("2026-03-30"), 1, RecurrenceUnit.MONTH))
    }

    /** The COMPLETION basis's one step, and the meter side's one sum. */
    @Test
    fun theCompletionStepAndTheMeterThreshold() {
        assertEquals(on("2026-12-12"), RecurrenceMath.plusInterval(on("2026-09-13"), 90, RecurrenceUnit.DAY))
        assertEquals(on("2027-08-20"), RecurrenceMath.plusInterval(on("2026-08-20"), 12, RecurrenceUnit.MONTH))
        assertEquals(170.0, RecurrenceMath.meterThreshold(120.0, 50.0))
    }
}
