package com.loosecannon.servicetag.ui.supplies

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.journal.CategoryKey
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.attachments.ROLE_HEADER
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.components.StatusBadge
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme

/**
 * #15 (C33; R15-3, R15-8) — the asset's Supplies: which SupplyItems this asset takes, in which role. The first section
 * of the asset detail's padded column, directly above the child-asset section.
 *
 * The header reads P15-1 with no subtitle and, when writes are offered, carries the add glyph labelled P15-13 (the
 * maintenance sections' glyph rule). A row opens the SupplyItem's own detail through [onOpenSupply]; its overflow
 * offers P15-17 and "Remove". Add is the picker (C32), then the role sheet; Remove is immediate, with no dialog.
 *
 * The wrapper owns the ViewModel, the picker and the sheet; [AssetSuppliesList] draws, so a device test can render it
 * with no store behind it. A held asset ([readOnly], #77) draws its rows and nothing that writes.
 */
@Composable
fun AssetSuppliesSection(
    assetId: AssetId,
    graph: AppGraph,
    snackbars: SnackbarHostState,
    onOpenSupply: (supplyId: String) -> Unit,
    readOnly: Boolean = false,
) {
    val model: AssetSuppliesSectionViewModel = viewModel(key = "asset-supplies-${assetId.value}") {
        AssetSuppliesSectionViewModel(graph, assetId)
    }
    val state by model.state.collectAsStateWithLifecycle()

    LaunchedEffect(model, readOnly) { model.setReadOnly(readOnly) }
    LaunchedEffect(model) { model.messages.collect { snackbars.showSnackbar(it) } }

    AssetSuppliesList(
        rows = state.rows,
        readOnly = readOnly || !state.offersWrites,
        onOpen = { onOpenSupply(it.supplyId.value) },
        onAdd = model::startAdd,
        onEditRole = model::startEdit,
        onRemove = { model.remove(it.id) },
    )
    if (state.picking) {
        SupplyItemPickerSheet(rows = state.choices, onPick = model::pick, onDismiss = model::dismissPicker)
    }
    state.sheet?.let { sheet ->
        AssetSupplyRoleSheet(
            sheet = sheet,
            suggestions = state.suggestions,
            onRole = model::onRole,
            onSave = model::save,
            onDismiss = model::dismissSheet,
        )
    }
}

/** The drawn half: the header and its glyph, P15-14 when the asset takes nothing, and a row per applicability row. */
@Composable
internal fun AssetSuppliesList(
    rows: List<AssetSupplyRowState>,
    readOnly: Boolean,
    onOpen: (AssetSupplyRowState) -> Unit,
    onAdd: () -> Unit,
    onEditRole: (AssetSupplyRowState) -> Unit,
    onRemove: (AssetSupplyRowState) -> Unit,
) {
    SectionHeader(
        title = SUPPLIES_SECTION,
        trailing = if (readOnly) null else {
            @Composable {
                IconButton(onClick = onAdd) {
                    Icon(Icons.Outlined.Add, contentDescription = ADD_SUPPLY)
                }
            }
        },
    )
    Column {
        if (rows.isEmpty()) QuietLine(NO_SUPPLIES)
        rows.forEach { row ->
            AssetSupplyRow(
                row = row,
                readOnly = readOnly,
                onOpen = { onOpen(row) },
                onEditRole = { onEditRole(row) },
                onRemove = { onRemove(row) },
            )
        }
    }
}

/** The item's name with the shipped "Archived" badge when it is archived, the role as a quiet line, and the overflow. */
@Composable
private fun AssetSupplyRow(
    row: AssetSupplyRowState,
    readOnly: Boolean,
    onOpen: () -> Unit,
    onEditRole: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .heightIn(min = 56.dp)
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            QuietLine(row.role)
        }
        if (row.archived) {
            StatusBadge(label = "Archived", colors = ServiceTagTheme.semanticColors.seasonInactive)
        }
        if (!readOnly) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(EDIT_ROLE) }, onClick = { menu = false; onEditRole() })
                    DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}

/**
 * The role sheet (C33): P15-13 when adding, P15-17 when re-roling, the SupplyItem's name under the title, the "Role"
 * field (free text), the one sentence a refusal draws under it, the roles in use as chips that fill the field, and
 * Cancel / Save. Save is enabled while the cleaned role says something. Fully expanded and scrolling, the reference
 * sheets' shape, so Save stays reachable at a large text size.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssetSupplyRoleSheet(
    sheet: RoleSheetState,
    suggestions: List<String>,
    onRole: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(sheet.title, style = MaterialTheme.typography.titleMedium)
            Text(sheet.supplyName, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = sheet.role,
                onValueChange = onRole,
                label = { Text(ROLE_HEADER) },
                singleLine = true,
                isError = sheet.problem != null,
                modifier = Modifier.fillMaxWidth(),
            )
            sheet.problem?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = ServiceTagTheme.semanticColors.due.foreground,
                )
            }
            if (suggestions.isNotEmpty()) {
                val typed = CategoryKey.display(sheet.role)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    suggestions.forEach { suggestion ->
                        FilterChip(
                            selected = typed == suggestion,
                            onClick = { onRole(suggestion) },
                            label = { Text(suggestion) },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = onSave, enabled = sheet.canSave) { Text("Save") }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
