package com.loosecannon.servicetag.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.ui.asset.AssetPicker
import com.loosecannon.servicetag.ui.asset.AssetsState
import com.loosecannon.servicetag.ui.attachments.ROLE_CHOICES
import com.loosecannon.servicetag.ui.attachments.label
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader

/**
 * Two steps (#93, C5): first the picker — what arrived, then the Assets tab's own search box, Type and
 * Components controls (no Archived: every row is maintained here, #69 C30) and rows, from [picker] — and,
 * once a destination is chosen, one scrolling column: what arrived, the destination with "Change" (#69 C29:
 * under a supply, its product line and P69-21), Name, Description, and — on a byte share only —
 * a Type control and a Role control (spec §7, and its #67 amendment). The step is decided above the
 * column, never inside it: the picker's lazy list cannot be measured in a scrolling one. It is a pure
 * function of [ShareIntakeState] and [AssetsState], so every state it can be in is one `setContent`
 * away in a device test.
 *
 * **The intake's own words come from [IntakeStrings]**, which carries spec §10 verbatim; the picker's
 * search box, controls, rows and empty sentences are `ui/asset`'s reused composables, in their own
 * ratified words (#73, #71). The Type control's seven labels are the shipped `AttachmentKind.label()`
 * values and the Role control's four are `DocumentRole?.label()`'s, reused and never re-spelled.
 */
@Composable
internal fun ShareIntakeScreen(
    state: ShareIntakeState,
    /** #93 (C9): the picker's list, from the hosted `AssetsViewModel`, and its own query (F3, never `picker.query`). */
    picker: AssetsState,
    pickerQuery: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onPickType: (String?) -> Unit,
    onToggleComponents: () -> Unit,
    onToggleArchived: () -> Unit,
    /** The final selection (C4; #69 C29). */
    onChoose: (ShareDestination) -> Unit,
    /** #93 (C6): the form's "Change", back to the picker. */
    onChangeAsset: () -> Unit,
    onName: (String) -> Unit,
    onDescribe: (String) -> Unit,
    onKind: (AttachmentKind) -> Unit,
    onRole: (DocumentRole?) -> Unit,
    onSave: () -> Unit,
    onConfirm: () -> Unit,
    onDismissConfirmation: () -> Unit,
    onCancel: () -> Unit,
) {
    if (state.picking) {
        PickerStep(
            state = state,
            picker = picker,
            query = pickerQuery,
            onQueryChange = onQueryChange,
            onClearQuery = onClearQuery,
            onPickType = onPickType,
            onToggleComponents = onToggleComponents,
            onToggleArchived = onToggleArchived,
            onChoose = onChoose,
            onCancel = onCancel,
        )
    } else {
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

                // `picking` is false here, so an asset is chosen: the form (C8).
                else -> IntakeForm(state, onChangeAsset, onName, onDescribe, onKind, onRole, onSave, onCancel)
            }
            Spacer(Modifier.height(4.dp))
        }
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

/**
 * #93 (C7): the first step — a sibling of the form's scrolling column, never inside it. One lazy list fills the
 * window inside the safe-drawing insets (bars, cutout and keyboard): the title, what arrived, ATTACH TO and "Choose
 * asset" as its leading items, then the tab's search box, controls and rows. Under it, Cancel — or Close on a byte
 * share with no folder (the form's rule). No Save: a tap on a row is the step's only way forward.
 *
 * **The header's item count is fixed for the visit**, every conditional line inside one item, so the search box
 * keeps its place in the list (and its focus) while what is above it changes; no header item is keyed by an asset id.
 */
@Composable
private fun PickerStep(
    state: ShareIntakeState,
    picker: AssetsState,
    query: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onPickType: (String?) -> Unit,
    onToggleComponents: () -> Unit,
    onToggleArchived: () -> Unit,
    onChoose: (ShareDestination) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AssetPicker(
            state = picker,
            query = query,
            onQueryChange = onQueryChange,
            onClearQuery = onClearQuery,
            onPickType = onPickType,
            onToggleComponents = onToggleComponents,
            onToggleArchived = onToggleArchived,
            onPick = { row -> onChoose(ShareDestination.Asset(row.asset.id.value, row.asset.name)) },
            // #69 (C30, C-1, C-4): every row here is maintained here, so no Archived control; none at all is P69-26.
            archivedControl = false,
            noAssetsLine = IntakeStrings.NO_ACTIVE_ASSETS,
            modifier = Modifier.weight(1f),
            header = {
                item {
                    Text(
                        text = IntakeStrings.TITLE,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp),
                    )
                }
                item {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 10.dp),
                    ) {
                        ReceivedBlock(state)
                    }
                }
                item {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 10.dp),
                    ) {
                        SectionHeader(title = IntakeStrings.ATTACH_TO)
                        Text(
                            text = IntakeStrings.CHOOSE_ASSET,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            TextButton(onClick = onCancel) {
                Text(if (state.noFolder) IntakeStrings.CLOSE else IntakeStrings.CANCEL)
            }
        }
    }
}

/**
 * What arrived, on both steps (#93, C7, R93-10): RECEIVED and its line, D-20's no-folder sentence, and the refusal or
 * "That is not a link." beside it. Drawn into the caller's column.
 */
@Composable
private fun ReceivedBlock(state: ShareIntakeState) {
    SectionHeader(title = IntakeStrings.RECEIVED)
    QuietLine(state.received)

    // D-20: only the byte path says anything about storage. A reference needs no folder, so a URI
    // share on the same phone draws none of this and saves normally.
    if (state.noFolder) QuietLine(IntakeStrings.NO_FOLDER)
    state.message?.let { QuietLine(it) }
}

@Composable
private fun IntakeForm(
    state: ShareIntakeState,
    onChangeAsset: () -> Unit,
    onName: (String) -> Unit,
    onDescribe: (String) -> Unit,
    onKind: (AttachmentKind) -> Unit,
    onRole: (DocumentRole?) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    ReceivedBlock(state)

    // #93 (C8, R93-5), #69 (C29): the destination line and "Change" back to the picker, then — under a supply — its
    // product line and P69-21. The form is the confirmation: no dialog.
    SectionHeader(title = IntakeStrings.ATTACH_TO)
    state.destination?.let { destination ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuietLine(text = destination.label, modifier = Modifier.weight(1f))
            TextButton(onClick = onChangeAsset) { Text(IntakeStrings.CHANGE) }
        }
        destination.notes.forEach { QuietLine(it) }
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

    // #67 (R67-9), amended by #91 (R91-4): the same canonical role the edit sheet sets, on a byte
    // share (after Type) and on a web-link share (after Description); a note link, an unfamiliar
    // scheme and a note take none. The view model decides; the screen only reads `roleOffered`.
    if (state.roleOffered) {
        SectionHeader(title = IntakeStrings.ROLE)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ROLE_CHOICES.forEach { option ->
                FilterChip(
                    selected = option == state.role,
                    onClick = { onRole(option) },
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
