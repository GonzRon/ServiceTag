package com.loosecannon.servicetag.ui.health

import com.loosecannon.servicetag.core.health.DriverLine
import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.health.SubjectHealth
import com.loosecannon.servicetag.core.health.SubjectValue
import java.time.LocalDate

/*
 * The words of derived health (spec §6.6, §10.7), RATIFIED and drawn by number, verbatim. The
 * engine hands over data — a band, a score, a `DriverLine` — and every surface turns it into words
 * here, so the scan sheet, the dashboard (B13) and asset detail (B14) cannot say it three ways.
 */

/** S95. */
const val BAND_NOMINAL = "NOMINAL"

/** S96. */
const val BAND_WARNING = "WARNING"

/** S97. */
const val BAND_CRITICAL = "CRITICAL"

/** S98: a subject or an aggregate with no value. It is drawn as these words, never as a number (inv. 118). */
const val NOT_TRACKED = "NOT TRACKED"

/** S100. */
const val NO_REPLACEMENT_RECORDED = "No replacement recorded yet"

/** S101. */
const val REPLACEMENT_ACTION_REMOVED = "The replacement quick action was removed."

/** S105. */
const val NOT_TRACKED_OUT_OF_SEASON = "Not tracked while out of season"

/** S106. */
const val NOT_TRACKED_PAUSED = "Not tracked while the schedule is paused"

/** S142. */
const val NOT_TRACKED_LINK = "Not tracked: the linked schedule has no date rule or belongs to another asset."

/**
 * The two quantity-bearing substitutions (plan decision 29): S99's `<age>` and S102 whole. The
 * `other` form is the ratified text; the `one` form drops only the unit's "s" ("1 day", "… is 1 day
 * overdue") — an inflection of a ratified string, not a new one (master plan §17.2).
 *
 * The forms are **four plain strings read by id**, chosen by **English** rules — `one` exactly when
 * the number is 1 — whatever the device language; there is no Android plurals resource, whose device
 * rules could read 0 or 21 as "1 day" (the controller's rulings on B12's review, I-2 and RS-3).
 * [AndroidHealthPlurals] is the production implementation over those strings. It is an interface
 * because `:app`'s JVM tests have no Robolectric: they pass a fake and prove the quantity reaches it,
 * and a connected contract test proves the strings themselves.
 */
interface HealthPlurals {
    /** S99's `<age>`: "1 day" or "`<n>` days". */
    fun ageDays(n: Long): String

    /** S102: "`<schedule>` is 1 day overdue" or "`<schedule>` is `<n>` days overdue". */
    fun daysOverdue(title: String, n: Long): String
}

/** S95–S97 for a band, S98 for none. */
fun bandWord(band: HealthBand?): String = when (band) {
    HealthBand.NOMINAL -> BAND_NOMINAL
    HealthBand.WARNING -> BAND_WARNING
    HealthBand.CRITICAL -> BAND_CRITICAL
    null -> NOT_TRACKED
}

/**
 * The health badge's label (spec §10.6: the band word and the score). A value with no band is
 * NOT TRACKED **whatever [score] says**: an untracked subject is excluded and never shown as a
 * number, least of all 100 (inv. 118).
 */
fun healthBadgeLabel(band: HealthBand?, score: Int?): String = when {
    band == null -> NOT_TRACKED
    score == null -> bandWord(band)
    else -> "${bandWord(band)} $score"
}

/**
 * One driver line in words (S99–S106, S142, S143), or null for no line. `<date>` is drawn by
 * [format], the app's display shape; S99's `<age>` and S102 go through [plurals].
 *
 * Which line a subject carries is the engine's decision, not this function's: a postponed
 * occurrence that is not late against its new date arrives as [DriverLine.Postponed] (S143) and is
 * otherwise [DriverLine.UpToDate] (S103), and a subject whose linked schedule is archived arrives
 * with no line at all.
 */
fun driverLineText(line: DriverLine, plurals: HealthPlurals, format: (LocalDate) -> String): String? =
    when (line) {
        is DriverLine.Replaced -> "Replaced ${format(line.on)}, ${plurals.ageDays(line.ageDays)} ago"
        DriverLine.NoReplacement -> NO_REPLACEMENT_RECORDED
        DriverLine.ProfileRemoved -> REPLACEMENT_ACTION_REMOVED
        is DriverLine.Overdue -> plurals.daysOverdue(line.title, line.days)
        is DriverLine.UpToDate -> "${line.title} is up to date"
        is DriverLine.Grace -> "Within the ${line.days}-day grace period"
        is DriverLine.Postponed -> "${line.title} was postponed to ${format(line.to)}"
        DriverLine.NotTrackedOutOfSeason -> NOT_TRACKED_OUT_OF_SEASON
        DriverLine.NotTrackedPaused -> NOT_TRACKED_PAUSED
        DriverLine.NotTrackedLink -> NOT_TRACKED_LINK
    }

/** Every line of one subject, in the engine's order; an archived link has none. */
fun driverLines(subject: SubjectHealth, plurals: HealthPlurals, format: (LocalDate) -> String): List<String> =
    subject.lines.mapNotNull { driverLineText(it, plurals, format) }

/**
 * S108, "<score> — <subject> <score>, <subject> <score>": the aggregate, then every contributor —
 * the non-archived subjects **with a value**, in `sortOrder` ([contributors] arrives in the engine's
 * `(sortOrder, id)` order, untracked ones included, and they are left out here).
 */
fun aggregateLine(score: Int, contributors: List<SubjectHealth>): String {
    val named = contributors
        .filter { it.subject.archivedAt == null }
        .mapNotNull { subject -> (subject.value as? SubjectValue.Scored)?.let { "${subject.subject.name} ${it.score}" } }
    return "$score — ${named.joinToString(", ")}"
}

/**
 * S109, "Critical: <subject> <score>": one CRITICAL subject, whatever the aggregate says (inv. 119).
 * [subject] is one of `AssetHealthResult.critical`, which holds scored subjects only.
 */
fun criticalLine(subject: SubjectHealth): String {
    val value = subject.value
    require(value is SubjectValue.Scored) { "only a scored subject is critical: ${subject.subject.id}" }
    return "Critical: ${subject.subject.name} ${value.score}"
}

/** S110, "<subject> <BAND>": a health row on the dashboard. */
fun dashboardHealthRow(fact: SubjectBandFact): String = "${fact.subjectName} ${bandWord(fact.band)}"
