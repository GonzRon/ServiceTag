package com.loosecannon.servicetag.ui.supplies

import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.usecase.SpecificationInput
import com.loosecannon.servicetag.core.usecase.SupplyItemCommand
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #15 (C31, row 60): the SupplyItem editor's state — the identity fields, the ordered specification rows, when Save
 * is offered, and what a refused or a gone save leaves on the form.
 *
 * The editor adds no rule of its own: every save is one `SaveSupplyItem` call over the Room-backed `FakeGraph`, so
 * what is kept, minted, keyed or refused here is the use case's answer, read back from the store. Fixtures are
 * fictional (the Global constraints).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupplyEditViewModelTest {

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

    private fun viewModel(id: SupplyId? = null) = SupplyEditViewModel(
        items = graph.supplyItems,
        saveSupplyItem = graph.saveSupplyItem,
        id = id,
    )

    private suspend fun loaded(vm: SupplyEditViewModel): SupplyEditState = vm.state.first { it.loaded }

    /** The form once its save has answered, whichever way. */
    private suspend fun settled(vm: SupplyEditViewModel): SupplyEditState = vm.state.first { !it.saving }

    private suspend fun stored(): List<SupplyItem> = graph.supplyItems.all()

    private fun spec(label: String, value: String, unit: String = "") =
        SpecificationInput(id = null, key = "", label = label, value = value, unit = unit)

    private suspend fun cartridge(): SupplyItem = graph.saveSupplyItem.run(
        null,
        SupplyItemCommand(
            name = "Example Prefilter Cartridge",
            category = "Filters",
            manufacturer = "Example Filters Co.",
            model = "PF",
            partNumber = "PF-10",
            preferredUnit = "cartridge",
            notes = "Fits the first housing.",
            specifications = listOf(spec("Micron rating", "5", "µm"), spec("Connection", "Quick-connect")),
        ),
    ).item

    /**
     * Row 60. Save is offered only while the name has something in it once trimmed, and never twice for one tap:
     * a name of spaces is no name, and the use case would refuse it.
     */
    @Test fun canSaveFollowsTheTrimmedName() = runTest {
        val vm = viewModel()
        assertFalse("a new item starts with no name", loaded(vm).canSave)

        vm.onName("   ")
        assertFalse("spaces are not a name", vm.state.value.canSave)

        vm.onName("  Example Carbon Block ")
        assertTrue(vm.state.value.canSave)

        vm.save()
        assertFalse("nothing is offered while the save is under way", vm.state.value.canSave)
        val id = vm.saved.first()
        assertEquals("Example Carbon Block", graph.supplyItems.get(SupplyId(id))!!.name)
    }

    /**
     * A new item is one save: every identity field trimmed, the rows in screen order with keys the phone never
     * typed (each its label's slug), and the saved id handed to the screen.
     */
    @Test fun aNewItemIsOneSaveWithEveryFieldAndItsRowsInScreenOrder() = runTest {
        val vm = viewModel()
        loaded(vm)
        vm.onName("Example RO Membrane")
        vm.onCategory(" Membranes ")
        vm.onManufacturer("Example Filters Co.")
        vm.onModel("RO-75")
        vm.onPartNumber("ROM-75")
        vm.onPreferredUnit("membrane")
        vm.onNotes("Rinse before fitting.")
        vm.addSpecification()
        vm.onSpecification(0, label = "Flow", value = "75", unit = "gpd")
        vm.addSpecification()
        vm.onSpecification(1, label = "Diameter")
        vm.onSpecification(1, value = "1.8")
        vm.onSpecification(1, unit = "in")

        vm.save()
        val item = graph.supplyItems.get(SupplyId(vm.saved.first()))!!

        assertEquals(
            listOf("Example RO Membrane", "Membranes", "Example Filters Co.", "RO-75", "ROM-75", "membrane", "Rinse before fitting."),
            listOf(item.name, item.category, item.manufacturer, item.model, item.partNumber, item.preferredUnit, item.notes),
        )
        assertEquals(
            listOf(Triple("Flow", "75", "gpd"), Triple("Diameter", "1.8", "in")),
            item.specifications.map { Triple(it.label, it.value, it.unit) },
        )
        assertEquals(listOf("flow", "diameter"), item.specifications.map { it.key })
        assertEquals(1, stored().size)
    }

    /** An edit opens on the stored item: every field, and every row in its stored order. */
    @Test fun anEditLoadsEveryFieldAndRowInOrder() = runTest {
        val item = cartridge()

        val state = loaded(viewModel(item.id))

        assertTrue(state.editing)
        assertEquals(
            listOf("Example Prefilter Cartridge", "Filters", "Example Filters Co.", "PF", "PF-10", "cartridge", "Fits the first housing."),
            listOf(state.name, state.category, state.manufacturer, state.model, state.partNumber, state.preferredUnit, state.notes),
        )
        assertEquals(item.specifications.map { it.id }, state.specifications.map { it.id })
        assertEquals(
            listOf(Triple("Micron rating", "5", "µm"), Triple("Connection", "Quick-connect", "")),
            state.specifications.map { Triple(it.label, it.value, it.unit) },
        )
        assertNull(state.specificationsProblem)
        assertNull(state.goneProblem)
    }

    /**
     * Row 60. A loaded row is sent back with its id, so it keeps that id and its stored key even when its label
     * changes (B3's rule: a label edit never re-keys a row); a removed row is gone; an added row is minted fresh.
     */
    @Test fun rowsKeepTheirIdsAndKeys() = runTest {
        val item = cartridge()
        val micron = item.specifications[0]
        val connection = item.specifications[1]
        assertEquals("micron_rating", micron.key)

        val vm = viewModel(item.id)
        loaded(vm)
        vm.onSpecification(0, label = "Rated micron")
        vm.removeSpecification(1)
        vm.addSpecification()
        vm.onSpecification(1, label = "Length", value = "10", unit = "in")
        vm.save()
        assertEquals(item.id.value, vm.saved.first())

        val rows = graph.supplyItems.get(item.id)!!.specifications
        assertEquals(listOf("Rated micron", "Length"), rows.map { it.label })
        assertEquals("the kept row keeps its id", micron.id, rows[0].id)
        assertEquals("and its key, though its label changed", "micron_rating", rows[0].key)
        assertTrue("the added row is minted", rows[1].id !in setOf(micron.id, connection.id))
        assertEquals("length", rows[1].key)
        assertTrue("the removed row is gone", rows.none { it.id == connection.id })
    }

    /**
     * Row 60. A refused save marks each row the use case named and draws P15-12 under the rows; nothing is
     * written. Editing the marked row clears its mark, and the next save goes through.
     */
    @Test fun aValidationNamingARowMarksItAndDrawsP15_12() = runTest {
        val vm = viewModel()
        loaded(vm)
        vm.onName("Example Carbon Block")
        vm.addSpecification()
        vm.onSpecification(0, label = "Micron rating", value = "10", unit = "µm")
        vm.addSpecification()
        vm.onSpecification(1, label = "Length")

        vm.save()
        val refused = settled(vm)

        assertEquals(listOf(false, true), refused.specifications.map { it.marked })
        assertEquals(SPECIFICATION_NEEDS_LABEL_AND_VALUE, refused.specificationsProblem)
        assertTrue("a refused save writes nothing", stored().isEmpty())
        assertEquals("the rows are kept as typed", listOf("Micron rating", "Length"), refused.specifications.map { it.label })

        vm.onSpecification(1, value = "10")
        assertEquals(listOf(false, false), vm.state.value.specifications.map { it.marked })
        assertNull(vm.state.value.specificationsProblem)

        vm.save()
        val item = graph.supplyItems.get(SupplyId(vm.saved.first()))!!
        assertEquals(listOf("10", "10"), item.specifications.map { it.value })
    }

    /** A removed marked row takes its mark with it; the rows left keep theirs, in their new places. */
    @Test fun removingAMarkedRowTakesItsMarkWithIt() = runTest {
        val vm = viewModel()
        loaded(vm)
        vm.onName("Example Carbon Block")
        repeat(3) { vm.addSpecification() }
        vm.onSpecification(1, label = "Length", value = "10")

        vm.save()
        assertEquals(listOf(true, false, true), settled(vm).specifications.map { it.marked })

        vm.removeSpecification(0)
        assertEquals(listOf(false, true), vm.state.value.specifications.map { it.marked })
        assertEquals(SPECIFICATION_NEEDS_LABEL_AND_VALUE, vm.state.value.specificationsProblem)

        vm.removeSpecification(1)
        assertNull(vm.state.value.specificationsProblem)
    }

    /**
     * Row 60. Cancel is the screen leaving: the form itself writes nothing until Save, so an edited, re-rowed form
     * that is never saved leaves the stored item exactly as it was, and a new one that is never saved stores nothing.
     */
    @Test fun cancelWritesNothing() = runTest {
        val item = cartridge()
        graph.now = 9_000L

        val edit = viewModel(item.id)
        loaded(edit)
        edit.onName("Example Renamed Cartridge")
        edit.onNotes("")
        edit.onSpecification(0, value = "1")
        edit.removeSpecification(1)
        edit.addSpecification()

        val fresh = viewModel()
        loaded(fresh)
        fresh.onName("Example Carbon Block")
        fresh.addSpecification()
        fresh.onSpecification(0, label = "Length", value = "10")
        scheduler.advanceUntilIdle()

        assertEquals(listOf(item), stored())
    }

    /** An edit that changes nothing still leaves the form, and the use case writes nothing: `updatedAt` held. */
    @Test fun anUnchangedEditLeavesAndWritesNothing() = runTest {
        val item = cartridge()
        graph.now = 9_000L

        val vm = viewModel(item.id)
        loaded(vm)
        vm.save()

        assertEquals(item.id.value, vm.saved.first())
        assertEquals(item, graph.supplyItems.get(item.id))
    }

    /**
     * P15-20: an item that is not there — gone before the editor opened, or gone between the load and the save —
     * draws the ratified sentence; nothing is written either way.
     */
    @Test fun anItemNoLongerThereDrawsP15_20() = runTest {
        val never = loaded(viewModel(SupplyId("no-such-item")))
        assertEquals(SUPPLY_ITEM_GONE, never.goneProblem)

        val item = cartridge()
        val vm = viewModel(item.id)
        loaded(vm)
        assertNull(vm.state.value.goneProblem)
        graph.uow.write { graph.supplyItems.deleteAll() }

        vm.onName("Example Renamed Cartridge")
        vm.save()
        val refused = settled(vm)

        assertEquals(SUPPLY_ITEM_GONE, refused.goneProblem)
        assertNull(refused.specificationsProblem)
        assertTrue("a gone item is not re-created", stored().isEmpty())
        assertNotEquals("the form keeps what was typed", "", refused.name)
    }
}
