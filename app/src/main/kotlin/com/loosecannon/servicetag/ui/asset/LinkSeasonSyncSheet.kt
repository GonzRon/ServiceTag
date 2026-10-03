package com.loosecannon.servicetag.ui.asset

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.attachments.SAVE_LABEL
import com.loosecannon.servicetag.ui.theme.ControlShape

/**
 * #16 (C27) — the setup sheet, opened by the card's Link (P16-22) or by Resume on an asset no longer MANUAL (C-4):
 * Entity ID (P16-42, helper P16-43; Link only), the asset's mode's sentence (P16-44/45/46), Save and Cancel (reused).
 * Nothing is written before Save. A refusal keeps the sheet open with its sentence; after a write out of YEAR_ROUND
 * #78's question (P78-1a/1b, P78-2, P78-3) takes the sheet's place, and dismissing it answers P78-3, as in the
 * editor. [opening] keys a fresh model for each opening, kept across a rotation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkSeasonSyncSheet(
    graph: AppGraph,
    assetId: String,
    purpose: SeasonSyncSheetPurpose,
    opening: Int,
    onReviewSchedules: () -> Unit,
    onDone: () -> Unit,
) {
    val model: LinkSeasonSyncViewModel = viewModel(key = "season-sync-sheet:$assetId:$opening") {
        LinkSeasonSyncViewModel(graph, AssetId(assetId), purpose)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val review by rememberUpdatedState(onReviewSchedules)
    val done by rememberUpdatedState(onDone)
    // Swipe, scrim and back all ask this first: Hidden is refused while saving, so a refusal is never left in a hidden
    // sheet (MaterializeSheet's idiom).
    val saving by rememberUpdatedState(state.saving)
    LaunchedEffect(state.finished) {
        when (state.finished) {
            SeasonSheetExit.CLOSED -> done()
            SeasonSheetExit.REVIEW_SCHEDULES -> {
                review()
                done()
            }
            null -> Unit
        }
    }
    when (val ask = state.prompt) {
        is EditPrompt.ReconcileSchedules -> AlertDialog(
            onDismissRequest = model::keepSchedules,
            text = { Text(notTiedToSeason(ask.count)) },
            confirmButton = { TextButton(onClick = model::reviewSchedules) { Text(REVIEW_MAINTENANCE_SCHEDULES) } },
            dismissButton = { TextButton(onClick = model::keepSchedules) { Text(KEEP_SCHEDULES_AS_IS) } },
        )
        null -> ModalBottomSheet(
            onDismissRequest = { if (!state.saving) onDone() },
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { it != SheetValue.Hidden || !saving },
            ),
        ) {
            LinkSeasonSyncForm(state, model::onEntityId, model::save, onCancel = onDone)
        }
    }
}

@Composable
private fun LinkSeasonSyncForm(
    state: LinkSeasonSyncState,
    onEntityId: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        if (state.purpose == SeasonSyncSheetPurpose.LINK) {
            OutlinedTextField(
                value = state.entityId,
                onValueChange = onEntityId,
                label = { Text(SEASON_SYNC_ENTITY_ID) },
                supportingText = { Text(state.entityLine ?: SEASON_SYNC_ENTITY_ID_HELP) },
                isError = state.entityLine != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                ),
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.sentence?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        state.refusal?.let { RefusalLine(it) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel, enabled = !state.saving) { Text(CANCEL_BUTTON) }
            Button(onClick = onSave, enabled = state.canSave, shape = ControlShape) { Text(SAVE_LABEL) }
        }
    }
}
