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
import com.loosecannon.servicetag.ui.condition.DATE_NOT_LATER_THAN_TODAY
import com.loosecannon.servicetag.ui.replace.ReplaceStrings
import com.loosecannon.servicetag.ui.supplies.LINKED_TO
import com.loosecannon.servicetag.ui.supplies.SUPPLY_ITEM_GONE
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #47 (C25–C27; rows 50 and 51): the asset detail's Installed components section, its read state — the tree, the quiet
 * lines, the removed rows behind the toggle, the two SupplyItem sets and the read-only flag — and its sheets: the row
 * sheet's facts and history, and the install, edit, replace and remove writes through the four use cases, each refusal
 * as its sentence. All against the Room-backed `FakeGraph`, so the flows and the use cases are the production ones. Every sentence is
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
                    installComponent = graph.installComponent,
                    updateInstalledComponent = graph.updateInstalledComponent,
                    replaceInstalledComponent = graph.replaceInstalledComponent,
                    removeInstalledComponent = graph.removeInstalledComponent,
                    today = graph.todayPort,
                    io = StandardTestDispatcher(scheduler),
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

    private suspend fun replace(row: InstalledComponent, on: String): InstalledComponent = replaceAs(row, row.name, on)

    private suspend fun replaceAs(row: InstalledComponent, name: String, on: String): InstalledComponent {
        val result = graph.replaceInstalledComponent.run(row.id, ReplaceComponentCommand(on, name, null, emptyList(), "", ""))
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

    /**
     * Row 51 (C26): the install sheet (P47-3) sends what its fields hold — the name, the link picked from the
     * unarchived SupplyItems, the serial or lot, the install day and the notes — at the top level, and closes on
     * success. Save waits for a name; an empty date is an unknown install day (R47-8), sent as none.
     */
    @Test fun installPassesTheCommand() = runTest {
        val ups = asset("Example UPS")
        val frame = item("Example Tray Frame")
        val vm = model(ups.id)
        vm.state.first { it.choices.size == 1 }

        vm.startInstall()
        val blank = vm.state.first { it.form != null }.form!!
        assertEquals(INSTALL_COMPONENT, blank.title)
        assertEquals(ComponentFormTarget.Install(parentId = null), blank.target)
        assertNull(blank.inside)
        assertEquals("", blank.date)
        assertFalse(blank.canSave)
        vm.onName("Example Battery Tray")
        vm.startLinkPick()
        assertEquals(PickFor.LINK, vm.state.first { it.form?.picking != null }.form!!.picking)
        vm.pick(vm.state.value.choices.single())
        vm.onSerialOrLot("SN-EXAMPLE-01")
        vm.onDate("2026-01-15")
        vm.onNotes("Fitted at the bench")
        assertTrue(vm.state.first { it.form?.notes == "Fitted at the bench" }.form!!.canSave)
        vm.save()
        vm.state.first { it.form == null && it.rows.size == 1 }

        val stored = graph.installedComponents.forAsset(ups.id).single()
        assertEquals("Example Battery Tray", stored.name)
        assertNull(stored.parentId)
        assertEquals(frame.id, stored.supplyId)
        assertEquals("SN-EXAMPLE-01", stored.serialOrLot)
        assertEquals("2026-01-15", stored.installedOn)
        assertEquals("Fitted at the bench", stored.notes)
        assertTrue(stored.composition.isEmpty())

        vm.startInstall()
        vm.onName("Example Cooling Fan")
        vm.save()
        vm.state.first { it.form == null && it.rows.size == 2 }
        assertNull(graph.installedComponents.forAsset(ups.id).single { it.name == "Example Cooling Fan" }.installedOn)
        clearModels()
    }

    /**
     * Row 51, R47-16: "Install inside" (P47-4) on a current row's sheet opens the install sheet with that row as the
     * parent, said by P47-5, and closes the row sheet; there is no parent picker. The row lands one level down.
     */
    @Test fun installInsideCarriesTheParent() = runTest {
        val ups = asset("Example UPS")
        val tray = install(ups, "Example Battery Tray")
        val vm = model(ups.id)
        vm.state.first { it.rows.size == 1 }

        vm.open(tray.id)
        assertTrue(vm.state.first { it.rowSheet != null }.rowSheet!!.current)
        vm.startInstallInside(tray.id)
        val state = vm.state.first { it.form != null }
        assertNull(state.rowSheet)
        assertEquals(ComponentFormTarget.Install(tray.id), state.form!!.target)
        assertEquals(INSTALL_COMPONENT, state.form!!.title)
        assertEquals(insideOf("Example Battery Tray"), state.form!!.inside)
        vm.onName("Position 1")
        vm.save()
        val after = vm.state.first { it.form == null && it.rows.size == 2 }

        assertEquals(tray.id, graph.installedComponents.forAsset(ups.id).single { it.name == "Position 1" }.parentId)
        assertEquals(listOf("Example Battery Tray" to 0, "Position 1" to 1), after.rows.map { it.name to it.depth })
        clearModels()
    }

    /**
     * Row 51 (the counted RED: the replace prefill drops the link). R47-17b: the replace sheet (P47-10) starts with
     * the predecessor's name and direct link as an editable draft, the serial or lot empty and today as the
     * replacement date; nothing is written until Save, which sends the fields as they stand — so the successor names
     * the link only because the command carried it, and a link cleared from the draft is none.
     */
    @Test fun replacePrefillsNameAndLinkAsADraftAndSendsThemExplicitly() = runTest {
        val ups = asset("Example UPS")
        val cell = item("Example 12 V Battery")
        val first = install(ups, "Position 1", supplyId = cell.id, serialOrLot = "SN-OLD", installedOn = "2025-03-04")
        val second = install(ups, "Position 2", supplyId = cell.id)
        val vm = model(ups.id)
        vm.state.first { it.rows.size == 2 && it.choices.size == 1 }

        vm.startReplace(first.id)
        val draft = vm.state.first { it.form != null }.form!!
        assertEquals(replaceTitle("Position 1"), draft.title)
        assertEquals(ComponentFormTarget.Replace(first.id), draft.target)
        assertEquals("Position 1", draft.name)
        assertEquals(cell.id, draft.supplyId)
        assertEquals("", draft.serialOrLot)
        assertEquals("2026-02-10", draft.date)
        assertTrue(graph.installedComponents.forAsset(ups.id).all { it.isCurrent })
        vm.save()
        vm.state.first { s -> s.form == null && s.components.any { it.replacesId == first.id } }

        val successor = graph.installedComponents.forAsset(ups.id).single { it.replacesId == first.id }
        assertEquals("Position 1", successor.name)
        assertEquals(cell.id, successor.supplyId)
        assertEquals("", successor.serialOrLot)
        assertEquals("2026-02-10", successor.installedOn)
        assertEquals("2026-02-10", graph.installedComponents.get(first.id)!!.removedOn)

        vm.startReplace(second.id)
        assertEquals(cell.id, vm.state.first { it.form != null }.form!!.supplyId)
        vm.unlink()
        vm.onName("Position 2 spare")
        vm.save()
        vm.state.first { s -> s.form == null && s.components.any { it.replacesId == second.id } }
        val cleared = graph.installedComponents.forAsset(ups.id).single { it.replacesId == second.id }
        assertNull(cleared.supplyId)
        assertEquals("Position 2 spare", cleared.name)
        clearModels()
    }

    /**
     * Row 51, R47-6: the remove sheet (P47-11) starts on today and says P47-12 only for a row with current children,
     * as the replace sheet does; Cancel writes nothing, and "Remove" closes the row and its subtree on the date typed.
     */
    @Test fun removeWithChildrenDrawsP47_12() = runTest {
        val ups = asset("Example UPS")
        val tray = install(ups, "Example Battery Tray", installedOn = "2025-03-04")
        val one = install(ups, "Position 1", parent = tray)
        val fan = install(ups, "Example Cooling Fan")
        val vm = model(ups.id)
        vm.state.first { it.rows.size == 3 }

        vm.startRemove(fan.id)
        val leaf = vm.state.first { it.removing != null }.removing!!
        assertEquals(removeTitle("Example Cooling Fan"), leaf.title)
        assertEquals("2026-02-10", leaf.removedOn)
        assertFalse(leaf.subtreeToo)
        vm.dismissRemove()
        vm.state.first { it.removing == null }
        assertTrue(graph.installedComponents.forAsset(ups.id).all { it.isCurrent })

        vm.startReplace(tray.id)
        assertTrue(vm.state.first { it.form != null }.form!!.subtreeToo)
        vm.dismissForm()
        vm.startReplace(fan.id)
        assertFalse(vm.state.first { it.form?.target == ComponentFormTarget.Replace(fan.id) }.form!!.subtreeToo)
        vm.dismissForm()

        vm.startRemove(tray.id)
        assertTrue(vm.state.first { it.removing?.rowId == tray.id }.removing!!.subtreeToo)
        vm.onRemovedOn("2026-02-01")
        vm.confirmRemove()
        vm.state.first { it.removing == null && it.rows.size == 1 }

        assertEquals("2026-02-01", graph.installedComponents.get(tray.id)!!.removedOn)
        assertEquals("2026-02-01", graph.installedComponents.get(one.id)!!.removedOn)
        assertNull(graph.installedComponents.get(fan.id)!!.removedOn)
        clearModels()
    }

    /**
     * Row 51, C26's refusals: each problem draws its ratified sentence under the field it names and keeps the sheet
     * open, writing nothing — a day after today, a malformed day, a removal before the install day, a SupplyItem
     * archived after the pick, and a row or parent closed underneath (P47-19).
     */
    @Test fun eachProblemDrawsItsSentenceUnderItsField() = runTest {
        val ups = asset("Example UPS")
        val frame = item("Example Tray Frame")
        val tray = install(ups, "Example Battery Tray", installedOn = "2025-03-04")
        val fan = install(ups, "Example Cooling Fan")
        val vm = model(ups.id)
        vm.state.first { it.rows.size == 2 && it.choices.size == 1 }

        vm.startInstall()
        vm.onName("Example Bracket")
        vm.onDate("2026-03-01")
        vm.save()
        val later = vm.state.first { it.form?.dateProblem != null }.form!!
        assertEquals(DATE_NOT_LATER_THAN_TODAY, later.dateProblem)
        assertFalse(later.saving)
        vm.onDate("2026-1-5")
        assertNull(vm.state.first { it.form?.date == "2026-1-5" }.form!!.dateProblem)
        vm.save()
        assertEquals(COMPONENT_CHANGED, vm.state.first { it.form?.dateProblem != null }.form!!.dateProblem)
        vm.onDate("")
        vm.startLinkPick()
        vm.pick(vm.state.value.choices.single())
        graph.archiveSupplyItem.run(frame.id, archived = true)
        vm.save()
        val archived = vm.state.first { it.form?.linkProblem != null }.form!!
        assertEquals(SUPPLY_ITEM_GONE, archived.linkProblem)
        assertNull(archived.dateProblem)
        vm.dismissForm()
        assertEquals(2, graph.installedComponents.forAsset(ups.id).size)

        vm.startRemove(tray.id)
        vm.onRemovedOn("2025-01-01")
        vm.confirmRemove()
        assertEquals(REMOVAL_BEFORE_INSTALL, vm.state.first { it.removing?.dateProblem != null }.removing!!.dateProblem)
        vm.dismissRemove()

        vm.startReplace(fan.id)
        vm.state.first { it.form != null }
        remove(fan, on = "2026-02-01")
        vm.save()
        assertEquals(COMPONENT_CHANGED, vm.state.first { it.form?.problem != null }.form!!.problem)
        vm.dismissForm()

        vm.startInstallInside(tray.id)
        vm.state.first { it.form != null }
        remove(tray, on = "2026-02-01")
        vm.onName("Position 1")
        vm.save()
        assertEquals(COMPONENT_CHANGED, vm.state.first { it.form?.problem != null }.form!!.problem)
        vm.dismissForm()

        val rows = graph.installedComponents.forAsset(ups.id)
        assertEquals(setOf("Example Battery Tray", "Example Cooling Fan"), rows.map { it.name }.toSet())
        assertTrue(rows.none { it.replacesId != null })
        clearModels()
    }

    /**
     * C-1, R47-17b: a replace draft whose link names an archived SupplyItem keeps it, drawn from the every-item map
     * with its mark and never offered by the picker; Save is refused with P15-20 under the link and writes nothing
     * until "Remove link" clears it, and then the successor has no link.
     */
    @Test fun anArchivedDraftLinkIsRefusedUntilRemoved() = runTest {
        val ups = asset("Example UPS")
        val cell = item("Example 12 V Battery")
        val first = install(ups, "Position 1", supplyId = cell.id)
        graph.archiveSupplyItem.run(cell.id, archived = true)
        val vm = model(ups.id)
        vm.state.first { s -> s.rows.size == 1 && s.supplies[cell.id]?.archived == true }

        vm.startReplace(first.id)
        val draft = vm.state.first { it.form != null }
        assertEquals(cell.id, draft.form!!.supplyId)
        assertTrue(draft.supplies.getValue(cell.id).archived)
        assertTrue(draft.choices.isEmpty())
        vm.save()
        assertEquals(SUPPLY_ITEM_GONE, vm.state.first { it.form?.linkProblem != null }.form!!.linkProblem)
        assertEquals(listOf(first.id), graph.installedComponents.forAsset(ups.id).map { it.id })
        assertTrue(graph.installedComponents.get(first.id)!!.isCurrent)

        vm.unlink()
        assertNull(vm.state.first { it.form?.supplyId == null }.form!!.linkProblem)
        vm.save()
        vm.state.first { s -> s.form == null && s.components.any { it.replacesId == first.id } }
        assertNull(graph.installedComponents.forAsset(ups.id).single { it.replacesId == first.id }.supplyId)
        clearModels()
    }

    /**
     * R47-15, C26: the edit sheet (P47-13) holds the whole editable set as stored, its composition included, so an
     * edit of the name keeps every entry and its id; an edit changing nothing closes quietly and writes nothing; a
     * removed row is edited too.
     */
    @Test fun anEditKeepsTheCompositionAndAnUnchangedOneWritesNothing() = runTest {
        val ups = asset("Example UPS")
        val cell = item("Example 12 V Battery")
        val coolant = item("Example Coolant", unit = "L")
        val pack = install(
            ups, "Example Battery Pack", composition = listOf(entry(cell, "4"), entry(coolant, "0.5", "L")),
            serialOrLot = "SN-EXAMPLE-01", installedOn = "2025-03-04",
        )
        val bracket = install(ups, "Example Bracket")
        remove(bracket, on = "2026-02-01")
        val vm = model(ups.id)
        vm.state.first { it.rows.size == 1 && it.removed.size == 1 }

        vm.startEdit(pack.id)
        val form = vm.state.first { it.form != null }.form!!
        assertEquals(EDIT_COMPONENT, form.title)
        assertEquals("2025-03-04", form.date)
        assertEquals("SN-EXAMPLE-01", form.serialOrLot)
        assertEquals(listOf("4", "0.5"), form.composition.map { it.quantity })
        val before = graph.installedComponents.get(pack.id)!!
        graph.now = 2_000L
        vm.save()
        vm.state.first { it.form == null }
        assertEquals(before, graph.installedComponents.get(pack.id))
        assertEquals(1_000L, before.updatedAt)

        vm.startEdit(pack.id)
        vm.onName("Example Battery Pack A")
        vm.save()
        vm.state.first { s -> s.form == null && s.rows.any { it.name == "Example Battery Pack A" } }
        val edited = graph.installedComponents.get(pack.id)!!
        assertEquals(before.composition, edited.composition)
        assertEquals(2_000L, edited.updatedAt)
        assertEquals("2025-03-04", edited.installedOn)

        vm.startEdit(bracket.id)
        assertEquals(EDIT_COMPONENT, vm.state.first { it.form != null }.form!!.title)
        vm.onSerialOrLot("SN-EXAMPLE-02")
        vm.save()
        vm.state.first { s -> s.form == null && s.components.any { it.serialOrLot == "SN-EXAMPLE-02" } }
        assertEquals("2026-02-01", graph.installedComponents.get(bracket.id)!!.removedOn)
        clearModels()
    }

    /**
     * C26, N-12: the row sheet reads the stored facts and the position's instances newest first — each with its
     * install line, #86's "Replaced by {name} on {day}" or P47-17, and "Replaces {name}"; a removed row reads P47-16
     * for an unknown install day and its removal day.
     */
    @Test fun theRowSheetReadsFactsAndHistoryNewestFirst() = runTest {
        val ups = asset("Example UPS")
        val cell = item("Example 12 V Battery")
        val first = install(ups, "Position 1", supplyId = cell.id, serialOrLot = "SN-A", installedOn = "2025-03-04")
        val second = replaceAs(first, "Position 1 B", on = "2025-09-01")
        val third = replaceAs(second, "Position 1 C", on = "2026-01-20")
        val bracket = install(ups, "Example Bracket")
        remove(bracket, on = "2026-02-01")
        val vm = model(ups.id)
        vm.state.first { it.rows.size == 1 && it.removed.size == 1 }

        vm.open(third.id)
        val sheet = vm.state.first { it.rowSheet != null }.rowSheet!!
        assertTrue(sheet.current)
        assertEquals("Position 1 C", sheet.name)
        assertNull(sheet.supplyId)
        assertEquals(ReplaceStrings.day("2026-01-20"), sheet.installedDay)
        assertNull(sheet.removedDay)
        assertEquals(
            listOf(
                HistoryLineState(third.id, "Position 1 C", installedOnDay("2026-01-20"), null, ReplaceStrings.replaces("Position 1 B")),
                HistoryLineState(
                    second.id, "Position 1 B", installedOnDay("2025-09-01"),
                    ReplaceStrings.replacedBy("Position 1 C", "2026-01-20"), ReplaceStrings.replaces("Position 1"),
                ),
                HistoryLineState(
                    first.id, "Position 1", installedOnDay("2025-03-04"), ReplaceStrings.replacedBy("Position 1 B", "2025-09-01"), null,
                ),
            ),
            sheet.history,
        )

        vm.open(bracket.id)
        val removed = vm.state.first { it.rowSheet?.id == bracket.id }.rowSheet!!
        assertFalse(removed.current)
        assertNull(removed.installedDay)
        assertEquals(ReplaceStrings.day("2026-02-01"), removed.removedDay)
        assertEquals(
            listOf(HistoryLineState(bracket.id, "Example Bracket", INSTALL_DATE_NOT_RECORDED, removedOnDay("2026-02-01"), null)),
            removed.history,
        )
        vm.closeRow()
        assertNull(vm.state.first { it.rowSheet == null }.rowSheet)
        clearModels()
    }

    /** #77: a hold closes the write sheets and offers none, while a row's sheet still opens to its facts and history. */
    @Test fun readOnlyClosesTheWriteSheetsAndKeepsTheRowSheet() = runTest {
        val ups = asset("Example UPS")
        val tray = install(ups, "Example Battery Tray")
        val vm = model(ups.id)
        vm.state.first { it.rows.size == 1 }

        vm.startInstall()
        vm.state.first { it.form != null }
        vm.setReadOnly(true)
        val held = vm.state.first { !it.offersWrites }
        assertNull(held.form)
        vm.startInstall()
        vm.startEdit(tray.id)
        vm.startReplace(tray.id)
        vm.startRemove(tray.id)
        vm.open(tray.id)
        val opened = vm.state.first { it.rowSheet != null }
        assertNull(opened.form)
        assertNull(opened.removing)
        assertEquals("Example Battery Tray", opened.rowSheet!!.name)
        clearModels()
    }
}
