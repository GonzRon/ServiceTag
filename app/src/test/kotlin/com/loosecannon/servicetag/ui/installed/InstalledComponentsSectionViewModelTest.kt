package com.loosecannon.servicetag.ui.installed

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.CompositionInput
import com.loosecannon.servicetag.core.usecase.InstallComponentCommand
import com.loosecannon.servicetag.core.usecase.InstalledComponentResult
import com.loosecannon.servicetag.core.usecase.ReplaceComponentCommand
import com.loosecannon.servicetag.core.usecase.SupplyItemCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.ui.supplies.LINKED_TO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #47 (C25, C27; row 50): the asset detail's Installed components section, its read state — the tree, the quiet
 * lines, the removed rows behind the toggle, the two SupplyItem sets and the read-only flag — against the Room-backed
 * `FakeGraph`, so the flows and the use cases that make each fixture are the production ones. Every sentence is
 * asserted through its one home (`InstalledComponentStrings.kt`, `LINKED_TO`), so a re-worded string moves this test
 * with it. Fixtures are fictional (the Global constraints); every date is on or before the graph's today, 2026-02-10.
 *
 * `viewModelScope` dispatches on `Dispatchers.Main`, an unconfined test dispatcher sharing the scheduler the graph's
 * queries run on, so every assertion waits for a state rather than reading `value` after a write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstalledComponentsSectionViewModelTest {

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

    private fun TestScope.model(assetId: AssetId): InstalledComponentsSectionViewModel {
        val factory = viewModelFactory {
            initializer {
                InstalledComponentsSectionViewModel(
                    assetId = assetId,
                    installedComponents = graph.installedComponents,
                    items = graph.supplyItems,
                )
            }
        }
        val vm = ViewModelProvider.create(store, factory)["asset-installed-${assetId.value}", InstalledComponentsSectionViewModel::class]
        backgroundScope.launch { vm.state.collect() }
        return vm
    }

    /** Cleared inside `runTest` as each case's last line, so the work behind a signal it waited for drains. */
    private fun TestScope.clearModels() {
        store.clear()
        advanceUntilIdle()
    }

    private suspend fun asset(name: String): Asset = graph.createAsset.run(AssetCommand(name = name, category = "Power"))

    private suspend fun item(name: String, partNumber: String = "", unit: String = ""): SupplyItem =
        graph.saveSupplyItem.run(null, SupplyItemCommand(name, "", "", "", partNumber, unit, "", emptyList())).item

    private fun entry(item: SupplyItem, quantity: String, unit: String = "") = CompositionInput(null, item.id, quantity, unit)

    private suspend fun install(
        asset: Asset,
        name: String,
        parent: InstalledComponent? = null,
        supplyId: SupplyId? = null,
        composition: List<CompositionInput> = emptyList(),
        serialOrLot: String = "",
        installedOn: String? = null,
        sortOrder: Int? = null,
    ): InstalledComponent {
        val result = graph.installComponent.run(
            InstallComponentCommand(asset.id, parent?.id, name, supplyId, composition, serialOrLot, installedOn, "", sortOrder),
        )
        return (result as InstalledComponentResult.Ok).row
    }

    private suspend fun remove(row: InstalledComponent, on: String) {
        assertTrue(graph.removeInstalledComponent.run(row.id, on) is InstalledComponentResult.Ok)
    }

    private suspend fun replace(row: InstalledComponent, on: String): InstalledComponent {
        val result = graph.replaceInstalledComponent.run(row.id, ReplaceComponentCommand(on, row.name, null, emptyList(), "", ""))
        return (result as InstalledComponentResult.Ok).row
    }

    /**
     * C25: the current rows parents first, each with its depth, siblings by `sortOrder` before their names ("Position
     * 10" after "Position 2"); a nested row carries P47-5 naming its parent, a top row none; another asset's rows and
     * this asset's removed rows are not in the tree.
     */
    @Test fun currentRowsAreIndentedByDepthInSiblingOrder() = runTest {
        val ups = asset("Example UPS")
        val spare = asset("Example Spare UPS")
        val tray = install(ups, "Example Battery Tray")
        val fan = install(ups, "Example Cooling Fan")
        val ten = install(ups, "Position 10", parent = tray, sortOrder = 2)
        install(ups, "Position 2", parent = tray, sortOrder = 1)
        val one = install(ups, "Position 1", parent = tray, sortOrder = 0)
        install(ups, "Example Terminal Cover", parent = one)
        install(spare, "Example Spare Tray")
        remove(install(ups, "Example Old Filter"), on = "2026-01-05")

        val vm = model(ups.id)
        val state = vm.state.first { it.rows.size == 6 }

        assertEquals(
            listOf(
                "Example Battery Tray" to 0, "Position 1" to 1, "Example Terminal Cover" to 2, "Position 2" to 1,
                "Position 10" to 1, "Example Cooling Fan" to 0,
            ),
            state.rows.map { it.name to it.depth },
        )
        assertEquals(
            listOf(
                null, insideOf("Example Battery Tray"), insideOf("Position 1"), insideOf("Example Battery Tray"),
                insideOf("Example Battery Tray"), null,
            ),
            state.rows.map { it.inside },
        )
        assertEquals(listOf(tray.id, fan.id), state.rows.filter { it.depth == 0 }.map { it.id })
        assertEquals(ten.id, state.rows[4].id)
        assertEquals(7, state.components.size)
        clearModels()
    }

    /**
     * C25's quiet line, joined with " · ": the direct link in P15-22's words and its part number, the composition's
     * first two entries in P47-21 (`formatNumber`'s "4", never "4.0", then the unit when there is one) and P47-22 for
     * the rest, the serial or lot, and P47-15's day. A row with only a name has an empty line; an archived direct
     * SupplyItem marks the row.
     */
    @Test fun theQuietLineReadsLinkCompositionSerialAndDate() = runTest {
        val ups = asset("Example UPS")
        val pack = item("Example Battery Pack", partNumber = "EX-PK-4")
        val cell = item("Example 12 V Battery")
        val coolant = item("Example Coolant", unit = "L")
        val fuse = item("Example Fuse")
        val gasket = item("Example Gasket")
        install(
            ups, "Example Battery Pack", supplyId = pack.id,
            composition = listOf(entry(cell, "4"), entry(coolant, "2", "L"), entry(fuse, "1"), entry(gasket, "0.5")),
            serialOrLot = "SN-EXAMPLE-01", installedOn = "2025-03-04",
        )
        install(ups, "Example Coolant Loop", composition = listOf(entry(coolant, "2", "L"), entry(cell, "4")))
        install(ups, "Example Bare Bracket")
        install(ups, "Example Spare Pack", supplyId = cell.id)
        graph.archiveSupplyItem.run(cell.id, archived = true)

        val vm = model(ups.id)
        val rows = vm.state.first { s -> s.rows.size == 4 && s.rows.any { it.archived } }.rows.associateBy { it.name }

        assertEquals(
            listOf(
                LINKED_TO.format("Example Battery Pack"), "EX-PK-4",
                compositionLine("4", "Example 12 V Battery"), compositionLine("2 L", "Example Coolant"), moreEntries(2),
                "SN-EXAMPLE-01", installedOnDay("2025-03-04"),
            ).joinToString(" · "),
            rows.getValue("Example Battery Pack").quiet,
        )
        assertEquals("4 × Example 12 V Battery", compositionLine("4", "Example 12 V Battery"))
        assertEquals(
            listOf(compositionLine("2 L", "Example Coolant"), compositionLine("4", "Example 12 V Battery")).joinToString(" · "),
            rows.getValue("Example Coolant Loop").quiet,
        )
        assertEquals("", rows.getValue("Example Bare Bracket").quiet)
        assertEquals(LINKED_TO.format("Example 12 V Battery"), rows.getValue("Example Spare Pack").quiet)
        assertEquals(listOf("Example Spare Pack"), rows.values.filter { it.archived }.map { it.name })
        clearModels()
    }

    /**
     * C25, R47-16: the removed rows nothing replaced sit behind P47-18, collapsed until toggled, newest removal first,
     * each with P47-17's day; a replaced row is not among them (its successor is current); a removed row whose parent
     * is not current leads with P47-5, and one whose parent is current does not.
     */
    @Test fun removedUnreplacedSitBehindTheToggle() = runTest {
        val ups = asset("Example UPS")
        val tray = install(ups, "Example Battery Tray")
        val one = install(ups, "Position 1", parent = tray)
        val two = install(ups, "Position 2", parent = tray)
        val oldTray = install(ups, "Example Old Tray")
        install(ups, "Position 3", parent = oldTray)
        replace(one, on = "2026-01-20")
        remove(two, on = "2026-02-01")
        remove(oldTray, on = "2026-01-10")

        val vm = model(ups.id)
        val state = vm.state.first { it.removed.size == 3 }

        assertFalse(state.showRemoved)
        assertEquals(listOf("Example Battery Tray", "Position 1"), state.rows.map { it.name })
        assertEquals(listOf("Position 2", "Example Old Tray", "Position 3"), state.removed.map { it.name })
        assertEquals(
            listOf(
                removedOnDay("2026-02-01"),
                removedOnDay("2026-01-10"),
                listOf(insideOf("Example Old Tray"), removedOnDay("2026-01-10")).joinToString(" · "),
            ),
            state.removed.map { it.quiet },
        )
        assertEquals(listOf(0, 0, 0), state.removed.map { it.depth })
        assertTrue(state.removed.all { it.inside == null })
        assertEquals("Removed (3)", removedCount(state.removed.size))

        vm.toggleRemoved()
        assertTrue(vm.state.first { it.showRemoved }.showRemoved)
        vm.toggleRemoved()
        assertFalse(vm.state.first { !it.showRemoved }.showRemoved)
        clearModels()
    }

    /**
     * Row 50 (the counted RED: the picker includes archived items). C-1, R47-3: a picker is offered the unarchived
     * SupplyItems only, in the Supplies list's order; the link and composition lines get every SupplyItem, archived
     * included, so an archived item a row already names still draws, marked.
     */
    @Test fun thePickerOffersUnarchivedOnly() = runTest {
        val system = asset("Example RO System")
        item("Example Sediment Cartridge")
        item("example prefilter cartridge")
        val membrane = item("Example Membrane")
        install(system, "Example Membrane Housing", supplyId = membrane.id, composition = listOf(entry(membrane, "1")))
        graph.archiveSupplyItem.run(membrane.id, archived = true)

        val vm = model(system.id)
        val state = vm.state.first { s -> s.supplies[membrane.id]?.archived == true && s.rows.size == 1 }

        assertEquals(listOf("example prefilter cartridge", "Example Sediment Cartridge"), state.choices.map { it.name })
        assertTrue(state.choices.none { it.archived })
        assertEquals(
            setOf("Example Sediment Cartridge", "example prefilter cartridge", "Example Membrane"),
            state.supplies.values.map { it.name }.toSet(),
        )
        assertTrue(state.supplies.getValue(membrane.id).archived)
        assertTrue(state.rows.single().archived)
        clearModels()
    }

    /**
     * C27, #77: a held asset offers nothing that writes, and still draws its rows and opens its toggle; lifting the
     * hold offers writes again.
     */
    @Test fun readOnlyOffersNothing() = runTest {
        val ups = asset("Example UPS")
        install(ups, "Example Battery Tray")
        remove(install(ups, "Example Old Tray"), on = "2026-01-10")

        val vm = model(ups.id)
        assertTrue(vm.state.first { it.rows.size == 1 }.offersWrites)

        vm.setReadOnly(true)
        val held = vm.state.first { !it.offersWrites }
        assertEquals(listOf("Example Battery Tray"), held.rows.map { it.name })
        assertEquals(listOf("Example Old Tray"), held.removed.map { it.name })
        vm.toggleRemoved()
        assertTrue(vm.state.first { it.showRemoved }.let { it.showRemoved && !it.offersWrites })

        vm.setReadOnly(false)
        assertTrue(vm.state.first { it.offersWrites }.showRemoved)
        clearModels()
    }

    /**
     * P47-2's state: an asset with nothing fitted has no rows and no removed rows, so no toggle draws, while the
     * catalog has loaded and another asset's rows stay on that asset.
     */
    @Test fun anAssetWithNothingFittedHasNoRows() = runTest {
        val ups = asset("Example UPS")
        val other = asset("Example Spare UPS")
        val fuse = item("Example Fuse")
        install(other, "Example Battery Tray", supplyId = fuse.id)

        val vm = model(ups.id)
        val state = vm.state.first { it.supplies.size == 1 }

        assertTrue(state.rows.isEmpty())
        assertTrue(state.removed.isEmpty())
        assertTrue(state.components.isEmpty())
        assertEquals(listOf("Example Fuse"), state.choices.map { it.name })
        clearModels()
    }
}
