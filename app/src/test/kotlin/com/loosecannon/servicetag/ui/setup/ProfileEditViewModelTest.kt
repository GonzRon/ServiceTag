package com.loosecannon.servicetag.ui.setup

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.usecase.ProfileCommand
import com.loosecannon.servicetag.core.usecase.ProfileConsumableInput
import com.loosecannon.servicetag.core.usecase.SupplyItemCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.ui.supplies.SUPPLY_ITEM_GONE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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

/**
 * The profile editor's form state against a Room-backed [FakeGraph]. Validation belongs to
 * `SaveProfile`; what is proved here is that the form sends the fields in the order the list shows
 * them, hands back the child ids it loaded so an edit is an edit, and only ever offers a field the
 * domain would accept.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileEditViewModelTest {

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

    private fun model(assetId: AssetId, profileId: ProfileId? = null) = ProfileEditViewModel(
        graph.profiles, graph.definitions, graph.assets, graph.supplyItems,
        graph.saveProfile, graph.archiveProfile, graph.deleteProfile,
        assetId, profileId,
    )

    @Test fun aNewActionSavesItsFieldsInTheOrderTheyWereChosen() = runTest {
        val ro = graph.createAsset.run("RO unit", "Water", templateKey = "ro_water")
        val defs = graph.definitions.forAsset(ro.id).associateBy(MeasurementDefinition::key)
        val vm = model(ro.id)
        val saved = mutableListOf<ProfileId>()
        backgroundScope.launch { vm.saved.collect { saved += it } }
        vm.state.first { it.loaded }

        vm.onName("Membrane check")
        vm.onKind(EventKind.INSPECTION)
        vm.addField(defs.getValue("tds_output").id)
        vm.addField(defs.getValue("tds_prefilter").id)
        vm.setRequired(defs.getValue("tds_prefilter").id, true)
        vm.addConsumable()
        vm.onConsumable(0, name = "Sediment filter", quantity = "1", unit = "ea")

        vm.save()
        vm.state.first { !it.saving && it.problems.isEmpty() }

        val stored = graph.profiles.forAsset(ro.id).single { it.name == "Membrane check" }
        assertEquals(EventKind.INSPECTION, stored.eventKind)
        // A title nobody typed in is the name: the form sends blank and the use case defaults it.
        assertEquals("Membrane check", stored.defaultTitle)
        assertEquals(
            listOf(defs.getValue("tds_output").id, defs.getValue("tds_prefilter").id),
            stored.fields.sortedBy { it.sortOrder }.map { it.definitionId },
        )
        assertEquals(
            listOf(false, true),
            stored.fields.sortedBy { it.sortOrder }.map { it.required },
        )
        val material = stored.consumables.single()
        assertEquals("Sediment filter", material.name)
        assertEquals(1.0, material.defaultQuantity!!, 1e-9)
        assertEquals("ea", material.unit)
        assertEquals(listOf(stored.id), saved)
    }

    /**
     * The in-flight guard, proved rather than assumed from frame timing: [FakeGraph.saveGate]
     * parks the write itself, so the second `save()` provably arrives while the first one is
     * still suspended on it, not merely "probably" in the same frame.
     */
    @Test fun aSecondSaveWhileTheFirstIsStillInFlightIsDropped() = runTest {
        val ro = graph.createAsset.run("RO unit", "Water", templateKey = "ro_water")
        val vm = model(ro.id)
        val saved = mutableListOf<ProfileId>()
        backgroundScope.launch { vm.saved.collect { saved += it } }
        vm.state.first { it.loaded }
        vm.onName("Membrane check")

        val gate = CompletableDeferred<Unit>()
        graph.saveGate = gate

        vm.save()
        assertTrue(vm.state.value.saving)
        // The write above is parked on `gate`, so this one provably lands while it is in flight.
        vm.save()
        assertTrue(vm.state.value.saving)

        gate.complete(Unit)
        vm.state.first { !it.saving && it.problems.isEmpty() }

        assertEquals(1, graph.profiles.forAsset(ro.id).count { it.name == "Membrane check" })
        assertEquals(1, saved.size)
    }

    @Test fun editingAnActionKeepsTheFieldAndMaterialIdsItLoaded() = runTest {
        val ro = graph.createAsset.run("RO unit", "Water", templateKey = "ro_water")
        val defs = graph.definitions.forAsset(ro.id).associateBy(MeasurementDefinition::key)
        val first = model(ro.id)
        first.state.first { it.loaded }
        first.onName("Filter swap")
        first.addField(defs.getValue("tds_output").id)
        first.addConsumable()
        first.onConsumable(0, name = "Carbon block", quantity = "2", unit = "ea")
        first.save()
        first.state.first { !it.saving && it.problems.isEmpty() }

        val created = graph.profiles.forAsset(ro.id).single { it.name == "Filter swap" }
        val fieldId = created.fields.single().id
        val consumableId = created.consumables.single().id

        val vm = model(ro.id, created.id)
        val loaded = vm.state.first { it.loaded }
        assertTrue(loaded.editing)
        assertEquals(listOf(defs.getValue("tds_output").id), loaded.fields.map { it.definition.id })
        assertEquals(listOf(consumableId), loaded.consumables.map { it.id })
        assertEquals("2", loaded.consumables.single().quantity)

        vm.onName("Filter change")
        vm.onConsumable(0, name = "Carbon block (10 in)")
        vm.addField(defs.getValue("tds_prefilter").id)
        vm.save()
        vm.state.first { !it.saving && it.problems.isEmpty() }

        val after = graph.profiles.get(created.id)!!
        assertEquals("Filter change", after.name)
        // A rename is a rename: the rows a person can see keep the identity they already had.
        assertEquals(
            fieldId,
            after.fields.single { it.definitionId == defs.getValue("tds_output").id }.id,
        )
        assertEquals(consumableId, after.consumables.single().id)
        assertEquals("Carbon block (10 in)", after.consumables.single().name)
        assertEquals(2, after.fields.size)
    }

    @Test fun aNameAnotherActionAlreadyUsesLandsUnderTheNameField() = runTest {
        val ro = graph.createAsset.run("RO unit", "Water", templateKey = "ro_water")
        val vm = model(ro.id)
        vm.state.first { it.loaded }

        vm.onName("tds test")   // the seeded action, in different case
        vm.save()

        val refused = vm.state.first { !it.saving && it.problems.isNotEmpty() }
        assertEquals("Another action already uses this name", refused.problems[ProfileForm.NAME])
        assertEquals(1, graph.profiles.forAsset(ro.id).size)

        // Typing in the name clears its own mark, so nothing is red before the next try.
        vm.onName("TDS test (weekly)")
        assertTrue(vm.state.value.problems.isEmpty())
    }

    @Test fun aBlankNameIsNamedUnderTheNameField() = runTest {
        val thing = graph.createAsset.run("Thing", "Misc")
        val vm = model(thing.id)
        vm.state.first { it.loaded }

        vm.save()

        val refused = vm.state.first { !it.saving && it.problems.isNotEmpty() }
        assertEquals("Give the action a name", refused.problems[ProfileForm.NAME])
        assertTrue(graph.profiles.forAsset(thing.id).isEmpty())
    }

    @Test fun thePickerOffersOnlyUnarchivedEnteredReadingsThatAreNotChosen() = runTest {
        val ro = graph.createAsset.run("RO unit", "Water", templateKey = "ro_water")
        val defs = graph.definitions.forAsset(ro.id).associateBy(MeasurementDefinition::key)
        val vm = model(ro.id)
        vm.state.first { it.loaded }

        // The derived reading is computed, never entered, so it is never on offer (spec §6).
        assertEquals(
            listOf("tds_prefilter", "tds_post_membrane", "tds_output"),
            vm.state.value.available.map(MeasurementDefinition::key),
        )

        vm.addField(defs.getValue("tds_post_membrane").id)
        assertEquals(
            listOf("tds_prefilter", "tds_output"),
            vm.state.value.available.map(MeasurementDefinition::key),
        )
        // Adding the same reading twice is not a thing the picker can produce, and not a thing the
        // form will hold either.
        vm.addField(defs.getValue("tds_post_membrane").id)
        assertEquals(1, vm.state.value.fields.size)

        vm.removeField(defs.getValue("tds_post_membrane").id)
        assertEquals(3, vm.state.value.available.size)

        // An archived reading drops off the picker...
        graph.archiveDefinition.run(defs.getValue("tds_output").id, archived = true)
        val fresh = model(ro.id)
        fresh.state.first { it.loaded }
        assertEquals(
            listOf("tds_prefilter", "tds_post_membrane"),
            fresh.state.value.available.map(MeasurementDefinition::key),
        )

        // ...but an action that already carries it keeps it, badge and all (spec §6).
        val seeded = graph.profiles.forAsset(ro.id).single()
        val editing = model(ro.id, seeded.id)
        val loaded = editing.state.first { it.loaded }
        assertTrue(loaded.fields.any { it.definition.key == "tds_output" })
        assertTrue(loaded.available.none { it.key == "tds_output" })

        editing.onName("TDS test (weekly)")
        editing.save()
        editing.state.first { !it.saving && it.problems.isEmpty() }
        assertEquals(3, graph.profiles.get(seeded.id)!!.fields.size)
    }

    @Test fun movingAFieldReordersWhatIsSaved() = runTest {
        val ro = graph.createAsset.run("RO unit", "Water", templateKey = "ro_water")
        val seeded = graph.profiles.forAsset(ro.id).single()
        val original = seeded.fields.sortedBy { it.sortOrder }.map { it.definitionId }
        val vm = model(ro.id, seeded.id)
        vm.state.first { it.loaded }

        vm.moveField(2, -1)
        // A move off either end does nothing rather than throwing the list away.
        vm.moveField(0, -1)
        vm.moveField(2, 1)
        assertEquals(
            listOf(original[0], original[2], original[1]),
            vm.state.value.fields.map { it.definition.id },
        )

        vm.save()
        vm.state.first { !it.saving && it.problems.isEmpty() }
        assertEquals(
            listOf(original[0], original[2], original[1]),
            graph.profiles.get(seeded.id)!!.fields.sortedBy { it.sortOrder }.map { it.definitionId },
        )
    }

    @Test fun deletingAnActionSaysSoAndLeavesTheAssetsReadingsAlone() = runTest {
        val ro = graph.createAsset.run("RO unit", "Water", templateKey = "ro_water")
        val seeded = graph.profiles.forAsset(ro.id).single()
        val vm = model(ro.id, seeded.id)
        val gone = backgroundScope.async { vm.deleted.first() }
        vm.state.first { it.loaded }

        vm.delete()

        gone.await()
        assertTrue(graph.profiles.forAsset(ro.id).isEmpty())
        assertEquals(4, graph.definitions.forAsset(ro.id).size)
    }

    @Test fun archiveFromTheEditorRetiresTheActionAndUnarchiveBringsItBack() = runTest {
        val ro = graph.createAsset.run("RO unit", "Water", templateKey = "ro_water")
        val seeded = graph.profiles.forAsset(ro.id).single()
        val vm = model(ro.id, seeded.id)
        vm.state.first { it.loaded }

        vm.archive(archived = true)
        assertTrue(vm.state.first { it.archived }.archived)
        assertEquals(3, graph.profiles.get(seeded.id)!!.fields.size)

        vm.archive(archived = false)
        assertFalse(vm.state.first { !it.archived }.archived)
    }

    @Test fun aMaterialWithNoNameIsNamedUnderItsOwnRow() = runTest {
        val thing = graph.createAsset.run("Thing", "Misc")
        val vm = model(thing.id)
        vm.state.first { it.loaded }

        vm.onName("Top up")
        vm.addConsumable()
        vm.addConsumable()
        vm.onConsumable(1, name = "Chlorine", unit = "g")
        vm.save()

        val refused = vm.state.first { !it.saving && it.problems.isNotEmpty() }
        assertEquals(
            "Needs a name, and a quantity of 0 or more",
            refused.problems[ProfileForm.consumable(0)],
        )
        assertTrue(graph.profiles.forAsset(thing.id).isEmpty())

        // A quantity that is not a number is caught before the command is built, on its own row.
        vm.onConsumable(0, name = "Acid", quantity = "lots")
        vm.save()
        val again = vm.state.first { !it.saving && it.problems.isNotEmpty() }
        assertEquals(
            "Quantity must be a number, or empty",
            again.problems[ProfileForm.consumable(0)],
        )

        vm.onConsumable(0, quantity = "50")
        vm.save()
        vm.state.first { !it.saving && it.problems.isEmpty() }
        val stored = graph.profiles.forAsset(thing.id).single()
        assertEquals(
            listOf("Acid", "Chlorine"),
            stored.consumables.sortedBy { it.sortOrder }.map { it.name },
        )
    }

    @Test fun removingAMaterialDropsTheMarksThatWouldPointAtTheWrongRow() = runTest {
        val thing = graph.createAsset.run("Thing", "Misc")
        val vm = model(thing.id)
        vm.state.first { it.loaded }

        vm.onName("Top up")
        vm.addConsumable()
        vm.addConsumable()
        vm.onConsumable(1, name = "Chlorine", unit = "g")
        vm.save()
        vm.state.first { !it.saving && it.problems.isNotEmpty() }

        vm.removeConsumable(0)
        assertTrue(vm.state.value.problems.isEmpty())
        assertEquals(listOf("Chlorine"), vm.state.value.consumables.map { it.name })

        vm.save()
        vm.state.first { !it.saving && it.problems.isEmpty() }
        assertEquals("Chlorine", graph.profiles.forAsset(thing.id).single().consumables.single().name)
    }

    @Test fun theDefaultTitleFollowsTheNameUntilItIsTypedIn() = runTest {
        val thing = graph.createAsset.run("Thing", "Misc")
        val vm = model(thing.id)
        vm.state.first { it.loaded }

        vm.onName("Oil change")
        assertEquals("Oil change", vm.state.value.defaultTitle)
        assertFalse(vm.state.value.titleEdited)

        vm.onTitle("Engine oil + filter")
        vm.onName("Oil service")
        assertTrue(vm.state.value.titleEdited)
        assertEquals("Engine oil + filter", vm.state.value.defaultTitle)

        vm.save()
        vm.state.first { !it.saving && it.problems.isEmpty() }
        val stored = graph.profiles.forAsset(thing.id).single()
        assertEquals("Oil service", stored.name)
        assertEquals("Engine oil + filter", stored.defaultTitle)
    }

    // --- #15 (C19, C20): a Materials row keeps its SupplyItem link, invisibly --------------------

    private suspend fun supplyItem(name: String): SupplyId =
        graph.saveSupplyItem.run(null, SupplyItemCommand(name, "", "", "", "", "", "", emptyList())).item.id

    /** One REPLACEMENT quick action on [assetId] with [lines] (name to link), stored through `SaveProfile`. */
    private suspend fun replaceAction(assetId: AssetId, vararg lines: Pair<String, SupplyId?>) =
        graph.saveProfile.run(
            null,
            ProfileCommand(
                assetId, "Replace filters", EventKind.REPLACEMENT, "", emptyList(),
                lines.map { (name, link) -> ProfileConsumableInput(null, name, 1.0, "ea", link) },
            ),
        )

    /**
     * Row 43: a line loaded with a link carries it through an edit of its words and back to the save, and an
     * unlinked line beside it stays unlinked. The link is never drawn or re-derived here (B8 draws it).
     */
    @Test fun aLoadedLinkIsSavedBack() = runTest {
        val ro = graph.createAsset.run("Example RO System", "Water")
        val cartridge = supplyItem("Example Prefilter Cartridge")
        val created = replaceAction(ro.id, "Prefilter cartridge" to cartridge, "O-ring grease" to null)

        val vm = model(ro.id, created.id)
        val loaded = vm.state.first { it.loaded }
        assertEquals(listOf(cartridge, null), loaded.consumables.map { it.supplyId })

        vm.onConsumable(0, name = "Prefilter cartridge (10 in)", quantity = "2", unit = "pcs")
        assertEquals(cartridge, vm.state.value.consumables.first().supplyId)
        vm.save()
        vm.state.first { !it.saving && it.problems.isEmpty() }

        val after = graph.profiles.get(created.id)!!.consumables.sortedBy { it.sortOrder }
        assertEquals(listOf(cartridge, null), after.map { it.supplyId })
        assertEquals(listOf("Prefilter cartridge (10 in)", "O-ring grease"), after.map { it.name })
        assertEquals(created.consumables.sortedBy { it.sortOrder }.map { it.id }, after.map { it.id })
    }

    /** Row 43: a row someone adds starts unlinked and is saved unlinked; the loaded link keeps its own row. */
    @Test fun anAddedRowIsUnlinked() = runTest {
        val ro = graph.createAsset.run("Example RO System", "Water")
        val cartridge = supplyItem("Example Prefilter Cartridge")
        val created = replaceAction(ro.id, "Prefilter cartridge" to cartridge)

        val vm = model(ro.id, created.id)
        vm.state.first { it.loaded }
        vm.addConsumable()
        vm.onConsumable(1, name = "Example Prefilter Cartridge", quantity = "1", unit = "ea")
        assertEquals(listOf(cartridge, null), vm.state.value.consumables.map { it.supplyId })
        vm.save()
        vm.state.first { !it.saving && it.problems.isEmpty() }

        val after = graph.profiles.get(created.id)!!.consumables.sortedBy { it.sortOrder }
        // The added row is named exactly as the SupplyItem and is still unlinked: no name ever links (C37).
        assertEquals(listOf(cartridge, null), after.map { it.supplyId })
    }

    /**
     * Row 40 (C20, C-2): a line whose SupplyItem is gone — only a race, since nothing deletes one (R15-5) — is
     * refused by `SaveProfile`, and the form marks that row with P15-20; nothing is written.
     */
    @Test fun anUnknownSupplyItemMarksItsRowWithP15_20() = runTest {
        val ro = graph.createAsset.run("Example RO System", "Water")
        val created = replaceAction(ro.id, "Prefilter cartridge" to null, "RO membrane" to null)
        val second = created.consumables.sortedBy { it.sortOrder }[1].id
        // Written straight to the store: the column has no foreign key (R79-4), so a link can outlive its row.
        graph.profiles.upsert(
            created.copy(
                consumables = created.consumables.map {
                    if (it.id == second) it.copy(supplyId = SupplyId("s-gone")) else it
                },
            ),
        )
        val stored = graph.profiles.get(created.id)!!

        val vm = model(ro.id, created.id)
        vm.state.first { it.loaded }
        vm.onName("Replace both filters")
        vm.save()
        val refused = vm.state.first { !it.saving }

        assertEquals(mapOf(ProfileForm.consumable(1) to SUPPLY_ITEM_GONE), refused.problems)
        assertEquals(stored, graph.profiles.get(created.id))
    }

    // --- #15 (C34, row 63): the line controls — a pick, "Remove link", and what the picker offers ----------

    /** A catalog item carrying a preferred unit, the one field a pick may copy onto a row besides the name. */
    private suspend fun catalogItem(name: String, preferredUnit: String): SupplyId =
        graph.saveSupplyItem.run(null, SupplyItemCommand(name, "", "", "", "", preferredUnit, "", emptyList())).item.id

    /**
     * Row 63 (C34): a pick links the row and fills its name only while the name is blank and its unit — from the
     * item's preferred unit — only while the unit is blank. Typed words are never overwritten, the quantity is never
     * touched, and the save stores exactly what the rows show.
     */
    @Test fun aPickFillsOnlyABlankNameAndUnit() = runTest {
        val ro = graph.createAsset.run("Example RO System", "Water")
        val cartridge = catalogItem("Example Prefilter Cartridge", preferredUnit = "pcs")
        val vm = model(ro.id)
        vm.state.first { it.loaded && it.supplyChoices.isNotEmpty() }

        vm.onName("Replace filters")
        vm.addConsumable()                                   // 0: blank — the pick fills its name and its unit
        vm.addConsumable()                                   // 1: name and unit typed — the pick fills neither
        vm.addConsumable()                                   // 2: name typed, unit blank — the pick fills the unit
        vm.onConsumable(1, name = "Prefilter (10 in)", quantity = "2", unit = "ea")
        vm.onConsumable(2, name = "Spare prefilter", quantity = "1")

        (0..2).forEach { vm.linkSupply(it, cartridge) }

        val rows = vm.state.value.consumables
        assertEquals(listOf(cartridge, cartridge, cartridge), rows.map { it.supplyId })
        assertEquals(listOf("Example Prefilter Cartridge", "Prefilter (10 in)", "Spare prefilter"), rows.map { it.name })
        assertEquals(listOf("pcs", "ea", "pcs"), rows.map { it.unit })
        assertEquals(listOf("", "2", "1"), rows.map { it.quantity })

        vm.save()
        vm.state.first { !it.saving && it.problems.isEmpty() }

        val stored = graph.profiles.forAsset(ro.id).single().consumables.sortedBy { it.sortOrder }
        assertEquals(listOf(cartridge, cartridge, cartridge), stored.map { it.supplyId })
        assertEquals(listOf("Example Prefilter Cartridge", "Prefilter (10 in)", "Spare prefilter"), stored.map { it.name })
        assertEquals(listOf("pcs", "ea", "pcs"), stored.map { it.unit })
    }

    /**
     * Row 63 (C34): "Remove link" clears that row's link and nothing else — its id, name, quantity and unit stay as
     * they were, the other row keeps its own link, and the save stores the row unlinked.
     */
    @Test fun unlinkClearsOnlyTheLink() = runTest {
        val ro = graph.createAsset.run("Example RO System", "Water")
        val cartridge = supplyItem("Example Prefilter Cartridge")
        val membrane = supplyItem("Example RO Membrane")
        val created = replaceAction(ro.id, "Prefilter cartridge" to cartridge, "RO membrane" to membrane)

        val vm = model(ro.id, created.id)
        val loaded = vm.state.first { it.loaded }
        vm.unlinkSupply(0)

        val rows = vm.state.value.consumables
        assertEquals(loaded.consumables[0].copy(supplyId = null), rows[0])
        assertEquals(loaded.consumables[1], rows[1])

        vm.save()
        vm.state.first { !it.saving && it.problems.isEmpty() }

        val before = created.consumables.sortedBy { it.sortOrder }
        val after = graph.profiles.get(created.id)!!.consumables.sortedBy { it.sortOrder }
        assertEquals(listOf(null, membrane), after.map { it.supplyId })
        assertEquals(before.map { it.copy(supplyId = null) }, after.map { it.copy(supplyId = null) })
    }

    /**
     * Row 63 (C32, R15-6): the picker is offered the unarchived SupplyItems only, in the Supplies list's order — the
     * filter is this host's — while a row linked to an archived item still names it, marked archived, so its line
     * can say so. Unarchiving it puts it back on offer, live.
     */
    @Test fun thePickerOffersUnarchivedOnly() = runTest {
        val ro = graph.createAsset.run("Example RO System", "Water")
        supplyItem("Example Sediment Cartridge")
        val carbon = supplyItem("Example Carbon Block")
        supplyItem("example prefilter cartridge")
        val created = replaceAction(ro.id, "Carbon block" to carbon)
        graph.archiveSupplyItem.run(carbon, archived = true)

        val vm = model(ro.id, created.id)
        val state = vm.state.first { it.loaded && it.supplies.size == 3 && it.supplies.getValue(carbon).archived }

        assertEquals(
            listOf("example prefilter cartridge", "Example Sediment Cartridge"),
            state.supplyChoices.map { it.name },
        )
        assertTrue(state.supplyChoices.none { it.archived })
        assertEquals(carbon, state.consumables.single().supplyId)
        assertEquals("Example Carbon Block", state.supplies.getValue(carbon).name)

        graph.archiveSupplyItem.run(carbon, archived = false)
        val live = vm.state.first { it.supplyChoices.size == 3 }
        assertEquals(
            listOf("Example Carbon Block", "example prefilter cartridge", "Example Sediment Cartridge"),
            live.supplyChoices.map { it.name },
        )
    }
}
