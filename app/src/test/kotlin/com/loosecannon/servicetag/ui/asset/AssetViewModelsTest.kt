package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.usecase.ActivationCommand
import com.loosecannon.servicetag.core.usecase.GetAssetSeason
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.replacementOf
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.testing.subjectRow
import com.loosecannon.servicetag.ui.health.ConditionView
import com.loosecannon.servicetag.ui.health.HealthPlurals
import com.loosecannon.servicetag.ui.health.NOT_TRACKED
import com.loosecannon.servicetag.core.journal.RangeState
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.isRetired
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.ui.journal.formatValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The three asset ViewModels against a Room-backed [FakeGraph]. `viewModelScope` dispatches on
 * `Dispatchers.Main`, so the main dispatcher is a test one for the length of each test; the
 * repositories still emit on their own query context, which is why every assertion waits for a
 * state rather than reading `value` straight after a write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetViewModelsTest {

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

    private fun tag(id: String, assetId: String) = TagBinding(
        id = TagId(id),
        payloadFormat = PayloadFormat.V1,
        payloadKey = "key-$id",
        target = TagTarget.AssetTarget(AssetId(assetId)),
        createdAt = 1L,
        updatedAt = 1L,
    )

    /** The detail model takes twenty-three collaborators; every test wants the same ones off the graph. */
    private fun detailModel(id: AssetId) = AssetDetailViewModel(
        graph.assets, graph.tags,
        graph.definitions, graph.profiles, graph.events,
        graph.schedules, graph.scheduleStates, graph.groups, graph.dueReadModel,
        graph.conditions, graph.seasonActivations, graph.healthSubjects,
        graph.assetHealthReadModel, graph.getAssetSeason, graph.recordSeasonActivation,
        graph.archiveAsset, graph.retireAsset, graph.deleteAsset,
        graph.applyTemplate, graph.uow, graph.clock, graph.todayPort, id,
    )

    /** The list, reading the season phase on the graph's injected `T`. */
    private fun listModel() = AssetsViewModel(graph.assets, graph.seasonActivations, graph.todayPort)

    /**
     * Create ([id] null) or edit one asset; [parentId] is the "+ Add component" preset. Suspends
     * until the form has read the picker, which is also when a stored row has been filled in —
     * a ViewModel left mid-load outlives the test that made it and then meets a closed database.
     */
    private suspend fun editModel(id: AssetId? = null, parentId: String? = null): AssetEditViewModel {
        val model = AssetEditViewModel(
            graph.assets, graph.healthSubjects, graph.saveAssetSettings, graph.schedules, id, parentId,
        )
        model.state.first { it.parentChoices.isNotEmpty() }
        return model
    }

    /** A calendar day as this device's epoch millis — what [FakeGraph.now] is moved to. */
    private fun millisOn(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun waterTest(
        assetId: AssetId,
        profileId: ProfileId,
        on: String,
        values: Map<DefinitionId, String>,
    ) = EventCommand(
        assetId = assetId,
        profileId = profileId,
        kind = EventKind.MEASUREMENT,
        title = "Water test",
        occurredOn = on,
        occurredTime = null,
        tzId = "UTC",
        notes = "",
        values = values,
        consumables = emptyList(),
    )

    @Test fun theListEmitsAfterACreateAndHidesArchivedRowsUntilTheChipIsOn() = runTest {
        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }

        val pump = graph.createAsset.run("Pool pump", "Water")
        assertEquals(listOf("Pool pump"), vm.state.first { it.items.isNotEmpty() }.items.map { it.asset.name })

        graph.archiveAsset.run(pump.id)
        val hidden = vm.state.first { it.items.isEmpty() }
        assertFalse(hidden.showArchived)
        // The empty list still knows why it is empty, so the screen can say so.
        assertEquals(1, hidden.archivedCount)

        vm.toggleArchived()
        val shown = vm.state.first { it.items.isNotEmpty() }
        assertTrue(shown.showArchived)
        assertEquals(AssetStatus.ARCHIVED, shown.items.single().asset.status)
    }

    @Test fun theDetailExposesOnlyTheTagsBoundToThisAsset() = runTest {
        val pump = graph.createAsset.run("Pool pump", "Water")
        val mower = graph.createAsset.run("Mower", "Yard")
        graph.tags.upsert(tag("t-pump", pump.id.value))
        graph.tags.upsert(tag("t-mower", mower.id.value))

        val vm = detailModel(pump.id)
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it != null && it.tags.isNotEmpty() }!!
        assertEquals("Pool pump", state.asset.name)
        assertEquals(listOf("t-pump"), state.tags.map { it.id.value })

        vm.archive()
        assertEquals(AssetStatus.ARCHIVED, vm.state.first { it?.asset?.status == AssetStatus.ARCHIVED }!!.asset.status)
    }

    @Test fun savingABlankNameFailsAndWritesNothing() = runTest {
        val vm = editModel()
        val saved = mutableListOf<AssetId>()
        backgroundScope.launch { vm.saved.collect { saved += it } }
        vm.onName("   ")
        vm.onCategory("Water")

        vm.save()
        vm.state.first { !it.saving }
        assertEquals("Give the asset a name", vm.state.value.problems[AssetField.NAME])
        assertTrue(graph.assets.all().isEmpty())
        assertTrue(saved.isEmpty())

        vm.onName("Hot tub")
        assertEquals(null, vm.state.value.problems[AssetField.NAME])
        vm.save()
        // The second tap lands in the same frame as the first: the in-flight guard drops it, so
        // one asset exists afterwards, not two.
        vm.save()
        vm.state.first { !it.saving }
        assertEquals(listOf("Hot tub"), graph.assets.all().map(Asset::name))
        assertEquals(graph.assets.all().single().id, saved.single())
    }

    @Test fun missingIsTrueForAnUnknownId() = runTest {
        val vm = detailModel(AssetId("nope"))
        backgroundScope.launch { vm.missing.collect() }

        assertTrue(vm.missing.first { it })
        assertEquals(null, vm.state.value)
    }

    @Test fun detailStateDerivesReadingsFromEvents() = runTest {
        val spa = graph.createAsset.run("Spa", "Water", templateKey = "hot_tub")
        val defs = graph.definitions.forAsset(spa.id).associateBy(MeasurementDefinition::key)
        val profile = graph.profiles.forAsset(spa.id).first { it.name == "Water test" }
        val ph = defs.getValue("ph")
        val chlorine = defs.getValue("free_chlorine")

        val vm = detailModel(spa.id)
        backgroundScope.launch { vm.state.collect() }

        graph.logEvent.run(
            waterTest(spa.id, profile.id, "2026-09-12", mapOf(ph.id to "7.4", chlorine.id to "2.0")),
        )
        val newer = graph.logEvent.run(
            waterTest(spa.id, profile.id, "2026-09-15", mapOf(ph.id to "7.8", chlorine.id to "3.4")),
        )

        val both = vm.state.first { it?.events?.size == 2 }!!
        // The newest event by §4.1 supplies the current reading, whatever order it was written in.
        assertEquals("pH", both.readings.first().definition.label)
        assertEquals(7.8, both.readings.first().measurement?.valueNum!!, 1e-9)
        // 7.8 is the top of 7.2-7.8 and the bounds are inclusive (§4.3), so the row is in range;
        // the chlorine reading is the one genuinely past its bound.
        assertEquals(RangeState.IN_RANGE, both.readings.first().state)
        assertEquals(RangeState.HIGH, both.readings.first { it.definition.key == "free_chlorine" }.state)
        // Nothing has been logged for water temperature, so it is a reading with no value.
        assertEquals(null, both.readings.first { it.definition.key == "water_temp" }.measurement)

        graph.deleteEvent.run(newer.id)
        val one = vm.state.first { it?.events?.size == 1 }!!
        assertEquals(7.4, one.readings.first().measurement?.valueNum!!, 1e-9)
    }

    @Test fun detailStateListsUnarchivedProfilesInOrder() = runTest {
        val spa = graph.createAsset.run("Spa", "Water", templateKey = "hot_tub")
        val vm = detailModel(spa.id)
        backgroundScope.launch { vm.state.collect() }

        val ordered = vm.state.first { it?.profiles?.isNotEmpty() == true }!!
        assertEquals(listOf("Water test", "Treatment"), ordered.profiles.map(EventProfile::name))

        val treatment = graph.profiles.forAsset(spa.id).first { it.name == "Treatment" }
        graph.profiles.upsert(treatment.copy(archivedAt = 9_000L))
        // An archived profile keeps its history but stops offering a quick action.
        assertEquals(
            listOf("Water test"),
            vm.state.first { it?.profiles?.size == 1 }!!.profiles.map(EventProfile::name),
        )
    }

    @Test fun setUpFromTemplateOnAPlainAsset() = runTest {
        val ups = graph.createAsset.run("Rack UPS", "Power")
        val vm = detailModel(ups.id)
        backgroundScope.launch { vm.state.collect() }
        assertTrue(vm.state.first { it != null }!!.definitions.isEmpty())

        vm.setUpFromTemplate("ups")

        val ready = vm.state.first { it?.definitions?.size == 4 && it.profiles.size == 2 }!!
        assertEquals("Battery voltage", ready.definitions.first().label)
        assertEquals(listOf("Load test", "Battery replacement"), ready.profiles.map(EventProfile::name))
    }

    /**
     * "Set up from template" is offered only while there is genuinely nothing to log against, and
     * archiving is not deleting (spec §9): an asset whose rows are all archived still has them, so
     * the template — which would be refused anyway — is not offered again.
     */
    @Test fun bareIgnoresNothingArchivedCountsAsExisting() = runTest {
        val thing = graph.createAsset.run("Thing", "Misc")
        val vm = detailModel(thing.id)
        backgroundScope.launch { vm.state.collect() }

        assertTrue(vm.state.first { it != null }!!.bare)

        vm.setUpFromTemplate("ups")
        assertFalse(vm.state.first { it?.definitions?.size == 4 }!!.bare)

        graph.definitions.forAsset(thing.id).forEach { graph.definitions.upsert(it.copy(archivedAt = 9_000L)) }
        graph.profiles.forAsset(thing.id).forEach { graph.profiles.upsert(it.copy(archivedAt = 9_000L)) }

        // The quick actions are gone because every profile is archived; the asset is still not bare.
        val archived = vm.state.first { it?.profiles?.isEmpty() == true }!!
        assertFalse(archived.bare)
        assertEquals(4, archived.definitions.size)
    }

    /**
     * The derived reading of spec §5 on the asset screen: computed from the newest event that can
     * produce it, with no measurement behind it and nothing stored anywhere.
     */
    @Test fun readingsIncludeDerived() = runTest {
        val ro = graph.createAsset.run("RO unit", "Water", templateKey = "ro_water")
        val defs = graph.definitions.forAsset(ro.id).associateBy(MeasurementDefinition::key)
        val profile = graph.profiles.forAsset(ro.id).first { it.name == "TDS test" }

        val vm = detailModel(ro.id)
        backgroundScope.launch { vm.state.collect() }

        graph.logEvent.run(
            EventCommand(
                assetId = ro.id,
                profileId = profile.id,
                kind = EventKind.MEASUREMENT,
                title = "TDS test",
                occurredOn = "2026-09-15",
                occurredTime = null,
                tzId = "UTC",
                notes = "",
                values = mapOf(
                    defs.getValue("tds_prefilter").id to "310",
                    defs.getValue("tds_post_membrane").id to "18",
                    defs.getValue("tds_output").id to "16",
                ),
                consumables = emptyList(),
            ),
        )

        val state = vm.state.first { it?.events?.size == 1 }!!
        val rejection = state.readings.first { it.definition.label == "Rejection" }
        assertEquals(null, rejection.measurement)
        assertEquals(94.19, rejection.derivedValue!!, 0.01)
        assertEquals("94.2", formatValue(rejection))
        assertEquals("2026-09-15", rejection.occurredOn)
    }

    @Test fun newAssetFormPassesTemplateKey() = runTest {
        val vm = editModel()
        vm.onName("Spa")
        vm.onTemplate("hot_tub")
        assertEquals("hot_tub", vm.state.value.templateKey)

        vm.save()
        vm.state.first { !it.saving }

        // The form only names a template; seeding it is the create's own transaction (§7).
        val id = graph.assets.all().single().id
        assertEquals(5, graph.definitions.forAsset(id).size)
        assertEquals(listOf("Water test", "Treatment"), graph.profiles.forAsset(id).map(EventProfile::name))
    }

    @Test fun newAssetDefaultsToNoTemplateAndCanBeSetUpLater() = runTest {
        val form = editModel()

        // None is the default: a new asset starts bare and is set up when the user knows what it is.
        assertEquals(null, form.state.value.templateKey)
        form.onName("Thing")
        form.save()
        form.state.first { !it.saving }

        val id = graph.assets.all().single { it.name == "Thing" }.id
        assertTrue(graph.definitions.forAsset(id).isEmpty())
        assertTrue(graph.profiles.forAsset(id).isEmpty())

        val detail = detailModel(id)
        backgroundScope.launch { detail.state.collect() }
        detail.setUpFromTemplate("ups")
        assertEquals(4, detail.state.first { it?.definitions?.size == 4 }!!.definitions.size)

        // Generic is a choice of its own, not the default: one profile and no definitions.
        val explicit = editModel()
        explicit.onName("Ladder")
        explicit.onTemplate("generic")
        explicit.save()
        explicit.state.first { !it.saving }

        val ladder = graph.assets.all().single { it.name == "Ladder" }.id
        assertTrue(graph.definitions.forAsset(ladder).isEmpty())
        assertEquals(listOf("Note"), graph.profiles.forAsset(ladder).map(EventProfile::name))
    }

    /**
     * The hint rule of spec §8, first half: a category that names a suggestion pre-selects that
     * suggestion's template. It follows the field down as well as up — a category with no hint,
     * and free text that matches nothing, both leave the form with no template — because until
     * the user chooses one, the template is the category's opinion and not a decision.
     */
    @Test fun hintPreselectsTemplateOnNewAsset() = runTest {
        val vm = editModel()
        assertEquals(null, vm.state.value.templateKey)

        vm.onCategory("RO system")
        assertEquals("ro_water", vm.state.value.templateKey)

        vm.onCategory("ro SYSTEM")
        assertEquals("ro_water", vm.state.value.templateKey)

        // A suggestion of its own with nothing to seed, and then text the catalog never heard of.
        vm.onCategory("Battery")
        assertEquals(null, vm.state.value.templateKey)
        vm.onCategory("Chicken coop")
        assertEquals(null, vm.state.value.templateKey)

        // Nothing here was the user choosing a template, so the hint is still allowed to speak.
        assertFalse(vm.state.value.templateTouched)
    }

    /** The other half of §8: once the user has chosen, the category stops having an opinion. */
    @Test fun explicitTemplateSurvivesCategoryChange() = runTest {
        val vm = editModel()
        vm.onTemplate("ups")
        assertTrue(vm.state.value.templateTouched)

        vm.onCategory("RO system")
        assertEquals("ups", vm.state.value.templateKey)

        // Choosing None by hand is a choice too: the category cannot talk it back up.
        val cleared = editModel()
        cleared.onTemplate(null)
        cleared.onCategory("Hot tub")
        assertEquals(null, cleared.state.value.templateKey)

        // And the seed the create actually runs is the one the user chose, not the one hinted.
        vm.onName("Rack UPS")
        vm.save()
        vm.state.first { !it.saving }
        val id = graph.assets.all().single { it.name == "Rack UPS" }.id
        assertEquals(4, graph.definitions.forAsset(id).size)
        assertEquals(listOf("Load test", "Battery replacement"), graph.profiles.forAsset(id).map(EventProfile::name))
    }

    /**
     * Editing never shows or changes the template (spec §8): an asset in use has rows of its own,
     * and a template is starter data that would clobber them.
     */
    @Test fun editingNeverShowsOrChangesTemplate() = runTest {
        val spa = graph.createAsset.run("Spa", "Water", templateKey = "hot_tub")
        val vm = editModel(spa.id)

        val loaded = vm.state.first { it.name == "Spa" }
        assertTrue(loaded.editing)
        assertEquals(null, loaded.templateKey)

        vm.onCategory("RO system")
        assertEquals(null, vm.state.value.templateKey)

        vm.save()
        vm.state.first { !it.saving }
        // Still the hot tub's five readings: the RO template was never named, let alone applied.
        assertEquals(5, graph.definitions.forAsset(spa.id).size)
        assertEquals("RO system", graph.assets.all().single().category)
    }

    /**
     * The picker of spec §5 over a three-level tree: choosing a parent can never be the move that
     * creates the cycle, so the root is offered nobody and the grandchild is offered everyone who
     * is not beneath it. Archived rows are offered and marked — a component of an archived machine
     * is still its component (R-9).
     */
    @Test fun parentChoicesExcludeSelfAndDescendants() = runTest {
        val root = graph.createAsset.run("Root")
        val child = graph.createAsset.run("Child")
        val grand = graph.createAsset.run("Grandchild")
        graph.updateAsset.run(child.id, AssetCommand(name = "Child", parentAssetId = root.id))
        graph.updateAsset.run(grand.id, AssetCommand(name = "Grandchild", parentAssetId = child.id))

        val editingRoot = editModel(root.id)
        assertEquals(
            listOf("None"),
            editingRoot.state.first { it.name == "Root" }.parentChoices.map(ParentChoice::label),
        )

        val editingGrand = editModel(grand.id)
        val loaded = editingGrand.state.first { it.name == "Grandchild" }
        assertEquals(listOf("None", "Child", "Root"), loaded.parentChoices.map(ParentChoice::label))
        // The stored parent comes back selected, so opening the form changes nothing by itself.
        assertEquals(child.id.value, loaded.parentId)

        graph.archiveAsset.run(root.id)
        val afterArchive = editModel(grand.id)
        assertEquals(
            listOf("None", "Child", "Root (archived)"),
            afterArchive.state.first { it.name == "Grandchild" }.parentChoices.map(ParentChoice::label),
        )
    }

    /** "+ Add component" is a new asset with its Part of already answered (spec §9). */
    @Test fun presetParentIsSelected() = runTest {
        val root = graph.createAsset.run("Generator")
        val vm = editModel(parentId = root.id.value)

        assertEquals(root.id.value, vm.state.value.parentId)
        // And the preset is a row of the picker, so the form shows a name rather than an id.
        val ready = vm.state.first { it.parentChoices.size > 1 }
        assertEquals("Generator", ready.parentChoices.first { it.id == root.id.value }.label)

        vm.onName("Starter battery")
        vm.save()
        vm.state.first { !it.saving }
        assertEquals(root.id, graph.assets.all().single { it.name == "Starter battery" }.parentAssetId)
    }

    /**
     * 1.4: "Same dates every year" is where a window lives; "Year-round" sends none. The typed dates
     * stay in the form but are sent only under S30, so going back to Year-round clears the stored pair.
     */
    @Test fun calendarSendsTheWindowAndYearRoundSendsNone() = runTest {
        val vm = editModel()
        vm.onName("Snowblower")
        assertEquals(SeasonMode.YEAR_ROUND, vm.state.value.seasonMode)

        vm.onSeasonMode(SeasonMode.CALENDAR)
        vm.onSeasonStart("11-01")
        vm.onSeasonEnd("03-31")
        vm.save()
        vm.state.first { !it.saving }

        val stored = graph.assets.all().single()
        assertEquals(SeasonMode.CALENDAR, stored.seasonMode)
        assertEquals("11-01", stored.seasonStartMmdd)
        assertEquals("03-31", stored.seasonEndMmdd)

        val edit = editModel(stored.id)
        val loaded = edit.state.first { it.name == "Snowblower" }
        assertEquals(SeasonMode.CALENDAR, loaded.seasonMode)
        assertEquals("11-01", loaded.seasonStart)

        edit.onSeasonMode(SeasonMode.YEAR_ROUND)
        edit.save()
        edit.state.first { !it.saving }

        val cleared = graph.assets.all().single()
        assertEquals(SeasonMode.YEAR_ROUND, cleared.seasonMode)
        assertEquals(null, cleared.seasonStartMmdd)
        assertEquals(null, cleared.seasonEndMmdd)
    }

    /**
     * A new asset guesses the currency from the device once (spec §9). Once, because a person who
     * clears it meant to clear it, and never on an existing asset — that one already has an answer.
     */
    @Test fun currencyDefaultsFromLocaleOnce() = runTest {
        val was = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val vm = editModel()
            assertEquals("USD", vm.state.value.currency)

            vm.onCurrency("")
            vm.onName("Pump")
            vm.onPrice("10")
            // Nothing re-applies the guess: the field stays as the person left it.
            assertEquals("", vm.state.value.currency)

            val stored = graph.createAsset.run(
                AssetCommand(name = "Mower", purchasePriceMinor = 45_000L, currency = "EUR"),
            )
            val edit = editModel(stored.id)
            // An existing asset takes the row's currency, never the locale's.
            assertEquals("EUR", edit.state.first { it.name == "Mower" }.currency)
        } finally {
            Locale.setDefault(was)
        }
    }

    /** Every field the editor holds reaches the row, in one command, through the one gate. */
    @Test fun saveSendsTheFullCommand() = runTest {
        val vm = editModel()
        vm.onName("Hot tub")
        vm.onCategory("Backyard water")
        vm.onManufacturer("Jacuzzi")
        vm.onModel("J-235")
        vm.onSerialNumber("SN-90210")
        vm.onDescription("Six seats, two pumps")
        vm.onLocation("Back deck")
        vm.onSeasonMode(SeasonMode.CALENDAR)
        vm.onSeasonStart("05-01")
        vm.onSeasonEnd("09-30")
        vm.onPurchaseOn("2026-04-01")
        vm.onInServiceOn("2026-04-05")
        vm.onCurrency("USD")
        vm.onPrice("8499.99")
        vm.onVendor("Spa Depot")
        vm.onWarrantyExpiresOn("2031-04-01")
        vm.onWarrantyNotes("Shell only")
        vm.onNotes("Drains in October")

        vm.save()
        vm.state.first { !it.saving }

        val stored = graph.assets.all().single()
        assertEquals("Hot tub", stored.name)
        assertEquals("Backyard water", stored.category)
        assertEquals("Jacuzzi", stored.manufacturer)
        assertEquals("J-235", stored.model)
        assertEquals("SN-90210", stored.serialNumber)
        assertEquals("Six seats, two pumps", stored.description)
        assertEquals("Back deck", stored.location)
        assertEquals(null, stored.parentAssetId)
        assertEquals("05-01", stored.seasonStartMmdd)
        assertEquals("09-30", stored.seasonEndMmdd)
        assertEquals("2026-04-01", stored.purchaseOn)
        assertEquals("2026-04-05", stored.inServiceOn)
        assertEquals(849_999L, stored.purchasePriceMinor)
        assertEquals("USD", stored.currency)
        assertEquals("Spa Depot", stored.vendor)
        assertEquals("2031-04-01", stored.warrantyExpiresOn)
        assertEquals("Shell only", stored.warrantyNotes)
        assertEquals("Drains in October", stored.notes)
        assertEquals(AssetStatus.ACTIVE, stored.status)
        assertEquals(null, stored.retiredOn)
    }

    /** Minor units have one owner (spec §4): the form only ever hands text to [Money]. */
    @Test fun priceParsesThroughMoney() = runTest {
        val vm = editModel()
        vm.onName("Generator")
        vm.onCurrency("USD")
        // Grouping stripped, "." decimal, scaled by the currency's two digits.
        vm.onPrice("1,234.5")
        vm.save()
        vm.state.first { !it.saving }

        val stored = graph.assets.all().single()
        assertEquals(123_450L, stored.purchasePriceMinor)
        assertEquals("USD", stored.currency)

        // And back out as the form shows it: the amount without the code the next field names.
        val edit = editModel(stored.id)
        assertEquals("1234.50", edit.state.first { it.name == "Generator" }.price)
    }

    /**
     * A reparent that would swallow the asset is refused by the use case and said out loud, by
     * name (spec §9). The picker would not offer the move; nothing about that makes the rule the
     * picker's, so the form has to survive being asked anyway.
     */
    @Test fun cycleRefusalIsAMessage() = runTest {
        val root = graph.createAsset.run("Root")
        val child = graph.createAsset.run("Child")
        val grand = graph.createAsset.run("Grandchild")
        graph.updateAsset.run(child.id, AssetCommand(name = "Child", parentAssetId = root.id))
        graph.updateAsset.run(grand.id, AssetCommand(name = "Grandchild", parentAssetId = child.id))

        val vm = editModel(root.id)
        // Subscribed before the save, on the unconfined main dispatcher, so the one-shot line
        // cannot be emitted into an empty room: `messages` has no replay, by design.
        val said = async(Dispatchers.Main) { vm.messages.first() }

        vm.onParent(grand.id.value)
        vm.save()

        assertEquals("Grandchild is already part of this asset.", said.await())
        vm.state.first { !it.saving }
        // Nothing moved: the tree is exactly as it was, and no field was marked either.
        assertEquals(null, graph.assets.get(root.id)!!.parentAssetId)
        assertEquals(child.id, graph.assets.get(grand.id)!!.parentAssetId)
        assertTrue(vm.state.value.problems.isEmpty())
    }

    /**
     * Every problem the use case can name lands under the field that can fix it, on one submit,
     * and editing a field clears only its own line.
     */
    @Test fun problemsLandUnderTheirFields() = runTest {
        val vm = editModel()
        vm.onPurchaseOn("nope")
        vm.onInServiceOn("2026-02-30")
        vm.onWarrantyExpiresOn("later")
        vm.save()

        val marked = vm.state.first { !it.saving }.problems
        assertEquals("Give the asset a name", marked[AssetField.NAME])
        assertEquals("Enter a date as YYYY-MM-DD", marked[AssetField.PURCHASE_ON])
        assertEquals("Enter a date as YYYY-MM-DD", marked[AssetField.IN_SERVICE_ON])
        assertEquals("Enter a date as YYYY-MM-DD", marked[AssetField.WARRANTY_EXPIRES_ON])
        assertTrue(graph.assets.all().isEmpty())

        vm.onPurchaseOn("2026-04-01")
        assertEquals(null, vm.state.value.problems[AssetField.PURCHASE_ON])
        assertEquals("Give the asset a name", vm.state.value.problems[AssetField.NAME])

        // The price pair is settled before the command is built, so its three marks come from here.
        val priced = editModel()
        priced.onName("Pump")
        priced.onPrice("100")
        priced.onCurrency("")
        priced.save()
        assertEquals(
            "A price needs a currency",
            priced.state.first { !it.saving }.problems[AssetField.CURRENCY],
        )

        priced.onCurrency("ZZZ")
        priced.save()
        assertEquals(
            "Currency is a three-letter code like USD",
            priced.state.first { !it.saving }.problems[AssetField.CURRENCY],
        )

        priced.onCurrency("USD")
        priced.onPrice("1.234")
        priced.save()
        assertEquals(
            "Enter a price like 123.45",
            priced.state.first { !it.saving }.problems[AssetField.PRICE],
        )
        assertTrue(graph.assets.all().isEmpty())
    }

    /**
     * The parent's COMPONENTS section counts each child's *own* out-of-range readings and rolls no
     * values up (spec §2): the spa's own instrument panel stays empty while its heater has a
     * reading past its bound, and the heater's screen names the spa as what it is part of.
     */
    @Test fun componentsListChildrenWithOutOfRangeCounts() = runTest {
        val spa = graph.createAsset.run("Spa", "Water")
        val heater = graph.createAsset.run("Heater", "Heating", templateKey = "hot_tub")
        val cover = graph.createAsset.run("Cover", "Cover")
        graph.updateAsset.run(
            heater.id,
            AssetCommand(name = "Heater", category = "Heating", parentAssetId = spa.id),
        )
        graph.updateAsset.run(
            cover.id,
            AssetCommand(name = "Cover", category = "Cover", parentAssetId = spa.id),
        )

        val defs = graph.definitions.forAsset(heater.id).associateBy(MeasurementDefinition::key)
        val profile = graph.profiles.forAsset(heater.id).first { it.name == "Water test" }
        graph.logEvent.run(
            waterTest(
                heater.id,
                profile.id,
                "2026-09-15",
                mapOf(
                    defs.getValue("ph").id to "7.4",
                    defs.getValue("free_chlorine").id to "9.0",
                ),
            ),
        )

        val vm = detailModel(spa.id)
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it?.components?.size == 2 }!!
        assertEquals(listOf("Cover", "Heater"), state.components.map(ComponentRow::name))
        assertEquals(listOf("Cover", "Heating"), state.components.map(ComponentRow::category))
        assertEquals(0, state.components.first { it.name == "Cover" }.outOfRange)
        assertEquals(1, state.components.first { it.name == "Heater" }.outOfRange)
        // The parent's own readings and service record contain only its own data (spec §5).
        assertTrue(state.readings.isEmpty())
        assertEquals(null, state.parentName)

        val child = detailModel(heater.id)
        backgroundScope.launch { child.state.collect() }
        val childState = child.state.first { it?.parentName != null }!!
        assertEquals("Spa", childState.parentName)
        assertEquals(spa.id.value, childState.parentId)
        // A child is a full asset: its own readings are its own, and it has no components.
        assertEquals(1, childState.readings.count { it.state == RangeState.HIGH })
        assertTrue(childState.components.isEmpty())
    }

    /**
     * OUT OF SEASON is read off the injected `T`, not stored (spec §6, §3.1). The same asset is out of
     * season in January and in it in June, and a window that wraps the year says the opposite of both.
     */
    @Test fun outOfSeasonComputedFromClock() = runTest {
        val mower = graph.createAsset.run("Mower", "Yard")
        graph.updateAsset.run(
            mower.id,
            AssetCommand(name = "Mower", seasonStartMmdd = "05-01", seasonEndMmdd = "09-30"),
        )

        graph.today = LocalDate.parse("2026-01-15")
        val winter = detailModel(mower.id)
        backgroundScope.launch { winter.state.collect() }
        assertTrue(winter.state.first { it != null }!!.outOfSeason)

        graph.today = LocalDate.parse("2026-06-15")
        val summer = detailModel(mower.id)
        backgroundScope.launch { summer.state.collect() }
        assertFalse(summer.state.first { it != null }!!.outOfSeason)

        // A window whose start is after its end wraps the year, inclusively (spec §6).
        graph.updateAsset.run(
            mower.id,
            AssetCommand(name = "Mower", seasonStartMmdd = "11-01", seasonEndMmdd = "02-28"),
        )
        graph.today = LocalDate.parse("2026-01-15")
        val wrappedWinter = detailModel(mower.id)
        backgroundScope.launch { wrappedWinter.state.collect() }
        assertFalse(wrappedWinter.state.first { it != null }!!.outOfSeason)

        graph.today = LocalDate.parse("2026-06-15")
        val wrappedSummer = detailModel(mower.id)
        backgroundScope.launch { wrappedSummer.state.collect() }
        assertTrue(wrappedSummer.state.first { it != null }!!.outOfSeason)
    }

    /**
     * Retirement is data (spec §7): the row stays, the status is untouched, and logging what
     * happened is a separate offer that "Not now" declines without undoing anything.
     */
    @Test fun retireKeepsAssetAndFollowOnIsOptional() = runTest {
        val mower = graph.createAsset.run("Mower", "Yard")
        val vm = detailModel(mower.id)
        backgroundScope.launch { vm.state.collect() }
        backgroundScope.launch { vm.prompt.collect() }
        backgroundScope.launch { vm.missing.collect() }
        vm.state.first { it != null }

        graph.now = millisOn("2026-09-15")
        vm.askRetire()
        // The dialog opens on today and the date is the user's from there: backdating is normal.
        assertEquals("2026-09-15", (vm.prompt.value as DetailPrompt.Retire).date)

        vm.retire("2026-04-02")
        val retired = vm.state.first { it?.asset?.isRetired == true }!!
        assertEquals("2026-04-02", retired.asset.retiredOn)
        assertEquals(AssetStatus.ACTIVE, retired.asset.status)
        assertFalse(vm.missing.value)

        // Only once the date is written is the entry offered — and declining it leaves it written.
        assertEquals(DetailPrompt.LogWhatHappened, vm.prompt.first { it is DetailPrompt.LogWhatHappened })
        vm.dismissPrompt()
        assertEquals(null, vm.prompt.value)
        assertEquals("2026-04-02", graph.assets.get(mower.id)!!.retiredOn)
    }

    @Test fun unretireClears() = runTest {
        val mower = graph.createAsset.run("Mower", "Yard")
        graph.retireAsset.retire(mower.id, "2026-04-02")

        val vm = detailModel(mower.id)
        backgroundScope.launch { vm.state.collect() }
        assertTrue(vm.state.first { it != null }!!.asset.isRetired)

        vm.unretire()
        assertEquals(null, vm.state.first { it?.asset?.isRetired == false }!!.asset.retiredOn)
    }

    /** Children-first (spec §5): the refusal names them, and nothing is deleted. */
    @Test fun deleteRefusedNamesChildren() = runTest {
        val generator = graph.createAsset.run("Generator", "Power")
        val battery = graph.createAsset.run("Starter battery", "Battery")
        graph.updateAsset.run(
            battery.id,
            AssetCommand(name = "Starter battery", parentAssetId = generator.id),
        )

        val vm = detailModel(generator.id)
        backgroundScope.launch { vm.state.collect() }
        backgroundScope.launch { vm.prompt.collect() }
        vm.state.first { it?.components?.size == 1 }

        vm.askDelete()
        assertEquals(DetailPrompt.ConfirmDelete, vm.prompt.value)

        vm.delete()
        val refused = vm.prompt.first { it is DetailPrompt.DeleteRefused } as DetailPrompt.DeleteRefused
        assertEquals(listOf("Starter battery"), refused.children)
        assertEquals(2, graph.assets.all().size)
    }

    @Test fun deleteWithoutChildrenEmits() = runTest {
        val thing = graph.createAsset.run("Thing", "Misc")
        val vm = detailModel(thing.id)
        backgroundScope.launch { vm.state.collect() }
        // The screen leaves by the one shot rather than as a side effect of the row disappearing,
        // so the test waits on that shot: the delete itself runs in the ViewModel's own scope.
        val gone = backgroundScope.async { vm.deleted.first() }
        vm.state.first { it != null }

        vm.delete()
        gone.await()
        assertTrue(graph.assets.all().isEmpty())
        assertEquals(null, vm.prompt.value)
    }

    /**
     * Active, then retired, then archived, by name within each group and case-insensitively, so
     * "apple press" does not sort after "Zebra mower" (spec §9). An asset that is both retired and
     * archived belongs to the archived tail: the chip that hides archived rows must hide all of them.
     */
    @Test fun assetsListSortsActiveRetiredArchived() = runTest {
        graph.createAsset.run("Zebra mower", "Yard")
        graph.createAsset.run("apple press", "Kitchen")
        val retired = graph.createAsset.run("Brine pump", "Water")
        val archived = graph.createAsset.run("Ash vacuum", "Shop")
        val both = graph.createAsset.run("Attic fan", "Air")
        graph.retireAsset.retire(retired.id, "2026-04-02")
        graph.archiveAsset.run(archived.id)
        graph.retireAsset.retire(both.id, "2026-01-01")
        graph.archiveAsset.run(both.id)

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }

        val active = vm.state.first { it.items.size == 3 }
        assertEquals(
            listOf("apple press", "Zebra mower", "Brine pump"),
            active.items.map { it.asset.name },
        )
        assertEquals(2, active.archivedCount)

        vm.toggleArchived()
        val all = vm.state.first { it.items.size == 5 }
        assertEquals(
            listOf("apple press", "Zebra mower", "Brine pump", "Ash vacuum", "Attic fan"),
            all.items.map { it.asset.name },
        )
    }

    /**
     * The COMPONENTS section is always there (spec §9), so a childless asset still offers
     * "+ Add component" — the action that makes a first child cannot be behind already having one.
     * At this level that is a state with no children and a screen that does not branch on it.
     */
    @Test fun componentsSectionOffersAddOnAChildlessAsset() = runTest {
        val generator = graph.createAsset.run("Generator", "Power")
        val vm = detailModel(generator.id)
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it != null }!!
        assertTrue(state.components.isEmpty())
        // And it is childless rather than unloaded: the asset itself is there.
        assertEquals("Generator", state.asset.name)

        // One child later the same state lists it, with no other part of the screen changing.
        val battery = graph.createAsset.run("Starter battery", "Battery")
        graph.updateAsset.run(
            battery.id,
            AssetCommand(name = "Starter battery", parentAssetId = generator.id),
        )
        assertEquals(
            listOf("Starter battery"),
            vm.state.first { it?.components?.size == 1 }!!.components.map(ComponentRow::name),
        )
    }

    /**
     * A row says whose component it is and whether today is outside its window (spec §6, §9).
     * Restored as it was (B07 fix round 1, controller ruling): the Assets screen never hid
     * components — B07 only moved the search box here, it did not change what a blank query lists.
     */
    @Test fun assetsRowsCarryPartOf() = runTest {
        val generator = graph.createAsset.run("Generator", "Power")
        val battery = graph.createAsset.run("Starter battery", "Battery")
        graph.updateAsset.run(
            battery.id,
            AssetCommand(
                name = "Starter battery",
                parentAssetId = generator.id,
                seasonStartMmdd = "05-01",
                seasonEndMmdd = "09-30",
            ),
        )
        graph.today = LocalDate.parse("2026-01-15")

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }

        val rows = vm.state.first { it.items.size == 2 }.items.associateBy { it.asset.name }
        assertEquals("Generator", rows.getValue("Starter battery").parentName)
        assertTrue(rows.getValue("Starter battery").outOfSeason)
        // A root asset with no window says neither thing.
        assertEquals(null, rows.getValue("Generator").parentName)
        assertFalse(rows.getValue("Generator").outOfSeason)
    }

    /**
     * B07 fix round 1 (controller ruling) — the search box's move did not change the list: a
     * component is on it under a blank query exactly as before, naming its system, and a search
     * for its name only narrows the same list down to it.
     */
    @Test fun aComponentIsListedUnderABlankQueryAndASearchNarrowsToIt() = runTest {
        val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
        graph.createAsset.run(AssetCommand(name = "Circulation pump", parentAssetId = tub.id))

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }

        // Blank query: both rows, the component already naming its system.
        val blank = vm.state.first { it.items.size == 2 }
        val byName = blank.items.associateBy { it.asset.name }
        assertEquals("Hot tub", byName.getValue("Circulation pump").parentName)
        assertEquals(null, byName.getValue("Hot tub").parentName)

        // A search for the component's own name narrows the same list down to it.
        vm.onQueryChange("circ")
        val narrowed = vm.state.first { it.query == "circ" }
        assertEquals(listOf("Circulation pump"), narrowed.items.map { it.asset.name })
        assertEquals("Hot tub", narrowed.items.single().parentName)
    }

    /** B07 — a blank query lists the assets exactly as the screen always has. */
    @Test fun blankQueryListsAssetsAsBefore() = runTest {
        graph.createAsset.run("Zebra mower", "Yard")
        graph.createAsset.run("apple press", "Kitchen")

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.items.size == 2 }
        assertEquals("", state.query)
        assertEquals(listOf("apple press", "Zebra mower"), state.items.map { it.asset.name })
    }

    /** B07 — a name match narrows the list to the asset it names. */
    @Test fun aNameMatchNarrowsTheList() = runTest {
        graph.createAsset.run("Circulation pump", "Water")
        graph.createAsset.run("Mower", "Yard")

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.items.size == 2 }

        vm.onQueryChange("circ")
        val hit = vm.state.first { it.query == "circ" }
        assertEquals(listOf("Circulation pump"), hit.items.map { it.asset.name })
    }

    /** B07 — a category match narrows the list too; the six fields carry over unchanged. */
    @Test fun aCategoryMatchNarrowsTheList() = runTest {
        graph.createAsset.run("Circulation pump", "Water")
        graph.createAsset.run("Mower", "Yard")

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.items.size == 2 }

        vm.onQueryChange("water")
        val hit = vm.state.first { it.query == "water" }
        assertEquals(listOf("Circulation pump"), hit.items.map { it.asset.name })
    }

    /** B07 — clearing the box puts the (unhidden) list back, in one call. */
    @Test fun clearRestoresTheList() = runTest {
        val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
        graph.createAsset.run(AssetCommand(name = "Circulation pump", parentAssetId = tub.id))

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.items.size == 2 }

        vm.onQueryChange("circ")
        assertEquals(
            listOf("Circulation pump"),
            vm.state.first { it.query == "circ" }.items.map { it.asset.name },
        )

        vm.clearQuery()
        val cleared = vm.state.first { it.query.isEmpty() && it.items.size == 2 }
        assertEquals(listOf("Circulation pump", "Hot tub"), cleared.items.map { it.asset.name })
    }

    /** B07 — "Show archived" and the search box narrow independently, exactly like F2 did. */
    @Test fun showArchivedStillComposesWithAQuery() = runTest {
        val mower = graph.createAsset.run("Mower", "Yard")
        graph.createAsset.run("Zebra mower", "Yard")
        graph.archiveAsset.run(mower.id)

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.items.isNotEmpty() }

        vm.onQueryChange("mower")
        val activeOnly = vm.state.first { it.query == "mower" }
        assertEquals(listOf("Zebra mower"), activeOnly.items.map { it.asset.name })

        vm.toggleArchived()
        val both = vm.state.first { it.showArchived && it.query == "mower" && it.items.size == 2 }
        assertEquals(listOf("Zebra mower", "Mower"), both.items.map { it.asset.name })
    }

    /**
     * Q2 (B07 fix round 2, controller ruling) — `Asset.matches` moved with `AssetSearch.kt` and its
     * trim rule moved with it, but nothing exercised leading/trailing whitespace once
     * `theQueryIsTrimmedButKeptVerbatim` was dropped with the rest of the Dashboard's query cases.
     * Pins both halves: a padded query still finds its hit, and a query of pure whitespace trims to
     * empty and matches everything, the same as a blank one.
     */
    @Test fun aPaddedQueryIsTrimmedAndWhitespaceOnlyMatchesEverything() = runTest {
        graph.createAsset.run(AssetCommand(name = "Circulation pump", category = "Water"))
        graph.createAsset.run("Mower", "Yard")

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.items.size == 2 }

        vm.onQueryChange(" circ ")
        val padded = vm.state.first { it.query == " circ " }
        assertEquals(listOf("Circulation pump"), padded.items.map { it.asset.name })

        vm.onQueryChange("   ")
        val whitespaceOnly = vm.state.first { it.query == "   " }
        assertEquals(2, whitespaceOnly.items.size)
    }

    /**
     * Q3 (B07 fix round 2, controller ruling) — F3: the search box's own `StateFlow` answers
     * synchronously, with no collector and no scheduler turn, the same property
     * `DashboardViewModel.query` proved before this brief moved it here.
     */
    @Test fun theBoxSeesItsOwnKeystrokeWithoutWaitingForTheList() = runTest {
        val vm = listModel()

        assertEquals("", vm.query.value)
        vm.onQueryChange("circ")
        assertEquals("circ", vm.query.value)
        vm.clearQuery()
        assertEquals("", vm.query.value)
    }

    /**
     * Q3 (B07 fix round 2, controller ruling) — F5: the query is an independent arm of the
     * `combine`, so a row arriving recomputes the list against the same query rather than the
     * keystroke triggering a re-query of its own.
     */
    @Test fun aRowArrivingDoesNotDisturbTheQuery() = runTest {
        graph.createAsset.run(AssetCommand(name = "Circulation pump", category = "Water"))

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.items.isNotEmpty() }

        vm.onQueryChange("pump")
        vm.state.first { it.query == "pump" }

        graph.createAsset.run("Pool pump", "Water")
        val after = vm.state.first { it.items.size == 2 }
        assertEquals("pump", after.query)
    }

    /**
     * Owner ruling §18.23 (B07 fix round 5, controller ruling Q4) — the archived-only hint's exact
     * condition is the view model's to decide, not the screen's: `showArchivedOnlyHint` is `true`
     * only for a non-blank query, "Show archived" off, no active row matching, at least one
     * archived row that does — self-sufficient, so the screen needs no `items.isEmpty()` check of
     * its own to be correct.
     */
    @Test fun showArchivedOnlyHintIsTrueWhenOnlyAnArchivedRowMatches() = runTest {
        val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
        graph.archiveAsset.run(tub.id)

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.archivedCount == 1 }

        vm.onQueryChange("hot")
        val state = vm.state.first { it.query == "hot" }
        assertTrue("no active row matches", state.items.isEmpty())
        assertFalse(state.showArchived)
        assertTrue(state.showArchivedOnlyHint)
    }

    /** Negative — a blank query never sets the hint; nothing was asked. */
    @Test fun showArchivedOnlyHintIsFalseUnderABlankQuery() = runTest {
        val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
        graph.archiveAsset.run(tub.id)

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.archivedCount == 1 }
        assertEquals("", state.query)
        assertFalse(state.showArchivedOnlyHint)
    }

    /**
     * Negative — an active row matching the query is the ordinary case, not the archived-only one,
     * even when an archived row matches the same query: a formula that only ever counted archived
     * matches would say `true` here and pass for the wrong reason (Q4). The fixture makes both
     * halves match "water" on purpose, so this only passes if the view model itself checks whether
     * an active row matched too.
     */
    @Test fun showArchivedOnlyHintIsFalseWhenAnActiveRowAlsoMatches() = runTest {
        graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
        val heater = graph.createAsset.run(AssetCommand(name = "Water heater", category = "Kitchen"))
        graph.archiveAsset.run(heater.id)

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.archivedCount == 1 }

        vm.onQueryChange("water")
        val state = vm.state.first { it.query == "water" }
        assertEquals(listOf("Hot tub"), state.items.map { it.asset.name })
        assertFalse(state.showArchivedOnlyHint)
    }

    /** Negative — a query that matches nothing anywhere stays "Nothing matches that.", not the hint. */
    @Test fun showArchivedOnlyHintIsFalseWhenNothingMatchesAnywhere() = runTest {
        val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
        graph.archiveAsset.run(tub.id)

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.archivedCount == 1 }

        vm.onQueryChange("zzz")
        val state = vm.state.first { it.query == "zzz" }
        assertTrue(state.items.isEmpty())
        assertFalse(state.showArchivedOnlyHint)
    }

    /**
     * Negative — once "Show archived" is on, the matching archived row is listed, not held back,
     * and the hint clears (the `|| archived` half Q4 also named).
     */
    @Test fun showArchivedOnlyHintIsFalseOnceShowArchivedIsOn() = runTest {
        val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
        graph.archiveAsset.run(tub.id)

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.archivedCount == 1 }

        vm.toggleArchived()
        vm.onQueryChange("hot")
        val state = vm.state.first { it.showArchived && it.query == "hot" }
        assertEquals(listOf("Hot tub"), state.items.map { it.asset.name })
        assertFalse(state.showArchivedOnlyHint)
    }

    // ------------------------------------------------------------------------------------------
    // 1.4 (B14) — asset detail's condition, health and season, and the list's season phase.
    // ------------------------------------------------------------------------------------------

    /** S99 and S102 through a fake: the JVM has no resources, and the day forms are B12's to prove. */
    private val plurals = object : HealthPlurals {
        override fun ageDays(n: Long) = "$n days"
        override fun daysOverdue(title: String, n: Long) = "$title is $n days overdue"
    }

    /** Every word the Health section draws, block by block, in its order. */
    private fun AssetDetailState.healthWords(): List<String> =
        healthBlocks.flatMap { it.words(plurals) { day -> day.toString() } }

    /** The detail state once it has loaded, collected in the test's background scope. */
    private suspend fun kotlinx.coroutines.test.TestScope.loaded(id: String): AssetDetailState {
        val vm = detailModel(AssetId(id))
        backgroundScope.launch { vm.state.collect() }
        return vm.state.first { it != null }!!
    }

    private fun activation(id: String, assetId: String, action: SeasonAction, on: String) = SeasonActivation(
        id = id,
        assetId = AssetId(assetId),
        action = action,
        occurredOn = on,
        eventId = null,
        createdAt = dayMillis(on),
    )

    /**
     * Spec §10.3: the plate carries the condition badge **beside** retired, archived and out of
     * season — four independent facts, all four on one asset — and an asset with none of the three
     * still carries its condition badge, S4 when nothing is recorded.
     */
    @Test fun thePlateCarriesConditionBesideRetiredArchivedAndOutOfSeason() = runTest {
        graph.today = LocalDate.parse("2026-01-15")
        graph.assets.upsert(
            assetRow(
                "mower", name = "Mower", status = AssetStatus.ARCHIVED, retiredOn = "2026-01-01",
                seasonMode = SeasonMode.CALENDAR, seasonStart = "05-01", seasonEnd = "09-30",
            ),
        )
        graph.conditions.insert(conditionRow("c1", "mower", OperationalCondition.DOWN, "2026-01-10", reason = "Belt snapped"))
        graph.assets.upsert(assetRow("gen", name = "Generator"))

        val down = ConditionView(
            condition = OperationalCondition.DOWN,
            since = LocalDate.parse("2026-01-10"),
            reason = "Belt snapped",
            occurredOn = LocalDate.parse("2026-01-10"),
            occurredTime = null,
            eventId = null,
            eventExists = false,
        )
        assertEquals(
            listOf(PlateFact.Condition(down), PlateFact.Retired, PlateFact.Archived("Archived"), PlateFact.OutOfSeason),
            loaded("mower").plate,
        )
        assertEquals("S4 on an asset with nothing recorded", listOf(PlateFact.Condition(null)), loaded("gen").plate)
    }

    /**
     * Spec §10.6 ("plate, season section") and the controller's ruling on I-3: IN SEASON is on the
     * plate wherever the Season section draws a phase word — a CALENDAR or a MANUAL asset in season —
     * and never on a YEAR_ROUND one, whose section draws S29 instead of a phase.
     */
    @Test fun thePlateCarriesInSeasonForCalendarAndManualButNotYearRound() = runTest {
        graph.today = LocalDate.parse("2026-01-15")
        graph.assets.upsert(assetRow("snow", name = "Snowblower", seasonMode = SeasonMode.CALENDAR, seasonStart = "11-01", seasonEnd = "03-31"))
        graph.assets.upsert(assetRow("tub", name = "Hot tub", seasonMode = SeasonMode.MANUAL))
        graph.seasonActivations.insert(activation("a1", "tub", SeasonAction.START, "2026-01-01"))
        graph.assets.upsert(assetRow("gen", name = "Generator"))

        assertEquals(listOf(PlateFact.Condition(null), PlateFact.InSeason), loaded("snow").plate)
        assertEquals(listOf(PlateFact.Condition(null), PlateFact.InSeason), loaded("tub").plate)
        assertEquals("no phase word on a year-round asset", listOf(PlateFact.Condition(null)), loaded("gen").plate)
    }

    /**
     * The controller's ruling on I-1 (B07's M1): "Mark operational" is offered only on an asset in
     * service — active and not retired — that is DOWN or DEGRADED. A retired or archived asset's page
     * still shows its condition; it just does not offer to change it back.
     */
    @Test fun markOperationalIsOfferedOnlyOnAnInServiceAsset() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.conditions.insert(conditionRow("c1", "gen", OperationalCondition.DOWN, "2026-02-01"))
        graph.assets.upsert(assetRow("old", name = "Old pack", retiredOn = "2026-01-01"))
        graph.conditions.insert(conditionRow("c2", "old", OperationalCondition.DOWN, "2026-02-01"))
        graph.assets.upsert(assetRow("fan", name = "Fan", status = AssetStatus.ARCHIVED))
        graph.conditions.insert(conditionRow("c3", "fan", OperationalCondition.DEGRADED, "2026-02-01"))
        graph.assets.upsert(assetRow("mower", name = "Mower"))
        graph.conditions.insert(conditionRow("c4", "mower", OperationalCondition.OPERATIONAL, "2026-02-01"))

        assertTrue("an active DOWN asset", loaded("gen").offersMarkOperational)
        assertFalse("a retired DOWN asset", loaded("old").offersMarkOperational)
        assertFalse("an archived DEGRADED asset", loaded("fan").offersMarkOperational)
        assertFalse("an OPERATIONAL asset", loaded("mower").offersMarkOperational)
        assertEquals("the retired asset still shows its condition", OperationalCondition.DOWN, loaded("old").condition?.condition)
    }

    /**
     * Brief: "a new condition redraws the page without a manual refresh" — a grandchild's DOWN, a
     * direct child's new condition and a health subject, each written **after** the page loaded.
     */
    @Test fun theDetailRedrawsWhenADescendantsConditionOrASubjectChangesAfterLoad() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.assets.upsert(assetRow("eng", name = "Engine", parent = "gen"))
        graph.assets.upsert(assetRow("pack", name = "Battery pack", parent = "eng"))
        val vm = detailModel(AssetId("gen"))
        backgroundScope.launch { vm.state.collect() }
        assertEquals(listOf(NOT_TRACKED, HEALTH_FOOTER), vm.state.first { it != null }!!.healthWords())

        graph.conditions.insert(conditionRow("c1", "pack", OperationalCondition.DOWN, "2026-02-01", reason = "Won't hold charge"))
        scheduler.advanceUntilIdle()
        assertEquals(
            "a grandchild's DOWN, recorded after load",
            listOf("Battery pack DOWN — Won't hold charge", NOT_TRACKED, HEALTH_FOOTER),
            vm.state.value!!.healthWords(),
        )

        graph.conditions.insert(conditionRow("c2", "eng", OperationalCondition.DEGRADED, "2026-02-02"))
        scheduler.advanceUntilIdle()
        assertEquals("the child's own badge", OperationalCondition.DEGRADED, vm.state.value!!.components.single().condition?.condition)

        graph.healthSubjects.upsert(subjectRow("h1", "gen", name = "Belt age"))
        scheduler.advanceUntilIdle()
        assertTrue("a subject added after load", vm.state.value!!.healthWords().contains("Belt age"))
    }

    /** Counts the season reads: every rebuild of the detail state reads the season exactly once. */
    private class CountingActivations(private val inner: SeasonActivationRepository) : SeasonActivationRepository by inner {
        var reads = 0
        override suspend fun forAsset(assetId: AssetId): List<SeasonActivation> {
            reads++
            return inner.forAsset(assetId)
        }
    }

    /**
     * Review M-1: one edit of the asset row rebuilds the detail state **once** — the condition
     * watchers are not torn down and re-subscribed when the asset tree has not changed.
     */
    @Test fun anAssetEditRebuildsTheDetailOnce() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.assets.upsert(assetRow("eng", name = "Engine", parent = "gen"))
        val reads = CountingActivations(graph.seasonActivations)
        val vm = AssetDetailViewModel(
            graph.assets, graph.tags,
            graph.definitions, graph.profiles, graph.events,
            graph.schedules, graph.scheduleStates, graph.groups, graph.dueReadModel,
            graph.conditions, graph.seasonActivations, graph.healthSubjects,
            graph.assetHealthReadModel, GetAssetSeason(graph.assets, reads, graph.uow, graph.todayPort),
            graph.recordSeasonActivation,
            graph.archiveAsset, graph.retireAsset, graph.deleteAsset,
            graph.applyTemplate, graph.uow, graph.clock, graph.todayPort, AssetId("gen"),
        )
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it != null }
        scheduler.advanceUntilIdle()
        reads.reads = 0

        graph.assets.upsert(graph.assets.get(AssetId("gen"))!!.copy(name = "Generator set"))
        scheduler.advanceUntilIdle()

        assertEquals("Generator set", vm.state.value!!.asset.name)
        assertEquals("one edit, one rebuild", 1, reads.reads)
    }

    /**
     * Spec §3.1: the out-of-season mark is the **season phase**, on the list and on the detail —
     * a MANUAL asset after an END reads out of season, one with no row at all does too, a START
     * brings it back in, and the list redraws on the activation alone (inv. 126: no asset column).
     */
    @Test fun outOfSeasonFollowsThePhaseIncludingManual() = runTest {
        graph.today = LocalDate.parse("2026-06-15")
        graph.assets.upsert(assetRow("tub", name = "Hot tub", seasonMode = SeasonMode.MANUAL))
        graph.seasonActivations.insert(activation("a1", "tub", SeasonAction.START, "2026-04-01"))
        graph.seasonActivations.insert(activation("a2", "tub", SeasonAction.END, "2026-06-01"))
        graph.assets.upsert(assetRow("bare", name = "Imported tub", seasonMode = SeasonMode.MANUAL))
        graph.assets.upsert(assetRow("mower", name = "Mower", seasonMode = SeasonMode.CALENDAR, seasonStart = "05-01", seasonEnd = "09-30"))
        graph.assets.upsert(assetRow("snow", name = "Snowblower", seasonMode = SeasonMode.CALENDAR, seasonStart = "11-01", seasonEnd = "03-31"))
        graph.assets.upsert(assetRow("gen", name = "Generator"))

        val list = listModel()
        backgroundScope.launch { list.state.collect() }
        val marks = list.state.first { it.items.size == 5 }.items.associate { it.asset.name to it.outOfSeason }
        assertEquals(
            mapOf("Generator" to false, "Hot tub" to true, "Imported tub" to true, "Mower" to false, "Snowblower" to true),
            marks,
        )

        val tub = loaded("tub")
        assertTrue("the detail reads the same phase", tub.outOfSeason)
        assertTrue(PlateFact.OutOfSeason in tub.plate)
        val bare = loaded("bare")
        assertTrue("no row at all reads out of season", bare.outOfSeason)
        assertEquals(SeasonAction.START, manualAction(bare.season))
        assertEquals("an empty history", emptyList<SeasonActivation>(), seasonHistory(bare.season))

        graph.recordSeasonActivation.run(AssetId("tub"), ActivationCommand(SeasonAction.START))
        val back = list.state.first { state -> state.items.single { it.asset.name == "Hot tub" }.outOfSeason.not() }
        assertFalse(back.items.single { it.asset.name == "Hot tub" }.outOfSeason)
        assertFalse(loaded("tub").outOfSeason)
    }

    /**
     * Spec §5.1, §5.3; inv. 107, 110: S21 lists **every** row, newest first by the ordering key
     * reversed — a backdated correction sorted into place, a return to OPERATIONAL as one more row —
     * and a row whose linked event is gone keeps every field and is marked for S24, never hidden. A
     * restored row whose zone this device cannot resolve is drawn like any other (B06 carry-forward).
     */
    @Test fun historyIsEveryRowNewestFirstWithADanglingLinkAsS24() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.events.upsert(replacementOf("e-kept", "gen", "2026-03-01"))
        graph.conditions.insert(conditionRow("c1", "gen", OperationalCondition.DEGRADED, "2026-02-20"))
        graph.conditions.insert(conditionRow("c2", "gen", OperationalCondition.DOWN, "2026-03-01", reason = "Won't start", eventId = "e-kept"))
        graph.conditions.insert(
            conditionRow("c3", "gen", OperationalCondition.DOWN, "2026-03-05", reason = "Still won't start", occurredTime = "08:30", eventId = "e-gone"),
        )
        graph.conditions.insert(
            conditionRow("c4", "gen", OperationalCondition.OPERATIONAL, "2026-03-06", tzId = "Mars/Olympus_Mons"),
        )
        // Recorded last, dated between the first two: it sorts into place by its date.
        graph.conditions.insert(
            conditionRow("c5", "gen", OperationalCondition.DEGRADED, "2026-02-25", reason = "Rough idle", createdAt = dayMillis("2026-03-07")),
        )

        val history = loaded("gen").conditionHistory

        assertEquals(listOf("c4", "c3", "c2", "c5", "c1"), history.map { it.id })
        val dangling = history.single { it.id == "c3" }
        assertEquals(
            ConditionHistoryRow(
                id = "c3", condition = OperationalCondition.DOWN, occurredOn = "2026-03-05", occurredTime = "08:30",
                reason = "Still won't start", eventId = EventId("e-gone"), eventExists = false, eventTitle = null,
            ),
            dangling,
        )
        assertEquals("only the dangling link reads S24", listOf("c3"), history.filter { it.linkRemoved }.map { it.id })
        val linked = history.single { it.id == "c2" }
        assertTrue(linked.eventExists)
        assertEquals("Battery replaced", linked.eventTitle)
        assertEquals("the unresolvable zone's row is drawn", OperationalCondition.OPERATIONAL, history.first().condition)
        assertEquals("S23 for an empty reason", "No reason given", com.loosecannon.servicetag.ui.condition.reasonLine(history.last().reason))
        assertEquals("The linked record was removed.", LINKED_RECORD_REMOVED)
    }

    /**
     * Inv. 119, spec §6.5: an AVERAGE that reads NOMINAL still leads its Health section with its
     * CRITICAL subject (S109), before the aggregate (S108), then every subject, then S107.
     */
    @Test fun anAverageNominalAssetShowsItsCriticalSubjectFirst() = runTest {
        graph.today = LocalDate.parse("2026-04-15")
        graph.assets.upsert(assetRow("ups", name = "UPS", aggregation = HealthAggregation.AVERAGE))
        graph.events.upsert(replacementOf("e1", "ups", "2026-01-15"))
        graph.healthSubjects.upsert(subjectRow("h1", "ups", name = "Battery age", sortOrder = 0))
        graph.healthSubjects.upsert(subjectRow("h2", "ups", name = "Fan age", nominalUntilDays = 1000, warningFromDays = 2000, criticalFromDays = 3000, sortOrder = 1))
        graph.healthSubjects.upsert(subjectRow("h3", "ups", name = "Case age", nominalUntilDays = 1000, warningFromDays = 2000, criticalFromDays = 3000, sortOrder = 2))

        val state = loaded("ups")
        val blocks = state.healthBlocks

        val critical = blocks.first() as HealthBlock.Critical
        assertEquals("Battery age", critical.subject.subject.name)
        val aggregate = blocks[1] as HealthBlock.Aggregate
        assertEquals(HealthBand.NOMINAL, aggregate.value?.band)
        assertEquals(
            listOf("Battery age", "Fan age", "Case age"),
            blocks.filterIsInstance<HealthBlock.Subject>().map { it.health.subject.name },
        )
        assertEquals(HealthBlock.Footer, blocks.last())
        val words = state.healthWords()
        assertTrue(words.first().startsWith("Critical: Battery age "))
        assertEquals("S107 closes the section", HEALTH_FOOTER, words.last())
        assertTrue("the replacement's age is S99", words.contains("Replaced 2026-01-15, 90 days ago"))
    }

    /**
     * Inv. 119, spec §6.5 "at any depth": a DOWN grandchild and a DEGRADED child come **first**, DOWN
     * before DEGRADED, before the aggregate — here NOT TRACKED, as the asset has no subject. Each
     * component row also carries its own condition badge.
     */
    @Test fun downComponentsAtAnyDepthComeFirst() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.assets.upsert(assetRow("eng", name = "Engine", parent = "gen"))
        graph.conditions.insert(conditionRow("c1", "eng", OperationalCondition.OPERATIONAL, "2026-01-01"))
        graph.assets.upsert(assetRow("pack", name = "Battery pack", parent = "eng"))
        graph.conditions.insert(conditionRow("c2", "pack", OperationalCondition.DOWN, "2026-01-02", reason = "Won't hold charge"))
        graph.assets.upsert(assetRow("fan", name = "Fan", parent = "gen"))
        graph.conditions.insert(conditionRow("c3", "fan", OperationalCondition.DEGRADED, "2026-01-03"))

        val state = loaded("gen")

        assertEquals(
            listOf("Battery pack DOWN — Won't hold charge", "Fan DEGRADED — No reason given", NOT_TRACKED, HEALTH_FOOTER),
            state.healthWords(),
        )
        assertEquals(
            mapOf("Engine" to OperationalCondition.OPERATIONAL, "Fan" to OperationalCondition.DEGRADED),
            state.components.associate { it.name to it.condition?.condition },
        )
        assertEquals("the grandchild is on its parent's page too", listOf("Battery pack DOWN — Won't hold charge", NOT_TRACKED, HEALTH_FOOTER), loaded("eng").healthWords())
    }

    /**
     * Inv. 118: a subject with no value and an aggregate with no contributor are S98, never a number
     * — least of all 100. Every subject archived leaves S98 and S107 and no subject line.
     */
    @Test fun untrackedSubjectsAndAggregatesAreS98() = runTest {
        graph.assets.upsert(assetRow("ups", name = "UPS"))
        graph.healthSubjects.upsert(subjectRow("h1", "ups", name = "Battery age"))
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.healthSubjects.upsert(subjectRow("h2", "gen", name = "Belt age", archivedAt = 5L))

        assertEquals(
            listOf(NOT_TRACKED, "Battery age", NOT_TRACKED, "No replacement recorded yet", HEALTH_FOOTER),
            loaded("ups").healthWords(),
        )
        assertEquals(listOf(NOT_TRACKED, HEALTH_FOOTER), loaded("gen").healthWords())
    }

    /**
     * Spec §6.5: a TRACK_ONE whose subject is gone falls back to WORST and says so with S138 under
     * the aggregate — and only then: with nothing to score there is no worst subject to show, so the
     * aggregate is S98 and S138 is absent, as it is on an ordinary WORST asset.
     */
    @Test fun theFallbackShowsS138() = runTest {
        graph.today = LocalDate.parse("2026-04-15")
        graph.assets.upsert(assetRow("ups", name = "UPS", aggregation = HealthAggregation.TRACK_ONE, primary = "h-gone"))
        graph.events.upsert(replacementOf("e1", "ups", "2026-01-15"))
        graph.healthSubjects.upsert(subjectRow("h1", "ups", name = "Battery age"))
        graph.assets.upsert(assetRow("gen", name = "Generator", aggregation = HealthAggregation.TRACK_ONE, primary = "h-gone"))
        graph.healthSubjects.upsert(subjectRow("h2", "gen", name = "Belt age"))
        graph.assets.upsert(assetRow("fan", name = "Fan"))
        graph.events.upsert(replacementOf("e2", "fan", "2026-01-15"))
        graph.healthSubjects.upsert(subjectRow("h3", "fan", name = "Blade age"))

        val fellBack = loaded("ups").healthBlocks
        assertEquals(HealthBlock.Fallback, fellBack[fellBack.indexOfFirst { it is HealthBlock.Aggregate } + 1])
        assertTrue(loaded("ups").healthWords().contains(FALLBACK_TO_WORST))

        val nothingToShow = loaded("gen").healthWords()
        assertEquals(listOf(NOT_TRACKED, "Belt age", NOT_TRACKED, "No replacement recorded yet", HEALTH_FOOTER), nothingToShow)
        assertFalse(loaded("fan").healthWords().contains(FALLBACK_TO_WORST))
    }

    // ---------------------------------------------------------------------------------------------
    // #78: the one question a save asks when an asset becomes seasonal (plan §2, §8 C1–C6)
    // ---------------------------------------------------------------------------------------------

    /** What one editor did after one Save: the question it raised, and where it said to go. */
    private class EditorAnswer(val model: AssetEditViewModel) {
        val saved = mutableListOf<AssetId>()
        val review = mutableListOf<AssetId>()
    }

    /**
     * An editor on [id], its two one-shot signals collected eagerly — they have no replay, so a
     * collector that subscribes after the emission would record nothing.
     */
    private suspend fun TestScope.editorOn(id: AssetId): EditorAnswer {
        val answer = EditorAnswer(editModel(id))
        val eager = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(eager) { answer.model.saved.collect { answer.saved += it } }
        backgroundScope.launch(eager) { answer.model.review.collect { answer.review += it } }
        return answer
    }

    /** Chooses [mode] the way the screen does — a window under S30, S35 answered on a switch into S31 — and saves. */
    private suspend fun AssetEditViewModel.saveAs(mode: SeasonMode) {
        onSeasonMode(mode)
        if (mode == SeasonMode.CALENDAR) {
            onSeasonStart("05-01")
            onSeasonEnd("09-30")
        }
        if (state.value.asksManualPhase) onManualPhase(SeasonPhase.OUT_OF_SEASON)
        save()
        state.first { !it.saving }
    }

    /** A weekly schedule on [assetId] with [policy]'s own offset rule, so every row is one the API would accept. */
    private fun weekly(id: String, assetId: String, policy: ServicePolicy, status: ScheduleStatus = ScheduleStatus.ACTIVE) =
        scheduleOf(
            id,
            assetId = assetId,
            title = "Check $id",
            timeInterval = 1,
            timeUnit = RecurrenceUnit.WEEK,
            leadDays = 1,
            servicePolicy = policy,
            policyOffsetDays = when (policy) {
                ServicePolicy.IN_SERVICE_AT_START -> 0
                ServicePolicy.PRE_SERVICE -> -7
                else -> null
            },
            status = status,
        )

    /**
     * One row of the trigger table: an existing asset stored in [from] with [rows] as its schedules,
     * saved into [to] (renamed, so a save that keeps the season is still a real write). Returns the
     * editor after the save has settled.
     */
    private suspend fun TestScope.trigger(
        assetId: String,
        from: SeasonMode,
        to: SeasonMode,
        vararg rows: MaintenanceSchedule,
    ): EditorAnswer {
        graph.assets.upsert(
            if (from == SeasonMode.CALENDAR) {
                assetRow(assetId, seasonMode = from, seasonStart = "05-01", seasonEnd = "09-30")
            } else {
                assetRow(assetId, seasonMode = from)
            },
        )
        rows.forEach { graph.schedules.upsert(it) }
        val editor = editorOn(AssetId(assetId))
        editor.model.onName("$assetId renamed")
        editor.model.saveAs(to)
        return editor
    }

    /**
     * C1 and R-1: only YEAR_ROUND → CALENDAR or MANUAL asks, and only about live CONTINUOUS rows —
     * ACTIVE and PAUSED counted, ARCHIVED, IN_SERVICE and PRE_SERVICE not. A save that asks holds
     * `saved` back; every save that does not ask finishes as it always did; a refused save asks nothing.
     */
    @Test fun theSeasonPromptTriggerTable() = runTest {
        val asked = mapOf(
            "active" to trigger(
                "active", SeasonMode.YEAR_ROUND, SeasonMode.MANUAL,
                weekly("a1", "active", ServicePolicy.CONTINUOUS),
            ),
            "paused" to trigger(
                "paused", SeasonMode.YEAR_ROUND, SeasonMode.MANUAL,
                weekly("p1", "paused", ServicePolicy.CONTINUOUS, ScheduleStatus.PAUSED),
            ),
            "calendar" to trigger(
                "calendar", SeasonMode.YEAR_ROUND, SeasonMode.CALENDAR,
                weekly("c1", "calendar", ServicePolicy.CONTINUOUS),
            ),
            "mixed" to trigger(
                "mixed", SeasonMode.YEAR_ROUND, SeasonMode.MANUAL,
                weekly("m1", "mixed", ServicePolicy.CONTINUOUS),
                weekly("m2", "mixed", ServicePolicy.CONTINUOUS, ScheduleStatus.PAUSED),
                weekly("m3", "mixed", ServicePolicy.IN_SERVICE_AT_START),
            ),
        )
        assertEquals(
            mapOf(
                "active" to EditPrompt.ReconcileSchedules(1),
                "paused" to EditPrompt.ReconcileSchedules(1),
                "calendar" to EditPrompt.ReconcileSchedules(1),
                "mixed" to EditPrompt.ReconcileSchedules(2),
            ),
            asked.mapValues { it.value.model.prompt.value },
        )
        asked.forEach { (case, editor) ->
            assertEquals("$case: the save is written before the question", "$case renamed", graph.assets.get(AssetId(case))!!.name)
            assertTrue("$case: the editor waits for the answer", editor.saved.isEmpty() && editor.review.isEmpty())
        }

        val notAsked = mapOf(
            "calendar-to-manual" to trigger(
                "calendar-to-manual", SeasonMode.CALENDAR, SeasonMode.MANUAL,
                weekly("cm1", "calendar-to-manual", ServicePolicy.CONTINUOUS),
            ),
            "manual-stays" to trigger(
                "manual-stays", SeasonMode.MANUAL, SeasonMode.MANUAL,
                weekly("mm1", "manual-stays", ServicePolicy.CONTINUOUS),
            ),
            "tied-only" to trigger(
                "tied-only", SeasonMode.YEAR_ROUND, SeasonMode.MANUAL,
                weekly("t1", "tied-only", ServicePolicy.IN_SERVICE_AT_START),
                weekly("t2", "tied-only", ServicePolicy.IN_SERVICE_RESUME_CLAMPED),
                weekly("t3", "tied-only", ServicePolicy.PRE_SERVICE),
            ),
            "archived-only" to trigger(
                "archived-only", SeasonMode.YEAR_ROUND, SeasonMode.MANUAL,
                weekly("x1", "archived-only", ServicePolicy.CONTINUOUS, ScheduleStatus.ARCHIVED),
            ),
            "year-round-stays" to trigger(
                "year-round-stays", SeasonMode.YEAR_ROUND, SeasonMode.YEAR_ROUND,
                weekly("y1", "year-round-stays", ServicePolicy.CONTINUOUS),
            ),
        )
        notAsked.forEach { (case, editor) ->
            assertEquals("$case asks nothing", null, editor.model.prompt.value)
            assertEquals("$case finishes as it always did", listOf(AssetId(case)), editor.saved)
            assertEquals("$case: the save was written", "$case renamed", graph.assets.get(AssetId(case))!!.name)
        }

        // A refused save: S55, because the break's pre-service work would be stranded by the calendar.
        graph.assets.upsert(assetRow("refused", breakStart = "07-01", breakEnd = "07-15"))
        graph.schedules.upsert(weekly("r1", "refused", ServicePolicy.CONTINUOUS))
        graph.schedules.upsert(weekly("r2", "refused", ServicePolicy.PRE_SERVICE))
        val refused = editorOn(AssetId("refused"))
        refused.model.saveAs(SeasonMode.CALENDAR)
        assertTrue("the save was refused", refused.model.state.value.seasonRefusal != null)
        assertEquals(SeasonMode.YEAR_ROUND, graph.assets.get(AssetId("refused"))!!.seasonMode)
        assertEquals("a refused save asks nothing", null, refused.model.prompt.value)
        assertTrue(refused.saved.isEmpty() && refused.review.isEmpty())

        // A new asset has no schedules: made seasonal on its first save, it finishes as it always did.
        val created = EditorAnswer(editModel())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { created.model.saved.collect { created.saved += it } }
        created.model.onName("Generator")
        created.model.saveAs(SeasonMode.MANUAL)
        assertEquals(null, created.model.prompt.value)
        assertEquals(1, created.saved.size)
    }

    /** C2: "Keep schedules as-is" finishes the editor through `saved`, never `review`, and the question goes. */
    @Test fun keepSchedulesFinishesTheEditor() = runTest {
        val editor = trigger("gen", SeasonMode.YEAR_ROUND, SeasonMode.MANUAL, weekly("s1", "gen", ServicePolicy.CONTINUOUS))
        assertEquals(EditPrompt.ReconcileSchedules(1), editor.model.prompt.value)
        assertTrue(editor.saved.isEmpty())

        editor.model.keepSchedules()
        advanceUntilIdle()
        assertEquals(null, editor.model.prompt.value)
        assertEquals(listOf(AssetId("gen")), editor.saved)
        assertTrue("no navigation hint", editor.review.isEmpty())

        // The question is gone, so a second answer (a back gesture racing the button) does nothing.
        editor.model.keepSchedules()
        editor.model.reviewSchedules()
        advanceUntilIdle()
        assertEquals(listOf(AssetId("gen")), editor.saved)
        assertTrue(editor.review.isEmpty())
    }

    /** C2: "Review maintenance schedules" finishes the editor through `review`, never `saved`. */
    @Test fun reviewSchedulesOpensTheSchedules() = runTest {
        val editor = trigger("gen", SeasonMode.YEAR_ROUND, SeasonMode.CALENDAR, weekly("s1", "gen", ServicePolicy.CONTINUOUS))
        assertEquals(EditPrompt.ReconcileSchedules(1), editor.model.prompt.value)

        editor.model.reviewSchedules()
        advanceUntilIdle()
        assertEquals(null, editor.model.prompt.value)
        assertEquals(listOf(AssetId("gen")), editor.review)
        assertTrue("the editor is not finished twice", editor.saved.isEmpty())

        editor.model.reviewSchedules()
        editor.model.keepSchedules()
        advanceUntilIdle()
        assertEquals(listOf(AssetId("gen")), editor.review)
        assertTrue(editor.saved.isEmpty())
    }

    /**
     * C5 (AC 7, AC 8): the question and both answers write nothing. Every schedule row — its policy,
     * status, profile, `[LOCAL]` provider row and `updatedAt` — is what it was before the save, and the
     * event, closure and activation counts do not move from the moment the question is asked.
     */
    @Test fun theSeasonPromptWritesNothing() = runTest {
        val gen = graph.createAsset.run("Generator", "Power", templateKey = "power_equipment").id
        val profile = graph.profiles.forAsset(gen).first()
        graph.schedules.upsert(
            weekly("s-form", gen.value, ServicePolicy.CONTINUOUS)
                .copy(completionMode = CompletionMode.FORM, profileId = profile.id, updatedAt = 4_242L),
        )
        graph.schedules.upsert(weekly("s-paused", gen.value, ServicePolicy.CONTINUOUS, ScheduleStatus.PAUSED))
        graph.schedules.upsert(weekly("s-tied", gen.value, ServicePolicy.IN_SERVICE_AT_START))
        val before = graph.schedules.forAsset(gen).sortedBy { it.id.value }
        assertTrue(before.all { row -> row.providers.any { it.provider == "LOCAL" } })

        suspend fun counts() = listOf(graph.events.all().size, graph.closures.all().size, graph.seasonActivations.all().size)

        // Keep schedules as-is, on a switch into MANUAL.
        val keep = editorOn(gen)
        keep.model.saveAs(SeasonMode.MANUAL)
        assertEquals(EditPrompt.ReconcileSchedules(2), keep.model.prompt.value)
        val asked = counts()
        keep.model.keepSchedules()
        advanceUntilIdle()
        assertEquals(before, graph.schedules.forAsset(gen).sortedBy { it.id.value })
        assertEquals(asked, counts())

        // Back to year-round (which asks nothing), then Review, on a switch into a calendar season.
        editorOn(gen).model.saveAs(SeasonMode.YEAR_ROUND)
        val review = editorOn(gen)
        review.model.saveAs(SeasonMode.CALENDAR)
        assertEquals(EditPrompt.ReconcileSchedules(2), review.model.prompt.value)
        val askedAgain = counts()
        review.model.reviewSchedules()
        advanceUntilIdle()
        assertEquals(before, graph.schedules.forAsset(gen).sortedBy { it.id.value })
        assertEquals(askedAgain, counts())
    }

    /** C6: the ratified words (plan §5), verbatim — P78-1b for one schedule, P78-1a with its count otherwise. */
    @Test fun theSeasonPromptWordsAreTheRatifiedOnes() {
        assertEquals(
            "This asset has 1 maintenance schedule that is not tied to its operating season. When active, it can " +
                "become or remain due while the asset is out of season unless you change when that maintenance " +
                "should be done.",
            notTiedToSeason(1),
        )
        assertEquals(
            "This asset has 3 maintenance schedules that are not tied to its operating season. When active, they " +
                "can become or remain due while the asset is out of season unless you change when that " +
                "maintenance should be done.",
            notTiedToSeason(3),
        )
        assertEquals(notTiedToSeason(1), NOT_TIED_TO_SEASON_ONE)
        assertEquals(NOT_TIED_TO_SEASON.replace("<n>", "2"), notTiedToSeason(2))
        assertEquals("Review maintenance schedules", REVIEW_MAINTENANCE_SCHEDULES)
        assertEquals("Keep schedules as-is", KEEP_SCHEDULES_AS_IS)
    }
}
