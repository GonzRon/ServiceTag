package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.reminders.ProviderId
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.reminders.NOTIFICATION_PERMISSION_RATIONALE
import com.loosecannon.servicetag.ui.asset.DateField
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.Eyebrow

// The editor's RATIFIED field labels and options, verbatim (master plan §17, §17.1c). D-24 ratified
// the five time-rule fragments; the gate ratified the rest. Every one of them is used exactly as
// written: a placeholder in a label — "Every N", "Every <n> <unit> of use" — is the label of the
// control that supplies it, because paraphrasing a ratified string to read more smoothly is the one
// thing no brief may do.
const val THIS_APPLIES_TO = "This applies to"
const val ONE_ASSET = "One asset"
const val A_MAINTENANCE_GROUP = "A maintenance group"
const val EVERY_N = "Every N"
const val REPEATS_FROM = "Repeats from"
const val THE_SCHEDULED_DATE = "the scheduled date"
const val WHEN_I_COMPLETE_IT = "when I complete it"
const val REMIND_ME_N_DAYS_EARLY = "Remind me N days early"
const val ALSO_DUE_BY_USE = "Also due by use"
const val EVERY_N_UNIT_OF_USE = "Every <n> <unit> of use"
const val LAST_DONE_AT = "Last done at"
const val REMIND_ME_N_UNIT_EARLY = "Remind me <n> <unit> early"
const val OUT_OF_SEASON_FIELD = "Out of season"
const val PAUSE_WITH_THE_SEASON = "Pause with the asset's season"
const val REMIND_ME_YEAR_ROUND = "Remind me year round"
const val COMPLETING_THIS_TAKES = "Completing this takes"
const val ONE_TAP = "One tap"
const val THE_FULL_FORM = "The full form"
const val USE_THIS_FORM = "Use this form"
const val REMIND_ME_THROUGH = "Remind me through"

/** D-11's non-blocking line, RATIFIED (§17). Shown, dismissible by fixing the title, never a gate. */
const val SIMILAR_THROUGH_A_GROUP = "This asset already has a similar schedule through another group."

/**
 * Create ([scheduleId] null) or edit one schedule, in the shape of the shipped editors.
 *
 * **The three group hides are the point of this screen.** A group target draws no meter block, no
 * profile picker and no "Pause with the asset's season" option, so the illegal group schedule D-12
 * and D-28 forbid is unreachable here rather than merely refused on save — and the target itself is
 * one value chosen once by the entry point, so no interaction sequence produces a command with both
 * a target asset and a target group (invariant 1).
 *
 * A refused save **marks** the control it is about and says nothing: §17 ratifies no wording for a
 * schedule refusal and no brief invents one, so the form points at the field and the owner fixes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditScreen(
    graph: AppGraph,
    scheduleId: String?,
    targetAssetId: String?,
    targetGroupId: String?,
    onDone: (String) -> Unit,
    onBack: () -> Unit,
) {
    val model: ScheduleEditViewModel = viewModel(
        key = scheduleId ?: "new-schedule-${targetAssetId ?: targetGroupId}",
    ) { ScheduleEditViewModel(graph, scheduleId, targetAssetId, targetGroupId) }
    val state by model.state.collectAsStateWithLifecycle()

    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(model) {
        model.saved.collect { id ->
            if (!leaving) {
                leaving = true
                onDone(id.value)
            }
        }
    }

    val eyebrow = listOf(
        if (state.editing) "EDIT SCHEDULE" else "NEW SCHEDULE",
        state.targetName.uppercase(),
    ).filter { it.isNotBlank() }.joinToString(" · ")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = eyebrow,
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
                    TextButton(onClick = model::save, enabled = !state.saving) { Text("Save") }
                },
            )
        },
    ) { padding ->
        if (!state.loaded) {
            QuietLine("Loading…", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }

        if (state.askingForNotifications) {
            // #24 AC 1: the rationale, verbatim, on the first schedule creation. A denial is not
            // fatal and neither is dismissing this — the schedule is already written (D-22).
            AlertDialog(
                onDismissRequest = model::dismissNotifications,
                text = { Text(NOTIFICATION_PERMISSION_RATIONALE) },
                confirmButton = { TextButton(onClick = model::requestNotifications) { Text("OK") } },
                dismissButton = { TextButton(onClick = model::dismissNotifications) { Text("Not now") } },
            )
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // The target, stated rather than picked: it is chosen once, by the screen that opened
            // this editor, and an edit keeps the stored one. Drawing it read-only is what makes
            // "both set" unreachable — there is no second picker to disagree with the first.
            MaintenanceField(
                value = if (state.isGroup) A_MAINTENANCE_GROUP else ONE_ASSET,
                onValueChange = {},
                label = THIS_APPLIES_TO,
                readOnly = true,
                problem = ScheduleField.TARGET in state.marks,
            )

            MaintenanceField(
                value = state.title,
                onValueChange = model::onTitle,
                label = "Name",
                problem = ScheduleField.TITLE in state.marks,
                imeAction = ImeAction.Next,
            )
            if (state.duplicateWarning) {
                // Non-blocking, by construction: nothing here gates the save (D-11).
                QuietLine(SIMILAR_THROUGH_A_GROUP)
            }
            MaintenanceField(
                value = state.description,
                onValueChange = model::onDescription,
                label = "Description",
                minLines = 2,
            )

            MaintenanceSectionTitle(REPEATS_FROM)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                MaintenanceField(
                    value = state.timeInterval,
                    onValueChange = model::onInterval,
                    label = EVERY_N,
                    numeric = true,
                    problem = ScheduleField.INTERVAL in state.marks,
                    modifier = Modifier.weight(1f),
                )
                UnitPicker(
                    selected = state.timeUnit,
                    onSelect = model::onUnit,
                    problem = ScheduleField.UNIT in state.marks,
                    modifier = Modifier.weight(1f),
                )
            }
            ChoiceRow(
                options = listOf(
                    THE_SCHEDULED_DATE to TimeBasis.FIXED,
                    WHEN_I_COMPLETE_IT to TimeBasis.COMPLETION,
                ),
                selected = state.timeBasis,
                onSelect = model::onBasis,
            )
            DateField(
                value = state.anchorOn,
                onValueChange = model::onAnchor,
                label = "Date",
                problem = if (ScheduleField.ANCHOR in state.marks) "" else null,
            )
            MaintenanceField(
                value = state.leadDays,
                onValueChange = model::onLead,
                label = REMIND_ME_N_DAYS_EARLY,
                numeric = true,
                problem = ScheduleField.LEAD in state.marks,
            )

            // D-12: the meter block belongs to an asset target and is **absent** for a group, which
            // is why the whole section is inside the guard rather than disabled inside it.
            if (!state.isGroup) {
                MaintenanceSectionTitle(ALSO_DUE_BY_USE)
                if (state.meters.isEmpty()) {
                    // Nothing to count: the asset has no meter reading, and §17 ratifies no line
                    // for that, so the section says only what its header already says.
                    Spacer(Modifier.height(0.dp))
                } else {
                    MeterPicker(
                        meters = state.meters.map { it.id to it.label },
                        selected = state.meterDefinitionId,
                        onSelect = model::onMeterDefinition,
                        problem = ScheduleField.METER in state.marks,
                    )
                    if (state.hasMeterRule) {
                        MaintenanceField(
                            value = state.meterInterval,
                            onValueChange = model::onMeterInterval,
                            label = EVERY_N_UNIT_OF_USE,
                            numeric = true,
                            problem = ScheduleField.METER_INTERVAL in state.marks,
                        )
                        MaintenanceField(
                            value = state.anchorMeter,
                            onValueChange = model::onAnchorMeter,
                            label = LAST_DONE_AT,
                            numeric = true,
                        )
                        MaintenanceField(
                            value = state.meterLead,
                            onValueChange = model::onMeterLead,
                            label = REMIND_ME_N_UNIT_EARLY,
                            numeric = true,
                            problem = ScheduleField.METER_LEAD in state.marks,
                        )
                    }
                }

                MaintenanceSectionTitle(OUT_OF_SEASON_FIELD)
                ChoiceRow(
                    options = listOf(
                        PAUSE_WITH_THE_SEASON to SeasonBehavior.FOLLOW_ASSET,
                        REMIND_ME_YEAR_ROUND to SeasonBehavior.IGNORE,
                    ),
                    selected = state.seasonBehavior,
                    onSelect = model::onSeason,
                )

                MaintenanceSectionTitle(COMPLETING_THIS_TAKES)
                ChoiceRow(
                    options = listOf(
                        ONE_TAP to CompletionMode.QUICK,
                        THE_FULL_FORM to CompletionMode.FORM,
                    ),
                    selected = state.completionMode,
                    onSelect = model::onCompletionMode,
                )
                if (state.completionMode == CompletionMode.FORM) {
                    ProfilePicker(
                        profiles = state.profiles.map { it.id to it.name },
                        selected = state.profileId,
                        onSelect = model::onProfile,
                        problem = ScheduleField.PROFILE in state.marks,
                    )
                }
            } else {
                // D-28: a group target is `IGNORE` season only, and there is no control for the
                // other value — the one option it does have is stated so the behaviour is not a
                // silent default.
                MaintenanceSectionTitle(OUT_OF_SEASON_FIELD)
                QuietLine(REMIND_ME_YEAR_ROUND)
                // D-12: QUICK-only, and the same reasoning.
                MaintenanceSectionTitle(COMPLETING_THIS_TAKES)
                QuietLine(ONE_TAP)
            }

            MaintenanceSectionTitle(REMIND_ME_THROUGH)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Switch(checked = state.remindersEnabled, onCheckedChange = model::onReminders)
                // Single-choice, and one enabled row at most (#4). 1.2 has one provider, so the row
                // is a choice of one; #25 is the multi-provider UI and this is deliberately not it.
                ProviderId.entries.forEach { provider ->
                    ChoiceOption(
                        label = provider.name,
                        selected = state.provider == provider,
                        enabled = state.remindersEnabled,
                        onSelect = { model.onProvider(provider) },
                    )
                }
            }

            // **No foot button**, unlike the shipped editors, and deliberately: the app bar's
            // "Save" is the one that commits, and the foot buttons that sit beside it elsewhere read
            // "Save reading", "Save action", "Save entry" — a distinct label each, which is what
            // keeps the bare "Save" unambiguous. The matching label here would be a string §17 does
            // not list, and no brief invents one, so this form commits from the app bar alone.
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The form field this package draws, in the shipped editors' shape.
 *
 * [problem] is a **flag**, not a sentence: §17 ratifies no wording for a refused schedule save, so
 * the control is marked and nothing is said. `internal` because the completion affordance draws the
 * same field.
 */
@Composable
internal fun MaintenanceField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    problem: Boolean = false,
    hint: String? = null,
    placeholder: String? = null,
    minLines: Int = 1,
    mono: Boolean = false,
    numeric: Boolean = false,
    readOnly: Boolean = false,
    imeAction: ImeAction = ImeAction.Default,
    trailingIcon: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder == null) null else { { Text(placeholder) } },
        isError = problem,
        supportingText = if (hint == null) null else { { Text(hint) } },
        trailingIcon = trailingIcon,
        readOnly = readOnly,
        singleLine = minLines == 1,
        minLines = minLines,
        textStyle = if (mono) {
            MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodyLarge
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
            imeAction = imeAction,
        ),
        shape = ControlShape,
        modifier = modifier,
    )
}

/** The four `RecurrenceUnit` names, which are the wire and column values and need no translation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitPicker(
    selected: RecurrenceUnit,
    onSelect: (RecurrenceUnit) -> Unit,
    problem: Boolean,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected.name,
            onValueChange = {},
            readOnly = true,
            isError = problem,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            shape = ControlShape,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            RecurrenceUnit.entries.forEach { unit ->
                DropdownMenuItem(
                    text = { Text(unit.name) },
                    onClick = { open = false; onSelect(unit) },
                )
            }
        }
    }
}

/** The asset's meter readings, by their own labels. Offered for an asset target alone (D-12). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeterPicker(
    meters: List<Pair<DefinitionId, String>>,
    selected: DefinitionId?,
    onSelect: (DefinitionId?) -> Unit,
    problem: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = meters.firstOrNull { it.first == selected }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(ALSO_DUE_BY_USE) },
            isError = problem,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            shape = ControlShape,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // Clearing the choice is how the meter rule is switched off, and it reuses the shipped
            // "No target" wording for "no counter chosen" rather than drafting a line for it.
            DropdownMenuItem(
                text = { Text("No target") },
                onClick = { open = false; onSelect(null) },
            )
            meters.forEach { (id, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onSelect(id) })
            }
        }
    }
}

/** The asset's quick actions, for a `FORM` completion. Offered for an asset target alone (D-12). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfilePicker(
    profiles: List<Pair<ProfileId, String>>,
    selected: ProfileId?,
    onSelect: (ProfileId?) -> Unit,
    problem: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = profiles.firstOrNull { it.first == selected }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(USE_THIS_FORM) },
            isError = problem,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            shape = ControlShape,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            profiles.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onSelect(id) })
            }
        }
    }
}

/** A closed set of two, as radio options: what is chosen is visible without opening anything. */
@Composable
private fun <T> ChoiceRow(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (label, value) ->
            ChoiceOption(
                label = label,
                selected = selected == value,
                enabled = true,
                onSelect = { onSelect(value) },
            )
        }
    }
}

@Composable
private fun ChoiceOption(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.selectable(selected = selected, enabled = enabled, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
