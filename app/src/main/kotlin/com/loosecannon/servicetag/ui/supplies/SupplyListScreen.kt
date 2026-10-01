package com.loosecannon.servicetag.ui.supplies

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.StatusBadge
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme

/**
 * The Supplies list (#15, C30), reached from the Maintenance shell's fifth row (C29), on `GroupList`'s shape.
 *
 * **Every SupplyItem is here, archived ones included and visibly distinguished** (R15-6): an archived item keeps
 * its place in the name order and draws the shipped "Archived" badge, because it stays on its assets and its
 * linked lines and a SupplyItem the owner cannot find is one whose uses cannot be read.
 *
 * A row says the name and, when either is there, "manufacturer · part number" as a quiet second line; the detail
 * it opens says the rest. **A name is a label, never identity**: rows are keyed and opened by id, and two items may
 * share a name.
 *
 * The add button reads P15-2 (the `MAINTENANCE_GROUP` precedent) and opens the editor; an empty catalog says P15-3
 * above it. No control to move a row, no filter, no search (R15-8).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplyListScreen(
    graph: AppGraph,
    onBack: () -> Unit,
    onOpenSupply: (String) -> Unit,
    onNewSupply: () -> Unit,
) {
    val model: SupplyListViewModel = viewModel { SupplyListViewModel(graph) }
    val rows by model.rows.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(SUPPLIES_SECTION) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        // Nothing to draw until the store has answered once; the title and the way back are enough.
        val current = rows ?: return@Scaffold
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            if (current.isEmpty()) {
                QuietLine(NO_SUPPLIES_YET, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            current.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
                SupplyRow(row = row, onClick = { onOpenSupply(row.id.value) })
            }
            // The "+" carries the verb and the RATIFIED noun carries the rest, as the group list's add button does.
            // 4dp on top of the button's own 12dp content inset puts the label on the rows' 16dp gutter.
            TextButton(onClick = onNewSupply, shape = ControlShape, modifier = Modifier.padding(start = 4.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(SUPPLY_ITEM)
            }
        }
    }
}

/**
 * One SupplyItem: its name, the quiet identity line when there is one, and for an archived item the shipped
 * "Archived" badge — the distinction R15-6 asks for, in a word and a treatment rather than in colour alone.
 */
@Composable
private fun SupplyRow(row: SupplyListRow, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (row.detail.isNotEmpty()) {
                Text(
                    text = row.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (row.archived) {
            StatusBadge(label = "Archived", colors = ServiceTagTheme.semanticColors.seasonInactive)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
