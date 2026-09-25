package com.loosecannon.servicetag.ui.journal

import com.loosecannon.servicetag.core.journal.RangeState
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.core.usecase.FieldProblem
import com.loosecannon.servicetag.testing.FakeGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
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
import java.time.Instant
import java.time.ZoneId

/**
 * The entry route and the event detail against a Room-backed [FakeGraph], in the shape
 * `AssetViewModelsTest` set: the main dispatcher is a test one for the length of each test, and
 * every assertion waits for a state rather than reading `value` straight after a write.
 *
 * A successful save is asserted on the stored row, not on `saved` read straight after `saving`
 * goes false — the flag is lowered before the emission, so the two are not the same instant. The
 * emission itself is awaited on a deferred that was already collecting before the save started.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventEntryViewModelTest {

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

    /** One hot tub with its five definitions and the Water test profile, as the seed makes them. */
    private class Spa(
        val asset: Asset,
        val definitions: Map<String, MeasurementDefinition>,
        val profile: EventProfile,
    ) {
        val id: AssetId get() = asset.id
        fun def(key: String) = definitions.getValue(key)
    }

    private suspend fun spa(): Spa {
        val asset = graph.createAsset.run("Spa", "Water", templateKey = "hot_tub")
        return Spa(
            asset = asset,
            definitions = graph.definitions.forAsset(asset.id).associateBy(MeasurementDefinition::key),
            profile = graph.profiles.forAsset(asset.id).first { it.name == "Water test" },
        )
    }

    private fun entryModel(assetId: AssetId, profileId: ProfileId?, eventId: EventId?) =
        EventEntryViewModel(
            graph.assets, graph.definitions, graph.profiles, graph.events,
            graph.logEvent, graph.updateEvent, graph.clock,
            assetId, profileId, eventId, offers = graph.eventOffers,
        )

    private fun detailModel(id: EventId) =
        EventDetailViewModel(graph.events, graph.definitions, graph.assets, graph.deleteEvent, id)

    private fun today(): String =
        Instant.ofEpochMilli(graph.now).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    @Test fun newEntryLoadsProfileRowsInOrderWithDefaults() = runTest {
        graph.now = 1_789_000_000_000L
        val spa = spa()

        val vm = entryModel(spa.id, spa.profile.id, null)
        val state = vm.state.first { it.loaded }

        assertEquals("Spa", state.assetName)
        assertEquals("Water test", state.profileName)
        assertEquals("Water test", state.title)
        assertFalse(state.editing)
        assertEquals(
            listOf("pH", "Free chlorine", "Alkalinity", "Calcium hardness", "Water temperature"),
            state.fields.map { it.definition.label },
        )
        assertEquals(listOf(true, true, false, false, false), state.fields.map { it.required })
        assertTrue(state.fields.all { it.text.isEmpty() && it.problem == null })
        // The profile's four chemicals are offered, not added: a suggestion is a tap away, not used.
        assertEquals(4, state.suggestions.size)
        assertTrue(state.consumables.isEmpty())
        assertEquals(today(), state.occurredOn)
    }

    @Test fun saveWithMissingRequiredMarksRowAndDoesNotEmit() = runTest {
        val spa = spa()
        val vm = entryModel(spa.id, spa.profile.id, null)
        vm.state.first { it.loaded }

        val saved = mutableListOf<EventId>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { vm.saved.collect { saved += it } }

        vm.save()
        val refused = vm.state.first { !it.saving && it.firstProblem != null }

        assertEquals("pH is required", refused.firstProblem)
        assertTrue(refused.fields.first().problem is FieldProblem.Required)
        assertTrue(graph.events.forAsset(spa.id).isEmpty())
        assertTrue(saved.isEmpty())
    }

    @Test fun liveStateClassifiesWhileTyping() = runTest {
        val spa = spa()
        val vm = entryModel(spa.id, spa.profile.id, null)
        vm.state.first { it.loaded }

        // 7.9 is past the top of pH's 7.2-7.8, and the badge says so before anything is saved.
        vm.onValue(spa.def("ph").id, "7.9")
        assertEquals(RangeState.HIGH, vm.state.value.fields.first().liveState)

        vm.onValue(spa.def("ph").id, "abc")
        assertNull(vm.state.value.fields.first().liveState)
    }

    @Test fun saveLogsEventAndEmits() = runTest {
        val spa = spa()
        val vm = entryModel(spa.id, spa.profile.id, null)
        val loaded = vm.state.first { it.loaded }

        // Undispatched: the collector must be registered before the save runs, because a shared
        // flow with no subscriber and no replay drops what it is given.
        val saved = CompletableDeferred<EventId>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { saved.complete(vm.saved.first()) }

        vm.onValue(spa.def("ph").id, "7.4")
        vm.onValue(spa.def("free_chlorine").id, "2.0")
        vm.addSuggested(loaded.suggestions.first())
        vm.onConsumable(0, quantity = "1")

        vm.save()
        vm.state.first { !it.saving }

        val stored = graph.events.forAsset(spa.id).single()
        assertEquals("Water test", stored.title)
        assertEquals(2, stored.measurements.size)
        // The unit is a snapshot of the definition's at entry (§4): pH has none, chlorine has ppm.
        assertEquals(listOf("", "ppm"), stored.measurements.map { it.unit })
        assertEquals(7.4, stored.measurements.first().valueNum!!, 1e-9)
        val used = stored.consumables.single()
        assertEquals("Chlorine", used.name)
        assertEquals(1.0, used.quantity, 1e-9)
        assertEquals("oz", used.unit)

        assertEquals(stored.id, saved.await())
    }

    @Test fun saveIsGuardedWhileSaving() = runTest {
        val spa = spa()
        val vm = entryModel(spa.id, spa.profile.id, null)
        vm.state.first { it.loaded }
        vm.onValue(spa.def("ph").id, "7.4")
        vm.onValue(spa.def("free_chlorine").id, "2.0")

        // Both taps land in the same frame: the in-flight guard drops the second.
        vm.save()
        vm.save()
        vm.state.first { !it.saving }

        assertEquals(1, graph.events.forAsset(spa.id).size)
    }

    /**
     * `save()` guards on `saving` alone would let a tap that lands between construction and the
     * `init` load's completion submit the form's still-default state over the event being edited.
     * A [StandardTestDispatcher] tied to this test's own scheduler, left un-advanced, catches the
     * VM mid-load: `init`'s coroutine is queued but has not run, so `state.loaded` is still false
     * when `save()` is called.
     */
    @Test fun saveBeforeLoadIsIgnored() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val spa = spa()
        val logged = graph.logEvent.run(
            EventCommand(
                assetId = spa.id,
                profileId = spa.profile.id,
                kind = EventKind.MEASUREMENT,
                title = "Water test",
                occurredOn = "2026-09-15",
                occurredTime = null,
                tzId = "UTC",
                notes = "",
                values = mapOf(spa.def("ph").id to "7.4", spa.def("free_chlorine").id to "2.0"),
                consumables = emptyList(),
            ),
        )

        val vm = entryModel(spa.id, null, logged.id)
        assertFalse(vm.state.value.loaded)

        val saved = mutableListOf<EventId>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { vm.saved.collect { saved += it } }

        vm.save()
        // The guard runs synchronously on the caller, before any coroutine is launched, so this
        // holds even before the dispatcher is advanced.
        assertFalse(vm.state.value.saving)

        // Let `init`'s load actually finish — a real Room query underlies it, so this waits on
        // the flow rather than fast-forwarding virtual time.
        vm.state.first { it.loaded }

        assertFalse(vm.state.value.saving)
        assertTrue(saved.isEmpty())
        assertEquals(logged, graph.events.forAsset(spa.id).single())
    }

    @Test fun editModePrefillsAndPreservesIds() = runTest {
        val spa = spa()
        val ph = spa.def("ph")
        val logged = graph.logEvent.run(
            EventCommand(
                assetId = spa.id,
                profileId = spa.profile.id,
                kind = EventKind.MEASUREMENT,
                title = "Water test",
                occurredOn = "2026-09-15",
                occurredTime = "14:42",
                tzId = "UTC",
                notes = "cloudy",
                values = mapOf(ph.id to "7.8", spa.def("free_chlorine").id to "2.0"),
                consumables = emptyList(),
            ),
        )

        val vm = entryModel(spa.id, null, logged.id)
        val state = vm.state.first { it.loaded }

        assertTrue(state.editing)
        assertEquals("Water test", state.profileName)
        assertEquals("2026-09-15", state.occurredOn)
        assertEquals("14:42", state.occurredTime)
        assertEquals("cloudy", state.notes)
        assertEquals("7.8", state.fields.first { it.definition.key == "ph" }.text)
        assertEquals("2.0", state.fields.first { it.definition.key == "free_chlorine" }.text)

        vm.onValue(ph.id, "7.5")
        vm.save()
        vm.state.first { !it.saving }

        val reloaded = graph.events.forAsset(spa.id).single()
        assertEquals(logged.id, reloaded.id)
        assertEquals(logged.createdAt, reloaded.createdAt)
        val before = logged.measurements.first { it.definitionId == ph.id }
        val after = reloaded.measurements.first { it.definitionId == ph.id }
        // An edit that keeps a field keeps that field's id, so history is edited, not replaced.
        assertEquals(before.id, after.id)
        assertEquals(7.5, after.valueNum!!, 1e-9)
    }

    @Test fun editKeepsMeasurementsNotInTheProfile() = runTest {
        val spa = spa()
        val alkalinity = spa.def("alkalinity")
        // Treatment's fields are pH and free chlorine only, so the alkalinity reading is an extra
        // the profile does not name — the shape an import, or a profile edited since, leaves behind.
        val treatment = graph.profiles.forAsset(spa.id).first { it.name == "Treatment" }
        val logged = graph.logEvent.run(
            EventCommand(
                assetId = spa.id,
                profileId = treatment.id,
                kind = EventKind.TREATMENT,
                title = "Treatment",
                occurredOn = "2026-09-15",
                occurredTime = null,
                tzId = "UTC",
                notes = "",
                values = mapOf(spa.def("ph").id to "7.4", alkalinity.id to "110"),
                consumables = emptyList(),
            ),
        )

        val vm = entryModel(spa.id, null, logged.id)
        val state = vm.state.first { it.loaded }

        // The carried row is shown, after the profile's own, so the value is not invisible.
        assertEquals(listOf("ph", "free_chlorine", "alkalinity"), state.fields.map { it.definition.key })
        assertEquals("110", state.fields.last().text)
        assertFalse(state.fields.last().required)

        vm.save()
        vm.state.first { !it.saving }

        val reloaded = graph.events.forAsset(spa.id).single()
        val before = logged.measurements.first { it.definitionId == alkalinity.id }
        val after = reloaded.measurements.first { it.definitionId == alkalinity.id }
        assertEquals(before.id, after.id)
        assertEquals(110.0, after.valueNum!!, 1e-9)
    }

    /**
     * The live derived row of spec §5: the form recomputes it from the values typed so far, on this
     * event's numbers only, and says nothing at all the moment one of its sources is missing.
     */
    @Test fun derivedRowUpdatesLiveFromTypedValues() = runTest {
        val ro = graph.createAsset.run("RO unit", "Water", templateKey = "ro_water")
        val defs = graph.definitions.forAsset(ro.id).associateBy(MeasurementDefinition::key)
        val profile = graph.profiles.forAsset(ro.id).first { it.name == "TDS test" }

        val vm = entryModel(ro.id, profile.id, null)
        val loaded = vm.state.first { it.loaded }

        // The derived definition is never an input: the three TDS readings are the only rows.
        assertEquals(
            listOf("tds_prefilter", "tds_post_membrane", "tds_output"),
            loaded.fields.map { it.definition.key },
        )
        val waiting = loaded.derivedRows.single()
        assertEquals("Rejection", waiting.definition.label)
        assertNull(waiting.derivedValue)
        assertNull(formatValue(waiting))

        vm.onValue(defs.getValue("tds_prefilter").id, "310")
        vm.onValue(defs.getValue("tds_post_membrane").id, "18")

        val computed = vm.state.value.derivedRows.single()
        assertEquals(94.19, computed.derivedValue!!, 0.01)
        assertEquals("94.2", formatValue(computed))
        // Nothing is stored for it: a derived reading is computed on every read (§5).
        assertNull(computed.measurement)

        // Clear one source and the row goes back to having nothing to say — the screen's em dash.
        vm.onValue(defs.getValue("tds_post_membrane").id, "")
        assertNull(vm.state.value.derivedRows.single().derivedValue)
        assertNull(formatValue(vm.state.value.derivedRows.single()))
    }

    /**
     * The 2A carry rule under archiving (spec §9): an archived definition never produces an input
     * row, except on an edit of an event that already measured it — dropping that row would delete
     * a reading the user never saw.
     */
    @Test fun archivedDefinitionGetsNoRowUnlessCarried() = runTest {
        val spa = spa()
        val alkalinity = spa.def("alkalinity")
        val logged = graph.logEvent.run(
            EventCommand(
                assetId = spa.id,
                profileId = spa.profile.id,
                kind = EventKind.MEASUREMENT,
                title = "Water test",
                occurredOn = "2026-09-15",
                occurredTime = null,
                tzId = "UTC",
                notes = "",
                values = mapOf(
                    spa.def("ph").id to "7.4",
                    spa.def("free_chlorine").id to "2.0",
                    alkalinity.id to "110",
                ),
                consumables = emptyList(),
            ),
        )
        graph.definitions.upsert(alkalinity.copy(archivedAt = 9_000L))

        val fresh = entryModel(spa.id, spa.profile.id, null)
        assertEquals(
            listOf("ph", "free_chlorine", "calcium_hardness", "water_temp"),
            fresh.state.first { it.loaded }.fields.map { it.definition.key },
        )

        val edit = entryModel(spa.id, null, logged.id)
        val editing = edit.state.first { it.loaded }
        assertEquals("110", editing.fields.first { it.definition.key == "alkalinity" }.text)

        edit.save()
        edit.state.first { !it.saving }
        // The carried reading survives the edit with its own id, archived definition and all.
        val reloaded = graph.events.forAsset(spa.id).single()
        val before = logged.measurements.first { it.definitionId == alkalinity.id }
        assertEquals(before.id, reloaded.measurements.first { it.definitionId == alkalinity.id }.id)
    }

    /**
     * Archiving a *required* reading used to make its quick action unsaveable: the row was off the
     * form but the domain still demanded it, so Save did nothing and said nothing. The remaining
     * fields are enough now, and the entry is stored.
     */
    @Test fun archivedRequiredFieldDoesNotBlockSave() = runTest {
        val asset = graph.createAsset.run("RO unit", "Water", templateKey = "ro_water")
        val defs = graph.definitions.forAsset(asset.id).associateBy(MeasurementDefinition::key)
        val tdsTest = graph.profiles.forAsset(asset.id).first { it.name == "TDS test" }
        graph.definitions.upsert(defs.getValue("tds_prefilter").copy(archivedAt = 9_000L))

        val vm = entryModel(asset.id, tdsTest.id, null)
        val loaded = vm.state.first { it.loaded }
        assertEquals(
            listOf("tds_post_membrane", "tds_output"),
            loaded.fields.map { it.definition.key },
        )

        vm.onValue(defs.getValue("tds_post_membrane").id, "12")
        vm.onValue(defs.getValue("tds_output").id, "8")
        vm.save()
        val after = vm.state.first { !it.saving }

        assertNull(after.firstProblem)
        val stored = graph.events.forAsset(asset.id).single()
        assertEquals(2, stored.measurements.size)
    }

    @Test fun deleteRemovesAndEmits() = runTest {
        val spa = spa()
        val logged = graph.logEvent.run(
            EventCommand(
                assetId = spa.id,
                profileId = spa.profile.id,
                kind = EventKind.MEASUREMENT,
                title = "Water test",
                occurredOn = "2026-09-15",
                occurredTime = null,
                tzId = "UTC",
                notes = "",
                values = mapOf(spa.def("ph").id to "7.4", spa.def("free_chlorine").id to "2.0"),
                consumables = emptyList(),
            ),
        )

        val vm = detailModel(logged.id)
        backgroundScope.launch { vm.state.collect() }
        val state = vm.state.first { it != null }!!

        assertEquals("Water test", state.event.title)
        assertEquals("Spa", state.assetName)
        assertEquals(5, state.definitions.size)

        val deleted = CompletableDeferred<Unit>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { deleted.complete(vm.deleted.first()) }
        vm.delete()
        deleted.await()

        assertTrue(graph.events.all().isEmpty())
    }

    @Test fun missingEventFlagsMissing() = runTest {
        val vm = detailModel(EventId("nope"))
        backgroundScope.launch { vm.missing.collect() }

        assertTrue(vm.missing.first { it })
        assertNull(vm.state.value)
    }
}
