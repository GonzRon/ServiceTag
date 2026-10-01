package com.loosecannon.servicetag.ui.supplies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.asset.CATEGORY_FIELD
import com.loosecannon.servicetag.ui.asset.MANUFACTURER_FIELD
import com.loosecannon.servicetag.ui.asset.MODEL_FIELD
import com.loosecannon.servicetag.ui.attachments.NOTES_LABEL
import com.loosecannon.servicetag.ui.maintenance.MaintenanceSectionTitle
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme

/**
 * The SupplyItem form (#15, C31), on `GroupEditScreen`'s shape: the P15-2 title, "Cancel" and "Save"; the fields
 * Name, Category, Manufacturer, Model, P15-4, P15-5 and Notes; and **"Specifications"** (P15-6) as rows of "Label",
 * "Value" and "Unit", each with a close glyph labelled P15-8 (the Materials rows' shape), under them P15-12 after a
 * refused save, then the P15-7 row button.
 *
 * Save is disabled while the name is blank once trimmed, the one refusal this form could otherwise ask for, so no
 * sentence is drawn for it. The rows go in screen order with their loaded ids; **there is no key field and no
 * control to move a row** (R15-8, R15-11). An item no longer there draws P15-20. Cancel and Back write nothing.
 * Archiving is the detail's, not this form's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplyEditScreen(
    graph: AppGraph,
    supplyId: String?,
    onDone: (String) -> Unit,
    onBack: () -> Unit,
) {
    val model: SupplyEditViewModel =
        viewModel(key = "supply-edit:${supplyId.orEmpty()}") { SupplyEditViewModel(graph, supplyId) }
    val state by model.state.collectAsStateWithLifecycle()

    LaunchedEffect(model) { model.saved.collect { onDone(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(SUPPLY_ITEM) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    TextButton(onClick = model::save, enabled = state.canSave) { Text("Save") }
                },
            )
        },
    ) { padding ->
        // The 16dp gutter is each block's own, because the section title carries its own (the group form's rule).
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.goneProblem?.let { Problem(it, Modifier.padding(horizontal = 16.dp)) }

            Field(state.name, model::onName, "Name")
            Field(state.category, model::onCategory, CATEGORY_FIELD)
            Field(state.manufacturer, model::onManufacturer, MANUFACTURER_FIELD)
            Field(state.model, model::onModel, MODEL_FIELD)
            Field(state.partNumber, model::onPartNumber, PART_NUMBER_FIELD)
            Field(state.preferredUnit, model::onPreferredUnit, PREFERRED_UNIT_FIELD)
            OutlinedTextField(
                value = state.notes,
                onValueChange = model::onNotes,
                label = { Text(NOTES_LABEL) },
                minLines = 2,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            MaintenanceSectionTitle(SPECIFICATIONS_SECTION)
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                state.specifications.forEachIndexed { index, row ->
                    SpecificationRowEditor(
                        row = row,
                        onLabel = { model.onSpecification(index, label = it) },
                        onValue = { model.onSpecification(index, value = it) },
                        onUnit = { model.onSpecification(index, unit = it) },
                        onRemove = { model.removeSpecification(index) },
                    )
                }
                state.specificationsProblem?.let { Problem(it, Modifier.padding(top = 8.dp)) }
                AddRowButton(text = ADD_SPECIFICATION, onClick = model::addSpecification)
            }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        shape = ControlShape,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

/**
 * One specification row, on the Materials row's shape: "Label" with the row's close glyph (P15-8), then "Value" and
 * "Unit". A row the refused save named draws its label and value in the error state; no key is drawn, ever.
 */
@Composable
private fun SpecificationRowEditor(
    row: SpecificationEdit,
    onLabel: (String) -> Unit,
    onValue: (String) -> Unit,
    onUnit: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = row.label,
                onValueChange = onLabel,
                label = { Text("Label") },
                singleLine = true,
                isError = row.marked,
                shape = ControlShape,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Close, contentDescription = REMOVE_SPECIFICATION)
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            OutlinedTextField(
                value = row.value,
                onValueChange = onValue,
                label = { Text("Value") },
                singleLine = true,
                isError = row.marked,
                shape = ControlShape,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = row.unit,
                onValueChange = onUnit,
                label = { Text("Unit") },
                singleLine = true,
                shape = ControlShape,
                modifier = Modifier.width(96.dp),
            )
        }
    }
}

/** The one way to add a row: outlined, full width, no FAB (the quick-action editor's row button, D12 §7). */
@Composable
private fun AddRowButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = ControlShape,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text = text, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun Problem(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = ServiceTagTheme.semanticColors.due.foreground,
        modifier = modifier,
    )
}
