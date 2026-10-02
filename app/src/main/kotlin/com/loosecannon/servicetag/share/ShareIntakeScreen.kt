package com.loosecannon.servicetag.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.ui.asset.AssetPicker
import com.loosecannon.servicetag.ui.asset.AssetsState
import com.loosecannon.servicetag.ui.asset.EmptyList
import com.loosecannon.servicetag.ui.asset.EmptyReason
import com.loosecannon.servicetag.ui.asset.SearchBox
import com.loosecannon.servicetag.ui.attachments.ROLE_CHOICES
import com.loosecannon.servicetag.ui.attachments.label
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.installed.NO_INSTALLED_COMPONENTS
import com.loosecannon.servicetag.ui.supplies.NO_SUPPLY_ITEMS_YET
import com.loosecannon.servicetag.ui.supplies.SupplyListRow
import com.loosecannon.servicetag.ui.supplies.SupplyRow
import com.loosecannon.servicetag.ui.theme.ControlShape

/**
 * Two steps (#93, C5): first the picker — what arrived, then the Assets tab's own search box, Type and
 * Components controls (no Archived: every row is maintained here, #69 C30) and rows, from [picker]; on a link or a
 * file, a type control above them switches to the installed components or the supplies (#69 C30 steps 1–4) — and,
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
    /** #69 (C30 step 2): the type control's choice; the one query stays with the hosted Assets list. */
    onChooseType: (ShareTargetType) -> Unit = {},
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
            onChooseType = onChooseType,
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
 * window inside the safe-drawing insets (bars, cutout and keyboard): the title, what arrived, ATTACH TO, the type
 * control (a link or a file, #69 C30) and "Choose asset" (Assets only) as its leading items, then the chosen type's
 * search box and rows — the tab's controls with them on Assets. Under it, Cancel — or Close on a byte share with no
 * folder (the form's rule). No Save: a tap on a row is the step's only way forward.
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
    onChooseType: (ShareTargetType) -> Unit,
    onChoose: (ShareDestination) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // #69 (C30 step 1): one header for every type: the type control sits in one place above each search box.
        val header: LazyListScope.() -> Unit = { pickerHeader(state, onChooseType) }
        when (state.type) {
            ShareTargetType.ASSETS -> AssetPicker(
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
                header = header,
            )
            // #69 (C30 steps 3–5): the other two lists under the same query; a row is the final selection, no dialog.
            ShareTargetType.INSTALLED_COMPONENTS -> TargetList(
                targets = state.componentRows(query),
                hint = IntakeStrings.SEARCH_INSTALLED_COMPONENTS,
                noneEligible = NO_INSTALLED_COMPONENTS,
                query = query,
                onQueryChange = onQueryChange,
                onClearQuery = onClearQuery,
                key = { it.componentId.value },
                header = header,
                modifier = Modifier.weight(1f),
            ) { target -> ComponentRow(target, onClick = { onChoose(target.destination) }) }
            ShareTargetType.SUPPLIES -> TargetList(
                targets = state.supplyRows(query),
                hint = IntakeStrings.SEARCH_SUPPLIES,
                noneEligible = NO_SUPPLY_ITEMS_YET,
                query = query,
                onQueryChange = onQueryChange,
                onClearQuery = onClearQuery,
                key = { it.supplyId.value },
                header = header,
                modifier = Modifier.weight(1f),
            ) { target ->
                SupplyRow(
                    row = SupplyListRow(target.supplyId, target.name, detail = target.productLine, archived = false),
                    onClick = { onChoose(target.destination) },
                )
            }
        }
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
 * The picker's leading items, the same three for every type (#93 C7; #69 C30 step 1): the title, what arrived, and
 * ATTACH TO over the type control (a link or a file) and "Choose asset" (Assets chosen) — both inside one item.
 */
private fun LazyListScope.pickerHeader(state: ShareIntakeState, onChooseType: (ShareTargetType) -> Unit) {
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
            if (state.typeOffered) TypeControl(type = state.type, onPick = onChooseType)
            if (state.type == ShareTargetType.ASSETS) {
                Text(
                    text = IntakeStrings.CHOOSE_ASSET,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * #69 (C30 step 2): which list the picker shows — the Assets tab's Type control's shape (an `AssistChip` with the
 * dropdown icon, read as a dropdown list, a fixed accessible name and the choice as its state), none of its category
 * logic, in the default chip colours: a mode, not a filter. Its accessible name is ATTACH TO, the header it sits under.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeControl(type: ShareTargetType, onPick: (ShareTargetType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(type.label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            shape = ControlShape,
            modifier = Modifier.semantics {
                role = Role.DropdownList
                contentDescription = IntakeStrings.ATTACH_TO
                stateDescription = type.label
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ShareTargetType.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = {
                        open = false
                        onPick(choice)
                    },
                )
            }
        }
    }
}

/**
 * #69 (C30 steps 3–4): the installed-component or supply list, laid out as the Assets list — the shared [header], the
 * search box under the one [query] with this type's [hint], then the rows with the tab's dividers. With nothing of this
 * type eligible before filtering, its own [noneEligible] line; for a miss, the tab's own miss line ([EmptyList]).
 */
@Composable
private fun <T> TargetList(
    targets: ShareTargetList<T>,
    hint: String,
    noneEligible: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    key: (T) -> String,
    header: LazyListScope.() -> Unit,
    modifier: Modifier,
    row: @Composable (T) -> Unit,
) {
    LazyColumn(modifier = modifier) {
        header()
        item {
            SearchBox(
                query = query,
                onQueryChange = onQueryChange,
                onClear = onClearQuery,
                hint = hint,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        when (targets) {
            ShareTargetList.NoneEligible -> item { QuietLine(text = noneEligible, modifier = Modifier.padding(16.dp)) }
            ShareTargetList.NothingMatches -> item {
                EmptyList(
                    reason = EmptyReason.NOTHING_MATCHES,
                    archivedCount = 0,
                    onNewAsset = null,
                    onShowArchived = {},
                )
            }
            is ShareTargetList.Rows -> itemsIndexed(targets.rows, key = { _, target -> key(target) }) { index, target ->
                if (index > 0) {
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
                row(target)
            }
        }
    }
}

/**
 * #69 (C30 step 4): one installed component — its name over a quiet line naming where it sits, the asset's name then
 * each ancestor's (P69-20). One clickable row, so TalkBack reads it whole.
 */
@Composable
private fun ComponentRow(target: ComponentTarget, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(
            text = target.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        QuietLine(IntakeStrings.pathOf(target.context))
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
