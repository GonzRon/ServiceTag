package com.loosecannon.servicetag.ui.asset

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.ui.api.OPEN_APP_SETTINGS
import com.loosecannon.servicetag.ui.components.appDetails
import com.loosecannon.servicetag.ui.components.open
import com.loosecannon.servicetag.ui.homeassistant.HA_ALLOW_AGAIN
import com.loosecannon.servicetag.ui.homeassistant.Notice
import com.loosecannon.servicetag.ui.homeassistant.NoticeAction
import com.loosecannon.servicetag.ui.homeassistant.haBackgroundLocationWhy
import com.loosecannon.servicetag.ui.theme.ControlShape

/**
 * #16 (C27) — the season card's Home Assistant block, drawn under the season on **every** mode (C-4, R16-19), so a
 * stopped binding on a CALENDAR or YEAR_ROUND asset keeps its Resume. Everything it says is
 * [SeasonSyncBlockViewModel]'s state. "Allow again" re-runs the matching permission request (precise location; or
 * background location — P16-73 and the app's settings page on API 30+, the system dialog on 29), and every grant is
 * read again on resume. [onLink] and [onReconcile] open the setup sheet (B8b2); while null, neither action is drawn.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeasonSyncBlock(
    model: SeasonSyncBlockViewModel,
    state: SeasonSyncBlockState,
    onLink: (() -> Unit)? = null,
    onReconcile: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var askingBackground by remember { mutableStateOf(false) }
    val precise = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.refresh()
    }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.refresh()
    }
    LifecycleResumeEffect(model) {
        model.refresh()
        onPauseOrDispose {}
    }
    val act: (NoticeAction) -> Unit = { action ->
        when (action) {
            NoticeAction.ALLOW_PRECISE_AGAIN -> precise.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
            NoticeAction.ALLOW_BACKGROUND_AGAIN -> when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> askingBackground = true
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                else -> model.refresh()
            }
            NoticeAction.OPEN_APP_SETTINGS -> context.open(appDetails(context))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.kind) {
            SeasonSyncBlockKind.NONE -> if (state.offersLink && onLink != null) {
                OutlinedButton(onClick = onLink, shape = ControlShape) { Text(SEASON_SYNC_LINK) }
            }
            SeasonSyncBlockKind.ENABLED -> {
                if (state.offersControls) ModeControl(state.mode, enabled = !state.busy, onChoose = model::chooseMode)
                listOfNotNull(
                    state.sourceLine, state.lastSuccessLine, state.staleLine, state.haChangedLine, state.appliedLine,
                    state.stateLine,
                ).forEach { Line(it) }
                state.pausedLine?.let { NoticeLine(it, act) }
                state.errorLines.forEach { NoticeLine(it, act) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.offersControls) {
                        Button(onClick = model::syncNow, enabled = !state.busy, shape = ControlShape) {
                            Text(SEASON_SYNC_NOW)
                        }
                    }
                    if (state.offersStop) {
                        OutlinedButton(onClick = model::stopSyncing, enabled = !state.busy, shape = ControlShape) {
                            Text(SEASON_SYNC_STOP)
                        }
                    }
                }
            }
            SeasonSyncBlockKind.STOPPED -> {
                listOfNotNull(state.stoppedLine, state.stateLine).forEach { Line(it) }
                val resume = if (state.resumeOpensSheet) onReconcile else model::resumeSyncing
                if (state.offersResume && resume != null) {
                    OutlinedButton(onClick = resume, enabled = !state.busy, shape = ControlShape) {
                        Text(SEASON_SYNC_RESUME)
                    }
                }
            }
        }
        state.notices.forEach { NoticeLine(it, act) }
    }
    if (askingBackground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val label = context.packageManager.backgroundPermissionOptionLabel.toString()
        AlertDialog(
            onDismissRequest = { askingBackground = false },
            text = { Text(haBackgroundLocationWhy(label)) },
            confirmButton = {
                TextButton(onClick = {
                    askingBackground = false
                    context.open(appDetails(context))
                }) { Text(OPEN_APP_SETTINGS) }
            },
            dismissButton = { TextButton(onClick = { askingBackground = false }) { Text(CANCEL_BUTTON) } },
        )
    }
}

/** P16-23…25 as one choice, grouped under "Operating season" for TalkBack (§5). */
@Composable
private fun ModeControl(selected: SyncMode?, enabled: Boolean, onChoose: (SyncMode) -> Unit) {
    Column(Modifier.selectableGroup().semantics { contentDescription = OPERATING_SEASON }) {
        SyncMode.entries.forEach { mode ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = mode == selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onChoose(mode) },
                    ),
            ) {
                RadioButton(selected = mode == selected, onClick = null, enabled = enabled)
                Text(
                    text = seasonSyncModeLabel(mode),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Line(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
}

/** A ratified sentence and, when it has one, its button: P16-76 or "Open app settings". */
@Composable
private fun NoticeLine(notice: Notice, onAction: (NoticeAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Line(notice.text)
        notice.action?.let { action ->
            TextButton(onClick = { onAction(action) }) {
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
