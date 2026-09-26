package com.loosecannon.servicetag.ui.asset

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.journal.CategoryChoice
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
 *
 * Under it, #73's one row of controls — Type, Components, Archived ([AssetsFilterRow]) — narrows
 * what the box searches. An empty list always says why ([EmptyReason], the view model's decision).
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
            AssetsFilterRow(
                filters = state.filters,
                typeLabel = state.typeLabel,
                typeChoices = state.typeChoices,
                onPickType = model::pickType,
                onToggleComponents = model::toggleComponents,
                onToggleArchived = model::toggleArchived,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (state.emptyReason == EmptyReason.NONE) {
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
            } else {
                EmptyList(
                    reason = state.emptyReason,
                    archivedCount = state.archivedCount,
                    onNewAsset = onNewAsset,
                    onShowArchived = model::toggleArchived,
                )
            }
        }
    }
}

/**
 * #73's controls (C6), directly under the search box: the Type menu, then Components, then Archived.
 * A `FlowRow` of compact chips, each as wide as its label: at 380dp of content width and font scale
 * 1.0 the three share one line, and a longer chosen type or a larger font wraps the row onto more
 * lines rather than cutting a label (the #68 rule). Internal so the layout tests can draw it alone.
 */
@Composable
internal fun AssetsFilterRow(
    filters: AssetFilters,
    typeLabel: String?,
    typeChoices: List<CategoryChoice>,
    onPickType: (String?) -> Unit,
    onToggleComponents: () -> Unit,
    onToggleArchived: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        TypeChip(label = typeLabel, choices = typeChoices, onPick = onPickType)
        FilterChip(
            selected = filters.showComponents,
            onClick = onToggleComponents,
            label = { Text(COMPONENTS) },
            shape = ControlShape,
        )
        FilterChip(
            selected = filters.showArchived,
            onClick = onToggleArchived,
            label = { Text(ARCHIVED) },
            shape = ControlShape,
        )
    }
}

/**
 * The Type control (C6, R73-3): an `AssistChip`, the one Material 3 chip with a trailing slot and no
 * selection semantics — a `FilterChip` would read to TalkBack as a checkbox, checked or not, and
 * this opens a menu. Its accessible name is always [TYPE] and its state the choice, so the chosen
 * value is never hidden behind the name; the outer `role` overrides the chip's own `Role.Button`.
 * The menu is All, then the catalog in its own order, with no mark on the chosen row (the
 * dashboard's shape). A chosen type draws in the selected chips' container, with no outline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeChip(label: String?, choices: List<CategoryChoice>, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(label ?: TYPE) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            shape = ControlShape,
            colors = if (label != null) {
                AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    trailingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            } else {
                AssistChipDefaults.assistChipColors()
            },
            border = if (label != null) {
                AssistChipDefaults.assistChipBorder(enabled = true, borderColor = Color.Transparent)
            } else {
                AssistChipDefaults.assistChipBorder(enabled = true)
            },
            modifier = Modifier.semantics {
                role = Role.DropdownList
                contentDescription = TYPE
                stateDescription = label ?: ALL_TYPES
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(ALL_TYPES) },
                onClick = {
                    open = false
                    onPick(null)
                },
            )
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.display) },
                    onClick = {
                        open = false
                        onPick(choice.key)
                    },
                )
            }
        }
    }
}

/**
 * The empty list's sentence, one per [EmptyReason] (C5), the screen computing none of it. A blank
 * query with nothing at all, or nothing active, still offers the way forward (F1): adding an asset,
 * and turning the archived rows back on. Every other reason is one quiet line naming the control in
 * the way (owner ruling §18.23, kept whole by R73-7), or saying honestly that nothing matched.
 */
@Composable
private fun EmptyList(
    reason: EmptyReason,
    archivedCount: Int,
    onNewAsset: () -> Unit,
    onShowArchived: () -> Unit,
) {
    val line = when (reason) {
        EmptyReason.NONE -> return
        EmptyReason.NO_ASSETS -> "No assets yet"
        EmptyReason.NO_ACTIVE_ASSETS -> "No active assets · $archivedCount archived"
        EmptyReason.ONLY_COMPONENTS -> ONLY_COMPONENTS_LINE
        EmptyReason.NOTHING_MATCHES -> "Nothing matches that."
        EmptyReason.TYPE_HIDDEN -> TYPE_HIDDEN_LINE
        EmptyReason.COMPONENTS_HIDDEN -> COMPONENTS_HIDDEN_LINE
        EmptyReason.ARCHIVED_HIDDEN -> ARCHIVED_HIDDEN_LINE
        EmptyReason.BOTH_HIDDEN -> BOTH_HIDDEN_LINE
    }
    if (reason != EmptyReason.NO_ASSETS && reason != EmptyReason.NO_ACTIVE_ASSETS) {
        QuietLine(text = line, modifier = Modifier.padding(16.dp))
        return
    }
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QuietLine(line)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onNewAsset, shape = ControlShape) { Text("Add asset") }
            if (reason == EmptyReason.NO_ACTIVE_ASSETS) {
                OutlinedButton(onClick = onShowArchived, shape = ControlShape) {
                    Text("Show archived")
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
    AssetStatus.ARCHIVED -> ARCHIVED
}

/** The two badge words spec §7 and §6 fix, shared by the list row and the identity plate. */
internal const val RETIRED = "Retired"
internal const val OUT_OF_SEASON = "Out of season"

// #73's words (plan §6, ratified 2026-09-26). Each literal sits on one line of its own.

/** P73-1: the Type chip's label while it is All, and its accessible name always. */
private const val TYPE = "Type"

/** P73-2: the Type menu's first row, and the chip's state while it is All. */
private const val ALL_TYPES = "All"

/** P73-3: the Components chip. */
private const val COMPONENTS = "Components"

/** P73-4: the Archived chip, and the word the list's badge draws (upper-cased) on an archived row. */
private const val ARCHIVED = "Archived"

/** P73-5: every match of the chosen type is hidden by Archived alone (the §18.23 hint, reworded). */
private const val ARCHIVED_HIDDEN_LINE = "Matching assets are archived. Turn on Archived to see them."

/** P73-6: every match of the chosen type is hidden by Components alone. */
private const val COMPONENTS_HIDDEN_LINE = "Matching assets are components. Turn on Components to see them."

/** P73-7: the matches are hidden by both controls, together or one row each. */
private const val BOTH_HIDDEN_LINE = "Matching assets are hidden. Turn on Components and Archived to see them."

/** P73-9: a blank query, Type All, and every asset Archived admits is a component. */
private const val ONLY_COMPONENTS_LINE = "Only components here. Turn on Components to see them."

/** P73-10: the query has matches, all of another type. */
private const val TYPE_HIDDEN_LINE = "Matching assets have another type. Set Type to All."
