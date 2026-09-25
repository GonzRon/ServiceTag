package com.loosecannon.servicetag.ui.condition

import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.ui.health.ComponentCondition
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/*
 * The words of operational condition (spec §10.7), RATIFIED and drawn by number, verbatim. Every
 * surface that names a condition — the scan sheet, the Change condition sheet, the dashboard and
 * asset detail (B13, B14) — reads them from here and defines none of its own (master plan §19).
 */

/** S1: the condition word for OPERATIONAL. */
const val CONDITION_OPERATIONAL = "OPERATIONAL"

/** S2: the condition word for DEGRADED. */
const val CONDITION_DEGRADED = "DEGRADED"

/** S3: the condition word for DOWN. */
const val CONDITION_DOWN = "DOWN"

/** S4: an asset with no condition row. Nothing stores an UNKNOWN; this is the absence of a row. */
const val CONDITION_NOT_RECORDED = "Condition not recorded"

/** S8: the option (and dashboard chip) for OPERATIONAL. */
const val OPTION_OPERATIONAL = "Operational"

/** S9: its helper. */
const val HELPER_OPERATIONAL = "Available for normal use."

/** S10: the option (and dashboard chip) for DEGRADED. */
const val OPTION_DEGRADED = "Degraded"

/** S11: its helper. */
const val HELPER_DEGRADED = "Works, but with a known problem."

/** S12: the option (and dashboard chip) for DOWN. */
const val OPTION_DOWN = "Down"

/** S13: its helper. */
const val HELPER_DOWN = "Not available for its intended use."

/** S23: an empty reason, wherever a reason would appear (the block, S27, history). */
const val NO_REASON_GIVEN = "No reason given"

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
fun sinceLine(since: LocalDate, format: (LocalDate) -> String): String = "since ${format(since)}"

/**
 * S27, "<component> <DOWN/DEGRADED> — <reason, or S23 when empty>": one DOWN or DEGRADED component,
 * wherever the asset above it is shown, so nothing hides behind the parent (inv. 119).
 */
fun componentLine(component: ComponentCondition): String =
    "${component.name} ${conditionWord(component.condition)} — ${reasonLine(component.reason)}"

/**
 * The shipped display-date shape, `d MMM uuuu` — the snooze line's and every other date the app
 * draws — for `<date>` in S22, S99 and S143.
 */
fun displayDate(date: LocalDate): String = date.format(DISPLAY_DATE)

private val DISPLAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM uuuu")
