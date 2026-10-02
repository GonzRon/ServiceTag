package com.loosecannon.servicetag.ui.installed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.ui.asset.DateField
import com.loosecannon.servicetag.ui.asset.FormField
import com.loosecannon.servicetag.ui.asset.NAME_FIELD
import com.loosecannon.servicetag.ui.attachments.NOTES_LABEL
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.components.StatusBadge
import com.loosecannon.servicetag.ui.supplies.LINKED_TO
import com.loosecannon.servicetag.ui.supplies.SupplyLinkLine
import com.loosecannon.servicetag.ui.supplies.SupplyListRow
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme

/*
 * #47 (C26) — the Installed components sheets, on #15's sheet pattern (`AssetSuppliesSection`'s role sheet): fully
 * expanded, scrolling, stateless and view-model-free. Each draws what it is handed and reports a tap; the section's
 * wrapper owns the view model and decides. Each takes a `composition` slot, empty here, where the composition display
 * and editor draw (P47-23).
 */

/**
 * The row sheet: the name, the direct SupplyItem in `SupplyLinkLine`'s words (a tap opens it through
 * [onOpenSupply]), the [composition] slot, the serial or lot (P47-6), the install day (P47-7, or P47-16), the removal
 * day (P47-8), and "History" (P47-14) — the position's instances newest first. With [offersWrites], a current row
 * offers "Install inside" (P47-4), "Replace" (P47-9), "Remove" and "Edit"; a removed row offers "Edit" only. A held
 * asset draws the facts and the history and no action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InstalledComponentRowSheet(
    sheet: RowSheetState,
    supplies: Map<SupplyId, SupplyListRow>,
    offersWrites: Boolean,
    onOpenSupply: (SupplyId) -> Unit,
    onInstallInside: () -> Unit,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
    composition: @Composable ColumnScope.() -> Unit = {},
) {
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
            Text(sheet.name, style = MaterialTheme.typography.titleMedium)
            sheet.supplyId?.let { id ->
                supplies[id]?.let { item -> LinkedSupply(item, onOpen = { onOpenSupply(id) }) }
            }
            composition()
            if (sheet.serialOrLot.isNotBlank()) Fact(SERIAL_OR_LOT, sheet.serialOrLot)
            val installedDay = sheet.installedDay
            if (installedDay != null) Fact(INSTALLED_ON, installedDay) else QuietLine(INSTALL_DATE_NOT_RECORDED)
            sheet.removedDay?.let { Fact(REMOVED_ON, it) }
            SectionHeader(title = COMPONENT_HISTORY)
            sheet.history.forEach { line ->
                Column {
                    Text(line.name, style = MaterialTheme.typography.bodyMedium)
                    QuietLine(listOfNotNull(line.installed, line.closed, line.replaces).joinToString(" · "))
                }
            }
            if (offersWrites) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (sheet.current) {
                        TextButton(onClick = onInstallInside) { Text(INSTALL_INSIDE) }
                        TextButton(onClick = onReplace) { Text(REPLACE_COMPONENT) }
                        TextButton(onClick = onRemove) { Text("Remove") }
                    }
                    TextButton(onClick = onEdit) { Text("Edit") }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * The install (P47-3), edit (P47-13) and replace (P47-10) sheet: "Name"; P47-5 when installing inside a row; the
 * direct link through `SupplyLinkLine` ("Link supply" asks for the picker, "Remove link" clears it; an archived link
 * wears its badge and stays removable) with the one sentence a refusal draws under it; the [composition] slot;
 * "Serial or lot" (P47-6); "Installed on" (P47-7, on replace the replacement date) with its sentence; "Notes"; P47-12
 * on a replace with current children; P47-19 when the row changed underneath; and Cancel / Save ("Replace" on replace).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComponentFormSheet(
    form: ComponentFormState,
    supplies: Map<SupplyId, SupplyListRow>,
    onName: (String) -> Unit,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
    onSerialOrLot: (String) -> Unit,
    onDate: (String) -> Unit,
    onNotes: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    composition: @Composable ColumnScope.() -> Unit = {},
) {
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
            Text(form.title, style = MaterialTheme.typography.titleMedium)
            form.inside?.let { QuietLine(it) }
            FormField(value = form.name, onValueChange = onName, label = NAME_FIELD)
            SupplyLinkLine(supplyId = form.supplyId, supplies = supplies, onUnlink = onUnlink, onLink = onLink)
            form.linkProblem?.let { Problem(it) }
            composition()
            FormField(value = form.serialOrLot, onValueChange = onSerialOrLot, label = SERIAL_OR_LOT)
            DateField(value = form.date, onValueChange = onDate, label = INSTALLED_ON, problem = form.dateProblem)
            FormField(value = form.notes, onValueChange = onNotes, label = NOTES_LABEL, minLines = 3)
            if (form.subtreeToo) Text(SUBTREE_REMOVED_TOO, style = MaterialTheme.typography.bodyMedium)
            form.problem?.let { Problem(it) }
            Buttons(
                confirm = if (form.replacing) REPLACE_COMPONENT else "Save",
                enabled = form.canSave,
                onConfirm = onSave,
                onDismiss = onDismiss,
            )
        }
    }
}

/**
 * The remove sheet: P47-11, "Removed on" (P47-8) with its sentence, P47-12 when the row has current children, P47-19
 * when it changed underneath, and Cancel / "Remove". Nothing is erased, so nothing is typed to confirm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoveComponentSheet(
    sheet: RemoveSheetState,
    onRemovedOn: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
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
            Text(sheet.title, style = MaterialTheme.typography.titleMedium)
            DateField(value = sheet.removedOn, onValueChange = onRemovedOn, label = REMOVED_ON, problem = sheet.dateProblem)
            if (sheet.subtreeToo) Text(SUBTREE_REMOVED_TOO, style = MaterialTheme.typography.bodyMedium)
            sheet.problem?.let { Problem(it) }
            Buttons(confirm = "Remove", enabled = sheet.canSave, onConfirm = onRemove, onDismiss = onDismiss)
        }
    }
}

/** "Linked to {name}" (P15-22) with the shipped "Archived" badge; the line opens the SupplyItem. */
@Composable
private fun LinkedSupply(item: SupplyListRow, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .heightIn(min = 48.dp),
    ) {
        Text(
            text = LINKED_TO.format(item.name),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (item.archived) {
            StatusBadge(label = "Archived", colors = ServiceTagTheme.semanticColors.seasonInactive)
        }
    }
}

/** One fact: its label, quiet, over its value. */
@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A refusal's sentence, in the role sheet's colour. */
@Composable
private fun Problem(line: String) {
    Text(text = line, style = MaterialTheme.typography.bodySmall, color = ServiceTagTheme.semanticColors.due.foreground)
}

@Composable
private fun Buttons(confirm: String, enabled: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("Cancel") }
        TextButton(onClick = onConfirm, enabled = enabled) { Text(confirm) }
    }
    Spacer(Modifier.height(4.dp))
}
