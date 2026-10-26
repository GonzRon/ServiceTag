package com.loosecannon.servicetag.ui.asset

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.theme.ControlShape

/**
 * #113/#114 (C36) — the publishing setup sheet, opened by a block's switch: the helper, picked in #105's browser
 * scoped to the kind's domain ([entityScopeOf]) or typed (P113-2); Check helper (P113-25), a fresh read while P113-28
 * shows; its answer, or the confirm naming the exact destination and the mapping (P113-5) with Publish (P113-6). A
 * refusal keeps the sheet open with its sentence; nothing is written before Publish. **Insets (G28):** an input sheet
 * of group E — fully expanded, its content scrolling so Publish scrolls above the keyboard, Material's own keyboard
 * handling and no form insets. [opening] keys a fresh model for each opening, kept across a rotation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkPublishSheet(
    graph: AppGraph,
    assetId: String,
    kind: PublishKind,
    opening: Int,
    onDone: () -> Unit,
) {
    val words = publishWordsOf(kind) ?: return
    val model: LinkPublishViewModel = viewModel(key = "publish-sheet:$assetId:${kind.name}:$opening") {
        LinkPublishViewModel(graph, AssetId(assetId), kind)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val done by rememberUpdatedState(onDone)
    // Swipe, scrim and back all ask this first: Hidden is refused while Publish runs, so a refusal is never hidden.
    val enabling by rememberUpdatedState(state.enabling)
    val dismiss = {
        model.dismiss()
        done()
    }
    LaunchedEffect(state.finished) { if (state.finished) done() }
    ModalBottomSheet(
        onDismissRequest = { if (!state.enabling) dismiss() },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !enabling },
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
                scope = entityScopeOf(kind),
            )
        } else {
            LinkPublishForm(
                title = words.title,
                state = state,
                onEntityId = model::onEntityId,
                onChooseHelper = model::chooseHelper,
                onEnterManually = model::enterManually,
                onCheck = model::check,
                onPublish = model::publish,
                onCancel = dismiss,
            )
        }
    }
}

@Composable
private fun LinkPublishForm(
    title: String,
    state: LinkPublishState,
    onEntityId: (String) -> Unit,
    onChooseHelper: () -> Unit,
    onEnterManually: () -> Unit,
    onCheck: () -> Unit,
    onPublish: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (state.manualEntry) {
            OutlinedTextField(
                value = state.entityId,
                onValueChange = onEntityId,
                label = { Text(HA_PUBLISH_HELPER_FIELD) },
                supportingText = state.entityLine?.let { line -> { Text(line) } },
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
            TextButton(onClick = onChooseHelper) { Text(HA_PUBLISH_CHOOSE_HELPER) }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onChooseHelper)
                    .padding(vertical = 8.dp),
            ) {
                val chosen = state.chosen
                if (chosen == null) {
                    Text(HA_PUBLISH_CHOOSE_HELPER, style = MaterialTheme.typography.titleSmall)
                    QuietLine(HA_PUBLISH_HELPER_FIELD)
                } else {
                    Text(chosen.friendlyName ?: chosen.entityId, style = MaterialTheme.typography.titleSmall)
                    if (chosen.friendlyName != null) QuietLine(chosen.entityId)
                }
                state.entityLine?.let { RefusalLine(it) }
            }
            TextButton(onClick = onEnterManually) { Text(SEASON_SYNC_ENTER_MANUALLY) }
        }
        if (state.checking) QuietLine(HA_PUBLISH_CHECKING)
        state.answer.forEach { RefusalLine(it) }
        state.confirm?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        state.refusal?.let { RefusalLine(it) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel, enabled = !state.enabling) { Text(CANCEL_BUTTON) }
            if (state.confirm == null) {
                Button(onClick = onCheck, enabled = state.canCheck, shape = ControlShape) { Text(HA_PUBLISH_CHECK_HELPER) }
            } else {
                Button(onClick = onPublish, enabled = state.canPublish, shape = ControlShape) {
                    Text(HA_PUBLISH_CONFIRM_BUTTON)
                }
            }
        }
    }
}
