package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

/** The RATIFIED status word for each derived status (spec §9.1, D12 §5 `:274-296`). */
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
 * The glyph half of D12 §5's four channels. Every status gets a **different** one, because colour
 * is reinforcement and the hierarchy has to survive grayscale: with the palette removed the word,
 * the glyph and the row's position are what still tell the seven states apart (#5 AC 2).
 *
 * `NO BASELINE` takes the meter glyph rather than a warning: what is missing is a reading, and the
 * row's own repair action says so.
 */
@Composable
fun statusIcon(status: DueStatus): ImageVector = when (status) {
    DueStatus.OK -> Icons.Outlined.CheckCircle
    DueStatus.DUE_SOON -> ServiceTagIcons.Schedule
    DueStatus.DUE -> ServiceTagIcons.Event
    DueStatus.OVERDUE -> Icons.Outlined.Warning
    DueStatus.INACTIVE_SEASON -> ServiceTagIcons.CalendarMonth
    DueStatus.PAUSED -> ServiceTagIcons.PauseCircle
    DueStatus.NO_DATA -> ServiceTagIcons.Speed
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
 * F3's line, from the two fields the projection already carries: **"Due at \<n\> \<unit\>, now
 * \<n\>."**
 *
 * Null in the two cases where there is nothing the data supports saying: a schedule with no meter
 * rule at all, and a meter rule with no baseline — the second is a `NO_DATA` row and states its
 * repair instead of a number it does not have. Synthesising a current reading where the journal
 * holds none would be stating a number as fact.
 */
fun meterLine(item: DueItem): String? {
    val due = item.computedDueMeter ?: return null
    val now = item.currentMeter ?: return null
    val unit = item.meterUnit.orEmpty()
    return "Due at ${formatNumber(due)}${if (unit.isEmpty()) "" else " $unit"}, now ${formatNumber(now)}."
}

/** The RATIFIED progress form of a group row: "3 of 5 complete". */
fun progressLine(item: DueItem): String? {
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
 * [onRepair] is offered **only** where the caller has a repairable row to repair. An
 * empty-required-set `NO_DATA` gets no repair label: there is nothing to log, and offering "Log
 * meter reading" there would attach meter-baseline wording to a condition that has no meter in it
 * (§17.1a, invariant 74).
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
        Icon(
            imageVector = statusIcon(item.status),
            contentDescription = null,
            tint = colors.foreground,
            modifier = Modifier.size(28.dp),
        )
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
                StatusBadge(label = statusLabel(item.status), colors = colors, icon = statusIcon(item.status))
            }
            QuietLine(subtitleOf(item))
            meterLine(item)?.let { QuietLine(it) }
            progressLine(item)?.let { QuietLine(it) }
            if (onRepair != null) {
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
