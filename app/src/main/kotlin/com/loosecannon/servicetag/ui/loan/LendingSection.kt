package com.loosecannon.servicetag.ui.loan

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.contacts.ContactOpener
import com.loosecannon.servicetag.contacts.contactOpenFailure
import com.loosecannon.servicetag.contacts.rememberContactPick
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.asset.DateField
import com.loosecannon.servicetag.ui.asset.FieldLabel
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.theme.ControlShape

/**
 * #72 (C16, C18; R72-11, R72-12, R72-17, R72-18, R72-22): **Lending** (P72-3), right after Warranty and
 * before Condition, on an asset in service or one that holds any loan ([LoanFacts.shown]).
 *
 * The open block — on every lifecycle — draws the badge, "Lent to", "Lent", the due line, the reminder
 * line, the notes, "Open contact" with P72-19 (or P72-17 for a name-only loan), then "Choose from
 * Contacts" (a relink), "Mark returned" and "Edit loan". With nothing lent and the asset in service,
 * "Lend out" opens the lend form ([onLendOut]); "Edit loan" opens it on the open loan ([onEditLoan]).
 * "Past loans" lists the returned ones, with no action (frozen). The two writes — a return and a relink
 * — are [LoanActionsViewModel]'s; the overflow is unchanged.
 */
@Composable
fun LendingSection(
    graph: AppGraph,
    assetId: String,
    facts: LoanFacts,
    snackbars: SnackbarHostState,
    onLendOut: () -> Unit,
    onEditLoan: (loanId: String) -> Unit,
) {
    if (!facts.shown) return
    val model: LoanActionsViewModel = viewModel(key = "loan-actions-$assetId") { LoanActionsViewModel(graph) }
    val returning by model.returning.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.messages.collect { snackbars.showSnackbar(it) } }

    val openLoanId = facts.open?.loanId
    // The relink's pick: read once, in its callback, for the loan open when the result lands.
    val pick = rememberContactPick(
        onPicked = { uri -> openLoanId?.let { model.relink(it, uri) } },
        onNoPicker = model::onNoPicker,
    )
    val activity = LocalActivity.current

    SectionHeader(title = LENDING)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val open = facts.open
        if (open != null) {
            OpenLoan(
                block = open,
                onOpenContact = {
                    val opened = activity?.let { ContactOpener.open(it, open.lookupUri) } == true
                    contactOpenFailure(opened)?.let(model::say)
                },
                onChooseFromContacts = pick.launch,
                onMarkReturned = { model.askReturn(open.loanId) },
                onEditLoan = { onEditLoan(open.loanId) },
            )
        } else if (facts.offersLendOut) {
            OutlinedButton(onClick = onLendOut, shape = ControlShape) { Text(LEND_OUT) }
        }
        if (facts.history.isNotEmpty()) {
            FieldLabel(PAST_LOANS)
            facts.history.forEach { row -> PastLoan(row) }
        }
    }

    returning?.let { prompt ->
        ReturnDialog(
            prompt = prompt,
            onDate = model::onReturnedOn,
            onConfirm = model::confirmReturn,
            onDismiss = model::dismissReturn,
        )
    }
}

@Composable
private fun OpenLoan(
    block: OpenLoanBlock,
    onOpenContact: () -> Unit,
    onChooseFromContacts: () -> Unit,
    onMarkReturned: () -> Unit,
    onEditLoan: () -> Unit,
) {
    LoanBadge(block.standing)
    val (lentTo, lentOn, due) = block.lines
    BodyLine(lentTo)
    QuietLine(lentOn)
    if (due == NO_DUE_DATE) QuietLine(due) else BodyLine(due)
    block.reminderLine?.let { QuietLine(it) }
    block.notes.takeIf { it.isNotBlank() }?.let { BodyLine(it) }
    if (LoanAction.OPEN_CONTACT in block.actions) {
        OutlinedButton(onClick = onOpenContact, shape = ControlShape) { Text(OPEN_CONTACT) }
    }
    QuietLine(block.contactLine)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (LoanAction.CHOOSE_FROM_CONTACTS in block.actions) {
            TextButton(onClick = onChooseFromContacts) { Text(CHOOSE_FROM_CONTACTS) }
        }
        if (LoanAction.MARK_RETURNED in block.actions) {
            Button(onClick = onMarkReturned, shape = ControlShape) { Text(MARK_RETURNED) }
        }
        if (LoanAction.EDIT_LOAN in block.actions) {
            TextButton(onClick = onEditLoan) { Text(EDIT_LOAN) }
        }
    }
}

/** A returned loan: its lines and notes, and nothing to press (R72-18). */
@Composable
private fun PastLoan(row: PastLoanRow) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        row.lines.firstOrNull()?.let { BodyLine(it) }
        row.lines.drop(1).forEach { QuietLine(it) }
        row.notes.takeIf { it.isNotBlank() }?.let { QuietLine(it) }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun BodyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
}

/** P72-34 with "Returned on" (P72-35, today), "Mark returned" and "Cancel"; its refusal under the date. */
@Composable
private fun ReturnDialog(
    prompt: ReturnPrompt,
    onDate: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(MARK_RETURNED_QUESTION) },
        text = {
            DateField(value = prompt.date, onValueChange = onDate, label = RETURNED_ON, problem = prompt.problem)
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !prompt.saving) { Text(MARK_RETURNED) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.loan_cancel)) } },
    )
}
