package com.loosecannon.servicetag.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.DerivedFormula
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.l10n.localizedPlural
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.Eyebrow
import com.loosecannon.servicetag.ui.theme.MonoText
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme

/**
 * One reading of an asset, new ([definitionId] null) or edited (spec §9). Outlined fields on the
 * 6dp control corner, Save in the app bar and again at the bottom so it survives an open keyboard
 * (G1 §1.3), and every refused field named under itself.
 *
 * Three controls can be locked: once measurements exist, `key`, `kind` and `valueType` are how
 * those measurements are read back, so the form disables them and says how many entries fixed
 * them rather than letting the save be refused. Everything else about a reading in use — its
 * label, unit, target, decimals — stays editable, because none of it changes what was recorded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefinitionEditScreen(
    graph: AppGraph,
    assetId: String,
    definitionId: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val model: DefinitionEditViewModel = viewModel(key = definitionId ?: "new-$assetId") {
        DefinitionEditViewModel(graph, assetId, definitionId)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val refusal by model.refusal.collectAsStateWithLifecycle()
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
    val titleRes = if (state.editing) R.string.setup_definition_title_edit else R.string.setup_definition_title_new

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleRes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.setup_cancel))
                    }
                },
                actions = {
                    TextButton(onClick = model::save, enabled = !state.saving) { Text(stringResource(R.string.setup_save)) }
                    if (state.editing) {
                        EditorOverflow(
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
            QuietLine(stringResource(R.string.setup_loading), Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }

        refusal?.let { refused ->
            ReferencedDialog(
                refusal = refused,
                definitionLabels = state.definitionLabels,
                profileNames = state.profileNames,
                onDismiss = model::dismissRefusal,
            )
        }
        if (confirming) {
            ConfirmDialog(
                title = stringResource(R.string.setup_delete_reading_title),
                body = stringResource(R.string.setup_delete_reading_body),
                onDismiss = { confirming = false },
                onConfirm = {
                    confirming = false
                    model.delete()
                },
            )
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.label,
                onValueChange = model::onLabel,
                label = { Text(stringResource(R.string.setup_definition_label)) },
                isError = state.problems.containsKey(DefinitionField.LABEL),
                supportingText = state.problems[DefinitionField.LABEL]?.let { { Text(it) } },
                singleLine = true,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.key,
                onValueChange = model::onKey,
                label = { Text(stringResource(R.string.setup_definition_key)) },
                // The key is how a stored measurement and a backup name this reading, so it is
                // shown in the technical face and frozen the moment there is data behind it.
                enabled = state.inUse == 0,
                textStyle = MonoText,
                isError = state.problems.containsKey(DefinitionField.KEY),
                supportingText = (state.problems[DefinitionField.KEY] ?: state.frozenReason())
                    ?.let { { Text(it) } },
                singleLine = true,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            )

            FieldGroup(
                label = stringResource(R.string.setup_definition_kind),
                note = state.problems[DefinitionField.KIND] ?: state.frozenReason(),
                isProblem = state.problems.containsKey(DefinitionField.KIND),
            ) {
                Choices(
                    options = DefinitionKind.entries,
                    selected = state.kind,
                    enabled = state.inUse == 0,
                    label = { definitionKindLabel(it) },
                    onSelect = model::onKind,
                )
            }

            if (state.kind == DefinitionKind.ENTERED) {
                FieldGroup(
                    label = stringResource(R.string.setup_definition_type),
                    note = state.problems[DefinitionField.TYPE] ?: state.frozenReason(),
                    isProblem = state.problems.containsKey(DefinitionField.TYPE),
                ) {
                    Choices(
                        options = ValueType.entries,
                        selected = state.valueType,
                        enabled = state.inUse == 0,
                        label = { typeLabel(it) },
                        onSelect = model::onValueType,
                    )
                }
            } else {
                FieldGroup(label = stringResource(R.string.setup_definition_formula), note = null, isProblem = false) {
                    // One formula exists today (spec §11), so it is stated rather than chosen.
                    Text(
                        text = formulaName(state.formula),
                        style = MonoText,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                SourcePicker(
                    label = stringResource(R.string.setup_definition_source_a),
                    selected = state.sourceA,
                    sources = state.sources,
                    onSelect = model::onSourceA,
                )
                SourcePicker(
                    label = stringResource(R.string.setup_definition_source_b),
                    selected = state.sourceB,
                    sources = state.sources,
                    onSelect = model::onSourceB,
                )
                state.problems[DefinitionField.SOURCES]?.let { Problem(it) }
                if (state.sources.size < 2) {
                    Text(
                        text = stringResource(R.string.setup_definition_sources_needed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = state.unit,
                onValueChange = model::onUnit,
                label = { Text(stringResource(R.string.setup_definition_unit)) },
                singleLine = true,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            )

            // Decimals, a target and a meter are things only a number can be (spec §6) — a TEXT
            // or BOOLEAN reading has nothing to round, so the control is not shown at all.
            if (state.numeric) {
                OutlinedTextField(
                    value = state.decimals,
                    onValueChange = model::onDecimals,
                    label = { Text(stringResource(R.string.setup_definition_decimals)) },
                    isError = state.problems.containsKey(DefinitionField.DECIMALS),
                    supportingText = state.problems[DefinitionField.DECIMALS]?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    shape = ControlShape,
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldGroup(
                    label = stringResource(R.string.setup_definition_target),
                    note = state.problems[DefinitionField.RANGE_LOW]
                        ?: state.problems[DefinitionField.RANGE_HIGH],
                    isProblem = true,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = state.rangeLow,
                            onValueChange = model::onRangeLow,
                            label = { Text(stringResource(R.string.setup_definition_target_low)) },
                            isError = state.problems.containsKey(DefinitionField.RANGE_LOW),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Next,
                            ),
                            shape = ControlShape,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = state.rangeHigh,
                            onValueChange = model::onRangeHigh,
                            label = { Text(stringResource(R.string.setup_definition_target_high)) },
                            isError = state.problems.containsKey(DefinitionField.RANGE_HIGH),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Done,
                            ),
                            shape = ControlShape,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (state.kind == DefinitionKind.ENTERED) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.setup_definition_meter),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = state.isMeter, onCheckedChange = model::onMeter)
                    }
                    state.problems[DefinitionField.METER]?.let { Problem(it) }
                }
            }

            Button(
                onClick = model::save,
                enabled = !state.saving,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.setup_definition_save))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** True when the reading is a number — a DERIVED one always is (spec §4). */
private val DefinitionEditState.numeric: Boolean
    get() = kind == DefinitionKind.DERIVED || valueType == ValueType.NUMBER

/** What the locked controls say for themselves: how much data is holding them still. */
private fun DefinitionEditState.frozenReason(): String? = when (inUse) {
    0 -> null
    else -> localizedPlural(R.plurals.setup_definition_frozen, inUse, inUse)
}

/** A small all-caps label over a control that is not a text field, with its note underneath. */
@Composable
private fun FieldGroup(
    label: String,
    note: String?,
    isProblem: Boolean,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label.uppercase(),
            style = Eyebrow,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
        note?.let { if (isProblem) Problem(it) else Hint(it) }
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

/** A segmented control over a small closed set — the app's answer to a radio group (G1 §1.3). */
@Composable
private fun <T> Choices(
    options: List<T>,
    selected: T,
    enabled: Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(label(option))
            }
        }
    }
}

/**
 * One of the asset's entered number readings. Read-only field plus a menu rather than a free text
 * box: a source is a row that exists, so it is picked, never typed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcePicker(
    label: String,
    selected: DefinitionId?,
    sources: List<MeasurementDefinition>,
    onSelect: (DefinitionId) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val chosen = sources.firstOrNull { it.id == selected }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = chosen?.label ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text(stringResource(R.string.setup_definition_choose_source)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            shape = ControlShape,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sources.forEach { source ->
                DropdownMenuItem(
                    text = { Text(source.label) },
                    onClick = {
                        open = false
                        onSelect(source.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun EditorOverflow(archived: Boolean, onArchive: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.setup_more))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(if (archived) R.string.setup_unarchive else R.string.setup_archive)) },
            onClick = { open = false; onArchive() },
        )
        DropdownMenuItem(text = { Text(stringResource(R.string.setup_delete)) }, onClick = { open = false; onDelete() })
    }
}

private fun typeLabel(type: ValueType): String = localized(
    when (type) {
        ValueType.NUMBER -> R.string.setup_value_type_number
        ValueType.TEXT -> R.string.setup_value_type_text
        ValueType.BOOLEAN -> R.string.setup_value_type_boolean
    },
)

/** Entered or Derived, on the Kind control's two segments. */
private fun definitionKindLabel(kind: DefinitionKind): String = localized(
    when (kind) {
        DefinitionKind.ENTERED -> R.string.setup_definition_kind_entered
        DefinitionKind.DERIVED -> R.string.setup_definition_kind_derived
    },
)

/** The formula, named and written out, because "Percent drop" alone does not say from what. */
private fun formulaName(formula: DerivedFormula): String = localized(
    when (formula) {
        DerivedFormula.PERCENT_DROP -> R.string.setup_formula_percent_drop
    },
)
