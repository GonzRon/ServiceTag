package com.loosecannon.servicetag.ui.supplies

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetSupply
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.usecase.AddAssetSupplyCommand
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.AssetSupplyResult
import com.loosecannon.servicetag.core.usecase.SupplyItemCommand
import com.loosecannon.servicetag.reminders.sourceFile
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.ui.transfer.`import`.TransferImportStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
 * #15 (C32, C33; row 62): the asset detail's Supplies section — its rows, the picker's offer, the role sheet and the
 * three writes — against the Room-backed `FakeGraph`, so the flows, the guarded applicability port and the three use
 * cases are the production ones. Every sentence is asserted through its constant (`SupplyStrings.kt`, P77-35's home),
 * so a re-worded string moves this test with it. Fixtures are fictional (the Global constraints).
 *
 * `viewModelScope` dispatches on `Dispatchers.Main`, an unconfined test dispatcher sharing the scheduler the graph's
 * queries and the model's writes run on, so every assertion waits for a state rather than reading `value` after a
 * write; `messages` has no replay, so a case that wants a line subscribes before the call that produces it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetSuppliesSectionViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph
    private val store = ViewModelStore()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        store.clear()
        graph.close()
        Dispatchers.resetMain()
    }

    private fun TestScope.model(assetId: AssetId): AssetSuppliesSectionViewModel {
        val factory = viewModelFactory {
            initializer {
                AssetSuppliesSectionViewModel(
                    assetId = assetId,
                    assetSupplies = graph.assetSupplies,
                    items = graph.supplyItems,
                    addAssetSupply = graph.addAssetSupply,
                    updateAssetSupply = graph.updateAssetSupply,
                    removeAssetSupply = graph.removeAssetSupply,
                    io = StandardTestDispatcher(scheduler),
                )
            }
        }
        val vm = ViewModelProvider.create(store, factory)["asset-supplies-${assetId.value}", AssetSuppliesSectionViewModel::class]
        backgroundScope.launch { vm.state.collect() }
        return vm
    }

    /** Cleared inside `runTest` as each case's last line, so the work behind a signal it waited for drains. */
    private fun TestScope.clearModels() {
        store.clear()
        advanceUntilIdle()
    }

    private suspend fun asset(name: String): Asset = graph.createAsset.run(AssetCommand(name = name, category = "Water"))

    private suspend fun item(name: String): SupplyItem = graph.saveSupplyItem.run(
        null,
        SupplyItemCommand(name, "", "", "", "", "", "", emptyList()),
    ).item

    private suspend fun take(asset: Asset, item: SupplyItem, role: String): AssetSupply =
        (graph.addAssetSupply.run(AddAssetSupplyCommand(asset.id, item.id, role)) as AssetSupplyResult.Ok).row

    private suspend fun AssetSuppliesSectionViewModel.loaded(rows: Int): AssetSuppliesState =
        state.first { it.rows.size == rows }

    /**
     * Row 62 (the counted RED: the picker list includes archived items). The picker is offered the catalog's
     * unarchived items only, in the Supplies list's `(name casefolded, id)` order (R15-6); an archived item this asset
     * already takes still draws as its row, marked.
     */
    @Test fun thePickerOffersUnarchivedOnly() = runTest {
        val system = asset("Example RO System")
        item("Example Sediment Cartridge")
        item("example prefilter cartridge")
        val carbon = item("Example Carbon Block")
        take(system, carbon, "Stage 3")
        graph.archiveSupplyItem.run(carbon.id, archived = true)

        val vm = model(system.id)
        val state = vm.state.first { it.rows.singleOrNull()?.archived == true }

        assertEquals(
            listOf("example prefilter cartridge", "Example Sediment Cartridge"),
            state.choices.map { it.name },
        )
        assertTrue(state.choices.none { it.archived })
        assertEquals(listOf("Example Carbon Block"), state.rows.map { it.name })
        clearModels()
    }

    /**
     * C33's order: `(role casefolded, SupplyItem name casefolded, id)`; each row carries the item's name, its role and
     * whether the item is archived, and nothing of another asset's.
     */
    @Test fun rowsAreInRoleThenNameOrderWithTheArchivedMarked() = runTest {
        val system = asset("Example RO System")
        val spare = asset("Example Spare Housing")
        val sediment = item("Example Sediment Cartridge")
        val prefilter = item("Example Prefilter Cartridge")
        val carbon = item("Example Carbon Block")
        take(system, prefilter, "Stage 2")
        take(system, sediment, "Spare")
        take(system, sediment, "stage 1")
        take(system, carbon, "Stage 1")
        take(spare, prefilter, "Stage 1")
        graph.archiveSupplyItem.run(carbon.id, archived = true)

        val vm = model(system.id)
        val state = vm.state.first { s -> s.rows.size == 4 && s.rows.any { it.archived } }

        assertEquals(
            listOf(
                "Spare" to "Example Sediment Cartridge",
                "Stage 1" to "Example Carbon Block",
                "stage 1" to "Example Sediment Cartridge",
                "Stage 2" to "Example Prefilter Cartridge",
            ),
            state.rows.map { it.role to it.name },
        )
        assertEquals(listOf(false, true, false, false), state.rows.map { it.archived })
        assertEquals(listOf(sediment.id, carbon.id, sediment.id, prefilter.id), state.rows.map { it.supplyId })
        clearModels()
    }

    /**
     * Row 62. The add glyph opens the picker; a pick opens the "Add supply" sheet with no role; Save passes the
     * command to `AddAssetSupply`, which stores the cleaned role, and the sheet closes.
     */
    @Test fun addPassesTheCommand() = runTest {
        val system = asset("Example RO System")
        val cartridge = item("Example Prefilter Cartridge")
        val vm = model(system.id)
        val choice = vm.state.first { it.choices.isNotEmpty() }.choices.single()

        vm.startAdd()
        assertTrue(vm.state.first { it.picking }.picking)
        vm.pick(choice)
        val sheet = vm.state.first { it.sheet != null }.sheet!!
        assertFalse(vm.state.value.picking)
        assertEquals(ADD_SUPPLY, sheet.title)
        assertEquals("Example Prefilter Cartridge", sheet.supplyName)
        assertEquals("", sheet.role)
        assertFalse(sheet.canSave)

        vm.onRole("  Stage   1 ")
        assertTrue(vm.state.first { it.sheet?.role == "  Stage   1 " }.sheet!!.canSave)
        vm.save()
        val state = vm.state.first { it.sheet == null && it.rows.size == 1 }

        val stored = graph.assetSupplies.forAsset(system.id).single()
        assertEquals(cartridge.id, stored.supplyId)
        assertEquals("Stage 1", stored.role)
        assertEquals(listOf("Stage 1"), state.rows.map { it.role })
        clearModels()
    }

    /** "Edit role" opens the same sheet titled P15-17 and prefilled; Save re-roles the row through `UpdateAssetSupply`. */
    @Test fun editRolePassesTheCommand() = runTest {
        val system = asset("Example RO System")
        val row = take(system, item("Example Prefilter Cartridge"), "Stage 1")
        val vm = model(system.id)

        vm.startEdit(vm.loaded(1).rows.single())
        val sheet = vm.state.first { it.sheet != null }.sheet!!
        assertEquals(EDIT_ROLE, sheet.title)
        assertEquals("Stage 1", sheet.role)
        vm.onRole("Stage 2")
        vm.save()
        vm.state.first { it.sheet == null && it.rows.singleOrNull()?.role == "Stage 2" }

        assertEquals("Stage 2", graph.assetSupplies.get(row.id)!!.role)
        clearModels()
    }

    /** Save is enabled while the cleaned role is non-blank (P15-19 is reserved: no sentence for a blank role). */
    @Test fun saveFollowsTheCleanedRole() = runTest {
        val system = asset("Example RO System")
        item("Example Prefilter Cartridge")
        val vm = model(system.id)
        vm.startAdd()
        vm.pick(vm.state.first { it.choices.isNotEmpty() }.choices.single())

        for (blank in listOf("", "   ", "   ")) {
            vm.onRole(blank)
            assertFalse("'$blank' cleans to nothing", vm.state.first { it.sheet?.role == blank }.sheet!!.canSave)
        }
        vm.onRole(" Spare ")
        assertTrue(vm.state.first { it.sheet?.role == " Spare " }.sheet!!.canSave)
        clearModels()
    }

    /**
     * Row 62. The same item in the same cleaned role on this asset is `Taken`: P15-18 under the field, the sheet open
     * with what was typed, nothing written; typing again clears the line.
     */
    @Test fun takenDrawsP15_18() = runTest {
        val system = asset("Example RO System")
        val cartridge = item("Example Prefilter Cartridge")
        take(system, cartridge, "Stage 1")
        val vm = model(system.id)
        vm.loaded(1)

        vm.startAdd()
        vm.pick(vm.state.value.choices.single())
        vm.onRole("Stage  1")
        vm.save()
        val sheet = vm.state.first { it.sheet?.problem != null }.sheet!!

        assertEquals(SUPPLY_ROLE_TAKEN, sheet.problem)
        assertEquals("Stage  1", sheet.role)
        assertFalse(sheet.saving)
        assertEquals(1, graph.assetSupplies.forAsset(system.id).size)
        vm.onRole("Stage 2")
        assertNull(vm.state.first { it.sheet?.role == "Stage 2" }.sheet!!.problem)
        clearModels()
    }

    /**
     * Row 62. Every arm that means the row or its item is no longer there — the asset gone, the item gone, the item
     * archived since the pick, the row removed under an open "Edit role" — draws P15-20 and writes nothing.
     */
    @Test fun everyGoneArmDrawsP15_20() = runTest {
        val system = asset("Example RO System")
        val cartridge = item("Example Prefilter Cartridge")

        // OwnerMissing: a section open on an asset that is not there.
        val orphan = model(AssetId("example-gone-asset"))
        orphan.startAdd()
        orphan.pick(orphan.state.first { it.choices.isNotEmpty() }.choices.single())
        orphan.onRole("Stage 1")
        orphan.save()
        assertEquals(SUPPLY_ITEM_GONE, orphan.state.first { it.sheet?.problem != null }.sheet!!.problem)

        // SupplyItemArchived: the item archived between the pick and Save.
        val vm = model(system.id)
        vm.startAdd()
        vm.pick(vm.state.first { it.choices.isNotEmpty() }.choices.single())
        graph.archiveSupplyItem.run(cartridge.id, archived = true)
        vm.onRole("Stage 1")
        vm.save()
        assertEquals(SUPPLY_ITEM_GONE, vm.state.first { it.sheet?.problem != null }.sheet!!.problem)
        assertTrue(graph.assetSupplies.forAsset(system.id).isEmpty())
        vm.dismissSheet()

        // NoSuchAssetSupply: the row removed under an open "Edit role".
        graph.archiveSupplyItem.run(cartridge.id, archived = false)
        val row = take(system, cartridge, "Stage 1")
        vm.startEdit(vm.loaded(1).rows.single())
        vm.state.first { it.sheet != null }
        graph.removeAssetSupply.run(row.id)
        vm.onRole("Stage 2")
        vm.save()
        assertEquals(SUPPLY_ITEM_GONE, vm.state.first { it.sheet?.problem != null }.sheet!!.problem)
        assertTrue(graph.assetSupplies.forAsset(system.id).isEmpty())
        vm.dismissSheet()

        // SupplyItemMissing: the whole catalog replaced between the pick and Save (a replacing import's wipe).
        vm.startAdd()
        vm.pick(vm.state.first { it.choices.isNotEmpty() && it.sheet == null }.choices.single())
        graph.uow.write { graph.supplyItems.deleteAll() }
        vm.onRole("Stage 1")
        vm.save()
        assertEquals(SUPPLY_ITEM_GONE, vm.state.first { it.sheet?.problem != null }.sheet!!.problem)
        assertTrue(graph.assetSupplies.forAsset(system.id).isEmpty())
        clearModels()
    }

    /** A re-role to the role the row already holds once cleaned answers `Unchanged`: the sheet closes as saved. */
    @Test fun unchangedClosesAsSaved() = runTest {
        val system = asset("Example RO System")
        val row = take(system, item("Example Prefilter Cartridge"), "Stage 1")
        val vm = model(system.id)

        vm.startEdit(vm.loaded(1).rows.single())
        vm.onRole(" Stage   1 ")
        vm.save()
        val state = vm.state.first { it.sheet == null }

        assertNull(state.sheet)
        val stored = graph.assetSupplies.get(row.id)!!
        assertEquals("Stage 1", stored.role)
        assertEquals("nothing was written", row.updatedAt, stored.updatedAt)
        clearModels()
    }

    /** Row 62. Remove calls `RemoveAssetSupply` at once — no dialog — and only that row leaves. */
    @Test fun removeCallsRemove() = runTest {
        val system = asset("Example RO System")
        val cartridge = item("Example Prefilter Cartridge")
        val first = take(system, cartridge, "Stage 1")
        val second = take(system, cartridge, "Spare")
        val vm = model(system.id)
        vm.loaded(2)

        vm.remove(first.id)
        val state = vm.state.first { it.rows.size == 1 }

        assertEquals(listOf(second.id), state.rows.map { it.id })
        assertEquals(listOf(second.id), graph.assetSupplies.forAsset(system.id).map { it.id })
        assertNull(state.sheet)
        assertFalse(state.picking)
        clearModels()
    }

    /**
     * Row 62. A read-only section (a held asset) offers nothing: no picker, no sheet, no remove — and a sheet open
     * when the asset turns read-only closes. The rows still draw.
     */
    @Test fun readOnlyOffersNothing() = runTest {
        val system = asset("Example RO System")
        val row = take(system, item("Example Prefilter Cartridge"), "Stage 1")
        val vm = model(system.id)
        val drawn = vm.loaded(1).rows.single()
        vm.startEdit(drawn)
        vm.state.first { it.sheet != null }

        vm.setReadOnly(true)
        assertNull(vm.state.first { !it.offersWrites }.sheet)
        vm.startAdd()
        vm.startEdit(drawn)
        vm.remove(row.id)
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.offersWrites)
        assertFalse(state.picking)
        assertNull(state.sheet)
        assertEquals(listOf(row.id), state.rows.map { it.id })
        assertEquals(listOf(row.id), graph.assetSupplies.forAsset(system.id).map { it.id })
        clearModels()
    }

    /**
     * A write refused because the asset was transferred out meanwhile says the shipped held sentence (P77-35): under
     * the sheet's field for a save, as a message for a remove; nothing is written either way.
     */
    @Test fun aHeldAssetsRefusalSaysTheHeldSentence() = runTest {
        val system = asset("Example RO System")
        val row = take(system, item("Example Prefilter Cartridge"), "Stage 1")
        val vm = model(system.id)
        vm.startEdit(vm.loaded(1).rows.single())
        vm.state.first { it.sheet != null }
        graph.transferRecords.append(
            TransferRecord(
                id = "out-example", assetId = system.id, kind = TransferKind.OUT, packId = "0f1e2d3c-pack",
                lineage = emptyList(), at = 1_790_510_400_000L, packSha256 = "ab".repeat(32),
                nameSnapshot = "Example RO System", note = "",
            ),
        )

        vm.onRole("Stage 2")
        vm.save()
        val sheet = vm.state.first { it.sheet?.problem != null }.sheet!!
        assertEquals(TransferImportStrings.ASSET_TRANSFERRED_OUT, sheet.problem)
        assertFalse(sheet.saving)
        assertEquals("Stage 1", graph.assetSupplies.get(row.id)!!.role)

        vm.dismissSheet()
        val line = async { vm.messages.first() }
        vm.remove(row.id)
        assertEquals(TransferImportStrings.ASSET_TRANSFERRED_OUT, line.await())
        assertEquals(listOf(row.id), graph.assetSupplies.forAsset(system.id).map { it.id })
        clearModels()
    }

    /**
     * The role sheet's chips: the distinct cleaned roles in use on any asset, in the category picker's order (C17,
     * `AssetSupplyRoles`). They fill nothing by themselves.
     */
    @Test fun suggestionsAreTheRolesInUseOnAnyAsset() = runTest {
        val system = asset("Example RO System")
        val spare = asset("Example Spare Housing")
        val cartridge = item("Example Prefilter Cartridge")
        take(system, cartridge, "Stage 2")
        take(system, cartridge, "Pre-filter")
        take(spare, cartridge, "stage 1")
        take(spare, cartridge, "Spare")
        val vm = model(system.id)

        val state = vm.state.first { it.suggestions.size == 4 }

        assertEquals(listOf("Pre-filter", "Spare", "stage 1", "Stage 2"), state.suggestions)
        assertNull(state.sheet)
        clearModels()
    }

    /**
     * C33's placement (R15-8): the section is the padded column's first, directly above the shipped child-asset
     * section, and the screen hands its row tap to a host parameter (C-1). Read from the source, as the references
     * section's placement is, because standing the whole screen up would prove the same one fact at far more cost.
     */
    @Test fun theSectionIsDrawnFirstInThePaddedColumnAboveTheChildAssets() {
        val screen = sourceFile("kotlin/com/loosecannon/servicetag/ui/asset/AssetDetailScreen.kt").readText()

        val maintenance = screen.indexOf("AssetMaintenanceSections(\n")
        val supplies = screen.indexOf("AssetSuppliesSection(\n")
        val children = screen.indexOf("ComponentsSection(\n")

        assertTrue("AssetSuppliesSection is called", supplies > 0)
        assertTrue("after the maintenance sections", maintenance in 1 until supplies)
        assertTrue("before the child assets", supplies < children)
        assertTrue("onOpenSupply reaches the section", screen.indexOf("onOpenSupply = onOpenSupply", supplies) in supplies until children)
    }
}
