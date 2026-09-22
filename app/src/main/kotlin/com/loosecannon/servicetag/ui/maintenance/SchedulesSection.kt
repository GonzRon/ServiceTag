package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.ui.components.QuietLine

/**
 * A titled block of due rows, shared by the Maintenance destination's "Due work" and "Schedules"
 * sections and by the dashboard's asset section (B15).
 *
 * [empty] is drawn in place of the rows when there are none, so a section that is present but has
 * nothing in it says why rather than showing a bare header. A section that should not appear at all
 * is simply not called — the dashboard's D12 §10 rule about omitting an empty section belongs to
 * the dashboard, which never passes an [empty] line.
 */
@Composable
fun SchedulesSection(
    title: String,
    items: List<DueItem>,
    onOpen: (DueItem) -> Unit,
    modifier: Modifier = Modifier,
    empty: String? = null,
    onRepair: ((DueItem) -> Unit)? = null,
) {
    Column(modifier = modifier) {
        MaintenanceSectionTitle(title)
        if (items.isEmpty()) {
            empty?.let { QuietLine(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            return@Column
        }
        items.forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
            DueItemRow(
                item = item,
                onClick = { onOpen(item) },
                // The repair is the repairable form's alone; `DueItemRow` re-checks the same
                // predicate, so the label cannot come back by a caller forgetting the gate
                // (invariant 74, §17.1a).
                onRepair = onRepair?.let { repair -> { repair(item) } },
            )
        }
    }
}

/**
 * A section heading on the Maintenance destination, drawn in the shared `SectionHeader` idiom —
 * `labelMedium` on `onSurfaceVariant` over a 1dp rule (G1 §1.1) — but **without** its
 * `.uppercase()`.
 *
 * The dashboard's four section labels are ratified *in* upper case (ATTENTION · UPCOMING · CURRENT
 * · OUT OF SEASON, D12 §10) so `SectionHeader` suits them exactly. This destination's four are
 * ratified in sentence case — "Due work", "Schedules", "Maintenance groups", "Reminders" (§17.1f) —
 * and upper-casing a ratified string is paraphrasing it, which no brief may do.
 *
 * [trailing] is the section's own action, drawn at the end of the heading row as `SectionHeader`
 * already does it — the asset screen's schedules section puts B14's "add a schedule" glyph there.
 */
@Composable
fun MaintenanceSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.padding(top = 18.dp, bottom = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}
