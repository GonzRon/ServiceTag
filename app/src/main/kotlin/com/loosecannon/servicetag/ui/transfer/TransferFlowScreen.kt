package com.loosecannon.servicetag.ui.transfer

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.maintenance.NOT_NOW
import com.loosecannon.servicetag.ui.maintenance.partOfLine
import com.loosecannon.servicetag.ui.theme.ControlShape

/**
 * #77 (C17, C18, C22) — the sender's flow in one destination: the selection tree, its review, then the ready screen
 * with Share, Save a copy and the mark question. Back from the review returns to the tree; back from the tree or the
 * ready screen leaves, and **leaving deletes the pack** (the pack model's `onCleared`, R77-18). Back is held only
 * while the mark is being written. A mark ends on the Assets list ([onMarked]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferFlowScreen(graph: AppGraph, preselect: String?, onBack: () -> Unit, onMarked: () -> Unit) {
    val selection: TransferSelectionViewModel = viewModel { TransferSelectionViewModel(graph, preselect) }
    val pack: TransferPackViewModel = viewModel {
        TransferPackViewModel(graph, runCatching { createSavedStateHandle() }.getOrElse { SavedStateHandle() })
    }
    val chosen by selection.state.collectAsStateWithLifecycle()
    val made by pack.state.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val context = LocalContext.current
    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) pack.saveCopy { context.contentResolver.openOutputStream(uri) }
    }
    LaunchedEffect(pack) {
        pack.events.collect { event ->
            when (event) {
                TransferPackEvent.Leave -> onBack()
                is TransferPackEvent.Say -> snackbars.showSnackbar(event.line)
            }
        }
    }
    // #84 C4 (77-2): a finished mark ends the flow from the state, collected or not. `switchTopLevel` clears and adds,
    // so a second call lands on the same Assets list.
    val marked = made.phase == PackPhase.MARKED
    LaunchedEffect(marked) { if (marked) onMarked() }
    val readyShown = made.phase == PackPhase.READY || made.phase == PackPhase.MARKING || made.phase == PackPhase.MARKED
    val back: () -> Unit = {
        if (!readyShown && chosen.review != null && made.phase != PackPhase.CREATING) {
            // mn-2: a refused Create's lines answered this review; they leave with it.
            pack.clearErrors()
            selection.backToSelection()
        } else {
            onBack()
        }
    }
    BackHandler(enabled = made.phase == PackPhase.MARKING) { }
    BackHandler(enabled = made.phase != PackPhase.MARKING, onBack = back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(TransferStrings.TRANSFER_ASSETS) },
                navigationIcon = {
                    IconButton(onClick = back, enabled = made.phase != PackPhase.MARKING) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.transfer_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        val review = chosen.review
        when {
            readyShown -> TransferReadyContent(
                state = made,
                onShare = {
                    pack.packFile()?.let { file ->
                        try {
                            context.startActivity(TransferShare.chooser(context, file))
                        } catch (e: ActivityNotFoundException) {
                            // R84-2: a recorded platform edge, deliberately silent. The system chooser owns the
                            // no-receiver case and there is no ServiceTag string for it; Save a copy still works.
                        }
                    }
                },
                onSaveCopy = { made.fileName?.let(saveCopy::launch) },
                onMark = pack::mark,
                onNotNow = pack::notNow,
                modifier = modifier,
            )
            review != null -> TransferReviewContent(
                review = review,
                creating = made.phase == PackPhase.CREATING,
                errors = made.errors,
                onNote = selection::onNote,
                onCreate = { pack.create(chosen.roots, review.note) },
                modifier = modifier,
            )
            else -> TransferSelectionContent(
                state = chosen,
                onToggle = { id -> pack.clearErrors(); selection.toggle(id) },
                onReview = { pack.clearErrors(); selection.review() },
                modifier = modifier,
            )
        }
    }
}

/**
 * The tree (C17), a pure function of [TransferSelectionState]: P77-2 and P77-3 over one row per asset not held, each
 * component indented under its parent with the shipped inline `Part of <parent>`. A forced component is checked,
 * disabled, and described P77-61; P77-56 when nothing can go. Review (P77-4) is disabled while nothing is checked.
 */
@Composable
fun TransferSelectionContent(
    state: TransferSelectionState,
    onToggle: (String) -> Unit,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text(text = TransferStrings.SELECT_WHAT_LEAVES, style = MaterialTheme.typography.titleMedium)
        QuietLine(TransferStrings.COMPONENTS_GO_WITH)
        if (state.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            return@Column
        }
        state.nothingToOffer?.let { line ->
            Text(text = line, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        state.choices.forEach { choice -> ChoiceRow(choice, onToggle) }
        Row {
            Spacer(Modifier.weight(1f))
            Button(onClick = onReview, enabled = state.canReview, shape = ControlShape) { Text(TransferStrings.REVIEW) }
        }
    }
}

@Composable
private fun ChoiceRow(choice: TransferChoice, onToggle: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = choice.checked, enabled = choice.enabled, role = Role.Checkbox) { onToggle(choice.id) }
            .then(
                choice.stateDescription?.let { described -> Modifier.semantics { stateDescription = described } }
                    ?: Modifier,
            )
            .padding(start = (choice.depth * 24).dp),
    ) {
        Checkbox(checked = choice.checked, onCheckedChange = null, enabled = choice.enabled)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = choice.name, style = MaterialTheme.typography.bodyLarge)
            choice.parentName?.let { parent ->
                Text(
                    text = partOfLine(parent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The review (C17), a pure function: P77-5, the count lines, P77-12, the note (P77-13, one line), every refusal —
 * which disables Create (P77-14) — and, while the pack is written, P77-19.
 */
@Composable
fun TransferReviewContent(
    review: TransferReview,
    creating: Boolean,
    errors: List<String>,
    onNote: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        SectionHeader(title = TransferStrings.TRANSFER_PACK)
        review.counts.forEach { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
        Text(text = TransferStrings.MAY_CONTAIN, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = review.note,
            onValueChange = onNote,
            label = { Text(TransferStrings.NOTE_LABEL) },
            singleLine = true,
            enabled = !creating,
            modifier = Modifier.fillMaxWidth(),
        )
        (review.refusals + errors).forEach { ErrorLine(it) }
        if (creating) {
            QuietLine(TransferStrings.CREATING)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row {
            Spacer(Modifier.weight(1f))
            Button(onClick = onCreate, enabled = review.canCreate && !creating, shape = ControlShape) {
                Text(TransferStrings.CREATE)
            }
        }
    }
}

/**
 * The ready screen (C18), a pure function of [TransferPackState]: P77-21, Share (P77-22), Save a copy (P77-23), P77-24
 * and P77-69; then the mark question — P77-27, Mark transferred (P77-28), P77-30 — and the reused `Not now`. A restored
 * screen whose file is gone shows P77-60 and offers none of the three actions.
 */
@Composable
fun TransferReadyContent(
    state: TransferPackState,
    onShare: () -> Unit,
    onSaveCopy: () -> Unit,
    onMark: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val marking = state.phase != PackPhase.READY
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text(text = TransferStrings.READY, style = MaterialTheme.typography.titleMedium)
        state.goneLine?.let { ErrorLine(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onShare, enabled = state.offersActions, shape = ControlShape) { Text(TransferStrings.SHARE) }
            OutlinedButton(onClick = onSaveCopy, enabled = state.offersActions, shape = ControlShape) {
                Text(TransferStrings.SAVE_A_COPY)
            }
        }
        state.sizeLine?.let { QuietLine(it) }
        QuietLine(TransferStrings.BEARER)
        Spacer(Modifier.heightIn(min = 8.dp))
        Text(text = TransferStrings.MARK_QUESTION, style = MaterialTheme.typography.bodyLarge)
        state.errors.forEach { ErrorLine(it) }
        if (marking) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onNotNow, enabled = !marking) { Text(NOT_NOW) }
            Button(onClick = onMark, enabled = state.offersActions, shape = ControlShape) { Text(TransferStrings.MARK) }
        }
        QuietLine(TransferStrings.MARK_CONSEQUENCE)
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}
