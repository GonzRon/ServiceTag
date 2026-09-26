package com.loosecannon.servicetag.ui.api

import android.Manifest
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.api.DEVELOPER_API_PORT
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.LabelValue
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.appDetails
import com.loosecannon.servicetag.ui.components.open
import com.loosecannon.servicetag.ui.theme.MeasurementHeroText

/**
 * The one screen the automation API has, and the whole of its lifetime (1.1.0, issue #46).
 *
 * While this screen is on top and the activity is resumed, the app listens on this phone's own
 * loopback address, port [DEVELOPER_API_PORT], and answers `/v1` requests that carry the code shown
 * here. Pausing — the screen going off, the phone locking, an app switch — stops it; leaving the
 * screen disposes the composable and stops it; the next visit is a different code. There is no
 * service, no background work and no way to start the listener without this screen in front of the
 * owner, which is the point: the honest answer to "when is my phone accepting commands?" is "while
 * you are looking at the screen that says so".
 *
 * Nothing here decides anything about the API. The code, the router and the socket are
 * [DeveloperApiViewModel]'s; this draws four lines, at most one notice, and binds two lifecycle
 * edges and one composition edge.
 *
 * **The notice** ([developerApiNotice]) is S6 for a taken port, any other failure, or a listener
 * that died (#51), and the network-permission sentence with its settings button for a denial
 * (#66). The three rows stay in every state (1.1.0 decision 5). Returning from the app's settings
 * resumes the activity, and the resume effect runs [DeveloperApiViewModel.listen] again.
 *
 * **`FLAG_SECURE` for the whole composition** (#51): added on entering composition and cleared in
 * the same effect's dispose, so the pairing code never reaches the Recents thumbnail or a
 * screenshot. Not tied to the resume effect: clearing it on pause would race the snapshot the
 * system takes of a task leaving the foreground.
 *
 * The port is drawn from the constant rather than from `boundPort`, because the constant is what
 * the label means and what a workstation forwards to.
 *
 * [onOpenAppSettings] is for tests; when null, the button opens the app's own details page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeveloperApiScreen(
    graph: AppGraph,
    onBack: () -> Unit,
    onOpenAppSettings: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val model: DeveloperApiViewModel = viewModel(key = "developer-api") {
        val appContext = context.applicationContext
        DeveloperApiViewModel(
            graph = graph,
            networkPermissionGranted = {
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.INTERNET) ==
                    PackageManager.PERMISSION_GRANTED
            },
        )
    }
    val requests by model.requests.collectAsStateWithLifecycle()
    val listener by model.listener.collectAsStateWithLifecycle()

    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    LifecycleResumeEffect(model) {
        model.listen()
        onPauseOrDispose { model.stopListening() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Developer API") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QuietLine(
                "While this screen is open, ServiceTag accepts commands on this phone's " +
                    "loopback address only. Pair with the code below; leave the screen to stop.",
            )
            // Drawn in the error colour rather than as a QuietLine, because the one thing this
            // screen exists to do is not happening.
            when (developerApiNotice(listener)) {
                DeveloperApiNotice.None -> Unit
                DeveloperApiNotice.CouldNotStart -> Text(
                    // S6 (1.1.0).
                    text = "The Developer API could not start. Leave this screen and open it again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                DeveloperApiNotice.NetworkPermissionDenied -> Column {
                    Text(
                        // P1A-1, ratified 2026-09-25.
                        text = "ServiceTag is not allowed to use the network, so the Developer API " +
                            "cannot start. Allow network access in the app settings, then close " +
                            "ServiceTag and open it again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        onClick = onOpenAppSettings ?: { context.open(appDetails(context)) },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    ) {
                        // P1A-2, ratified 2026-09-25.
                        Text("Open app settings")
                    }
                }
            }
            LabelValue(label = "Port", value = DEVELOPER_API_PORT.toString(), mono = true)
            LabelValue(
                label = "Pairing code",
                value = model.pairingCode,
                valueStyle = MeasurementHeroText,
            )
            LabelValue(label = "Requests this session", value = requests.toString(), mono = true)
        }
    }
}
