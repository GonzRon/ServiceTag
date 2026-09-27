package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.ports.ScheduleRepository
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
import com.loosecannon.servicetag.core.health.AssetHealthResult
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.ui.health.AssetHealthReadModel
import com.loosecannon.servicetag.ui.health.AssetHealthView
import com.loosecannon.servicetag.ui.health.ConditionView
import com.loosecannon.servicetag.ui.health.HealthPlurals
import com.loosecannon.servicetag.ui.health.NOT_TRACKED
import com.loosecannon.servicetag.core.journal.CategoryCatalog
import com.loosecannon.servicetag.core.journal.CategoryChoice
import com.loosecannon.servicetag.core.journal.RangeState
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCategory
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
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.health.SubjectValue
import com.loosecannon.servicetag.core.model.isRetired
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.FakeAttachmentStorage
import com.loosecannon.servicetag.testing.InMemoryAttachmentStore
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.AttachmentStore
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.StoreIoException
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.ports.StoredBytes
import com.loosecannon.servicetag.core.usecase.AddAttachment
import com.loosecannon.servicetag.core.usecase.AddAttachmentCommand
import com.loosecannon.servicetag.core.usecase.SaveAssetSettings
import com.loosecannon.servicetag.ui.attachments.PickedFile
import kotlinx.coroutines.CompletableDeferred
import java.io.ByteArrayInputStream
import java.io.InputStream
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
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

    /** The detail model takes twenty-four collaborators; every test wants the same ones off the graph. */
    private fun detailModel(id: AssetId) = AssetDetailViewModel(
        graph.assets, graph.tags,
        graph.definitions, graph.profiles, graph.events,
        graph.schedules, graph.scheduleStates, graph.groups, graph.dueReadModel,
        graph.conditions, graph.seasonActivations, graph.healthSubjects, graph.attachments,
        graph.assetHealthReadModel, graph.getAssetSeason, graph.recordSeasonActivation,
        graph.archiveAsset, graph.retireAsset, graph.deleteAsset,
        graph.applyTemplate, graph.uow, graph.clock, graph.todayPort, id,
    )

    /** The list, reading the season phase on the graph's injected `T` and the catalog off the graph. */
    private fun listModel() = AssetsViewModel(
        graph.assets, graph.categories, graph.seasonActivations, graph.tags, graph.assetHealthReadModel, graph.todayPort,
    )

    /**
     * Create ([id] null) or edit one asset; [parentId] is the "+ Add component" preset. Suspends
     * until the form has read the picker, which is also when a stored row has been filled in —
     * a ViewModel left mid-load outlives the test that made it and then meets a closed database.
     */
    private suspend fun editModel(id: AssetId? = null, parentId: String? = null): AssetEditViewModel {
        val model = AssetEditViewModel(
            graph.assets, graph.healthSubjects, graph.saveAssetSettings, graph.schedules, graph.categories,
            graph.attachments, graph.attachmentStorage, graph.addAttachment, graph.todayPort, id, parentId,
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

    /**
     * #74 AC 2, first half: typing a category nobody has saved writes nothing. The editor has no
     * catalog write of its own; leaving it without a save — a new asset or an edit — never reaches the
     * promotion, so the catalog and the stored row are as they were.
     */
    @Test fun cancellingAfterTypingACategoryAddsNothing() = runTest {
        val vm = editModel()
        vm.onName("Compressor")
        vm.onCategory("Appliance")
        vm.onCategory("Large appliance")
        // Anything the typing started has run to the end before the catalog is read.
        advanceUntilIdle()
        assertTrue(graph.categories.all().isEmpty())
        assertTrue(graph.assets.all().isEmpty())

        val stored = graph.createAsset.run(AssetCommand(name = "Blower", category = "Pump"))
        val edit = editModel(stored.id)
        edit.state.first { it.name == "Blower" }
        edit.onCategory("Appliance")
        advanceUntilIdle()
        assertTrue(graph.categories.all().isEmpty())
        assertEquals("Pump", graph.assets.get(stored.id)!!.category)
    }

    /**
     * #74 AC 2, second half: a refused save adds no category. The same form, once it saves, promotes
     * the text it holds — so what kept the row out was the refusal and nothing else.
     */
    @Test fun aRefusedSaveAddsNoCategory() = runTest {
        val vm = editModel()
        vm.onName("   ")
        vm.onCategory("Appliance")
        vm.save()
        vm.state.first { !it.saving }
        assertEquals("Give the asset a name", vm.state.value.problems[AssetField.NAME])
        assertTrue(graph.categories.all().isEmpty())

        vm.onName("Compressor")
        vm.save()
        vm.state.first { !it.saving }
        assertEquals(listOf("appliance" to "Appliance"), graph.categories.all().map { it.key to it.display })
        assertEquals("Appliance", graph.assets.all().single().category)
    }

    /**
     * #74 AC 1 (C16): a category saved once is offered by every editor after it — the next one opened,
     * and one already open, because the choices follow the catalog rather than being read once. Typing
     * alone offers nothing: until the save the choices are the built-ins, and the saved category comes
     * after them, reading like one of them but with no template hint (AC 7).
     */
    @Test fun aSavedCategoryIsOfferedNextTime() = runTest {
        val first = editModel()
        backgroundScope.launch { first.categoryChoices.collect() }
        first.onName("Unit A")
        first.onCategory("Appliance")
        advanceUntilIdle()
        assertEquals("typing offers nothing", CategoryCatalog.builtIns, first.categoryChoices.value)

        first.save()
        first.state.first { !it.saving }
        advanceUntilIdle()
        val appliance = CategoryChoice("Appliance", "appliance", builtIn = false, templateKey = null)
        assertEquals("the open editor gains it", CategoryCatalog.builtIns + appliance, first.categoryChoices.value)

        val second = editModel()
        backgroundScope.launch { second.categoryChoices.collect() }
        advanceUntilIdle()
        assertEquals("the next editor offers it", CategoryCatalog.builtIns + appliance, second.categoryChoices.value)
        assertEquals(13, second.categoryChoices.value.size)
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
     * A row says whose component it is and whether today is outside its window (spec §6, §9). A
     * component is listed once the Components control is on (#73: it is off by default), and it
     * still names its system when it is.
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
        vm.toggleComponents()

        val rows = vm.state.first { it.items.size == 2 }.items.associateBy { it.asset.name }
        assertEquals("Generator", rows.getValue("Starter battery").parentName)
        assertTrue(rows.getValue("Starter battery").outOfSeason)
        // A root asset with no window says neither thing.
        assertEquals(null, rows.getValue("Generator").parentName)
        assertFalse(rows.getValue("Generator").outOfSeason)
    }

    /**
     * #73 — with the Components control on, a component is listed under a blank query beside its
     * system, naming it, and a search for its name only narrows that same list down to it.
     */
    @Test fun aComponentIsListedWithComponentsOnAndASearchNarrowsToIt() = runTest {
        val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
        graph.createAsset.run(AssetCommand(name = "Circulation pump", parentAssetId = tub.id))

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }
        vm.toggleComponents()

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

    /** B07, under #73's controls: a blank query with every control at its default lists every active root, by name. */
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
        vm.toggleComponents()
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

    /** B07 — the Archived control and the search box narrow independently, exactly like F2 did. */
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
     * Owner ruling §18.23 (B07 fix round 5, controller ruling Q4), carried into #73's [EmptyReason]
     * — why the list is empty is the view model's to decide, not the screen's: a query that matches
     * only an archived row, with Archived off, reads ARCHIVED_HIDDEN.
     */
    @Test fun emptyReasonIsArchivedHiddenWhenOnlyAnArchivedRowMatches() = runTest {
        val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
        graph.archiveAsset.run(tub.id)

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.archivedCount == 1 }

        vm.onQueryChange("hot")
        val state = vm.state.first { it.query == "hot" }
        assertTrue("no active row matches", state.items.isEmpty())
        assertFalse(state.showArchived)
        assertEquals(EmptyReason.ARCHIVED_HIDDEN, state.emptyReason)
    }

    /** A blank query is never answered as a search: the archived-only store reads NO_ACTIVE_ASSETS. */
    @Test fun emptyReasonIsNoActiveAssetsUnderABlankQuery() = runTest {
        val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
        graph.archiveAsset.run(tub.id)

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.archivedCount == 1 }
        assertEquals("", state.query)
        assertEquals(EmptyReason.NO_ACTIVE_ASSETS, state.emptyReason)
    }

    /**
     * Negative — an active row matching the query is the ordinary case, not the archived-only one,
     * even when an archived row matches the same query: a formula that only ever counted archived
     * matches would say ARCHIVED_HIDDEN here and pass for the wrong reason (Q4). The fixture makes
     * both halves match "water" on purpose, so this only passes if the view model itself checks
     * whether an active row matched too.
     */
    @Test fun emptyReasonIsNoneWhenAnActiveRowAlsoMatches() = runTest {
        graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
        val heater = graph.createAsset.run(AssetCommand(name = "Water heater", category = "Kitchen"))
        graph.archiveAsset.run(heater.id)

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.archivedCount == 1 }

        vm.onQueryChange("water")
        val state = vm.state.first { it.query == "water" }
        assertEquals(listOf("Hot tub"), state.items.map { it.asset.name })
        assertEquals(EmptyReason.NONE, state.emptyReason)
    }

    /** Negative — a query that matches nothing anywhere stays "Nothing matches that.", not the hint. */
    @Test fun emptyReasonIsNothingMatchesWhenNothingMatchesAnywhere() = runTest {
        val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
        graph.archiveAsset.run(tub.id)

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.archivedCount == 1 }

        vm.onQueryChange("zzz")
        val state = vm.state.first { it.query == "zzz" }
        assertTrue(state.items.isEmpty())
        assertEquals(EmptyReason.NOTHING_MATCHES, state.emptyReason)
    }

    /**
     * Negative — once Archived is on, the matching archived row is listed, not held back, and the
     * reason clears (the `|| archived` half Q4 also named).
     */
    @Test fun emptyReasonIsNoneOnceArchivedIsOn() = runTest {
        val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
        graph.archiveAsset.run(tub.id)

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.archivedCount == 1 }

        vm.toggleArchived()
        vm.onQueryChange("hot")
        val state = vm.state.first { it.showArchived && it.query == "hot" }
        assertEquals(listOf("Hot tub"), state.items.map { it.asset.name })
        assertEquals(EmptyReason.NONE, state.emptyReason)
    }

    // ------------------------------------------------------------------------------------------
    // #73 — the Type, Components and Archived controls (plan 2026-09-26-issue-73-assets-filters §3).

    /** AC 3–5: Type All, Components off, Archived off — only the active root is listed. */
    @Test fun theFiltersDefaultToAllOffOff() = runTest {
        val system = graph.createAsset.run(AssetCommand(name = "Pool pump", category = "Pump"))
        graph.createAsset.run(AssetCommand(name = "Pump seal", category = "Pump", parentAssetId = system.id))
        val old = graph.createAsset.run(AssetCommand(name = "Old heater", category = "Pump"))
        graph.archiveAsset.run(old.id)

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.archivedCount == 1 }
        assertEquals("Type defaults to All", null, state.filters.type)
        assertFalse("Components defaults off", state.filters.showComponents)
        assertFalse("Archived defaults off", state.filters.showArchived)
        assertFalse("the mirror agrees", state.showArchived)
        assertEquals(null, state.typeLabel)
        assertEquals(listOf("Pool pump"), state.items.map { it.asset.name })
        assertEquals(EmptyReason.NONE, state.emptyReason)
    }

    /**
     * AC 7: the four predicates AND together. The walk visits all sixteen combinations of (query,
     * Type, Components, Archived) one change at a time — a Gray code with Archived changing most
     * often — so every control is toggled with the query non-blank and with another control on,
     * and each step proves the other two controls and the query are exactly as they were. Every
     * expected list is written down here, sorted as the list sorts: active by name, then archived.
     */
    @Test fun theFourPredicatesCompose() = runTest {
        val pump = graph.createAsset.run(AssetCommand(name = "Pool pump", category = "Water"))
        graph.createAsset.run(AssetCommand(name = "Pump seal", category = "Water", parentAssetId = pump.id))
        val mower = graph.createAsset.run(AssetCommand(name = "Old mower", category = "Yard"))
        val blade = graph.createAsset.run(AssetCommand(name = "Mower blade", category = "Yard", parentAssetId = pump.id))
        graph.createAsset.run(AssetCommand(name = "Hedge trimmer", category = "Yard"))
        graph.archiveAsset.run(mower.id)
        graph.archiveAsset.run(blade.id)

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }

        data class Combo(val query: String, val type: String?, val components: Boolean, val archived: Boolean)
        val walk = listOf(
            Combo("", null, false, false) to listOf("Hedge trimmer", "Pool pump"),
            Combo("", null, false, true) to listOf("Hedge trimmer", "Pool pump", "Old mower"),
            Combo("", null, true, true) to listOf("Hedge trimmer", "Pool pump", "Pump seal", "Mower blade", "Old mower"),
            Combo("", null, true, false) to listOf("Hedge trimmer", "Pool pump", "Pump seal"),
            Combo("", "yard", true, false) to listOf("Hedge trimmer"),
            Combo("", "yard", true, true) to listOf("Hedge trimmer", "Mower blade", "Old mower"),
            Combo("", "yard", false, true) to listOf("Hedge trimmer", "Old mower"),
            Combo("", "yard", false, false) to listOf("Hedge trimmer"),
            Combo("mower", "yard", false, false) to emptyList(),
            Combo("mower", "yard", false, true) to listOf("Old mower"),
            Combo("mower", "yard", true, true) to listOf("Mower blade", "Old mower"),
            Combo("mower", "yard", true, false) to emptyList(),
            Combo("mower", null, true, false) to emptyList(),
            Combo("mower", null, true, true) to listOf("Mower blade", "Old mower"),
            Combo("mower", null, false, true) to listOf("Old mower"),
            Combo("mower", null, false, false) to emptyList(),
        )

        val start = vm.state.first { it.archivedCount == 2 }
        assertEquals(walk.first().second, start.items.map { it.asset.name })
        walk.zipWithNext().forEach { (before, after) ->
            val (from, _) = before
            val (to, rows) = after
            val state = when {
                from.archived != to.archived -> {
                    vm.toggleArchived()
                    vm.state.first { it.filters.showArchived == to.archived }
                }
                from.components != to.components -> {
                    vm.toggleComponents()
                    vm.state.first { it.filters.showComponents == to.components }
                }
                from.type != to.type -> {
                    vm.pickType(to.type)
                    vm.state.first { it.filters.type == to.type }
                }
                else -> {
                    vm.onQueryChange(to.query)
                    vm.state.first { it.query == to.query }
                }
            }
            assertEquals("$to: the controls", AssetFilters(to.type, to.components, to.archived), state.filters)
            assertEquals("$to: the query", to.query, state.query)
            assertEquals("$to: the rows", rows, state.items.map { it.asset.name })
        }
    }

    /**
     * AC 3, 9; R73-5: Type matches by the catalog key, so a spelling stored before promotion (seeded
     * straight into the repository, as no write path can any more) is still of its type.
     */
    @Test fun typeFiltersByTheCatalogKey() = runTest {
        graph.createAsset.run(AssetCommand(name = "Main spa", category = "Hot tub"))
        graph.assets.upsert(assetRow("seeded", name = "Backyard spa").copy(category = "hot TUB"))
        graph.createAsset.run(AssetCommand(name = "Sump pump", category = "Pump"))

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.items.size == 3 }

        vm.pickType("hot tub")
        val spas = vm.state.first { it.filters.type == "hot tub" }
        assertEquals(listOf("Backyard spa", "Main spa"), spas.items.map { it.asset.name })
        assertEquals("Hot tub", spas.typeLabel)

        vm.pickType(null)
        val all = vm.state.first { it.filters.type == null }
        assertEquals(listOf("Backyard spa", "Main spa", "Sump pump"), all.items.map { it.asset.name })
        assertEquals(null, all.typeLabel)
    }

    /**
     * C4, R73-3: a chosen category renamed to a new key, or deleted, writes the control back to All —
     * and it stays All when the key comes back (#74's accepted resurrection, or the owner typing the
     * category again), because the control itself was reset rather than an "effective type" derived.
     */
    @Test fun aRenamedOrDeletedTypeResetsToAllAndStaysThere() = runTest {
        graph.createAsset.run(AssetCommand(name = "Deck heater", category = "Patio"))
        graph.createAsset.run(AssetCommand(name = "Sump pump", category = "Pump"))
        graph.categories.upsert(AssetCategory(key = "shed", display = "Shed", createdAt = 1L, updatedAt = 1L))

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.items.size == 2 }

        vm.pickType("patio")
        val picked = vm.state.first { it.filters.type == "patio" }
        assertEquals("Patio", picked.typeLabel)
        assertEquals(listOf("Deck heater"), picked.items.map { it.asset.name })

        // Renamed to a new key: the control is written back to All.
        graph.renameCategory.run("patio", "Terrace")
        val renamed = vm.state.first { state -> state.typeChoices.none { it.key == "patio" } }
        assertEquals(null, renamed.filters.type)
        assertEquals(null, renamed.typeLabel)
        assertEquals(listOf("Deck heater", "Sump pump"), renamed.items.map { it.asset.name })

        // The key comes back: Type stays All until the owner picks again.
        graph.createAsset.run(AssetCommand(name = "Patio lamp", category = "Patio"))
        val back = vm.state.first { state -> state.typeChoices.any { it.key == "patio" } }
        assertEquals(null, back.filters.type)
        assertEquals(null, back.typeLabel)
        val listed = vm.state.first { it.items.size == 3 }
        assertEquals(null, listed.filters.type)
        assertEquals(listOf("Deck heater", "Patio lamp", "Sump pump"), listed.items.map { it.asset.name })

        // Deleted (only an unused category can be): the control is written back to All as well.
        vm.pickType("shed")
        val shed = vm.state.first { it.filters.type == "shed" }
        assertEquals("Shed", shed.typeLabel)
        assertTrue(shed.items.isEmpty())
        graph.deleteCategory.run("shed")
        val deleted = vm.state.first { state -> state.typeChoices.none { it.key == "shed" } }
        assertEquals(null, deleted.filters.type)
        assertEquals(null, deleted.typeLabel)
        assertEquals(3, deleted.items.size)
    }

    /**
     * AC 9: the menu is the durable catalog (#74) — the built-ins in compiled order, then the owner's
     * rows by name — never the categories the Assets happen to hold: a row no asset uses is offered,
     * a spelling only an asset holds is not, and a row under a built-in's key is not offered twice.
     */
    @Test fun typeChoicesAreTheCatalogInItsOrder() = runTest {
        graph.createAsset.run(AssetCommand(name = "Tool bench", category = "Workshop"))
        graph.createAsset.run(AssetCommand(name = "Door opener", category = "garage"))
        graph.createAsset.run(AssetCommand(name = "Spare pump", category = "Pump"))
        graph.assets.upsert(assetRow("seeded", name = "Backyard spa").copy(category = "hot TUB"))
        graph.categories.upsert(AssetCategory(key = "attic", display = "Attic", createdAt = 1L, updatedAt = 1L))
        graph.categories.upsert(AssetCategory(key = "pump", display = "PUMP", createdAt = 1L, updatedAt = 1L))

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.items.size == 4 }
        assertEquals(
            listOf(
                "Generator", "Lawn mower", "Snowblower", "UPS", "Battery", "Inverter / charger",
                "Solar charge controller", "RO system", "Hot tub", "HVAC", "Pump", "Other",
                "Attic", "garage", "Workshop",
            ),
            state.typeChoices.map { it.display },
        )
        val keys = state.typeChoices.map { it.key }
        assertEquals("no key twice", keys.distinct(), keys)
    }

    /**
     * R73-1: a query searches the admitted set and never widens it. With Components off, a query that
     * matches only a component lists nothing, names Components as the control in the way, and leaves
     * every control as it was; turning Components on lists the match.
     */
    @Test fun aSearchNeverWidensTheAdmittedSet() = runTest {
        val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
        graph.createAsset.run(AssetCommand(name = "Circulation pump", parentAssetId = tub.id))

        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }
        assertEquals(listOf("Hot tub"), vm.state.first { it.items.isNotEmpty() }.items.map { it.asset.name })

        vm.onQueryChange("circ")
        val hidden = vm.state.first { it.query == "circ" }
        assertTrue("the component stays hidden", hidden.items.isEmpty())
        assertEquals(EmptyReason.COMPONENTS_HIDDEN, hidden.emptyReason)
        assertEquals(AssetFilters(type = null, showComponents = false, showArchived = false), hidden.filters)

        vm.toggleComponents()
        val shown = vm.state.first { it.filters.showComponents }
        assertEquals(listOf("Circulation pump"), shown.items.map { it.asset.name })
        assertEquals("Hot tub", shown.items.single().parentName)
        assertEquals("circ", shown.query)
        assertEquals(EmptyReason.NONE, shown.emptyReason)
    }

    /**
     * C5, R73-7: every empty list says why, and the reason is the **union** of the controls hiding
     * the matches — never a precedence, so a mix of an active component and an archived root names
     * both. The blank-query reasons come first (one store grown step by step); the query and Type
     * reasons after, over the same store; the one combination no write path can produce last.
     */
    @Test fun theEmptyReasonIsTheUnionOfTheControlsInTheWay() = runTest {
        val vm = listModel()
        backgroundScope.launch { vm.state.collect() }

        // Blank query, Type All: nothing at all.
        assertEquals(EmptyReason.NO_ASSETS, vm.state.first { it.typeChoices.isNotEmpty() }.emptyReason)

        // Only archived rows.
        val array = graph.createAsset.run(AssetCommand(name = "Solar array"))
        graph.archiveAsset.run(array.id)
        assertEquals(EmptyReason.NO_ACTIVE_ASSETS, vm.state.first { it.archivedCount == 1 }.emptyReason)

        // An archived root with two active components (AssetModelDeviceProofTest's fixture).
        graph.createAsset.run(AssetCommand(name = "Inverter", parentAssetId = array.id))
        graph.createAsset.run(AssetCommand(name = "Battery bank", parentAssetId = array.id))
        vm.toggleComponents()
        val listed = vm.state.first { it.filters.showComponents && it.items.size == 2 }
        assertEquals(listOf("Battery bank", "Inverter"), listed.items.map { it.asset.name })
        assertEquals(EmptyReason.NONE, listed.emptyReason)
        vm.toggleComponents()
        val parts = vm.state.first { !it.filters.showComponents }
        assertTrue(parts.items.isEmpty())
        assertEquals(EmptyReason.ONLY_COMPONENTS, parts.emptyReason)

        // The query and Type reasons, over a store none of the rows above matches.
        val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
        graph.createAsset.run(AssetCommand(name = "Circulation pump", category = "Pump", parentAssetId = tub.id))
        val heater = graph.createAsset.run(AssetCommand(name = "Old heater", category = "Water"))
        graph.archiveAsset.run(heater.id)
        val filter = graph.createAsset.run(AssetCommand(name = "Spare filter", category = "Water", parentAssetId = tub.id))
        graph.archiveAsset.run(filter.id)
        graph.createAsset.run(AssetCommand(name = "Mixer valve", category = "Water", parentAssetId = tub.id))
        val tank = graph.createAsset.run(AssetCommand(name = "Mixer tank", category = "Water"))
        graph.archiveAsset.run(tank.id)
        vm.state.first { it.archivedCount == 4 && it.items.size == 1 }

        suspend fun reasonFor(query: String): EmptyReason {
            vm.onQueryChange(query)
            return vm.state.first { it.query == query }.emptyReason
        }
        assertEquals("components only", EmptyReason.COMPONENTS_HIDDEN, reasonFor("circ"))
        assertEquals("archived only", EmptyReason.ARCHIVED_HIDDEN, reasonFor("heater"))
        assertEquals("an archived component", EmptyReason.BOTH_HIDDEN, reasonFor("spare"))
        assertEquals("an active component and an archived root", EmptyReason.BOTH_HIDDEN, reasonFor("mixer"))
        assertEquals("no hit", EmptyReason.NOTHING_MATCHES, reasonFor("zzz"))

        vm.pickType("water")
        vm.state.first { it.filters.type == "water" }
        assertEquals("a match of another type", EmptyReason.TYPE_HIDDEN, reasonFor("circ"))
        assertEquals("no hit, with a type", EmptyReason.NOTHING_MATCHES, reasonFor("zzz"))

        vm.onQueryChange("")
        vm.pickType("hvac")
        val noneOfType = vm.state.first { it.filters.type == "hvac" && it.query.isEmpty() }
        assertEquals("a blank query, a type nothing has", EmptyReason.NOTHING_MATCHES, noneOfType.emptyReason)
        vm.pickType("pump")
        val componentsOfType = vm.state.first { it.filters.type == "pump" }
        assertEquals("a blank query, a type only components have", EmptyReason.COMPONENTS_HIDDEN, componentsOfType.emptyReason)

        // Archived on, and every row a component: only a store no write path produces (a parent chain
        // with no root, seeded straight into a second database) can hold it, and it is still not "No
        // active assets" — that sentence is about what the Archived control hides, not about status.
        val rootless = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        try {
            rootless.assets.upsert(assetRow("loop", name = "Loose part", parent = "loop", status = AssetStatus.ARCHIVED))
            val alone = AssetsViewModel(
                rootless.assets, rootless.categories, rootless.seasonActivations, rootless.tags,
                rootless.assetHealthReadModel, rootless.todayPort,
            )
            val collecting = launch { alone.state.collect() }
            assertEquals(EmptyReason.NO_ACTIVE_ASSETS, alone.state.first { it.archivedCount == 1 }.emptyReason)
            alone.toggleArchived()
            assertEquals(EmptyReason.ONLY_COMPONENTS, alone.state.first { it.showArchived }.emptyReason)
            collecting.cancel()
        } finally {
            rootless.close()
        }
    }

    // ------------------------------------------------------------------------------------------
    // #71 — the list row's NFC and health indicators (plan §3, errata E6). `T` is 2026-04-15 for
    // every health case, and the fixture thresholds are 0 / 40 / 75: a replacement 10, 50 and 100
    // days back reads NOMINAL, WARNING and CRITICAL. Room answers the list's flows off the virtual
    // clock, so every wait is real time and bounded, and a case that asserts an absence waits first
    // for a control row whose health came from the same pass.
    // ------------------------------------------------------------------------------------------

    /** An asset with one AGE subject replaced [daysBack] days before [T71]. */
    private suspend fun trackedAsset(
        id: String,
        daysBack: Long,
        name: String = id,
        parent: String? = null,
        retiredOn: String? = null,
        status: AssetStatus = AssetStatus.ACTIVE,
    ) {
        graph.assets.upsert(assetRow(id, name = name, parent = parent, retiredOn = retiredOn, status = status))
        graph.events.upsert(replacementOf("e-$id", id, T71.minusDays(daysBack).toString()))
        graph.healthSubjects.upsert(subjectRow("h-$id", id, name = "$name age"))
    }

    /** A tag row as `ProvisionTag` leaves it once the write is verified: ACTIVE, targeting [assetId], written. */
    private fun written(id: String, assetId: String) = tag(id, assetId).copy(writtenAt = 42L)

    /** The list model, collected for the length of the test. */
    private fun TestScope.collectedList(): AssetsViewModel =
        listModel().also { vm -> backgroundScope.launch { vm.state.collect() } }

    /** The rows by id, once [until] accepts them — bounded in real time, and naming what it waited for. */
    private suspend fun AssetsViewModel.rowsOnce(what: String, until: (Map<String, AssetRow>) -> Boolean): Map<String, AssetRow> =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(WAIT_MS) { state.first { until(it.items.associateBy { row -> row.asset.id.value }) } }
        }?.items?.associateBy { it.asset.id.value }
            ?: throw AssertionError("$what: never; the rows were ${state.value.items.map { it.summary() }}")

    private fun AssetRow.summary() =
        "${asset.id.value}(tag=$hasWrittenTag, band=${band()}, condition=${health?.condition?.condition})"

    private fun AssetRow.band(): HealthBand? = health?.result?.aggregate?.band

    /** AC 1: a verified write shows the indicator, through the real provisioning path. */
    @Test fun aWrittenActiveTagShowsTheIndicator() = runTest {
        graph.assets.upsert(assetRow("pump", name = "Pool pump"))
        val vm = collectedList()
        vm.rowsOnce("listed") { "pump" in it }

        val provisioned = graph.provisionTag.begin(TagTarget.AssetTarget(AssetId("pump")), "lid")
        graph.provisionTag.complete(provisioned.id, "04aabbcc")

        vm.rowsOnce("the written tag") { it["pump"]?.hasWrittenTag == true }
    }

    /** AC 2: no tag row at all, no indicator — on a row whose tag fact has certainly arrived. */
    @Test fun noTagRowsNoIndicator() = runTest {
        graph.assets.upsert(assetRow("pump", name = "Pool pump"))
        graph.assets.upsert(assetRow("heater", name = "Heater"))
        graph.tags.upsert(written("t1", "heater"))
        val rows = collectedList().rowsOnce("the control's tag") { it["heater"]?.hasWrittenTag == true }

        assertFalse(rows.getValue("pump").hasWrittenTag)
    }

    /** AC 3: a row provisioned but never written is not a tag the asset carries. */
    @Test fun aProvisionedUnwrittenTagDoesNotCount() = runTest {
        graph.assets.upsert(assetRow("pump", name = "Pool pump"))
        graph.assets.upsert(assetRow("heater", name = "Heater"))
        graph.provisionTag.begin(TagTarget.AssetTarget(AssetId("pump")), "lid")
        graph.tags.upsert(written("t1", "heater"))
        val rows = collectedList().rowsOnce("the control's tag") { it["heater"]?.hasWrittenTag == true }

        assertFalse("provisioned, unwritten", rows.getValue("pump").hasWrittenTag)
    }

    /** AC 4: LOST and RETIRED rows never count, and one qualifying row among them is enough. */
    @Test fun oneQualifyingTagAmongLostAndRetiredOnesIsEnough() = runTest {
        graph.assets.upsert(assetRow("pump", name = "Pool pump"))
        graph.assets.upsert(assetRow("heater", name = "Heater"))
        listOf("pump", "heater").forEach { id ->
            graph.tags.upsert(written("lost-$id", id).copy(status = TagStatus.LOST))
            graph.tags.upsert(written("retired-$id", id).copy(status = TagStatus.RETIRED))
            graph.tags.upsert(tag("unwritten-$id", id))
        }
        graph.tags.upsert(written("t1", "pump"))

        val rows = collectedList().rowsOnce("the qualifying tag") { it["pump"]?.hasWrittenTag == true }

        assertFalse("only lost, retired and unwritten rows", rows.getValue("heater").hasWrittenTag)
    }

    /** AC 5: a tag counts for the asset it targets, and for no other. */
    @Test fun anotherAssetsTagCountsForThatAssetOnly() = runTest {
        graph.assets.upsert(assetRow("pump", name = "Pool pump"))
        graph.assets.upsert(assetRow("heater", name = "Heater"))
        graph.tags.upsert(written("t1", "heater"))

        val rows = collectedList().rowsOnce("heater's tag") { it["heater"]?.hasWrittenTag == true }

        assertTrue(rows.getValue("heater").hasWrittenTag)
        assertFalse(rows.getValue("pump").hasWrittenTag)
    }

    /** AC 6: the indicator follows the store without a restart — lost, retired, unbound, re-targeted. */
    @Test fun losingTheLastQualifyingTagRemovesTheIndicatorWithoutRestart() = runTest {
        graph.assets.upsert(assetRow("pump", name = "Pool pump"))
        graph.assets.upsert(assetRow("heater", name = "Heater"))
        graph.tags.upsert(written("t1", "pump"))
        val vm = collectedList()
        vm.rowsOnce("tagged") { it["pump"]?.hasWrittenTag == true }

        graph.tags.upsert(written("t1", "pump").copy(status = TagStatus.LOST))
        vm.rowsOnce("marked lost") { it["pump"]?.hasWrittenTag == false }

        graph.tags.upsert(written("t2", "pump"))
        vm.rowsOnce("a new tag") { it["pump"]?.hasWrittenTag == true }
        graph.tags.upsert(written("t2", "pump").copy(status = TagStatus.RETIRED))
        vm.rowsOnce("retired") { it["pump"]?.hasWrittenTag == false }

        graph.tags.upsert(written("t3", "pump"))
        vm.rowsOnce("another") { it["pump"]?.hasWrittenTag == true }
        graph.tags.upsert(written("t3", "pump").copy(target = TagTarget.None, status = TagStatus.UNBOUND))
        vm.rowsOnce("unbound") { it["pump"]?.hasWrittenTag == false }

        graph.tags.upsert(written("t4", "pump"))
        vm.rowsOnce("and another") { it["pump"]?.hasWrittenTag == true }
        graph.tags.upsert(written("t4", "heater"))
        val rows = vm.rowsOnce("re-targeted") { it["pump"]?.hasWrittenTag == false }
        assertTrue("the tag now counts for the asset it names", rows.getValue("heater").hasWrittenTag)
    }

    /** AC 7: a component's row reads its own tag and its own health; the parent's borrows neither. */
    @Test fun aComponentCarriesItsOwnIndicators() = runTest {
        graph.today = T71
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        trackedAsset("pack", daysBack = 10, name = "Battery pack", parent = "gen")
        graph.tags.upsert(written("t1", "pack"))
        val vm = collectedList()
        vm.toggleComponents()

        val rows = vm.rowsOnce("the component's own facts") { it["pack"]?.hasWrittenTag == true && it["pack"]?.band() != null }

        assertEquals(HealthBand.NOMINAL, rows.getValue("pack").band())
        assertFalse("the parent borrows no tag", rows.getValue("gen").hasWrittenTag)
        assertEquals("the parent borrows no health", null, rows.getValue("gen").health)
    }

    /** AC 10, 11: the band is the read model's aggregate, and it moves with the day on a refresh. */
    @Test fun theHealthBadgeIsTheReadModelsAggregate() = runTest {
        graph.today = T71
        trackedAsset("ups", daysBack = 10, name = "UPS")
        val vm = collectedList()
        val nominal = vm.rowsOnce("10 days") { it["ups"]?.band() == HealthBand.NOMINAL }.getValue("ups")
        assertEquals("the very view forAsset gives", graph.assetHealthReadModel.forAsset(AssetId("ups")), nominal.health)

        graph.today = T71.plusDays(40)
        vm.refresh()
        vm.rowsOnce("50 days, after a refresh") { it["ups"]?.band() == HealthBand.WARNING }

        graph.today = T71.plusDays(90)
        vm.refresh()
        vm.rowsOnce("100 days, after the next") { it["ups"]?.band() == HealthBand.CRITICAL }
    }

    /** AC 12: NOT TRACKED draws nothing — no subject, every subject archived, a screened TRACK_ONE primary. */
    @Test fun notTrackedDrawsNoHealth() = runTest {
        graph.today = T71
        trackedAsset("ctl", daysBack = 10, name = "Control")
        graph.assets.upsert(assetRow("bare", name = "Bare"))
        trackedAsset("shelved", daysBack = 10, name = "Shelved")
        graph.healthSubjects.upsert(subjectRow("h-shelved", "shelved", name = "Shelved age", archivedAt = dayMillis("2026-04-01")))
        graph.assets.upsert(assetRow("tub", name = "Tub", aggregation = HealthAggregation.TRACK_ONE, primary = "h-bad"))
        graph.events.upsert(replacementOf("e-tub", "tub", "2026-01-15"))
        graph.healthSubjects.upsert(subjectRow("h-bad", "tub", name = "Heater age", nominalUntilDays = 50, warningFromDays = 40, criticalFromDays = 75))
        graph.healthSubjects.upsert(subjectRow("h-good", "tub", name = "Pump age", sortOrder = 1))

        val rows = collectedList().rowsOnce("the control's band") { it["ctl"]?.band() == HealthBand.NOMINAL }

        listOf("bare", "shelved", "tub").forEach { id -> assertEquals("$id is not tracked", null, rows.getValue(id).health) }
    }

    /** AC 13: condition never becomes health — DOWN alone draws none, DOWN beside a NOMINAL subject stays NOMINAL. */
    @Test fun conditionNeverBecomesHealth() = runTest {
        graph.today = T71
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.conditions.insert(conditionRow("c1", "gen", OperationalCondition.DOWN, "2026-04-10"))
        trackedAsset("ups", daysBack = 10, name = "UPS")
        graph.conditions.insert(conditionRow("c2", "ups", OperationalCondition.DOWN, "2026-04-11"))

        val rows = collectedList().rowsOnce("the tracked DOWN row") { it["ups"]?.band() != null }

        assertEquals("DOWN beside a NOMINAL subject is NOMINAL", HealthBand.NOMINAL, rows.getValue("ups").band())
        assertEquals("DOWN with no subject draws no health", null, rows.getValue("gen").health)
    }

    /** R71-10: a retired or an archived asset draws no health on the list, whatever its subjects score. */
    @Test fun aRetiredOrArchivedAssetDrawsNoHealth() = runTest {
        graph.today = T71
        trackedAsset("ups", daysBack = 10, name = "UPS")
        trackedAsset("old", daysBack = 100, name = "Old", retiredOn = "2026-01-01")
        trackedAsset("gone", daysBack = 100, name = "Gone", status = AssetStatus.ARCHIVED)
        assertEquals("the retired asset would score", HealthBand.CRITICAL, graph.assetHealthReadModel.forAsset(AssetId("old")).result.aggregate?.band)
        val vm = collectedList()
        vm.toggleArchived()

        val rows = vm.rowsOnce("every row, the control scored") { it.size == 3 && it["ups"]?.band() == HealthBand.NOMINAL }

        assertEquals("retired", null, rows.getValue("old").health)
        assertEquals("archived", null, rows.getValue("gone").health)
    }

    /** E6, inv. 119: a DOWN asset's row that shows health carries its condition in the same view. */
    @Test fun aDownRowCarriesItsConditionBesideHealth() = runTest {
        graph.today = T71
        trackedAsset("ups", daysBack = 10, name = "UPS")
        graph.conditions.insert(conditionRow("c1", "ups", OperationalCondition.DOWN, "2026-04-11", reason = "Won't start"))

        val ups = collectedList().rowsOnce("health") { it["ups"]?.band() != null }.getValue("ups")

        assertEquals(HealthBand.NOMINAL, ups.band())
        assertEquals(OperationalCondition.DOWN, ups.health?.condition?.condition)
        assertEquals("Won't start", ups.health?.condition?.reason)
    }

    /** E6, inv. 119: a CRITICAL subject behind a NOMINAL average is listed on the row, in S109's words. */
    @Test fun aCriticalContributorBehindANominalAggregateIsListed() = runTest {
        graph.today = T71
        graph.assets.upsert(assetRow("ups", name = "UPS", aggregation = HealthAggregation.AVERAGE))
        graph.events.upsert(replacementOf("e1", "ups", "2026-01-15"))
        graph.healthSubjects.upsert(subjectRow("h1", "ups", name = "Battery age", sortOrder = 0))
        graph.healthSubjects.upsert(subjectRow("h2", "ups", name = "Fan age", nominalUntilDays = 1000, warningFromDays = 2000, criticalFromDays = 3000, sortOrder = 1))
        graph.healthSubjects.upsert(subjectRow("h3", "ups", name = "Case age", nominalUntilDays = 1000, warningFromDays = 2000, criticalFromDays = 3000, sortOrder = 2))

        val health = collectedList().rowsOnce("health") { it["ups"]?.band() != null }.getValue("ups").health!!

        assertEquals("the average is NOMINAL", HealthBand.NOMINAL, health.result.aggregate?.band)
        val critical = healthBlocksOf(health).filterIsInstance<HealthBlock.Critical>().single()
        assertEquals("Battery age", critical.subject.subject.name)
        val score = (critical.subject.value as SubjectValue.Scored).score
        assertEquals(listOf("Critical: Battery age $score"), rowHealthLines(health))
    }

    /** E6, inv. 119: a DOWN in-service component is listed on its parent's row, in S27's words; retired, it is not. */
    @Test fun aDownInServiceComponentIsListedOnTheParentRow() = runTest {
        graph.today = T71
        trackedAsset("gen", daysBack = 10, name = "Generator")
        graph.assets.upsert(assetRow("pack", name = "Battery pack", parent = "gen"))
        graph.conditions.insert(conditionRow("c1", "pack", OperationalCondition.DOWN, "2026-04-11", reason = "Won't hold charge"))
        val vm = collectedList()

        val gen = vm.rowsOnce("the parent's health") { it["gen"]?.band() != null }.getValue("gen").health!!
        assertEquals(listOf(AssetId("pack")), healthBlocksOf(gen).filterIsInstance<HealthBlock.Component>().map { it.component.assetId })
        assertEquals(listOf("Battery pack DOWN — Won't hold charge"), rowHealthLines(gen))

        graph.assets.upsert(assetRow("pack", name = "Battery pack", parent = "gen", retiredOn = "2026-04-12"))
        val after = vm.rowsOnce("the component retired") { it["gen"]?.health?.components?.isEmpty() == true }.getValue("gen").health!!
        assertEquals(emptyList<String>(), rowHealthLines(after))
    }

    /** E2, R71-11: a row that shows no health shows none of the group — untracked, or retired — even DOWN. */
    @Test fun noHealthNoGroup() = runTest {
        graph.today = T71
        trackedAsset("ctl", daysBack = 10, name = "Control")
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.conditions.insert(conditionRow("c1", "gen", OperationalCondition.DOWN, "2026-04-10"))
        trackedAsset("old", daysBack = 10, name = "Old", retiredOn = "2026-01-01")
        graph.conditions.insert(conditionRow("c2", "old", OperationalCondition.DOWN, "2026-04-10"))

        val rows = collectedList().rowsOnce("the control's band") { it["ctl"]?.band() == HealthBand.NOMINAL }

        assertEquals("DOWN, untracked", null, rows.getValue("gen").health)
        assertEquals("DOWN, tracked, retired", null, rows.getValue("old").health)
    }

    /**
     * Review MINOR-1: coming back to the list after the subscription grace restarts its flows, and the
     * health group already drawn must not blink off while the restarted pass runs. The pass is held
     * shut across the restart, so the first list after it is built from the kept views, never from
     * the read model's empty first emission. An asset retired while the list was away shows no health
     * from the kept views: the row's own asset gates them.
     */
    @Test fun aRestartAfterTheGraceKeepsTheHealthAlreadyDrawn() = runTest {
        graph.today = T71
        trackedAsset("ups", daysBack = 10, name = "UPS")
        trackedAsset("old", daysBack = 10, name = "Old")
        val open = MutableStateFlow(true)
        val held = object : HealthSubjectRepository by graph.healthSubjects {
            override suspend fun forAsset(assetId: AssetId): List<HealthSubject> {
                open.first { it }
                return graph.healthSubjects.forAsset(assetId)
            }
        }
        val health = AssetHealthReadModel(
            graph.assets, held, graph.schedules, graph.scheduleStates, graph.events, graph.profiles,
            graph.seasonActivations, graph.conditions, graph.recomputeSchedules, graph.todayPort, zone = { ZoneOffset.UTC },
        )
        val vm = AssetsViewModel(graph.assets, graph.categories, graph.seasonActivations, graph.tags, health, graph.todayPort)
        val watching = backgroundScope.launch { vm.state.collect() }
        vm.rowsOnce("the group drawn") { it["ups"]?.band() == HealthBand.NOMINAL && it["old"]?.band() == HealthBand.NOMINAL }

        watching.cancel()
        advanceTimeBy(PAST_THE_GRACE_MS)
        open.value = false
        graph.assets.upsert(assetRow("old", name = "Old", retiredOn = "2026-04-14"))
        backgroundScope.launch { vm.state.collect() }

        val restarted = vm.rowsOnce("the first list after the restart") { it["old"]?.asset?.retiredOn != null }
        assertEquals("the kept health, with the pass still held", HealthBand.NOMINAL, restarted.getValue("ups").band())
        assertEquals("a row retired meanwhile shows none of the kept health", null, restarted.getValue("old").health)

        open.value = true
        val passed = vm.rowsOnce("the restarted pass") { it["old"]?.asset?.retiredOn != null && it["ups"]?.band() == HealthBand.NOMINAL }
        assertEquals(null, passed.getValue("old").health)
    }

    /**
     * Review MINOR-2: the row's gate alone (E2, E4), on synthetic views — the read model never hands
     * it an out-of-service view today, so each clause is pinned here: the row's own asset in service,
     * the view in service, and the view tracked.
     */
    @Test fun theRowGateShowsHealthOnlyForAnInServiceTrackedRow() {
        val active = assetRow("ups", name = "UPS")
        val tracked = gateView(inService = true, band = HealthBand.NOMINAL)

        assertEquals("in service and tracked: the view itself", tracked, rowHealthOf(active, tracked))
        assertEquals("no view", null, rowHealthOf(active, null))
        assertEquals("the view is not tracked", null, rowHealthOf(active, gateView(inService = true, band = null)))
        assertEquals("the view is out of service", null, rowHealthOf(active, gateView(inService = false, band = HealthBand.NOMINAL)))
        assertEquals("the row is retired", null, rowHealthOf(assetRow("ups", name = "UPS", retiredOn = "2026-01-01"), tracked))
        assertEquals("the row is archived", null, rowHealthOf(assetRow("ups", name = "UPS", status = AssetStatus.ARCHIVED), tracked))
    }

    private fun gateView(inService: Boolean, band: HealthBand?) = AssetHealthView(
        assetId = AssetId("ups"),
        computedForOn = T71,
        condition = null,
        aggregation = HealthAggregation.WORST,
        result = AssetHealthResult(
            subjects = emptyList(),
            aggregate = band?.let { SubjectValue.Scored(80, it, null) },
            fallback = false,
            critical = emptyList(),
        ),
        components = emptyList(),
        inService = inService,
    )

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
     * #67, R67-5: DETAILS names the newest purchase invoice or receipt — by `capturedOn`, the
     * undated last — and follows the asset's files as they land. A manual never stands in for it,
     * however recent, and an asset with no receipt has no fact at all.
     */
    @Test fun theDetailsFactsNameThePurchaseDocument() = runTest {
        graph.assets.upsert(assetRow("tub", name = "Hot tub"))
        val vm = detailModel(AssetId("tub"))
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it != null }
        suspend fun attach(name: String, role: DocumentRole, capturedOn: String?): String? {
            graph.addAttachment.run(
                AttachmentOwner.OfAsset(AssetId("tub")),
                AddAttachmentCommand(
                    displayName = name,
                    mimeType = "application/pdf",
                    capturedOn = capturedOn,
                    role = role,
                ),
                ByteSource { name.byteInputStream() },
            )
            scheduler.advanceUntilIdle()
            return vm.state.value!!.purchaseDocument
        }
        assertNull(vm.state.value!!.purchaseDocument)

        assertNull(attach("Owner's manual.pdf", DocumentRole.USER_MANUAL, "2026-09-01"))
        assertEquals("Receipt, undated.pdf", attach("Receipt, undated.pdf", DocumentRole.PURCHASE_INVOICE_OR_RECEIPT, null))
        assertEquals("Receipt 2025.pdf", attach("Receipt 2025.pdf", DocumentRole.PURCHASE_INVOICE_OR_RECEIPT, "2025-05-01"))
        assertEquals("Receipt 2026.pdf", attach("Receipt 2026.pdf", DocumentRole.PURCHASE_INVOICE_OR_RECEIPT, "2026-02-01"))
        assertEquals("Receipt 2026.pdf", attach("Receipt 2024.pdf", DocumentRole.PURCHASE_INVOICE_OR_RECEIPT, "2024-01-01"))
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
            graph.conditions, graph.seasonActivations, graph.healthSubjects, graph.attachments,
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
                // PRE_SERVICE on a YEAR_ROUND asset with no break has no boundary: the merge-only state
                // the engine evaluates as CONTINUOUS. R-1 counts by policy name, so it is not counted.
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

    /**
     * C1's read is advisory: the save is already written when the schedules are read, so a read that
     * fails finishes the editor through `saved` as a save that asked nothing — no question, no stuck
     * Save, no crash out of `viewModelScope`.
     */
    @Test fun aScheduleReadThatFailsAfterTheSaveStillFinishesTheEditor() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.schedules.upsert(weekly("s1", "gen", ServicePolicy.CONTINUOUS))
        val unreadable = object : ScheduleRepository by graph.schedules {
            override suspend fun forAsset(assetId: AssetId): List<MaintenanceSchedule> =
                throw IllegalStateException("the schedule read failed")
        }
        val model = AssetEditViewModel(
            graph.assets, graph.healthSubjects, graph.saveAssetSettings, unreadable, graph.categories,
            graph.attachments, graph.attachmentStorage, graph.addAttachment, graph.todayPort, AssetId("gen"),
        )
        model.state.first { it.parentChoices.isNotEmpty() }
        val saved = mutableListOf<AssetId>()
        val review = mutableListOf<AssetId>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.saved.collect { saved += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.review.collect { review += it } }

        model.saveAs(SeasonMode.MANUAL)

        assertEquals("the save stands", SeasonMode.MANUAL, graph.assets.get(AssetId("gen"))!!.seasonMode)
        assertEquals(null, model.prompt.value)
        assertEquals(listOf(AssetId("gen")), saved)
        assertTrue(review.isEmpty())
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

    // ---------------------------------------------------------------------------------------------
    // #67: the editor's document intake — staging, and Save with staged files (plan §2 C5, C6)
    // ---------------------------------------------------------------------------------------------

    /** What one intake editor said: where it finished, and how many files were still staged when it did. */
    private class IntakeEditor(val model: AssetEditViewModel) {
        val saved = mutableListOf<AssetId>()
        val stagedAtSaved = mutableListOf<Int>()
    }

    /** A picked file as `AttachmentPickers` hands one over: a name, a type, and bytes read on demand. */
    private fun picked(
        name: String,
        mime: String = "application/pdf",
        open: () -> InputStream = { ByteArrayInputStream(name.toByteArray()) },
    ) = PickedFile(displayName = name, mimeType = mime, sizeBytes = name.length.toLong(), open = open)

    /** The settings write over [assets], so a test can count its first read — one per `SaveAssetSettings.run`. */
    private fun saveSettingsOver(assets: AssetRepository) = SaveAssetSettings(
        assets, graph.schedules, graph.healthSubjects, graph.seasonActivations, graph.uow, graph.ids,
        graph.clock, graph.todayPort, graph.recomputeSchedules, graph.applyTemplate, graph.promoteCategory,
    )

    /** `SaveAssetSettings.run` reads every asset first, and nothing else here goes through this port. */
    private class CountingAssets(private val inner: AssetRepository) : AssetRepository by inner {
        var reads = 0
        override suspend fun all(): List<Asset> {
            reads += 1
            return inner.all()
        }
    }

    /**
     * An editor whose folder and settings write a test chooses, its copies on this test's scheduler, and
     * its `saved` collected eagerly (no replay). Suspends until the form has loaded.
     */
    private suspend fun TestScope.intake(
        id: AssetId? = null,
        storage: AttachmentStorage = FakeAttachmentStorage(),
        saveSettings: SaveAssetSettings = graph.saveAssetSettings,
    ): IntakeEditor {
        val add = AddAttachment(graph.attachments, graph.assets, graph.events, storage, graph.uow, graph.ids, graph.clock)
        val model = AssetEditViewModel(
            graph.assets, graph.healthSubjects, saveSettings, graph.schedules, graph.categories,
            graph.attachments, storage, add, graph.todayPort, id,
            io = StandardTestDispatcher(testScheduler),
        )
        model.state.first { it.parentChoices.isNotEmpty() }
        val editor = IntakeEditor(model)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.saved.collect {
                editor.saved += it
                editor.stagedAtSaved += model.state.value.staged.size
            }
        }
        return editor
    }

    private suspend fun AssetEditViewModel.saveAndWait() {
        save()
        state.first { !it.saving }
    }

    private fun AssetEditViewModel.stagedLines() = state.value.staged.map { it.file.displayName to it.problem }

    /** A folder that holds every copy at the door until [gate] opens. */
    private fun gatedStorage(gate: CompletableDeferred<Unit>): AttachmentStorage {
        val inner = InMemoryAttachmentStore()
        val store = object : AttachmentStore by inner {
            override suspend fun put(locator: String, source: ByteSource): StoredBytes {
                gate.await()
                return inner.put(locator, source)
            }
        }
        return object : AttachmentStorage {
            override fun state(): StoreState = READY
            override fun store(): AttachmentStore = store
        }
    }

    /** C5 (AC 1): a pick is held, not copied — not at the pick, not on a refused Save. */
    @Test fun stagingWritesNothingUntilSave() = runTest {
        val storage = FakeAttachmentStorage()
        val editor = intake(storage = storage)
        editor.model.stage(DocumentRole.PURCHASE_INVOICE_OR_RECEIPT, picked("receipt.pdf"))
        editor.model.stage(DocumentRole.USER_MANUAL, picked("manual.pdf"))
        advanceUntilIdle()
        assertEquals(listOf("receipt.pdf" to null, "manual.pdf" to null), editor.model.stagedLines())
        assertEquals(0, graph.attachments.count())
        assertTrue(storage.store.files.isEmpty())

        // No name: the settings write is refused, so nothing is copied and the staged list stays.
        editor.model.saveAndWait()
        assertTrue(editor.model.state.value.problems.containsKey(AssetField.NAME))
        assertEquals(listOf("receipt.pdf" to null, "manual.pdf" to null), editor.model.stagedLines())
        assertTrue(graph.assets.all().isEmpty())
        assertEquals(0, graph.attachments.count())
        assertTrue(storage.store.files.isEmpty())
        assertTrue(editor.saved.isEmpty())
        // Cancel is this editor abandoned: nothing was written, so nothing is left behind.

        // An existing asset has an id before any write, so only the order of the steps keeps its
        // files back: a refused write copies nothing, and the staged list stays for the next Save.
        graph.assets.upsert(assetRow("tub", name = "Hot tub"))
        val existing = intake(AssetId("tub"), storage = storage)
        existing.model.stage(DocumentRole.USER_MANUAL, picked("manual.pdf"))
        existing.model.stage(DocumentRole.SERVICE_MANUAL, picked("service.pdf"))
        existing.model.onName("")
        existing.model.saveAndWait()
        assertTrue(existing.model.state.value.problems.containsKey(AssetField.NAME))
        assertEquals(listOf("manual.pdf" to null, "service.pdf" to null), existing.model.stagedLines())
        assertEquals("Hot tub", graph.assets.get(AssetId("tub"))!!.name)
        assertEquals(0, graph.attachments.count())
        assertTrue(storage.store.files.isEmpty())
        assertTrue(existing.saved.isEmpty())
    }

    /** C6 step 2: the written asset gets every staged file — its role, its kind inferred, today's date — and then `saved`. */
    @Test fun savingANewAssetAttachesTheStagedFilesWithTheirRoles() = runTest {
        val storage = FakeAttachmentStorage()
        val editor = intake(storage = storage)
        editor.model.onName("Hot tub")
        editor.model.stage(DocumentRole.PURCHASE_INVOICE_OR_RECEIPT, picked("receipt.pdf"))
        editor.model.stage(DocumentRole.USER_MANUAL, picked("manual.pdf"))
        editor.model.stage(DocumentRole.SERVICE_MANUAL, picked("wiring.jpg", "image/jpeg"))
        editor.model.saveAndWait()

        val asset = graph.assets.all().single()
        assertEquals(listOf(asset.id), editor.saved)
        assertEquals("saved only once nothing is staged", listOf(0), editor.stagedAtSaved)
        val rows = graph.attachments.forOwner(AttachmentOwner.OfAsset(asset.id)).associateBy { it.displayName }
        assertEquals(
            mapOf(
                "receipt.pdf" to (DocumentRole.PURCHASE_INVOICE_OR_RECEIPT to AttachmentKind.DOCUMENT),
                "manual.pdf" to (DocumentRole.USER_MANUAL to AttachmentKind.DOCUMENT),
                "wiring.jpg" to (DocumentRole.SERVICE_MANUAL to AttachmentKind.PHOTO),
            ),
            rows.mapValues { (_, row) -> row.role to row.kind },
        )
        assertEquals(setOf("2026-02-10"), rows.values.map { it.capturedOn }.toSet())
        assertEquals(3, storage.store.files.size)
        assertTrue(editor.model.state.value.staged.isEmpty())
    }

    /** C5: Remove forgets a staged file, and Save copies only what is left. */
    @Test fun removingAStagedFileForgetsIt() = runTest {
        val storage = FakeAttachmentStorage()
        val editor = intake(storage = storage)
        editor.model.onName("Hot tub")
        editor.model.stage(DocumentRole.USER_MANUAL, picked("old manual.pdf"))
        editor.model.stage(DocumentRole.USER_MANUAL, picked("manual.pdf"))
        editor.model.unstage(editor.model.state.value.staged.first())
        assertEquals(listOf("manual.pdf" to null), editor.model.stagedLines())

        editor.model.saveAndWait()
        assertEquals(listOf("manual.pdf"), graph.attachments.all().map { it.displayName })
        assertEquals(1, storage.store.files.size)
    }

    /**
     * C6 (B1): a copy that fails after a new asset's write leaves one asset, and the retry addresses
     * it by the id the write minted — an edited retry writes again, onto that id, never a second
     * asset. From the write on the form is an edit: the title reads "Edit asset" and the template
     * row hides, both keyed off `editing`.
     */
    @Test fun aRetryAfterAFailedCopyAddressesTheSavedAssetOnly() = runTest {
        val storage = FakeAttachmentStorage(state = LOST)
        val editor = intake(storage = storage)
        editor.model.onName("Hot tub")
        editor.model.stage(DocumentRole.USER_MANUAL, picked("manual.pdf"))
        editor.model.saveAndWait()

        val minted = graph.assets.all().single().id
        assertTrue("an edit from the write on", editor.model.state.value.editing)
        assertEquals(listOf("manual.pdf" to "The attachment folder is not available"), editor.model.stagedLines())
        assertTrue(editor.saved.isEmpty())

        // An edit after the failure: the retry runs the settings write again, addressed to the minted id.
        editor.model.onName("Hot tub 2")
        storage.state = READY
        editor.model.saveAndWait()
        assertEquals("one asset, never a second", 1, graph.assets.all().size)
        assertEquals("Hot tub 2", graph.assets.get(minted)!!.name)
        assertEquals(listOf(minted), editor.saved)
        assertEquals(
            listOf(AttachmentOwner.OfAsset(minted)),
            graph.attachments.all().map { it.owner },
        )
        assertTrue(editor.model.state.value.staged.isEmpty())
    }

    /** C6: `saving` holds from the tap until the copies end, so a second tap writes and copies nothing twice. */
    @Test fun aSecondTapDuringTheCopiesIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val reads = CountingAssets(graph.assets)
        val editor = intake(storage = gatedStorage(gate), saveSettings = saveSettingsOver(reads))
        editor.model.onName("Hot tub")
        editor.model.stage(DocumentRole.USER_MANUAL, picked("manual.pdf"))
        editor.model.save()
        // Written, and the copy is waiting at the gate.
        editor.model.state.first { it.editing }
        advanceUntilIdle()
        assertTrue(editor.model.state.value.saving)

        editor.model.save()
        advanceUntilIdle()
        assertTrue("still the first save", editor.model.state.value.saving)

        gate.complete(Unit)
        editor.model.state.first { !it.saving }
        assertEquals("one settings write", 1, reads.reads)
        assertEquals(1, graph.assets.all().size)
        assertEquals(1, graph.attachments.all().size)
        assertEquals(1, editor.saved.size)
    }

    /**
     * C6 (B-1, R67-8): the staged list is held with the form while the copies run. A pick that lands
     * then is ignored, as a second Save tap is — never staged only to be dropped when the copies end —
     * and `saved` never finishes over a staged file.
     */
    @Test fun aPickDuringTheCopiesIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val editor = intake(storage = gatedStorage(gate))
        editor.model.onName("Hot tub")
        editor.model.stage(DocumentRole.USER_MANUAL, picked("manual.pdf"))
        editor.model.save()
        editor.model.state.first { it.editing }
        advanceUntilIdle()
        assertTrue("the copy waits at the gate", editor.model.state.value.saving)

        editor.model.stage(DocumentRole.SERVICE_MANUAL, picked("late.pdf"))
        assertEquals("held while saving", listOf("manual.pdf" to null), editor.model.stagedLines())

        gate.complete(Unit)
        editor.model.state.first { !it.saving }
        val asset = graph.assets.all().single().id
        assertEquals(listOf(asset), editor.saved)
        assertEquals("saved only once nothing is staged", listOf(0), editor.stagedAtSaved)
        assertEquals(listOf("manual.pdf"), graph.attachments.all().map { it.displayName })
    }

    /**
     * C6 (B-1, R67-8): Remove is held too while the copies run, so a line is copied exactly as it is
     * shown — never hidden and attached anyway, never brought back by an earlier line's failure. Once
     * the copies end Remove works again, and what it removed is never attached.
     */
    @Test fun aRemoveDuringTheCopiesIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val editor = intake(storage = gatedStorage(gate))
        editor.model.onName("Hot tub")
        editor.model.stage(DocumentRole.PURCHASE_INVOICE_OR_RECEIPT, picked("receipt.pdf"))
        editor.model.stage(DocumentRole.USER_MANUAL, picked("revoked.pdf", open = { throw SecurityException("grant gone") }))
        editor.model.stage(DocumentRole.SERVICE_MANUAL, picked("service.pdf"))
        editor.model.save()
        editor.model.state.first { it.editing }
        advanceUntilIdle()
        assertTrue("the first copy waits at the gate", editor.model.state.value.saving)

        editor.model.state.value.staged.forEach(editor.model::unstage)
        assertEquals(
            "held while saving",
            listOf("receipt.pdf" to null, "revoked.pdf" to null, "service.pdf" to null),
            editor.model.stagedLines(),
        )

        gate.complete(Unit)
        editor.model.state.first { !it.saving }
        assertEquals(
            listOf("revoked.pdf" to "Could not add revoked.pdf", "service.pdf" to null),
            editor.model.stagedLines(),
        )
        assertEquals(listOf("receipt.pdf"), graph.attachments.all().map { it.displayName })
        assertTrue(editor.saved.isEmpty())

        // Not saving any more: Remove is honoured, and nothing it removed comes back or attaches.
        editor.model.state.value.staged.forEach(editor.model::unstage)
        assertTrue(editor.model.state.value.staged.isEmpty())
        editor.model.saveAndWait()
        assertEquals(listOf("receipt.pdf"), graph.attachments.all().map { it.displayName })
        assertEquals(1, editor.saved.size)
    }

    /**
     * C6 (M-2): a copy that lands before one that fails is done — its line goes, and its file shows
     * under its role while the editor stays open — so the retry copies only what is still staged: no
     * duplicate row, no duplicate bytes.
     */
    @Test fun aSecondFileFailingKeepsTheFirstAndRetriesOnlyTheRest() = runTest {
        val inner = InMemoryAttachmentStore()
        var puts = 0
        val counting = object : AttachmentStore by inner {
            override suspend fun put(locator: String, source: ByteSource): StoredBytes =
                inner.put(locator, source).also { puts += 1 }
        }
        val storage = object : AttachmentStorage {
            override fun state(): StoreState = READY
            override fun store(): AttachmentStore = counting
        }
        var grantGone = true
        val editor = intake(storage = storage)
        editor.model.onName("Hot tub")
        editor.model.stage(DocumentRole.PURCHASE_INVOICE_OR_RECEIPT, picked("receipt.pdf"))
        editor.model.stage(
            DocumentRole.USER_MANUAL,
            picked("manual.pdf", open = {
                if (grantGone) throw SecurityException("grant gone") else ByteArrayInputStream("manual".toByteArray())
            }),
        )
        editor.model.stage(DocumentRole.SERVICE_MANUAL, picked("service.pdf"))
        editor.model.saveAndWait()

        val asset = graph.assets.all().single().id
        assertEquals(
            "the copied line is gone, the failed one says why, the rest wait",
            listOf("manual.pdf" to "Could not add manual.pdf", "service.pdf" to null),
            editor.model.stagedLines(),
        )
        assertEquals(listOf("receipt.pdf"), graph.attachments.all().map { it.displayName })
        assertEquals(1, puts)
        assertEquals(
            "the copied file is listed under its role while the editor stays open",
            listOf(AttachedDocument(DocumentRole.PURCHASE_INVOICE_OR_RECEIPT, "receipt.pdf")),
            editor.model.state.value.attached,
        )
        assertTrue(editor.saved.isEmpty())

        grantGone = false
        editor.model.saveAndWait()
        assertEquals(
            "each file once: no duplicate row",
            listOf("manual.pdf", "receipt.pdf", "service.pdf"),
            graph.attachments.all().map { it.displayName }.sorted(),
        )
        assertEquals("no duplicate bytes", 3, puts)
        assertEquals(3, inner.files.size)
        assertEquals(listOf(asset), editor.saved)
        assertTrue(editor.model.state.value.staged.isEmpty())
    }

    /**
     * C6 (R-1): a picked file whose name and size are still being asked of the provider holds Save,
     * so a tap in that moment cannot leave the file behind; once the answer lands and is staged, Save
     * copies it.
     */
    @Test fun aPickStillBeingLookedUpHoldsSaveUntilItLands() = runTest {
        val editor = intake()
        editor.model.onName("Hot tub")
        val answer = CompletableDeferred<Unit>()
        editor.model.stagePicked(DocumentRole.USER_MANUAL) {
            answer.await()
            picked("manual.pdf")
        }
        advanceUntilIdle()
        assertFalse("Save is held while the pick is looked up", editor.model.state.value.canSave)

        editor.model.save()
        advanceUntilIdle()
        assertTrue("nothing written while the pick is looked up", graph.assets.all().isEmpty())
        assertTrue(editor.saved.isEmpty())

        answer.complete(Unit)
        editor.model.state.first { it.canSave }
        assertEquals(listOf("manual.pdf" to null), editor.model.stagedLines())

        editor.model.saveAndWait()
        val asset = graph.assets.all().single().id
        assertEquals(listOf(asset), editor.saved)
        assertEquals(listOf("manual.pdf"), graph.attachments.all().map { it.displayName })
        assertTrue(editor.model.state.value.staged.isEmpty())
    }

    /**
     * C6 (M1): a new MANUAL asset whose copy failed is saved again after an edit. The stored mode was
     * refreshed from the written row, so MANUAL → MANUAL sends no phase and the save lands.
     */
    @Test fun aManualNewAssetWithAFailingCopyRetriesToCompletion() = runTest {
        val storage = FakeAttachmentStorage(state = LOST)
        val editor = intake(storage = storage)
        editor.model.onName("Generator")
        editor.model.onSeasonMode(SeasonMode.MANUAL)
        editor.model.onManualPhase(SeasonPhase.IN_SEASON)
        editor.model.stage(DocumentRole.SERVICE_MANUAL, picked("service.pdf"))
        editor.model.saveAndWait()

        val written = graph.assets.all().single()
        assertEquals(SeasonMode.MANUAL, written.seasonMode)
        assertEquals(SeasonMode.MANUAL, editor.model.state.value.storedSeasonMode)
        assertFalse("S35 is not asked of an asset already MANUAL", editor.model.state.value.asksManualPhase)
        assertTrue(editor.saved.isEmpty())

        editor.model.onName("Generator 2")
        storage.state = READY
        editor.model.saveAndWait()
        assertEquals(listOf(written.id), editor.saved)
        assertEquals("Generator 2", graph.assets.get(written.id)!!.name)
        assertEquals(1, graph.assets.all().size)
        assertEquals(listOf("service.pdf"), graph.attachments.all().map { it.displayName })
    }

    /**
     * C6 step 3 and #78: the question decided at the write waits while a file is staged, is asked
     * once, and its answer never finishes the editor over a file still staged.
     */
    @Test fun theReconcileQuestionIsAskedOnceAndOnlyWhenNothingIsStaged() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.schedules.upsert(weekly("s1", "gen", ServicePolicy.CONTINUOUS))
        val storage = FakeAttachmentStorage(state = LOST)
        val editor = intake(AssetId("gen"), storage = storage)
        editor.model.stage(DocumentRole.USER_MANUAL, picked("manual.pdf"))
        editor.model.saveAs(SeasonMode.MANUAL)

        // Written, the copy failed: the question is held, not asked.
        assertEquals(SeasonMode.MANUAL, graph.assets.get(AssetId("gen"))!!.seasonMode)
        assertEquals(null, editor.model.prompt.value)
        assertTrue(editor.saved.isEmpty())

        // Copied, nothing staged: now it is asked.
        storage.state = READY
        editor.model.saveAndWait()
        assertEquals(EditPrompt.ReconcileSchedules(1), editor.model.prompt.value)
        assertTrue(editor.saved.isEmpty())

        // A file staged under the question: the answer takes the question down and does not finish over it.
        editor.model.stage(DocumentRole.SERVICE_MANUAL, picked("service.pdf"))
        editor.model.keepSchedules()
        assertEquals(null, editor.model.prompt.value)
        assertTrue(editor.saved.isEmpty())

        // The next Save copies it and finishes, without asking again.
        editor.model.saveAndWait()
        assertEquals(null, editor.model.prompt.value)
        assertEquals(listOf(AssetId("gen")), editor.saved)
        assertEquals(2, graph.attachments.count())
    }

    /** C6 step 3 and #78: "Review maintenance schedules" never finishes onto the schedules over a staged file either. */
    @Test fun reviewingTheSchedulesNeverFinishesOverAStagedFile() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.schedules.upsert(weekly("s1", "gen", ServicePolicy.CONTINUOUS))
        val editor = intake(AssetId("gen"))
        val reviews = mutableListOf<AssetId>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            editor.model.review.collect { reviews += it }
        }
        editor.model.saveAs(SeasonMode.MANUAL)
        assertEquals(EditPrompt.ReconcileSchedules(1), editor.model.prompt.value)

        // A file staged under the question: the answer takes the question down and does not finish over it.
        editor.model.stage(DocumentRole.USER_MANUAL, picked("manual.pdf"))
        editor.model.reviewSchedules()
        assertEquals(null, editor.model.prompt.value)
        assertTrue("never onto the schedules over a staged file", reviews.isEmpty())
        assertTrue(editor.saved.isEmpty())

        // The next Save copies it and finishes.
        editor.model.saveAndWait()
        assertEquals(listOf(AssetId("gen")), editor.saved)
        assertTrue(reviews.isEmpty())
        assertEquals(1, graph.attachments.count())
    }

    /**
     * #78 over C6: each write re-decides the held question. A retry that takes the asset back to
     * year-round has nothing left to ask about; a retry that keeps it in a season still asks, once.
     */
    @Test fun anEditedRetryReDecidesTheHeldQuestion() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.schedules.upsert(weekly("s1", "gen", ServicePolicy.CONTINUOUS))
        val storage = FakeAttachmentStorage(state = LOST)
        val back = intake(AssetId("gen"), storage = storage)
        back.model.stage(DocumentRole.USER_MANUAL, picked("manual.pdf"))
        back.model.saveAs(SeasonMode.CALENDAR)
        assertEquals(SeasonMode.CALENDAR, graph.assets.get(AssetId("gen"))!!.seasonMode)
        assertEquals("held while staged", null, back.model.prompt.value)

        back.model.onSeasonMode(SeasonMode.YEAR_ROUND)
        storage.state = READY
        back.model.saveAndWait()
        assertEquals(SeasonMode.YEAR_ROUND, graph.assets.get(AssetId("gen"))!!.seasonMode)
        assertEquals("nothing to ask of a year-round asset", null, back.model.prompt.value)
        assertEquals(listOf(AssetId("gen")), back.saved)

        graph.assets.upsert(assetRow("pump", name = "Pump"))
        graph.schedules.upsert(weekly("s2", "pump", ServicePolicy.CONTINUOUS))
        storage.state = LOST
        val kept = intake(AssetId("pump"), storage = storage)
        kept.model.stage(DocumentRole.USER_MANUAL, picked("pump manual.pdf"))
        kept.model.saveAs(SeasonMode.CALENDAR)
        assertEquals("held while staged", null, kept.model.prompt.value)

        kept.model.onName("Pool pump")
        storage.state = READY
        kept.model.saveAndWait()
        assertEquals("Pool pump", graph.assets.get(AssetId("pump"))!!.name)
        assertEquals("still in a season: still asked", EditPrompt.ReconcileSchedules(1), kept.model.prompt.value)
        assertTrue(kept.saved.isEmpty())
    }

    /** C6: a retry whose form has not changed since the write never runs the settings write again. */
    @Test fun anUnchangedRetryNeverCallsSaveAssetSettings() = runTest {
        val reads = CountingAssets(graph.assets)
        val storage = FakeAttachmentStorage(state = LOST)
        val editor = intake(storage = storage, saveSettings = saveSettingsOver(reads))
        editor.model.onName("Hot tub")
        editor.model.stage(DocumentRole.USER_MANUAL, picked("manual.pdf"))
        editor.model.saveAndWait()
        assertEquals(1, reads.reads)

        storage.state = READY
        editor.model.saveAndWait()
        assertEquals("no second settings write", 1, reads.reads)
        assertEquals(1, editor.saved.size)
        assertEquals(1, graph.attachments.count())

        // A seasonal asset: the write brings its mode back into the form as the stored mode. That is
        // not an edit — the stored mode is not the person's to type — so the retry still writes nothing.
        val seasonalReads = CountingAssets(graph.assets)
        val seasonal = intake(storage = storage, saveSettings = saveSettingsOver(seasonalReads))
        storage.state = LOST
        seasonal.model.onName("Pool")
        seasonal.model.onSeasonMode(SeasonMode.CALENDAR)
        seasonal.model.onSeasonStart("05-01")
        seasonal.model.onSeasonEnd("09-30")
        seasonal.model.stage(DocumentRole.USER_MANUAL, picked("pool manual.pdf"))
        seasonal.model.saveAndWait()
        assertEquals(1, seasonalReads.reads)
        assertEquals(SeasonMode.CALENDAR, seasonal.model.state.value.storedSeasonMode)

        storage.state = READY
        seasonal.model.saveAndWait()
        assertEquals("no second settings write on a seasonal asset either", 1, seasonalReads.reads)
        assertEquals(1, seasonal.saved.size)
        assertEquals(2, graph.attachments.count())
    }

    /** C6: a lost folder says so on the staged line, in the section's own words, and the affordances go. */
    @Test fun aLostFolderSentencesTheLine() = runTest {
        val storage = FakeAttachmentStorage()
        val editor = intake(storage = storage)
        editor.model.onName("Hot tub")
        editor.model.stage(DocumentRole.PURCHASE_INVOICE_OR_RECEIPT, picked("receipt.pdf"))
        storage.state = LOST
        editor.model.saveAndWait()

        assertEquals(listOf("receipt.pdf" to "The attachment folder is not available"), editor.model.stagedLines())
        assertFalse("the refusal is news about the folder", editor.model.state.value.offersDocuments)
        assertEquals(0, graph.attachments.count())
    }

    /**
     * C6: a copy that throws — a revoked grant at `open`, or a `put` that fails — names the file; the
     * first failure stops the rest; Remove, then Save, finishes.
     */
    @Test fun aThrowingCopySentencesTheLine() = runTest {
        val editor = intake()
        editor.model.onName("Hot tub")
        editor.model.stage(DocumentRole.USER_MANUAL, picked("revoked.pdf", open = { throw SecurityException("grant gone") }))
        editor.model.stage(DocumentRole.SERVICE_MANUAL, picked("service.pdf"))
        editor.model.saveAndWait()

        assertEquals(
            listOf("revoked.pdf" to "Could not add revoked.pdf", "service.pdf" to null),
            editor.model.stagedLines(),
        )
        assertEquals("stopped at the first failure", 0, graph.attachments.count())
        assertTrue(editor.saved.isEmpty())

        editor.model.unstage(editor.model.state.value.staged.first())
        editor.model.saveAndWait()
        assertEquals(listOf("service.pdf"), graph.attachments.all().map { it.displayName })
        assertEquals(1, editor.saved.size)

        val broken = object : AttachmentStorage {
            private val store = object : AttachmentStore by InMemoryAttachmentStore() {
                override suspend fun put(locator: String, source: ByteSource): StoredBytes =
                    throw StoreIoException("rigged put failure")
            }
            override fun state(): StoreState = READY
            override fun store(): AttachmentStore = store
        }
        val second = intake(storage = broken)
        second.model.onName("Pool pump")
        second.model.stage(DocumentRole.USER_MANUAL, picked("manual.pdf"))
        second.model.saveAndWait()
        assertEquals(listOf("manual.pdf" to "Could not add manual.pdf"), second.model.stagedLines())
    }

    /** R67-13: with no folder the three affordances are hidden; the re-read on return from Settings brings them back. */
    @Test fun withoutAFolderTheStateHidesTheAffordances() = runTest {
        val storage = FakeAttachmentStorage(state = StoreState.NotConfigured)
        val editor = intake(storage = storage)
        assertFalse(editor.model.state.value.offersDocuments)

        storage.state = LOST
        editor.model.refreshStore()
        advanceUntilIdle()
        assertFalse(editor.model.state.value.offersDocuments)

        storage.state = READY
        editor.model.refreshStore()
        advanceUntilIdle()
        assertTrue(editor.model.state.value.offersDocuments)
    }

    /** R67-6: the asset's role-tagged files are listed read-only, newest first; a file without a role is not a key document. */
    @Test fun theEditorListsTheAssetsKeyDocumentsByRole() = runTest {
        graph.assets.upsert(assetRow("tub", name = "Hot tub"))
        listOf(
            Triple("old manual.pdf", DocumentRole.USER_MANUAL, "2024-01-01"),
            Triple("manual.pdf", DocumentRole.USER_MANUAL, "2025-06-01"),
            Triple("receipt.pdf", DocumentRole.PURCHASE_INVOICE_OR_RECEIPT, null),
            Triple("photo.jpg", null, "2025-01-01"),
        ).forEach { (name, role, on) ->
            graph.addAttachment.run(
                AttachmentOwner.OfAsset(AssetId("tub")),
                AddAttachmentCommand(displayName = name, mimeType = "application/pdf", capturedOn = on, role = role),
                ByteSource { ByteArrayInputStream(name.toByteArray()) },
            )
        }

        val editor = intake(AssetId("tub"))
        assertEquals(
            listOf(
                AttachedDocument(DocumentRole.USER_MANUAL, "manual.pdf"),
                AttachedDocument(DocumentRole.USER_MANUAL, "old manual.pdf"),
                AttachedDocument(DocumentRole.PURCHASE_INVOICE_OR_RECEIPT, "receipt.pdf"),
            ),
            editor.model.state.value.attached,
        )
    }

    private companion object {
        val READY = StoreState.Ready("Attachments", "com.example.provider")
        val LOST = StoreState.AccessLost("Attachments")

        /** #71's `T`. */
        val T71: LocalDate = LocalDate.parse("2026-04-15")

        /** A real-time bound on each #71 wait: Room answers the list's flows on its own threads. */
        const val WAIT_MS = 5_000L

        /** Past the list's 5s subscription grace, on the virtual clock the view model's scope runs on. */
        const val PAST_THE_GRACE_MS = 6_000L
    }
}
