package com.loosecannon.servicetag.ui.asset

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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.ui.homeassistant.LocationAsks
import com.loosecannon.servicetag.ui.homeassistant.NoticeAction
import com.loosecannon.servicetag.ui.homeassistant.NoticeLine
import com.loosecannon.servicetag.ui.theme.ControlShape

/**
 * #16 (C27) — the season card's Home Assistant block, drawn under the season on **every** mode (C-4, R16-19), so a
 * stopped binding on a CALENDAR or YEAR_ROUND asset keeps its Resume. Everything it says is
 * [SeasonSyncBlockViewModel]'s state. "Allow again" re-runs the matching flow the model decides (P16-65 then precise
 * location; or, after the foreground grant, P16-73 and the app's settings page on API 30+, the system dialog on 29),
 * launched by [LocationAsks], which the Home Assistant screen shares; every grant is read again on resume. [onLink]
 * and [onReconcile] open the setup sheet (B8b2); while null, neither action is drawn.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeasonSyncBlock(
    model: SeasonSyncBlockViewModel,
    state: SeasonSyncBlockState,
    onLink: (() -> Unit)? = null,
    onReconcile: (() -> Unit)? = null,
) {
    LocationAsks(
        requests = model.requests,
        backgroundAsk = state.backgroundAsk,
        onAnswered = model::onPermissionAnswered,
        onResumed = model::onResumed,
        onAcceptBackgroundAsk = model::acceptBackgroundAsk,
        onDeclineBackgroundAsk = model::declineBackgroundAsk,
    )
    val act: (NoticeAction) -> Unit = { model.act(it) }
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
