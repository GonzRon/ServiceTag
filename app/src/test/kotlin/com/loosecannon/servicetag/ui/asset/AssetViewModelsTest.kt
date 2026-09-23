package com.loosecannon.servicetag.ui.asset

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

    /** The detail model takes seventeen collaborators; every test wants the same seventeen off the graph. */
    private fun detailModel(id: AssetId) = AssetDetailViewModel(
        graph.assets, graph.tags,
        graph.definitions, graph.profiles, graph.events,
        graph.schedules, graph.scheduleStates, graph.groups, graph.dueReadModel,
        graph.archiveAsset, graph.retireAsset, graph.deleteAsset,
        graph.applyTemplate, graph.uow, graph.clock, id,
    )

    /**
     * Create ([id] null) or edit one asset; [parentId] is the "+ Add component" preset. Suspends
     * until the form has read the picker, which is also when a stored row has been filled in —
     * a ViewModel left mid-load outlives the test that made it and then meets a closed database.
     */
    private suspend fun editModel(id: AssetId? = null, parentId: String? = null): AssetEditViewModel {
        val model = AssetEditViewModel(graph.assets, graph.createAsset, graph.updateAsset, id, parentId)
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
        val vm = AssetsViewModel(graph.assets, graph.clock)
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

    /** Year-round is the absence of a window (spec §6), so the switch is what clears both dates. */
    @Test fun seasonYearRoundClearsBoth() = runTest {
        val vm = editModel()
        vm.onName("Snowblower")
        assertTrue(vm.state.value.seasonYearRound)

        vm.onSeasonYearRound(false)
        vm.onSeasonStart("11-01")
        vm.onSeasonEnd("03-31")
        vm.save()
        vm.state.first { !it.saving }

        val stored = graph.assets.all().single()
        assertEquals("11-01", stored.seasonStartMmdd)
        assertEquals("03-31", stored.seasonEndMmdd)

        val edit = editModel(stored.id)
        val loaded = edit.state.first { it.name == "Snowblower" }
        assertFalse(loaded.seasonYearRound)
        assertEquals("11-01", loaded.seasonStart)

        edit.onSeasonYearRound(true)
        assertEquals("", edit.state.value.seasonStart)
        assertEquals("", edit.state.value.seasonEnd)
        edit.save()
        edit.state.first { !it.saving }

        val cleared = graph.assets.all().single()
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
        vm.onSeasonYearRound(false)
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
        vm.onSeasonYearRound(false)
        vm.onSeasonStart("13-40")
        vm.onSeasonEnd("02-30")
        vm.save()

        val marked = vm.state.first { !it.saving }.problems
        assertEquals("Give the asset a name", marked[AssetField.NAME])
        assertEquals("Enter a date as YYYY-MM-DD", marked[AssetField.PURCHASE_ON])
        assertEquals("Enter a date as YYYY-MM-DD", marked[AssetField.IN_SERVICE_ON])
        assertEquals("Enter a date as YYYY-MM-DD", marked[AssetField.WARRANTY_EXPIRES_ON])
        assertEquals("Not a real month and day", marked[AssetField.SEASON_START])
        assertEquals("Not a real month and day", marked[AssetField.SEASON_END])
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

        // Both-or-neither is about the pair, so it lands on the pair and not on either date.
        val season = editModel()
        season.onName("Mower")
        season.onSeasonYearRound(false)
        season.onSeasonStart("05-01")
        season.save()
        val pair = season.state.first { !it.saving }.problems
        assertEquals("Set both season dates or neither", pair[AssetField.SEASON])
        assertEquals(null, pair[AssetField.SEASON_START])
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
     * OUT OF SEASON is read off the clock, not stored (spec §6). The same asset is out of season in
     * January and in it in June, and a window that wraps the year says the opposite of both.
     */
    @Test fun outOfSeasonComputedFromClock() = runTest {
        val mower = graph.createAsset.run("Mower", "Yard")
        graph.updateAsset.run(
            mower.id,
            AssetCommand(name = "Mower", seasonStartMmdd = "05-01", seasonEndMmdd = "09-30"),
        )

        graph.now = millisOn("2026-01-15")
        val winter = detailModel(mower.id)
        backgroundScope.launch { winter.state.collect() }
        assertTrue(winter.state.first { it != null }!!.outOfSeason)

        graph.now = millisOn("2026-06-15")
        val summer = detailModel(mower.id)
        backgroundScope.launch { summer.state.collect() }
        assertFalse(summer.state.first { it != null }!!.outOfSeason)

        // A window whose start is after its end wraps the year, inclusively (spec §6).
        graph.updateAsset.run(
            mower.id,
            AssetCommand(name = "Mower", seasonStartMmdd = "11-01", seasonEndMmdd = "02-28"),
        )
        graph.now = millisOn("2026-01-15")
        val wrappedWinter = detailModel(mower.id)
        backgroundScope.launch { wrappedWinter.state.collect() }
        assertFalse(wrappedWinter.state.first { it != null }!!.outOfSeason)

        graph.now = millisOn("2026-06-15")
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

        val vm = AssetsViewModel(graph.assets, graph.clock)
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
        graph.now = millisOn("2026-01-15")

        val vm = AssetsViewModel(graph.assets, graph.clock)
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

        val vm = AssetsViewModel(graph.assets, graph.clock)
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

        val vm = AssetsViewModel(graph.assets, graph.clock)
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.items.size == 2 }
        assertEquals("", state.query)
        assertEquals(listOf("apple press", "Zebra mower"), state.items.map { it.asset.name })
    }

    /** B07 — a name match narrows the list to the asset it names. */
    @Test fun aNameMatchNarrowsTheList() = runTest {
        graph.createAsset.run("Circulation pump", "Water")
        graph.createAsset.run("Mower", "Yard")

        val vm = AssetsViewModel(graph.assets, graph.clock)
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

        val vm = AssetsViewModel(graph.assets, graph.clock)
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

        val vm = AssetsViewModel(graph.assets, graph.clock)
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

        val vm = AssetsViewModel(graph.assets, graph.clock)
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

        val vm = AssetsViewModel(graph.assets, graph.clock)
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
        val vm = AssetsViewModel(graph.assets, graph.clock)

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

        val vm = AssetsViewModel(graph.assets, graph.clock)
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

        val vm = AssetsViewModel(graph.assets, graph.clock)
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

        val vm = AssetsViewModel(graph.assets, graph.clock)
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

        val vm = AssetsViewModel(graph.assets, graph.clock)
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

        val vm = AssetsViewModel(graph.assets, graph.clock)
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

        val vm = AssetsViewModel(graph.assets, graph.clock)
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.archivedCount == 1 }

        vm.toggleArchived()
        vm.onQueryChange("hot")
        val state = vm.state.first { it.showArchived && it.query == "hot" }
        assertEquals(listOf("Hot tub"), state.items.map { it.asset.name })
        assertFalse(state.showArchivedOnlyHint)
    }
}
