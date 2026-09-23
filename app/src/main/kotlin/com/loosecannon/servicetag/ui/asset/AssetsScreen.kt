package com.loosecannon.servicetag.ui.asset

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.isRetired
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.ServiceTagIcons
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.StatusBadge
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme

/**
 * The asset list: one line per asset, active rows first and archived ones only when asked for
 * (archive is not delete — R-9 — but a list that keeps showing what you archived is no better
 * than never archiving). Rows are hairline-separated lines, not cards (D12 §7), and adding an
 * asset is an app-bar action: the one FAB this app allows belongs to the ledger (G1 §3 c).
 *
 * The search box (#39) moved here from the Dashboard by the owner's 2026-09-23 instruction: the
 * Dashboard's category and maintenance-status dropdowns now serve as its navigation aids, and the
 * quick filter belongs on the screen that lists every asset and component.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetsScreen(
    graph: AppGraph,
    onOpenAsset: (String) -> Unit,
    onNewAsset: () -> Unit,
) {
    val model: AssetsViewModel = viewModel { AssetsViewModel(graph) }
    val state by model.state.collectAsStateWithLifecycle()
    // The box draws itself from the view model's own query holder, not from `state.query` (F3):
    // the latter is a `combine`/`stateIn` round trip and a text field has to see its own keystroke
    // back in the same frame. `state.query` still decides what the list below says.
    val query by model.query.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assets") },
                actions = {
                    IconButton(onClick = onNewAsset) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add asset")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            SearchBox(
                query = query,
                onQueryChange = model::onQueryChange,
                onClear = model::clearQuery,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            FilterChip(
                selected = state.showArchived,
                onClick = model::toggleArchived,
                label = { Text("Show archived") },
                shape = ControlShape,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (state.items.isEmpty()) {
                if (state.query.isNotBlank() && !state.showArchived && state.archivedMatchCount > 0) {
                    // Owner ruling §18.23 (B07 fix round 4): a match exists, it is just behind the
                    // chip — a different fact from "nothing matches that", and the ratified hint
                    // says so instead of leaving the owner to conclude the asset is gone.
                    QuietLine(
                        text = "Matching assets are archived. Turn on Show archived to see them.",
                        modifier = Modifier.padding(16.dp),
                    )
                } else if (state.query.isNotBlank()) {
                    // Only ever an answer to something asked for (F1): a blank query has its own
                    // honest reasons for an empty list below, and neither of them is "found
                    // nothing" — nothing was searched for.
                    QuietLine(text = "Nothing matches that.", modifier = Modifier.padding(16.dp))
                } else {
                    // "Nothing here" and "nothing here because the chip is off" are different
                    // facts, and telling someone the first while the second is true is how they
                    // conclude their assets are gone. The offer to look is part of the sentence.
                    val onlyArchivedLeft = !state.showArchived && state.archivedCount > 0
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        QuietLine(
                            if (onlyArchivedLeft) {
                                "No active assets · ${state.archivedCount} archived"
                            } else {
                                "No assets yet"
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onNewAsset, shape = ControlShape) { Text("Add asset") }
                            if (onlyArchivedLeft) {
                                OutlinedButton(onClick = model::toggleArchived, shape = ControlShape) {
                                    Text("Show archived")
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn {
                    itemsIndexed(state.items, key = { _, row -> row.asset.id.value }) { index, row ->
                        if (index > 0) {
                            HorizontalDivider(
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        AssetListRow(row = row, onClick = { onOpenAsset(row.asset.id.value) })
                    }
                }
            }
        }
    }
}

/**
 * Name over category, then "Part of <parent>" when the asset is a component of another (spec §9).
 * The badges do the work colour alone must not (D12 §5), and an asset can carry more than one:
 * retired and out of season are different facts and neither implies the other.
 */
@Composable
private fun AssetListRow(row: AssetRow, onClick: () -> Unit) {
    val asset = row.asset
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = asset.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (asset.category.isNotBlank()) {
                Text(
                    text = asset.category,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            row.parentName?.let { parent ->
                Text(
                    text = "Part of $parent",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (asset.isRetired) {
            StatusBadge(
                label = RETIRED,
                colors = ServiceTagTheme.semanticColors.paused,
                icon = ServiceTagIcons.PauseCircle,
            )
        }
        if (row.outOfSeason) {
            StatusBadge(
                label = OUT_OF_SEASON,
                colors = ServiceTagTheme.semanticColors.seasonInactive,
                icon = ServiceTagIcons.CalendarMonth,
            )
        }
        statusLabel(asset.status)?.let { label ->
            StatusBadge(label = label, colors = ServiceTagTheme.semanticColors.seasonInactive)
        }
    }
}

/** An active asset says nothing; the other says what it is, in the neutral family. */
internal fun statusLabel(status: AssetStatus): String? = when (status) {
    AssetStatus.ACTIVE -> null
    AssetStatus.ARCHIVED -> "Archived"
}

/** The two badge words spec §7 and §6 fix, shared by the list row and the identity plate. */
internal const val RETIRED = "Retired"
internal const val OUT_OF_SEASON = "Out of season"
