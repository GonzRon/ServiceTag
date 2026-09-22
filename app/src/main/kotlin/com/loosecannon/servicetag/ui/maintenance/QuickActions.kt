package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.ui.theme.ControlShape

/** F4's three RATIFIED labels, quoted verbatim (master plan §1.2). */
const val SCAN_TAG = "Scan tag"
const val ADD_ASSET = "Add asset"
const val LOG_MAINTENANCE = "Log maintenance"

/**
 * The Maintenance destination's three persistent quick actions (F4).
 *
 * **This file writes nothing, and that is the requirement rather than an accident.** "Scan tag" and
 * "Add asset" push the shipped routes. "Log maintenance" is an **entry point**, not a completion
 * mechanism: it opens B14's canonical `CompletionFlow` — the same one the schedule detail and
 * B09's scan sheet use, with the ratified "When was this done?" affordance, the meter prompt where
 * a rule requires a reading, and the profile form for a `FORM` schedule — and inserts no event and
 * closes no round of its own. A quick action that logged an event directly would be exactly the
 * second completion path #50 forbids, and this is the one place in 1.2 where one could plausibly
 * have appeared.
 *
 * The actions are persistent: they sit above the sections and do not scroll away with them, because
 * the three things an owner most often arrives here to do should not depend on where the list is.
 */
@Composable
fun QuickActions(
    onScanTag: () -> Unit,
    onAddAsset: () -> Unit,
    onLogMaintenance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onLogMaintenance, shape = ControlShape) { Text(LOG_MAINTENANCE) }
        OutlinedButton(onClick = onScanTag, shape = ControlShape) { Text(SCAN_TAG) }
        OutlinedButton(onClick = onAddAsset, shape = ControlShape) { Text(ADD_ASSET) }
    }
}
