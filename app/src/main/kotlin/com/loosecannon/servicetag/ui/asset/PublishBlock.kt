package com.loosecannon.servicetag.ui.asset

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.theme.ControlShape

/**
 * #113/#114 (C36) — one publishing block on asset detail, the frame for both kinds: the title and its switch (on: the
 * setup sheet, [onSetUp]; off: the stop confirm), the destination, the mapping, the state's line, the published and
 * verified lines apart, the current error as a fault or a fact, the date kind's own lines ([PublishDateLines]), the
 * delivery limit line, and Publish now, Check helper and Stop publishing. Everything it says is [PublishBlockViewModel]'s state; nothing is drawn while
 * [PublishBlockState.drawn] is false.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PublishBlock(model: PublishBlockViewModel, state: PublishBlockState, onSetUp: () -> Unit) {
    if (!state.drawn) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(state.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(
                checked = state.enabled,
                onCheckedChange = { on -> if (on) onSetUp() else model.askStop() },
                enabled = !state.busy && if (state.enabled) state.offersStop else state.offersSetUp,
            )
        }
        state.destination?.let { entityId ->
            Column {
                QuietLine(HA_PUBLISH_HELPER_FIELD)
                Text(entityId, style = MaterialTheme.typography.bodyMedium)
            }
        }
        state.mapping.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(row.condition, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(row.option, style = MaterialTheme.typography.bodyMedium)
            }
        }
        listOfNotNull(state.stateLine, state.publishedLine, state.verifiedLine).forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        state.factLines.forEach { QuietLine(it) }
        state.faultLines.forEach { RefusalLine(it) }
        // #114: the date kind's "may still allow starts" line and its obligations (P114-36…38).
        PublishDateLines(model, state)
        state.limitLine?.let { QuietLine(it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.offersPublishNow) {
                Button(onClick = model::publishNow, enabled = !state.busy, shape = ControlShape) { Text(HA_PUBLISH_NOW) }
            }
            if (state.offersCheck) {
                OutlinedButton(onClick = model::checkHelper, enabled = !state.busy, shape = ControlShape) {
                    Text(HA_PUBLISH_CHECK_HELPER)
                }
            }
            // P113-21: a copied setup is checked and turned on again here, through the sheet.
            if (state.enabled && state.offersSetUp) {
                OutlinedButton(onClick = onSetUp, enabled = !state.busy, shape = ControlShape) {
                    Text(HA_PUBLISH_CHECK_HELPER)
                }
            }
            if (state.enabled && state.offersStop) {
                OutlinedButton(onClick = model::askStop, enabled = !state.busy, shape = ControlShape) {
                    Text(HA_PUBLISH_STOP)
                }
            }
        }
        state.notices.forEach { RefusalLine(it) }
    }
    state.stopAsk?.let { ask ->
        AlertDialog(
            onDismissRequest = model::dismissStop,
            text = { Text(ask) },
            confirmButton = { TextButton(onClick = model::confirmStop) { Text(HA_PUBLISH_STOP) } },
            dismissButton = { TextButton(onClick = model::dismissStop) { Text(CANCEL_BUTTON) } },
        )
    }
}
