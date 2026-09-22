package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.LabelValue
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.StatusBadge
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.LocalServiceTagSemanticColors

/**
 * The scan completion sheet (#50, spec §2.8, D5 §7A), titled **"Maintenance"**.
 *
 * It is reached **only** from a `Resolution.OpenAsset` that has actionable work — every other
 * resolution routes exactly as it does today (invariant 58) — and it opens **before** the ordinary
 * detail path, which **"Open asset"** always still reaches. The scan that led here mutated nothing:
 * `ResolveTag`'s `lastScannedAt` write is informational and is the only write on the way in
 * (invariant 57, #50 AC 11), and everything this screen writes follows an explicit tap.
 *
 * **It reads no tags.** `Route.MaintenanceSheet` is deliberately not in `Route.readsTags()` and
 * this file installs no tag sink: the hold for the read that got here belongs to `Route.Scan` or to
 * the ambient trampoline, and holding reader mode over a screen with no sink is the #37
 * re-dispatch 2.7 removed.
 *
 * Nothing here is a second completion path. Every completion goes through B14's [CompletionFlow]
 * and its ratified **"When was this done?"**, so a backdated completion is first class and a `FORM`
 * schedule leaves through [onLogForm] with nothing fabricated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaintenanceSheet(
    graph: AppGraph,
    assetId: String,
    tagId: String?,
    onOpenAsset: (String) -> Unit,
    onReviewSchedule: (String) -> Unit,
    onLogForm: (assetId: String, profileId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val model: MaintenanceSheetViewModel = viewModel(key = "sheet/$assetId/$tagId") {
        MaintenanceSheetViewModel(graph, assetId, tagId)
    }
    val state by model.state.collectAsStateWithLifecycle()
    var postponing by remember { mutableStateOf<ScheduleId?>(null) }

    // Coming back from a profile form is how the sequential run advances, so the sheet re-derives
    // on every resume rather than trusting the list it drew before it left.
    LifecycleResumeEffect(model) {
        model.refresh()
        onPauseOrDispose { }
    }

    LaunchedEffect(model) {
        model.needsForm.collect { form -> onLogForm(form.assetId.value, form.profileId?.value) }
    }

    // The route is reached only with actionable work, so an empty **first** load is a back stack
    // restored into an asset whose work has since been done: that is the "no actionable work opens
    // the asset as today" case (#50 AC 1) and it is answered the same way.
    LaunchedEffect(state.loaded, state.emptyOnArrival) {
        if (state.loaded && state.emptyOnArrival) onOpenAsset(assetId)
    }

    // The one affordance, hosted here so the sheet asks "when was this done" in the same words the
    // schedule detail and the quick action ask it in.
    CompletionFlowHost(model.completion)

    Scaffold(topBar = { TopAppBar(title = { Text(MAINTENANCE_SHEET_TITLE) }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = state.assetName, style = MaterialTheme.typography.titleLarge)
            // #49 AC 3: which scan point this is, when the owner labelled it — and a second tag on
            // the same Asset shows the same work under its **own** label.
            state.tagPlacement?.let { LabelValue(label = TAG_PLACEMENT, value = it) }

            state.items.forEach { item ->
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SheetItemRow(
                    item = item,
                    checked = item.scheduleId.value in state.selected,
                    busy = state.busy,
                    onCheck = { model.toggle(item.scheduleId) },
                    onReview = { onReviewSchedule(item.scheduleId.value) },
                    onSnooze = { model.snooze(item.scheduleId) },
                    onPostpone = { postponing = item.scheduleId },
                    onRepair = { model.repair(item.scheduleId) },
                )
            }

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Button(
                onClick = model::completeSelected,
                enabled = state.canComplete && !state.busy,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(COMPLETE_SELECTED) }
            OutlinedButton(
                onClick = { onOpenAsset(assetId) },
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(OPEN_ASSET) }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(NOT_NOW) }
        }
    }

    postponing?.let { scheduleId ->
        val initial = state.items.firstOrNull { it.scheduleId == scheduleId }?.effectiveDueOn.orEmpty()
        PostponeDialog(
            initial = initial,
            onDismiss = { postponing = null },
            onConfirm = { dueOn ->
                postponing = null
                model.postpone(scheduleId, dueOn)
            },
        )
    }
}

/**
 * One actionable item: everything D5 §7A `:219-221` says the owner needs to decide, and the three
 * actions that are **not** a completion.
 *
 * Quick versus form is drawn **before** selection, because "one tap" and "the full form" are
 * different amounts of the owner's attention and the decision to select is made knowing which.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetItemRow(
    item: SheetItem,
    checked: Boolean,
    busy: Boolean,
    onCheck: () -> Unit,
    onReview: () -> Unit,
    onSnooze: () -> Unit,
    onPostpone: () -> Unit,
    onRepair: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (item.selectable) {
            Checkbox(checked = checked, onCheckedChange = { onCheck() }, enabled = !busy)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f, fill = false),
                )
                StatusBadge(
                    label = item.statusWord,
                    colors = statusColors(item.status, LocalServiceTagSemanticColors.current),
                    icon = statusIcon(item.status),
                )
            }
            item.whyNow?.let { QuietLine(it) }
            item.meter?.let { QuietLine(it) }
            item.progress?.let { QuietLine(it) }
            item.lastCompletedOn?.let { done ->
                val readings = item.lastReadings.joinToString(" · ")
                QuietLine(if (readings.isEmpty()) done else "$done · $readings")
            }
            QuietLine(item.completionTakes)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.repairOnly) {
                    TextButton(onClick = onRepair, enabled = !busy, shape = ControlShape) {
                        Text(LOG_METER_READING)
                    }
                }
                TextButton(onClick = onReview, shape = ControlShape) { Text(REVIEW_MAINTENANCE) }
                if (item.canSnooze) {
                    TextButton(onClick = onSnooze, enabled = !busy, shape = ControlShape) { Text(SNOOZE) }
                }
                if (item.canPostpone) {
                    TextButton(onClick = onPostpone, enabled = !busy, shape = ControlShape) { Text(POSTPONE) }
                }
            }
        }
    }
}
