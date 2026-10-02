package com.loosecannon.servicetag.ui.installed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.components.StatusBadge
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme

/** One depth step of the tree (C25). */
private val INDENT_STEP = 16.dp

/**
 * #47 (C25, C27; R47-16) — the asset's Installed components: what is fitted in it now, as a tree, and the removed rows
 * nothing replaced behind one toggle. It sits in the asset detail's padded column below Supplies and above the child
 * assets.
 *
 * The header reads P47-1 and, when writes are offered, carries the add glyph labelled P47-3, which installs at the top
 * level (the maintenance sections' glyph rule). A row is the component's name and its quiet line; a nested row is
 * indented 16 dp per depth and says P47-5 to TalkBack. Below the tree, when any exist, P47-18 opens the removed rows.
 *
 * The wrapper owns the ViewModel; [InstalledComponentsList] draws, so a device test can render it with no store behind
 * it. [snackbars] and [onOpenSupply] are the row and write sheets' (a refused write's line, a tapped SupplyItem). A held
 * asset ([readOnly], #77) draws its rows and its toggle, and nothing that writes.
 */
@Composable
fun InstalledComponentsSection(
    assetId: AssetId,
    graph: AppGraph,
    snackbars: SnackbarHostState,
    onOpenSupply: (supplyId: String) -> Unit,
    readOnly: Boolean = false,
) {
    val model: InstalledComponentsSectionViewModel = viewModel(key = "asset-installed-${assetId.value}") {
        InstalledComponentsSectionViewModel(graph, assetId)
    }
    val state by model.state.collectAsStateWithLifecycle()

    LaunchedEffect(model, readOnly) { model.setReadOnly(readOnly) }

    InstalledComponentsList(
        rows = state.rows,
        removed = state.removed,
        showRemoved = state.showRemoved,
        readOnly = readOnly || !state.offersWrites,
        // The row sheet and the install sheet are the next step's (B6b); until then a tap opens nothing.
        onOpen = {},
        onInstall = {},
        onToggleRemoved = model::toggleRemoved,
    )
}

/**
 * The drawn half: the header and its glyph, P47-2 when nothing is fitted, the current rows indented by depth, and,
 * when any removed row exists, the P47-18 toggle with those rows under it while it is open.
 */
@Composable
internal fun InstalledComponentsList(
    rows: List<InstalledComponentRowState>,
    removed: List<InstalledComponentRowState>,
    showRemoved: Boolean,
    readOnly: Boolean,
    onOpen: (InstalledComponentRowState) -> Unit,
    onInstall: () -> Unit,
    onToggleRemoved: () -> Unit,
) {
    SectionHeader(
        title = INSTALLED_COMPONENTS_SECTION,
        trailing = if (readOnly) null else {
            @Composable {
                IconButton(onClick = onInstall) {
                    Icon(Icons.Outlined.Add, contentDescription = INSTALL_COMPONENT)
                }
            }
        },
    )
    Column {
        if (rows.isEmpty()) QuietLine(NO_INSTALLED_COMPONENTS)
        rows.forEach { row -> InstalledComponentRow(row = row, onOpen = { onOpen(row) }) }
        if (removed.isNotEmpty()) {
            TextButton(onClick = onToggleRemoved) { Text(removedCount(removed.size)) }
            if (showRemoved) {
                removed.forEach { row -> InstalledComponentRow(row = row, onOpen = { onOpen(row) }) }
            }
        }
    }
}

/**
 * The name, the shipped "Archived" badge while the direct SupplyItem is archived, and the quiet line when it says
 * anything. A nested row starts with its indent, which carries P47-5 as the row's content description.
 */
@Composable
private fun InstalledComponentRow(row: InstalledComponentRowState, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .heightIn(min = 56.dp)
            .padding(vertical = 6.dp),
    ) {
        if (row.depth > 0) {
            val inside = row.inside
            Spacer(
                Modifier
                    .width(INDENT_STEP * row.depth)
                    .then(if (inside == null) Modifier else Modifier.semantics { contentDescription = inside }),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.quiet.isNotEmpty()) QuietLine(row.quiet)
        }
        if (row.archived) {
            StatusBadge(label = "Archived", colors = ServiceTagTheme.semanticColors.seasonInactive)
        }
    }
}
