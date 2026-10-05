package com.loosecannon.servicetag.ui.service

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.asset.DateField
import com.loosecannon.servicetag.ui.asset.FieldLabel
import com.loosecannon.servicetag.ui.asset.FormField
import com.loosecannon.servicetag.ui.asset.RefusalLine
import com.loosecannon.servicetag.ui.attachments.AttachmentsSection
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.MonoText

/**
 * #79 (C22): one service case, titled P79-21, with "Edit". The header's facts; the Incident (P79-57)
 * and Repair record (P79-58) link rows — each opens its event, a gone one reads S24 — with each linked
 * event's documents drawn through its own section under it (R79-2: no document is owned by a case);
 * P79-59 when there is a repair to link; then the Timeline (P79-53), oldest first, and "Add update"
 * (P79-54). No entry can be edited or removed (R79-8), and closing a case asks nothing (R79-9).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceCaseScreen(
    graph: AppGraph,
    caseId: String,
    onEdit: (assetId: String, caseId: String) -> Unit,
    onOpenEvent: (eventId: String) -> Unit,
    onBack: () -> Unit,
    /** DOCUMENTS sends the person here when there is no attachment folder yet (spec §8.1). */
    onOpenSettings: () -> Unit,
) {
    val model: ServiceCaseViewModel = viewModel(key = "case-$caseId") { ServiceCaseViewModel(graph, caseId) }
    val state by model.state.collectAsStateWithLifecycle()
    val missing by model.missing.collectAsStateWithLifecycle()
    val sheet by model.sheet.collectAsStateWithLifecycle()
    val picking by model.picking.collectAsStateWithLifecycle()
    val linking by model.linking.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }

    LaunchedEffect(missing) { if (missing) onBack() }
    LaunchedEffect(model) { model.messages.collect { snackbars.showSnackbar(it) } }

    val current = state
    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(SERVICE_CASE) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.service_back))
                    }
                },
                actions = {
                    // #77 (C19): a transferred-out asset's case is read, never changed.
                    if (current != null && current.editable) {
                        TextButton(onClick = { onEdit(current.case.assetId.value, current.case.id.value) }) {
                            Text(stringResource(R.string.service_edit))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (current == null) return@Scaffold
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(current.case.title, style = MaterialTheme.typography.titleLarge)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 4.dp)) {
                current.facts.forEach { fact ->
                    FieldLabel(fact.label)
                    Text(fact.value, style = MaterialTheme.typography.bodyMedium)
                }
            }
            current.incident?.let { link ->
                LinkRow(link, onOpenEvent = onOpenEvent, onRemove = null, busy = linking)
                if (link.exists) LinkedDocuments(graph, link.eventId, snackbars, onOpenSettings, !current.editable)
            }
            val repair = current.repair
            if (repair != null) {
                val remove: (() -> Unit)? = if (current.editable) model::removeRepair else null
                LinkRow(repair, onOpenEvent = onOpenEvent, onRemove = remove, busy = linking)
                if (repair.exists) LinkedDocuments(graph, repair.eventId, snackbars, onOpenSettings, !current.editable)
            } else if (current.offersLinkRepair) {
                TextButton(onClick = model::openPicker, enabled = !linking) { Text(LINK_REPAIR_RECORD) }
            }
            SectionHeader(title = TIMELINE)
            current.timeline.forEach { row -> TimelineEntry(row) }
            if (current.editable) {
                OutlinedButton(onClick = model::openUpdate, shape = ControlShape, modifier = Modifier.padding(top = 8.dp)) {
                    Text(ADD_UPDATE)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (picking) {
            RepairPicker(current.candidates, onPick = model::linkRepair, onDismiss = model::closePicker)
        }
        sheet?.let { open ->
            UpdateSheetContent(
                sheet = open,
                onDate = model::onUpdateDate,
                onTime = model::onUpdateTime,
                onNote = model::onUpdateNote,
                onStatus = model::onUpdateStatus,
                onSave = model::saveUpdate,
                onCancel = model::cancelUpdate,
            )
        }
    }
}

/** A link row: its label, the event's title and date (tap opens it) or S24; "Remove" where offered. */
@Composable
private fun LinkRow(link: CaseLink, onOpenEvent: (String) -> Unit, onRemove: (() -> Unit)?, busy: Boolean) {
    FieldLabel(link.label)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        val gone = link.removedLine
        if (gone != null) {
            QuietLine(gone, Modifier.weight(1f))
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onOpenEvent(link.eventId) }
                    .heightIn(min = 44.dp)
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    link.title.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                link.date?.let { Text(it, style = MonoText, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (onRemove != null) {
            TextButton(onClick = onRemove, enabled = !busy) { Text(stringResource(R.string.service_remove)) }
        }
    }
}

/** The linked event's own documents, through its own section: the case owns none (R79-2). */
@Composable
private fun LinkedDocuments(
    graph: AppGraph,
    eventId: String,
    snackbars: SnackbarHostState,
    onOpenSettings: () -> Unit,
    readOnly: Boolean = false,
) {
    AttachmentsSection(
        graph = graph,
        owner = AttachmentOwner.OfEvent(EventId(eventId)),
        snackbars = snackbars,
        onOpenSettings = onOpenSettings,
        readOnly = readOnly,
    )
}

/** One timeline entry as recorded: its day and time, the status it set, and what happened. */
@Composable
private fun TimelineEntry(row: TimelineRow) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            listOfNotNull(row.date, row.time).joinToString(" · "),
            style = MonoText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        row.status?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        if (row.note.isNotBlank()) Text(row.note, style = MaterialTheme.typography.bodyMedium)
    }
}

/** P79-59's picker: this asset's MAINTENANCE and REPLACEMENT events, newest first, and "Cancel". */
@Composable
private fun RepairPicker(candidates: List<RepairCandidate>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(LINK_REPAIR_RECORD) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                candidates.forEach { event ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(event.eventId) }
                            .heightIn(min = 48.dp)
                            .padding(vertical = 8.dp),
                    ) {
                        Text(event.title, style = MaterialTheme.typography.bodyMedium)
                        Text(event.date, style = MonoText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.service_cancel)) } },
    )
}

/**
 * P79-54's sheet: Date (today), Time, P79-55, the status chips (none chosen is no change), then
 * "Cancel" and P79-56 — enabled only with a note or a status. Its refusals sit under their fields;
 * a failure is "Could not save this entry.".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateSheetContent(
    sheet: UpdateSheet,
    onDate: (String) -> Unit,
    onTime: (String) -> Unit,
    onNote: (String) -> Unit,
    onStatus: (CaseStatus) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(ADD_UPDATE, style = MaterialTheme.typography.titleLarge)
            DateField(
                value = sheet.date,
                onValueChange = onDate,
                label = stringResource(R.string.service_field_date),
                problem = sheet.problems[UpdateField.DATE],
            )
            FormField(
                value = sheet.time,
                onValueChange = onTime,
                label = stringResource(R.string.service_field_time),
                problem = sheet.problems[UpdateField.TIME],
                placeholder = stringResource(R.string.service_time_placeholder),
                mono = true,
            )
            OutlinedTextField(
                value = sheet.note,
                onValueChange = onNote,
                label = { Text(WHAT_HAPPENED) },
                minLines = 3,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            )
            FieldLabel(CASE_STATUS)
            Chips(options = CASE_STATUSES, selected = sheet.status, word = ::caseStatusWord, onSelect = onStatus)
            sheet.failure?.let { RefusalLine(it) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel, enabled = !sheet.saving) { Text(stringResource(R.string.service_cancel)) }
                Button(onClick = onSave, enabled = sheet.canSave, shape = ControlShape) { Text(SAVE_UPDATE) }
            }
        }
    }
}
