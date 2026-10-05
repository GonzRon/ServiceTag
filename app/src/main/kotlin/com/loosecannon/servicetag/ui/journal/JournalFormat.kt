package com.loosecannon.servicetag.ui.journal

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.journal.RangeState
import com.loosecannon.servicetag.core.journal.Reading
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.Measurement
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.l10n.localizedDecimal
import com.loosecannon.servicetag.l10n.localizedDecimalSeparator
import com.loosecannon.servicetag.l10n.localizedFlag
import com.loosecannon.servicetag.l10n.parseLocalizedDecimal
import com.loosecannon.servicetag.ui.components.ServiceTagIcons
import com.loosecannon.servicetag.ui.theme.ServiceTagSemanticColors
import com.loosecannon.servicetag.ui.theme.StatusColor

/**
 * How the journal reads on screen: a definition's target, a stored value at the precision its
 * definition asks for, the exact state words of spec §10, and the one-line summary the service
 * record puts under an event's title.
 *
 * All pure but for the two that name a theme colour or a glyph, so the wording is pinned by a
 * plain JUnit test rather than by a screenshot.
 */

/** The three-way dash of D12 §9: a real interval, one bound, or nothing to compare against. */
fun formatTarget(definition: MeasurementDefinition): String {
    val low = definition.rangeLow
    val high = definition.rangeHigh
    return when {
        low != null && high != null -> "${bound(low, definition)}–${bound(high, definition)}"
        low != null -> "≥ ${bound(low, definition)}"
        high != null -> "≤ ${bound(high, definition)}"
        else -> localized(R.string.journal_no_target)
    }
}

/** Null when there is nothing logged for the definition yet; the row then shows an em dash. */
fun formatValue(measurement: Measurement?, definition: MeasurementDefinition): String? {
    val m = measurement ?: return null
    return when (definition.valueType) {
        // The label names the question ("Passed"), so the value only ever answers it (spec §7).
        ValueType.BOOLEAN -> localized(if (m.valueNum == 1.0) R.string.journal_yes else R.string.journal_no)
        ValueType.TEXT -> m.valueText
        ValueType.NUMBER -> m.valueNum?.let { bound(it, definition) }
    }
}

/**
 * A current reading, entered or derived. A DERIVED row has no measurement behind it (spec §5), so
 * its value comes off [Reading.derivedValue] — at the derived definition's own decimals, like any
 * other NUMBER. Null when this reading could not be computed, which every row draws as an em dash.
 */
fun formatValue(reading: Reading): String? =
    if (reading.definition.kind == DefinitionKind.DERIVED) {
        reading.derivedValue?.let { bound(it, reading.definition) }
    } else {
        formatValue(reading.measurement, reading.definition)
    }

/**
 * A number with only the decimals it needs: 1.0 as "1", 0.5 as "0.5" ("0,5" in German, #102). For the
 * values no definition bounds — a consumable's quantity, and a stored number typed back into an entry
 * field, which [neutralNumber] then reads back.
 */
fun formatNumber(value: Double): String = localizedDecimal(value)

/**
 * Owner-typed number text as the language-neutral text the use cases parse (#102): "0,5" typed in
 * German goes on as "0.5", and in English the text goes on exactly as typed. Text that is not a
 * number in the owner's language goes on as [NOT_A_NUMBER], which every use case refuses by its own
 * rule against the field it came from — never as typed, where a German "45.000" (forty-five thousand)
 * would be read as 45.
 */
internal fun neutralNumber(typed: String): String {
    val separator = localizedDecimalSeparator()
    if (separator == '.') return typed
    if (parseLocalizedDecimal(typed) == null) return NOT_A_NUMBER
    return typed.trim().replace(separator, '.')
}

/** What the use cases read as no finite number; never drawn, since a refused form keeps the owner's own text. */
private const val NOT_A_NUMBER = "NaN" // l10n-ok: a wire value the use cases refuse, never shown

fun stateLabel(state: RangeState): String = when (state) {
    RangeState.LOW -> localized(R.string.journal_state_low)
    RangeState.IN_RANGE -> localized(R.string.journal_state_in_range)
    RangeState.HIGH -> localized(R.string.journal_state_high)
    RangeState.NO_TARGET -> localized(R.string.journal_state_no_target)
}

fun stateColors(state: RangeState, colors: ServiceTagSemanticColors): StatusColor = when (state) {
    RangeState.LOW -> colors.measurementLow
    RangeState.IN_RANGE -> colors.measurementInRange
    RangeState.HIGH -> colors.measurementHigh
    RangeState.NO_TARGET -> colors.measurementNoTarget
}

/**
 * Colour is never the only carrier (D12 §5): every state has a word and a glyph too. Composable
 * because [ServiceTagIcons] resolves its vectors out of resources.
 */
@Composable
fun stateIcon(state: RangeState): ImageVector = when (state) {
    RangeState.LOW -> ServiceTagIcons.ArrowDownward
    RangeState.IN_RANGE -> Icons.Outlined.Check
    RangeState.HIGH -> ServiceTagIcons.ArrowUpward
    RangeState.NO_TARGET -> Icons.Outlined.Info
}

/**
 * "Water test" is what the profile is called; "Log water test" is what the button does. Only
 * decapitalized when the second character is itself lowercase, so an acronym like "TDS test" or
 * "UPS check" is not mangled into "tDS test" / "uPS check".
 */
fun quickActionLabel(profile: EventProfile): String {
    val name = profile.name
    // English lower-cases the name's first letter inside the sentence ("Log water test"), never an acronym's; a
    // language whose nouns keep their capital (German) turns this off in its own bools.xml (#102).
    val lower = localizedFlag(R.bool.journal_lowercase_profile_name) && name.length > 1 && name[1].isLowerCase()
    val label = if (lower) name.replaceFirstChar { it.lowercase() } else name
    return localized(R.string.journal_log_profile, label)
}

/**
 * The ledger's detail line: what this entry was, in one line. Readings first because that is what
 * a maintenance record is looked up for, then what was put in, then whatever the user wrote.
 */
fun eventDetailLine(event: AssetEvent, definitions: Map<DefinitionId, MeasurementDefinition>): String {
    val readings = event.measurements
        .sortedBy { it.sortOrder }
        .mapNotNull { m ->
            val def = definitions[m.definitionId] ?: return@mapNotNull null
            val value = formatValue(m, def) ?: return@mapNotNull null
            listOf(def.label, value, m.unit).filter { it.isNotBlank() }.joinToString(" ")
        }
    if (readings.isNotEmpty()) return readings.take(MAX_READINGS_IN_LINE).joinToString(" · ")

    event.consumables.minByOrNull { it.sortOrder }?.let { used ->
        return listOf(used.name, formatNumber(used.quantity), used.unit).filter { it.isNotBlank() }.joinToString(" ")
    }
    return event.notes.lineSequence().firstOrNull()?.trim().orEmpty()
}

/** Three readings is what fits on one line on a phone without the title having to shrink. */
private const val MAX_READINGS_IN_LINE = 3

/** At the definition's decimals, with the language's own decimal separator and no grouping (#102). */
private fun bound(value: Double, definition: MeasurementDefinition): String =
    localizedDecimal(value, definition.decimals.coerceAtLeast(0))
