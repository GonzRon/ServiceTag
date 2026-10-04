package com.loosecannon.servicetag.ui.journal

import com.loosecannon.servicetag.core.journal.Reading
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ConsumableUsage
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.DerivedFormula
import com.loosecannon.servicetag.core.model.DerivedSpec
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.Measurement
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.l10n.AppText
import com.loosecannon.servicetag.testing.EnglishResources
import com.loosecannon.servicetag.testing.ResourcePack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The display half of the journal: what a definition's target reads as, how a stored value is
 * rendered back, and the one-line summary the service record shows under an event's title. Pure
 * functions over domain rows, so this is plain JUnit with no dispatcher and no database.
 */
class JournalFormatTest {

    private fun definition(
        key: String,
        label: String,
        unit: String = "",
        valueType: ValueType = ValueType.NUMBER,
        decimals: Int = 1,
        low: Double? = null,
        high: Double? = null,
        kind: DefinitionKind = DefinitionKind.ENTERED,
        derived: DerivedSpec? = null,
    ) = MeasurementDefinition(
        id = DefinitionId("d-$key"),
        assetId = AssetId("a"),
        key = key,
        label = label,
        unit = unit,
        valueType = valueType,
        decimals = decimals,
        rangeLow = low,
        rangeHigh = high,
        isMeter = false,
        sortOrder = 0,
        archivedAt = null,
        createdAt = 1L,
        updatedAt = 1L,
        kind = kind,
        derived = derived,
    )

    private fun number(def: MeasurementDefinition, value: Double, sortOrder: Int = 0) = Measurement(
        id = "m-${def.key}",
        definitionId = def.id,
        valueNum = value,
        valueText = null,
        unit = def.unit,
        sortOrder = sortOrder,
    )

    private fun event(
        measurements: List<Measurement> = emptyList(),
        consumables: List<ConsumableUsage> = emptyList(),
        notes: String = "",
    ) = AssetEvent(
        id = EventId("e-1"),
        assetId = AssetId("a"),
        kind = EventKind.MEASUREMENT,
        title = "Water test",
        profileId = null,
        occurredOn = "2026-09-15",
        occurredTime = null,
        tzId = "UTC",
        notes = notes,
        source = EventSource.MANUAL,
        sourceRef = null,
        createdAt = 1L,
        updatedAt = 1L,
        measurements = measurements,
        consumables = consumables,
    )

    @Test fun targetsReadAsAnIntervalABoundOrNothing() {
        assertEquals("7.2–7.8", formatTarget(definition("ph", "pH", low = 7.2, high = 7.8)))
        assertEquals("≥ 80", formatTarget(definition("alk", "Alkalinity", decimals = 0, low = 80.0)))
        assertEquals("≤ 120", formatTarget(definition("alk", "Alkalinity", decimals = 0, high = 120.0)))
        assertEquals("No target", formatTarget(definition("temp", "Water temperature")))
    }

    @Test fun valuesRenderAtTheDefinitionsPrecision() {
        val ph = definition("ph", "pH", decimals = 1, low = 7.2, high = 7.8)
        assertEquals("7.8", formatValue(number(ph, 7.8), ph))

        val alk = definition("alk", "Alkalinity", unit = "ppm", decimals = 0)
        assertEquals("110", formatValue(number(alk, 110.0), alk))

        // BOOLEAN's label names the question ("Passed"), so the value is only ever Yes or No.
        val passed = definition("test_passed", "Passed", valueType = ValueType.BOOLEAN, decimals = 0)
        assertEquals("Yes", formatValue(number(passed, 1.0), passed))
        assertEquals("No", formatValue(number(passed, 0.0), passed))

        // A definition with nothing logged against it yet has no value to show.
        assertNull(formatValue(null, ph))
    }

    private fun profile(name: String) = EventProfile(
        id = ProfileId("p"),
        assetId = AssetId("a"),
        name = name,
        eventKind = EventKind.MEASUREMENT,
        defaultTitle = name,
        templateKey = "hot_tub",
        sortOrder = 0,
        archivedAt = null,
        createdAt = 1L,
        updatedAt = 1L,
        fields = emptyList(),
        consumables = emptyList(),
    )

    @Test fun aQuickActionIsTheProfileNameAsAVerbPhrase() {
        // A plain word decapitalizes; an acronym like "TDS" or "UPS" does not get mangled by it.
        assertEquals("Log water test", quickActionLabel(profile("Water test")))
        assertEquals("Log TDS test", quickActionLabel(profile("TDS test")))
        assertEquals("Log UPS check", quickActionLabel(profile("UPS check")))
        assertEquals("Log note", quickActionLabel(profile("Note")))
    }

    /** #102: lower-casing is English's rule, not the owner's name: German nouns keep their capital. */
    @Test fun germanKeepsTheActionNamesCapital() {
        val german = ResourcePack.pack("de")
        AppText.install(german)
        try {
            assertEquals(String.format(german.locale, german.stringNamed("journal_log_profile"), "Wassertest"), quickActionLabel(profile("Wassertest")))
        } finally {
            AppText.install(EnglishResources())
        }
        assertEquals("Log water test", quickActionLabel(profile("Water test")))
    }

    @Test fun theDetailLineFallsBackFromReadingsToMaterialsToNotes() {
        val ph = definition("ph", "pH", decimals = 1, low = 7.2, high = 7.8)
        val fc = definition("free_chlorine", "Free chlorine", unit = "ppm", decimals = 1)
        val alk = definition("alkalinity", "Alkalinity", unit = "ppm", decimals = 0)
        val temp = definition("water_temp", "Water temperature", unit = "°F", decimals = 0)
        val defs = listOf(ph, fc, alk, temp).associateBy { it.id }

        // Four readings, three shown: the ledger line is a summary, not the event.
        val measured = event(
            measurements = listOf(
                number(ph, 7.8, sortOrder = 0),
                number(fc, 0.8, sortOrder = 1),
                number(alk, 110.0, sortOrder = 2),
                number(temp, 102.0, sortOrder = 3),
            ),
        )
        assertEquals("pH 7.8 · Free chlorine 0.8 ppm · Alkalinity 110 ppm", eventDetailLine(measured, defs))

        val treated = event(
            consumables = listOf(
                ConsumableUsage(id = "c-1", name = "Chlorine", quantity = 1.0, unit = "oz", sortOrder = 0, supplyId = null),
                ConsumableUsage(id = "c-2", name = "pH reducer", quantity = 0.5, unit = "oz", sortOrder = 1, supplyId = null),
            ),
            notes = "topped up",
        )
        assertEquals("Chlorine 1 oz", eventDetailLine(treated, defs))

        assertEquals("Swapped the filter", eventDetailLine(event(notes = "Swapped the filter\nsecond line"), defs))
        assertEquals("", eventDetailLine(event(), defs))
    }

    /** The one number formatter of §10: a quantity with only the decimals it needs, nothing else. */
    @Test fun numbersKeepOnlyTheDecimalsTheyNeed() {
        assertEquals("1", formatNumber(1.0))
        assertEquals("0.5", formatNumber(0.5))
        assertEquals("110", formatNumber(110.0))
        assertEquals("-2.25", formatNumber(-2.25))
        assertEquals("0", formatNumber(0.0))
    }

    /**
     * #102 (PR #106 review): a stored number is drawn with the rendering language's decimal separator, at the
     * definition's precision — "7,4" in German, "7.4" in English — and the stored value stays the number itself.
     */
    @Test fun numbersTakeTheLanguagesDecimalSeparator() {
        val ph = definition("ph", "pH", decimals = 1, low = 7.2, high = 7.8)
        val fc = definition("free_chlorine", "Free chlorine", unit = "ppm", decimals = 2)
        val defs = listOf(ph, fc).associateBy { it.id }
        val measured = event(measurements = listOf(number(ph, 7.4, sortOrder = 0), number(fc, 1.5, sortOrder = 1)))

        AppText.install(ResourcePack.pack("de"))
        try {
            assertEquals("7,4", formatValue(number(ph, 7.4), ph))
            assertEquals("1,50", formatValue(number(fc, 1.5), fc))
            assertEquals("7,2–7,8", formatTarget(ph))
            assertEquals("0,5", formatNumber(0.5))
            assertEquals("110", formatNumber(110.0))
            assertEquals("pH 7,4 · Free chlorine 1,50 ppm", eventDetailLine(measured, defs))
            assertEquals("the stored number is untouched", 7.4, measured.measurements.first().valueNum!!, 0.0)
        } finally {
            AppText.install(EnglishResources())
        }

        assertEquals("7.4", formatValue(number(ph, 7.4), ph))
        assertEquals("1.50", formatValue(number(fc, 1.5), fc))
        assertEquals("7.2–7.8", formatTarget(ph))
        assertEquals("0.5", formatNumber(0.5))
        assertEquals("pH 7.4 · Free chlorine 1.50 ppm", eventDetailLine(measured, defs))
    }

    /**
     * #102 (PR #106 review): what the use cases read from a typed number. English goes on exactly as typed, as it
     * always did; German's "0,5" goes on as "0.5"; and text that is not a number in German is something no use case
     * reads as a finite number — "45.000" (forty-five thousand) must never be stored as 45.
     */
    @Test fun typedNumbersReachTheUseCasesInTheirNeutralForm() {
        fun readByUseCase(text: String) = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() }

        for (typed in listOf("2", " 0.5 ", "7,4", "1e3", "abc")) assertEquals(typed, neutralNumber(typed))

        AppText.install(ResourcePack.pack("de"))
        try {
            assertEquals("0.5", neutralNumber("0,5"))
            assertEquals("0.5", neutralNumber(" 0.5 "))
            assertEquals("2", neutralNumber("2"))
            assertEquals("-1.25", neutralNumber("-1,25"))
            for (refused in listOf("45.000", "1.234,5", "1,2,3", "abc", "")) {
                assertNull("$refused must not reach a use case as a number", readByUseCase(neutralNumber(refused)))
            }
        } finally {
            AppText.install(EnglishResources())
        }
    }

    /**
     * A derived reading carries no measurement (spec §5), so it is formatted from `derivedValue` at
     * the derived definition's own precision — and reads as nothing at all when this event could
     * not produce one, which the row draws as an em dash.
     */
    @Test fun aDerivedReadingFormatsAtItsOwnPrecision() {
        val rejection = definition(
            key = "rejection_percent",
            label = "Rejection",
            unit = "%",
            decimals = 1,
            kind = DefinitionKind.DERIVED,
            derived = DerivedSpec(DerivedFormula.PERCENT_DROP, DefinitionId("d-a"), DefinitionId("d-b")),
        )
        val computed = Reading(rejection, null, "2026-09-15", null, null, derivedValue = 94.19354838709677)
        assertEquals("94.2", formatValue(computed))

        val nothing = Reading(rejection, null, null, null, null, derivedValue = null)
        assertNull(formatValue(nothing))

        // An entered reading still comes off its measurement, through the same one-argument call.
        val ph = definition("ph", "pH", decimals = 1, low = 7.2, high = 7.8)
        assertEquals("7.4", formatValue(Reading(ph, number(ph, 7.4), "2026-09-15", null, null)))
    }

    @Test fun theStateWordsAreExactlyTheOnesTheSpecNames() {
        assertEquals("LOW", stateLabel(com.loosecannon.servicetag.core.journal.RangeState.LOW))
        assertEquals("IN RANGE", stateLabel(com.loosecannon.servicetag.core.journal.RangeState.IN_RANGE))
        assertEquals("HIGH", stateLabel(com.loosecannon.servicetag.core.journal.RangeState.HIGH))
        assertEquals("NO TARGET SET", stateLabel(com.loosecannon.servicetag.core.journal.RangeState.NO_TARGET))
    }
}
