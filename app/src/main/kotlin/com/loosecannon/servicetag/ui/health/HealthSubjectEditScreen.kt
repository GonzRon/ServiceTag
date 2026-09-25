package com.loosecannon.servicetag.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.health.HealthSubjectShape
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.asset.ChoiceRow
import com.loosecannon.servicetag.ui.asset.FieldLabel
import com.loosecannon.servicetag.ui.asset.RefusalLine
import com.loosecannon.servicetag.ui.asset.ratifiedParts
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.maintenance.LinkGuardDialog
import com.loosecannon.servicetag.ui.maintenance.LinkGuardPrompt
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.Eyebrow
import com.loosecannon.servicetag.ui.theme.MonoText

// The health subject editor's words (spec §10.7), RATIFIED, each by its S-number and verbatim. S123,
// S124, S128 and S136 are each one ratified set of words, split only at their "·" separators. S137 is
// B08's constant (`THE_SUBJECT_HEALTH_FOLLOWS`), drawn here through B08's own dialog.

/** The shipped field word. */
internal const val NAME = "Name"

/** S113, field. */
const val WHAT_IS_IT = "What is it?"

/** S114, option: [HealthSubjectKind.ASSET]. */
const val THE_WHOLE_ASSET = "The whole asset"

/** S115, option: [HealthSubjectKind.PART]. */
const val A_PART = "A part"

/** S116, option: [HealthSubjectKind.MEDIUM]. */
const val SOMETHING_MAINTAINED_LIKE_WATER = "Something maintained, like water"

/** S117, field. */
const val WHAT_WEARS_IT_DOWN = "What wears it down?"

/** S118, option: [HealthDriver.AGE]. */
const val AGE_SINCE_REPLACEMENT = "Age since replacement"

/** S119, option: [HealthDriver.MAINTENANCE_OVERDUE]. */
const val OVERDUE_MAINTENANCE = "Overdue maintenance"

/** S120, field, under S118. */
const val REPLACEMENT_QUICK_ACTION = "Replacement quick action"

/** S121, option: any REPLACEMENT event is the baseline. */
const val ANY_REPLACEMENT = "Any replacement"

/** S122, field, under S119. */
const val MAINTENANCE_SCHEDULE = "Maintenance schedule"

/** S123, the three threshold labels of an age subject, one ratified set. */
const val AGE_THRESHOLD_LABELS = "As new for (days) · Warning after (days) · Critical after (days)"

/** S124, the three threshold labels of an overdue subject, one ratified set. */
const val OVERDUE_THRESHOLD_LABELS =
    "Grace period (days overdue) · Warning at (days overdue) · Critical at (days overdue)"

/** S125, refusal: the thresholds do not rise strictly. */
const val EACH_NUMBER_MUST_BE_LARGER = "Each number must be larger than the one before."

/** S126, refusal: drawn while a threshold is empty and Save is held. */
const val ENTER_ALL_THREE_NUMBERS = "Enter all three numbers, or use a starting point."

/** S127, action: overdue maintenance only. */
const val USE_A_STARTING_POINT = "Use a starting point"

/** S128, the two starting points' names, one ratified set — see [StartingPoint]. */
const val STARTING_POINT_NAMES = "Engine service: 14 / 45 / 120 days overdue · Water care: 2 / 7 / 14 days overdue"

/** S129, confirmation body. */
const val STARTING_POINTS_ARE_NOT_SAFETY_LIMITS =
    "These are starting points, not safety limits. Check them for this equipment before saving."

/** S130, confirm: the only way a starting point fills the fields. */
const val USE_THESE_NUMBERS = "Use these numbers"

/** S133, field: a 1–10 stepper, only under Weighted average. */
const val WEIGHT = "Weight"

/** S135, refusal: `HEALTH_SCHEDULE_TAKEN`. */
const val SCHEDULE_ALREADY_DRIVES_ANOTHER_SUBJECT = "That schedule already drives another subject."

/** S136, the two actions, one ratified set. */
const val SUBJECT_ACTIONS = "Archive subject · Restore subject"

/** S136's first action. */
val ARCHIVE_SUBJECT: String = ratifiedParts(SUBJECT_ACTIONS)[0]

/** S136's second action; the asset editor marks an archived subject with it. */
val RESTORE_SUBJECT: String = ratifiedParts(SUBJECT_ACTIONS)[1]

/** S113's options in their ratified order. */
private val KIND_CHOICES = listOf(
    HealthSubjectKind.ASSET to THE_WHOLE_ASSET,
    HealthSubjectKind.PART to A_PART,
    HealthSubjectKind.MEDIUM to SOMETHING_MAINTAINED_LIKE_WATER,
)

/** S117's options in their ratified order. */
private val DRIVER_CHOICES = listOf(
    HealthDriver.AGE to AGE_SINCE_REPLACEMENT,
    HealthDriver.MAINTENANCE_OVERDUE to OVERDUE_MAINTENANCE,
)

/**
 * Creates ([subjectId] null) or edits one health subject of [assetId] (spec §10.4; master §13.3).
 *
 * What it is (S113), what wears it down (S117), and the link that driver needs: S120's REPLACEMENT
 * quick actions after S121, or S122's timed schedules. Three thresholds labelled by S123 or S124 start
 * **empty**; Save waits for all three with S126 under them, S125 says when they do not rise, and S127
 * offers S128's starting points, which fill the fields only through S129's S130 (Q-1, inv. 121). S133
 * appears only when the asset combines by Weighted average. An existing subject carries S136's action;
 * Restore is disabled while its link would be refused.
 *
 * The app bar names the asset, which is data; §10.7 ratifies no title for this screen. There is no
 * foot button, as in the schedule editor: the app bar's Save commits. Cancel writes nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthSubjectEditScreen(
    graph: AppGraph,
    assetId: String,
    subjectId: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val model: HealthSubjectEditViewModel = viewModel(key = subjectId ?: "new-subject-$assetId") {
        HealthSubjectEditViewModel(graph, assetId, subjectId)
    }
    val state by model.state.collectAsStateWithLifecycle()

    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(model) {
        model.saved.collect {
            if (!leaving) {
                leaving = true
                onDone()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.assetName.uppercase(),
                        style = Eyebrow,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    // Held until every answer is given (master dec. 46): no refusal of the form's own
                    // fields is reachable, so none needs a sentence.
                    TextButton(onClick = model::save, enabled = state.canSave) { Text("Save") }
                },
            )
        },
    ) { padding ->
        if (!state.loaded) return@Scaffold

        state.confirming?.let { point ->
            AlertDialog(
                onDismissRequest = model::cancelStartingPoint,
                title = { Text(point.label) },
                text = { Text(STARTING_POINTS_ARE_NOT_SAFETY_LIMITS) },
                confirmButton = { TextButton(onClick = model::confirmStartingPoint) { Text(USE_THESE_NUMBERS) } },
                dismissButton = { TextButton(onClick = model::cancelStartingPoint) { Text("Cancel") } },
            )
        }
        if (state.primaryRefused) {
            LinkGuardDialog(
                prompt = LinkGuardPrompt.Primary,
                onArchiveBoth = model::dismissPrimaryRefusal,
                onCancel = model::dismissPrimaryRefusal,
            )
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = state.name,
                onValueChange = model::onName,
                label = { Text(state.nameLabel) },
                singleLine = true,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            )

            FieldLabel(state.kindLabel)
            KIND_CHOICES.forEach { (kind, words) ->
                ChoiceRow(words, state.kind == kind) { model.onKind(kind) }
            }

            FieldLabel(state.driverLabel)
            DRIVER_CHOICES.forEach { (driver, words) ->
                ChoiceRow(words, state.driver == driver) { model.onDriver(driver) }
            }

            when (state.driver) {
                HealthDriver.AGE -> {
                    FieldLabel(state.baselineLabel)
                    state.baselineOptions.forEach { option ->
                        ChoiceRow(option.label, state.baseline == option.id) { model.onBaseline(option.id) }
                    }
                }
                HealthDriver.MAINTENANCE_OVERDUE -> {
                    FieldLabel(state.scheduleLabel)
                    state.timedSchedules.forEach { option ->
                        ChoiceRow(option.label, state.scheduleId == option.id) { model.onSchedule(option.id) }
                    }
                    // S135 is about this picker's schedule, so it is drawn under it and nowhere else.
                    if (state.scheduleTaken) RefusalLine(SCHEDULE_ALREADY_DRIVES_ANOTHER_SUBJECT)
                }
                null -> Unit
            }

            if (state.driver != null) Thresholds(state, model)
            if (state.weighted) WeightStepper(state.weight, model::onWeightStep)

            if (state.editing) {
                Spacer(Modifier.height(8.dp))
                if (state.archived) {
                    OutlinedButton(
                        onClick = model::restore,
                        enabled = state.restoreAllowed && !state.saving,
                        shape = ControlShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(RESTORE_SUBJECT) }
                } else {
                    OutlinedButton(
                        onClick = model::archive,
                        enabled = !state.saving,
                        shape = ControlShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(ARCHIVE_SUBJECT) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The three thresholds under their driver's labels, each through the digits filter capped at 36,500,
 * then S125 when they do not rise, S126 while one is empty, and S127 for an overdue subject.
 *
 * S125 is judged when a field is **left** — its focus lost — or when Save is tried, never keystroke by
 * keystroke: typing 45 after 14 passes through a 4 that is smaller, and that is not a refusal (the
 * controller's ruling on B10-M3).
 */
@Composable
private fun Thresholds(state: HealthSubjectEditState, model: HealthSubjectEditViewModel) {
    state.thresholdLabels.forEachIndexed { index, label ->
        var focused by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = state.thresholds[index],
            onValueChange = { model.onThreshold(index, it) },
            label = { Text(label) },
            singleLine = true,
            isError = state.orderRefused,
            textStyle = MonoText,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = ControlShape,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focus ->
                    if (focused && !focus.isFocused) model.commitThresholds()
                    focused = focus.isFocused
                },
        )
    }
    if (state.orderRefused) RefusalLine(EACH_NUMBER_MUST_BE_LARGER)
    if (state.thresholdsMissing) QuietLine(ENTER_ALL_THREE_NUMBERS)
    if (state.startingPointsOffered) StartingPoints(model::chooseStartingPoint)
}

/** S127 and S128's two names. Picking one opens S129; nothing is filled here. */
@Composable
private fun StartingPoints(onPick: (StartingPoint) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(USE_A_STARTING_POINT) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            StartingPoint.entries.forEach { point ->
                DropdownMenuItem(
                    text = { Text(point.label) },
                    onClick = {
                        open = false
                        onPick(point)
                    },
                )
            }
        }
    }
}

/**
 * S133 as a 1–10 stepper, never free text (master dec. 46). The two steps are the minus and plus signs,
 * which are symbols, not words; each is disabled at its end of `:core`'s own range,
 * [HealthSubjectShape.WEIGHTS], the one the subject command refuses outside.
 */
@Composable
private fun WeightStepper(weight: Int, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text = WEIGHT, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { onStep(-1) }, enabled = weight > HealthSubjectShape.WEIGHTS.first) { Text("−") }
        Text(text = weight.toString(), style = MonoText)
        TextButton(onClick = { onStep(1) }, enabled = weight < HealthSubjectShape.WEIGHTS.last) { Text("+") }
    }
}
