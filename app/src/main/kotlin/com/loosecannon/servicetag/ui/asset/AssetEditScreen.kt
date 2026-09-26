package com.loosecannon.servicetag.ui.asset

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.journal.CategorySuggestions
import com.loosecannon.servicetag.core.journal.SeedTemplates
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.components.ServiceTagIcons
import com.loosecannon.servicetag.ui.health.RESTORE_SUBJECT
import com.loosecannon.servicetag.ui.theme.BadgeShape
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.MonoText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

// The asset editor's season, break and health words (spec §10.7), RATIFIED, each by its S-number and
// verbatim. S132 is one ratified set of words, split only at its "·" separators.

/** S28, asset editor section. */
const val OPERATING_SEASON = "Operating season"

/** S29, mode (re). */
const val YEAR_ROUND = "Year-round"

/** S30, mode. */
const val SAME_DATES_EVERY_YEAR = "Same dates every year"

/** S31, mode. */
const val STARTED_AND_ENDED_BY_HAND = "Started and ended by hand"

/** S32, field (re). */
const val SEASON_STARTS = "Season starts"

/** S33, field (re). */
const val SEASON_ENDS = "Season ends"

/** S34, helper under S32 and S33. */
const val SEASON_MAY_RUN_ACROSS_THE_NEW_YEAR =
    "The season may run across the new year, for example from October to April."

/** S35, the switch question: asked only on a switch into S31, with no default. */
const val IS_THIS_ASSET_IN_SEASON = "Is this asset in season right now?"

/** S36, option. */
const val IN_SEASON_NOW = "In season"

/** S37, option (re). */
const val OUT_OF_SEASON_NOW = "Out of season"

/** S38, helper under S31. */
const val YOU_START_AND_END_THE_SEASON =
    "You start and end the season yourself. Maintenance set to follow the season waits while it is ended."

/** S55, refusal; `<titles>` is the stranded schedules' titles — see [seasonStrands]. */
const val SEASON_STRANDS_PRE_SERVICE =
    "Some maintenance on this asset is set to be ready before its season. Change it first: <titles>."

/** S58, asset editor section. */
const val MAINTENANCE_BREAK = "Maintenance break"

/** S59, toggle: off unless a break is stored. */
const val NO_ROUTINE_MAINTENANCE_BETWEEN_TWO_DATES = "No routine maintenance between two dates"

/** S60, field. */
const val BREAK_STARTS = "Break starts"

/** S61, field. */
const val BREAK_ENDS = "Break ends"

/** S62, helper under S60 and S61. */
const val BREAK_HELPER =
    "Maintenance set to follow the season, or to be ready before it, does not become due during the break. " +
        "Work already overdue stays overdue, without reminders."

/** S63, refusal: `BLACKOUT_COVERS_THE_YEAR`. */
const val BREAK_CANNOT_COVER_THE_YEAR = "The break cannot cover the whole year."

/** S64, refusal; `<titles>` is the stranded schedules' titles — see [breakStrands]. */
const val BREAK_STRANDS_PRE_SERVICE =
    "Some maintenance on this asset is set to be ready before the break. Change it first: <titles>."

/** S111, section. */
const val HEALTH_SUBJECTS = "Health subjects"

/** S112, action: opens the health subject editor for this asset (B14's asset detail uses it too). */
const val ADD_HEALTH_SUBJECT = "Add health subject"

/** S131, field. */
const val COMBINE_HEALTH_BY = "Combine health by"

/** S132, options, one ratified set: split only at its "·" separators, in its own order. */
const val COMBINE_HEALTH_OPTIONS = "Worst subject · One subject · Average · Weighted average"

/** S134, field under "One subject". */
const val WHICH_SUBJECT = "Which subject?"

// #78's reconciliation prompt (plan §5), RATIFIED 2026-09-25, verbatim. The dialog has no title (R-2).

/** P78-1a, the dialog's body when the count is not 1; `<n>` is the count — see [notTiedToSeason]. */
const val NOT_TIED_TO_SEASON =
    "This asset has <n> maintenance schedules that are not tied to its operating season. " +
        "When active, they can become or remain due while the asset is out of season " +
        "unless you change when that maintenance should be done."

/** P78-1b, the dialog's body when the count is 1. */
const val NOT_TIED_TO_SEASON_ONE =
    "This asset has 1 maintenance schedule that is not tied to its operating season. " +
        "When active, it can become or remain due while the asset is out of season " +
        "unless you change when that maintenance should be done."

/** P78-2, the dialog's confirm button: the editor closes onto the asset's schedules. */
const val REVIEW_MAINTENANCE_SCHEDULES = "Review maintenance schedules"

/** P78-3, the dialog's dismiss button: the editor closes and the schedules stay as they are. */
const val KEEP_SCHEDULES_AS_IS = "Keep schedules as-is"

/** P78-1b for one schedule, otherwise P78-1a with its one substitution: the live CONTINUOUS count. */
fun notTiedToSeason(count: Int): String =
    if (count == 1) NOT_TIED_TO_SEASON_ONE else NOT_TIED_TO_SEASON.replace("<n>", count.toString())

/** S132's four words, each with the aggregation it names, in the ratified order. */
internal val COMBINE_CHOICES: List<Pair<HealthAggregation, String>> =
    listOf(
        HealthAggregation.WORST,
        HealthAggregation.TRACK_ONE,
        HealthAggregation.AVERAGE,
        HealthAggregation.WEIGHTED,
    ).zip(ratifiedParts(COMBINE_HEALTH_OPTIONS))

/** One ratified set of words, split only at its "·" separators (spec §10.7). */
internal fun ratifiedParts(words: String): List<String> = words.split(" · ")

/**
 * Create ([assetId] null) or edit one asset: the grouped form of spec §9 — IDENTITY, PLACEMENT,
 * PURCHASE, WARRANTY, NOTES, and on a new asset only, TEMPLATE. Save sits in the app bar and
 * again at the bottom so it is reachable with the keyboard open (G1 §1.3).
 *
 * [parentId] is the "Part of" a new asset opens with, which is how "+ Add component" on a
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
) {
    // The key carries the parent as well as the id: "+ Add component" on two different parents
    // must not share one half-filled form, and neither must a plain "Add asset" and a component.
    val model: AssetEditViewModel = viewModel(key = assetId ?: "new-${parentId ?: "root"}") {
        AssetEditViewModel(graph, assetId, parentId)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val prompt by model.prompt.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }

    // The save itself belongs to the ViewModel; this only listens for where it says to go next.
    LaunchedEffect(model) { model.saved.collect { id -> onDone(id.value) } }
    LaunchedEffect(model) { model.review.collect { id -> onReviewSchedules(id.value) } }
    LaunchedEffect(model) { model.messages.collect { snackbars.showSnackbar(it) } }

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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(if (state.editing) "Edit asset" else "New asset") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    TextButton(onClick = model::save, enabled = state.canSave) { Text("Save") }
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
            PurchaseBlock(state, model)
            WarrantyBlock(state, model)

            SectionHeader(title = "Notes")
            FormField(
                value = state.notes,
                onValueChange = model::onNotes,
                label = "Notes",
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
                Text("Save asset")
            }
        }
    }
}

/** What the thing is. Name first, because it is the one field every version of this app required. */
@Composable
private fun IdentityBlock(state: AssetEditState, model: AssetEditViewModel) {
    SectionHeader(title = "Identity")
    FormField(
        value = state.name,
        onValueChange = model::onName,
        label = "Name",
        problem = state.problems[AssetField.NAME],
    )
    CategoryField(value = state.category, onValueChange = model::onCategory)
    FormField(value = state.manufacturer, onValueChange = model::onManufacturer, label = "Manufacturer")
    FormField(value = state.model, onValueChange = model::onModel, label = "Model")
    FormField(value = state.serialNumber, onValueChange = model::onSerialNumber, label = "Serial number")
    FormField(value = state.description, onValueChange = model::onDescription, label = "Description")
}

/** Where it is and what it is part of (spec §5). When it is in use is "Operating season", below. */
@Composable
private fun PlacementBlock(state: AssetEditState, model: AssetEditViewModel) {
    SectionHeader(title = "Placement")
    FormField(value = state.location, onValueChange = model::onLocation, label = "Location")
    ChoiceField(
        label = "Part of",
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
 */
@Composable
private fun OperatingSeasonBlock(state: AssetEditState, model: AssetEditViewModel) {
    SentenceSectionHeader(OPERATING_SEASON)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ChoiceRow(YEAR_ROUND, state.seasonMode == SeasonMode.YEAR_ROUND) {
            model.onSeasonMode(SeasonMode.YEAR_ROUND)
        }
        ChoiceRow(SAME_DATES_EVERY_YEAR, state.seasonMode == SeasonMode.CALENDAR) {
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
        ChoiceRow(STARTED_AND_ENDED_BY_HAND, state.seasonMode == SeasonMode.MANUAL) {
            model.onSeasonMode(SeasonMode.MANUAL)
        }
        if (state.seasonMode == SeasonMode.MANUAL) {
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

/** What it cost and when it arrived. A price is text until [priceHint]'s currency resolves it. */
@Composable
private fun PurchaseBlock(state: AssetEditState, model: AssetEditViewModel) {
    SectionHeader(title = "Purchase")
    DateField(
        value = state.purchaseOn,
        onValueChange = model::onPurchaseOn,
        label = "Purchase date",
        problem = state.problems[AssetField.PURCHASE_ON],
    )
    DateField(
        value = state.inServiceOn,
        onValueChange = model::onInServiceOn,
        label = "In service date",
        problem = state.problems[AssetField.IN_SERVICE_ON],
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        FormField(
            value = state.price,
            onValueChange = model::onPrice,
            label = "Price",
            problem = state.problems[AssetField.PRICE],
            hint = priceHint(state.currency),
            mono = true,
            numeric = true,
            modifier = Modifier.weight(1f),
        )
        FormField(
            value = state.currency,
            onValueChange = model::onCurrency,
            label = "Currency",
            problem = state.problems[AssetField.CURRENCY],
            mono = true,
            modifier = Modifier.width(126.dp),
        )
    }
    FormField(value = state.vendor, onValueChange = model::onVendor, label = "Vendor")
}

/** When the cover runs out, and whatever the paperwork says about it. */
@Composable
private fun WarrantyBlock(state: AssetEditState, model: AssetEditViewModel) {
    SectionHeader(title = "Warranty")
    DateField(
        value = state.warrantyExpiresOn,
        onValueChange = model::onWarrantyExpiresOn,
        label = "Expires on",
        problem = state.problems[AssetField.WARRANTY_EXPIRES_ON],
    )
    FormField(
        value = state.warrantyNotes,
        onValueChange = model::onWarrantyNotes,
        label = "Warranty notes",
        minLines = 2,
    )
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
 */
@Composable
private fun FormField(
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryField(value: String, onValueChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val matches = CategorySuggestions.all.filter {
        value.isBlank() || it.label.startsWith(value.trim(), ignoreCase = true)
    }
    val open = expanded && matches.isNotEmpty()
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = { typed -> onValueChange(typed); expanded = true },
            label = { Text("Category") },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            shape = ControlShape,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { expanded = false }) {
            matches.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion.label) },
                    onClick = { onValueChange(suggestion.label); expanded = false },
                )
            }
        }
    }
}

/** A read-only field over a fixed list — "Part of", whose first row is always "None" (spec §5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceField(
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
        placeholder = "YYYY-MM-DD",
        mono = true,
        trailingIcon = {
            IconButton(onClick = { picking = true }) {
                Icon(ServiceTagIcons.CalendarMonth, contentDescription = "Pick $label")
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
        placeholder = "MM-DD",
        mono = true,
        modifier = modifier,
        trailingIcon = {
            IconButton(onClick = { picking = true }) {
                Icon(ServiceTagIcons.CalendarMonth, contentDescription = "Pick ${input.label}")
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
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
    val options = listOf<Pair<String?, String>>(NONE to "None · set up later") +
        SeedTemplates.all.map { it.key to it.name }
    Column {
        SectionHeader(title = "Template")
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
            text = "Starts the asset with its readings and quick actions. " +
                "Choose None to decide on the asset later.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** "None · set up later" is the absence of a template key, not a template called none. */
private val NONE: String? = null
