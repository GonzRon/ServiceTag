package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.asset.DateField
import com.loosecannon.servicetag.ui.components.LabelValue
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.components.StatusBadge
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.Eyebrow
import com.loosecannon.servicetag.ui.theme.LocalServiceTagSemanticColors

/** RATIFIED (master plan §17): the two operation labels that are not a completion. */
const val SNOOZE = "Snooze"
const val POSTPONE = "Postpone"

/**
 * One schedule, in full, and the five operations.
 *
 * **Each action does exactly its own thing and no two of them collapse.** "Log maintenance" opens
 * the canonical [CompletionFlow] — the same affordance the scan sheet and the quick action use, and
 * the only thing on this screen that writes an event. "Snooze" writes a device-local instant.
 * "Postpone" moves this occurrence and nothing else, and is **not offered at all** for a
 * meter-only schedule, which has no occurrence date to move. "Close this round" is offered only on
 * a group round that is open, obliges somebody and is unfinished. The recurrence edit is the editor,
 * and it is the only path to a rule column.
 *
 * The closure history below is **read-only**: no edit, no delete, nothing that amends an immutable
 * row (invariant 43).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleDetailScreen(
    graph: AppGraph,
    scheduleId: String,
    onBack: () -> Unit,
    onEditRecurrence: (String) -> Unit,
    onLogForm: (assetId: String, profileId: String?) -> Unit,
) {
    val model: ScheduleDetailViewModel =
        viewModel(key = scheduleId) { ScheduleDetailViewModel(graph, scheduleId) }
    val state by model.state.collectAsStateWithLifecycle()

    // Coming back from the editor or the profile form is the moment to re-derive: the rule may have
    // changed, and the due date this screen shows is derived from it.
    LifecycleResumeEffect(model) {
        model.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(model) {
        model.needsForm.collect { form -> onLogForm(form.assetId.value, form.profileId?.value) }
    }

    var closing by remember { mutableStateOf(false) }
    var postponing by remember { mutableStateOf(false) }
    val selected = remember { mutableStateOf(setOf<String>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.targetName.uppercase(),
                            style = Eyebrow,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(text = state.title, style = MaterialTheme.typography.titleMedium)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { onEditRecurrence(scheduleId) }) { Text("Edit") }
                    DetailOverflow(
                        paused = state.paused,
                        archived = state.archived,
                        onPause = { model.pause(!state.paused) },
                        onArchive = { model.archive(!state.archived) },
                    )
                },
            )
        },
    ) { padding ->
        if (!state.loaded) {
            QuietLine("Loading…", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        if (state.missing) {
            // The schedule has gone — an import, or another device. Nothing to say that the empty
            // screen does not already say, and §17 ratifies no line for it.
            Spacer(Modifier.padding(padding))
            return@Scaffold
        }

        if (closing) {
            val openOn = state.roundOpenOn
            if (openOn == null) {
                closing = false
            } else {
                CloseRoundDialog(
                    openOn = openOn,
                    today = state.today,
                    onDismiss = { closing = false },
                    onConfirm = { closedOn ->
                        closing = false
                        model.closeRound(closedOn)
                    },
                )
            }
        }
        if (postponing) {
            PostponeDialog(
                initial = state.effectiveDueOn ?: state.today.toString(),
                onDismiss = { postponing = false },
                onConfirm = { dueOn ->
                    postponing = false
                    model.postpone(dueOn)
                },
            )
        }

        // The affordance, wherever this schedule is completed from. It is the flow's own dialog, so
        // this screen and the scan sheet ask the same question in the same words.
        CompletionFlowHost(model.completion)

        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            // A round that obliges nobody carries no status treatment at all: §17 ratifies no word
            // for it, and "NO BASELINE" belongs to the repairable meter form alone (§17.1a).
            state.statusWord?.let { word ->
                val colors = statusColors(state.status!!, LocalServiceTagSemanticColors.current)
                StatusBadge(label = word, colors = colors, icon = statusIcon(state.status!!))
                Spacer(Modifier.height(8.dp))
            }
            state.effectiveDueOn?.let { LabelValue(label = "Date", value = it) }
            state.postponedDueOn?.let { LabelValue(label = POSTPONE, value = it) }
            state.description.takeIf { it.isNotBlank() }?.let { QuietLine(it) }
            state.progress?.let { QuietLine(it) }

            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.canComplete) {
                    if (state.isGroup) {
                        // The two RATIFIED group labels. "Complete all" derives its own member list
                        // inside the use case, so a surface cannot complete somebody the round does
                        // not oblige (invariants 28, 29).
                        Button(
                            onClick = model::completeAll,
                            enabled = !state.busy,
                            shape = ControlShape,
                        ) { Text(COMPLETE_ALL) }
                        OutlinedButton(
                            onClick = {
                                model.completeSelected(
                                    state.members
                                        .filter { !it.complete && it.assetId.value in selected.value }
                                        .map { it.assetId },
                                )
                                selected.value = emptySet()
                            },
                            enabled = !state.busy && selected.value.isNotEmpty(),
                            shape = ControlShape,
                        ) { Text(COMPLETE_SELECTED) }
                    } else {
                        // The RATIFIED entry-point label for the canonical flow (F4's third action),
                        // which is exactly what this button is: it opens the flow and writes nothing
                        // of its own.
                        Button(
                            onClick = { model.complete() },
                            enabled = !state.busy,
                            shape = ControlShape,
                        ) { Text(LOG_MAINTENANCE) }
                    }
                }
                OutlinedButton(
                    onClick = model::snooze,
                    enabled = !state.busy && !state.archived,
                    shape = ControlShape,
                ) { Text(SNOOZE) }
                if (state.canPostpone) {
                    OutlinedButton(
                        onClick = { postponing = true },
                        enabled = !state.busy,
                        shape = ControlShape,
                    ) { Text(POSTPONE) }
                }
                if (state.canClearPostponement) {
                    OutlinedButton(
                        onClick = model::clearPostponement,
                        enabled = !state.busy,
                        shape = ControlShape,
                    ) { Text("Remove") }
                }
                if (state.canClose) {
                    OutlinedButton(
                        onClick = { closing = true },
                        enabled = !state.busy,
                        shape = ControlShape,
                    ) { Text(CLOSE_THIS_ROUND) }
                }
            }

            if (state.isGroup && state.members.isNotEmpty()) {
                MaintenanceSectionTitle(GROUPS_SECTION)
                state.members.forEach { member ->
                    MemberRow(
                        name = member.name,
                        complete = member.complete,
                        checked = member.assetId.value in selected.value,
                        onCheck = { checked ->
                            selected.value = if (checked) {
                                selected.value + member.assetId.value
                            } else {
                                selected.value - member.assetId.value
                            }
                        },
                        onComplete = { model.complete(AssetId(member.assetId.value)) },
                        busy = state.busy,
                    )
                }
            }

            SectionHeader(title = "Service record")
            if (state.history.isEmpty()) {
                QuietLine("No service recorded yet")
            } else {
                state.history.forEach { row ->
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    LabelValue(label = row.assetName, value = row.occurredOn)
                }
            }

            if (state.closures.isNotEmpty()) {
                // Immutable, exported history. There is deliberately no affordance here at all: no
                // edit, no delete, nothing that could amend a row the whole design forbids amending.
                MaintenanceSectionTitle(CLOSE_THIS_ROUND)
                state.closures.forEach { row ->
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    LabelValue(label = row.occurrenceOn, value = row.closedOn)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One member of the round: its name, whether it is done, and the two ways to record it. */
@Composable
private fun MemberRow(
    name: String,
    complete: Boolean,
    checked: Boolean,
    onCheck: (Boolean) -> Unit,
    onComplete: () -> Unit,
    busy: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Checkbox(checked = complete || checked, onCheckedChange = onCheck, enabled = !complete && !busy)
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (!complete) {
            TextButton(onClick = onComplete, enabled = !busy) { Text(LOG_MAINTENANCE) }
        }
    }
}

/**
 * **"Postpone"**: this occurrence's date, and nothing else.
 *
 * Unbounded on purpose, and the opposite of the close dialog's picker: a postponement is a one-off
 * override the owner can move again or remove, whereas a closure can never be amended — so the one
 * that is permanent is the one that is bounded (invariant 78, and spec §2.1's single override).
 */
@Composable
private fun PostponeDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var dueOn by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(POSTPONE) },
        text = { DateField(value = dueOn, onValueChange = { dueOn = it }, label = "Date") },
        confirmButton = { TextButton(onClick = { onConfirm(dueOn) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Pause and archive, in the shipped overflow idiom.
 *
 * The pause row is labelled with the RATIFIED status word **PAUSED**, because §17 ratifies no verb
 * for the action and the word for the state is the one thing this brief may say; archive reuses the
 * shipped "Archive"/"Unarchive" pair. **Neither is destructive**: an archived schedule keeps its
 * history and its closures and can be brought back (invariant 76).
 */
@Composable
private fun DetailOverflow(
    paused: Boolean,
    archived: Boolean,
    onPause: () -> Unit,
    onArchive: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(statusLabel(com.loosecannon.servicetag.core.schedule.DueStatus.PAUSED)) },
            trailingIcon = { Checkbox(checked = paused, onCheckedChange = null) },
            onClick = { open = false; onPause() },
        )
        DropdownMenuItem(
            text = { Text(if (archived) "Unarchive" else "Archive") },
            onClick = { open = false; onArchive() },
        )
    }
}
