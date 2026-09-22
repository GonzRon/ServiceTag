package com.loosecannon.servicetag.ui.maintenance

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.ui.components.StatusBadge
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme

/** The RATIFIED noun (master plan §17): the one word these screens are allowed to call a group. */
const val MAINTENANCE_GROUP = "Maintenance group"

/**
 * The group list, inside the Maintenance destination's "Maintenance groups" section (#55's
 * navigation minimum, master plan §11).
 *
 * **Every group is here, archived ones included and visibly distinguished** (plan decision 39).
 * Spec §2.3's "archiving hides it and its schedules" is applied where it belongs — the projection
 * drops an archived group's schedules, so they are in no due total and no dashboard section — but
 * the group itself stays findable, because #55 requires an archived group to keep its maintenance
 * history and history nobody can open is not kept. 1.2 has no filter UI to hide it behind.
 *
 * A row says the group's name and nothing else. A member count would be a second user-visible
 * string this screen has no ratification for, and the detail it opens says the rest.
 *
 * **A name is a label, never identity** (invariant 7): rows are keyed and opened by id, two groups
 * may share a name, and nothing here resolves one by it.
 */
@Composable
fun GroupList(
    groups: List<MaintenanceGroupRow>,
    onOpenGroup: (String) -> Unit,
    onNewGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        groups.forEachIndexed { index, group ->
            if (index > 0) {
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
            GroupRow(group = group, onClick = { onOpenGroup(group.id.value) })
        }
        // The "+" carries the verb and the RATIFIED noun carries the rest: §17 ratifies the noun
        // for these screens and no sentence, so the affordance is named with the word it was given.
        // 4dp on top of the button's own 12dp content inset puts the label on the rows' 16dp gutter.
        TextButton(onClick = onNewGroup, shape = ControlShape, modifier = Modifier.padding(start = 4.dp)) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(MAINTENANCE_GROUP)
        }
    }
}

/**
 * One group: its name, and for an archived one the shipped "Archived" badge the definition and
 * profile editors already use for the same state — the distinction decision 39 asks for, in a word
 * and a treatment rather than in colour alone.
 */
@Composable
private fun GroupRow(group: MaintenanceGroupRow, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(
            text = group.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (group.archived) {
            StatusBadge(label = "Archived", colors = ServiceTagTheme.semanticColors.seasonInactive)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
