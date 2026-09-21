package com.loosecannon.servicetag.ui.api

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.api.DEVELOPER_API_PORT
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.LabelValue
import com.loosecannon.servicetag.ui.components.QuietLine
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
 * [DeveloperApiViewModel]'s; this draws four lines and binds two lifecycle edges.
 *
 * The port is drawn from the constant rather than from `boundPort`, because the constant is what
 * the label means and what a workstation forwards to; a bind failure therefore shows as a request
 * count that never moves, and is logged once (see [DeveloperApiViewModel.listen]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeveloperApiScreen(
    graph: AppGraph,
    onBack: () -> Unit,
) {
    val model: DeveloperApiViewModel =
        viewModel(key = "developer-api") { DeveloperApiViewModel(graph) }
    val requests by model.requests.collectAsStateWithLifecycle()
    val failed by model.failedToStart.collectAsStateWithLifecycle()

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
            if (failed) {
                // S6. Drawn in the error colour rather than as a QuietLine, because the one thing
                // this screen exists to do is not happening.
                Text(
                    text = "The Developer API could not start. Leave this screen and open it again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
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
