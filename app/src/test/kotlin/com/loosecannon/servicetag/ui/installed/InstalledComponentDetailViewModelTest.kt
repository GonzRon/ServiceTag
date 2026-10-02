package com.loosecannon.servicetag.ui.installed

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.CompositionInput
import com.loosecannon.servicetag.core.usecase.InstallComponentCommand
import com.loosecannon.servicetag.core.usecase.InstalledComponentResult
import com.loosecannon.servicetag.core.usecase.SupplyItemCommand
import com.loosecannon.servicetag.core.usecase.WithdrawTransferResult
import com.loosecannon.servicetag.testing.FakeGraph
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
 * #69 (C27; row 51): the installed-component screen's state — the component's own sections keyed by the component,
 * one open-only group per distinct SupplyItem its direct link and its composition name (link first, then the entries as
 * listed), and the observed reads (N-8): the held flag follows a hold and a release while open, and a component whose
 * asset is deleted reads as missing. Against the Room-backed `FakeGraph`, so the flows are the production ones.
 * Fixtures are fictional; every date is on or before the graph's today, 2026-02-10.
 *
 * `viewModelScope` dispatches on `Dispatchers.Main`, an unconfined test dispatcher sharing the scheduler the graph's
 * queries run on, so every assertion waits for a state rather than reading `value` after a write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstalledComponentDetailViewModelTest {

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

    private fun TestScope.model(id: InstalledComponentId): InstalledComponentDetailViewModel {
        val factory = viewModelFactory {
            initializer {
                InstalledComponentDetailViewModel(
                    id = id,
                    installedComponents = graph.installedComponents,
                    items = graph.supplyItems,
                    transfers = graph.transferRecords,
                )
            }
        }
        val vm = ViewModelProvider.create(store, factory)
            .get("installed-component-${id.value}", InstalledComponentDetailViewModel::class)
        backgroundScope.launch { vm.state.collect() }
        backgroundScope.launch { vm.missing.collect() }
        return vm
    }

    /** Cleared inside `runTest` as each case's last line, so the work behind a signal it waited for drains. */
    private fun TestScope.clearModels() {
        store.clear()
        advanceUntilIdle()
    }

    private suspend fun asset(name: String): Asset = graph.createAsset.run(AssetCommand(name = name, category = "Power"))

    private suspend fun item(name: String): SupplyItem =
        graph.saveSupplyItem.run(null, SupplyItemCommand(name, "", "", "", "", "", "", emptyList())).item

    private fun entry(item: SupplyItem) = CompositionInput(null, item.id, "1", "")

    private suspend fun install(
        asset: Asset,
        name: String,
        supplyId: SupplyId? = null,
        composition: List<CompositionInput> = emptyList(),
    ): InstalledComponent {
        val result = graph.installComponent.run(
            InstallComponentCommand(asset.id, null, name, supplyId, composition, "", "2026-01-05", "", null),
        )
        return (result as InstalledComponentResult.Ok).row
    }

    private suspend fun hold(asset: Asset) = graph.transferRecords.append(
        TransferRecord(
            id = "out-1", assetId = asset.id, kind = TransferKind.OUT, packId = PACK, lineage = emptyList(),
            at = 1_758_960_000_000L, packSha256 = "ab".repeat(32), nameSnapshot = asset.name, note = "",
        ),
    )

    /**
     * C27 (1): the component's own Documents and References are keyed by the component, never by its asset or by a
     * SupplyItem it names; the title is its name.
     */
    @Test fun ownSectionsAreKeyedByTheComponent() = runTest {
        val ups = asset("Example UPS")
        val battery = item("Example 12 V Battery")
        val tray = install(ups, "Example Battery Tray", supplyId = battery.id)

        val state = model(tray.id).state.first { it != null }!!

        assertEquals(tray.id, state.id)
        assertEquals("Example Battery Tray", state.name)
        assertEquals(AttachmentOwner.OfInstalledComponent(tray.id), state.files)
        assertEquals(ReferenceOwner.OfInstalledComponent(tray.id), state.links)
        assertFalse("the component's own sections are writable on an asset that is not held", state.readOnly)
        assertEquals(listOf(AttachmentOwner.OfSupplyItem(battery.id)), state.supplyGroups.map { it.files })
        assertEquals(listOf(ReferenceOwner.OfSupplyItem(battery.id)), state.supplyGroups.map { it.links })
        clearModels()
    }

    /**
     * C27 (2), R69-8 — the counted RED: a SupplyItem named by the direct link and by an entry, or by two entries, is
     * one group; the link's comes first, then the entries' as the composition lists them, each heading P69-3.
     */
    @Test fun oneGroupPerDistinctSupplyItemDirectFirstThenEntries() = runTest {
        val ups = asset("Example UPS")
        val battery = item("Example 12 V Battery")
        val strap = item("Example Battery Strap")
        val cover = item("Example Terminal Cover")
        val pack = install(
            ups, "Example Battery Pack", supplyId = battery.id,
            composition = listOf(entry(strap), entry(battery), entry(cover), entry(strap)),
        )

        val state = model(pack.id).state.first { it != null }!!

        assertEquals(listOf(battery.id, strap.id, cover.id), state.supplyGroups.map { it.supplyId })
        assertEquals(
            listOf("Example 12 V Battery", "Example Battery Strap", "Example Terminal Cover").map(::fromSupply),
            state.supplyGroups.map { it.heading },
        )
        assertEquals(listOf(battery.id, strap.id, cover.id), suppliesNamedBy(pack))
        clearModels()
    }

    /**
     * C27 (2), R69-10: an archived SupplyItem's group is drawn — when it is archived while the screen is open, and when
     * the screen opens on it archived.
     */
    @Test fun anArchivedSupplyItemsGroupIsDrawn() = runTest {
        val ups = asset("Example UPS")
        val battery = item("Example 12 V Battery")
        val pack = install(ups, "Example Battery Pack", composition = listOf(entry(battery)))
        val vm = model(pack.id)
        assertEquals(listOf(battery.id), vm.state.first { it != null }!!.supplyGroups.map { it.supplyId })

        graph.archiveSupplyItem.run(battery.id, archived = true)
        advanceUntilIdle()

        assertEquals(listOf(fromSupply("Example 12 V Battery")), vm.state.value!!.supplyGroups.map { it.heading })
        store.clear()
        val reopened = model(pack.id).state.first { it != null }!!
        assertEquals(listOf(battery.id), reopened.supplyGroups.map { it.supplyId })
        clearModels()
    }

    /** C27 (1), #77: a component of a held asset opens with its own sections read-only. */
    @Test fun aHeldAssetMakesOwnSectionsReadOnly() = runTest {
        val ups = asset("Example UPS")
        val tray = install(ups, "Example Battery Tray")
        hold(ups)

        assertTrue(model(tray.id).state.first { it != null }!!.readOnly)
        clearModels()
    }

    /** C27 (1), R69-10: a removed component keeps its own sections writable and is still shown. */
    @Test fun aRemovedComponentIsWritable() = runTest {
        val ups = asset("Example UPS")
        val tray = install(ups, "Example Battery Tray")
        assertTrue(graph.removeInstalledComponent.run(tray.id, "2026-02-01") is InstalledComponentResult.Ok)

        val vm = model(tray.id)
        val state = vm.state.first { it != null }!!

        assertEquals(tray.id, state.id)
        assertFalse(state.readOnly)
        assertFalse(vm.missing.value)
        clearModels()
    }

    /**
     * N-8: the row is observed, not read once — the asset deleted while the screen is open takes the row by CASCADE
     * and the screen reads it as missing (it goes back). An id that names no row is missing from the start.
     */
    @Test fun aDeletedAssetGoesBack() = runTest {
        val ups = asset("Example UPS")
        val tray = install(ups, "Example Battery Tray")
        val vm = model(tray.id)
        vm.state.first { it != null }
        assertFalse(vm.missing.value)

        graph.deleteAsset.run(ups.id)

        assertTrue(vm.missing.first { it })
        assertEquals(null, vm.state.first { it == null })
        assertTrue(model(InstalledComponentId("example-no-such-row")).missing.first { it })
        clearModels()
    }

    /** N-8: a hold while the screen is open makes the own sections read-only, and its release makes them writable. */
    @Test fun aHoldWhileOpenMakesItReadOnly() = runTest {
        val ups = asset("Example UPS")
        val tray = install(ups, "Example Battery Tray")
        val vm = model(tray.id)
        assertFalse(vm.state.first { it != null }!!.readOnly)

        hold(ups)
        assertTrue(vm.state.first { it?.readOnly == true }!!.readOnly)

        assertTrue(graph.withdrawTransferRecord.run(ups.id, PACK) is WithdrawTransferResult.Withdrawn)
        assertFalse(vm.state.first { it?.readOnly == false }!!.readOnly)
        clearModels()
    }

    private companion object {
        const val PACK = "0f1e2d3c-pack"
    }
}
