package com.loosecannon.servicetag.share

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.ServiceTagApp
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.prefs.AppearanceMode
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import com.loosecannon.servicetag.ui.transfer.`import`.TransferDoor
import com.loosecannon.servicetag.ui.transfer.`import`.TransferImportContent
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId

/**
 * The exported `ACTION_SEND` target (spec §4.1). **This fully-qualified name is the string the
 * manifest and `ManifestContractTest`'s exported set both carry**, so it is never renamed, moved
 * or repackaged.
 *
 * It builds the graph the way the shell does and draws one screen over the sharing app. Its empty
 * `taskAffinity` keeps it out of the shell's `singleTask` stack, so a running shell is neither
 * navigated, recreated nor scrolled; Save, Cancel, Close and back all end in `finish()`, which
 * returns to the app that shared, and **cancel and every refusal write nothing** (I-8).
 */
class ShareIntakeActivity : ComponentActivity() {

    private val graph: AppGraph get() = (application as ServiceTagApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Read once, by the view model, on an IO context: the byte arm is two binder round trips
        // to a provider that may be remote and the uri-list arm pulls up to 64 KiB, none of which
        // belongs on the way to the first frame. `onNewIntent` cannot reach a `standard` activity,
        // and a recreation re-reads the same intent under a grant that lives until the task ends.
        val read: suspend () -> SharedShare = {
            readShare(
                intent = intent,
                resolver = contentResolver,
                streamPolicy = graph.streamSourcePolicy,
                linkPolicy = graph.linkLaunchPolicy,
            )
        }

        setContent {
            val dark = when (graph.prefs.appearanceMode) {
                AppearanceMode.SYSTEM -> isSystemInDarkTheme()
                AppearanceMode.LIGHT -> false
                AppearanceMode.DARK -> true
            }
            ServiceTagTheme(darkTheme = dark) {
                val model = viewModel(key = "share-intake") {
                    ShareIntakeViewModel(
                        readShare = read,
                        assets = graph.assets,
                        storage = graph.attachmentStorage,
                        addReference = graph.addReference,
                        addAttachment = graph.addAttachment,
                        logEvent = graph.logEvent,
                        today = { LocalDate.now().toString() },
                        zoneId = { ZoneId.systemDefault().id },
                        heldIds = { graph.transferRecords.heldIds() },
                        packInbox = graph.transferPackInbox,
                    )
                }
                val state by model.state.collectAsStateWithLifecycle()

                LaunchedEffect(state.finished) { if (state.finished) finish() }

                // #77 (C16, R77-2): a shared Transfer Pack hosts the import screen over the intake's copy; its Close,
                // Cancel and every end finish, returning to the app that shared.
                val pack = state.packCopy
                if (state.path == IntakePath.TRANSFER_PACK && pack != null) {
                    val importModel = viewModel(key = "share-transfer-import") {
                        shareTransferImport(graph.importTransferPack, graph.transferPackInbox, pack)
                    }
                    val importState by importModel.state.collectAsStateWithLifecycle()
                    LaunchedEffect(importState.finished) { if (importState.finished) finish() }
                    // MJ-1: back does nothing while the import runs; finishing would cancel it mid-write.
                    BackHandler(enabled = importState.importing) { }
                    TransferImportContent(
                        state = importState,
                        door = TransferDoor.SHARE,
                        onImport = importModel::import,
                        onCancel = importModel::cancel,
                        onClose = importModel::close,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                } else {
                    ShareIntakeScreen(
                        state = state,
                        onChoose = model::choose,
                        onName = model::name,
                        onDescribe = model::describe,
                        onKind = model::kind,
                        onRole = model::role,
                        onSave = model::save,
                        onConfirm = model::confirmUnknownScheme,
                        onDismissConfirmation = model::dismissConfirmation,
                        onCancel = model::cancel,
                    )
                }
            }
        }
    }
}
