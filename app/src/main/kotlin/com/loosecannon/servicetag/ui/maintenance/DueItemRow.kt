package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.ServiceTagIcons
import com.loosecannon.servicetag.ui.components.StatusBadge
import com.loosecannon.servicetag.ui.journal.formatNumber
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.LocalServiceTagSemanticColors
import com.loosecannon.servicetag.ui.theme.ServiceTagSemanticColors
import com.loosecannon.servicetag.ui.theme.StatusColor

/**
 * The RATIFIED status word for each derived status (spec §9.1, D12 §5 `:274-296`).
 *
 * **"NO BASELINE" belongs to the repairable form of `NO_DATA` and to nothing else.** An
 * empty-required-set row reads `NO_DATA` too and must never receive the meter-baseline wording
 * merely because its status enum says so (master plan §17.1a, invariants 74, 77) — so no caller may
 * hand this function a status without first asking whether the row's required set is empty. The one
 * caller that draws a badge, [DueItemRow], asks; and §17 ratifies **no** word for "this round
 * obliges nobody", so such a row is drawn with no status treatment at all rather than a drafted one.
 */
fun statusLabel(status: DueStatus): String = when (status) {
    DueStatus.OK -> "OK"
    DueStatus.DUE_SOON -> "DUE SOON"
    DueStatus.DUE -> "DUE"
    DueStatus.OVERDUE -> "OVERDUE"
    DueStatus.INACTIVE_SEASON -> "OUT OF SEASON"
    DueStatus.PAUSED -> "PAUSED"
    DueStatus.NO_DATA -> "NO BASELINE"
}

/** The RATIFIED dashboard section labels (D12 §10 `:706-707`). */
fun sectionLabel(section: AttentionSection): String = when (section) {
    AttentionSection.ATTENTION -> "ATTENTION"
    AttentionSection.UPCOMING -> "UPCOMING"
    AttentionSection.CURRENT -> "CURRENT"
    AttentionSection.OUT_OF_SEASON -> "OUT OF SEASON"
}

/**
 * The glyph half of D12 §5's four channels, named rather than resolved, so that "every status has a
 * **different** glyph" is a fact a JVM test can assert.
 *
 * Colour is reinforcement and the hierarchy has to survive grayscale: with the palette removed the
 * word, the glyph and the row's position are what tell the seven states apart (#5 AC 2). That is
 * three channels, and a duplicate glyph would quietly reduce it to two — which is exactly the kind
 * of regression [statusGlyph]'s distinctness test exists to catch. [statusIcon] resolves a member to
 * its vector and is `@Composable` only because [ServiceTagIcons] reads its drawables out of
 * resources.
 */
enum class StatusGlyph { CHECK_CIRCLE, SCHEDULE, EVENT, WARNING, CALENDAR_MONTH, PAUSE_CIRCLE, METER }

/**
 * One glyph per status, from D12 §5's table row by row. `NO BASELINE` takes the meter glyph rather
 * than a warning: what is missing is a reading, and the row's own repair action says so.
 */
fun statusGlyph(status: DueStatus): StatusGlyph = when (status) {
    DueStatus.OK -> StatusGlyph.CHECK_CIRCLE
    DueStatus.DUE_SOON -> StatusGlyph.SCHEDULE
    DueStatus.DUE -> StatusGlyph.EVENT
    DueStatus.OVERDUE -> StatusGlyph.WARNING
    DueStatus.INACTIVE_SEASON -> StatusGlyph.CALENDAR_MONTH
    DueStatus.PAUSED -> StatusGlyph.PAUSE_CIRCLE
    DueStatus.NO_DATA -> StatusGlyph.METER
}

@Composable
fun statusIcon(status: DueStatus): ImageVector = when (statusGlyph(status)) {
    StatusGlyph.CHECK_CIRCLE -> Icons.Outlined.CheckCircle
    StatusGlyph.SCHEDULE -> ServiceTagIcons.Schedule
    StatusGlyph.EVENT -> ServiceTagIcons.Event
    StatusGlyph.WARNING -> Icons.Outlined.Warning
    StatusGlyph.CALENDAR_MONTH -> ServiceTagIcons.CalendarMonth
    StatusGlyph.PAUSE_CIRCLE -> ServiceTagIcons.PauseCircle
    StatusGlyph.METER -> ServiceTagIcons.Speed
}

/** D12 §5's row for each status. Never read at a call site as a raw colour (D12 §15). */
fun statusColors(status: DueStatus, colors: ServiceTagSemanticColors): StatusColor = when (status) {
    DueStatus.OK -> colors.maintenanceOkay
    DueStatus.DUE_SOON -> colors.dueSoon
    DueStatus.DUE -> colors.due
    DueStatus.OVERDUE -> colors.overdue
    DueStatus.INACTIVE_SEASON -> colors.seasonInactive
    DueStatus.PAUSED -> colors.paused
    DueStatus.NO_DATA -> colors.measurementNoTarget
}

/** The RATIFIED repair label, offered only by a **repairable** missing-meter-baseline row. */
const val LOG_METER_READING = "Log meter reading"

/**
 * Whether this row is the **repairable** `NO_DATA` — a missing meter baseline, which is what "Log
 * meter reading" repairs. The empty-required-set form reads `NO_DATA` too and is never offered a
 * repair, which is why the flag and not the status alone is the question asked.
 */
val DueItem.isRepairableNoData: Boolean
    get() = status == DueStatus.NO_DATA && !requiredSetEmpty

/**
 * F3's line, from the two fields the projection already carries: **"Due at \<n\> \<unit\>, now
 * \<n\>."**
 *
 * Null in the two cases where there is nothing the data supports saying: a schedule with no meter
 * rule at all, and a meter rule with no baseline — the second is a `NO_DATA` row and states its
 * repair instead of a number it does not have. Synthesising a current reading where the journal
 * holds none would be stating a number as fact.
 *
 * A definition whose `unit` is blank (pH has none, `Journal.kt:12`) yields "Due at 500, now 520." —
 * the ratified form with an empty placeholder rather than a stray space or an invented unit. The
 * two numbers are the point of the line and they are both real; withholding the whole line because
 * the thing being counted is unitless would hide the only fact the row has.
 */
fun meterLine(item: DueItem): String? {
    val due = item.computedDueMeter ?: return null
    val now = item.currentMeter ?: return null
    val unit = item.meterUnit.orEmpty()
    return "Due at ${formatNumber(due)}${if (unit.isEmpty()) "" else " $unit"}, now ${formatNumber(now)}."
}

/**
 * The RATIFIED progress form of a group row: "3 of 5 complete".
 *
 * Null for an **empty required set**, and not "0 of 0 complete": that reads as *done*, and
 * emptiness never means complete (§11.1, invariant 74). A round that obliges nobody has no progress
 * to report, so it reports none.
 */
fun progressLine(item: DueItem): String? {
    if (item.requiredSetEmpty) return null
    val required = item.membersRequired ?: return null
    val complete = item.membersComplete ?: return null
    return "$complete of $required complete"
}

/**
 * One schedule, wherever it is listed: the dashboard's attention sections, the Maintenance
 * destination's due work and schedules sections, and B09's sheet.
 *
 * Four channels per D12 §5, and position is one of them — so this row never restates its section.
 * A **component**'s row names its parent, which is what makes a promoted part's due work
 * understandable rather than a name with no home (§11.1, #5 AC 1); a **group** row carries the
 * ratified progress form instead, and is one row however many members are outstanding (D-15).
 *
 * **An empty-required-set row gets no status treatment at all** — no word, no glyph, no colour, no
 * progress line and no repair (master plan §17.1a). Every one of those would say something about a
 * round that obliges nobody: "NO BASELINE" would apply the meter-baseline wording to a row with no
 * meter, the meter glyph would do it in a picture, "0 of 0 complete" would read as done, and "Log
 * meter reading" would offer a repair for a condition no reading fixes. §17 ratifies no word for
 * this state, so the row says its name and what it is on, and nothing it cannot support. D12 §5 has
 * precedent for a state drawn without a glyph (its "Measurement no target" row).
 *
 * [onRepair] is offered **only** where the caller has a repairable row to repair; it is ignored on
 * any other row, so a caller cannot reintroduce the label by forgetting the gate.
 */
@Composable
fun DueItemRow(
    item: DueItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRepair: (() -> Unit)? = null,
) {
    val colors = statusColors(item.status, LocalServiceTagSemanticColors.current)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        if (item.requiredSetEmpty) {
            // The glyph column is held open so the text block still lines up with every other row.
            Spacer(modifier = Modifier.size(28.dp))
        } else {
            Icon(
                imageVector = statusIcon(item.status),
                contentDescription = null,
                tint = colors.foreground,
                modifier = Modifier.size(28.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!item.requiredSetEmpty) {
                    StatusBadge(
                        label = statusLabel(item.status),
                        colors = colors,
                        icon = statusIcon(item.status),
                    )
                }
            }
            QuietLine(subtitleOf(item))
            meterLine(item)?.let { QuietLine(it) }
            progressLine(item)?.let { QuietLine(it) }
            if (onRepair != null && item.isRepairableNoData) {
                TextButton(onClick = onRepair, shape = ControlShape) { Text(LOG_METER_READING) }
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What the row says under its title: the thing the work is on, and for a component the system it is
 * part of — the same "Part of <parent>" the dashboard's asset rows use, so a promoted part reads
 * the same way whichever list it came from.
 */
private fun subtitleOf(item: DueItem): String =
    item.parentName?.let { parent -> "${item.assetName} · Part of $parent" } ?: item.assetName
