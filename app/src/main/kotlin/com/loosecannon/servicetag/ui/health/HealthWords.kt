package com.loosecannon.servicetag.ui.health

import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.health.DriverLine
import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.health.SubjectHealth
import com.loosecannon.servicetag.core.health.SubjectValue
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.l10n.localizedList
import com.loosecannon.servicetag.l10n.localizedPlural
import java.time.LocalDate

/*
 * The words of derived health (spec §6.6, §10.7), RATIFIED and drawn by number, verbatim. The
 * engine hands over data — a band, a score, a `DriverLine` — and every surface turns it into words
 * here, so the scan sheet, the dashboard (B13) and asset detail (B14) cannot say it three ways.
 * #102: the words are string resources (`strings_components_health.xml`, and `plurals.xml` for the
 * quantity-bearing lines), read when drawn; each sentence is one format string.
 */

/** S95. */
val BAND_NOMINAL: String get() = localized(R.string.health_band_nominal)

/** S96. */
val BAND_WARNING: String get() = localized(R.string.health_band_warning)

/** S97. */
val BAND_CRITICAL: String get() = localized(R.string.health_band_critical)

/** S98: a subject or an aggregate with no value. It is drawn as these words, never as a number (inv. 118). */
val NOT_TRACKED: String get() = localized(R.string.health_not_tracked)

/** S100. */
val NO_REPLACEMENT_RECORDED: String get() = localized(R.string.health_no_replacement_recorded)

/** S101. */
val REPLACEMENT_ACTION_REMOVED: String get() = localized(R.string.health_replacement_action_removed)

/** S105. */
val NOT_TRACKED_OUT_OF_SEASON: String get() = localized(R.string.health_not_tracked_out_of_season)

/** S106. */
val NOT_TRACKED_PAUSED: String get() = localized(R.string.health_not_tracked_paused)

/** S142. */
val NOT_TRACKED_LINK: String get() = localized(R.string.health_not_tracked_link)

/**
 * The two quantity-bearing substitutions (plan decision 29): S99's `<age>` and S102 whole. The
 * `other` form is the ratified text; English's `one` form drops only the unit's "s" ("1 day", "… is 1
 * day overdue") — an inflection of a ratified string, not a new one (master plan §17.2).
 *
 * #102: the forms are the Android plurals `health_age_days` and `health_days_overdue` in `plurals.xml`,
 * picked by the rendering language's own plural rules. Every form shows its number through a
 * placeholder, never as a literal "1", so whichever form a language's rules select — `one` takes 21
 * in Russian and 0 in French — still says the right number. [AndroidHealthPlurals] is the production
 * implementation over those plurals. It is an interface because `:app`'s JVM tests have no
 * Robolectric: they pass a fake and prove the quantity reaches it, and a connected contract test
 * proves the resources themselves.
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
    else -> localized(R.string.health_badge_label, bandWord(band), score)
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
        is DriverLine.Replaced -> localized(R.string.health_replaced, format(line.on), plurals.ageDays(line.ageDays))
        DriverLine.NoReplacement -> NO_REPLACEMENT_RECORDED
        DriverLine.ProfileRemoved -> REPLACEMENT_ACTION_REMOVED
        is DriverLine.Overdue -> plurals.daysOverdue(line.title, line.days)
        is DriverLine.UpToDate -> localized(R.string.health_up_to_date, line.title)
        is DriverLine.Grace -> localizedPlural(R.plurals.health_grace_period, line.days, line.days)
        is DriverLine.Postponed -> localized(R.string.health_postponed, line.title, format(line.to))
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
        .mapNotNull { subject ->
            (subject.value as? SubjectValue.Scored)?.let { localized(R.string.health_subject_score, subject.subject.name, it.score) }
        }
    return localized(R.string.health_aggregate_line, score, localizedList(named))
}

/**
 * S109, "Critical: <subject> <score>": one CRITICAL subject, whatever the aggregate says (inv. 119).
 * [subject] is one of `AssetHealthResult.critical`, which holds scored subjects only.
 */
fun criticalLine(subject: SubjectHealth): String {
    val value = subject.value
    require(value is SubjectValue.Scored) { "only a scored subject is critical: ${subject.subject.id}" }
    return localized(R.string.health_critical_line, subject.subject.name, value.score)
}

/** S110, "<subject> <BAND>": a health row on the dashboard. */
fun dashboardHealthRow(fact: SubjectBandFact): String = localized(R.string.health_dashboard_row, fact.subjectName, bandWord(fact.band))
