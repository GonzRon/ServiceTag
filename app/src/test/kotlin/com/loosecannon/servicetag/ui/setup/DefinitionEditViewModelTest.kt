package com.loosecannon.servicetag.ui.setup

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.l10n.AppText
import com.loosecannon.servicetag.testing.EnglishResources
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.ResourcePack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The definition editor's form state against a Room-backed [FakeGraph]. Validation belongs to
 * `SaveDefinition`; what is proved here is that the form sends what was typed, puts every problem
 * that comes back under the field it belongs to, and writes nothing when a save is refused.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefinitionEditViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private fun model(assetId: AssetId, definitionId: DefinitionId? = null) = DefinitionEditViewModel(
        graph.definitions, graph.profiles, graph.events,
        graph.saveDefinition, graph.archiveDefinition, graph.deleteDefinition,
        assetId, definitionId,
    )

    private fun tdsTest(assetId: AssetId, values: Map<DefinitionId, String>) = EventCommand(
        assetId = assetId,
        profileId = null,
        kind = EventKind.MEASUREMENT,
        title = "TDS test",
        occurredOn = "2026-09-15",
        occurredTime = null,
        tzId = "UTC",
        notes = "",
        values = values,
        consumables = emptyList(),
    )

    @Test fun aNewEnteredReadingSavesUnderTheKeyItsLabelGenerates() = runTest {
        val thing = graph.createAsset.run("Thing", "Misc")
        val vm = model(thing.id)
        val saved = mutableListOf<DefinitionId>()
        backgroundScope.launch { vm.saved.collect { saved += it } }
        vm.state.first { it.loaded }

        vm.onLabel("Water temp")
        vm.onUnit("°C")
        vm.onDecimals("1")
        vm.onRangeLow("36")
        vm.onRangeHigh("40")
        // The key field shows what the label generates until someone types in it.
        assertEquals("water_temp", vm.state.value.key)
        assertFalse(vm.state.value.keyEdited)

        vm.save()
        // The second tap lands in the same frame: the in-flight guard drops it.
        vm.save()
        vm.state.first { !it.saving && it.problems.isEmpty() }

        val stored = graph.definitions.forAsset(thing.id).single()
        assertEquals("water_temp", stored.key)
        assertEquals("Water temp", stored.label)
        assertEquals("°C", stored.unit)
        assertEquals(ValueType.NUMBER, stored.valueType)
        assertEquals(DefinitionKind.ENTERED, stored.kind)
        assertEquals(36.0, stored.rangeLow!!, 1e-9)
        assertEquals(40.0, stored.rangeHigh!!, 1e-9)
        assertEquals(listOf(stored.id), saved)
    }

    /**
     * #102 (PR #106 review): a target is typed in the owner's language. Under German "0,5" is a half, stored as the
     * number 0.5, and the edit form draws the stored bounds back as "0,5" and "1,5" so they read back unchanged.
     * English never read a comma as a decimal point: there "0,5" is still not a number, and nothing is written.
     */
    @Test fun aTargetIsTypedWithTheLanguagesDecimalSeparator() = runTest {
        val thing = graph.createAsset.run("Thing", "Misc")
        AppText.install(ResourcePack.pack("de"))
        try {
            val vm = model(thing.id)
            vm.state.first { it.loaded }
            vm.onLabel("Chlorine")
            vm.onRangeLow("0,5")
            vm.onRangeHigh("1,5")
            vm.save()
            vm.state.first { !it.saving && it.problems.isEmpty() }

            val stored = graph.definitions.forAsset(thing.id).single()
            assertEquals(0.5, stored.rangeLow!!, 0.0)
            assertEquals(1.5, stored.rangeHigh!!, 0.0)

            val editing = model(thing.id, stored.id).state.first { it.loaded }
            assertEquals("0,5", editing.rangeLow)
            assertEquals("1,5", editing.rangeHigh)
        } finally {
            AppText.install(EnglishResources())
        }

        val english = model(thing.id)
        english.state.first { it.loaded }
        english.onLabel("Bromine")
        english.onRangeLow("0,5")
        english.save()
        val refused = english.state.first { !it.saving && it.problems.isNotEmpty() }
        assertEquals("Not a number", refused.problems["rangeLow"])
        assertEquals(1, graph.definitions.forAsset(thing.id).size)
    }

    @Test fun typingAKeyStopsItFollowingTheLabel() = runTest {
        val thing = graph.createAsset.run("Thing", "Misc")
        val vm = model(thing.id)
        vm.state.first { it.loaded }

        vm.onLabel("Water temp")
        vm.onKey("temp_c")
        vm.onLabel("Water temperature at the inlet")

        assertTrue(vm.state.value.keyEdited)
        assertEquals("temp_c", vm.state.value.key)

        vm.save()
        vm.state.first { !it.saving && it.problems.isEmpty() }
        assertEquals("temp_c", graph.definitions.forAsset(thing.id).single().key)
    }

    @Test fun aNewDerivedReadingSavesWithItsTwoSources() = runTest {
        val ro = graph.createAsset.run("RO unit", "Water", templateKey = "ro_water")
        val defs = graph.definitions.forAsset(ro.id).associateBy(MeasurementDefinition::key)
        val vm = model(ro.id)
        vm.state.first { it.loaded }

        // Only the readings a person enters as plain numbers can be a source (spec §4).
        assertEquals(
            listOf("tds_prefilter", "tds_post_membrane", "tds_output"),
            vm.state.value.sources.map(MeasurementDefinition::key),
        )

        vm.onLabel("Output rejection")
        vm.onKind(DefinitionKind.DERIVED)
        // The unit a percentage is in is the only sensible default, so the form fills it in.
        assertEquals("%", vm.state.value.unit)
        vm.onSourceA(defs.getValue("tds_prefilter").id)
        vm.onSourceB(defs.getValue("tds_output").id)
        vm.save()
        vm.state.first { !it.saving && it.problems.isEmpty() }

        val stored = graph.definitions.forAsset(ro.id).single { it.key == "output_rejection" }
        assertEquals(DefinitionKind.DERIVED, stored.kind)
        assertEquals(ValueType.NUMBER, stored.valueType)
        assertFalse(stored.isMeter)
        assertEquals(defs.getValue("tds_prefilter").id, stored.derived!!.sourceA)
        assertEquals(defs.getValue("tds_output").id, stored.derived!!.sourceB)
    }

    @Test fun oneSourceTwiceIsNamedUnderTheSourcePickers() = runTest {
        val ro = graph.createAsset.run("RO unit", "Water", templateKey = "ro_water")
        val defs = graph.definitions.forAsset(ro.id).associateBy(MeasurementDefinition::key)
        val vm = model(ro.id)
        vm.state.first { it.loaded }

        vm.onLabel("Silly")
        vm.onKind(DefinitionKind.DERIVED)
        vm.onSourceA(defs.getValue("tds_output").id)
        vm.onSourceB(defs.getValue("tds_output").id)
        vm.save()

        val refused = vm.state.first { !it.saving && it.problems.isNotEmpty() }
        assertEquals("Choose two different number readings", refused.problems["sources"])
        assertEquals(4, graph.definitions.forAsset(ro.id).size)
    }

    @Test fun problemsLandUnderTheFieldTheyBelongTo() = runTest {
        val thing = graph.createAsset.run("Thing", "Misc")
        val vm = model(thing.id)
        vm.state.first { it.loaded }

        vm.onDecimals("9")
        vm.onRangeLow("10")
        vm.onRangeHigh("1")
        vm.save()

        val refused = vm.state.first { !it.saving && it.problems.isNotEmpty() }
        assertEquals("Give the reading a label", refused.problems["label"])
        assertEquals("Decimals must be 0–4", refused.problems["decimals"])
        assertEquals("Low must not exceed high", refused.problems["rangeLow"])
        assertTrue(graph.definitions.forAsset(thing.id).isEmpty())

        // Typing in a field clears only its own mark, so nothing is red before the next try.
        vm.onLabel("Depth")
        assertNull(vm.state.value.problems["label"])
        assertEquals("Decimals must be 0–4", vm.state.value.problems["decimals"])
    }

    @Test fun aReadingWithStoredMeasurementsReportsHowManyAndFreezesItsKey() = runTest {
        val ro = graph.createAsset.run("RO unit", "Water", templateKey = "ro_water")
        val defs = graph.definitions.forAsset(ro.id).associateBy(MeasurementDefinition::key)
        val output = defs.getValue("tds_output")
        graph.logEvent.run(tdsTest(ro.id, mapOf(output.id to "16")))

        val vm = model(ro.id, output.id)
        val loaded = vm.state.first { it.loaded }

        assertEquals(1, loaded.inUse)
        assertEquals("tds_output", loaded.key)
        assertEquals("Output TDS", loaded.label)
        assertTrue(loaded.editing)

        // A label edit is always allowed, in use or not: it is not how a measurement is read back.
        vm.onLabel("Output TDS (product)")
        vm.save()
        vm.state.first { !it.saving && it.problems.isEmpty() }
        assertEquals("Output TDS (product)", graph.definitions.get(output.id)!!.label)
        assertEquals("tds_output", graph.definitions.get(output.id)!!.key)
    }

    @Test fun aSourceChangeThatWouldBreakADerivedReadingIsRefusedOutLoud() = runTest {
        val ro = graph.createAsset.run("RO unit", "Water", templateKey = "ro_water")
        val prefilter = graph.definitions.forAsset(ro.id).single { it.key == "tds_prefilter" }
        val vm = model(ro.id, prefilter.id)
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        vm.state.first { it.loaded }

        vm.onValueType(ValueType.TEXT)
        vm.save()
        vm.state.first { !it.saving }

        // The prospective graph check of spec §6, named so the user knows what to fix first.
        assertTrue(messages.single().contains("Rejection"))
        assertEquals(ValueType.NUMBER, graph.definitions.get(prefilter.id)!!.valueType)
    }

    @Test fun deletingAReferencedReadingFromTheEditorSurfacesTheSameRefusal() = runTest {
        val ro = graph.createAsset.run("RO unit", "Water", templateKey = "ro_water")
        val prefilter = graph.definitions.forAsset(ro.id).single { it.key == "tds_prefilter" }
        val vm = model(ro.id, prefilter.id)
        backgroundScope.launch { vm.refusal.collect() }
        vm.state.first { it.loaded }

        vm.delete()

        val refusal = vm.refusal.first { it != null }!!
        assertEquals(0, refusal.measurements)
        assertEquals(1, refusal.derivedBy.size)
        assertEquals(1, refusal.profiles.size)
        assertEquals(4, graph.definitions.forAsset(ro.id).size)
    }

    @Test fun archiveFromTheEditorRetiresTheReadingWithoutTouchingItsHistory() = runTest {
        val ro = graph.createAsset.run("RO unit", "Water", templateKey = "ro_water")
        val output = graph.definitions.forAsset(ro.id).single { it.key == "tds_output" }
        graph.logEvent.run(tdsTest(ro.id, mapOf(output.id to "16")))
        val vm = model(ro.id, output.id)
        vm.state.first { it.loaded }

        vm.archive(archived = true)
        assertTrue(vm.state.first { it.archived }.archived)
        assertEquals(1, graph.events.countMeasurementsFor(output.id))

        vm.archive(archived = false)
        assertFalse(vm.state.first { !it.archived }.archived)
        assertNull(graph.definitions.get(output.id)!!.archivedAt)
    }
}
