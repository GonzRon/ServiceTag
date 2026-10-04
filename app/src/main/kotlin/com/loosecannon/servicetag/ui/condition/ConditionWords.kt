package com.loosecannon.servicetag.ui.condition

import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.l10n.localizedDate
import com.loosecannon.servicetag.ui.health.ComponentCondition
import java.time.LocalDate

/*
 * The words of operational condition (spec §10.7), RATIFIED and drawn by number, verbatim. Every
 * surface that names a condition — the scan sheet, the Change condition sheet, the dashboard and
 * asset detail (B13, B14) — reads them from here and defines none of its own (master plan §19).
 * #102: the words themselves are string resources (`strings_components_health.xml`), read when drawn.
 */

/** S1: the condition word for OPERATIONAL. */
val CONDITION_OPERATIONAL: String get() = localized(R.string.condition_word_operational)

/** S2: the condition word for DEGRADED. */
val CONDITION_DEGRADED: String get() = localized(R.string.condition_word_degraded)

/** S3: the condition word for DOWN. */
val CONDITION_DOWN: String get() = localized(R.string.condition_word_down)

/** S4: an asset with no condition row. Nothing stores an UNKNOWN; this is the absence of a row. */
val CONDITION_NOT_RECORDED: String get() = localized(R.string.condition_not_recorded)

/** S8: the option (and dashboard chip) for OPERATIONAL. */
val OPTION_OPERATIONAL: String get() = localized(R.string.condition_option_operational)

/** S9: its helper. */
val HELPER_OPERATIONAL: String get() = localized(R.string.condition_helper_operational)

/** S10: the option (and dashboard chip) for DEGRADED. */
val OPTION_DEGRADED: String get() = localized(R.string.condition_option_degraded)

/** S11: its helper. */
val HELPER_DEGRADED: String get() = localized(R.string.condition_helper_degraded)

/** S12: the option (and dashboard chip) for DOWN. */
val OPTION_DOWN: String get() = localized(R.string.condition_option_down)

/** S13: its helper. */
val HELPER_DOWN: String get() = localized(R.string.condition_helper_down)

/** S23: an empty reason, wherever a reason would appear (the block, S27, history). */
val NO_REASON_GIVEN: String get() = localized(R.string.condition_no_reason_given)

/**
 * P82-10 (#82, R82-7, R82-8): the way to a new Incident — on asset detail's Condition section for every
 * asset in service, and on the scan sheet while the current failure has none. It only navigates.
 */
val LOG_INCIDENT: String get() = localized(R.string.condition_log_incident)

/** S1–S3 for a recorded condition, S4 for none. */
fun conditionWord(condition: OperationalCondition?): String = when (condition) {
    OperationalCondition.OPERATIONAL -> CONDITION_OPERATIONAL
    OperationalCondition.DEGRADED -> CONDITION_DEGRADED
    OperationalCondition.DOWN -> CONDITION_DOWN
    null -> CONDITION_NOT_RECORDED
}

/** S8, S10, S12: the option a person picks in Change condition, and the dashboard chip. */
fun conditionOption(condition: OperationalCondition): String = when (condition) {
    OperationalCondition.OPERATIONAL -> OPTION_OPERATIONAL
    OperationalCondition.DEGRADED -> OPTION_DEGRADED
    OperationalCondition.DOWN -> OPTION_DOWN
}

/** S9, S11, S13: the helper under each option. */
fun conditionHelper(condition: OperationalCondition): String = when (condition) {
    OperationalCondition.OPERATIONAL -> HELPER_OPERATIONAL
    OperationalCondition.DEGRADED -> HELPER_DEGRADED
    OperationalCondition.DOWN -> HELPER_DOWN
}

/** A reason as it is drawn: the text itself, or S23 when it is empty. */
fun reasonLine(reason: String): String = reason.ifBlank { NO_REASON_GIVEN }

/**
 * S22, "since <date>": the badge suffix. [since] is the first day of the latest run of the current
 * condition (`ConditionHistory.since`), so recording DOWN again with a new reason does not move it.
 * [format] is the display shape — [displayDate] everywhere in the app.
 */
fun sinceLine(since: LocalDate, format: (LocalDate) -> String): String = localized(R.string.condition_since, format(since))

/**
 * S27, "<component> <DOWN/DEGRADED> — <reason, or S23 when empty>": one DOWN or DEGRADED component,
 * wherever the asset above it is shown, so nothing hides behind the parent (inv. 119).
 */
fun componentLine(component: ComponentCondition): String =
    localized(
        R.string.condition_component_line,
        component.name,
        conditionWord(component.condition),
        reasonLine(component.reason),
    )

/**
 * The shipped display-date shape — the snooze line's and every other date the app draws — for `<date>`
 * in S22, S99 and S143. #102: it is [localizedDate], so English keeps `d MMM uuuu` ("1 Mar 2026") and a
 * language pack gives its own order and month names.
 */
fun displayDate(date: LocalDate): String = localizedDate(date)
