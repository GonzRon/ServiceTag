package com.loosecannon.servicetag.ui.attachments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.usecase.UpdateAttachmentCommand
import com.loosecannon.servicetag.ui.asset.DateField
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme

/**
 * Rename, re-kind, captured-on, notes, and Delete, in a [ModalBottomSheet] (spec §8.1). Delete is
 * a plain confirmation, not a typed one (spec §11.7): it removes one file from the owner's own
 * folder, which is not the weight of deleting an asset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentEditSheet(
    row: AttachmentRowState,
    /** #67, C7: the Role chips are drawn only when true — an asset's file, never an event's. */
    rolesOffered: Boolean,
    onSave: (UpdateAttachmentCommand) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Keyed by the row's id, not the row: a save that comes back through the state flow must not
    // reset the fields the person is still editing.
    var name by remember(row.id) { mutableStateOf(row.displayName) }
    var kind by remember(row.id) { mutableStateOf(row.kind) }
    var capturedOn by remember(row.id) { mutableStateOf(row.capturedOn.orEmpty()) }
    var notes by remember(row.id) { mutableStateOf(row.notes) }
    var confirming by remember(row.id) { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SectionHeader(title = "Kind")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AttachmentKind.entries.forEach { option ->
                    FilterChip(
                        selected = kind == option,
                        onClick = { kind = option },
                        label = { Text(option.label()) },
                    )
                }
            }
            DateField(
                value = capturedOn,
                onValueChange = { capturedOn = it },
                label = "Captured on",
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { confirming = true }) {
                    Text(
                        text = "Delete",
                        color = ServiceTagTheme.semanticColors.destructiveAction.foreground,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(
                    onClick = {
                        onSave(
                            UpdateAttachmentCommand(
                                displayName = name,
                                kind = kind,
                                capturedOn = capturedOn.ifBlank { null },
                                notes = notes,
                                // #67, C2: the row's own role, so a rename never clears it.
                                role = row.role,
                            ),
                        )
                    },
                ) { Text("Save") }
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete file?") },
            text = {
                Text(
                    "Delete ${row.displayName}? The file is removed from your attachment folder.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onDelete() }) {
                    Text(
                        text = "Delete",
                        color = ServiceTagTheme.semanticColors.destructiveAction.foreground,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}
