package com.loosecannon.servicetag.ui.supplies

import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.usecase.SupplyItemCommand
import com.loosecannon.servicetag.testing.FakeGraph
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
import org.junit.Before
import org.junit.Test

/**
 * #15 (C30, row 59): the Supplies list's state, over the Room-backed `FakeGraph` and the production use cases.
 *
 * The failure this file exists to prevent is a catalog that hides what it archived: R15-6 keeps an archived
 * SupplyItem on its assets and its linked lines, so the one list that names every SupplyItem has to name it too —
 * in its own place in the name order, marked, never dropped and never moved to the end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupplyListViewModelTest {

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

    private suspend fun save(name: String, manufacturer: String = "", partNumber: String = ""): SupplyItem =
        graph.saveSupplyItem.run(
            null,
            SupplyItemCommand(
                name = name,
                category = "",
                manufacturer = manufacturer,
                model = "",
                partNumber = partNumber,
                preferredUnit = "",
                notes = "",
                specifications = emptyList(),
            ),
        ).item

    /**
     * Row 59. Every SupplyItem in `(name casefolded, id)` order; the archived one keeps its place — first here, by
     * its name — and is marked; the quiet line is "manufacturer · part number", each half only when it is there.
     */
    @Test fun rowsAreOrderedAndArchivedOnesMarked() = runTest {
        save("example prefilter cartridge", manufacturer = "Example Filters Co.", partNumber = "PF-10")
        val carbon = save("Example Carbon Block", partNumber = "CB-5")
        save("Example RO Membrane", manufacturer = "Example Filters Co.")
        save("Example Sediment Cartridge")
        graph.archiveSupplyItem.run(carbon.id, archived = true)

        val vm = SupplyListViewModel(graph.supplyItems)
        backgroundScope.launch { vm.rows.collect() }
        val rows = vm.rows.first { it != null }!!

        assertEquals(
            listOf(
                "Example Carbon Block",
                "example prefilter cartridge",
                "Example RO Membrane",
                "Example Sediment Cartridge",
            ),
            rows.map { it.name },
        )
        assertEquals(listOf(true, false, false, false), rows.map { it.archived })
        assertEquals(listOf("CB-5", "Example Filters Co. · PF-10", "Example Filters Co.", ""), rows.map { it.detail })
        assertEquals(carbon.id, rows.first().id)
    }

    /** An empty catalog is a read answer — an empty list — and never the "not read yet" null the screen waits on. */
    @Test fun anEmptyCatalogIsReadAsEmpty() = runTest {
        val vm = SupplyListViewModel(graph.supplyItems)
        backgroundScope.launch { vm.rows.collect() }

        assertEquals(emptyList<SupplyListRow>(), vm.rows.first { it != null })
    }

    /** The list is live: an item saved while it is open joins it, and an unarchive clears its mark. */
    @Test fun theListFollowsTheStore() = runTest {
        val membrane = save("Example RO Membrane")
        graph.archiveSupplyItem.run(membrane.id, archived = true)
        val vm = SupplyListViewModel(graph.supplyItems)
        backgroundScope.launch { vm.rows.collect() }
        assertEquals(listOf(true), vm.rows.first { it != null }!!.map { it.archived })

        save("Example Carbon Block")
        graph.archiveSupplyItem.run(membrane.id, archived = false)

        val after = vm.rows.first { it != null && it.size == 2 && it.none { row -> row.archived } }!!
        assertEquals(listOf("Example Carbon Block", "Example RO Membrane"), after.map { it.name })
    }
}
