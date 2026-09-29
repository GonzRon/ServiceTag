package com.loosecannon.servicetag.ui.transfer.`import`

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.share.IntakeStrings
import com.loosecannon.servicetag.ui.asset.FieldLabel
import com.loosecannon.servicetag.ui.backup.NoAttachmentFolder
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.theme.ControlShape

/**
 * #77 (C16, R77-2) — the Backup screen's door: [TransferImportContent] under the P77-38 title, over the copy the
 * Backup screen made in `cache/transfer-in/` ([copy] is its bare file name). Back cancels, which writes nothing and
 * deletes the copy; P77-50 is a snackbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferImportScreen(graph: AppGraph, copy: String, onBack: () -> Unit) {
    val model: TransferImportViewModel = viewModel(key = "transfer-import-$copy") {
        TransferImportViewModel(
            graph.importTransferPack, graph.transferPackInbox, graph.transferPackInbox.find(copy),
            NoAttachmentFolder().message.orEmpty(),
        )
    }
    val state by model.state.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    // MJ-1: back does nothing while the import runs — leaving would cancel it mid-write. The precedent is the asset
    // editor's save (`AssetEditScreen`).
    BackHandler(enabled = state.importing) { }
    LaunchedEffect(state.finished) { if (state.finished) onBack() }
    LaunchedEffect(state.done) { state.done?.let { snackbars.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(TransferImportStrings.TITLE) },
                navigationIcon = {
                    IconButton(onClick = model::cancel, enabled = !state.importing) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        TransferImportContent(
            state = state,
            door = TransferDoor.BACKUP,
            onImport = model::import,
            onCancel = model::cancel,
            onClose = model::close,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * The import screen's body, a pure function of [TransferImportState], so every state is one `setContent` away in a
 * device test. **Every word is a ratified sentence** from [TransferImportStrings], or a reused one through its home
 * (`Cancel`, `Close`). The share host ([TransferDoor.SHARE]) draws P77-50 as a line with the intake's `Close`; the
 * Backup door's P77-50 is its screen's snackbar.
 */
@Composable
fun TransferImportContent(
    state: TransferImportState,
    door: TransferDoor,
    onImport: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        if (door == TransferDoor.SHARE) {
            Text(text = TransferImportStrings.TITLE, style = MaterialTheme.typography.headlineSmall)
        }
        when (state.phase) {
            TransferImportPhase.READING -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            TransferImportPhase.PREVIEW, TransferImportPhase.IMPORTING -> Preview(state, onImport, onCancel)
            TransferImportPhase.DONE -> if (door == TransferDoor.SHARE) {
                QuietLine(state.done.orEmpty())
                CloseRow(onClose)
            }
            TransferImportPhase.REFUSED -> {
                ErrorLine(state.refusal.orEmpty())
                if (door == TransferDoor.SHARE) CloseRow(onClose)
            }
        }
    }
}

@Composable
private fun Preview(state: TransferImportState, onImport: () -> Unit, onCancel: () -> Unit) {
    state.note?.let { Text(text = it, style = MaterialTheme.typography.bodyLarge) }
    state.created?.let { QuietLine(it) }
    FieldLabel(TransferImportStrings.CONTAINS)
    state.contains.forEach { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
    state.comingBack.forEach { QuietLine(it) }
    state.duplicates.forEach { QuietLine(it) }
    state.outcome.forEach { line ->
        if (line == TransferImportStrings.ALREADY_HERE) {
            Text(text = line, style = MaterialTheme.typography.bodyMedium)
        } else {
            ErrorLine(line)
        }
    }
    if (state.importing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCancel, enabled = !state.importing) { Text(IntakeStrings.CANCEL) }
        Button(onClick = onImport, enabled = state.importEnabled && !state.importing, shape = ControlShape) {
            Text(TransferImportStrings.IMPORT)
        }
    }
}

@Composable
private fun CloseRow(onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onClose) { Text(IntakeStrings.CLOSE) }
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}
