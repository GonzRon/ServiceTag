package com.loosecannon.servicetag.ui.attachments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.references.MaterializeStrings
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme

/** #85 §6 (reused, hoisted byte-identical): this sheet's words, which the Save-as-document review reuses. */
internal const val NAME_LABEL = "Name"
internal const val KIND_HEADER = "Kind"
internal const val NOTES_LABEL = "Notes"
internal const val CANCEL_LABEL = "Cancel"
internal const val SAVE_LABEL = "Save"

/**
 * Rename, re-kind, re-role (#67, an asset's files only), captured-on, notes, and Delete, in a
 * [ModalBottomSheet] (spec §8.1). Delete is a plain confirmation, not a typed one (spec §11.7): it
 * removes one file from the owner's own folder, which is not the weight of deleting an asset.
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
    /** #85 (C25): hands [AttachmentRowState.sourceUri] to the section's open decision; null draws no button. */
    onOpenSource: ((String) -> Unit)? = null,
) {
    // Keyed by the row's id, not the row: a save that comes back through the state flow must not
    // reset the fields the person is still editing.
    var name by remember(row.id) { mutableStateOf(row.displayName) }
    var kind by remember(row.id) { mutableStateOf(row.kind) }
    var role by remember(row.id) { mutableStateOf(row.role) }
    var capturedOn by remember(row.id) { mutableStateOf(row.capturedOn.orEmpty()) }
    var notes by remember(row.id) { mutableStateOf(row.notes) }
    var confirming by remember(row.id) { mutableStateOf(false) }

    // Fully expanded and scrolling, as `ChangeConditionSheet` does: with the Role section, a narrow
    // phone at a large text size has more sheet than window, and Save must still be reachable.
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
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(NAME_LABEL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SectionHeader(title = KIND_HEADER)
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
            // #67, C7: under Kind, and only for an asset's file (R67-11). Independent of the kind
            // (R67-7): a receipt can be a photo, and a manual can be filed as a document.
            if (rolesOffered) {
                SectionHeader(title = ROLE_HEADER)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ROLE_CHOICES.forEach { option ->
                        FilterChip(
                            selected = role == option,
                            onClick = { role = option },
                            label = { Text(option.label()) },
                        )
                    }
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
                label = { Text(NOTES_LABEL) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            // #85 C25 (R85-9): read-only, and only for a file saved from a reference. The button hands
            // on the reference's own URI, never the redirect's, and no URI is ever drawn here.
            row.provenanceLine?.let { line ->
                Column {
                    QuietLine(line)
                    val source = row.sourceUri
                    if (source != null && onOpenSource != null) {
                        TextButton(onClick = { onOpenSource(source) }) { Text(MaterializeStrings.OPEN_SOURCE_LINK) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { confirming = true }) {
                    Text(
                        text = "Delete",
                        color = ServiceTagTheme.semanticColors.destructiveAction.foreground,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(CANCEL_LABEL) }
                TextButton(
                    onClick = {
                        onSave(
                            UpdateAttachmentCommand(
                                displayName = name,
                                kind = kind,
                                capturedOn = capturedOn.ifBlank { null },
                                notes = notes,
                                // #67, C2/C7: the chosen role, seeded from the row's own, so a
                                // rename with the chips untouched never clears it.
                                role = role,
                            ),
                        )
                    },
                ) { Text(SAVE_LABEL) }
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
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(CANCEL_LABEL) } },
        )
    }
}
