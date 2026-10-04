package com.loosecannon.servicetag.ui.replace

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.journal.CategoryChoice
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.asset.CANCEL_BUTTON
import com.loosecannon.servicetag.ui.asset.CategoryField
import com.loosecannon.servicetag.ui.asset.ChoiceField
import com.loosecannon.servicetag.ui.asset.ChoiceRow
import com.loosecannon.servicetag.ui.asset.DateField
import com.loosecannon.servicetag.ui.asset.FieldLabel
import com.loosecannon.servicetag.ui.asset.FormField
import com.loosecannon.servicetag.ui.asset.IN_SEASON_NOW
import com.loosecannon.servicetag.ui.asset.IN_SERVICE_DATE_FIELD
import com.loosecannon.servicetag.ui.asset.LOCATION_FIELD
import com.loosecannon.servicetag.ui.asset.MANUFACTURER_FIELD
import com.loosecannon.servicetag.ui.asset.MODEL_FIELD
import com.loosecannon.servicetag.ui.asset.NAME_FIELD
import com.loosecannon.servicetag.ui.asset.OUT_OF_SEASON_NOW
import com.loosecannon.servicetag.ui.asset.PART_OF_FIELD
import com.loosecannon.servicetag.ui.asset.PURCHASE_DATE_FIELD
import com.loosecannon.servicetag.ui.asset.READINGS_AND_ACTIONS
import com.loosecannon.servicetag.ui.asset.RETIRED_ON_FIELD
import com.loosecannon.servicetag.ui.asset.RefusalLine
import com.loosecannon.servicetag.ui.asset.SERIAL_NUMBER_FIELD
import com.loosecannon.servicetag.ui.asset.TAGS_SECTION
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.maintenance.GROUPS_SECTION
import com.loosecannon.servicetag.ui.maintenance.SCHEDULES_SECTION
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.transfer.TransferStrings

/**
 * #86 (C17, C18) — **Replace asset**, one screen in two phases over [ReplaceAssetViewModel]. It is a pure drawing of
 * [ReplaceAssetState]: every word is a [ReplaceStrings] constant, a reused home's constant, or a line the model has
 * already composed; the screen composes none.
 *
 * - **FORM:** the old asset, the new asset on the editor's own fields, then every offered item **unticked** and every
 *   tag on Leave (R86-9, R86-14); `Review` is enabled iff the plan answers no problem.
 * - **REVIEW:** the lines of what will happen; P86-1 commits once, `Cancel` returns to the form writing nothing.
 * - **Leaving:** the top bar's arrow and the system Back share one action: from the review it is its `Cancel`, from
 *   the form it leaves, writing nothing; both are held only while the one write runs. GONE leaves at once. A finished
 *   replace hands the new asset's id to [onDone] once (C18).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplaceAssetScreen(graph: AppGraph, assetId: String, onBack: () -> Unit, onDone: (successorId: String) -> Unit) {
    val model: ReplaceAssetViewModel = viewModel(key = "replace/$assetId") { ReplaceAssetViewModel(graph, assetId) }
    val state by model.state.collectAsStateWithLifecycle()
    val categories by model.categoryChoices.collectAsStateWithLifecycle()

    val gone = state.phase == ReplacePhase.GONE
    LaunchedEffect(gone) { if (gone) onBack() }
    // The #84 one-shot lesson: the state carries the id, and takeDone() hands it over once.
    LaunchedEffect(state.done) { model.takeDone()?.let(onDone) }
    // One Back for the arrow and the system (#77's precedent): the review steps back to the form, anything else leaves.
    // Neither writes; both are held while the one write runs.
    val back: () -> Unit = { if (state.phase == ReplacePhase.REVIEW) model.backToForm() else onBack() }
    BackHandler(enabled = state.saving) { }
    BackHandler(enabled = !state.saving, onBack = back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ReplaceStrings.REPLACE_ASSET) },
                navigationIcon = {
                    // R86-B3-BACK: the shipped arrow and its shipped label, a reused convention rather than a P86 string.
                    IconButton(onClick = back, enabled = !state.saving) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.replace_back))
                    }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        val review = state.review
        when {
            state.phase == ReplacePhase.FORM -> ReplaceFormContent(state, categories, model, modifier)
            state.phase == ReplacePhase.REVIEW && review != null -> ReplaceReviewContent(state, review, model, modifier)
            state.phase == ReplacePhase.LOADING -> LinearProgressIndicator(modifier = modifier.fillMaxWidth())
            else -> Unit
        }
    }
}

@Composable
private fun ReplaceFormContent(
    state: ReplaceAssetState,
    categories: List<CategoryChoice>,
    model: ReplaceAssetViewModel,
    modifier: Modifier,
) {
    val form = state.form
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        // MN-2: P86-25 first, so a stale confirm's line is seen when the form reopens at the top.
        state.error?.let { RefusalLine(it) }
        SectionHeader(title = ReplaceStrings.OLD_ASSET)
        Text(text = state.oldAssetLine, style = MaterialTheme.typography.bodyMedium)
        if (state.asksRetiredOn) {
            DateField(
                value = form.retiredOn,
                onValueChange = model::onRetiredOn,
                label = RETIRED_ON_FIELD,
                problem = state.problems[ReplaceField.RETIRED_ON],
            )
        } else {
            // P86-4's case: the stored date is the one refused (R86-13a), so its line sits under the body.
            state.problems[ReplaceField.RETIRED_ON]?.let { RefusalLine(it) }
        }

        SectionHeader(title = ReplaceStrings.NEW_ASSET)
        QuietLine(ReplaceStrings.SEPARATE_ASSET)
        FormField(
            value = form.name,
            onValueChange = model::onName,
            label = NAME_FIELD,
            problem = state.problems[ReplaceField.NAME],
        )
        CategoryField(value = form.category, choices = categories, onValueChange = model::onCategory)
        FormField(value = form.location, onValueChange = model::onLocation, label = LOCATION_FIELD)
        ChoiceField(label = PART_OF_FIELD, choices = state.parentChoices, selected = form.parentId, onSelect = model::onParent)
        FormField(value = form.manufacturer, onValueChange = model::onManufacturer, label = MANUFACTURER_FIELD)
        FormField(value = form.model, onValueChange = model::onModel, label = MODEL_FIELD)
        FormField(value = form.serialNumber, onValueChange = model::onSerialNumber, label = SERIAL_NUMBER_FIELD)
        DateField(
            value = form.purchaseOn,
            onValueChange = model::onPurchaseOn,
            label = PURCHASE_DATE_FIELD,
            problem = state.problems[ReplaceField.PURCHASE_ON],
        )
        DateField(
            value = form.inServiceOn,
            onValueChange = model::onInServiceOn,
            label = IN_SERVICE_DATE_FIELD,
            problem = state.problems[ReplaceField.IN_SERVICE_ON],
        )

        SectionHeader(title = ReplaceStrings.CARRY_FORWARD)
        QuietLine(ReplaceStrings.ONLY_WHAT_YOU_TICK)
        if (state.seasonOffered) {
            TickRow(ReplaceStrings.SEASON_AND_BREAK, form.carrySeason, model::onCarrySeason)
            state.phaseQuestion?.let { question ->
                // P86-28 with S36 and S37, none chosen until the owner answers.
                Column(modifier = Modifier.padding(start = 36.dp)) {
                    Text(text = question, style = MaterialTheme.typography.bodyMedium)
                    ChoiceRow(IN_SEASON_NOW, form.manualPhase == SeasonPhase.IN_SEASON) {
                        model.onManualPhase(SeasonPhase.IN_SEASON)
                    }
                    ChoiceRow(OUT_OF_SEASON_NOW, form.manualPhase == SeasonPhase.OUT_OF_SEASON) {
                        model.onManualPhase(SeasonPhase.OUT_OF_SEASON)
                    }
                }
            }
        }
        if (state.setupOffered) TickRow(READINGS_AND_ACTIONS, form.carrySetup, model::onCarrySetup)
        if (state.notesOffered) TickRow(ReplaceStrings.DESCRIPTION_AND_NOTES, form.carryNotes, model::onCarryNotes)
        if (state.schedules.isNotEmpty()) {
            FieldLabel(SCHEDULES_SECTION)
            state.schedules.forEach { schedule ->
                TickRow(schedule.title, schedule.ticked) { model.onSchedule(schedule.id, it) }
                // P86-13 / P86-14: the dependency, as text under the schedule it blocks.
                schedule.needs.forEach { need -> Row(modifier = Modifier.padding(start = 36.dp)) { RefusalLine(need) } }
            }
        }
        if (state.asksScheduleStart) {
            DateField(
                value = form.scheduleStartOn,
                onValueChange = model::onScheduleStartOn,
                label = ReplaceStrings.SCHEDULES_START_ON,
                problem = state.problems[ReplaceField.SCHEDULE_START_ON],
            )
        }
        if (state.groups.isNotEmpty()) {
            FieldLabel(GROUPS_SECTION)
            state.groups.forEach { group -> TickRow(group.label, group.ticked) { model.onGroup(group.id, it) } }
        }
        // Named, never moved (R86-6, R86-7).
        state.childrenLine?.let { QuietLine(it) }
        state.loanLine?.let { QuietLine(it) }

        if (state.tags.isNotEmpty()) {
            SectionHeader(title = TAGS_SECTION)
            state.tags.forEach { tag ->
                Text(text = tag.identity, style = MaterialTheme.typography.bodyLarge)
                tag.placement?.let { QuietLine(it) }
                ChoiceRow(ReplaceStrings.LEAVE_WITH_OLD, !tag.moved) { model.onTagMove(tag.id, false) }
                ChoiceRow(ReplaceStrings.MOVE_TO_NEW, tag.moved) { model.onTagMove(tag.id, true) }
            }
            QuietLine(ReplaceStrings.MOVED_TAG_NOT_REWRITTEN)
        }

        Row {
            Spacer(Modifier.weight(1f))
            Button(onClick = model::review, enabled = state.reviewEnabled, shape = ControlShape) {
                Text(TransferStrings.REVIEW)
            }
        }
    }
}

@Composable
private fun ReplaceReviewContent(
    state: ReplaceAssetState,
    review: ReplaceReview,
    model: ReplaceAssetViewModel,
    modifier: Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text(text = review.retireLine, style = MaterialTheme.typography.bodyLarge)
        Text(text = review.createLine, style = MaterialTheme.typography.bodyLarge)
        FieldLabel(ReplaceStrings.CARRY_FORWARD)
        review.carried.forEach { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
        if (review.movedTags.isNotEmpty()) {
            FieldLabel(ReplaceStrings.MOVE_TO_NEW)
            review.movedTags.forEach { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
        }
        state.error?.let { RefusalLine(it) }
        if (state.saving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = model::backToForm, enabled = !state.saving) { Text(CANCEL_BUTTON) }
            Button(onClick = model::confirm, enabled = !state.saving, shape = ControlShape) {
                Text(ReplaceStrings.REPLACE_ASSET)
            }
        }
    }
}

/** One carry-forward item: the whole row is the checkbox, so its words are its name (R86-9: it starts unticked). */
@Composable
private fun TickRow(label: String, ticked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = ticked, role = Role.Checkbox, onValueChange = onToggle),
    ) {
        Checkbox(checked = ticked, onCheckedChange = null)
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}
