package com.loosecannon.servicetag.ui.homeassistant

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.loosecannon.servicetag.ui.api.OPEN_APP_SETTINGS
import com.loosecannon.servicetag.ui.asset.CANCEL_BUTTON
import com.loosecannon.servicetag.ui.components.appDetails
import com.loosecannon.servicetag.ui.components.open
import kotlinx.coroutines.flow.Flow

/**
 * #16 (C26, C27, C32) — the location asks the Home Assistant screen and the season card share: it launches each
 * [requests] item its model queues (precise and approximate together, background location on API 29, the app's
 * settings page), reports every answer back ([onAnswered]) and every resume ([onResumed]), and draws P16-73 while
 * [backgroundAsk] is set, with Open app settings and Cancel. The model decides what to ask; this only asks.
 */
@Composable
internal fun LocationAsks(
    requests: Flow<PermissionRequest>,
    backgroundAsk: String?,
    onAnswered: () -> Unit,
    onResumed: () -> Unit,
    onAcceptBackgroundAsk: () -> Unit,
    onDeclineBackgroundAsk: () -> Unit,
) {
    val context = LocalContext.current
    val precise = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onAnswered()
    }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onAnswered() }
    LaunchedEffect(requests) {
        requests.collect { request ->
            when (request) {
                PermissionRequest.PRECISE_LOCATION -> precise.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
                PermissionRequest.BACKGROUND_LOCATION ->
                    background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                PermissionRequest.APP_SETTINGS -> context.open(appDetails(context))
            }
        }
    }
    LifecycleResumeEffect(requests) {
        onResumed()
        onPauseOrDispose {}
    }
    backgroundAsk?.let { body ->
        AlertDialog(
            onDismissRequest = onDeclineBackgroundAsk,
            text = { Text(body) },
            confirmButton = { TextButton(onClick = onAcceptBackgroundAsk) { Text(OPEN_APP_SETTINGS) } },
            dismissButton = { TextButton(onClick = onDeclineBackgroundAsk) { Text(CANCEL_BUTTON) } },
        )
    }
}

/** One ratified line and, when it has one, its button: Allow again (P16-76) or Open app settings. */
@Composable
internal fun NoticeLine(notice: Notice, onAction: (NoticeAction) -> Unit) {
    Column {
        Text(notice.text, style = MaterialTheme.typography.bodyMedium)
        notice.action?.let { action ->
            TextButton(
                onClick = { onAction(action) },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) {
                Text(
                    when (action) {
                        NoticeAction.ALLOW_PRECISE_AGAIN, NoticeAction.ALLOW_BACKGROUND_AGAIN -> HA_ALLOW_AGAIN
                        NoticeAction.OPEN_APP_SETTINGS -> OPEN_APP_SETTINGS
                    },
                )
            }
        }
    }
}
