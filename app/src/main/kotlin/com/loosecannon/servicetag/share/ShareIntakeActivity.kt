package com.loosecannon.servicetag.share

import android.os.Bundle
import androidx.activity.ComponentActivity
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

        // Read once, here, and never again: `onNewIntent` cannot reach a `standard` activity, and
        // a recreation re-reads the same intent under a grant that lives until the task finishes.
        val shared = readSharedItem(
            intent = intent,
            resolver = contentResolver,
            streamPolicy = graph.streamSourcePolicy,
            linkPolicy = graph.linkLaunchPolicy,
        )
        val content = shared.asContent()
        val source = (shared as? SharedItem.Bytes)?.let { contentResolver.byteSourceFor(it.uri) }

        setContent {
            val dark = when (graph.prefs.appearanceMode) {
                AppearanceMode.SYSTEM -> isSystemInDarkTheme()
                AppearanceMode.LIGHT -> false
                AppearanceMode.DARK -> true
            }
            ServiceTagTheme(darkTheme = dark) {
                val model = viewModel(key = "share-intake") {
                    ShareIntakeViewModel(
                        content = content,
                        source = source,
                        assets = graph.assets,
                        storage = graph.attachmentStorage,
                        addReference = graph.addReference,
                        addAttachment = graph.addAttachment,
                        logEvent = graph.logEvent,
                        today = { LocalDate.now().toString() },
                        zoneId = { ZoneId.systemDefault().id },
                    )
                }
                val state by model.state.collectAsStateWithLifecycle()

                LaunchedEffect(state.finished) { if (state.finished) finish() }

                ShareIntakeScreen(
                    state = state,
                    onChoose = model::choose,
                    onName = model::name,
                    onDescribe = model::describe,
                    onKind = model::kind,
                    onSave = model::save,
                    onConfirm = model::confirmUnknownScheme,
                    onDismissConfirmation = model::dismissConfirmation,
                    onCancel = model::cancel,
                )
            }
        }
    }
}
