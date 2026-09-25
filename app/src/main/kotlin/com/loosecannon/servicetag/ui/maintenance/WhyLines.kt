package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.schedule.DueStatus
import java.time.LocalDate

/*
 * The why-lines (spec §10.5, §10.7 S85–S91), RATIFIED and drawn by number, verbatim. B13 owns them
 * (master plan §19); every surface that draws a schedule row reads its line from [whyLine] through
 * `DueItemRow`, so the dashboard and the Maintenance tab cannot word one differently.
 */

/** S86: a PRE_SERVICE row pulled ahead of the season's start. */
internal const val WHY_BEFORE_SEASON = "Made due before the season starts"

/** S87: a PRE_SERVICE row pulled ahead of the maintenance break. */
internal const val WHY_BEFORE_BREAK = "Made due before the maintenance break"

/** S90: a dormant row on a MANUAL asset, whose next start is never predicted (plan decision 37). */
internal const val WHY_UNTIL_YOU_START = "Out of season until you start it"

/** S91: the break withholds delivery; the row keeps its place (quiet changes delivery only). */
internal const val WHY_REMINDERS_WAIT = "Reminders wait for the maintenance break to end"

/** S85, "Held until <date> because of the maintenance break". */
internal fun heldUntilLine(date: LocalDate, format: (LocalDate) -> String): String =
    "Held until ${format(date)} because of the maintenance break"

/** S88, "Moved from <date> because the season was not running". */
internal fun movedFromLine(date: LocalDate, format: (LocalDate) -> String): String =
    "Moved from ${format(date)} because the season was not running"

/** S89, "Out of season until <date>". */
internal fun outOfSeasonUntilLine(date: LocalDate, format: (LocalDate) -> String): String =
    "Out of season until ${format(date)}"

/**
 * The one line under a schedule row that says why its date is what it is (spec §10.5; master plan
 * §13.3, plan decision 27), or null for none. The first that applies wins:
 *
 * 1. **DORMANT** — S89 with [DueItem.dormantUntil] on a CALENDAR asset, S90 on a MANUAL one. A
 *    dormant row is waiting for its season and nothing else about its date is the point.
 * 2. **DEFERRED** — S85 with [DueItem.actionableDueOn], the first allowed day after the break.
 * 3. **quiet** — S91. Quiet changes delivery, never placement, so an OVERDUE quiet row stays where
 *    it is and says only this.
 * 4. The policy's **reason** — BEFORE_SEASON (S86), BEFORE_BREAK (S87), or SEASON_START (S88 with
 *    [DueItem.effectiveDueOn], `P ?: R`, the date it was moved from).
 * 5. Otherwise none. `POLICY_INAPPLICABLE` draws none on a row; the schedule editor says S77 (B08).
 *
 * Every fact is read off the row the projection handed over; nothing here derives a date. [format]
 * is the app's display shape (`displayDate`).
 */
fun whyLine(item: DueItem, format: (LocalDate) -> String): String? = when {
    item.policyPhase == PolicyPhase.DORMANT -> when (item.seasonMode) {
        SeasonMode.CALENDAR -> item.dormantUntil?.let { outOfSeasonUntilLine(it, format) }
        SeasonMode.MANUAL -> WHY_UNTIL_YOU_START
        // A YEAR_ROUND asset is never out of season, and a group has no season at all.
        SeasonMode.YEAR_ROUND, null -> null
    }
    item.status == DueStatus.DEFERRED -> item.actionableDueOn?.let { heldUntilLine(it, format) }
    item.quiet -> WHY_REMINDERS_WAIT
    else -> when (item.policyReason) {
        PolicyReason.BEFORE_SEASON -> WHY_BEFORE_SEASON
        PolicyReason.BEFORE_BREAK -> WHY_BEFORE_BREAK
        PolicyReason.SEASON_START -> item.effectiveDueOn?.let { movedFromLine(it, format) }
        PolicyReason.NONE,
        PolicyReason.AFTER_BREAK,
        PolicyReason.POLICY_INAPPLICABLE,
        PolicyReason.AWAITING_START,
        -> null
    }
}
