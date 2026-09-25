package com.loosecannon.servicetag.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.journal.Reading
import com.loosecannon.servicetag.core.model.ProfileConsumable
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.InstrumentEntryHeader
import com.loosecannon.servicetag.ui.components.InstrumentEntryRow
import com.loosecannon.servicetag.ui.components.InstrumentList
import com.loosecannon.servicetag.ui.components.InstrumentRow
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.condition.EventOfferDialog
import com.loosecannon.servicetag.ui.theme.BadgeShape
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.Eyebrow
import com.loosecannon.servicetag.ui.theme.MonoText

/**
 * The field test sheet of G1 §1.3: app bar with ✕, the eyebrow and Save; the date it is logged
 * against; one row per profile field under a READING / VALUE / TARGET header; the materials that
 * went in, kept deliberately apart from the readings (D12 §9 — measurement ≠ intervention); a
 * note; and Save again at the bottom so it stays reachable with the keyboard open. Anything the
 * asset derives from those inputs reads back live under them, never as a field (spec §5).
 *
 * The form knows nothing about what kind of asset this is: the rows come from the profile and the
 * control in each row comes from its definition's value type.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEntryScreen(
    graph: AppGraph,
    assetId: String,
    profileId: String?,
    eventId: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
    /** The `EventKind` name a new, profile-less entry opens with (spec §7); ignored otherwise. */
    kind: String? = null,
) {
    val model: EventEntryViewModel = viewModel(key = eventId ?: "new-$assetId-$profileId-$kind") {
        EventEntryViewModel(graph, assetId, profileId, eventId, kind)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val focus = LocalFocusManager.current

    // The save itself belongs to the ViewModel; this only listens for the one shot that says done.
    LaunchedEffect(model) { model.saved.collect { onDone() } }

    // 1.4: a just-logged event's one question — "Mark operational?" or the season offer — asked
    // before the screen leaves. Only its accept writes (spec §3.3, §5.4).
    state.offer?.let { EventOfferDialog(it, onAccept = model::acceptOffer, onDecline = model::declineOffer) }
    LaunchedEffect(state.firstProblem) { state.firstProblem?.let { snackbars.showSnackbar(it) } }

    // A profile-less entry has no name of its own in the bar, so it shows one part, not an orphan
    // separator. With a profile this reads exactly "WATER TEST · SPA".
    val eyebrow = listOf(state.profileName, state.assetName)
        .filter { it.isNotBlank() }
        .joinToString(" · ") { it.uppercase() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = eyebrow,
                        style = Eyebrow,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    TextButton(onClick = model::save, enabled = state.loaded && !state.saving) { Text("Save") }
                },
            )
        },
    ) { padding ->
        // A `Column` and not a `LazyColumn`: a lazy list disposes the row the keyboard is over as
        // soon as it scrolls out, which on a long profile took the focus and the IME with it
        // (spec §9, the 2A fix). Every row is composed, so `ImeAction.Next` reaches all of them.
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            LoggedBlock(
                occurredOn = state.occurredOn,
                occurredTime = state.occurredTime,
                onDate = model::onDate,
                onTime = model::onTime,
            )
            // With a profile behind it the title is the profile's and the bar already says it;
            // without one there is nothing else to name the entry, so the field appears.
            if (state.profileName.isBlank()) {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = model::onTitle,
                    label = { Text("Entry") },
                    singleLine = true,
                    shape = ControlShape,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
            if (state.fields.isNotEmpty()) {
                InstrumentEntryHeader()
                state.fields.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    InstrumentEntryRow(
                        row = row,
                        onValue = { model.onValue(row.definition.id, it) },
                        // Focus walks down the value column and stops at the last row (G1 §1.3).
                        imeAction = if (index == state.fields.lastIndex) ImeAction.Done else ImeAction.Next,
                        onNext = {
                            if (index == state.fields.lastIndex) {
                                focus.clearFocus()
                            } else {
                                focus.moveFocus(FocusDirection.Down)
                            }
                        },
                    )
                }
            }
            DerivedBlock(rows = state.derivedRows, underInputs = state.fields.isNotEmpty())
            MaterialsBlock(
                suggestions = state.suggestions,
                rows = state.consumables,
                onSuggested = model::addSuggested,
                onAdd = model::addBlankConsumable,
                onChange = model::onConsumable,
                onRemove = model::removeConsumable,
            )
            OutlinedTextField(
                value = state.notes,
                onValueChange = model::onNotes,
                label = { Text("Notes") },
                minLines = 3,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )
            Button(
                onClick = model::save,
                enabled = state.loaded && !state.saving,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            ) {
                Text(if (state.editing) "Save entry" else "Record entry")
            }
        }
    }
}

/**
 * The derived readings of spec §5, live from what has been typed: the same [InstrumentRow] the
 * asset screen draws, under the inputs and marked DERIVED, with an em dash until this entry's own
 * values can produce a number. Read-only by construction — there is no control here to type into.
 */
@Composable
private fun DerivedBlock(rows: List<Reading>, underInputs: Boolean) {
    if (rows.isEmpty()) return
    if (underInputs) {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
    InstrumentList(count = rows.size) { index ->
        val reading = rows[index]
        InstrumentRow(
            eyebrow = "Derived",
            label = reading.definition.label,
            target = formatTarget(reading.definition),
            value = formatValue(reading),
            unit = reading.definition.unit,
            state = reading.state,
        )
    }
}

/** When it happened, not when it was typed: the date is editable and backdating is expected (§4). */
@Composable
private fun LoggedBlock(
    occurredOn: String,
    occurredTime: String?,
    onDate: (String) -> Unit,
    onTime: (String?) -> Unit,
) {
    Column {
        SectionHeader(title = "Logged")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = occurredOn,
                onValueChange = onDate,
                label = { Text("Date") },
                placeholder = { Text("YYYY-MM-DD") },
                singleLine = true,
                textStyle = MonoText,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = ControlShape,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = occurredTime.orEmpty(),
                onValueChange = { onTime(it.ifBlank { null }) },
                label = { Text("Time") },
                placeholder = { Text("HH:MM") },
                singleLine = true,
                textStyle = MonoText,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = ControlShape,
                modifier = Modifier.width(118.dp),
            )
        }
    }
}

/**
 * What went in, kept in its own section (D12 §9). The profile's suggestions are chips that add a
 * row with its unit already filled; "+ Add material" opens an empty one for anything else.
 */
@Composable
private fun MaterialsBlock(
    suggestions: List<ProfileConsumable>,
    rows: List<ConsumableRow>,
    onSuggested: (ProfileConsumable) -> Unit,
    onAdd: () -> Unit,
    onChange: (Int, String?, String?, String?) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column {
        SectionHeader(title = "Materials used")
        if (suggestions.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                suggestions.forEach { suggestion ->
                    AssistChip(
                        onClick = { onSuggested(suggestion) },
                        label = { Text(suggestion.name) },
                        shape = BadgeShape,
                    )
                }
            }
        }
        rows.forEachIndexed { index, row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = row.name,
                    onValueChange = { onChange(index, it, null, null) },
                    label = { Text("Material") },
                    singleLine = true,
                    isError = row.problem,
                    shape = ControlShape,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = row.quantity,
                    onValueChange = { onChange(index, null, it, null) },
                    label = { Text("Qty") },
                    singleLine = true,
                    isError = row.problem,
                    textStyle = MonoText,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = ControlShape,
                    modifier = Modifier.width(78.dp),
                )
                OutlinedTextField(
                    value = row.unit,
                    onValueChange = { onChange(index, null, null, it) },
                    label = { Text("Unit") },
                    singleLine = true,
                    shape = ControlShape,
                    modifier = Modifier.width(74.dp),
                )
                IconButton(onClick = { onRemove(index) }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Remove material")
                }
            }
        }
        OutlinedButton(
            onClick = onAdd,
            shape = ControlShape,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Add material")
        }
    }
}
