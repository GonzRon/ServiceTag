package com.loosecannon.servicetag.ui.asset

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.journal.CategoryChoice
import com.loosecannon.servicetag.core.journal.SeedTemplates
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.l10n.localizedPlural
import com.loosecannon.servicetag.reminders.WARRANTY_NOTIFICATION_RATIONALE
import com.loosecannon.servicetag.ui.attachments.NO_APP_CAN_PICK_FILES
import com.loosecannon.servicetag.ui.attachments.NoAttachmentFolderCard
import com.loosecannon.servicetag.ui.attachments.label
import com.loosecannon.servicetag.ui.attachments.rememberDocumentPicker
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.components.ServiceTagIcons
import com.loosecannon.servicetag.ui.health.RESTORE_SUBJECT
import com.loosecannon.servicetag.ui.maintenance.NOT_NOW
import com.loosecannon.servicetag.ui.maintenance.REMIND_ME_N_DAYS_EARLY
import com.loosecannon.servicetag.ui.replace.ReplaceStrings
import com.loosecannon.servicetag.ui.theme.BadgeShape
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.MonoText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.launch

// The asset editor's season, break and health words (spec §10.7), RATIFIED, each by its S-number and
// verbatim. S132 is one ratified set of words, split only at its "·" separators.
// #102: every word here lives in res/values/strings_asset_edit.xml (`asset_edit_*`) beside its S-, P67- or P78-
// number, and is read when drawn; S132's four words are four strings there, so a translation cannot lose one.

/** S28, asset editor section. */
val OPERATING_SEASON: String get() = localized(R.string.asset_edit_operating_season)

/** S29, mode (re). */
val YEAR_ROUND: String get() = localized(R.string.asset_edit_year_round)

/** S30, mode. */
val SAME_DATES_EVERY_YEAR: String get() = localized(R.string.asset_edit_same_dates_every_year)

/** S31, mode. */
val STARTED_AND_ENDED_BY_HAND: String get() = localized(R.string.asset_edit_started_and_ended_by_hand)

/** S32, field (re). */
val SEASON_STARTS: String get() = localized(R.string.asset_edit_season_starts)

/** S33, field (re). */
val SEASON_ENDS: String get() = localized(R.string.asset_edit_season_ends)

/** S34, helper under S32 and S33. */
val SEASON_MAY_RUN_ACROSS_THE_NEW_YEAR: String get() = localized(R.string.asset_edit_season_may_run_across_the_new_year)

/** S35, the switch question: asked only on a switch into S31, with no default. */
val IS_THIS_ASSET_IN_SEASON: String get() = localized(R.string.asset_edit_is_this_asset_in_season)

/** S36, option. */
val IN_SEASON_NOW: String get() = localized(R.string.asset_edit_in_season_now)

/** S37, option (re). */
val OUT_OF_SEASON_NOW: String get() = localized(R.string.asset_edit_out_of_season_now)

/** S38, helper under S31. */
val YOU_START_AND_END_THE_SEASON: String get() = localized(R.string.asset_edit_you_start_and_end_the_season)

/** S58, asset editor section. */
val MAINTENANCE_BREAK: String get() = localized(R.string.asset_edit_maintenance_break)

/** S59, toggle: off unless a break is stored. */
val NO_ROUTINE_MAINTENANCE_BETWEEN_TWO_DATES: String get() = localized(R.string.asset_edit_no_routine_maintenance_between_two_dates)

/** S60, field. */
val BREAK_STARTS: String get() = localized(R.string.asset_edit_break_starts)

/** S61, field. */
val BREAK_ENDS: String get() = localized(R.string.asset_edit_break_ends)

/** S62, helper under S60 and S61. */
val BREAK_HELPER: String get() = localized(R.string.asset_edit_break_helper)

/** S63, refusal: `BLACKOUT_COVERS_THE_YEAR`. */
val BREAK_CANNOT_COVER_THE_YEAR: String get() = localized(R.string.asset_edit_break_cannot_cover_the_year)

/** S111, section. */
val HEALTH_SUBJECTS: String get() = localized(R.string.asset_edit_health_subjects)

/** S112, action: opens the health subject editor for this asset (B14's asset detail uses it too). */
val ADD_HEALTH_SUBJECT: String get() = localized(R.string.asset_edit_add_health_subject)

/** S131, field. */
val COMBINE_HEALTH_BY: String get() = localized(R.string.asset_edit_combine_health_by)

/**
 * S132, options, one ratified set: split only at its "·" separators, in its own order. #102: each word is its own
 * string ([COMBINE_CHOICES]); this is the set as one run, the words joined at the ratified separator.
 */
val COMBINE_HEALTH_OPTIONS: String get() = COMBINE_CHOICES.joinToString(" · ") { it.second }

/** S134, field under "One subject". */
val WHICH_SUBJECT: String get() = localized(R.string.asset_edit_which_subject)

// #78's reconciliation prompt (plan §5), RATIFIED 2026-09-25, verbatim. The dialog has no title (R-2).

/** P78-1b, the dialog's body when the count is 1 — [notTiedToSeason]'s `one` form. */
val NOT_TIED_TO_SEASON_ONE: String get() = notTiedToSeason(1)

/** P78-2, the dialog's confirm button: the editor closes onto the asset's schedules. */
val REVIEW_MAINTENANCE_SCHEDULES: String get() = localized(R.string.asset_edit_review_maintenance_schedules)

/** P78-3, the dialog's dismiss button: the editor closes and the schedules stay as they are. */
val KEEP_SCHEDULES_AS_IS: String get() = localized(R.string.asset_edit_keep_schedules_as_is)

/**
 * P78-1b for one schedule, otherwise P78-1a with its one substitution: the live CONTINUOUS count. #102: the two are
 * one plural, `asset_edit_not_tied_to_season`, chosen by the rendering language's rules.
 */
fun notTiedToSeason(count: Int): String = localizedPlural(R.plurals.asset_edit_not_tied_to_season, count, count)

/** S132's four words, each with the aggregation it names, in the ratified order. Read when drawn (#102). */
internal val COMBINE_CHOICES: List<Pair<HealthAggregation, String>>
    get() = listOf(
        HealthAggregation.WORST to localized(R.string.asset_edit_combine_worst),
        HealthAggregation.TRACK_ONE to localized(R.string.asset_edit_combine_one),
        HealthAggregation.AVERAGE to localized(R.string.asset_edit_combine_average),
        HealthAggregation.WEIGHTED to localized(R.string.asset_edit_combine_weighted),
    )

/** One ratified set of words, split only at its "·" separators (spec §10.7). */
internal fun ratifiedParts(words: String): List<String> = words.split(" · ")

// #67's document intake (plan §6), RATIFIED verbatim.

/** P67-1, a `SectionHeader` drawn upper-case like DOCUMENTS. */
val KEY_DOCUMENTS: String get() = localized(R.string.asset_edit_key_documents)

/** P67-7, Purchase block. */
val ADD_PURCHASE_INVOICE_OR_RECEIPT: String get() = localized(R.string.asset_edit_add_purchase_invoice_or_receipt)

/** P67-8, Key documents block. */
val ADD_USER_MANUAL: String get() = localized(R.string.asset_edit_add_user_manual)

/** P67-9, Key documents block. */
val ADD_SERVICE_MANUAL: String get() = localized(R.string.asset_edit_add_service_manual)

/** P67-10, a staged file's quiet line; replaced by the problem sentence after a failed copy. */
val ATTACHED_WHEN_YOU_SAVE: String get() = localized(R.string.asset_edit_attached_when_you_save)

// #86 (plan §6, reused 1–9): the editor's field labels, hoisted byte-identical so Replace asset draws them from here.

/** The Name field's label. */
val NAME_FIELD: String get() = localized(R.string.asset_edit_name_field)

/** The Manufacturer field's label. */
val MANUFACTURER_FIELD: String get() = localized(R.string.asset_edit_manufacturer_field)

/** The Model field's label. */
val MODEL_FIELD: String get() = localized(R.string.asset_edit_model_field)

/** The Serial number field's label. */
val SERIAL_NUMBER_FIELD: String get() = localized(R.string.asset_edit_serial_number_field)

/** The Location field's label. */
val LOCATION_FIELD: String get() = localized(R.string.asset_edit_location_field)

/** The parent picker's label. */
val PART_OF_FIELD: String get() = localized(R.string.asset_edit_part_of_field)

/** The Purchase date field's label. */
val PURCHASE_DATE_FIELD: String get() = localized(R.string.asset_edit_purchase_date_field)

/** The In service date field's label. */
val IN_SERVICE_DATE_FIELD: String get() = localized(R.string.asset_edit_in_service_date_field)

/** The Category field's label. */
val CATEGORY_FIELD: String get() = localized(R.string.asset_edit_category_field)

/**
 * Create ([assetId] null) or edit one asset: the grouped form of spec §9 — IDENTITY, PLACEMENT,
 * PURCHASE, WARRANTY, NOTES, and on a new asset only, TEMPLATE. Save sits in the app bar and
 * again at the bottom so it is reachable with the keyboard open (G1 §1.3).
 *
 * [parentId] is the "Part of" a new asset opens with, which is how "+ Add child asset" on a
 * parent's screen makes a child. Every rule belongs to the use cases; the screen only draws what
 * they refused — a line under each bad field, and the refused reparent on the snackbar, because
 * "that asset is inside this one" is about a pair and not about any single input.
 *
 * **1.4 (B10):** "Operating season" replaces 1.2's single year-round switch, "Maintenance break" is
 * never prefilled, and an existing asset lists its "Health subjects" — [onAddSubject] and
 * [onOpenSubject] open the health subject editor — and asks how to "Combine health by". All of it is
 * one save. Save is held, in the app bar and at the foot, while an answer the owner must give is
 * missing; the fields that hold it carry an asterisk, and no sentence is drawn for it (master dec. 46).
 * Leaving without Save writes nothing: opening a subject writes nothing either.
 *
 * **#78:** a save that takes an existing asset from year-round into a season, while it has live
 * schedules set to "Whenever it is due", asks once before the editor closes (P78-1a/1b, no title). The
 * season is already saved; "Keep schedules as-is" and the back gesture finish through [onDone], and
 * "Review maintenance schedules" through [onReviewSchedules]. Neither answer writes anything.
 *
 * **#79:** the Warranty block carries the reminder's lead, and a save that first sets one while
 * notifications are not granted asks P79-12 before the editor closes — after #78's question.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetEditScreen(
    graph: AppGraph,
    assetId: String?,
    onDone: (String) -> Unit,
    onBack: () -> Unit,
    parentId: String? = null,
    onAddSubject: (assetId: String) -> Unit = {},
    onOpenSubject: (assetId: String, subjectId: String) -> Unit = { _, _ -> },
    onReviewSchedules: (assetId: String) -> Unit = {},
    // #67, R67-13: the Key documents block's status card, with no attachment folder configured.
    onOpenSettings: () -> Unit = {},
) {
    // The key carries the parent as well as the id: "+ Add child asset" on two different parents
    // must not share one half-filled form, and neither must a plain "Add asset" and a component.
    val model: AssetEditViewModel = viewModel(key = assetId ?: "new-${parentId ?: "root"}") {
        AssetEditViewModel(graph, assetId, parentId)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val prompt by model.prompt.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // The save itself belongs to the ViewModel; this only listens for where it says to go next.
    LaunchedEffect(model) { model.saved.collect { id -> onDone(id.value) } }
    LaunchedEffect(model) { model.review.collect { id -> onReviewSchedules(id.value) } }
    LaunchedEffect(model) { model.messages.collect { snackbars.showSnackbar(it) } }
    // #67, R67-13: entering composition is how this screen learns the person went to Settings,
    // chose a folder and came back — the ViewModel outlives the push, so nothing else would tell
    // it (mirrors `AttachmentsSection`'s own `refreshStore` call).
    LaunchedEffect(model) { model.refreshStore() }

    // #67, R67-8 (C6): close and back are held throughout the copies.
    BackHandler(enabled = state.saving) { }

    // #78 (C3): the question over the saved form. Dismissing it any other way — the back gesture, a
    // tap outside — is "Keep schedules as-is", so the owner is never left without an answer.
    when (val ask = prompt) {
        is EditPrompt.ReconcileSchedules -> AlertDialog(
            onDismissRequest = model::keepSchedules,
            text = { Text(notTiedToSeason(ask.count)) },
            confirmButton = {
                TextButton(onClick = model::reviewSchedules) { Text(REVIEW_MAINTENANCE_SCHEDULES) }
            },
            dismissButton = {
                TextButton(onClick = model::keepSchedules) { Text(KEEP_SCHEDULES_AS_IS) }
            },
        )
        null -> Unit
    }

    // #79 (C11, R79-16): P79-12 after a save that first set a lead, while notifications are not granted.
    // The asset is already written; "OK" alone requests, and "Not now" or any dismissal requests nothing.
    if (state.askingForNotifications) {
        AlertDialog(
            onDismissRequest = model::dismissNotifications,
            text = { Text(WARRANTY_NOTIFICATION_RATIONALE) },
            confirmButton = {
                TextButton(onClick = model::requestNotifications) { Text(stringResource(R.string.asset_edit_notifications_ok)) }
            },
            dismissButton = { TextButton(onClick = model::dismissNotifications) { Text(NOT_NOW) } },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = {
                    Text(if (state.editing) stringResource(R.string.asset_edit_title_edit) else ReplaceStrings.NEW_ASSET)
                },
                navigationIcon = {
                    // #67, R67-8 (C6): held throughout the copies, same as the back gesture above.
                    IconButton(onClick = onBack, enabled = !state.saving) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.asset_edit_cancel))
                    }
                },
                actions = {
                    TextButton(onClick = model::save, enabled = state.canSave) {
                        Text(stringResource(R.string.asset_edit_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IdentityBlock(state, model)
            PlacementBlock(state, model)
            OperatingSeasonBlock(state, model)
            MaintenanceBreakBlock(state, model)
            // A subject needs its asset's id, so a new asset gains subjects after its first save
            // (master dec. 39).
            if (state.editing && assetId != null) {
                HealthBlock(
                    state = state,
                    model = model,
                    onAddSubject = { onAddSubject(assetId) },
                    onOpenSubject = { subjectId -> onOpenSubject(assetId, subjectId) },
                )
            }
            // #67, C5: reused from its home, exactly as DOCUMENTS says it.
            val onNoFilePicker: () -> Unit = { scope.launch { snackbars.showSnackbar(NO_APP_CAN_PICK_FILES) } }
            PurchaseBlock(state, model, onNoFilePicker)
            WarrantyBlock(state, model)
            KeyDocumentsBlock(state, model, onOpenSettings, onNoFilePicker)

            SectionHeader(title = stringResource(R.string.asset_edit_section_notes))
            FormField(
                value = state.notes,
                onValueChange = model::onNotes,
                label = stringResource(R.string.asset_edit_notes_field),
                minLines = 3,
            )

            // Editing an existing asset shows no template row: a template is starter data, and
            // an asset that has been in use has rows of its own that it must not clobber (§7).
            if (!state.editing) {
                TemplateRow(selected = state.templateKey, onSelect = model::onTemplate)
            }
            Button(
                onClick = model::save,
                enabled = state.canSave,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.asset_edit_save_asset))
            }
        }
    }
}

/** What the thing is. Name first, because it is the one field every version of this app required. */
@Composable
private fun IdentityBlock(state: AssetEditState, model: AssetEditViewModel) {
    SectionHeader(title = stringResource(R.string.asset_edit_section_identity))
    FormField(
        value = state.name,
        onValueChange = model::onName,
        label = NAME_FIELD,
        problem = state.problems[AssetField.NAME],
    )
    val choices by model.categoryChoices.collectAsStateWithLifecycle()
    CategoryField(value = state.category, choices = choices, onValueChange = model::onCategory)
    FormField(value = state.manufacturer, onValueChange = model::onManufacturer, label = MANUFACTURER_FIELD)
    FormField(value = state.model, onValueChange = model::onModel, label = MODEL_FIELD)
    FormField(value = state.serialNumber, onValueChange = model::onSerialNumber, label = SERIAL_NUMBER_FIELD)
    FormField(
        value = state.description,
        onValueChange = model::onDescription,
        label = stringResource(R.string.asset_edit_description_field),
    )
}

/** Where it is and what it is part of (spec §5). When it is in use is "Operating season", below. */
@Composable
private fun PlacementBlock(state: AssetEditState, model: AssetEditViewModel) {
    SectionHeader(title = stringResource(R.string.asset_edit_section_placement))
    FormField(value = state.location, onValueChange = model::onLocation, label = LOCATION_FIELD)
    ChoiceField(
        label = PART_OF_FIELD,
        choices = state.parentChoices,
        selected = state.parentId,
        onSelect = model::onParent,
        problem = state.problems[AssetField.PARENT],
    )
}

/**
 * S28 and its three answers (spec §3.1, §10.4), each chosen answer's own fields under it: S32 and S33
 * with S34 under S30; S38 under S31, and S35 with S36 and S37 — **none chosen** — when S31 is a
 * switch into MANUAL (inv. 92). S55 names the schedules a refused change would strand.
 *
 * **#16 (C27, R16-16):** while a Home Assistant binding owns the season the block is drawn read-only — the stored
 * answer chosen, none offered — with P16-47 under it; the other blocks are as shipped.
 */
@Composable
private fun OperatingSeasonBlock(state: AssetEditState, model: AssetEditViewModel) {
    SentenceSectionHeader(OPERATING_SEASON)
    val open = state.seasonSyncLine == null
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ChoiceRow(YEAR_ROUND, state.seasonMode == SeasonMode.YEAR_ROUND, enabled = open) {
            model.onSeasonMode(SeasonMode.YEAR_ROUND)
        }
        ChoiceRow(SAME_DATES_EVERY_YEAR, state.seasonMode == SeasonMode.CALENDAR, enabled = open) {
            model.onSeasonMode(SeasonMode.CALENDAR)
        }
        if (state.seasonMode == SeasonMode.CALENDAR) {
            Nested {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    MonthDayField(state.seasonStartInput, model::onSeasonStart, Modifier.weight(1f))
                    MonthDayField(state.seasonEndInput, model::onSeasonEnd, Modifier.weight(1f))
                }
                QuietLine(SEASON_MAY_RUN_ACROSS_THE_NEW_YEAR)
            }
        }
        ChoiceRow(STARTED_AND_ENDED_BY_HAND, state.seasonMode == SeasonMode.MANUAL, enabled = open) {
            model.onSeasonMode(SeasonMode.MANUAL)
        }
        if (state.seasonMode == SeasonMode.MANUAL && open) {
            Nested {
                QuietLine(YOU_START_AND_END_THE_SEASON)
                if (state.asksManualPhase) {
                    FieldLabel(state.manualQuestionLabel)
                    ChoiceRow(IN_SEASON_NOW, state.manualPhase == SeasonPhase.IN_SEASON) {
                        model.onManualPhase(SeasonPhase.IN_SEASON)
                    }
                    ChoiceRow(OUT_OF_SEASON_NOW, state.manualPhase == SeasonPhase.OUT_OF_SEASON) {
                        model.onManualPhase(SeasonPhase.OUT_OF_SEASON)
                    }
                }
            }
        }
    }
    state.seasonSyncLine?.let { QuietLine(it) }
    state.seasonRefusal?.let { RefusalLine(it) }
}

/**
 * S58: S59, off unless a break is stored; on, S60 and S61 **empty** with S62 under them (inv. 121).
 * S63 and S64 are drawn under the fields when a save is refused.
 */
@Composable
private fun MaintenanceBreakBlock(state: AssetEditState, model: AssetEditViewModel) {
    SentenceSectionHeader(MAINTENANCE_BREAK)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = state.breakOn, role = Role.Switch, onValueChange = model::onBreak),
    ) {
        Text(
            text = NO_ROUTINE_MAINTENANCE_BETWEEN_TWO_DATES,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = state.breakOn, onCheckedChange = null)
    }
    if (state.breakOn) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MonthDayField(state.breakStartInput, model::onBreakStart, Modifier.weight(1f))
            MonthDayField(state.breakEndInput, model::onBreakEnd, Modifier.weight(1f))
        }
        QuietLine(BREAK_HELPER)
    }
    state.breakRefusal?.let { RefusalLine(it) }
}

/**
 * S111 and S131, an existing asset only (master dec. 39). The subjects in `sortOrder`, each opening
 * its editor; an archived one is marked by its S136 action, "Restore subject", which is done in that
 * editor, so nothing is written from here. S112 opens a new subject. S134 lists the non-archived
 * subjects alone, so `HEALTH_PRIMARY_INVALID` cannot be reached from this form.
 */
@Composable
private fun HealthBlock(
    state: AssetEditState,
    model: AssetEditViewModel,
    onAddSubject: () -> Unit,
    onOpenSubject: (String) -> Unit,
) {
    SentenceSectionHeader(HEALTH_SUBJECTS)
    Column {
        state.subjects.forEach { subject ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSubject(subject.id) }
                    .padding(vertical = 12.dp),
            ) {
                Text(
                    text = subject.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (subject.archived) {
                    Text(
                        text = RESTORE_SUBJECT,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        TextButton(onClick = onAddSubject) { Text(ADD_HEALTH_SUBJECT) }
    }

    FieldLabel(COMBINE_HEALTH_BY)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        COMBINE_CHOICES.forEach { (aggregation, words) ->
            ChoiceRow(words, state.aggregation == aggregation) { model.onAggregation(aggregation) }
            if (aggregation == HealthAggregation.TRACK_ONE && state.aggregation == aggregation) {
                Nested {
                    FieldLabel(state.primaryQuestionLabel)
                    state.primaryChoices.forEach { subject ->
                        ChoiceRow(subject.name, state.primaryId == subject.id) { model.onPrimary(subject.id) }
                    }
                }
            }
        }
    }
}

/**
 * What it cost and when it arrived. A price is text until [priceHint]'s currency resolves it.
 *
 * #67, C5, R67-6: the receipt affordance and the asset's existing receipts, read-only under their
 * role label — the Purchase block's own document, never a link (R67-5 is the Details fact, not
 * this).
 */
@Composable
private fun PurchaseBlock(
    state: AssetEditState,
    model: AssetEditViewModel,
    onNoFilePicker: () -> Unit,
) {
    SectionHeader(title = stringResource(R.string.asset_edit_section_purchase))
    DateField(
        value = state.purchaseOn,
        onValueChange = model::onPurchaseOn,
        label = PURCHASE_DATE_FIELD,
        problem = state.problems[AssetField.PURCHASE_ON],
    )
    DateField(
        value = state.inServiceOn,
        onValueChange = model::onInServiceOn,
        label = IN_SERVICE_DATE_FIELD,
        problem = state.problems[AssetField.IN_SERVICE_ON],
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        FormField(
            value = state.price,
            onValueChange = model::onPrice,
            label = stringResource(R.string.asset_edit_price_field),
            problem = state.problems[AssetField.PRICE],
            hint = priceHint(state.currency),
            mono = true,
            numeric = true,
            modifier = Modifier.weight(1f),
        )
        FormField(
            value = state.currency,
            onValueChange = model::onCurrency,
            label = stringResource(R.string.asset_edit_currency_field),
            problem = state.problems[AssetField.CURRENCY],
            mono = true,
            modifier = Modifier.width(126.dp),
        )
    }
    FormField(value = state.vendor, onValueChange = model::onVendor, label = stringResource(R.string.asset_edit_vendor_field))
    DocumentRoleBlock(
        role = DocumentRole.PURCHASE_INVOICE_OR_RECEIPT,
        buttonText = ADD_PURCHASE_INVOICE_OR_RECEIPT,
        state = state,
        model = model,
        onNoFilePicker = onNoFilePicker,
    )
}

/**
 * When the cover runs out, how early to be warned, and whatever the paperwork says about it. #79 (C11):
 * the lead sits under "Expires on" with the schedule editor's ratified label and P79-8 under it; it is
 * off unless a number is typed, and its one line replaces P79-8 when a save is refused.
 */
@Composable
private fun WarrantyBlock(state: AssetEditState, model: AssetEditViewModel) {
    SectionHeader(title = stringResource(R.string.asset_edit_section_warranty))
    DateField(
        value = state.warrantyExpiresOn,
        onValueChange = model::onWarrantyExpiresOn,
        label = stringResource(R.string.asset_edit_warranty_expires_on),
        problem = state.problems[AssetField.WARRANTY_EXPIRES_ON],
    )
    FormField(
        value = state.warrantyLead,
        onValueChange = model::onWarrantyLead,
        label = REMIND_ME_N_DAYS_EARLY,
        problem = state.problems[AssetField.WARRANTY_LEAD],
        hint = LEAVE_BLANK_FOR_NO_REMINDER,
        numeric = true,
    )
    FormField(
        value = state.warrantyNotes,
        onValueChange = model::onWarrantyNotes,
        label = stringResource(R.string.asset_edit_warranty_notes_field),
        minLines = 2,
    )
}

/**
 * #67, C5: the two manual affordances and the asset's existing manuals, read-only under their role
 * labels. With no attachment folder the three document affordances across this screen (here and in
 * [PurchaseBlock]) are **hidden**, never disabled, and this block alone carries the one status card
 * (R67-13) — DOCUMENTS' own card, drawn from its home.
 */
@Composable
private fun KeyDocumentsBlock(
    state: AssetEditState,
    model: AssetEditViewModel,
    onOpenSettings: () -> Unit,
    onNoFilePicker: () -> Unit,
) {
    SectionHeader(title = KEY_DOCUMENTS)
    DocumentRoleBlock(
        role = DocumentRole.USER_MANUAL,
        buttonText = ADD_USER_MANUAL,
        state = state,
        model = model,
        onNoFilePicker = onNoFilePicker,
    )
    DocumentRoleBlock(
        role = DocumentRole.SERVICE_MANUAL,
        buttonText = ADD_SERVICE_MANUAL,
        state = state,
        model = model,
        onNoFilePicker = onNoFilePicker,
    )
    if (!state.offersDocuments) NoAttachmentFolderCard(onOpenSettings)
}

/**
 * One role's affordance, its staged lines, and its existing files under [role]'s own label — the
 * button is hidden (never disabled) without a folder (R67-13); the staged lines and the existing
 * files draw either way, so a file already there or already picked is never hidden by a folder
 * that later went away. While the copies run (C6, R67-8) a tap on the button or on Remove is
 * ignored, as close and back are held: the picker is not opened, and no line changes under the copy.
 */
@Composable
private fun DocumentRoleBlock(
    role: DocumentRole,
    buttonText: String,
    state: AssetEditState,
    model: AssetEditViewModel,
    onNoFilePicker: () -> Unit,
) {
    if (state.offersDocuments) {
        val picker = rememberDocumentPicker(
            onPicked = { lookup -> model.stagePicked(role, lookup) },
            onNoFilePicker = onNoFilePicker,
        )
        TextButton(onClick = { if (!model.state.value.saving) picker.pick() }) {
            Icon(ServiceTagIcons.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(buttonText)
        }
    }
    state.staged.filter { it.role == role }.forEach { document ->
        StagedDocumentRow(
            document = document,
            onRemove = { if (!model.state.value.saving) model.unstage(document) },
        )
    }
    val existing = state.attached.filter { it.role == role }
    if (existing.isNotEmpty()) {
        FieldLabel(role.label())
        existing.forEach { AttachedDocumentRow(it) }
    }
}

/**
 * P67-10/12: one staged file — its name, [ATTACHED_WHEN_YOU_SAVE] or the failed copy's sentence, and
 * the reused visible `Remove` (the references' word; it has no shared home to draw from), whose
 * content description P67-12 names the file, since a screen reader cannot see which line it is on.
 */
@Composable
private fun StagedDocumentRow(document: StagedDocument, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = document.file.displayName, style = MaterialTheme.typography.bodyMedium)
            val problem = document.problem
            if (problem != null) RefusalLine(problem) else QuietLine(ATTACHED_WHEN_YOU_SAVE)
        }
        val removeLabel = stringResource(R.string.asset_edit_remove_file, document.file.displayName)
        TextButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = removeLabel }) {
            Text(stringResource(R.string.asset_edit_remove))
        }
    }
}

/** R67-6: one of the asset's own role-tagged files, read-only — its name alone. */
@Composable
private fun AttachedDocumentRow(document: AttachedDocument) {
    Text(text = document.displayName, style = MaterialTheme.typography.bodyMedium)
}

/**
 * A section heading in [SectionHeader]'s idiom — `labelMedium` on `onSurfaceVariant` over a 1dp rule
 * — **without** its upper-casing: S28, S58 and S111 are ratified in sentence case, and upper-casing a
 * ratified string is paraphrasing it. Internal because the health subject editor draws its own the
 * same way.
 */
@Composable
internal fun SentenceSectionHeader(title: String) {
    Column(modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** A question or field name over its answers (S35, S131, S134 here; S113, S117 and more in the subject editor). */
@Composable
internal fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * One answer of a question, as a radio row. The whole row is the control, so its words are its name
 * and nothing else needs saying; the radio mirrors the row.
 */
@Composable
internal fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}

/** A refusal that has ratified words, in the error colour under the section it is about. */
@Composable
internal fun RefusalLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/** The fields and helpers under a chosen answer, indented to sit under its words. */
@Composable
private fun Nested(content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 36.dp, top = 4.dp, bottom = 8.dp),
    ) {
        content()
    }
}

/**
 * The plain outlined field every section is made of, on the 6dp control corner (D12 §7). A
 * [problem] both reddens it and replaces the hint, so the one line under a field is always the
 * most urgent thing that field has to say.
 * Internal since #79: the service case editor (`ui/service`) draws its fields with it.
 */
@Composable
internal fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    problem: String? = null,
    hint: String? = null,
    minLines: Int = 1,
    mono: Boolean = false,
    numeric: Boolean = false,
    placeholder: String? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
    /** The error outline. It follows [problem] unless the caller's state decides it ([MonthDayInput.outlined]). */
    outlined: Boolean = problem != null,
) {
    val supporting = problem ?: hint
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder == null) null else { { Text(placeholder) } },
        isError = outlined,
        supportingText = if (supporting == null) null else { { Text(supporting) } },
        trailingIcon = trailingIcon,
        singleLine = minLines == 1,
        minLines = minLines,
        textStyle = if (mono) MonoText else LocalTextStyle.current,
        keyboardOptions = if (numeric) {
            KeyboardOptions(keyboardType = KeyboardType.Decimal)
        } else {
            KeyboardOptions.Default
        },
        shape = ControlShape,
        modifier = modifier,
    )
}

/**
 * Category is free text with the catalog of spec §8 behind it: an editable field whose menu
 * narrows by prefix as you type, so typing something the catalog never heard of is no harder
 * than picking a suggestion. Picking one is what the creation-time template hint listens to.
 *
 * #74 (C16): [choices] is the durable catalog — the built-ins, then the owner's own categories — and
 * one of the owner's reads exactly like a built-in. Nothing here writes it; a save does.
 * Internal since #86 (MN-3): Replace asset draws the new asset's Category with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryField(value: String, choices: List<CategoryChoice>, onValueChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val matches = choices.filter {
        value.isBlank() || it.display.startsWith(value.trim(), ignoreCase = true)
    }
    val open = expanded && matches.isNotEmpty()
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = { typed -> onValueChange(typed); expanded = true },
            label = { Text(CATEGORY_FIELD) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            shape = ControlShape,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { expanded = false }) {
            matches.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.display) },
                    onClick = { onValueChange(choice.display); expanded = false },
                )
            }
        }
    }
}

/**
 * A read-only field over a fixed list — "Part of", whose first row is always "None" (spec §5).
 * Internal since #86 (MN-3): Replace asset draws the new asset's Part of with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChoiceField(
    label: String,
    choices: List<ParentChoice>,
    selected: String?,
    onSelect: (String?) -> Unit,
    problem: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = choices.firstOrNull { it.id == selected }?.label ?: NO_PARENT
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            isError = problem != null,
            supportingText = if (problem == null) null else { { Text(problem) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = ControlShape,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = { onSelect(choice.id); expanded = false },
                )
            }
        }
    }
}

/**
 * An ISO date: typed, or picked from a calendar that writes the same `YYYY-MM-DD` text. Internal
 * rather than private because the retirement dialog of spec §7 asks for a date the same way, and
 * "how this app asks for a day" should have one owner.
 */
@Composable
internal fun DateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    problem: String? = null,
) {
    var picking by remember { mutableStateOf(false) }
    FormField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        problem = problem,
        placeholder = stringResource(R.string.asset_edit_date_placeholder),
        mono = true,
        trailingIcon = {
            IconButton(onClick = { picking = true }) {
                Icon(ServiceTagIcons.CalendarMonth, contentDescription = stringResource(R.string.asset_edit_pick_date, label))
            }
        },
    )
    if (picking) {
        CalendarDialog(
            initial = value,
            onDismiss = { picking = false },
            onPicked = { date -> onValueChange(date.toString()); picking = false },
        )
    }
}

/**
 * A `MM-DD` boundary: the same calendar, with the year it hands back thrown away (spec §6). The
 * [input] carries its label with the required mark, its outline and its one shipped line.
 */
@Composable
private fun MonthDayField(
    input: MonthDayInput,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    var picking by remember { mutableStateOf(false) }
    FormField(
        value = input.text,
        onValueChange = onValueChange,
        label = input.drawnLabel,
        problem = input.problem,
        outlined = input.outlined,
        placeholder = stringResource(R.string.asset_edit_month_day_placeholder),
        mono = true,
        modifier = modifier,
        trailingIcon = {
            IconButton(onClick = { picking = true }) {
                Icon(
                    ServiceTagIcons.CalendarMonth,
                    contentDescription = stringResource(R.string.asset_edit_pick_date, input.label),
                )
            }
        },
    )
    if (picking) {
        CalendarDialog(
            initial = "",
            onDismiss = { picking = false },
            onPicked = { date ->
                onValueChange(String.format(Locale.US, "%02d-%02d", date.monthValue, date.dayOfMonth))
                picking = false
            },
        )
    }
}

/**
 * The Material date picker in a dialog, in UTC both ways: the picker speaks epoch millis and the
 * app stores calendar dates, so a fixed offset is what keeps the day the user tapped the day that
 * gets written.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarDialog(initial: String, onDismiss: () -> Unit, onPicked: (LocalDate) -> Unit) {
    val start = runCatching { LocalDate.parse(initial) }.getOrNull()
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = start?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = picker.selectedDateMillis
                    if (millis != null) {
                        onPicked(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    } else {
                        onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.asset_edit_calendar_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.asset_edit_calendar_cancel)) } },
    ) {
        DatePicker(state = picker)
    }
}

/**
 * The five seeds plus None, as chips rather than a dropdown: six short options are quicker to
 * read side by side than behind a menu, and the default has to be visibly the selected one.
 */
@Composable
private fun TemplateRow(selected: String?, onSelect: (String?) -> Unit) {
    // The starter templates' names stay as shipped (#102): a template's words become the owner's own records.
    val options = listOf<Pair<String?, String>>(NONE to stringResource(R.string.asset_edit_template_none)) +
        SeedTemplates.all.map { it.key to it.name }
    Column {
        SectionHeader(title = stringResource(R.string.asset_edit_section_template))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            options.forEach { (key, label) ->
                FilterChip(
                    selected = key == selected,
                    onClick = { onSelect(key) },
                    label = { Text(label) },
                    shape = BadgeShape,
                )
            }
        }
        Text(
            text = stringResource(R.string.asset_edit_template_helper),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** "None · set up later" is the absence of a template key, not a template called none. */
private val NONE: String? = null
