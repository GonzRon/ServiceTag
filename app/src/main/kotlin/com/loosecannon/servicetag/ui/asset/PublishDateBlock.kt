package com.loosecannon.servicetag.ui.asset

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.ui.theme.ControlShape

/**
 * #114 (C34, C36, F-16) — the Season Start date block's own lines inside [PublishBlock]'s frame: the "may still allow
 * starts" line beside the binding's own while its `1970-01-01` is not read back, then each obligation this asset still
 * owes a helper it let go of. Nothing here is drawn for the status kind, whose state carries neither.
 */
@Composable
internal fun PublishDateLines(model: PublishBlockViewModel, state: PublishBlockState) {
    state.invalidationLine?.let { RefusalLine(it) }
    state.tombstones.forEach { line ->
        PublishTombstoneRow(line, enabled = !state.busy, onDismiss = model::dismissTombstone)
    }
}

/**
 * #114 (C34) — one obligation as drawn, on an asset's date block or the Home Assistant screen: its sentence and, once
 * ServiceTag can no longer reach the helper, P114-38 — the owner's own acknowledgement that it is cleared, by the
 * tombstone's id. Nothing else lets an obligation go.
 */
@Composable
internal fun PublishTombstoneRow(line: PublishTombstoneLine, enabled: Boolean, onDismiss: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RefusalLine(line.text)
        if (line.dismissible) {
            OutlinedButton(onClick = { onDismiss(line.id) }, enabled = enabled, shape = ControlShape) {
                Text(HA_PUBLISH_CLEARED_BY_HAND)
            }
        }
    }
}
