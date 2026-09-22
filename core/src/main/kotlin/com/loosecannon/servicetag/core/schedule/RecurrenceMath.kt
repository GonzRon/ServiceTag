package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.RecurrenceUnit
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The time side's calendar arithmetic and the meter side's one sum: pure functions over dates and
 * numbers, with no repository, no clock and no schedule.
 *
 * **Every date is computed from the anchor with a multiplier, never iteratively** (D5 §2.1). That
 * is the whole design: `java.time` clamps a month-end or leap-day overflow to the last valid day,
 * so an iterative `plusMonths` would take the *clamped* date as its next base and drift off the
 * series for good — Jan 31 → Feb 28 → Mar 28 instead of Mar 31, and a Feb 29 anchor stuck on Feb 28
 * for every later year. Computed from the anchor, the series returns to the 31st and to Feb 29 on
 * its own, and no special case is needed for a year wrap.
 *
 * The series is `anchor + k·interval` for `k >= 0`, and it is strictly increasing in `k` for every
 * unit, which is what lets the three lookups below estimate `k` and then step at most a place or
 * two to the answer.
 */
object RecurrenceMath {

    /** `anchor + k·interval` — the k-th date of the series, clamped by `java.time`'s own rules. */
    fun seriesDate(anchor: LocalDate, k: Int, interval: Int, unit: RecurrenceUnit): LocalDate {
        require(k >= 0) { "k must not be negative, was $k" }
        require(interval >= 1) { "interval must be at least 1, was $interval" }
        val steps = k.toLong() * interval.toLong()
        return plusSteps(anchor, steps, unit)
    }

    /** The smallest `k` whose series date is **not before** [floor]; 0 when [floor] is at or before the anchor. */
    fun kAtOrAfter(anchor: LocalDate, floor: LocalDate, interval: Int, unit: RecurrenceUnit): Int {
        require(interval >= 1) { "interval must be at least 1, was $interval" }
        if (!floor.isAfter(anchor)) return 0
        var k = estimateK(anchor, floor, interval, unit)
        // The estimate counts whole units, so it can land one place either side of the answer once
        // a clamp is involved; both walks are bounded by that, not by the size of the interval.
        while (k > 0 && !seriesDate(anchor, k - 1, interval, unit).isBefore(floor)) k -= 1
        while (seriesDate(anchor, k, interval, unit).isBefore(floor)) k += 1
        return k
    }

    /** The smallest series date `>= [floor]` — the pin, and the first occurrence of a new series. */
    fun firstSeriesDateAtOrAfter(
        anchor: LocalDate,
        floor: LocalDate,
        interval: Int,
        unit: RecurrenceUnit,
    ): LocalDate = seriesDate(anchor, kAtOrAfter(anchor, floor, interval, unit), interval, unit)

    /**
     * The smallest series date **strictly after** [after] — the FIXED advance, where [after] is
     * `max(D, E)`. Strictly after is what makes a completion *on* the due date advance to the next
     * occurrence instead of handing back the one just satisfied.
     */
    fun firstSeriesDateAfter(
        anchor: LocalDate,
        after: LocalDate,
        interval: Int,
        unit: RecurrenceUnit,
    ): LocalDate {
        var k = kAtOrAfter(anchor, after, interval, unit)
        while (!seriesDate(anchor, k, interval, unit).isAfter(after)) k += 1
        return seriesDate(anchor, k, interval, unit)
    }

    /**
     * The largest series date `<= [limit]`, or null when [limit] is before the anchor. This is the
     * **documented-approximate** reconstruction spec §2.2 keeps for a completion that carries no
     * `occurrence_on`: it is exact except for an early completion, where the occurrence it
     * satisfied lies *after* the date it was done.
     */
    fun largestSeriesDateAtOrBefore(
        anchor: LocalDate,
        limit: LocalDate,
        interval: Int,
        unit: RecurrenceUnit,
    ): LocalDate? {
        if (limit.isBefore(anchor)) return null
        val k = kAtOrAfter(anchor, limit, interval, unit)
        val at = seriesDate(anchor, k, interval, unit)
        return if (!at.isAfter(limit)) at else seriesDate(anchor, k - 1, interval, unit)
    }

    /**
     * The COMPLETION basis's single step: `E + interval`, the same value as `seriesDate(from, 1, …)`.
     *
     * The engine reaches the COMPLETION series through [firstSeriesDateAfter] now, because the next
     * occurrence also has to clear the one just satisfied; this stays as the name of what the basis
     * *means*, and as the one-step case its own test pins.
     */
    fun plusInterval(from: LocalDate, interval: Int, unit: RecurrenceUnit): LocalDate {
        require(interval >= 1) { "interval must be at least 1, was $interval" }
        return plusSteps(from, interval.toLong(), unit)
    }

    /** The meter side's threshold: `lastCompletedMeter + meterInterval`. */
    fun meterThreshold(baseline: Double, interval: Double): Double = baseline + interval

    private fun plusSteps(from: LocalDate, steps: Long, unit: RecurrenceUnit): LocalDate = when (unit) {
        RecurrenceUnit.DAY -> from.plusDays(steps)
        RecurrenceUnit.WEEK -> from.plusWeeks(steps)
        RecurrenceUnit.MONTH -> from.plusMonths(steps)
        RecurrenceUnit.YEAR -> from.plusYears(steps)
    }

    /** Whole units between the two dates, divided by the interval: a starting guess, not the answer. */
    private fun estimateK(anchor: LocalDate, floor: LocalDate, interval: Int, unit: RecurrenceUnit): Int {
        val units = when (unit) {
            RecurrenceUnit.DAY -> ChronoUnit.DAYS.between(anchor, floor)
            RecurrenceUnit.WEEK -> ChronoUnit.WEEKS.between(anchor, floor)
            RecurrenceUnit.MONTH -> ChronoUnit.MONTHS.between(anchor, floor)
            RecurrenceUnit.YEAR -> ChronoUnit.YEARS.between(anchor, floor)
        }
        return (units / interval).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }
}
