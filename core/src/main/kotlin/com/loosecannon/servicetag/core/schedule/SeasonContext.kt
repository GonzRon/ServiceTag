package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.Season
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonInputs
import com.loosecannon.servicetag.core.model.SeasonMode
import java.time.LocalDate
import java.time.Year

/** Whether an asset is in its operating season on a day (spec §3.1). */
enum class SeasonPhase { IN_SEASON, OUT_OF_SEASON }

/**
 * What PRE_SERVICE counts back from (spec §4.3): the start of a calendar season, the start of the
 * maintenance break when there is no calendar season, or nothing at all.
 */
enum class BoundaryKind { SEASON, BREAK, NONE }

/** One PRE_SERVICE boundary: the day it falls on and what kind of day it is. */
data class Boundary(val on: LocalDate, val kind: BoundaryKind)

/**
 * The kind of boundary an asset offers PRE_SERVICE: a CALENDAR asset's season start, else the break's
 * start when it has one, else none. A MANUAL asset never offers its START, because a future START is
 * never predicted (Q-6, O-5): with a break it offers the break like a YEAR_ROUND asset (O-3).
 */
fun boundaryKindOf(mode: SeasonMode, hasBreak: Boolean): BoundaryKind = when {
    mode == SeasonMode.CALENDAR -> BoundaryKind.SEASON
    hasBreak -> BoundaryKind.BREAK
    else -> BoundaryKind.NONE
}

/**
 * One asset's season and break as questions about days (master plan §7.1, spec §3.1, §4.3, §4.4).
 *
 * Built once from [SeasonInputs] and pure from then on: every answer is a function of the inputs
 * and the day asked about, and nothing here reads a clock. It is **total** — a CALENDAR asset whose
 * window is missing or malformed, or a break with only one bound, reads as having no window and no
 * break rather than throwing, because the engine is not where those rules are enforced (the commands
 * and the decoder are), and a read model that threw on one merged row would take a whole list down.
 *
 * The calendar predicate is the shipped [Season.inSeason], unchanged: inclusive, wrapping, and a
 * 02-29 bound read as 02-28 in a common year (#14 AC 6). The break uses the **same** predicate, so
 * every consumer — the engine, the health clock, the season view — agrees about which days it covers.
 *
 * Activation rows are read **only** in MANUAL mode (inv. 90): in any other mode they are dropped here
 * and can never answer a question.
 */
class SeasonContext private constructor(
    val mode: SeasonMode,
    private val window: YearlyWindow?,
    private val breakWindow: YearlyWindow?,
    /** MANUAL only, oldest first by `(occurredOn, createdAt, id)`. */
    private val activations: List<Dated>,
) {

    val boundaryKind: BoundaryKind = boundaryKindOf(mode, breakWindow != null)

    /**
     * YEAR_ROUND is always in season; CALENDAR reads the window; MANUAL is the action of the latest
     * activation dated on or before [d] — START in season from its own day, END out of season from
     * its own day — and **no row at all is out of season**, which keeps the rule total for an import
     * that carries a MANUAL asset with no history.
     */
    fun phaseAt(d: LocalDate): SeasonPhase = when (mode) {
        SeasonMode.YEAR_ROUND -> SeasonPhase.IN_SEASON
        SeasonMode.CALENDAR -> if (window == null || window.contains(d)) SeasonPhase.IN_SEASON else SeasonPhase.OUT_OF_SEASON
        SeasonMode.MANUAL -> if (latestOnOrBefore(d)?.action == SeasonAction.START) {
            SeasonPhase.IN_SEASON
        } else {
            SeasonPhase.OUT_OF_SEASON
        }
    }

    /**
     * The first day of the season span containing [d] (spec §3.1). A CALENDAR asset out of season
     * answers its **next** start; a MANUAL asset out of season answers null, because its next START
     * is never predicted; a YEAR_ROUND asset has no season to start and answers null.
     *
     * A MANUAL span is the run of rows since the last END: two STARTs in a row (only a merge makes
     * them) are one span, and it began at the first of them.
     */
    fun cycleStartAt(d: LocalDate): LocalDate? = when (mode) {
        SeasonMode.YEAR_ROUND -> null
        SeasonMode.CALENDAR -> window?.let { if (it.contains(d)) it.latestStartOnOrBefore(d) else it.nextStartAfter(d) }
        SeasonMode.MANUAL -> if (phaseAt(d) == SeasonPhase.IN_SEASON) manualSpanStartOnOrBefore(d) else null
    }

    /**
     * The start of the latest span that began on or before [d], whether or not it is still running:
     * the calendar start at or before [d], or the recorded START that opened the latest MANUAL span.
     * Null for YEAR_ROUND and for a MANUAL asset that has never started.
     */
    fun latestCycleStartOnOrBefore(d: LocalDate): LocalDate? = when (mode) {
        SeasonMode.YEAR_ROUND -> null
        SeasonMode.CALENDAR -> window?.latestStartOnOrBefore(d)
        SeasonMode.MANUAL -> manualSpanStartOnOrBefore(d)
    }

    /** The last day of the CALENDAR span containing [d]; null out of season and in any other mode. */
    fun seasonEndAt(d: LocalDate): LocalDate? {
        if (mode != SeasonMode.CALENDAR) return null
        val w = window ?: return null
        return if (w.contains(d)) w.earliestEndOnOrAfter(d) else null
    }

    /** Whether [d] lies inside the maintenance break. */
    fun inBreak(d: LocalDate): Boolean = breakWindow?.contains(d) == true

    /** Whether work may be actionable on [d]: every day outside the break. */
    fun allowed(d: LocalDate): Boolean = !inBreak(d)

    /**
     * [d] itself when it is allowed, else the first day after the break that contains it.
     *
     * A break that covers every day of some year is refused by its command (`BLACKOUT_COVERS_THE_YEAR`)
     * and so reaches here only through a merge; with no allowed day to move to, the date is left
     * where it was, which keeps this total.
     */
    fun firstAllowedAfter(d: LocalDate): LocalDate {
        val b = breakWindow ?: return d
        if (!b.contains(d)) return d
        var day = b.earliestEndOnOrAfter(d).plusDays(1)
        repeat(MAX_SCAN_DAYS) {
            if (!b.contains(day)) return day
            day = day.plusDays(1)
        }
        return d
    }

    /**
     * PRE_SERVICE's boundary for the raw due [r] (spec §4.3). CALENDAR: the start of the season
     * containing [r], or else the next start after it. Otherwise, with a break: the start of the break
     * containing [r], or else the next one (O-3). Otherwise none. **Never a manual START** (inv. 99).
     */
    fun preServiceBoundary(r: LocalDate): Boundary? = when (boundaryKind) {
        BoundaryKind.SEASON -> window?.let {
            Boundary(if (it.contains(r)) it.latestStartOnOrBefore(r) else it.nextStartAfter(r), BoundaryKind.SEASON)
        }
        BoundaryKind.BREAK -> breakWindow?.let {
            Boundary(if (it.contains(r)) it.latestStartOnOrBefore(r) else it.nextStartAfter(r), BoundaryKind.BREAK)
        }
        BoundaryKind.NONE -> null
    }

    /**
     * The pre-season window `W` for the boundary [s]: the maximal run of allowed days ending on
     * `s − 1`, or null when `s − 1` is inside the break (spec §4.3). With no break at all the run has
     * no first day, which [PreSeasonWindow.first] reports as null.
     */
    internal fun preSeasonWindow(s: LocalDate): PreSeasonWindow? {
        val last = s.minusDays(1)
        val b = breakWindow ?: return PreSeasonWindow(first = null, last = last)
        if (b.contains(last)) return null
        var first = b.latestEndOnOrBefore(last).plusDays(1)
        // The run between two occurrences of one yearly break is allowed by construction; the scan
        // only guards a break whose bounds collide in a common year, which no command can store.
        var guard = 0
        while (b.contains(first) && first < last && guard++ < MAX_SCAN_DAYS) first = first.plusDays(1)
        return PreSeasonWindow(first = first, last = last)
    }

    /**
     * The last allowed day before the break containing [d] — the day before that break starts. Only
     * reached when the pre-season window is empty (O-4), and total like [firstAllowedAfter].
     */
    internal fun dayBeforeBreakContaining(d: LocalDate): LocalDate {
        val b = breakWindow ?: return d
        if (!b.contains(d)) return d
        var day = b.latestStartOnOrBefore(d).minusDays(1)
        repeat(MAX_SCAN_DAYS) {
            if (!b.contains(day)) return day
            day = day.minusDays(1)
        }
        return d
    }

    /** The latest activation dated on or before [d]; rows are held oldest first. */
    private fun latestOnOrBefore(d: LocalDate): Dated? = activations.lastOrNull { it.on <= d }

    /** The first START of the run the latest row on or before [d] belongs to, when that row is a START. */
    private fun manualSpanStartOnOrBefore(d: LocalDate): LocalDate? {
        val upTo = activations.filter { it.on <= d }
        val lastStart = upTo.indexOfLast { it.action == SeasonAction.START }
        if (lastStart < 0) return null
        var first = lastStart
        while (first > 0 && upTo[first - 1].action == SeasonAction.START) first--
        return upTo[first].on
    }

    /** The pre-season window: [first] null means it runs back without end (there is no break). */
    internal data class PreSeasonWindow(val first: LocalDate?, val last: LocalDate) {
        operator fun contains(d: LocalDate): Boolean = d <= last && (first == null || d >= first)

        /** The latest day of the window on or before [d], or null when the window starts after [d]. */
        fun lastOnOrBefore(d: LocalDate): LocalDate? {
            val candidate = minOf(d, last)
            return if (first == null || candidate >= first) candidate else null
        }
    }

    /** One activation reduced to what the phase reads. */
    private data class Dated(val on: LocalDate, val action: SeasonAction)

    /**
     * A yearly `MM-DD` window, inclusive and wrapping. [contains] is the shipped [Season.inSeason];
     * the arithmetic around it resolves each bound per year with the same 02-29 rule, so the dates it
     * returns agree with the predicate on every year.
     */
    private class YearlyWindow(private val start: String, private val end: String) {
        private val startMonth = start.substring(0, 2).toInt()
        private val startDay = start.substring(3, 5).toInt()
        private val endMonth = end.substring(0, 2).toInt()
        private val endDay = end.substring(3, 5).toInt()

        fun contains(d: LocalDate): Boolean = Season.inSeason(start, end, d)

        fun latestStartOnOrBefore(d: LocalDate): LocalDate =
            resolve(startMonth, startDay, d.year).takeIf { it <= d } ?: resolve(startMonth, startDay, d.year - 1)

        fun nextStartAfter(d: LocalDate): LocalDate =
            resolve(startMonth, startDay, d.year).takeIf { it > d } ?: resolve(startMonth, startDay, d.year + 1)

        fun latestEndOnOrBefore(d: LocalDate): LocalDate =
            resolve(endMonth, endDay, d.year).takeIf { it <= d } ?: resolve(endMonth, endDay, d.year - 1)

        fun earliestEndOnOrAfter(d: LocalDate): LocalDate =
            resolve(endMonth, endDay, d.year).takeIf { it >= d } ?: resolve(endMonth, endDay, d.year + 1)

        private fun resolve(month: Int, day: Int, year: Int): LocalDate =
            LocalDate.of(year, month, if (month == 2 && day == 29 && !Year.isLeap(year.toLong())) 28 else day)
    }

    companion object {
        /** Longer than any year, so a scan over one break always finds its far side when it has one. */
        private const val MAX_SCAN_DAYS = 800

        fun of(inputs: SeasonInputs): SeasonContext = SeasonContext(
            mode = inputs.mode,
            window = if (inputs.mode == SeasonMode.CALENDAR) {
                windowOf(inputs.seasonStartMmdd, inputs.seasonEndMmdd)
            } else {
                null
            },
            breakWindow = windowOf(inputs.blackoutStartMmdd, inputs.blackoutEndMmdd),
            activations = if (inputs.mode == SeasonMode.MANUAL) ordered(inputs.activations) else emptyList(),
        )

        private fun windowOf(start: String?, end: String?): YearlyWindow? {
            if (start == null || end == null) return null
            if (Season.validate(start, end).isNotEmpty()) return null
            return YearlyWindow(start, end)
        }

        /** `(occurredOn, createdAt, id)`, whatever order the caller handed them in. */
        private fun ordered(rows: List<SeasonActivation>): List<Dated> = rows
            .mapNotNull { row -> runCatching { LocalDate.parse(row.occurredOn) }.getOrNull()?.let { row to it } }
            .sortedWith(compareBy({ it.second }, { it.first.createdAt }, { it.first.id }))
            .map { (row, on) -> Dated(on, row.action) }
    }
}
