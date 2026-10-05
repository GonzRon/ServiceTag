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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.l10n.localized
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
val THIS_APPLIES_TO: String get() = localized(R.string.maintenance_this_applies_to)
val ONE_ASSET: String get() = localized(R.string.maintenance_one_asset)
val A_MAINTENANCE_GROUP: String get() = localized(R.string.maintenance_a_maintenance_group)
val EVERY_N: String get() = localized(R.string.maintenance_every_n)
val REPEATS_FROM: String get() = localized(R.string.maintenance_repeats_from)
val THE_SCHEDULED_DATE: String get() = localized(R.string.maintenance_the_scheduled_date)
val WHEN_I_COMPLETE_IT: String get() = localized(R.string.maintenance_when_i_complete_it)
val REMIND_ME_N_DAYS_EARLY: String get() = localized(R.string.maintenance_remind_me_n_days_early)
val ALSO_DUE_BY_USE: String get() = localized(R.string.maintenance_also_due_by_use)
val EVERY_N_UNIT_OF_USE: String get() = localized(R.string.maintenance_every_n_unit_of_use)
val LAST_DONE_AT: String get() = localized(R.string.maintenance_last_done_at)
val REMIND_ME_N_UNIT_EARLY: String get() = localized(R.string.maintenance_remind_me_n_unit_early)
val COMPLETING_THIS_TAKES: String get() = localized(R.string.maintenance_completing_this_takes)
val ONE_TAP: String get() = localized(R.string.maintenance_one_tap)
val THE_FULL_FORM: String get() = localized(R.string.maintenance_the_full_form)
val USE_THIS_FORM: String get() = localized(R.string.maintenance_use_this_form)

/** P141-3, ratified 2026-09-25: the profile picker's label under One tap (#81). */
val QUICK_ACTION: String get() = localized(R.string.maintenance_quick_action)

/** P141-4, ratified 2026-09-25: the picker's first row, clearing a chosen profile (#81). */
val NONE: String get() = localized(R.string.maintenance_none)
val REMIND_ME_THROUGH: String get() = localized(R.string.maintenance_remind_me_through)

/** D-11's non-blocking line, RATIFIED (§17). Shown, dismissible by fixing the title, never a gate. */
val SIMILAR_THROUGH_A_GROUP: String get() = localized(R.string.maintenance_similar_through_a_group)

// The service-policy question, RATIFIED (spec §10.7, S65–S84), verbatim and by number. It replaces
// 1.2's two-option season choice, whose words are retired with it (master plan §1).

/** S65, the schedule question. */
val WHEN_SHOULD_THIS_BE_DONE: String get() = localized(R.string.maintenance_when_should_this_be_done)

/** S66, option. */
val BEFORE_THE_SEASON_STARTS: String get() = localized(R.string.maintenance_before_the_season_starts)

/** S67, option. */
val WHEN_THE_SEASON_STARTS: String get() = localized(R.string.maintenance_when_the_season_starts)

/** S68, option. */
val WHENEVER_IT_IS_DUE: String get() = localized(R.string.maintenance_whenever_it_is_due)

/** S69, option. */
val BEFORE_THE_MAINTENANCE_BREAK: String get() = localized(R.string.maintenance_before_the_maintenance_break)

/** S70, option. */
val AFTER_THE_MAINTENANCE_BREAK: String get() = localized(R.string.maintenance_after_the_maintenance_break)

/** S71, field. */
val DAYS_BEFORE_IT_STARTS: String get() = localized(R.string.maintenance_days_before_it_starts)

/** S72, field. */
val DAYS_AFTER_IT_STARTS: String get() = localized(R.string.maintenance_days_after_it_starts)

/** S73, field. */
val START_COUNTING_FROM: String get() = localized(R.string.maintenance_start_counting_from)

/** S74, option. */
val THE_SEASONS_START: String get() = localized(R.string.maintenance_the_seasons_start)

/** S75, option. */
val ITS_OWN_DATE_NOT_BEFORE_THE_SEASON: String get() = localized(R.string.maintenance_its_own_date_not_before_the_season)

/** S76, warning. */
val FIRST_DUE_OUTSIDE_THE_SEASON: String get() = localized(R.string.maintenance_first_due_outside_the_season)

/** S77, warning. */
val NOTHING_TO_BE_READY_BEFORE: String get() = localized(R.string.maintenance_nothing_to_be_ready_before)

/** S78, helper under S66. */
val HELPER_BEFORE_THE_SEASON: String get() = localized(R.string.maintenance_helper_before_the_season)

/** S79, helper under S69. */
val HELPER_BEFORE_THE_BREAK: String get() = localized(R.string.maintenance_helper_before_the_break)

/** S80, helper under S67. */
val HELPER_WHEN_THE_SEASON_STARTS: String get() = localized(R.string.maintenance_helper_when_the_season_starts)

/** S81, helper under S70. */
val HELPER_AFTER_THE_BREAK: String get() = localized(R.string.maintenance_helper_after_the_break)

/** S82, helper under S68. */
val HELPER_WHENEVER_IT_IS_DUE: String get() = localized(R.string.maintenance_helper_whenever_it_is_due)

/** S83, under an empty S71. */
val ENTER_THE_NUMBER_OF_DAYS: String get() = localized(R.string.maintenance_enter_the_number_of_days)

/** S84, warning under S72. */
val AFTER_THE_SEASON_ENDS: String get() = localized(R.string.maintenance_after_the_season_ends)

// The health link guard (spec §6.1, D-30), RATIFIED. S137 is defined here because this dialog lands
// first; B10's subject editor uses the same constant (master dec. 42).

/** S137, refusal: archiving the subject its asset's health follows. */
val THE_SUBJECT_HEALTH_FOLLOWS: String get() = localized(R.string.maintenance_the_subject_health_follows)

/** S141, the link-guard confirm. */
val ARCHIVE_BOTH: String get() = localized(R.string.maintenance_archive_both)

/** S140, the link-guard dialog, naming the subject: "This schedule drives the health subject <name>. …". */
fun scheduleDrivesSubject(name: String): String = localized(R.string.maintenance_schedule_drives_subject, name)

/** Each option's ratified word. */
internal fun policyOptionLabel(option: PolicyOption): String = when (option) {
    PolicyOption.BEFORE_SEASON -> BEFORE_THE_SEASON_STARTS
    PolicyOption.BEFORE_BREAK -> BEFORE_THE_MAINTENANCE_BREAK
    PolicyOption.WHEN_SEASON_STARTS -> WHEN_THE_SEASON_STARTS
    PolicyOption.AFTER_BREAK -> AFTER_THE_MAINTENANCE_BREAK
    PolicyOption.WHENEVER_DUE -> WHENEVER_IT_IS_DUE
}

/** Each option's helper, S78–S82, drawn under it while it is chosen. */
internal fun policyOptionHelper(option: PolicyOption): String = when (option) {
    PolicyOption.BEFORE_SEASON -> HELPER_BEFORE_THE_SEASON
    PolicyOption.BEFORE_BREAK -> HELPER_BEFORE_THE_BREAK
    PolicyOption.WHEN_SEASON_STARTS -> HELPER_WHEN_THE_SEASON_STARTS
    PolicyOption.AFTER_BREAK -> HELPER_AFTER_THE_BREAK
    PolicyOption.WHENEVER_DUE -> HELPER_WHENEVER_IT_IS_DUE
}

/**
 * Create ([scheduleId] null) or edit one schedule, in the shape of the shipped editors.
 *
 * **The three group hides are the point of this screen.** A group target draws no meter block, no
 * profile picker and no service-policy question, so the illegal group schedule D-12 and inv. 106
 * forbid is unreachable here rather than merely refused on save — and the target itself is
 * one value chosen once by the entry point, so no interaction sequence produces a command with both
 * a target asset and a target group (invariant 1).
 *
 * A refused save **marks** the control it is about and says nothing: §17 ratifies no wording for a
 * schedule refusal and no brief invents one, so the form points at the field and the owner fixes it.
 * Two refusals are the exceptions, because 1.4 ratified words for them: the health link guard asks
 * S140–S141 (or shows S137) in [LinkGuardDialog], and the `PreServiceNeedsDates` race shows S77 above
 * the question.
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

    Scaffold(
        topBar = {
            TopAppBar(
                // **The app bar names the target and nothing else.** The create/edit words that
                // stood here were this brief's own invented copy and are gone: §17 ratifies none
                // for either state, the shell the owner arrived through already says which one it
                // is, and the Asset's or group's name is stored **data** rather than a string this
                // release had to draft. A comment must not restate a literal a reviewer greps for,
                // so neither word appears above.
                title = {
                    Text(
                        text = state.targetName.uppercase(),
                        style = Eyebrow,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.maintenance_cancel))
                    }
                },
                actions = {
                    // Held while the question is unanswered or S71 is empty or 0 (master dec. 46), so
                    // no policy refusal is reachable and none needs a sentence.
                    TextButton(onClick = model::save, enabled = state.canSave) {
                        Text(stringResource(R.string.maintenance_save))
                    }
                },
            )
        },
    ) { padding ->
        if (!state.loaded) {
            QuietLine(stringResource(R.string.maintenance_loading), Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }

        if (state.askingForNotifications) {
            // #24 AC 1: the rationale, verbatim, on the first schedule creation. A denial is not
            // fatal and neither is dismissing this — the schedule is already written (D-22).
            AlertDialog(
                onDismissRequest = model::dismissNotifications,
                text = { Text(NOTIFICATION_PERMISSION_RATIONALE) },
                confirmButton = {
                    TextButton(onClick = model::requestNotifications) { Text(stringResource(R.string.maintenance_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = model::dismissNotifications) { Text(stringResource(R.string.maintenance_not_now)) }
                },
            )
        }
        state.linkGuard?.let { prompt ->
            LinkGuardDialog(prompt = prompt, onArchiveBoth = model::archiveBoth, onCancel = model::cancelLinkGuard)
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
                label = stringResource(R.string.maintenance_name),
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
                label = stringResource(R.string.maintenance_description),
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
                label = stringResource(R.string.maintenance_date),
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
                // No section heading here: the picker's own label **is** the ratified
                // "Also due by use", and drawing it twice in one section reads as a mistake. An
                // asset with no meter reading gets no block at all rather than a heading over
                // nothing — §17 ratifies no line for "there is nothing to count".
                if (state.meters.isNotEmpty()) {
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

                // Spec §10.4: the question only where the asset's season or break gives it meaning.
                // An asset with neither sees nothing here, and its schedule stays CONTINUOUS.
                if (state.questionDrawn) {
                    PolicyQuestion(state = state, model = model)
                }

                MaintenanceSectionTitle(COMPLETING_THIS_TAKES)
                ChoiceRow(
                    options = listOf(
                        ONE_TAP to CompletionMode.QUICK,
                        THE_FULL_FORM to CompletionMode.FORM,
                    ),
                    selected = state.completionMode,
                    onSelect = model::onCompletionMode,
                )
                // The picker is drawn for an asset target in **both** modes (#81): a profile is
                // one Asset's quick action, valid whichever way this schedule is completed, so it
                // is offered — and shown, and clearable — regardless of the mode.
                ProfilePicker(
                    label = if (state.completionMode == CompletionMode.FORM) USE_THIS_FORM else QUICK_ACTION,
                    profiles = state.profiles.map { it.id to it.name },
                    selected = state.profileId,
                    onSelect = model::onProfile,
                    problem = ScheduleField.PROFILE in state.marks,
                )
            } else {
                // Inv. 106: a group target is CONTINUOUS only and is asked no question at all — a
                // group has no season and no break for one to be about.
                // D-12: QUICK-only, stated so the behaviour is not a silent default.
                MaintenanceSectionTitle(COMPLETING_THIS_TAKES)
                QuietLine(ONE_TAP)
            }

            // The RATIFIED row label, and **no option word** — §17.1c ratifies the label and,
            // deliberately, no option for it. `ProviderId.name` was this brief's own invented copy
            // and is gone: `LOCAL` is a wire value and a domain member (decision 8), not ratified
            // display text. 1.2 has exactly one provider, so the single-choice row of #4 is a
            // choice of one and the switch is the whole of it; the command still writes **at most
            // one** enabled row, and #25 is the multi-provider UI this deliberately is not.
            MaintenanceSectionTitle(REMIND_ME_THROUGH)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
            ) {
                Switch(
                    checked = state.remindersEnabled,
                    onCheckedChange = model::onReminders,
                    // The row's ratified heading is what names this control; there is no second
                    // word for it to repeat.
                    modifier = Modifier.semantics { contentDescription = REMIND_ME_THROUGH },
                )
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
 * S65 and its answers, spec §10.4: the options the asset's season and break make meaningful, in
 * order, each with its helper under it while it is chosen, and the chosen option's own fields.
 *
 * Nothing here is prefilled that the owner must decide: S71 starts empty and shows S83 until a
 * number is entered (inv. 121); S72 is empty, which is 0, the start itself. S76, S77 and S84 are
 * warnings — the line is shown and nothing is refused.
 */
@Composable
private fun PolicyQuestion(state: ScheduleEditState, model: ScheduleEditViewModel) {
    MaintenanceSectionTitle(WHEN_SHOULD_THIS_BE_DONE)
    if (state.noBoundary) QuietLine(NOTHING_TO_BE_READY_BEFORE)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        state.policyOptions.forEach { option ->
            ChoiceOption(
                label = policyOptionLabel(option),
                selected = state.policyOption == option,
                enabled = true,
                onSelect = { model.onPolicy(option) },
            )
            if (state.policyOption == option) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
                ) {
                    QuietLine(policyOptionHelper(option))
                    when (option) {
                        PolicyOption.BEFORE_SEASON, PolicyOption.BEFORE_BREAK -> MaintenanceField(
                            value = state.daysBefore,
                            onValueChange = model::onDaysBefore,
                            label = DAYS_BEFORE_IT_STARTS,
                            digitsOnly = true,
                            hint = if (state.marginMissing) ENTER_THE_NUMBER_OF_DAYS else null,
                            problem = ScheduleField.POLICY_OFFSET in state.marks,
                        )
                        PolicyOption.WHEN_SEASON_STARTS -> StartCounting(state = state, model = model)
                        PolicyOption.AFTER_BREAK, PolicyOption.WHENEVER_DUE -> Unit
                    }
                }
            }
        }
    }
}

/** S73 under S67: S74 with S72's offset, or S75. S72 is absent on a meter-only schedule (O-7). */
@Composable
private fun StartCounting(state: ScheduleEditState, model: ScheduleEditViewModel) {
    if (state.anchorOutsideSeason) QuietLine(FIRST_DUE_OUTSIDE_THE_SEASON)
    Text(
        text = START_COUNTING_FROM,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ChoiceOption(
        label = THE_SEASONS_START,
        selected = state.startCountingFrom == StartCountingFrom.SEASON_START,
        enabled = true,
        onSelect = { model.onStartCountingFrom(StartCountingFrom.SEASON_START) },
    )
    if (state.startCountingFrom == StartCountingFrom.SEASON_START && state.hasTimeRule) {
        MaintenanceField(
            value = state.daysAfter,
            onValueChange = model::onDaysAfter,
            label = DAYS_AFTER_IT_STARTS,
            digitsOnly = true,
            problem = ScheduleField.POLICY_OFFSET in state.marks,
        )
        if (state.offsetPassesSeasonEnd) QuietLine(AFTER_THE_SEASON_ENDS)
    }
    ChoiceOption(
        label = ITS_OWN_DATE_NOT_BEFORE_THE_SEASON,
        selected = state.startCountingFrom == StartCountingFrom.OWN_DATE,
        enabled = true,
        onSelect = { model.onStartCountingFrom(StartCountingFrom.OWN_DATE) },
    )
}

/**
 * The health link guard's dialog (spec §6.1, D-30; inv. 130), for the editor's save and the schedule
 * detail's archive alike: S140 naming the subject, with S141 "Archive both" and the shipped Cancel;
 * or S137, when "Archive both" was answered that the subject is the one its asset's health follows,
 * with Cancel alone. **Cancel writes nothing.**
 */
@Composable
internal fun LinkGuardDialog(prompt: LinkGuardPrompt, onArchiveBoth: () -> Unit, onCancel: () -> Unit) {
    when (prompt) {
        is LinkGuardPrompt.Asks -> AlertDialog(
            onDismissRequest = onCancel,
            text = { Text(scheduleDrivesSubject(prompt.subjectName)) },
            confirmButton = { TextButton(onClick = onArchiveBoth) { Text(ARCHIVE_BOTH) } },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.maintenance_cancel)) } },
        )
        LinkGuardPrompt.Primary -> AlertDialog(
            onDismissRequest = onCancel,
            text = { Text(THE_SUBJECT_HEALTH_FOLLOWS) },
            confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.maintenance_cancel)) } },
        )
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
    /** A whole-number field: the number keyboard, with no decimal point to offer (master dec. 46). */
    digitsOnly: Boolean = false,
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
            keyboardType = when {
                digitsOnly -> KeyboardType.Number
                numeric -> KeyboardType.Decimal
                else -> KeyboardType.Text
            },
            imeAction = imeAction,
        ),
        shape = ControlShape,
        modifier = modifier,
    )
}

/**
 * The interval unit's word in the picker. The `RecurrenceUnit` names are the wire and column values and are never
 * shown through here as data; the English words read the same as the names, and a language pack gives its own.
 */
internal fun recurrenceUnitLabel(unit: RecurrenceUnit): String = when (unit) {
    RecurrenceUnit.DAY -> localized(R.string.maintenance_unit_day)
    RecurrenceUnit.WEEK -> localized(R.string.maintenance_unit_week)
    RecurrenceUnit.MONTH -> localized(R.string.maintenance_unit_month)
    RecurrenceUnit.YEAR -> localized(R.string.maintenance_unit_year)
}

/** The four `RecurrenceUnit`s, each by its [recurrenceUnitLabel]. */
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
            value = recurrenceUnitLabel(selected),
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
                    text = { Text(recurrenceUnitLabel(unit)) },
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
                text = { Text(stringResource(R.string.maintenance_no_target)) },
                onClick = { open = false; onSelect(null) },
            )
            meters.forEach { (id, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onSelect(id) })
            }
        }
    }
}

/**
 * The asset's quick actions. Offered for an asset target alone (D-12), in either completion mode
 * (#81): [label] is [QUICK_ACTION] under One tap and [USE_THIS_FORM] under The full form. `None`
 * is always the first row and is the only way left to clear a chosen profile explicitly, now that
 * the mode switch no longer does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfilePicker(
    label: String,
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
            label = { Text(label) },
            isError = problem,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            shape = ControlShape,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(NONE) },
                onClick = { open = false; onSelect(null) },
            )
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
