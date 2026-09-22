package com.loosecannon.servicetag.ui.asset

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.maintenance.DueItem
import com.loosecannon.servicetag.ui.maintenance.DueItemRow
import com.loosecannon.servicetag.ui.maintenance.GROUPS_SECTION
import com.loosecannon.servicetag.ui.maintenance.MaintenanceSectionTitle
import com.loosecannon.servicetag.ui.maintenance.SCHEDULES_SECTION

/**
 * The asset's **own** schedules, with their status words (spec §2.6, #55's navigation minimum).
 *
 * `Plan decision:` this section lists **asset-targeted schedules only** (master plan decision 38).
 * A group-targeted schedule the asset is a required member of is real work on this asset, but it is
 * **one** obligation, and a group row counts once (D-15) — listing it here as well as in the groups
 * section would make one obligation look like two on one screen. The scan sheet is the surface that
 * deliberately merges both, for the standing-at-the-equipment case.
 *
 * The shipped "No schedule yet" is the empty line, which is exactly what it was written for: before
 * 1.2 it stood alone above the actions, saying the same true thing about the same asset.
 *
 * [onAddSchedule] is **B14's** create entry, carried here because B14's note asks B15 to: it stood
 * beside the line this section replaced, and without it there is no way to make a schedule at all —
 * the ratified empty state ("Add one from an asset or a maintenance group") sends the owner to
 * exactly this screen. It stays a **glyph** labelled with the ratified section word, because §17
 * ratifies no wording for the action and no brief invents one. What the entry opens, and every rule
 * about what it may write, stays B14's.
 */
@Composable
fun AssetSchedulesSection(
    schedules: List<DueItem>,
    onOpenSchedule: (String) -> Unit,
    onAddSchedule: () -> Unit,
) {
    MaintenanceSectionTitle(
        title = SCHEDULES_SECTION,
        trailing = {
            IconButton(onClick = onAddSchedule) {
                Icon(Icons.Outlined.Add, contentDescription = SCHEDULES_SECTION)
            }
        },
    )
    if (schedules.isEmpty()) {
        QuietLine("No schedule yet", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        return
    }
    schedules.forEachIndexed { index, item ->
        if (index > 0) {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
        DueItemRow(item = item, onClick = { onOpenSchedule(item.scheduleId.value) })
    }
}

/**
 * The groups this asset belongs to — #55's asset → group direction, the other half of the pair the
 * group screen's member rows make.
 *
 * **Open windows only.** `GroupRepository.forAsset` answers exactly that question, and it is the
 * right one: a closed window is history, and listing it here would make a removed asset look like a
 * current member. A group's schedules and their rounds are on the group's own screen, which is
 * where the group's work is counted once.
 *
 * Omitted entirely when the asset is in no group: §17 ratifies no line for "this asset is in no
 * maintenance group", and a heading over nothing is worse than no heading. Reported to the
 * controller as the one empty state this brief has no ratified sentence for.
 */
@Composable
fun AssetGroupsSection(groups: List<AssetGroupRow>, onOpenGroup: (String) -> Unit) {
    if (groups.isEmpty()) return
    MaintenanceSectionTitle(GROUPS_SECTION)
    groups.forEachIndexed { index, group ->
        if (index > 0) {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenGroup(group.id.value) }
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 11.dp),
        ) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Two sections, in the order the screen draws them: what is scheduled, then who it is shared with. */
@Composable
fun AssetMaintenanceSections(
    schedules: List<DueItem>,
    groups: List<AssetGroupRow>,
    onOpenSchedule: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
    onAddSchedule: () -> Unit,
) {
    Column {
        AssetSchedulesSection(
            schedules = schedules,
            onOpenSchedule = onOpenSchedule,
            onAddSchedule = onAddSchedule,
        )
        AssetGroupsSection(groups = groups, onOpenGroup = onOpenGroup)
    }
}
