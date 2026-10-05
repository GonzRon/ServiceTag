package com.loosecannon.servicetag.ui.asset

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import com.loosecannon.servicetag.core.seasonsync.HaEntityCandidate
import com.loosecannon.servicetag.core.seasonsync.MAX_LISTED_ENTITIES
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.attachments.SAVE_LABEL
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.theme.ControlShape

/**
 * #16 (C27) — the setup sheet, opened by the card's Link (P16-22) or by Resume on an asset no longer MANUAL (C-4):
 * the entity (Link only), the asset's mode's sentence (P16-44/45/46), Save and Cancel (reused). Since #105 (B3) the
 * entity is chosen from a browser that is a mode of this same sheet (owner ruling Q6): a Choose entity row (P105-1)
 * opens it, a tap on a row picks the exact entity, and Enter entity ID manually (P105-3) keeps the typed field
 * (P16-42/43/49). Nothing is read until Choose entity or Refresh; nothing is written before Save. A refusal keeps the sheet open with its sentence; after a write out of YEAR_ROUND
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
    // Every way out but Save — Cancel, back, the scrim, a swipe — goes through the model first, so a list read still
    // running stops with the sheet (#105).
    val dismiss = {
        model.dismiss()
        done()
    }
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
            onDismissRequest = { if (!state.saving) dismiss() },
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { it != SheetValue.Hidden || !saving },
            ),
        ) {
            val browse = state.browse
            if (browse != null) {
                EntityBrowser(
                    browse = browse,
                    onQuery = model::onQuery,
                    onPick = model::pick,
                    onRefresh = model::refreshEntities,
                    onEnterManually = model::enterManually,
                    onBack = model::closeBrowse,
                )
            } else {
                LinkSeasonSyncForm(
                    state = state,
                    onEntityId = model::onEntityId,
                    onChooseEntity = model::chooseEntity,
                    onEnterManually = model::enterManually,
                    onSave = model::save,
                    onCancel = dismiss,
                )
            }
        }
    }
}

@Composable
private fun LinkSeasonSyncForm(
    state: LinkSeasonSyncState,
    onEntityId: (String) -> Unit,
    onChooseEntity: () -> Unit,
    onEnterManually: () -> Unit,
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
            if (state.manualEntry) {
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
                TextButton(onClick = onChooseEntity) { Text(SEASON_SYNC_CHOOSE_ENTITY) }
            } else {
                ChosenEntityRow(chosen = state.chosen, entityLine = state.entityLine, onChooseEntity = onChooseEntity)
                TextButton(onClick = onEnterManually) { Text(SEASON_SYNC_ENTER_MANUALLY) }
            }
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

/**
 * #105 — the form's entity row. Nothing chosen: Choose entity (P105-1) over P16-43's helper, the whole row a tap.
 * Chosen: the friendly name over the exact id (the id alone when Home Assistant gave no name) and Change (P105-2).
 * A shape refusal (P16-49) under it, as under the field, though a picked id cannot earn one.
 */
@Composable
private fun ChosenEntityRow(chosen: HaEntityCandidate?, entityLine: String?, onChooseEntity: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onChooseEntity)
            .padding(vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (chosen == null) {
                Text(SEASON_SYNC_CHOOSE_ENTITY, style = MaterialTheme.typography.titleSmall)
                QuietLine(SEASON_SYNC_ENTITY_ID_HELP)
            } else {
                Text(chosen.friendlyName ?: chosen.entityId, style = MaterialTheme.typography.titleSmall)
                if (chosen.friendlyName != null) QuietLine(chosen.entityId)
            }
            entityLine?.let { RefusalLine(it) }
        }
        if (chosen != null) {
            TextButton(onClick = onChooseEntity) { Text(SEASON_SYNC_CHANGE_ENTITY) }
        }
    }
}

/**
 * #105 — the browser, in the sheet's place while it is open: the title (P105-1), the search field (P105-4), the scope
 * line (P105-6), the loading line (P105-5) while a read runs, the latest read's ratified failure sentences over the
 * last good rows, P105-10 over a bounded list, P105-8 for an empty scope, P105-9 for a query that matches nothing,
 * then the rows — friendly name over id, a tap the pick — and Enter entity ID manually, Cancel (back to the form,
 * keeping what was chosen) and Refresh.
 */
@Composable
private fun EntityBrowser(
    browse: EntityBrowseState,
    onQuery: (String) -> Unit,
    onPick: (HaEntityCandidate) -> Unit,
    onRefresh: () -> Unit,
    onEnterManually: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(SEASON_SYNC_CHOOSE_ENTITY, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = browse.query,
            onValueChange = onQuery,
            placeholder = { Text(SEASON_SYNC_SEARCH_ENTITIES) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            shape = ControlShape,
            modifier = Modifier.fillMaxWidth(),
        )
        QuietLine(SEASON_SYNC_SCOPE_LINE)
        if (browse.loading) QuietLine(SEASON_SYNC_READING_ENTITIES)
        browse.failure.forEach { RefusalLine(it.text) }
        if (browse.truncatedList) QuietLine(seasonSyncOnlyFirstShown(MAX_LISTED_ENTITIES))
        if (browse.failure.isEmpty() && browse.scopeEmpty) QuietLine(SEASON_SYNC_NO_HELPERS)
        if (browse.noMatch) QuietLine(SEASON_SYNC_NO_MATCH)
        // Bounded, because a lazy list cannot measure inside a scrolling column without a height of its own.
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
            items(browse.rows, key = { it.entityId }) { row ->
                EntityRow(row = row, onClick = { onPick(row) })
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onEnterManually) { Text(SEASON_SYNC_ENTER_MANUALLY) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text(CANCEL_BUTTON) }
            TextButton(onClick = onRefresh, enabled = !browse.loading) { Text(SEASON_SYNC_REFRESH) }
        }
    }
}

/** One candidate: its friendly name over its exact id, or the id alone; the whole row is the pick. */
@Composable
private fun EntityRow(row: HaEntityCandidate, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(row.friendlyName ?: row.entityId, style = MaterialTheme.typography.titleSmall)
        if (row.friendlyName != null) QuietLine(row.entityId)
    }
}
