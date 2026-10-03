package com.loosecannon.servicetag.ui.homeassistant

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.seasonsync.AndroidNetworkPlatform
import com.loosecannon.servicetag.ui.asset.CANCEL_BUTTON
import com.loosecannon.servicetag.ui.attachments.SAVE_LABEL
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader

/**
 * #16 (C26) — Settings → Home Assistant: the one connection of this installation. A pushed destination reached only
 * from Settings, like the Developer API screen; no deep link. Everything it says is [HomeAssistantViewModel]'s
 * state; this draws it, launches the permission requests the model emits, and reports their answers back. The token
 * field is masked, never pre-filled and has no reveal toggle (C26).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeAssistantScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val model: HomeAssistantViewModel = viewModel(key = "home-assistant") {
        val appContext = context.applicationContext
        HomeAssistantViewModel(
            graph = graph,
            platform = AndroidNetworkPlatform(appContext),
            backgroundOptionLabel = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    appContext.packageManager.backgroundPermissionOptionLabel.toString()
                } else {
                    ""
                }
            },
        )
    }
    val state by model.state.collectAsStateWithLifecycle()
    val token by model.tokenField.collectAsStateWithLifecycle()

    LocationAsks(
        requests = model.requests,
        backgroundAsk = state.backgroundAsk,
        onAnswered = model::onPermissionAnswered,
        onResumed = model::onResumed,
        onAcceptBackgroundAsk = model::acceptBackgroundAsk,
        onDeclineBackgroundAsk = model::declineBackgroundAsk,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(HA_TITLE) },
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.statusLine?.let { QuietLine(it) }
            OutlinedTextField(
                value = state.address,
                onValueChange = model::onAddressChange,
                label = { Text(HA_SERVER_ADDRESS) },
                supportingText = { Text(HA_SERVER_ADDRESS_HELP) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = model::onTokenChange,
                label = { Text(HA_ACCESS_TOKEN) },
                supportingText = { Text(HA_ACCESS_TOKEN_HELP) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.tokenPresent) QuietLine(HA_TOKEN_SAVED)

            SectionHeader(title = HA_HOW_OFTEN)
            Column(Modifier.selectableGroup()) {
                HA_CADENCE_CHOICES.forEach { (cadence, label) ->
                    ChoiceRow(label, state.cadence == cadence) { model.chooseCadence(cadence) }
                }
            }

            SectionHeader(title = HA_WHERE_TO_CHECK)
            Column(Modifier.selectableGroup()) {
                ChoiceRow(HA_ANY_NETWORK, !state.homeNetworkChosen, model::chooseAnyNetwork)
                QuietLine(HA_ANY_NETWORK_HELP)
                ChoiceRow(HA_HOME_WIFI_ONLY, state.homeNetworkChosen, model::chooseHomeWifi)
            }
            if (state.homeNetworkChosen) {
                state.homeWifiLine?.let { QuietLine(it) }
                if (state.promptCapture) {
                    Button(onClick = model::captureNetwork) { Text(HA_USE_THIS_NETWORK) }
                } else {
                    OutlinedButton(onClick = model::captureNetwork) { Text(HA_USE_THIS_NETWORK) }
                }
                SectionHeader(title = HA_BACKGROUND_CHECKS)
                Column(Modifier.selectableGroup()) {
                    ChoiceRow(HA_OFF, state.backgroundChecks == BackgroundChecks.OFF) {
                        model.chooseBackgroundChecks(BackgroundChecks.OFF)
                    }
                    ChoiceRow(HA_ON, state.backgroundChecks == BackgroundChecks.ON) {
                        model.chooseBackgroundChecks(BackgroundChecks.ON)
                    }
                }
                state.locationLines.forEach { NoticeLine(it, model::act) }
            }

            state.notices.forEach { NoticeLine(it, model::act) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = model::save, enabled = !state.busy) { Text(SAVE_LABEL) }
                OutlinedButton(
                    onClick = model::testConnection,
                    // The stored token only for the stored connection (I10): a changed form needs a typed one.
                    enabled = !state.busy && (token.isNotBlank() || state.testsStoredConnection),
                ) { Text(HA_TEST_CONNECTION) }
            }
            if (state.connected) {
                TextButton(onClick = model::askDisconnect, enabled = !state.busy) { Text(HA_DISCONNECT) }
            }
        }
    }

    state.disconnectAsk?.let { body ->
        AlertDialog(
            onDismissRequest = model::dismissDisconnect,
            text = { Text(body) },
            confirmButton = { TextButton(onClick = model::confirmDisconnect) { Text(HA_DISCONNECT) } },
            dismissButton = { TextButton(onClick = model::dismissDisconnect) { Text(CANCEL_BUTTON) } },
        )
    }
}

/** One radio row; the whole row is the target (the Settings screen's pattern). */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}
