package com.loosecannon.servicetag.ui.references

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.share.IntakeStrings
import com.loosecannon.servicetag.ui.api.OPEN_APP_SETTINGS
import com.loosecannon.servicetag.ui.attachments.CANCEL_LABEL
import com.loosecannon.servicetag.ui.attachments.KIND_HEADER
import com.loosecannon.servicetag.ui.attachments.NAME_LABEL
import com.loosecannon.servicetag.ui.attachments.NOTES_LABEL
import com.loosecannon.servicetag.ui.attachments.ROLE_CHOICES
import com.loosecannon.servicetag.ui.attachments.ROLE_HEADER
import com.loosecannon.servicetag.ui.attachments.SAVE_LABEL
import com.loosecannon.servicetag.ui.attachments.label
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.components.appDetails
import com.loosecannon.servicetag.ui.components.open
import kotlinx.coroutines.Dispatchers

/**
 * #85 C23 (R85-8, R85-9): Save as document, one modal sheet drawing [MaterializeViewModel]'s states —
 * downloading, the owner's review, saving, or one refusal line. Every word is a `MaterializeStrings`
 * constant or a reused home's; the host is drawn and the URI never is.
 *
 * The model is built here, inside the open sheet, because building it starts the download: the sheet
 * exists only after the owner's tap. Every way out but Save is `cancel()` (the job stops, the staging
 * goes), and Saving cannot be left at all — no button, no swipe, no scrim, no back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaterializeSheet(
    assetId: AssetId,
    row: ReferenceRowState,
    graph: AppGraph,
    /** Done, handed off once: the host closes the sheet and says P85-7. */
    onSaved: () -> Unit,
    /** Closed: the host closes the sheet with nothing to say. */
    onClose: () -> Unit,
) {
    val model: MaterializeViewModel = viewModel(key = "materialize-${assetId.value}-${row.id}") {
        MaterializeViewModel(graph, assetId, ReferenceId(row.id), row.uri)
    }
    // Main.immediate, so a keystroke's rename lands in the field in the same frame (the fields bind to the model).
    val state by model.state.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val saved by rememberUpdatedState(onSaved)
    val closed by rememberUpdatedState(onClose)

    // Opening is the tap: a model an earlier Cancel, Close or Save left Closed downloads afresh, and a live
    // one is untouched (`start()`). Only then is the flow read, so that Closed means closed: Done is handed
    // off exactly once (#84), and Closed (a cancel, or a vanished or ineligible row) closes silently.
    LaunchedEffect(model) {
        model.start()
        model.state.collect { now ->
            when (now) {
                MaterializeState.Done -> if (model.handOffDone()) saved()
                MaterializeState.Closed -> closed()
                else -> Unit
            }
        }
    }

    val saving = state == MaterializeState.Saving
    val refuseHideWhileSaving = remember(model) {
        { target: SheetValue -> target != SheetValue.Hidden || model.state.value != MaterializeState.Saving }
    }
    ModalBottomSheet(
        onDismissRequest = model::cancel,
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = refuseHideWhileSaving,
        ),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !saving),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            when (val now = state) {
                is MaterializeState.Downloading -> DownloadingBody(now, onCancel = model::cancel)
                is MaterializeState.Review -> ReviewBody(now, model)
                MaterializeState.Saving -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                is MaterializeState.Refused -> RefusedBody(now, onClose = model::cancel)
                MaterializeState.Done, MaterializeState.Closed -> Unit
            }
        }
    }
}

/** P85-2 and P85-3; the bar is indeterminate until the size is known. */
@Composable
private fun DownloadingBody(state: MaterializeState.Downloading, onCancel: () -> Unit) {
    Text(MaterializeStrings.downloadingFrom(state.host), style = MaterialTheme.typography.titleMedium)
    val total = state.total?.takeIf { it > 0L }
    if (total == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(progress = { state.done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
    }
    QuietLine(MaterializeStrings.progress(state.done, state.total))
    Buttons { TextButton(onClick = onCancel) { Text(CANCEL_LABEL) } }
}

/** P85-1, P85-4, P85-5, then the four fields, each bound to the model rather than to local state. */
@Composable
private fun ReviewBody(state: MaterializeState.Review, model: MaterializeViewModel) {
    Text(MaterializeStrings.SAVE_AS_DOCUMENT, style = MaterialTheme.typography.titleMedium)
    QuietLine(MaterializeStrings.fromHost(state.host))
    QuietLine(state.typeLine)
    OutlinedTextField(
        value = state.name,
        onValueChange = model::rename,
        label = { Text(NAME_LABEL) },
        singleLine = true,
        isError = state.nameError != null,
        supportingText = state.nameError?.let { line -> { Text(line) } },
        modifier = Modifier.fillMaxWidth(),
    )
    SectionHeader(title = KIND_HEADER)
    Chips(AttachmentKind.entries, selected = state.kind, label = { it.label() }, onChoose = model::chooseKind)
    SectionHeader(title = ROLE_HEADER)
    Chips(ROLE_CHOICES, selected = state.role, label = { it.label() }, onChoose = model::chooseRole)
    OutlinedTextField(
        value = state.notes,
        onValueChange = model::editNotes,
        label = { Text(NOTES_LABEL) },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
    Buttons {
        TextButton(onClick = model::cancel) { Text(CANCEL_LABEL) }
        TextButton(onClick = model::save) { Text(SAVE_LABEL) }
    }
}

/** The line (a P85 refusal or a reused sentence), Close, and for P85-10 the app's own settings page. */
@Composable
private fun RefusedBody(state: MaterializeState.Refused, onClose: () -> Unit) {
    val context = LocalContext.current
    Text(state.line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    if (state.offersAppSettings) {
        TextButton(
            onClick = { context.open(appDetails(context)) },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) { Text(OPEN_APP_SETTINGS) }
    }
    Buttons { TextButton(onClick = onClose) { Text(IntakeStrings.CLOSE) } }
}

@Composable
private fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onChoose: (T) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            FilterChip(selected = selected == option, onClick = { onChoose(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        content()
    }
}
