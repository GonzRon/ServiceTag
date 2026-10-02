package com.loosecannon.servicetag.ui.supplies

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.usecase.AddAssetSupplyCommand
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.SpecificationInput
import com.loosecannon.servicetag.core.usecase.SupplyItemCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.ui.asset.CATEGORY_FIELD
import com.loosecannon.servicetag.ui.asset.MANUFACTURER_FIELD
import com.loosecannon.servicetag.ui.attachments.NOTES_LABEL
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #15 (C30, row 59): one SupplyItem's detail state — its identity facts, its specifications, and "Used by", the
 * applicability rows naming it with each asset's name and the role.
 *
 * Every row is written through the production use cases over the Room-backed `FakeGraph`, so a role here is the
 * cleaned role `AddAssetSupply` stored and an archive is the one column `ArchiveSupplyItem` sets. Fixtures are
 * fictional (the Global constraints).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupplyDetailViewModelTest {

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

    private fun viewModel(id: SupplyId) = SupplyDetailViewModel(
        items = graph.supplyItems,
        assetSupplies = graph.assetSupplies,
        assets = graph.assets,
        archiveSupplyItem = graph.archiveSupplyItem,
        id = id,
    )

    private suspend fun asset(name: String): Asset =
        graph.createAsset.run(AssetCommand(name = name, category = "Water"))

    private suspend fun save(
        name: String,
        category: String = "",
        manufacturer: String = "",
        model: String = "",
        partNumber: String = "",
        preferredUnit: String = "",
        notes: String = "",
        specifications: List<SpecificationInput> = emptyList(),
    ): SupplyItem = graph.saveSupplyItem.run(
        null,
        SupplyItemCommand(name, category, manufacturer, model, partNumber, preferredUnit, notes, specifications),
    ).item

    private suspend fun take(asset: Asset, item: SupplyItem, role: String) {
        graph.addAssetSupply.run(AddAssetSupplyCommand(asset.id, item.id, role))
    }

    private fun spec(label: String, value: String, unit: String = "") =
        SpecificationInput(id = null, key = "", label = label, value = value, unit = unit)

    /**
     * Row 59. One row per applicability row naming this item, each with its asset's name and its role, in the
     * asset's name order and then the role's; another item's row on the same asset is not this item's.
     */
    @Test fun usedByListsEachRowWithItsAssetName() = runTest {
        val spare = asset("Example Spare Housing")
        val system = asset("Example RO System")
        val cartridge = save("Example Prefilter Cartridge")
        val carbon = save("Example Carbon Block")
        take(spare, cartridge, "Spare")
        take(system, cartridge, "Stage 2")
        take(system, cartridge, "Stage 1")
        take(system, carbon, "Stage 3")

        val vm = viewModel(cartridge.id)
        backgroundScope.launch { vm.state.collect() }
        val state = vm.state.first { it != null }!!

        assertEquals(
            listOf(
                "Example RO System" to "Stage 1",
                "Example RO System" to "Stage 2",
                "Example Spare Housing" to "Spare",
            ),
            state.usedBy.map { it.assetName to it.role },
        )
        assertEquals(listOf(system.id, system.id, spare.id), state.usedBy.map { it.assetId })
    }

    /** No applicability row: "Used by" is empty, which is what draws P15-11. */
    @Test fun anItemNoAssetTakesHasNoUsers() = runTest {
        val item = save("Example Sediment Cartridge")

        val vm = viewModel(item.id)
        backgroundScope.launch { vm.state.collect() }

        assertEquals(emptyList<SupplyUseRow>(), vm.state.first { it != null }!!.usedBy)
    }

    /** Row 59. Archive and unarchive go through `ArchiveSupplyItem`, and the state follows the one column. */
    @Test fun archiveToggles() = runTest {
        val system = asset("Example RO System")
        val item = save("Example Prefilter Cartridge")
        take(system, item, "Stage 1")
        val vm = viewModel(item.id)
        backgroundScope.launch { vm.state.collect() }
        assertFalse(vm.state.first { it != null }!!.archived)

        vm.setArchived(true)
        assertTrue(vm.state.first { it?.archived == true }!!.archived)
        assertNotNull(graph.supplyItems.get(item.id)!!.archivedAt)
        // R15-6: an archived item stays on the asset that takes it.
        assertEquals(1, graph.assetSupplies.forSupply(item.id).size)

        vm.setArchived(false)
        vm.state.first { it?.archived == false }
        assertNull(graph.supplyItems.get(item.id)!!.archivedAt)
    }

    /**
     * The identity facts, each only when it is there, in C30's order, under the shipped labels and P15-4/P15-5:
     * a blank model is no fact at all, never an empty one.
     */
    @Test fun theFactsAreTheFieldsThatAreThereInOrder() = runTest {
        val item = save(
            name = "Example Prefilter Cartridge",
            category = "Water treatment",
            manufacturer = "Example Filters Co.",
            partNumber = "PF-10",
            preferredUnit = "cartridge",
            notes = "Fits the first housing.",
        )

        val vm = viewModel(item.id)
        backgroundScope.launch { vm.state.collect() }
        val state = vm.state.first { it != null }!!

        assertEquals("Example Prefilter Cartridge", state.name)
        assertEquals(
            listOf(
                CATEGORY_FIELD to "Water treatment",
                MANUFACTURER_FIELD to "Example Filters Co.",
                PART_NUMBER_FIELD to "PF-10",
                PREFERRED_UNIT_FIELD to "cartridge",
                NOTES_LABEL to "Fits the first housing.",
            ),
            state.facts.map { it.label to it.value },
        )
    }

    /** Each specification reads "label — value unit" in the stored order, the unit only when there is one. */
    @Test fun eachSpecificationReadsLabelValueAndUnit() = runTest {
        val item = save(
            name = "Example Prefilter Cartridge",
            specifications = listOf(
                spec("Micron rating", "5", "µm"),
                spec("Length", "10", "in"),
                spec("Connection", "Quick-connect"),
            ),
        )

        val vm = viewModel(item.id)
        backgroundScope.launch { vm.state.collect() }
        val state = vm.state.first { it != null }!!

        assertEquals(
            listOf("Micron rating — 5 µm", "Length — 10 in", "Connection — Quick-connect"),
            state.specifications.map { it.text },
        )
        assertTrue(state.facts.isEmpty())
    }

    /**
     * "Used by" is not a live table read (the port observes an asset's rows, not an item's), so returning to the
     * screen re-reads it: a row added on the asset's screen meanwhile is there after [SupplyDetailViewModel.refresh].
     */
    @Test fun aRowAddedElsewhereIsThereAfterARefresh() = runTest {
        val system = asset("Example RO System")
        val item = save("Example Prefilter Cartridge")
        val vm = viewModel(item.id)
        backgroundScope.launch { vm.state.collect() }
        assertTrue(vm.state.first { it != null }!!.usedBy.isEmpty())

        take(system, item, "Stage 1")
        vm.refresh()

        assertEquals(listOf("Stage 1"), vm.state.first { it != null && it.usedBy.isNotEmpty() }!!.usedBy.map { it.role })
    }

    /**
     * #69 row 50 (C26, R69-10): the Documents and References sections below "Used by" stay writable when the item is
     * archived. A SupplyItem is never held, so archiving it never turns its own files and links open-only.
     */
    @Test fun anArchivedItemIsWritable() = runTest {
        val item = save("Example 12 V Battery", manufacturer = "Example Power Co.")
        val vm = viewModel(item.id)
        backgroundScope.launch { vm.state.collect() }
        assertFalse(vm.state.first { it != null }!!.resourcesReadOnly)

        graph.archiveSupplyItem.run(item.id, true)

        val archived = vm.state.first { it?.archived == true }!!
        assertEquals(item.id, archived.id)
        assertFalse("an archived item's sections stay writable", archived.resourcesReadOnly)
    }

    /** A back stack naming an item no longer there (a replacing import) is `missing`, which sends the owner back. */
    @Test fun anItemThatIsNotThereIsMissing() = runTest {
        save("Example Prefilter Cartridge")
        val vm = viewModel(SupplyId("00000000-0000-4000-8000-999999999999"))
        backgroundScope.launch { vm.missing.collect() }

        assertTrue(vm.missing.first { it })
    }
}
