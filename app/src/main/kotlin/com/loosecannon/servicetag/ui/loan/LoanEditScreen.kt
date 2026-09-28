package com.loosecannon.servicetag.ui.loan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.contacts.rememberContactPick
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.reminders.LOAN_NOTIFICATION_RATIONALE
import com.loosecannon.servicetag.ui.asset.DateField
import com.loosecannon.servicetag.ui.asset.FieldLabel
import com.loosecannon.servicetag.ui.asset.FormField
import com.loosecannon.servicetag.ui.asset.RefusalLine
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.maintenance.NONE
import com.loosecannon.servicetag.ui.maintenance.NOT_NOW
import com.loosecannon.servicetag.ui.theme.BadgeShape
import com.loosecannon.servicetag.ui.theme.ControlShape

/**
 * #72 (C17): the lend form — "Lend out" (P72-14) or "Edit loan" (P72-13) as its title. Borrower (P72-21):
 * on a new loan the picked name beside "Choose from Contacts" (P72-18), never typed; on an edit the
 * stored name as text. Then "Lent on" (P72-22, today), "Due back" (P72-23, optional), "Reminder" (P72-24)
 * with None / Once / Until returned — enabled only with a due date, P72-27 under them otherwise — Notes,
 * and "Save loan" (P72-28). A refusal is drawn under its field; a stale form or a failure is a snackbar.
 * After a save that first set a reminder with notifications off, P72-33 asks with "OK" and "Not now".
 * ✕ and back write nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoanEditScreen(
    graph: AppGraph,
    assetId: String,
    loanId: String?,
    onDone: (assetId: String) -> Unit,
    onBack: () -> Unit,
) {
    val model: LoanEditViewModel = viewModel(key = loanId ?: "new-loan-$assetId") {
        LoanEditViewModel(graph, assetId, loanId)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val missing by model.missing.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val pick = rememberContactPick(onPicked = model::onPicked, onNoPicker = model::onNoPicker)

    LaunchedEffect(model) { model.saved.collect { onDone(it) } }
    LaunchedEffect(model) { model.messages.collect { snackbars.showSnackbar(it) } }
    LaunchedEffect(missing) { if (missing) onBack() }

    if (state.askingForNotifications) {
        AlertDialog(
            onDismissRequest = model::dismissNotifications,
            text = { Text(LOAN_NOTIFICATION_RATIONALE) },
            confirmButton = { TextButton(onClick = model::requestNotifications) { Text("OK") } },
            dismissButton = { TextButton(onClick = model::dismissNotifications) { Text(NOT_NOW) } },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(state.screenTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
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
            FieldLabel(BORROWER)
            if (state.borrower.isNotBlank()) {
                Text(
                    text = state.borrower,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (state.isNew) {
                OutlinedButton(onClick = pick.launch, enabled = !state.saving && !state.reading, shape = ControlShape) {
                    Text(CHOOSE_FROM_CONTACTS)
                }
            }
            state.problems[LoanField.BORROWER]?.let { RefusalLine(it) }
            DateField(
                value = state.lentOn,
                onValueChange = model::onLentOn,
                label = LENT_ON,
                problem = state.problems[LoanField.LENT_ON],
            )
            DateField(
                value = state.dueOn,
                onValueChange = model::onDueOn,
                label = DUE_BACK,
                problem = state.problems[LoanField.DUE_ON],
            )
            FieldLabel(REMINDER)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                REMINDER_MODES.forEach { mode ->
                    FilterChip(
                        selected = state.mode == mode,
                        onClick = { model.onMode(mode) },
                        enabled = state.remindersEnabled,
                        label = { Text(reminderModeWord(mode)) },
                        shape = BadgeShape,
                    )
                }
            }
            if (!state.remindersEnabled) QuietLine(ADD_A_DUE_DATE_TO_GET_A_REMINDER)
            FormField(value = state.notes, onValueChange = model::onNotes, label = "Notes", minLines = 3)
            Button(
                onClick = model::save,
                enabled = state.canSave,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(SAVE_LOAN) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The chips in their order: the reused None, then P72-25 and P72-26. */
private val REMINDER_MODES = listOf(LoanReminderMode.NONE, LoanReminderMode.ONCE, LoanReminderMode.UNTIL_RETURNED)

/** A chip's word: the shipped "None", P72-25 "Once", P72-26 "Until returned". */
fun reminderModeWord(mode: LoanReminderMode): String = when (mode) {
    LoanReminderMode.NONE -> NONE
    LoanReminderMode.ONCE -> ONCE
    LoanReminderMode.UNTIL_RETURNED -> UNTIL_RETURNED
}
