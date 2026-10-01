package com.loosecannon.servicetag.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.LedgerList
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.components.StatusBadge
import com.loosecannon.servicetag.ui.supplies.SupplyItemPickerSheet
import com.loosecannon.servicetag.ui.supplies.SupplyLinkLine
import com.loosecannon.servicetag.ui.supplies.SupplyListRow
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.Eyebrow
import com.loosecannon.servicetag.ui.theme.MonoText
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme

/**
 * One quick action of an asset, new ([profileId] null) or edited (spec §9). The name, the kind of
 * entry it logs and the title it suggests sit above two lists: the readings it puts on the form, in
 * the order it puts them, and the materials it offers as suggestions.
 *
 * A field is picked, never typed — the picker offers the asset's unarchived entered readings that
 * are not already on the action. One already on it whose reading has since been archived keeps its
 * place and wears the badge instead, because dropping it would rewrite someone's action behind
 * their back (spec §6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    graph: AppGraph,
    assetId: String,
    profileId: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val model: ProfileEditViewModel = viewModel(key = profileId ?: "new-profile-$assetId") {
        ProfileEditViewModel(graph, assetId, profileId)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }

    LaunchedEffect(model) { model.messages.collect { snackbars.showSnackbar(it) } }

    // A save and a delete both end the screen; one latch, so two pops never take the asset with it.
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(model) {
        model.saved.collect {
            if (!leaving) {
                leaving = true
                onDone()
            }
        }
    }
    LaunchedEffect(model) {
        model.deleted.collect {
            if (!leaving) {
                leaving = true
                onDone()
            }
        }
    }

    var confirming by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    // #15 (C34): the Materials row whose link control (P15-21) opened the SupplyItem picker; null while it is closed.
    var linking by remember { mutableStateOf<Int?>(null) }

    // The entry form's app bar shape (D12 §3): what this screen is, then what it is about.
    val eyebrow = listOf(
        if (state.editing) "EDIT ACTION" else "NEW ACTION",
        state.assetName.uppercase(),
    ).filter { it.isNotBlank() }.joinToString(" · ")

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = eyebrow,
                        style = Eyebrow,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    TextButton(onClick = model::save, enabled = !state.saving) { Text("Save") }
                    if (state.editing) {
                        ProfileOverflow(
                            archived = state.archived,
                            onArchive = { model.archive(!state.archived) },
                            onDelete = { confirming = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (!state.loaded) {
            QuietLine("Loading…", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }

        if (confirming) {
            ConfirmDialog(
                title = "Delete this action?",
                body = "Past entries keep their readings.",
                onDismiss = { confirming = false },
                onConfirm = {
                    confirming = false
                    model.delete()
                },
            )
        }
        if (picking) {
            FieldPicker(
                available = state.available,
                onDismiss = { picking = false },
                onPick = {
                    picking = false
                    model.addField(it)
                },
            )
        }
        linking?.let { index ->
            SupplyItemPickerSheet(
                rows = state.supplyChoices,
                onPick = { picked ->
                    linking = null
                    model.linkSupply(index, picked.id)
                },
                onDismiss = { linking = null },
            )
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = state.name,
                onValueChange = model::onName,
                label = { Text("Name") },
                isError = state.problems.containsKey(ProfileForm.NAME),
                supportingText = state.problems[ProfileForm.NAME]?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            )
            KindPicker(selected = state.eventKind, onSelect = model::onKind)
            OutlinedTextField(
                value = state.defaultTitle,
                onValueChange = model::onTitle,
                label = { Text("Default title") },
                // Until it is typed in, this field shows the name: the entry it logs is titled
                // after the action unless someone wants it titled otherwise.
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            )

            Column {
                SectionHeader(title = "Fields")
                state.problems[ProfileForm.FIELDS]?.let { Problem(it) }
                if (state.fields.isEmpty()) {
                    QuietLine("No fields yet · this action just logs an entry")
                } else {
                    LedgerList(count = state.fields.size) { index ->
                        val pick = state.fields[index]
                        FieldRow(
                            pick = pick,
                            canMoveUp = index > 0,
                            canMoveDown = index < state.fields.lastIndex,
                            onRequired = { model.setRequired(pick.definition.id, it) },
                            onMove = { delta -> model.moveField(index, delta) },
                            onRemove = { model.removeField(pick.definition.id) },
                        )
                    }
                }
                AddRowButton(
                    text = "Add field",
                    enabled = state.available.isNotEmpty(),
                    onClick = { picking = true },
                )
                if (state.available.isEmpty()) {
                    Hint("Every entered reading of this asset is already on this action.")
                }
            }

            Column {
                SectionHeader(title = "Materials")
                if (state.consumables.isEmpty()) {
                    QuietLine("No materials suggested · the entry form still takes any")
                }
                state.consumables.forEachIndexed { index, row ->
                    ConsumableRowEditor(
                        row = row,
                        problem = state.problems[ProfileForm.consumable(index)],
                        supplies = state.supplies,
                        onChange = { name, quantity, unit ->
                            model.onConsumable(index, name, quantity, unit)
                        },
                        onRemove = { model.removeConsumable(index) },
                        onLink = { linking = index },
                        onUnlink = { model.unlinkSupply(index) },
                    )
                }
                AddRowButton(text = "Add material", enabled = true, onClick = model::addConsumable)
            }

            Button(
                onClick = model::save,
                enabled = !state.saving,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save action")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** What kind of entry this action logs. A closed set of ten, so a menu rather than a segmented row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindPicker(selected: EventKind, onSelect: (EventKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = kindLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text("Kind") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            shape = ControlShape,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            EventKind.entries.forEach { kind ->
                DropdownMenuItem(
                    text = { Text(kindLabel(kind)) },
                    onClick = {
                        open = false
                        onSelect(kind)
                    },
                )
            }
        }
    }
}

/**
 * One field of the action: the reading it asks for, whether it is required, and where it sits. The
 * move lives in the row's own overflow for the same reason it does on the setup screen — a drag
 * handle inside a scrolling column is a gesture fight.
 */
@Composable
private fun FieldRow(
    pick: FieldPick,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRequired: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = pick.definition.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (pick.definition.archivedAt != null) {
                    StatusBadge(
                        label = "Archived",
                        colors = ServiceTagTheme.semanticColors.seasonInactive,
                    )
                }
            }
            Text(
                text = listOfNotNull(
                    pick.definition.unit.takeIf { it.isNotBlank() },
                    if (pick.required) "Required" else "Optional",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = pick.required, onCheckedChange = onRequired)
        FieldOverflow(
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onMove = onMove,
            onRemove = onRemove,
        )
    }
}

/**
 * A material this action suggests: what it is, how much of it, and in what (spec §9). Under it, the row's
 * SupplyItem link (#15, C34): the link control while it has none (P15-21), else what it is linked to and the remove
 * action (P15-22, P15-23).
 */
@Composable
private fun ConsumableRowEditor(
    row: ConsumableEdit,
    problem: String?,
    supplies: Map<SupplyId, SupplyListRow>,
    onChange: (String?, String?, String?) -> Unit,
    onRemove: () -> Unit,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
) {
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            OutlinedTextField(
                value = row.name,
                onValueChange = { onChange(it, null, null) },
                label = { Text("Material") },
                singleLine = true,
                isError = problem != null,
                shape = ControlShape,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = row.quantity,
                onValueChange = { onChange(null, it, null) },
                label = { Text("Qty") },
                singleLine = true,
                isError = problem != null,
                textStyle = MonoText,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = ControlShape,
                modifier = Modifier.width(78.dp),
            )
            OutlinedTextField(
                value = row.unit,
                onValueChange = { onChange(null, null, it) },
                label = { Text("Unit") },
                singleLine = true,
                shape = ControlShape,
                modifier = Modifier.width(74.dp),
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Close, contentDescription = "Remove material")
            }
        }
        SupplyLinkLine(supplyId = row.supplyId, supplies = supplies, onUnlink = onUnlink, onLink = onLink)
        problem?.let { Problem(it) }
    }
}

/** The readings this action could still ask for. A list, because a reading is chosen, never typed. */
@Composable
private fun FieldPicker(
    available: List<MeasurementDefinition>,
    onDismiss: () -> Unit,
    onPick: (DefinitionId) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a field") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                available.forEach { definition ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(definition.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = definition.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        definition.unit.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FieldOverflow(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text("Move up") },
            enabled = canMoveUp,
            onClick = { open = false; onMove(-1) },
        )
        DropdownMenuItem(
            text = { Text("Move down") },
            enabled = canMoveDown,
            onClick = { open = false; onMove(1) },
        )
        DropdownMenuItem(text = { Text("Remove") }, onClick = { open = false; onRemove() })
    }
}

@Composable
private fun ProfileOverflow(archived: Boolean, onArchive: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(if (archived) "Unarchive" else "Archive") },
            onClick = { open = false; onArchive() },
        )
        DropdownMenuItem(text = { Text("Delete") }, onClick = { open = false; onDelete() })
    }
}

/** The one way to add a row in either section: outlined, full width, no FAB (D12 §7). */
@Composable
private fun AddRowButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = ControlShape,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text = text, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun Problem(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = ServiceTagTheme.semanticColors.due.foreground,
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
