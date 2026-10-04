package com.loosecannon.servicetag.ui.service

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.asset.DateField
import com.loosecannon.servicetag.ui.asset.FieldLabel
import com.loosecannon.servicetag.ui.asset.FormField
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.theme.BadgeShape
import com.loosecannon.servicetag.ui.theme.ControlShape

/**
 * #79 (C21): the case editor — titled P79-19 on a new case and P79-21 on an edit. Title, Type, Opened
 * on, Service provider, Phone or contact, Case or RMA number, Coverage (P79-36 under it on a new
 * case), each leg's tracking and carrier, Cost beside Currency, Notes, then "Save case". A refusal is
 * drawn under its field; an unexpected failure is P79-61 on the snackbar. ✕ and back write nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceCaseEditScreen(
    graph: AppGraph,
    assetId: String,
    caseId: String?,
    incidentId: String?,
    onDone: (caseId: String) -> Unit,
    onBack: () -> Unit,
) {
    val model: ServiceCaseEditViewModel = viewModel(key = caseId ?: "new-case-$assetId-$incidentId") {
        ServiceCaseEditViewModel(graph, assetId, caseId, incidentId)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val missing by model.missing.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }

    LaunchedEffect(model) { model.saved.collect { onDone(it) } }
    LaunchedEffect(model) { model.messages.collect { snackbars.showSnackbar(it) } }
    LaunchedEffect(missing) { if (missing) onBack() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(state.screenTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.service_close))
                    }
                },
            )
        },
    ) { padding ->
        // Nothing is drawn until the form is filled: a half-filled form would invite a half-typed save.
        if (!state.loaded) return@Scaffold
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            FormField(
                value = state.title,
                onValueChange = model::onTitle,
                label = CASE_TITLE,
                problem = state.problems[CaseField.TITLE],
            )
            FieldLabel(CASE_TYPE)
            Chips(options = CASE_TYPES, selected = state.type, word = ::caseTypeWord, onSelect = model::onType)
            DateField(
                value = state.openedOn,
                onValueChange = model::onOpenedOn,
                label = OPENED_ON,
                problem = state.problems[CaseField.OPENED_ON],
            )
            FormField(value = state.provider, onValueChange = model::onProvider, label = SERVICE_PROVIDER)
            FormField(value = state.contact, onValueChange = model::onContact, label = PHONE_OR_CONTACT)
            FormField(value = state.caseRef, onValueChange = model::onCaseRef, label = CASE_OR_RMA_NUMBER, mono = true)
            FieldLabel(COVERAGE)
            Chips(options = CASE_COVERAGES, selected = state.coverage, word = ::coverageWord, onSelect = model::onCoverage)
            if (state.suggested) QuietLine(SUGGESTED_FROM_THE_WARRANTY_DATE)
            Leg(
                tracking = state.outboundTracking,
                carrier = state.outboundCarrier,
                trackingLabel = OUTBOUND_TRACKING,
                onTracking = model::onOutboundTracking,
                onCarrier = model::onOutboundCarrier,
            )
            Leg(
                tracking = state.returnTracking,
                carrier = state.returnCarrier,
                trackingLabel = RETURN_TRACKING,
                onTracking = model::onReturnTracking,
                onCarrier = model::onReturnCarrier,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                FormField(
                    value = state.cost,
                    onValueChange = model::onCost,
                    label = COST,
                    problem = state.problems[CaseField.COST],
                    mono = true,
                    numeric = true,
                    modifier = Modifier.weight(1f),
                )
                FormField(
                    value = state.currency,
                    onValueChange = model::onCurrency,
                    label = stringResource(R.string.service_field_currency),
                    problem = state.problems[CaseField.CURRENCY],
                    mono = true,
                    modifier = Modifier.width(126.dp),
                )
            }
            FormField(
                value = state.notes,
                onValueChange = model::onNotes,
                label = stringResource(R.string.service_field_notes),
                minLines = 3,
            )
            Button(
                onClick = model::save,
                enabled = state.canSave,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(SAVE_CASE) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One leg of the case: its tracking number beside its carrier (P79-46, one home for both legs). */
@Composable
private fun Leg(
    tracking: String,
    carrier: String,
    trackingLabel: String,
    onTracking: (String) -> Unit,
    onCarrier: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        FormField(
            value = tracking,
            onValueChange = onTracking,
            label = trackingLabel,
            mono = true,
            modifier = Modifier.weight(1f),
        )
        FormField(value = carrier, onValueChange = onCarrier, label = CARRIER, modifier = Modifier.width(126.dp))
    }
}

/** A label's answers as chips, the chosen one — if any — selected. */
@Composable
internal fun <T> Chips(options: List<T>, selected: T?, word: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(word(option)) },
                shape = BadgeShape,
            )
        }
    }
}
