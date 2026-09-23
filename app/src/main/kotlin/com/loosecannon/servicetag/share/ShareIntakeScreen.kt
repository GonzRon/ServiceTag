package com.loosecannon.servicetag.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.ui.attachments.label
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader

/**
 * One scrolling column: what arrived, the asset chooser, Name, Description, and — on a byte share
 * only — a Type control (spec §7). It is a pure function of [ShareIntakeState], so every state it
 * can be in is one `setContent` away in a device test.
 *
 * **Every word comes from [IntakeStrings]**, which carries spec §10 verbatim. The Type control's
 * seven labels are the shipped `AttachmentKind.label()` values, reused and never re-spelled.
 */
@Composable
internal fun ShareIntakeScreen(
    state: ShareIntakeState,
    onChoose: (String) -> Unit,
    onName: (String) -> Unit,
    onDescribe: (String) -> Unit,
    onKind: (AttachmentKind) -> Unit,
    onSave: () -> Unit,
    onConfirm: () -> Unit,
    onDismissConfirmation: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 24.dp, bottom = 24.dp),
    ) {
        Text(text = IntakeStrings.TITLE, style = MaterialTheme.typography.headlineSmall)

        when {
            // The intent is read off the main thread, so for one frame there is nothing to draw
            // but the title — and nothing actionable, which is the point: no chooser, no Save.
            state.loading -> Unit

            state.saved != null -> QuietLine(state.saved)

            // No assets, a refused stream, a blocked scheme, an over-long URI, an unreadable
            // share: one sentence and Close. Nothing is offered that could write a row.
            state.deadEnd != null -> {
                QuietLine(state.deadEnd)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text(IntakeStrings.CLOSE) }
                }
            }

            else -> IntakeForm(state, onChoose, onName, onDescribe, onKind, onSave, onCancel)
        }
        Spacer(Modifier.height(4.dp))
    }

    // Asked by name, and only the person's own "Save" confirms it (plan §18.2).
    state.confirming?.let { scheme ->
        AlertDialog(
            onDismissRequest = onDismissConfirmation,
            title = { Text(IntakeStrings.CONFIRM_TITLE) },
            text = {
                Text(
                    text = IntakeStrings.confirmBody(scheme),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = onConfirm) { Text(IntakeStrings.SAVE) } },
            dismissButton = {
                TextButton(onClick = onDismissConfirmation) { Text(IntakeStrings.CANCEL) }
            },
        )
    }
}

@Composable
private fun IntakeForm(
    state: ShareIntakeState,
    onChoose: (String) -> Unit,
    onName: (String) -> Unit,
    onDescribe: (String) -> Unit,
    onKind: (AttachmentKind) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    SectionHeader(title = IntakeStrings.RECEIVED)
    QuietLine(state.received)

    // D-20: only the byte path says anything about storage. A reference needs no folder, so a URI
    // share on the same phone draws none of this and saves normally.
    if (state.noFolder) QuietLine(IntakeStrings.NO_FOLDER)
    state.message?.let { QuietLine(it) }

    SectionHeader(title = IntakeStrings.ATTACH_TO)
    Text(
        text = IntakeStrings.CHOOSE_ASSET,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.assets.forEach { choice ->
            FilterChip(
                selected = choice.id == state.chosen,
                onClick = { onChoose(choice.id) },
                label = { Text(choice.name) },
            )
        }
    }

    OutlinedTextField(
        value = state.name,
        onValueChange = onName,
        label = { Text(IntakeStrings.NAME) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.description,
        onValueChange = onDescribe,
        label = { Text(IntakeStrings.DESCRIPTION) },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )

    // Only the byte path has a kind: the reference model has no column for one, and its kind is
    // inferred from the scheme rather than picked (spec §3.2, D-4).
    if (state.path == IntakePath.BYTES) {
        SectionHeader(title = IntakeStrings.TYPE)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AttachmentKind.entries.forEach { option ->
                FilterChip(
                    selected = option == state.kind,
                    onClick = { onKind(option) },
                    label = { Text(option.label()) },
                )
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel) {
            // A byte share with nowhere to put bytes is a dead end wearing a form: what is left
            // to do here is close it, not cancel a save that was never on offer.
            Text(if (state.noFolder) IntakeStrings.CLOSE else IntakeStrings.CANCEL)
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onSave, enabled = state.saveEnabled) {
            Text(
                if (state.path == IntakePath.NOTE) IntakeStrings.SAVE_AS_NOTE else IntakeStrings.SAVE,
            )
        }
    }
}
