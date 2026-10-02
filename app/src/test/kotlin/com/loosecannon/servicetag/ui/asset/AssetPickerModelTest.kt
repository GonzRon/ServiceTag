package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.LoanStanding
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.model.isRetired
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.loanRow
import java.time.LocalDate
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
 * #93 (C1, C2; rows 1–13) — the Share intake's picker hosts the Assets tab's own list model, switched only to drop
 * **held** rows once, first, before the controls, the archived count and the empty reason run. Everything else is
 * the tab's: the six-field query, Type by catalog key, Archived and Components off by default, the order (active,
 * retired, archived; by name), the parent names and the badges. With nothing held the picker's state is the tab's.
 * Over a Room-backed [FakeGraph]; every name is fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetPickerModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse("2026-09-28")
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    /** The picker's model: the tab's, with held rows dropped first (C2). */
    private fun TestScope.pickerModel() = AssetsViewModel(
        graph.assets, graph.categories, graph.seasonActivations, graph.tags, graph.assetHealthReadModel, graph.todayPort,
        loans = graph.loans, transfers = graph.transferRecords,
        excludeHeld = true,
    ).also { vm -> backgroundScope.launch { vm.state.collect() } }

    /** The tab's model, exactly as the tab's own tests build it. */
    private fun TestScope.tabModel() = AssetsViewModel(
        graph.assets, graph.categories, graph.seasonActivations, graph.tags, graph.assetHealthReadModel, graph.todayPort,
        loans = graph.loans, transfers = graph.transferRecords,
    ).also { vm -> backgroundScope.launch { vm.state.collect() } }

    private fun TestScope.settled(vm: AssetsViewModel): AssetsState {
        advanceUntilIdle()
        return vm.state.value
    }

    private val AssetsState.ids: List<String> get() = items.map { it.asset.id.value }

    private suspend fun seed(vararg rows: Asset) = rows.forEach { graph.assets.upsert(it) }

    /** One open OUT: the asset is held — transferred out from this phone — whatever its status. */
    private suspend fun out(asset: String) = graph.transferRecords.append(
        TransferRecord(
            id = "out-$asset", assetId = AssetId(asset), kind = TransferKind.OUT, packId = PACK,
            lineage = emptyList(), at = AT, packSha256 = "ab".repeat(32), nameSnapshot = "Example asset", note = "",
        ),
    )

    // Row 1 (C2 defaults).
    @Test fun byDefaultOnlyActiveRootAssetsAreOffered() = runTest(scheduler) {
        seed(
            assetRow("mower", name = "Example Mower"),
            assetRow("spa", name = "Sample Spa"),
            assetRow("pump", name = "Spa Pump", parent = "spa"),
            assetRow("ladder", name = "Old Ladder", status = AssetStatus.ARCHIVED),
        )

        val state = settled(pickerModel())

        assertEquals(AssetFilters(), state.filters)
        assertEquals(listOf("mower", "spa"), state.ids)
        assertEquals(EmptyReason.NONE, state.emptyReason)
        assertEquals("the archived row is counted behind the control", 1, state.archivedCount)
    }

    // Row 2 (C2; AC2, AC3): #39's six fields, reused as they are.
    @Test fun aQueryNarrowsByNameAndByEveryOtherSearchedField() = runTest(scheduler) {
        seed(
            assetRow("byName", name = "Example Mower"),
            assetRow("byCategory", name = "Sample Unit A").copy(category = "Pump"),
            assetRow("byMake", name = "Sample Unit B").copy(manufacturer = "Acme Works"),
            assetRow("byModel", name = "Sample Unit C").copy(model = "Q-200"),
            assetRow("bySerial", name = "Sample Unit D").copy(serialNumber = "SN-4417"),
            assetRow("byPlace", name = "Sample Unit E").copy(location = "Back shed"),
        )
        val vm = pickerModel()
        assertEquals(6, settled(vm).items.size)

        listOf(
            "MOWER" to "byName",
            "pump" to "byCategory",
            "acme" to "byMake",
            "q-200" to "byModel",
            "4417" to "bySerial",
            "shed" to "byPlace",
        ).forEach { (query, id) ->
            vm.onQueryChange(query)
            val state = settled(vm)
            assertEquals("\"$query\"", listOf(id), state.ids)
            assertEquals(query, state.query)
        }
        vm.onQueryChange("sample unit")
        assertEquals("a prefix narrows to its five", 5, settled(vm).items.size)
    }

    // Row 3 (C2; AC4): Type by the catalog's key, so a spelling stored before promotion still matches (R73-5).
    @Test fun typeOffersOnlyThatCategory() = runTest(scheduler) {
        seed(
            assetRow("sump", name = "Example Sump").copy(category = "Pump"),
            assetRow("seeded", name = "Sample Well Pump").copy(category = "pUMP"),
            assetRow("spa", name = "Sample Spa").copy(category = "Hot tub"),
            assetRow("held", name = "Example Pool Pump").copy(category = "Pump"),
        )
        out("held")
        val vm = pickerModel()
        settled(vm)

        vm.pickType("pump")
        val pumps = settled(vm)
        assertEquals(listOf("sump", "seeded"), pumps.ids)
        assertEquals("Pump", pumps.typeLabel)

        vm.pickType(null)
        assertEquals(listOf("sump", "spa", "seeded"), settled(vm).ids)
    }

    // Row 4 (C2; R93-2).
    @Test fun archivedAssetsAppearOnlyWithArchivedOn() = runTest(scheduler) {
        seed(
            assetRow("mower", name = "Example Mower"),
            assetRow("old", name = "Old Mower", status = AssetStatus.ARCHIVED),
        )
        val vm = pickerModel()
        assertEquals(listOf("mower"), settled(vm).ids)

        vm.toggleArchived()
        val shown = settled(vm)
        assertTrue(shown.filters.showArchived)
        assertEquals("active first, archived after", listOf("mower", "old"), shown.ids)

        vm.toggleArchived()
        assertEquals(listOf("mower"), settled(vm).ids)
    }

    // Row 5 (C2; R93-3).
    @Test fun componentsAppearOnlyWithComponentsOn() = runTest(scheduler) {
        seed(
            assetRow("spa", name = "Example Spa"),
            assetRow("pump", name = "Spa Pump", parent = "spa"),
        )
        val vm = pickerModel()
        assertEquals(listOf("spa"), settled(vm).ids)

        vm.toggleComponents()
        val shown = settled(vm)
        assertEquals(listOf("spa", "pump"), shown.ids)
        assertEquals("still naming its parent", "Example Spa", shown.items.last().parentName)
        assertNull(shown.items.first().parentName)

        vm.toggleComponents()
        assertEquals(listOf("spa"), settled(vm).ids)
    }

    // Row 6 (C2; rm-5, AC7): counted RED — the switch ignored, so the tab's rule lists held rows with Archived on.
    @Test fun aHeldAssetIsNeverOfferedArchivedOnOrOff() = runTest(scheduler) {
        seed(
            assetRow("heldActive", name = "Example Water Heater"),
            assetRow("heldArchived", name = "Example Heat Pump", status = AssetStatus.ARCHIVED),
            assetRow("heldPart", name = "Heater Anode", parent = "heldActive"),
            assetRow("garage", name = "Sample Garage Door Opener"),
            assetRow("ladder", name = "Sample Ladder", status = AssetStatus.ARCHIVED),
        )
        out("heldActive")
        out("heldArchived")
        out("heldPart")
        val vm = pickerModel()
        val held = setOf("heldActive", "heldArchived", "heldPart")

        data class Controls(val components: Boolean, val archived: Boolean)
        val seen = mutableMapOf<Controls, List<String>>()
        listOf(
            Controls(components = false, archived = false),
            Controls(components = false, archived = true),
            Controls(components = true, archived = true),
            Controls(components = true, archived = false),
        ).forEach { want ->
            val now = settled(vm).filters
            if (now.showArchived != want.archived) vm.toggleArchived()
            if (now.showComponents != want.components) vm.toggleComponents()
            val state = settled(vm)
            assertEquals(want, Controls(state.filters.showComponents, state.filters.showArchived))
            assertTrue("$want: no held row offered, got ${state.ids}", state.ids.none { it in held })
            assertTrue("$want: no row marked transferred", state.items.none { it.transferred })
            seen[want] = state.ids
        }
        assertEquals("the ordinary archived row still shows", listOf("garage", "ladder"), seen.getValue(Controls(false, true)))
        assertEquals(listOf("garage"), seen.getValue(Controls(false, false)))
    }

    // Row 7 (C2): counted RED — the switch filters `items` after the controls instead of the rows before them.
    @Test fun aHeldOnlyMatchSaysNothingMatchesAndIsNotCountedArchived() = runTest(scheduler) {
        seed(
            assetRow("heldActive", name = "Example Water Heater"),
            assetRow("heldArchived", name = "Example Heat Pump", status = AssetStatus.ARCHIVED),
            assetRow("garage", name = "Sample Garage Door Opener"),
            assetRow("ladder", name = "Sample Ladder", status = AssetStatus.ARCHIVED),
        )
        out("heldActive")
        out("heldArchived")
        val vm = pickerModel()
        assertEquals("held rows are never counted archived", 1, settled(vm).archivedCount)

        vm.onQueryChange("heat")
        val state = settled(vm)
        assertEquals(emptyList<String>(), state.ids)
        assertEquals("never \"Turn on Archived\" for rows it would not show", EmptyReason.NOTHING_MATCHES, state.emptyReason)
        assertEquals(1, state.archivedCount)

        vm.toggleArchived()
        val archivedOn = settled(vm)
        assertEquals(emptyList<String>(), archivedOn.ids)
        assertEquals(EmptyReason.NOTHING_MATCHES, archivedOn.emptyReason)
    }

    // Row 8 (C2; R93-8).
    @Test fun everyEligibleAssetArchivedSaysNoActiveAssets() = runTest(scheduler) {
        seed(
            assetRow("ladder", name = "Sample Ladder", status = AssetStatus.ARCHIVED),
            assetRow("mower", name = "Old Mower", status = AssetStatus.ARCHIVED),
            assetRow("held", name = "Example Water Heater"),
        )
        out("held")
        val vm = pickerModel()

        val state = settled(vm)
        assertEquals(emptyList<String>(), state.ids)
        assertEquals(EmptyReason.NO_ACTIVE_ASSETS, state.emptyReason)
        assertEquals("the held row is not behind Archived", 2, state.archivedCount)

        vm.toggleArchived()
        assertEquals(listOf("mower", "ladder"), settled(vm).ids)
    }

    // Row 9 (C2; AC9): the tab's reasons, held rows playing no part in them.
    @Test fun eachEmptyMatchCarriesTheTabsReason() = runTest(scheduler) {
        seed(
            assetRow("spa", name = "Example Spa").copy(category = "Hot tub"),
            assetRow("filter", name = "Spa Filter", parent = "spa"),
            assetRow("spareFilter", name = "Spare Filter"),
            assetRow("hose", name = "Old Hose", status = AssetStatus.ARCHIVED),
            assetRow("coverPart", name = "Spa Cover", parent = "spa"),
            assetRow("coverOld", name = "Old Cover", status = AssetStatus.ARCHIVED),
            assetRow("sump", name = "Example Sump").copy(category = "Pump"),
        )
        out("spareFilter")
        val vm = pickerModel()
        settled(vm)

        fun reasonFor(query: String): EmptyReason {
            vm.onQueryChange(query)
            val state = settled(vm)
            assertEquals("\"$query\" lists nothing", emptyList<String>(), state.ids)
            return state.emptyReason
        }

        assertEquals("only Child assets hides it; the held match is gone", EmptyReason.COMPONENTS_HIDDEN, reasonFor("filter"))
        assertEquals(EmptyReason.ARCHIVED_HIDDEN, reasonFor("hose"))
        assertEquals(EmptyReason.BOTH_HIDDEN, reasonFor("cover"))
        vm.pickType("hot tub")
        assertEquals(EmptyReason.TYPE_HIDDEN, reasonFor("sump"))
    }

    // Row 10 (AC5).
    @Test fun clearingTheQueryRestoresTheEligibleList() = runTest(scheduler) {
        seed(
            assetRow("garage", name = "Sample Garage Door Opener"),
            assetRow("mower", name = "Example Mower"),
            assetRow("held", name = "Example Garage Heater"),
        )
        out("held")
        val vm = pickerModel()
        val eligible = settled(vm)
        assertEquals(listOf("mower", "garage"), eligible.ids)

        vm.onQueryChange("garage")
        assertEquals(listOf("garage"), settled(vm).ids)

        vm.clearQuery()
        val cleared = settled(vm)
        assertEquals("", cleared.query)
        assertEquals(eligible, cleared)
    }

    // Row 11 (R93-6, R93-13): offered by default, with the tab's badges, after the active rows.
    @Test fun retiredReplacedAndLentAssetsAreOfferedWithTheirBadgesAfterActiveOnes() = runTest(scheduler) {
        seed(
            assetRow("ladder", name = "Sample Ladder"),
            assetRow("drill", name = "Example Drill"),
            assetRow("generator", name = "Example Generator", retiredOn = "2026-03-01"),
            // #86: a replaced predecessor is retired, never archived.
            assetRow("oldMower", name = "Example Mower (2019)", retiredOn = "2026-05-01"),
        )
        graph.loans.upsert(loanRow("l1", "drill", lentOn = "2026-09-20"))
        val state = settled(pickerModel())

        assertEquals(listOf("drill", "ladder", "generator", "oldMower"), state.ids)
        val byId = state.items.associateBy { it.asset.id.value }
        assertEquals(LoanStanding.LENT_OUT, byId.getValue("drill").loan)
        assertFalse("lent, and still active", byId.getValue("drill").asset.isRetired)
        assertFalse("no badge on the plain active row", byId.getValue("ladder").asset.isRetired)
        assertNull(byId.getValue("ladder").loan)
        assertTrue("Retired", byId.getValue("generator").asset.isRetired)
        assertTrue("Retired, the replaced predecessor too", byId.getValue("oldMower").asset.isRetired)
        assertEquals(0, state.archivedCount)
    }

    // Row 12 (AC8): counted RED — the switch also drops ARCHIVED rows (the tempting "eligible = active" reading).
    @Test fun withNothingHeldThePickerStateEqualsTheTabs() = runTest(scheduler) {
        seed(
            assetRow("spa", name = "Example Spa").copy(category = "Hot tub"),
            assetRow("filter", name = "Spa Filter", parent = "spa").copy(category = "Pump"),
            assetRow("sump", name = "Example Sump Pump").copy(category = "Pump", location = "Basement"),
            assetRow("generator", name = "Example Generator", retiredOn = "2026-03-01").copy(category = "Generator"),
            assetRow("oldSpa", name = "Old Spa", status = AssetStatus.ARCHIVED).copy(category = "Hot tub"),
            assetRow("oldPart", name = "Old Spa Pump", parent = "oldSpa", status = AssetStatus.ARCHIVED).copy(category = "Pump"),
            assetRow("drill", name = "Example Drill"),
        )
        graph.loans.upsert(loanRow("l1", "drill", lentOn = "2026-09-20"))
        val tab = tabModel()
        val picker = pickerModel()

        data class Combo(val query: String, val type: String?, val components: Boolean, val archived: Boolean)
        val walk = listOf(
            Combo("", null, false, false),
            Combo("", null, false, true),
            Combo("", null, true, true),
            Combo("", null, true, false),
            Combo("", "pump", true, false),
            Combo("", "pump", true, true),
            Combo("", "pump", false, true),
            Combo("", "pump", false, false),
            Combo("spa", "pump", false, false),
            Combo("spa", "pump", true, false),
            Combo("spa", "pump", true, true),
            Combo("spa", null, true, true),
            Combo("spa", null, false, true),
            Combo("spa", null, false, false),
            Combo("basement", null, false, false),
            Combo("nothing here", null, false, false),
        )
        var listedArchived = false
        walk.forEach { to ->
            val from = settled(tab).filters
            listOf(tab, picker).forEach { vm ->
                if (from.showArchived != to.archived) vm.toggleArchived()
                if (from.showComponents != to.components) vm.toggleComponents()
                if (from.type != to.type) vm.pickType(to.type)
                vm.onQueryChange(to.query)
            }
            val expected = settled(tab)
            val actual = settled(picker)
            assertEquals("$to: the tab's controls", AssetFilters(to.type, to.components, to.archived), expected.filters)
            assertEquals("$to", expected, actual)
            if (actual.items.any { it.asset.status == AssetStatus.ARCHIVED }) listedArchived = true
        }
        assertTrue("the walk listed archived rows", listedArchived)
    }

    // Row 13 (C2): counted RED — `byId` built from the reduced rows, so the component loses "Part of <parent>".
    @Test fun aComponentOfAHeldParentStillNamesItsParent() = runTest(scheduler) {
        seed(
            assetRow("heater", name = "Example Water Heater", status = AssetStatus.ARCHIVED),
            assetRow("anode", name = "Heater Anode", parent = "heater"),
        )
        out("heater")
        val vm = pickerModel()
        vm.toggleComponents()

        val state = settled(vm)
        assertEquals(listOf("anode"), state.ids)
        assertEquals("Example Water Heater", state.items.single().parentName)
    }

    private companion object {
        const val PACK = "0f1e2d3c-4b5a-4968-8776-655443322110"
        const val AT = 1_790_510_400_000L // 27 Sep 2026, 12:00 UTC
    }
}
