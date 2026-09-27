package com.loosecannon.servicetag.ui.condition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.asset.DateField
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.theme.ControlShape

/** S5: the Change condition sheet's title, and asset detail's section (B14). */
const val CONDITION_TITLE = "Condition"

/** S6: the action that opens this sheet — on the scan sheet, and on asset detail (B14). */
const val CHANGE_CONDITION = "Change condition"

/** S14. */
const val WHAT_IS_WRONG = "What is wrong? (optional)"

/** S15. */
const val WHEN_DID_THIS_CHANGE = "When did this change?"

/** S16. */
const val SAVE_CONDITION = "Save condition"

/**
 * #82's P82-1: the question S16 asks, over this sheet, when DOWN or DEGRADED is saved for an asset in
 * service. Nothing is written until it is answered (R82-2).
 */
const val LOG_INCIDENT_DETAILS_QUESTION = "Log incident details?"

/** P82-2, "<asset> is <DOWN/DEGRADED>. Record what went wrong in the service record?": its body. */
fun logIncidentDetailsBody(assetName: String, condition: OperationalCondition): String =
    "$assetName is ${conditionWord(condition)}. Record what went wrong in the service record?"

/** P82-3: its accept. It opens the Incident entry and writes nothing here. */
const val LOG_INCIDENT_DETAILS = "Log incident details"

/** P82-4: its decline, which writes the held condition alone (R82-14; not "Done"). */
const val SAVE_CONDITION_ONLY = "Save condition only"

/** P82-5, "Saving also records <asset> as <DOWN/DEGRADED>.": the combined entry's line under its eyebrow. */
fun savingAlsoRecordsLine(assetName: String, condition: OperationalCondition): String =
    "Saving also records $assetName as ${conditionWord(condition)}."

/**
 * **Change condition** (spec §5.4, §10.1), titled S5: the three options S8, S10 and S12, each with
 * its helper and **none preselected**; S14, the optional reason; S15, the day it changed — today by
 * default, past dates allowed, a later one answered by S25; **S16** records it once, and Cancel
 * writes nothing. The scan sheet opens it, and asset detail opens the same composable (B14).
 *
 * **#82 (C6).** DOWN or DEGRADED for an asset in service is held and P82-1 asked over the sheet:
 * P82-4 records the row alone; P82-3 hands the draft to [onLogIncidentDetails], whose Incident entry
 * records both; dismissing the question returns to the form. Nothing is written until one of the
 * two answers commits it. Every time the sheet is shown again — back from the entry — the view model
 * reads once whether the entry's Save landed before the question is drawn again.
 *
 * It takes the [graph] every screen here takes, beside the pinned `assetId` and `onDone`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeConditionSheet(
    graph: AppGraph,
    assetId: String,
    onLogIncidentDetails: (PendingCondition) -> Unit,
    onDone: () -> Unit,
) {
    val model: ChangeConditionViewModel = viewModel(key = "change-condition/$assetId") {
        ChangeConditionViewModel(graph, assetId)
    }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.finished.collect { onDone() } }
    val handOver by rememberUpdatedState(onLogIncidentDetails)
    LaunchedEffect(model) { model.handOver.collect { handOver(it) } }
    LifecycleResumeEffect(model) {
        model.onShown()
        onPauseOrDispose { }
    }

    ModalBottomSheet(
        onDismissRequest = model::cancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ChangeConditionForm(
            state = state,
            onChoose = model::choose,
            onReason = model::onReason,
            onDate = model::onDate,
            onSave = model::save,
            onCancel = model::cancel,
        )
    }
    val held = state.held
    if (held != null && state.asking) {
        LogIncidentQuestion(
            assetName = state.assetName,
            condition = held.condition,
            saving = state.saving,
            onLogIncidentDetails = model::logIncidentDetails,
            onSaveConditionOnly = model::saveConditionOnly,
            onDismiss = model::dismissQuestion,
        )
    }
}

/**
 * P82-1 over the sheet: P82-2 names the asset and the held condition; P82-3 opens the Incident entry
 * and P82-4 records the condition alone. Dismissing it writes nothing and leaves the form as typed.
 * Both answers disable while P82-4's row is landing.
 */
@Composable
private fun LogIncidentQuestion(
    assetName: String,
    condition: OperationalCondition,
    saving: Boolean,
    onLogIncidentDetails: () -> Unit,
    onSaveConditionOnly: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(LOG_INCIDENT_DETAILS_QUESTION) },
        text = { Text(logIncidentDetailsBody(assetName, condition), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(enabled = !saving, onClick = onLogIncidentDetails) { Text(LOG_INCIDENT_DETAILS) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onSaveConditionOnly) { Text(SAVE_CONDITION_ONLY) }
        },
    )
}

@Composable
private fun ChangeConditionForm(
    state: ChangeConditionState,
    onChoose: (OperationalCondition) -> Unit,
    onReason: (String) -> Unit,
    onDate: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(CONDITION_TITLE, style = MaterialTheme.typography.titleLarge)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OPTIONS.forEach { option ->
                ConditionOption(option, selected = state.choice == option, onChoose = { onChoose(option) })
            }
        }
        OutlinedTextField(
            value = state.reason,
            onValueChange = onReason,
            label = { Text(WHAT_IS_WRONG) },
            minLines = 2,
            shape = ControlShape,
            modifier = Modifier.fillMaxWidth(),
        )
        DateField(
            value = state.occurredOn,
            onValueChange = onDate,
            label = WHEN_DID_THIS_CHANGE,
            problem = state.refusal,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel, enabled = !state.saving) { Text("Cancel") }
            Button(onClick = onSave, enabled = state.canSave, shape = ControlShape) { Text(SAVE_CONDITION) }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** One option: the radio, its word (S8, S10, S12) and its helper (S9, S11, S13), as one target. */
@Composable
private fun ConditionOption(option: OperationalCondition, selected: Boolean, onChoose: () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onChoose, role = Role.RadioButton),
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(conditionOption(option), style = MaterialTheme.typography.bodyLarge)
            QuietLine(conditionHelper(option))
        }
    }
}

/** The three answers, in the order spec §10.7 lists them. */
private val OPTIONS = listOf(
    OperationalCondition.OPERATIONAL,
    OperationalCondition.DEGRADED,
    OperationalCondition.DOWN,
)
