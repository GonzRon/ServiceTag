package com.loosecannon.servicetag.ui.journal

import com.loosecannon.servicetag.core.journal.RangeState
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.core.usecase.ConsumableInput
import com.loosecannon.servicetag.core.usecase.DefinitionCommand
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.core.usecase.FieldProblem
import com.loosecannon.servicetag.core.usecase.GroupCommand
import com.loosecannon.servicetag.core.usecase.ProfileCommand
import com.loosecannon.servicetag.core.usecase.ProfileConsumableInput
import com.loosecannon.servicetag.core.usecase.ProfileFieldInput
import com.loosecannon.servicetag.core.usecase.SupplyItemCommand
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.l10n.AppText
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.testing.EnglishResources
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.ResourcePack
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.ui.condition.DATE_NOT_LATER_THAN_TODAY
import com.loosecannon.servicetag.ui.condition.EntryOffers
import com.loosecannon.servicetag.ui.condition.EventOffer
import com.loosecannon.servicetag.ui.condition.ImpairmentOfferPrompt
import com.loosecannon.servicetag.ui.condition.OperationalOfferPrompt
import com.loosecannon.servicetag.ui.condition.PendingCondition
import com.loosecannon.servicetag.ui.condition.savingAlsoRecordsLine
import com.loosecannon.servicetag.ui.maintenance.ReminderReconcile
import com.loosecannon.servicetag.ui.transfer.`import`.TransferImportStrings
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    private fun entryModel(
        assetId: AssetId,
        profileId: ProfileId?,
        eventId: EventId?,
        offers: EntryOffers = graph.eventOffers,
        kind: EventKind? = null,
    ) = EventEntryViewModel(
        graph.assets, graph.definitions, graph.profiles, graph.events,
        graph.logEvent, graph.updateEvent, graph.clock,
        assetId, profileId, eventId, presetKind = kind, offers = offers,
        supplyItems = graph.supplyItems,
    )

    /** #82: counts what reaches the graph's one [EntryOffers], delegating everything to it. */
    private class RecordingOffers(private val real: EntryOffers) : EntryOffers by real {
        var asked = 0
        var accepts = 0

        override suspend fun offersAfter(event: AssetEvent): List<EventOffer> {
            asked += 1
            return real.offersAfter(event)
        }

        override suspend fun accept(offer: EventOffer) {
            accepts += 1
            real.accept(offer)
        }
    }

    /** An asset in service with no condition recorded: a new Incident on it asks Workflow B's question. */
    private suspend fun pump(): AssetId {
        graph.assets.upsert(assetRow("pump", name = "Pump"))
        return AssetId("pump")
    }

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

    /**
     * #102 (PR #106 review): a German owner types decimals with a comma. "7,4" is pH 7.4 — the badge reads it while
     * it is typed — and "0,5" half an ounce; what is stored is the number. Editing the entry draws them back the same
     * way ("7,4", "0,5"), so a save that changes nothing re-reads the same numbers.
     */
    @Test fun aCommaDecimalLanguageTypesReadingsAndQuantitiesWithAComma() = runTest {
        val spa = spa()
        AppText.install(ResourcePack.pack("de"))
        try {
            val vm = entryModel(spa.id, spa.profile.id, null)
            val loaded = vm.state.first { it.loaded }

            vm.onValue(spa.def("ph").id, "7,9")
            assertEquals(RangeState.HIGH, vm.state.value.fields.first().liveState)
            vm.onValue(spa.def("ph").id, "7,4")
            vm.onValue(spa.def("free_chlorine").id, "2,5")
            vm.addSuggested(loaded.suggestions.first())
            vm.onConsumable(0, quantity = "0,5")
            vm.save()
            vm.state.first { !it.saving }

            val stored = graph.events.forAsset(spa.id).single()
            assertEquals(7.4, stored.measurements.single { it.definitionId == spa.def("ph").id }.valueNum!!, 0.0)
            assertEquals(2.5, stored.measurements.single { it.definitionId == spa.def("free_chlorine").id }.valueNum!!, 0.0)
            assertEquals(0.5, stored.consumables.single().quantity, 0.0)

            val editing = entryModel(spa.id, null, stored.id).state.first { it.loaded }
            assertEquals("7,4", editing.fields.first { it.definition.key == "ph" }.text)
            assertEquals("2,5", editing.fields.first { it.definition.key == "free_chlorine" }.text)
            assertEquals("0,5", editing.consumables.single().quantity)
        } finally {
            AppText.install(EnglishResources())
        }
    }

    /**
     * German reads "45.000" as forty-five thousand, which no reading here can be: it is refused against its field,
     * never stored as 45.
     */
    @Test fun aCommaDecimalLanguageRefusesAThousandsShapedReading() = runTest {
        val spa = spa()
        AppText.install(ResourcePack.pack("de"))
        try {
            val vm = entryModel(spa.id, spa.profile.id, null)
            vm.state.first { it.loaded }

            vm.onValue(spa.def("ph").id, "7,4")
            vm.onValue(spa.def("free_chlorine").id, "45.000")
            assertNull(vm.state.value.fields.first { it.definition.key == "free_chlorine" }.liveState)
            vm.save()
            val refused = vm.state.first { !it.saving }

            assertEquals(localized(R.string.journal_field_not_a_number, "Free chlorine"), refused.firstProblem)
            assertEquals(0, graph.events.forAsset(spa.id).size)
        } finally {
            AppText.install(EnglishResources())
        }
    }

    /** English never read a comma as a decimal point: "7,4" is still not a number there, and nothing is written. */
    @Test fun englishStillRefusesACommaDecimal() = runTest {
        val spa = spa()
        val vm = entryModel(spa.id, spa.profile.id, null)
        vm.state.first { it.loaded }

        vm.onValue(spa.def("ph").id, "7,4")
        assertNull(vm.state.value.fields.first().liveState)
        vm.onValue(spa.def("free_chlorine").id, "2.0")
        vm.save()
        val refused = vm.state.first { !it.saving }

        assertEquals("pH is not a number", refused.firstProblem)
        assertEquals(0, graph.events.forAsset(spa.id).size)
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

    // ------------------------------------------------------------------ #82: Workflow B on the entry (C8)

    /** Row 15: an edit never asks — it logged nothing new, and S53 says "You logged". */
    @Test fun anEditNeverAsks() = runTest {
        val pump = pump()
        val logged = graph.logEvent.run(
            EventCommand(
                assetId = pump, profileId = null, kind = EventKind.INCIDENT, title = "Seized",
                occurredOn = today(), occurredTime = null, tzId = "UTC", notes = "",
                values = emptyMap(), consumables = emptyList(),
            ),
        )
        val offers = RecordingOffers(graph.eventOffers)
        val vm = entryModel(pump, null, logged.id, offers)
        vm.state.first { it.loaded }
        val saved = CompletableDeferred<EventId>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { saved.complete(vm.saved.first()) }

        vm.onNotes("Bearing gone")
        vm.save()

        val settled = vm.state.first { !it.saving || it.offer != null }
        assertNull("the edit never asks", settled.offer)
        assertEquals(logged.id, saved.await())
        assertEquals("the offers are never read for an edit", 0, offers.asked)
        assertEquals(0, graph.conditions.all().size)
        assertTrue(
            "the same Incident, new, would have asked",
            graph.eventOffers.offersAfter(graph.events.get(logged.id)!!).single() is ImpairmentOfferPrompt,
        )
    }

    /**
     * Row 16: a second tap on an answer is ignored at the view model — one accept reaches the offers
     * and the screen leaves once. Rows cannot show it: the accept re-reads and would write nothing.
     */
    @Test fun aSecondTapOnAnAnswerIsIgnored() = runTest {
        val pump = pump()
        val offers = RecordingOffers(graph.eventOffers)
        val vm = entryModel(pump, null, null, offers, kind = EventKind.INCIDENT)
        vm.state.first { it.loaded }
        val saved = mutableListOf<EventId>()
        val left = CompletableDeferred<EventId>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.saved.collect {
                saved += it
                left.complete(it)
            }
        }

        vm.save()
        advanceUntilIdle()
        assertTrue("Workflow B asks", vm.state.value.offer is ImpairmentOfferPrompt)
        vm.acceptImpairment(OperationalCondition.DOWN)
        vm.acceptImpairment(OperationalCondition.DEGRADED)
        left.await()
        vm.state.first { it.offer == null && !it.saving }
        advanceUntilIdle()

        assertEquals("one accept reaches the offers", 1, offers.accepts)
        assertEquals(1, saved.size)
        assertEquals(OperationalCondition.DOWN, graph.conditions.forAsset(pump).single().condition)
    }

    // ------------------------------------------------------------------ #82: the pending entry (C7, R82-3)

    /** The combined flow's Incident entry, opened with Change condition's held [held]. */
    private fun pendingModel(assetId: AssetId, held: PendingCondition, offers: EntryOffers = graph.eventOffers) =
        EventEntryViewModel(
            graph.assets, graph.definitions, graph.profiles, graph.events,
            graph.logEvent, graph.updateEvent, graph.clock,
            assetId, null, null, presetKind = EventKind.INCIDENT, offers = offers,
            pending = held, recordWithIncident = graph.recordConditionWithIncident,
        )

    /** Today is 2026-02-10 for the store and the entry alike; the held DOWN was on the 9th. */
    private fun heldDown(reason: String = "Will not start\nStarter clicks") =
        PendingCondition("c-held", OperationalCondition.DOWN, "2026-02-09", reason)

    private fun onTheTenth() {
        graph.today = java.time.LocalDate.parse("2026-02-10")
        graph.now = dayMillis("2026-02-10") + 12 * 60 * 60 * 1000L
    }

    /**
     * Row 14 (R82-3): the entry opens as an INCIDENT with no profile — the title the reason's first
     * non-blank line (or the preset "Incident"), the notes its remaining lines, the held day and no
     * time, all editable — and the data P82-5 is drawn from.
     */
    @Test fun aPendingEntryOpensAsAPrefilledIncident() = runTest {
        onTheTenth()
        val pump = pump()

        val state = pendingModel(pump, heldDown("  \n Will not start \nStarter clicks\n\n  Smells of smoke \n"))
            .state.first { it.loaded }
        assertEquals("Will not start", state.title)
        assertEquals("Starter clicks\n\n  Smells of smoke", state.notes)
        assertEquals("2026-02-09", state.occurredOn)
        assertNull("no time", state.occurredTime)
        assertEquals("no profile", "", state.profileName)
        assertEquals(OperationalCondition.DOWN, state.alsoRecords)
        assertEquals("Saving also records Pump as DOWN.", savingAlsoRecordsLine(state.assetName, state.alsoRecords!!))

        val blank = pendingModel(pump, heldDown("  \n ")).state.first { it.loaded }
        assertEquals("Incident", blank.title)
        assertEquals("", blank.notes)
        val oneLine = pendingModel(pump, heldDown("Belt snapped")).state.first { it.loaded }
        assertEquals("Belt snapped", oneLine.title)
        assertEquals("", oneLine.notes)

        // A plain Incident entry carries no condition line.
        assertNull(entryModel(pump, null, null, kind = EventKind.INCIDENT).state.first { it.loaded }.alsoRecords)
    }

    /**
     * Row 14 (AC 3): Save writes the Incident and the held row, linked, once — through the combined
     * write, never LogEvent — asks nothing, and the screen leaves once.
     */
    @Test fun savingItWritesOnceLinkedAndAsksNothing() = runTest {
        onTheTenth()
        val pump = pump()
        val held = heldDown()
        val offers = RecordingOffers(graph.eventOffers)
        val vm = pendingModel(pump, held, offers)
        vm.state.first { it.loaded }
        val saved = mutableListOf<EventId>()
        val left = CompletableDeferred<EventId>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.saved.collect {
                saved += it
                left.complete(it)
            }
        }

        vm.save()
        val leftFor = left.await()
        advanceUntilIdle()

        val event = graph.events.forAsset(pump).single()
        assertEquals(event.id, leftFor)
        assertEquals(EventKind.INCIDENT, event.kind)
        assertEquals("Will not start", event.title)
        assertEquals("Starter clicks", event.notes)
        assertEquals("2026-02-09", event.occurredOn)
        assertNull(event.occurredTime)
        assertNull(event.profileId)
        val row = graph.conditions.forAsset(pump).single()
        assertEquals(held.id, row.id)
        assertEquals(event.id, row.eventId)
        assertEquals(OperationalCondition.DOWN, row.condition)
        assertEquals("2026-02-09", row.occurredOn)
        assertNull(row.occurredTime)
        assertEquals(held.reason, row.reason)
        assertNull(vm.state.value.offer)
        assertEquals("the offers are never read in the combined flow", 0, offers.asked)
        assertEquals(1, saved.size)
    }

    /** Row 14: leaving the entry — back, the close icon — writes nothing. */
    @Test fun leavingItWritesNothing() = runTest {
        onTheTenth()
        val pump = pump()
        val vm = pendingModel(pump, heldDown())
        vm.state.first { it.loaded }

        vm.onTitle("Starter motor")
        advanceUntilIdle()

        assertEquals(0, graph.events.forAsset(pump).size)
        assertEquals(0, graph.conditions.forAsset(pump).size)
    }

    /** Row 14 (R82-3): an Incident dated after today refuses the whole Save with S25 and writes nothing. */
    @Test fun aLaterIncidentDateSaysS25AndWritesNothing() = runTest {
        onTheTenth()
        val pump = pump()
        val vm = pendingModel(pump, heldDown())
        vm.state.first { it.loaded }
        val saved = mutableListOf<EventId>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { vm.saved.collect { saved += it } }

        vm.onDate("2026-02-11")
        vm.save()
        val refused = vm.state.first { !it.saving }

        assertEquals(DATE_NOT_LATER_THAN_TODAY, refused.firstProblem)
        assertEquals(0, graph.events.forAsset(pump).size)
        assertEquals(0, graph.conditions.forAsset(pump).size)
        assertTrue(saved.isEmpty())
    }

    /** Row 14 (C7): a field row's problem is named before S25 — the form's own first line, as today. */
    @Test fun fieldRowsWinOverS25() = runTest {
        onTheTenth()
        val spa = spa()
        val vm = pendingModel(spa.id, heldDown())
        vm.state.first { it.loaded }

        vm.onValue(spa.def("ph").id, "abc")
        vm.onDate("2026-02-11")
        vm.save()
        val refused = vm.state.first { !it.saving }

        assertEquals("pH is not a number", refused.firstProblem)
        assertTrue(refused.fields.single { it.definition.id == spa.def("ph").id }.problem is FieldProblem.NotANumber)
        assertEquals(0, graph.events.forAsset(spa.id).size)
        assertEquals(0, graph.conditions.forAsset(spa.id).size)
    }

    /** C7: a refusal no row can explain — the asset gone — is the shipped "Could not save this entry." */
    @Test fun aRefusalNoRowExplainsSaysCouldNotSave() = runTest {
        onTheTenth()
        val vm = pendingModel(AssetId("gone"), heldDown())
        vm.state.first { it.loaded }

        vm.save()
        val refused = vm.state.first { !it.saving }

        assertEquals("Could not save this entry.", refused.firstProblem)
        assertEquals(0, graph.conditions.all().size)
    }

    /**
     * Row 14 (K5): a second Save on the same held id — a fresh view model on the same route, as after
     * a lost pop — writes nothing more and still leaves.
     */
    @Test fun aSecondSaveAfterTheCommitWritesNothing() = runTest {
        onTheTenth()
        val pump = pump()
        val held = heldDown()
        suspend fun saveOnce(): EventId {
            val vm = pendingModel(pump, held)
            vm.state.first { it.loaded }
            val left = CompletableDeferred<EventId>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { left.complete(vm.saved.first()) }
            vm.save()
            return left.await()
        }

        val first = saveOnce()
        val second = saveOnce()
        advanceUntilIdle()

        assertEquals(first, second)
        assertEquals(1, graph.events.forAsset(pump).size)
        assertEquals(listOf(held.id), graph.conditions.forAsset(pump).map { it.id })
    }

    // --- #15 (C19, C20): a material row keeps its SupplyItem link, invisibly ---------------------

    private suspend fun supplyItem(name: String): SupplyId =
        graph.saveSupplyItem.run(null, SupplyItemCommand(name, "", "", "", "", "", "", emptyList())).item.id

    /** One REPLACEMENT quick action on the spa whose one line names [link] (null: unlinked). */
    private suspend fun filterSwap(spa: Spa, link: SupplyId?): EventProfile = graph.saveProfile.run(
        null,
        ProfileCommand(
            spa.id, "Filter swap", EventKind.REPLACEMENT, "", emptyList(),
            listOf(ProfileConsumableInput(null, "Filter cartridge", 1.0, "ea", link)),
        ),
    )

    /** Row 43: the quick action's chip brings its link into the row, and the save stores it with the line. */
    @Test fun aProfileChipCarriesItsLink() = runTest {
        val spa = spa()
        val cartridge = supplyItem("Example Filter Cartridge")
        val swap = filterSwap(spa, cartridge)
        val vm = entryModel(spa.id, swap.id, null)
        val loaded = vm.state.first { it.loaded }

        vm.addSuggested(loaded.suggestions.single())
        assertEquals(listOf(cartridge), vm.state.value.consumables.map { it.supplyId })
        vm.save()
        vm.state.first { !it.saving }

        val used = graph.events.forAsset(spa.id).single().consumables.single()
        assertEquals("Filter cartridge", used.name)
        assertEquals(cartridge, used.supplyId)
    }

    /**
     * Row 43: an edited event loads each row's link and saves it back with that row, even when a row above it
     * is removed and the linked row moves up (the link travels with its row, C19).
     */
    @Test fun anEditedEventKeepsItsLinksOnSave() = runTest {
        val spa = spa()
        val cartridge = supplyItem("Example Filter Cartridge")
        val logged = graph.logEvent.run(
            EventCommand(
                assetId = spa.id,
                profileId = null,
                kind = EventKind.REPLACEMENT,
                title = "Filter swap",
                occurredOn = "2026-09-15",
                occurredTime = null,
                tzId = "UTC",
                notes = "",
                values = emptyMap(),
                consumables = listOf(
                    ConsumableInput("Sanitiser", "50", "ml", supplyId = null),
                    ConsumableInput("Filter cartridge", "1", "ea", cartridge),
                ),
            ),
        )

        val vm = entryModel(spa.id, null, logged.id)
        val state = vm.state.first { it.loaded }
        assertEquals(listOf(null, cartridge), state.consumables.map { it.supplyId })

        vm.removeConsumable(0)
        vm.save()
        vm.state.first { !it.saving }

        val after = graph.events.get(logged.id)!!.consumables.single()
        assertEquals("Filter cartridge", after.name)
        assertEquals(cartridge, after.supplyId)
    }

    /** Row 43: typing over a linked row's name, quantity and unit leaves its link alone (C20: the words are the person's). */
    @Test fun editingTheNameKeepsTheLink() = runTest {
        val spa = spa()
        val cartridge = supplyItem("Example Filter Cartridge")
        val swap = filterSwap(spa, cartridge)
        val vm = entryModel(spa.id, swap.id, null)
        val loaded = vm.state.first { it.loaded }

        vm.addSuggested(loaded.suggestions.single())
        vm.onConsumable(0, name = "Filter cartridge (pleated)", quantity = "2", unit = "pcs")
        assertEquals(cartridge, vm.state.value.consumables.single().supplyId)
        vm.save()
        vm.state.first { !it.saving }

        val used = graph.events.forAsset(spa.id).single().consumables.single()
        assertEquals("Filter cartridge (pleated)", used.name)
        assertEquals("pcs", used.unit)
        assertEquals(cartridge, used.supplyId)
    }

    /**
     * Row 40 (C20, C-2): a chip whose SupplyItem is gone — only a race, since nothing deletes one (R15-5) — is
     * refused by `LogEvent`; the event form keeps its shipped sentence, marks no row, and writes nothing.
     */
    @Test fun anUnknownSupplyItemDrawsCannotSaveAndMarksNoRow() = runTest {
        val spa = spa()
        val swap = filterSwap(spa, link = null)
        // Written straight to the store: the column has no foreign key (R79-4), so a link can outlive its row.
        graph.profiles.upsert(swap.copy(consumables = swap.consumables.map { it.copy(supplyId = SupplyId("s-gone")) }))
        val vm = entryModel(spa.id, swap.id, null)
        val loaded = vm.state.first { it.loaded }

        vm.addSuggested(loaded.suggestions.single())
        vm.onConsumable(0, quantity = "1")
        vm.save()
        val refused = vm.state.first { !it.saving }

        assertEquals(CANNOT_SAVE, refused.firstProblem)
        assertEquals(listOf(false), refused.consumables.map { it.problem })
        assertEquals(emptyList<AssetEvent>(), graph.events.forAsset(spa.id))
    }

    // --- #15 (C35, row 63): the event form shows a link and removes it; it never links -------------------

    /**
     * Row 63 (C35): the form can name a linked row's item (for "Linked to %s"), and "Remove link" clears that row's
     * link and nothing else — its words stay as typed, the other row keeps its link, and the save stores both lines
     * with their words.
     */
    @Test fun removeLinkClearsOnlyTheLink() = runTest {
        val spa = spa()
        val cartridge = supplyItem("Example Filter Cartridge")
        val swap = filterSwap(spa, cartridge)
        val vm = entryModel(spa.id, swap.id, null)
        val loaded = vm.state.first { it.loaded && it.supplies.isNotEmpty() }
        assertEquals("Example Filter Cartridge", loaded.supplies.getValue(cartridge).name)

        vm.addSuggested(loaded.suggestions.single())
        vm.addSuggested(loaded.suggestions.single())
        vm.onConsumable(0, quantity = "2")
        val before = vm.state.value.consumables

        vm.unlinkSupply(0)

        val after = vm.state.value.consumables
        assertEquals(before[0].copy(supplyId = null), after[0])
        assertEquals(before[1], after[1])

        vm.save()
        vm.state.first { !it.saving }

        val used = graph.events.forAsset(spa.id).single().consumables.sortedBy { it.sortOrder }
        assertEquals(listOf(null, cartridge), used.map { it.supplyId })
        assertEquals(listOf("Filter cartridge", "Filter cartridge"), used.map { it.name })
        assertEquals(listOf(2.0, 1.0), used.map { it.quantity })
        assertEquals(listOf("ea", "ea"), used.map { it.unit })
    }

    /**
     * B8b's ruling on the untouched-row filter (B4b review, NOTE 3): a linked row whose words were all cleared is not
     * an untouched row — it carries the chip's link — so it reaches validation and is named, rather than vanishing
     * on Save with its link while its "Linked to" line was showing. Once "Remove link" clears the link it is an
     * untouched row again, dropped as it always was.
     */
    @Test fun aLinkedRowWithItsWordsClearedIsNamedNotDropped() = runTest {
        val spa = spa()
        val cartridge = supplyItem("Example Filter Cartridge")
        val swap = filterSwap(spa, cartridge)
        val vm = entryModel(spa.id, swap.id, null)
        val loaded = vm.state.first { it.loaded }

        vm.addSuggested(loaded.suggestions.single())
        vm.onConsumable(0, name = "", quantity = "", unit = "")
        vm.save()
        val refused = vm.state.first { !it.saving }

        assertEquals("Check material 1", refused.firstProblem)
        assertEquals(listOf(true), refused.consumables.map { it.problem })
        assertEquals(listOf(cartridge), refused.consumables.map { it.supplyId })
        assertEquals(emptyList<AssetEvent>(), graph.events.forAsset(spa.id))

        vm.unlinkSupply(0)
        assertEquals("remove link clears the row's mark too", listOf(false), vm.state.value.consumables.map { it.problem })
        vm.save()
        vm.state.first { !it.saving }

        assertEquals(emptyList<Any>(), graph.events.forAsset(spa.id).single().consumables)
    }

    // --- #117 (V1–V13): a form opened for a FORM schedule completes it -----------------------------

    /** The sweeps the form ran, in the order they ran against the offers (D8, I9). */
    private val sweeps = mutableListOf<String>()
    private val countingReconcile = ReminderReconcile { sweeps += "reconcile" }
    private val reconciles: Int get() = sweeps.count { it == "reconcile" }

    /** Notes when the offers are asked, into the same list the reconcile writes to. */
    private class OrderedOffers(private val real: EntryOffers, private val order: MutableList<String>) : EntryOffers by real {
        override suspend fun offersAfter(event: AssetEvent): List<EventOffer> {
            order += "offers"
            return real.offersAfter(event)
        }
    }

    /** The entry route as the root builds it for a schedule's form: the completion collaborators always present. */
    private fun formModel(
        assetId: AssetId,
        profileId: ProfileId?,
        scheduleId: ScheduleId?,
        eventId: EventId? = null,
        offers: EntryOffers = graph.eventOffers,
        reconcile: ReminderReconcile = countingReconcile,
    ) = EventEntryViewModel(
        graph.assets, graph.definitions, graph.profiles, graph.events,
        graph.logEvent, graph.updateEvent, graph.clock,
        assetId, profileId, eventId, offers = offers,
        supplyItems = graph.supplyItems,
        scheduleId = scheduleId,
        completeSchedule = graph.completeSchedule,
        schedules = graph.schedules,
        reconcile = reconcile,
    )

    /**
     * A FORM schedule on [assetId], FIXED quarterly from 2025-08-01 and never done: due 2025-08-01, so on the
     * tenth of February 2026 it is two quarters overdue, and one completion on the tenth moves it to 2026-05-01.
     * With a [meter] its rule also counts that meter, every 25 from 100 (#119).
     */
    private suspend fun formSchedule(
        assetId: AssetId,
        profileId: ProfileId?,
        title: String = "Quarterly water check",
        id: String = "s-water",
        meter: DefinitionId? = null,
    ): MaintenanceSchedule {
        val schedule = scheduleOf(
            id, assetId = assetId.value, title = title, anchorOn = "2025-08-01", leadDays = 0,
            meterDefinitionId = meter?.value, meterInterval = meter?.let { 25.0 }, anchorMeter = meter?.let { 100.0 },
            completionMode = CompletionMode.FORM, createdOn = "2025-08-01",
        ).copy(profileId = profileId)
        graph.schedules.upsert(schedule)
        graph.recomputeSchedules.forSchedule(schedule.id)
        return schedule
    }

    /** The schedule's computed due as the engine derives it now. */
    private suspend fun dueOf(id: ScheduleId): String? =
        graph.recomputeSchedules.stateOf(graph.schedules.get(id)!!).computedDueOn

    /** A MAINTENANCE quick action on the spa: pH and alkalinity, chlorine suggested and a cartridge line naming [link]. */
    private suspend fun service(spa: Spa, phRequired: Boolean = true, link: SupplyId? = null): EventProfile =
        graph.saveProfile.run(
            null,
            ProfileCommand(
                spa.id, "Service", EventKind.MAINTENANCE, "Service",
                listOf(ProfileFieldInput(spa.def("ph").id, phRequired), ProfileFieldInput(spa.def("alkalinity").id, false)),
                listOf(
                    ProfileConsumableInput(null, "Chlorine", 1.0, "oz", supplyId = null),
                    ProfileConsumableInput(null, "Filter cartridge", 1.0, "ea", link),
                ),
            ),
        )

    private suspend fun EventEntryViewModel.typeWaterTest(spa: Spa) {
        state.first { it.loaded }
        onDate("2026-02-10")
        onValue(spa.def("ph").id, "7.4")
        onValue(spa.def("free_chlorine").id, "2.0")
    }

    /**
     * V1: the owner's report. A FORM schedule two quarters overdue, its form saved with the required readings, writes
     * **one** event linked to it — the occurrence the engine said was open, never owing details, a manual entry of the
     * profile's kind — leaves, and the due moves to the first series date after the tenth, not one quarter on.
     */
    @Test fun aScheduleOpenedFormCompletesTheOpenOccurrence() = runTest {
        onTheTenth()
        val spa = spa()
        val schedule = formSchedule(spa.id, spa.profile.id)
        val dueBefore = dueOf(schedule.id)
        assertEquals("2025-08-01", dueBefore)

        val vm = formModel(spa.id, spa.profile.id, schedule.id)
        val saved = CompletableDeferred<EventId>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { saved.complete(vm.saved.first()) }
        vm.typeWaterTest(spa)
        vm.onNotes("Example note")
        vm.onTime("09:30")
        vm.save()
        val left = saved.await()

        val stored = graph.events.all().single()
        assertEquals(left, stored.id)
        assertEquals(schedule.id, stored.scheduleId)
        assertEquals(dueBefore, stored.occurrenceOn)
        assertEquals("Example note", stored.notes)
        assertEquals("09:30", stored.occurredTime)
        assertFalse(stored.detailsPending)
        assertEquals(EventSource.MANUAL, stored.source)
        assertEquals(spa.profile.eventKind, stored.kind)
        assertEquals(spa.profile.id, stored.profileId)
        assertEquals("2026-02-10", stored.occurredOn)
        assertEquals("no replay: one next occurrence after the tenth", "2026-05-01", dueOf(schedule.id))
    }

    /**
     * V2: everything the form collected rides on the linked event — the readings, a suggested material and one linked
     * to a SupplyItem — as the plain save sends them: numbers read in the owner's language (German here), units, links.
     */
    @Test fun aScheduleOpenedFormCarriesItsReadingsAndMaterials() = runTest {
        onTheTenth()
        val spa = spa()
        val cartridge = supplyItem("Example Filter Cartridge")
        val profile = service(spa, link = cartridge)
        val schedule = formSchedule(spa.id, profile.id, title = "Quarterly service")
        AppText.install(ResourcePack.pack("de"))
        try {
            val vm = formModel(spa.id, profile.id, schedule.id)
            val loaded = vm.state.first { it.loaded }
            vm.onDate("2026-02-10")
            vm.onValue(spa.def("ph").id, "7,4")
            vm.onValue(spa.def("alkalinity").id, "90")
            loaded.suggestions.forEach(vm::addSuggested)
            vm.onConsumable(0, quantity = "0,5")
            vm.save()
            vm.state.first { !it.saving }
        } finally {
            AppText.install(EnglishResources())
        }

        val stored = graph.events.all().single()
        assertEquals(schedule.id, stored.scheduleId)
        assertEquals(7.4, stored.measurements.single { it.definitionId == spa.def("ph").id }.valueNum!!, 0.0)
        assertEquals(90.0, stored.measurements.single { it.definitionId == spa.def("alkalinity").id }.valueNum!!, 0.0)
        val used = stored.consumables.sortedBy { it.sortOrder }
        assertEquals(listOf("Chlorine", "Filter cartridge"), used.map { it.name })
        assertEquals(listOf(0.5, 1.0), used.map { it.quantity })
        assertEquals(listOf("oz", "ea"), used.map { it.unit })
        assertEquals(listOf(null, cartridge), used.map { it.supplyId })
    }

    /** V3: a required reading left empty is refused against its row; nothing is written and the due stays put. */
    @Test fun aScheduleOpenedFormIsHeldToItsRequiredReadings() = runTest {
        onTheTenth()
        val spa = spa()
        val schedule = formSchedule(spa.id, spa.profile.id)
        val vm = formModel(spa.id, spa.profile.id, schedule.id)
        vm.state.first { it.loaded }

        vm.save()
        val refused = vm.state.first { !it.saving }

        assertEquals("pH is required", refused.firstProblem)
        assertTrue(refused.fields.first().problem is FieldProblem.Required)
        assertEquals(emptyList<AssetEvent>(), graph.events.all())
        assertEquals("2025-08-01", dueOf(schedule.id))
        assertEquals(0, reconciles)
    }

    /** V4: an all-optional form saved with nothing typed completes the occurrence; the form collected it, so nothing is owed. */
    @Test fun anAllOptionalFormSavedEmptyCompletesWithNothingOwed() = runTest {
        onTheTenth()
        val spa = spa()
        val profile = service(spa, phRequired = false)
        val schedule = formSchedule(spa.id, profile.id, title = "Quarterly service")
        val vm = formModel(spa.id, profile.id, schedule.id)
        vm.state.first { it.loaded }
        vm.onDate("2026-02-10")

        vm.save()
        vm.state.first { !it.saving }

        val stored = graph.events.all().single()
        assertEquals(schedule.id, stored.scheduleId)
        assertFalse("the form collected the details", stored.detailsPending)
        assertEquals(profile.id, stored.profileId)
        assertEquals("2026-05-01", dueOf(schedule.id))
    }

    /** V5 (pin): the same asset and profile without a schedule id — the asset screen's way in — log an ordinary entry. */
    @Test fun theSameProfileWithoutAScheduleIdStaysAnOrdinaryEntry() = runTest {
        onTheTenth()
        val spa = spa()
        val schedule = formSchedule(spa.id, spa.profile.id)
        val vm = formModel(spa.id, spa.profile.id, scheduleId = null)
        vm.typeWaterTest(spa)

        vm.save()
        vm.state.first { !it.saving }

        val stored = graph.events.all().single()
        assertNull(stored.scheduleId)
        assertNull(stored.occurrenceOn)
        assertEquals("Water test", stored.title)
        assertEquals("2025-08-01", dueOf(schedule.id))
        assertEquals(0, reconciles)
    }

    /** V6 (pin): editing a linked completion keeps its id, link and occurrence key; it never completes, asks or sweeps. */
    @Test fun editingALinkedCompletionKeepsItsLinkAndNeverCompletes() = runTest {
        onTheTenth()
        val spa = spa()
        val schedule = formSchedule(spa.id, spa.profile.id)
        val done = graph.completeSchedule.run(
            schedule.id,
            CompletionCommand(
                occurredOn = "2026-02-10", tzId = "UTC",
                values = mapOf(spa.def("ph").id to "7.4", spa.def("free_chlorine").id to "2.0"),
            ),
        )
        val dueAfterDone = dueOf(schedule.id)
        val offers = RecordingOffers(graph.eventOffers)
        val vm = formModel(spa.id, null, scheduleId = null, eventId = done.id, offers = offers)
        vm.state.first { it.loaded }

        vm.onNotes("Cloudy")
        vm.save()
        vm.state.first { !it.saving }

        val stored = graph.events.all().single()
        assertEquals(done.id, stored.id)
        assertEquals(schedule.id, stored.scheduleId)
        assertEquals(done.occurrenceOn, stored.occurrenceOn)
        assertEquals("Cloudy", stored.notes)
        assertEquals(dueAfterDone, dueOf(schedule.id))
        assertEquals("an edit never asks", 0, offers.asked)
        assertEquals("an edit never sweeps", 0, reconciles)
    }

    /**
     * V7: the schedule archived, or deleted, while its form was open: the shipped could-not-save line and **no** event —
     * never an unlinked entry in its place (D5, I1).
     */
    @Test fun anArchivedOrDeletedScheduleRefusesTheSaveAndWritesNothing() = runTest {
        onTheTenth()
        val spa = spa()
        val archived = formSchedule(spa.id, spa.profile.id)
        val vm = formModel(spa.id, spa.profile.id, archived.id)
        vm.typeWaterTest(spa)
        graph.archiveSchedule.run(archived.id, archived = true)

        vm.save()
        val refused = vm.state.first { !it.saving }
        assertEquals(CANNOT_SAVE, refused.firstProblem)
        assertEquals(emptyList<AssetEvent>(), graph.events.all())

        val deleted = formSchedule(spa.id, spa.profile.id, id = "s-gone")
        val gone = formModel(spa.id, spa.profile.id, deleted.id)
        gone.typeWaterTest(spa)
        graph.schedules.deleteAll()

        gone.save()
        val alsoRefused = gone.state.first { !it.saving }
        assertEquals(CANNOT_SAVE, alsoRefused.firstProblem)
        assertEquals(emptyList<AssetEvent>(), graph.events.all())
        assertEquals("nothing written, nothing swept", 0, reconciles)
    }

    /** V7c: the schedule re-targeted to a group (QUICK, no profile) while its form was open: could-not-save, no event. */
    @Test fun aScheduleRetargetedToAGroupRefusesTheSave() = runTest {
        onTheTenth()
        val spa = spa()
        val schedule = formSchedule(spa.id, spa.profile.id)
        val vm = formModel(spa.id, spa.profile.id, schedule.id)
        vm.typeWaterTest(spa)
        val spas = graph.saveGroup.run(null, GroupCommand(name = "Spas")).id
        graph.schedules.upsert(
            schedule.copy(
                target = ScheduleTarget.GroupTarget(spas),
                completionMode = CompletionMode.QUICK,
                profileId = null,
            ),
        )

        vm.save()
        val refused = vm.state.first { !it.saving }

        assertEquals(CANNOT_SAVE, refused.firstProblem)
        assertEquals(emptyList<AssetEvent>(), graph.events.all())
        assertEquals(0, reconciles)
    }

    /**
     * V8a: a profile-less FORM schedule's form opens its Entry field with the schedule's title; saved untouched it
     * stores that title, and an edit there is stored as typed.
     */
    @Test fun aProfileLessFormOpensWithTheSchedulesTitle() = runTest {
        onTheTenth()
        val spa = spa()
        val schedule = formSchedule(spa.id, profileId = null, title = "Deck clean")

        val first = formModel(spa.id, null, schedule.id)
        assertEquals("Deck clean", first.state.first { it.loaded }.title)
        first.onDate("2026-02-10")
        first.save()
        first.state.first { !it.saving }
        assertEquals(listOf("Deck clean"), graph.events.all().map { it.title })
        assertEquals(schedule.id, graph.events.all().single().scheduleId)

        val second = formModel(spa.id, null, schedule.id)
        second.state.first { it.loaded }
        second.onDate("2026-02-10")
        second.onTitle("Deck clean and oil")
        second.save()
        second.state.first { !it.saving }
        val titles = graph.events.all().filter { it.scheduleId == schedule.id }.map { it.title }.sorted()
        assertEquals(listOf("Deck clean", "Deck clean and oil"), titles)
    }

    /** V8b: a profile-backed form stores the schedule's title, not the profile's default title (D3). */
    @Test fun aProfileBackedFormStoresTheSchedulesTitle() = runTest {
        onTheTenth()
        val spa = spa()
        val schedule = formSchedule(spa.id, spa.profile.id, title = "Quarterly water check")
        val vm = formModel(spa.id, spa.profile.id, schedule.id)
        assertEquals("the form draws the profile's default", "Water test", vm.state.first { it.loaded }.title)
        vm.typeWaterTest(spa)

        vm.save()
        vm.state.first { !it.saving }

        assertEquals("Quarterly water check", graph.events.all().single().title)
    }

    /** V9 (pin): a schedule-opened save asks its offers once, as any new entry does, and leaves once after the answer. */
    @Test fun aScheduleOpenedSaveAsksItsOffersOnce() = runTest {
        onTheTenth()
        val spa = spa()
        graph.conditions.insert(conditionRow("c-spa", spa.id.value, OperationalCondition.DOWN, "2026-02-01"))
        val profile = service(spa)
        val schedule = formSchedule(spa.id, profile.id, title = "Quarterly service")
        val offers = RecordingOffers(graph.eventOffers)
        val vm = formModel(spa.id, profile.id, schedule.id, offers = offers)
        vm.state.first { it.loaded }
        val saved = mutableListOf<EventId>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { vm.saved.collect { saved += it } }
        vm.onDate("2026-02-10")
        vm.onValue(spa.def("ph").id, "7.4")

        vm.save()
        val asking = vm.state.first { it.offer != null }
        assertTrue(asking.offer is OperationalOfferPrompt)
        vm.declineOffer()
        vm.state.first { it.offer == null && !it.saving }
        advanceUntilIdle()

        assertEquals(1, offers.asked)
        assertEquals(listOf(graph.events.all().single().id), saved)
    }

    /** V10 (pin): two taps in one frame write one event, leave once, and move the due at most one occurrence on. */
    @Test fun twoTapsOnAScheduleOpenedFormWriteOneEvent() = runTest {
        onTheTenth()
        val spa = spa()
        val schedule = formSchedule(spa.id, spa.profile.id)
        val vm = formModel(spa.id, spa.profile.id, schedule.id)
        val saved = mutableListOf<EventId>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { vm.saved.collect { saved += it } }
        vm.typeWaterTest(spa)

        vm.save()
        vm.save()
        vm.state.first { !it.saving }
        advanceUntilIdle()

        assertEquals(1, graph.events.all().size)
        assertEquals(1, saved.size)
        assertNotEquals("never two occurrences", "2026-08-01", dueOf(schedule.id))
    }

    /**
     * V11 (I5): a schedule id comes only with the completion collaborators and only on a new, plain entry — never with
     * an edit's event id, never with a held condition.
     */
    @Test fun aScheduleIdIsRefusedOnAnEditAPendingEntryOrWithoutTheCompletion() = runTest {
        val spa = spa()
        val id = ScheduleId("s-any")
        fun build(
            eventId: EventId? = null,
            held: PendingCondition? = null,
            complete: Boolean = true,
            repository: Boolean = true,
            sweep: Boolean = true,
        ) = EventEntryViewModel(
            graph.assets, graph.definitions, graph.profiles, graph.events,
            graph.logEvent, graph.updateEvent, graph.clock,
            spa.id, null, eventId, offers = graph.eventOffers,
            pending = held, recordWithIncident = graph.recordConditionWithIncident.takeIf { held != null },
            scheduleId = id,
            completeSchedule = graph.completeSchedule.takeIf { complete },
            schedules = graph.schedules.takeIf { repository },
            reconcile = countingReconcile.takeIf { sweep },
        )

        assertThrows(IllegalArgumentException::class.java) { build(eventId = EventId("e-1")) }
        assertThrows(IllegalArgumentException::class.java) { build(held = heldDown()) }
        assertThrows(IllegalArgumentException::class.java) { build(complete = false) }
        assertThrows(IllegalArgumentException::class.java) { build(repository = false) }
        assertThrows(IllegalArgumentException::class.java) { build(sweep = false) }
    }

    /**
     * V12 (D8, I9): the form sweeps the reminders once after a schedule-opened write and before the offers are read;
     * never after a plain entry or an edit; and a sweep that fails leaves the save standing.
     */
    @Test fun aScheduleOpenedWriteSweepsTheRemindersOnceBeforeItsOffers() = runTest {
        onTheTenth()
        val spa = spa()
        val schedule = formSchedule(spa.id, spa.profile.id)

        val completes = formModel(spa.id, spa.profile.id, schedule.id, offers = OrderedOffers(graph.eventOffers, sweeps))
        completes.typeWaterTest(spa)
        completes.save()
        completes.state.first { !it.saving }
        assertEquals(listOf("reconcile", "offers"), sweeps)

        sweeps.clear()
        val plain = formModel(spa.id, spa.profile.id, scheduleId = null, offers = OrderedOffers(graph.eventOffers, sweeps))
        plain.typeWaterTest(spa)
        plain.save()
        plain.state.first { !it.saving }
        val edit = formModel(spa.id, null, scheduleId = null, eventId = graph.events.all().first().id)
        edit.state.first { it.loaded }
        edit.onNotes("Edited")
        edit.save()
        edit.state.first { !it.saving }
        assertEquals("a plain entry and an edit never sweep", listOf("offers"), sweeps)

        val failing = formModel(
            spa.id, spa.profile.id, schedule.id,
            reconcile = ReminderReconcile { throw IllegalStateException("example sweep failure") },
        )
        val left = CompletableDeferred<EventId>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { left.complete(failing.saved.first()) }
        failing.typeWaterTest(spa)
        failing.save()
        val written = left.await()
        assertNull(failing.state.value.firstProblem)
        assertEquals(schedule.id, graph.events.get(written)!!.scheduleId)
        assertEquals("the second occurrence is written too", 2, graph.events.all().count { it.scheduleId == schedule.id })
    }

    /** V13: the asset transferred out while the form was open: the transferred-out line (P77-35), and nothing written. */
    @Test fun aTransferredOutAssetRefusesTheSaveWithItsOwnLine() = runTest {
        onTheTenth()
        val spa = spa()
        val schedule = formSchedule(spa.id, spa.profile.id)
        val vm = formModel(spa.id, spa.profile.id, schedule.id)
        vm.typeWaterTest(spa)
        graph.transferRecords.append(
            TransferRecord(
                id = "out-1", assetId = spa.id, kind = TransferKind.OUT, packId = "0f1e2d3c-pack",
                lineage = emptyList(), at = dayMillis("2026-02-10"), packSha256 = "ab".repeat(32),
                nameSnapshot = "Spa", note = "",
            ),
        )

        vm.save()
        val refused = vm.state.first { !it.saving }

        assertEquals(TransferImportStrings.ASSET_TRANSFERRED_OUT, refused.firstProblem)
        assertEquals(emptyList<AssetEvent>(), graph.events.all())
        assertEquals(0, reconciles)
    }

    // --- #119 (M1–M8): a schedule's form collects the meter reading its schedule counts ----------------------

    /** The Mower: a reading "Blade edge", the meter "Engine hours" (h) and a reading "Deck height", in that order. */
    private class Mower(
        val id: AssetId,
        val edge: MeasurementDefinition,
        val hours: MeasurementDefinition,
        val deck: MeasurementDefinition,
    )

    private suspend fun mower(): Mower {
        val id = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard")).id
        suspend fun reading(key: String, label: String, unit: String, isMeter: Boolean) = graph.saveDefinition.run(
            null,
            DefinitionCommand(
                assetId = id, key = key, label = label, unit = unit, kind = DefinitionKind.ENTERED,
                valueType = ValueType.NUMBER, decimals = 0, rangeLow = null, rangeHigh = null, isMeter = isMeter,
                formula = null, sourceA = null, sourceB = null,
            ),
        )
        return Mower(
            id,
            edge = reading("blade_edge", "Blade edge", "mm", isMeter = false),
            hours = reading("engine_hours", "Engine hours", "h", isMeter = true),
            deck = reading("deck_height", "Deck height", "mm", isMeter = false),
        )
    }

    /** "Blade service": "Blade edge" required, then [more]; it carries the meter only when [more] names it. */
    private suspend fun bladeService(
        mower: Mower,
        more: ProfileFieldInput? = null,
        name: String = "Blade service",
    ): EventProfile = graph.saveProfile.run(
        null,
        ProfileCommand(
            mower.id, name, EventKind.MAINTENANCE, "",
            listOfNotNull(ProfileFieldInput(mower.edge.id, true), more), emptyList(),
        ),
    )

    /** "Blade sharpen": a FORM schedule on [profileId] whose rule also counts [meter] (none: time only). */
    private suspend fun bladeSharpen(mower: Mower, profileId: ProfileId?, meter: DefinitionId? = mower.hours.id) =
        formSchedule(mower.id, profileId, title = "Blade sharpen", id = "s-blade", meter = meter)

    /** Each row as (definition, required): what the form draws and what it demands. */
    private fun List<FieldRow>.drawn(): List<Pair<DefinitionId, Boolean>> = map { it.definition.id to it.required }

    /** M1: the profile does not carry the meter, so its row comes after the profile's rows, required and empty. */
    @Test fun aMeteredScheduleFormAppendsTheMeterRowRequired() = runTest {
        onTheTenth()
        val mower = mower()
        val profile = bladeService(mower)
        val schedule = bladeSharpen(mower, profile.id)

        val fields = formModel(mower.id, profile.id, schedule.id).state.first { it.loaded }.fields

        assertEquals(listOf(mower.edge.id to true, mower.hours.id to true), fields.drawn())
        assertEquals("", fields.last().text)
        assertNull(fields.last().problem)
    }

    /** M2: the profile carries the meter as optional: no second row, and that row is now required. */
    @Test fun aMeterTheProfileCarriesOptionalIsMadeRequiredNotDrawnTwice() = runTest {
        onTheTenth()
        val mower = mower()
        val profile = bladeService(mower, ProfileFieldInput(mower.hours.id, false))
        val schedule = bladeSharpen(mower, profile.id)

        val fields = formModel(mower.id, profile.id, schedule.id).state.first { it.loaded }.fields

        assertEquals(listOf(mower.edge.id to true, mower.hours.id to true), fields.drawn())
    }

    /** M3: a profile-less form draws every unarchived definition once, and the meter's row is the required one. */
    @Test fun aProfileLessMeteredFormRequiresTheMeterRowAmongTheAssetsDefinitions() = runTest {
        onTheTenth()
        val mower = mower()
        val schedule = bladeSharpen(mower, profileId = null)

        val fields = formModel(mower.id, null, schedule.id).state.first { it.loaded }.fields

        assertEquals(
            listOf(mower.edge.id to false, mower.hours.id to true, mower.deck.id to false),
            fields.drawn(),
        )
    }

    /**
     * M4: the meter left blank and the required reading typed: the meter's own row is marked, the screen says
     * "Engine hours is required", and nothing is written — no event, the due and the meter state unchanged.
     */
    @Test fun aBlankMeterReadingIsRefusedOnItsRowAndNothingIsWritten() = runTest {
        onTheTenth()
        val mower = mower()
        val profile = bladeService(mower)
        val schedule = bladeSharpen(mower, profile.id)
        val stateBefore = graph.scheduleStates.get(schedule.id)
        val vm = formModel(mower.id, profile.id, schedule.id)
        vm.state.first { it.loaded }
        vm.onDate("2026-02-10")
        vm.onValue(mower.edge.id, "3")

        vm.save()
        val refused = vm.state.first { !it.saving }

        val problems = refused.fields.associate { it.definition.id to it.problem }
        assertEquals(mapOf(mower.edge.id to null, mower.hours.id to FieldProblem.Required(mower.hours.id)), problems)
        assertEquals("Engine hours is required", refused.firstProblem)
        assertFalse(refused.saving)
        assertEquals(emptyList<AssetEvent>(), graph.events.all())
        assertEquals("2025-08-01", dueOf(schedule.id))
        assertEquals(stateBefore, graph.scheduleStates.get(schedule.id))
        assertEquals(0, reconciles)
    }

    /** M5: the meter typed: one linked completion, the reading among its measurements once, the meter's state on. */
    @Test fun aTypedMeterReadingIsStoredOnceOnTheLinkedCompletion() = runTest {
        onTheTenth()
        val mower = mower()
        val profile = bladeService(mower)
        val schedule = bladeSharpen(mower, profile.id)
        val vm = formModel(mower.id, profile.id, schedule.id)
        vm.state.first { it.loaded }
        vm.onDate("2026-02-10")
        vm.onValue(mower.edge.id, "3")
        vm.onValue(mower.hours.id, "120")

        vm.save()
        vm.state.first { !it.saving }

        val stored = graph.events.all().single()
        assertEquals(schedule.id, stored.scheduleId)
        assertEquals(listOf(mower.edge.id, mower.hours.id), stored.measurements.map { it.definitionId })
        assertEquals(120.0, stored.measurements.single { it.definitionId == mower.hours.id }.valueNum!!, 0.0)
        assertEquals(120.0, graph.scheduleStates.get(schedule.id)!!.lastCompletedMeter!!, 0.0)
        assertEquals(1, reconciles)
    }

    /** M6 (pin): the same asset and profiles opened without a schedule id draw their rows as 1.8.1 did. */
    @Test fun withoutAScheduleIdTheMeterIsNeitherAddedNorRequired() = runTest {
        onTheTenth()
        val mower = mower()
        val without = bladeService(mower)
        val carrying = bladeService(mower, ProfileFieldInput(mower.hours.id, false), name = "Blade check")
        bladeSharpen(mower, without.id)

        val plain = formModel(mower.id, without.id, scheduleId = null).state.first { it.loaded }.fields
        val optional = formModel(mower.id, carrying.id, scheduleId = null).state.first { it.loaded }.fields

        assertEquals(listOf(mower.edge.id to true), plain.drawn())
        assertEquals(listOf(mower.edge.id to true, mower.hours.id to false), optional.drawn())
    }

    /** M7 (pin): a FORM schedule that counts no meter draws its rows as 1.8.1 did, profile-backed or profile-less. */
    @Test fun aFormScheduleWithoutAMeterDrawsItsRowsUnchanged() = runTest {
        onTheTenth()
        val mower = mower()
        val profile = bladeService(mower, ProfileFieldInput(mower.hours.id, false))
        val backed = bladeSharpen(mower, profile.id, meter = null)
        val bare = formSchedule(mower.id, null, title = "Deck clean", id = "s-deck")

        val withProfile = formModel(mower.id, profile.id, backed.id).state.first { it.loaded }.fields
        val profileLess = formModel(mower.id, null, bare.id).state.first { it.loaded }.fields

        assertEquals(listOf(mower.edge.id to true, mower.hours.id to false), withProfile.drawn())
        assertEquals(
            listOf(mower.edge.id to false, mower.hours.id to false, mower.deck.id to false),
            profileLess.drawn(),
        )
    }

    /** M8 (pin): editing a metered schedule's linked completion draws its rows as 1.8.1 did: no schedule is read. */
    @Test fun editingAMeteredCompletionDrawsItsRowsUnchanged() = runTest {
        onTheTenth()
        val mower = mower()
        val profile = bladeService(mower)
        val schedule = bladeSharpen(mower, profile.id)
        val done = graph.completeSchedule.run(
            schedule.id,
            CompletionCommand(
                occurredOn = "2026-02-10", tzId = "UTC",
                values = mapOf(mower.edge.id to "3", mower.hours.id to "120"),
            ),
        )

        val fields = formModel(mower.id, null, scheduleId = null, eventId = done.id).state.first { it.loaded }.fields

        assertEquals(listOf(mower.edge.id to true, mower.hours.id to false), fields.drawn())
        assertEquals(listOf("3", "120"), fields.map { it.text })
    }

    /** M9 (pin): an archived meter is off the form, as every archived definition is, even when its schedule counts it. */
    @Test fun anArchivedMeterTheProfileLacksDrawsNoRow() = runTest {
        onTheTenth()
        val mower = mower()
        val profile = bladeService(mower)
        val schedule = bladeSharpen(mower, profile.id)
        graph.definitions.upsert(mower.hours.copy(archivedAt = 9_000L))

        val fields = formModel(mower.id, profile.id, schedule.id).state.first { it.loaded }.fields

        assertEquals(listOf(mower.edge.id to true), fields.drawn())
    }

    // --- O-16 as amended (C39, row 130): a completion dated after today --------------------------------------------

    /** A schedule's form completion dated tomorrow: S25 as the first problem, nothing written, no sweep. */
    @Test fun aFormCompletionDatedTomorrowSaysS25AndWritesNothing() = runTest {
        onTheTenth()
        val spa = spa()
        val schedule = formSchedule(spa.id, spa.profile.id)
        val dueBefore = dueOf(schedule.id)
        val vm = formModel(spa.id, spa.profile.id, schedule.id)
        vm.typeWaterTest(spa)
        vm.onDate("2026-02-11")

        vm.save()
        val refused = vm.state.first { !it.saving }
        assertEquals(DATE_NOT_LATER_THAN_TODAY, refused.firstProblem)
        assertEquals(emptyList<AssetEvent>(), graph.events.all())
        assertEquals(dueBefore, dueOf(schedule.id))
        assertEquals("nothing written, nothing swept", 0, reconciles)
    }

    /** An edit that moves a completion to tomorrow: S25 as the first problem, the stored row unchanged, no sweep. */
    @Test fun anEditMovingACompletionToTomorrowSaysS25AndWritesNothing() = runTest {
        onTheTenth()
        val spa = spa()
        val schedule = formSchedule(spa.id, spa.profile.id)
        val done = graph.completeSchedule.run(
            schedule.id,
            CompletionCommand(
                occurredOn = "2026-02-10", tzId = "UTC",
                values = mapOf(spa.def("ph").id to "7.4", spa.def("free_chlorine").id to "2.0"),
            ),
        )
        val storedBefore = graph.events.get(done.id)
        val vm = formModel(spa.id, null, scheduleId = null, eventId = done.id)
        vm.state.first { it.loaded }
        vm.onDate("2026-02-11")

        vm.save()
        val refused = vm.state.first { !it.saving }
        assertEquals(DATE_NOT_LATER_THAN_TODAY, refused.firstProblem)
        assertEquals(storedBefore, graph.events.get(done.id))
        assertEquals("an edit never sweeps", 0, reconciles)
    }
}
