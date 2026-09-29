package com.loosecannon.servicetag.ui.transfer

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.loanRow
import com.loosecannon.servicetag.ui.transfer.`import`.TransferPackAppFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
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
 * #77 (C17; row 29) — the selection and its review over a Room-backed [FakeGraph] and the production creation (a read):
 * parents first, each component under its parent, a held asset never offered; P77-56 with nothing to offer; a
 * component forced by its checked parent is checked, disabled and described P77-61; every refusal blocks Create, a
 * held component forced in by a parent that is not held included (P77-57); the note is one bounded line.
 * Fictional names only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferSelectionViewModelTest {

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

    private fun TestScope.model(preselect: String? = null): TransferSelectionViewModel =
        TransferSelectionViewModel(
            graph.assets, graph.transferRecords, graph.groups, graph.createTransferPack,
            preselect?.let(::AssetId), io = StandardTestDispatcher(scheduler),
        ).also { vm -> backgroundScope.launch { vm.state.collect() } }

    private suspend fun asset(id: String, name: String, parent: String? = null) = graph.assets.upsert(
        Asset(id = AssetId(id), name = name, parentAssetId = parent?.let(::AssetId), createdAt = 100L, updatedAt = 100L),
    )

    private suspend fun hold(id: String, name: String) = graph.transferRecords.append(
        TransferRecord(
            id = "out-$id", assetId = AssetId(id), kind = TransferKind.OUT, packId = "0f1e2d3c-pack-$id",
            lineage = emptyList(), at = 1_758_960_000_000L, packSha256 = "ab".repeat(32), nameSnapshot = name, note = "",
        ),
    )

    /** The heater and its anode, a garage door opener, and a pump already transferred out. */
    private suspend fun estate() {
        asset("h1", "Example Water Heater")
        asset("a1", "Example Anode Rod", parent = "h1")
        asset("g1", "Sample Garage Door Opener")
        asset("p1", "Example Pump")
        hold("p1", "Example Pump")
    }

    @Test fun parentsFirstEachComponentUnderItsParentAndAHeldAssetNeverOffered() = runTest(scheduler) {
        estate()
        val vm = model()
        advanceUntilIdle()

        val choices = vm.state.value.choices
        assertEquals(listOf("h1", "a1", "g1"), choices.map { it.id })
        assertEquals(listOf(0, 1, 0), choices.map { it.depth })
        assertEquals(listOf(null, "Example Water Heater", null), choices.map { it.parentName })
        assertTrue(choices.none { it.checked })
        assertFalse("Review is disabled while nothing is checked", vm.state.value.canReview)
    }

    @Test fun nothingToOfferSaysP77_56() = runTest(scheduler) {
        asset("p1", "Example Pump")
        hold("p1", "Example Pump")
        val vm = model()
        advanceUntilIdle()

        assertEquals(emptyList<TransferChoice>(), vm.state.value.choices)
        assertEquals("No assets can be transferred.", vm.state.value.nothingToOffer)
    }

    @Test fun aCheckedParentForcesItsComponentsCheckedDisabledAndDescribed() = runTest(scheduler) {
        estate()
        val vm = model(preselect = "h1")
        advanceUntilIdle()

        val anode = vm.state.value.choices.single { it.id == "a1" }
        assertTrue(anode.checked)
        assertFalse(anode.enabled)
        assertEquals("Included with Example Water Heater", anode.stateDescription)
        vm.toggle("a1")
        advanceUntilIdle()
        assertTrue("a forced row cannot be cleared", vm.state.value.choices.single { it.id == "a1" }.checked)
        assertEquals(listOf(AssetId("h1")), vm.state.value.roots)
        assertNull(vm.state.value.choices.single { it.id == "g1" }.stateDescription)
    }

    @Test fun refusalsBlockCreate() = runTest(scheduler) {
        estate()
        graph.loans.upsert(loanRow("l1", assetId = "g1", lentOn = "2026-09-20", borrower = "Example Buyer"))
        val vm = model()
        advanceUntilIdle()

        vm.toggle("g1")
        vm.review()
        advanceUntilIdle()
        val lent = vm.state.value.review!!
        assertEquals(listOf("Sample Garage Door Opener is lent out. Mark it returned first."), lent.refusals)
        assertFalse(lent.canCreate)

        vm.toggle("g1")
        vm.toggle("a1")
        vm.review()
        advanceUntilIdle()
        val orphan = vm.state.value.review!!
        assertEquals(
            listOf("Example Anode Rod is a component of Example Water Heater. Select Example Water Heater too."),
            orphan.refusals,
        )
        assertFalse(orphan.canCreate)
    }

    @Test fun aHeldComponentForcedInByItsParentIsRefusedAtReview() = runTest(scheduler) {
        asset("h1", "Example Water Heater")
        asset("a1", "Example Anode Rod", parent = "h1")
        hold("a1", "Example Anode Rod")
        val vm = model(preselect = "h1")
        advanceUntilIdle()
        assertEquals(listOf("h1"), vm.state.value.choices.map { it.id })

        vm.review()
        advanceUntilIdle()

        val review = vm.state.value.review!!
        assertEquals(listOf("These assets are already marked transferred out."), review.refusals)
        assertFalse(review.canCreate)
    }

    @Test fun theReviewCountsWhatWouldLeave() = runTest(scheduler) {
        TransferPackAppFixtures.seedHeater(graph)
        val vm = model(preselect = TransferPackAppFixtures.HEATER)
        advanceUntilIdle()

        vm.review()
        advanceUntilIdle()

        val review = vm.state.value.review!!
        assertEquals(listOf("1 asset", "1 NFC tag", "1 document or photo"), review.counts)
        assertTrue(review.canCreate)
    }

    @Test fun theNoteIsOneBoundedLine() = runTest(scheduler) {
        estate()
        val vm = model(preselect = "g1")
        advanceUntilIdle()
        vm.review()
        advanceUntilIdle()

        vm.onNote("Keys are\nin the drawer")
        assertEquals("Keys are in the drawer", vm.state.value.review!!.note)
        vm.onNote("x".repeat(250))
        advanceUntilIdle()
        assertEquals(200, vm.state.value.review!!.note.length)
        assertTrue(vm.state.value.review!!.canCreate)
    }
}
