package com.loosecannon.servicetag.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.journal.RangeState
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.usecase.FieldProblem
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.ui.journal.FieldRow
import com.loosecannon.servicetag.ui.journal.formatTarget
import com.loosecannon.servicetag.ui.journal.stateColors
import com.loosecannon.servicetag.ui.journal.stateIcon
import com.loosecannon.servicetag.ui.journal.stateLabel
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.Eyebrow
import com.loosecannon.servicetag.ui.theme.MeasurementEntryText
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme

/**
 * One line of the field test sheet of D12 §9: name and reference interval on the left, the value
 * large and monospaced in its own column, the state as a small marker under it. Deliberately not
 * a card and deliberately not a row filled orange — the word does the work, the colour only backs
 * it up (D12 §5). [InstrumentList] supplies the hairline between rows.
 *
 * [value] is already formatted; null means nothing has been logged for this definition yet, which
 * reads as an em dash and carries no state.
 *
 * [eyebrow] is the small word over the label that says where the number came from when that is not
 * obvious — "DERIVED" on a computed row (spec §5), which is never an input and never stored.
 */
@Composable
fun InstrumentRow(
    label: String,
    target: String,
    value: String?,
    unit: String,
    state: RangeState?,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
) {
    Row(modifier = modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(
                    text = eyebrow.uppercase(),
                    style = Eyebrow,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = target,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.width(VALUE_COLUMN),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = value ?: "—",
                style = MeasurementEntryText,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (unit.isNotBlank() && value != null) {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(modifier = Modifier.width(STATE_COLUMN), horizontalAlignment = Alignment.End) {
            if (state != null) {
                StatusBadge(
                    label = stateLabel(state),
                    colors = stateColors(state, ServiceTagTheme.semanticColors),
                    icon = stateIcon(state),
                )
            }
        }
    }
}

/** Stacks [InstrumentRow]s with the ledger's 1dp `outlineVariant` rule between them, none around. */
@Composable
fun InstrumentList(count: Int, modifier: Modifier = Modifier, row: @Composable (Int) -> Unit) {
    Column(modifier = modifier) {
        repeat(count) { index ->
            if (index > 0) HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            row(index)
        }
    }
}

/** Wide enough for "-999.9" at the 22sp entry size, so the decimal points line up down the column. */
private val VALUE_COLUMN = 92.dp

/** "NO TARGET SET" wraps to two short lines here rather than stealing width from the label. */
private val STATE_COLUMN = 72.dp

/**
 * The header over a column of [InstrumentEntryRow]s (G1 §1.3), in the same three widths the rows
 * use so the words sit over what they name.
 */
@Composable
fun InstrumentEntryHeader(modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
        HeaderCell(stringResource(R.string.ui_instrument_header_reading), Modifier.weight(1f))
        HeaderCell(stringResource(R.string.ui_instrument_header_value), Modifier.width(VALUE_COLUMN))
        HeaderCell(stringResource(R.string.ui_instrument_header_target), Modifier.width(STATE_COLUMN))
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(
        text = text.uppercase(),
        style = Eyebrow,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * The writable twin of [InstrumentRow]: the same three columns, with the value column an outlined
 * field instead of a number. The state marker sits under the label and a 3dp rail in the state
 * colour runs down the row (G1 §1.3, D12 §9) — the row itself is never tinted.
 *
 * The control the row draws is decided by the definition's [ValueType] and nothing else. Every
 * value carries `testTag("value-<key>")`, which is how the instrumented test finds its field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstrumentEntryRow(
    row: FieldRow,
    onValue: (String) -> Unit,
    imeAction: ImeAction,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val definition = row.definition
    val live = row.liveState
    val rail = live?.let { stateColors(it, ServiceTagTheme.semanticColors).foreground }
    val keyboard = KeyboardActions(onNext = { onNext() }, onDone = { onNext() })
    val tag = Modifier.testTag("value-${definition.key}")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind { rail?.let { drawRect(color = it, size = Size(RAIL.toPx(), size.height)) } }
            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = definition.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                when {
                    live != null -> StatusBadge(
                        label = stateLabel(live),
                        colors = stateColors(live, ServiceTagTheme.semanticColors),
                        icon = stateIcon(live),
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    // Said once, quietly, until it is filled: a required row is not an error yet.
                    row.required && row.text.isBlank() -> Text(
                        text = stringResource(R.string.ui_instrument_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (definition.valueType) {
                ValueType.NUMBER -> {
                    OutlinedTextField(
                        value = row.text,
                        onValueChange = onValue,
                        singleLine = true,
                        isError = row.problem != null,
                        textStyle = MeasurementEntryText,
                        suffix = if (definition.unit.isNotBlank()) {
                            { Text(definition.unit, style = MaterialTheme.typography.bodySmall) }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = imeAction,
                        ),
                        keyboardActions = keyboard,
                        shape = ControlShape,
                        modifier = Modifier.width(VALUE_COLUMN).then(tag),
                    )
                    Text(
                        text = formatTarget(definition),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(STATE_COLUMN).padding(start = 8.dp),
                    )
                }
                // The label names the question ("Passed"), so the control only answers it (§7).
                ValueType.BOOLEAN -> SingleChoiceSegmentedButtonRow(modifier = Modifier.width(WIDE_COLUMN).then(tag)) {
                    BOOLEAN_CHOICES.forEachIndexed { index, (stored, label) ->
                        SegmentedButton(
                            selected = row.text == stored,
                            onClick = { onValue(stored) },
                            shape = SegmentedButtonDefaults.itemShape(index, BOOLEAN_CHOICES.size),
                        ) {
                            Text(label)
                        }
                    }
                }
                ValueType.TEXT -> OutlinedTextField(
                    value = row.text,
                    onValueChange = onValue,
                    singleLine = true,
                    isError = row.problem != null,
                    keyboardOptions = KeyboardOptions(imeAction = imeAction),
                    keyboardActions = keyboard,
                    shape = ControlShape,
                    modifier = Modifier.width(WIDE_COLUMN).then(tag),
                )
            }
        }
        row.problem?.let { problem ->
            Text(
                text = problemText(problem),
                style = MaterialTheme.typography.bodySmall,
                color = ServiceTagTheme.semanticColors.due.foreground,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** What a refused save says under the row it refused; the app bar says the first of them out loud. */
private fun problemText(problem: FieldProblem): String = when (problem) {
    is FieldProblem.Required -> localized(R.string.ui_instrument_required)
    is FieldProblem.NotANumber -> localized(R.string.ui_instrument_not_a_number)
    else -> localized(R.string.ui_instrument_check_value)
}

/** Stored as 1/0 by the domain (§4), read as Yes/No by everyone else. The stored values are never words. */
private val BOOLEAN_CHOICES: List<Pair<String, String>>
    get() = listOf("1" to localized(R.string.ui_instrument_yes), "0" to localized(R.string.ui_instrument_no))

/** The rail of D12 §9: a marker beside the row, never a fill behind it. */
private val RAIL = 3.dp

/** A segmented control or a sentence needs the value and target columns together. */
private val WIDE_COLUMN = 172.dp
