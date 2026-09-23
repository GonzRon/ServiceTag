package com.loosecannon.servicetag.ui.references

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_DESCRIPTION_CHARS
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_NAME_CHARS
import com.loosecannon.servicetag.core.usecase.UpdateReferenceCommand
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme

/**
 * Name and description, and nothing else. The URI is not a field and is not on the sheet at all:
 * it is stored exactly as it was validated and is never edited (I-1), so an affordance for it
 * would be one that cannot work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReferenceEditSheet(
    row: ReferenceRowState,
    onSave: (UpdateReferenceCommand) -> Unit,
    onDismiss: () -> Unit,
) {
    // Keyed by the row's id, not the row: a save that comes back through the state flow must not
    // reset the fields the person is still editing.
    var name by remember(row.id) { mutableStateOf(row.displayName) }
    var description by remember(row.id) { mutableStateOf(row.description) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetColumn {
            Text("Edit reference", style = MaterialTheme.typography.titleMedium)
            NameField(name) { name = it }
            DescriptionField(description) { description = it }
            SheetButtons(
                onCancel = onDismiss,
                onSave = {
                    onSave(UpdateReferenceCommand(displayName = name, description = description))
                },
            )
        }
    }
}

/**
 * The section's own "Add link" (D-21 C). It hands its three strings to the same use case a share
 * does, so the row it writes is indistinguishable from a shared one afterwards — deliberately.
 *
 * The link field is **not** capped as the other two are: the name and description caps are field
 * limits with no sentence behind them, while an over-long URI has a ratified refusal of its own,
 * which a field that stopped typing at 2,048 characters would make unreachable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddLinkSheet(
    onSave: (uri: String, name: String, description: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var link by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetColumn {
            Text("Add link", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("Link") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            NameField(name) { name = it }
            DescriptionField(description) { description = it }
            SheetButtons(onCancel = onDismiss, onSave = { onSave(link, name, description) })
        }
    }
}

/**
 * A plain confirm and then a hard delete: one metadata row, no bytes, nothing to orphan, so the
 * typed-REPLACE weight an asset carries would be out of all proportion here (D-9).
 */
@Composable
internal fun RemoveReferenceDialog(onRemove: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove this reference?") },
        text = {
            Text(
                text = "The link is removed from this asset. Nothing in the other app is changed.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onRemove) {
                Text(
                    text = "Remove",
                    color = ServiceTagTheme.semanticColors.destructiveAction.foreground,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The one confirmation an unfamiliar scheme takes, asked by name. Answering Save re-runs the same
 * command with `confirmedUnknownScheme = true`; there is no second question at launch (spec §4.2).
 */
@Composable
internal fun UnknownSchemeDialog(scheme: String, onSave: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save this link?") },
        text = {
            Text(
                text = "ServiceTag does not recognise \"$scheme\" links. " +
                    "It will be saved as written and opened with whatever app claims it.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SheetColumn(content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
    ) {
        content()
        Spacer(Modifier.height(4.dp))
    }
}

/** Stops at the cap rather than refusing after the fact, which is why §10 ratifies no line here. */
@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(MAX_REFERENCE_NAME_CHARS)) },
        label = { Text("Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DescriptionField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(MAX_REFERENCE_DESCRIPTION_CHARS)) },
        label = { Text("Description") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SheetButtons(onCancel: () -> Unit, onSave: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text("Cancel") }
        TextButton(onClick = onSave) { Text("Save") }
    }
}
