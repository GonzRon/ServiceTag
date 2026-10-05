package com.loosecannon.servicetag.ui.installed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.R
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
 * The wrapper owns the ViewModel, the sheets (`InstalledComponentSheets.kt`) and the picker; [InstalledComponentsList]
 * draws, so a device test can render it with no store behind it. A row's tap opens its sheet; [onOpenSupply] opens a
 * SupplyItem from there, and [onOpenDocuments] the row's own screen (#69, C27, P69-1), each after the sheet closes, so
 * Back returns to the asset with no sheet stacked; [snackbars] carries the shipped transferred-out line when the asset
 * leaves mid-save. A held asset ([readOnly], #77) draws its rows, its toggle and a row's facts and history, and nothing
 * that writes.
 */
@Composable
fun InstalledComponentsSection(
    assetId: AssetId,
    graph: AppGraph,
    snackbars: SnackbarHostState,
    onOpenSupply: (supplyId: String) -> Unit,
    onOpenDocuments: (componentId: String) -> Unit,
    readOnly: Boolean = false,
) {
    val model: InstalledComponentsSectionViewModel = viewModel(key = "asset-installed-${assetId.value}") {
        InstalledComponentsSectionViewModel(graph, assetId)
    }
    val state by model.state.collectAsStateWithLifecycle()

    LaunchedEffect(model, readOnly) { model.setReadOnly(readOnly) }
    LaunchedEffect(model) { model.messages.collect { snackbars.showSnackbar(it) } }

    val offersWrites = !readOnly && state.offersWrites
    InstalledComponentsList(
        rows = state.rows,
        removed = state.removed,
        showRemoved = state.showRemoved,
        readOnly = !offersWrites,
        onOpen = { model.open(it.id) },
        onInstall = model::startInstall,
        onToggleRemoved = model::toggleRemoved,
    )
    state.rowSheet?.let { sheet ->
        InstalledComponentRowSheet(
            sheet = sheet,
            supplies = state.supplies,
            offersWrites = offersWrites,
            onOpenSupply = { model.closeRow(); onOpenSupply(it.value) },
            onInstallInside = { model.startInstallInside(sheet.id) },
            onReplace = { model.startReplace(sheet.id) },
            onRemove = { model.startRemove(sheet.id) },
            onEdit = { model.startEdit(sheet.id) },
            onDismiss = model::closeRow,
            onOpenDocuments = { model.closeRow(); onOpenDocuments(sheet.id.value) },
            composition = {
                CompositionLines(
                    entries = sheet.composition,
                    supplies = state.supplies,
                    onOpenSupply = { model.closeRow(); onOpenSupply(it.value) },
                )
            },
        )
    }
    state.form?.let { form ->
        // One sheet at a time: the form, or the picker in its place, its fields kept by the view model. The picker,
        // for the link or an entry, is handed the unarchived SupplyItems only (R47-3); the form's lines get every one (C-1).
        ComponentFormOrPicker(
            form = form,
            supplies = state.supplies,
            choices = state.choices,
            onName = model::onName,
            onLink = model::startLinkPick,
            onUnlink = model::unlink,
            onSerialOrLot = model::onSerialOrLot,
            onDate = model::onDate,
            onNotes = model::onNotes,
            onSave = model::save,
            onDismiss = model::dismissForm,
            onQuantity = model::onEntryQuantity,
            onUnit = model::onEntryUnit,
            onPickEntry = model::startEntryPick,
            onRemoveEntry = model::removeEntry,
            onAddEntry = model::startAddEntry,
            onPick = model::pick,
            onDismissPicker = model::dismissPicker,
        )
    }
    state.removing?.let { sheet ->
        RemoveComponentSheet(
            sheet = sheet,
            onRemovedOn = model::onRemovedOn,
            onRemove = model::confirmRemove,
            onDismiss = model::dismissRemove,
        )
    }
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
            .padding(vertical = 6.dp)
            // The indent takes the row's height: a zero-area node never reaches TalkBack, and it carries P47-5.
            .height(IntrinsicSize.Min),
    ) {
        if (row.depth > 0) {
            val inside = row.inside
            Spacer(
                Modifier
                    .width(INDENT_STEP * row.depth)
                    .fillMaxHeight()
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
            StatusBadge(label = stringResource(R.string.supplies_item_archived_badge), colors = ServiceTagTheme.semanticColors.seasonInactive)
        }
    }
}
