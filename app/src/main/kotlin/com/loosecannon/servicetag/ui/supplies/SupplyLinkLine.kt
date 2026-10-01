package com.loosecannon.servicetag.ui.supplies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.ui.components.StatusBadge
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme

/**
 * #15 (C34, C35): the line under one Materials row that says which SupplyItem the row is linked to — shared by the
 * quick-action editor and the event form, stateless, and deciding nothing about the row's words.
 *
 * - **Unlinked** ([supplyId] null): [LINK_SUPPLY] (P15-21) when the host passes [onLink] — the quick-action editor,
 *   which opens the picker — and nothing at all when it does not: the event form shows and removes a link, never
 *   makes one (R15-8).
 * - **Linked**: "Linked to {name}" (P15-22), the shipped "Archived" badge when that SupplyItem is archived (R15-6),
 *   and [REMOVE_LINK] (P15-23), which reports [onUnlink]; the host clears the link and nothing else.
 * - **Linked to an item [supplies] does not hold**: no line. The link is kept — the row still sends it — and the save
 *   decides (C20).
 *
 * [supplies] is every SupplyItem the host has read, archived included, by id: the names a linked row can draw.
 */
@Composable
internal fun SupplyLinkLine(
    supplyId: SupplyId?,
    supplies: Map<SupplyId, SupplyListRow>,
    onUnlink: () -> Unit,
    onLink: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (supplyId == null) {
        if (onLink != null) {
            TextButton(onClick = onLink, modifier = modifier) { Text(LINK_SUPPLY) }
        }
        return
    }
    val item = supplies[supplyId] ?: return
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = LINKED_TO.format(item.name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (item.archived) {
            StatusBadge(label = "Archived", colors = ServiceTagTheme.semanticColors.seasonInactive)
        }
        TextButton(onClick = onUnlink) { Text(REMOVE_LINK) }
    }
}
