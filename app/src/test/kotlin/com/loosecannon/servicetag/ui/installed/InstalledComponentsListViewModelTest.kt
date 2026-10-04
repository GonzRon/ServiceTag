package com.loosecannon.servicetag.ui.installed

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.testing.FakeGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * #103 (1.7.1; owner ruling Q2): the cross-asset Installed components list's state, over the Room-backed
 * `FakeGraph`. The failure this file exists to prevent is a list that names a component the owner cannot
 * place: every row carries its asset's name and its parents' names (P69-20's path), rows sit together under
 * their asset in a stated order, a removed component is history and not here, and a tap target is the asset.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstalledComponentsListViewModelTest {

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

    private suspend fun asset(name: String): AssetId =
        graph.createAsset.run(AssetCommand(name = name, category = "Example")).id

    private suspend fun fit(
        id: String,
        assetId: AssetId,
        name: String,
        parentId: String? = null,
        sortOrder: Int = 0,
        removedOn: String? = null,
    ): InstalledComponentId {
        val row = InstalledComponent(
            id = InstalledComponentId(id), assetId = assetId, parentId = parentId?.let(::InstalledComponentId),
            name = name, supplyId = null, composition = emptyList(), serialOrLot = "", installedOn = "2026-01-10",
            removedOn = removedOn, replacesId = null, sortOrder = sortOrder, notes = "", createdAt = 1_000L,
            updatedAt = 2_000L,
        )
        graph.installedComponents.insert(row)
        return row.id
    }

    /**
     * Rows are grouped under their asset in asset-name order, then by `sortOrder` then name within it; a nested
     * component's path names the asset and then each parent, outermost first; a removed component is absent;
     * every row's tap target is its asset.
     */
    @Test fun rowsAreOrderedUnderTheirAssetWithTheirPathAndARemovedOneIsAbsent() = runTest {
        val heater = asset("Example Heater")
        val boat = asset("example boat")
        fit("tray", heater, "Example Battery Tray", sortOrder = 1)
        fit("pack", heater, "Example Battery Pack", parentId = "tray", sortOrder = 0)
        fit("anode", heater, "Example Anode", sortOrder = 0)
        fit("old", heater, "Example Old Element", sortOrder = 2, removedOn = "2026-02-01")
        fit("prop", boat, "Example Propeller")

        val vm = InstalledComponentsListViewModel(graph.installedComponents, graph.assets)
        assertNull("nothing has been read yet", vm.rows.value)
        vm.refresh()
        val rows = vm.rows.first { it != null }!!

        assertEquals(
            listOf("Example Propeller", "Example Anode", "Example Battery Pack", "Example Battery Tray"),
            rows.map { it.name },
        )
        assertEquals(
            listOf(
                "example boat",
                "Example Heater",
                "Example Heater › Example Battery Tray",
                "Example Heater",
            ),
            rows.map { it.path },
        )
        assertEquals(listOf(boat, heater, heater, heater), rows.map { it.assetId })
        assertEquals("the removed element is history, not a row", 0, rows.count { it.id.value == "old" })
    }

    /** A phone with nothing fitted is an empty list — a read answer — never the null the screen waits on. */
    @Test fun aPhoneWithNothingFittedIsReadAsEmpty() = runTest {
        asset("Example Heater")
        val vm = InstalledComponentsListViewModel(graph.installedComponents, graph.assets)
        vm.refresh()
        assertEquals(emptyList<InstalledComponentListRow>(), vm.rows.first { it != null })
    }

    /** A second read picks up what changed in between: the screen refreshes on every start for this. */
    @Test fun refreshRereadsTheStore() = runTest {
        val heater = asset("Example Heater")
        val vm = InstalledComponentsListViewModel(graph.installedComponents, graph.assets)
        vm.refresh()
        assertEquals(emptyList<InstalledComponentListRow>(), vm.rows.first { it != null })

        fit("tray", heater, "Example Battery Tray")
        vm.refresh()
        assertEquals(listOf("Example Battery Tray"), vm.rows.first { !it.isNullOrEmpty() }!!.map { it.name })
    }
}
