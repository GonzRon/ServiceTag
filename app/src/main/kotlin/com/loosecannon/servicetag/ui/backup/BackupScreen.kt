package com.loosecannon.servicetag.ui.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.backup.SafBackupIO
import com.loosecannon.servicetag.backup.SafBackupSetWriter
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.transfer.`import`.TransferImportStrings
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import com.loosecannon.servicetag.ui.components.LabelValue
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.theme.ControlShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The word the user has to type before an import runs (R-9). Not localised: it is a password. */
private const val REPLACE_WORD = "REPLACE"

/** What an import will accept back. Providers hand zips back as octet-stream often enough to matter. */
private val IMPORT_TYPES = arrayOf("application/zip", "application/octet-stream")

/**
 * Export a backup *set* into a folder the owner picks, or restore one — in two steps, because a
 * set is two files (spec §7.3).
 *
 * Export is filled and safe. Restore data is outlined and asks the user to type [REPLACE_WORD]
 * first, because it deletes everything that is not in the file (R-9) — unless there is nothing
 * here to delete, in which case it asks for a plain confirmation instead (#40): a phone with no
 * assets, tags, events, attachments or tombstone link rows has nothing to accept the loss of, and
 * spelling out REPLACE over an empty database warns about data that does not exist. Restore files
 * is outlined but has no dialog at all: it adds bytes the data archive only listed and deletes
 * nothing, and it refuses an archive belonging to a different set rather than mixing two backups
 * together. There is no wipe here — that is the debug harness's job, not the product's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    graph: AppGraph,
    onBack: () -> Unit,
    /** #77 (C16): opens the import screen over the picked pack's copy in `cache/transfer-in/`, by its bare name. */
    onImportPack: (String) -> Unit = {},
    /** #84 C3 (D-3): P77-50 from a completed Transfer Pack import, carried back by the root; shown once. */
    importedLine: String? = null,
    /** Clears [importedLine] in the root as it starts showing, so a recomposition or a resume never shows it twice. */
    onImportedLineShown: () -> Unit = {},
) {
    val model: BackupViewModel = viewModel(key = "backup") { BackupViewModel(graph) }
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resolver = context.contentResolver
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf<Uri?>(null) }
    var typed by remember { mutableStateOf("") }
    // Which confirmation the picked file gets (#40). Written before [confirming], so one pick
    // raises exactly one dialog and the owner never sees one replaced by the other.
    var emptyStore by remember { mutableStateOf(false) }

    LaunchedEffect(model) { model.messages.collect { snackbars.showSnackbar(it) } }

    // #84 C3 (D-3, M-1): the import's P77-50, cleared as it starts showing. The effect is keyed on the snackbar host
    // and never on the line: clearing the line recomposes this screen with null, and an effect keyed on it would
    // restart, cancel `showSnackbar` mid-show and take the snackbar down with it.
    val pendingLine by rememberUpdatedState(importedLine)
    val lineShown by rememberUpdatedState(onImportedLineShown)
    LaunchedEffect(snackbars) {
        snapshotFlow { pendingLine }.filterNotNull().collect { line ->
            lineShown()
            snackbars.showSnackbar(line)
        }
    }

    // A folder for this export only: no persistable grant is taken, so nothing accumulates and
    // the destination is not remembered (spec §11.2 — 3R is where a remembered one arrives).
    val exportInto = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val tree = uri?.let { DocumentFile.fromTreeUri(context.applicationContext, it) }
        when {
            uri == null -> Unit
            tree == null ->
                scope.launch { snackbars.showSnackbar("That folder cannot be written to") }
            else -> model.exportSetTo(SafBackupSetWriter(context.applicationContext, resolver, tree))
        }
    }

    val restoreDataFrom = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        // Picking the file is not agreeing to lose what is here: the dialog is the agreement.
        //
        // #40 — and which agreement is a question about this phone, not about the file. It is asked
        // once per pick and answered before the dialog goes up, so there is never a frame in which
        // the wrong confirmation is on screen. A cancelled pick asks nothing.
        typed = ""
        if (uri == null) {
            confirming = null
        } else {
            scope.launch {
                // Ask more, never less. `BackupViewModel`'s own storage calls all go through
                // `runCatching { … }.rethrowCancellation()`, and that helper is file-private to it,
                // so the same rule is written out here: a cancelled coroutine still cancels, and a
                // read that cannot answer gets the typed word rather than the gentler dialog. The
                // alternative — letting it escape a `rememberCoroutineScope()` job that carries no
                // exception handler — is a crash on the one screen an owner reaches *because*
                // something is wrong with their phone.
                emptyStore = try {
                    model.isStoreEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    false
                }
                confirming = uri
            }
        }
    }

    val restoreFilesFrom = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { model.restoreFilesFrom(SafBackupIO(resolver, it)) } }

    // #77 (C16, R77-2): the picked Transfer Pack is copied into `cache/transfer-in/` before anything reads it, and
    // the import screen reads only that copy. A copy that fails leaves no file and writes nothing.
    val importPackFrom = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val copy = try {
                    withContext(Dispatchers.IO) {
                        graph.transferPackInbox.copyIn(
                            ByteSource { resolver.openInputStream(uri) ?: throw IOException("the provider returned no stream") },
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (copy == null) {
                    snackbars.showSnackbar(TransferImportStrings.COULD_NOT_IMPORT)
                } else {
                    onImportPack(copy.name)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LabelValue(label = "Last backup", value = lastBackupLine(state.lastBackupAt))
            QuietLine(
                "A backup set is two files in the folder you pick: the data, and the attachment " +
                    "files beside it. It is the only copy off this phone.",
            )

            Button(
                onClick = { exportInto.launch(null) },
                enabled = !state.busy,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Export backup set")
            }

            SectionHeader(title = "Restore")
            QuietLine("Restoring the data deletes everything on this phone first, then loads the file.")
            OutlinedButton(
                onClick = { restoreDataFrom.launch(IMPORT_TYPES) },
                enabled = !state.busy,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Restore data")
            }

            QuietLine(
                "Then restore the files archive of the same set to put the attachment files back. " +
                    "It adds files and deletes nothing.",
            )
            OutlinedButton(
                onClick = { restoreFilesFrom.launch(IMPORT_TYPES) },
                enabled = !state.busy,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Restore files")
            }

            OutlinedButton(
                onClick = { importPackFrom.launch(IMPORT_TYPES) },
                enabled = !state.busy,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(TransferImportStrings.TITLE)
            }
        }
    }

    confirming?.let { uri ->
        if (emptyStore) {
            RestoreEmptyStoreDialog(
                onDismiss = { confirming = null },
                onRestore = {
                    confirming = null
                    model.restoreDataFrom(SafBackupIO(resolver, uri))
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { confirming = null },
                title = { Text("Replace everything?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Every asset, tag and link on this phone is deleted and replaced with what " +
                                "is in the file. This cannot be undone.",
                        )
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            singleLine = true,
                            label = { Text("Type $REPLACE_WORD to confirm") },
                            shape = ControlShape,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirming = null
                            model.restoreDataFrom(SafBackupIO(resolver, uri))
                        },
                        enabled = typed == REPLACE_WORD,
                    ) {
                        Text("Replace")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirming = null }) { Text("Cancel") }
                },
                shape = ControlShape,
            )
        }
    }
}

/**
 * The confirmation an empty phone gets (#40). No typed word, because there is nothing to authorise
 * the loss of: `REPLACE` exists so that the owner has to spell out that they accept losing what is
 * on this phone (R-9), and on a phone with no assets, tags, events, attachments or tombstone link
 * rows there is nothing to lose. The restore itself is the same call either way — a wipe-and-load
 * of an empty database is a load.
 */
@Composable
private fun RestoreEmptyStoreDialog(onDismiss: () -> Unit, onRestore: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore this backup?") },
        text = { Text("This phone has no records yet, so there is nothing to replace.") },
        confirmButton = { TextButton(onClick = onRestore) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = ControlShape,
    )
}

/** "Never" is a fact worth stating plainly; anything else is the instant, to the minute. */
private fun lastBackupLine(at: Long?): String = at
    ?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it)) }
    ?: "Never"
