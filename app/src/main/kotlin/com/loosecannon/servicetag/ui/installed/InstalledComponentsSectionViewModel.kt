package com.loosecannon.servicetag.ui.installed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompositionEntry
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.InstalledComponentTree
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.ports.InstalledComponentRepository
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.usecase.CompositionInput
import com.loosecannon.servicetag.core.usecase.InstallComponent
import com.loosecannon.servicetag.core.usecase.InstallComponentCommand
import com.loosecannon.servicetag.core.usecase.InstalledComponentProblem
import com.loosecannon.servicetag.core.usecase.InstalledComponentResult
import com.loosecannon.servicetag.core.usecase.RemoveInstalledComponent
import com.loosecannon.servicetag.core.usecase.ReplaceComponentCommand
import com.loosecannon.servicetag.core.usecase.ReplaceInstalledComponent
import com.loosecannon.servicetag.core.usecase.UpdateInstalledComponent
import com.loosecannon.servicetag.core.usecase.UpdateInstalledComponentCommand
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.asset.ENTER_A_DATE_AS_YYYY_MM_DD
import com.loosecannon.servicetag.ui.condition.DATE_NOT_LATER_THAN_TODAY
import com.loosecannon.servicetag.ui.journal.formatNumber
import com.loosecannon.servicetag.ui.replace.ReplaceStrings
import com.loosecannon.servicetag.ui.supplies.LINKED_TO
import com.loosecannon.servicetag.ui.supplies.SUPPLY_ITEM_GONE
import com.loosecannon.servicetag.ui.supplies.SupplyListRow
import com.loosecannon.servicetag.ui.supplies.listRowsOf
import com.loosecannon.servicetag.ui.transfer.transferredOutOr
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How long the repository flows stay hot after the last collector leaves (a rotation, typically). */
private const val SUBSCRIPTION_GRACE_MS = 5_000L

/** The joiner of the quiet line's pieces (C25), the Supplies list's detail joiner. */
private const val QUIET_JOINER = " · "

/** How many composition entries the quiet line names before P47-22 counts the rest (C25). */
private const val ENTRIES_NAMED = 2

/**
 * One installed component as the section draws it (C25): its [name], indented [depth] steps of 16 dp, and the
 * [quiet] line joining what is there — the direct link with its part number, the composition, the serial or lot,
 * and the date. [archived] is true while the direct SupplyItem is archived: the row wears the shipped badge.
 *
 * [inside] is P47-5 for a nested current row: the content description that says what the indent shows. A removed
 * row is drawn flat, so its parent, when that is not current, leads its [quiet] line instead and [inside] is null.
 */
data class InstalledComponentRowState(
    val id: InstalledComponentId,
    val name: String,
    val depth: Int,
    val inside: String?,
    val quiet: String,
    val archived: Boolean,
)

data class InstalledComponentsState(
    /** The current rows, parents first and depth-annotated, siblings `(sortOrder, name casefolded, id)` (C5, C25). */
    val rows: List<InstalledComponentRowState> = emptyList(),
    /** The removed rows nothing replaced, newest removal first (R47-16): drawn behind the P47-18 toggle. */
    val removed: List<InstalledComponentRowState> = emptyList(),
    /** The toggle's state; collapsed until the person opens it. */
    val showRemoved: Boolean = false,
    /** Every row of this asset, current and removed, by id: what the row sheet's facts and history read. */
    val components: List<InstalledComponent> = emptyList(),
    /**
     * Every SupplyItem, **archived included**, by id (C-1): the names and badges `SupplyLinkLine` and the composition
     * lines draw. `SupplyLinkLine` draws nothing for an id it does not hold, so a link or entry naming an archived
     * item must find it here.
     */
    val supplies: Map<SupplyId, SupplyListRow> = emptyMap(),
    /** What a picker offers: the **unarchived** SupplyItems only, in the Supplies list's order (R47-3). */
    val choices: List<SupplyListRow> = emptyList(),
    /** Each SupplyItem's preferred unit, by id: what a composition pick fills a blank "Unit" with (#15 C34's rule). */
    val preferredUnits: Map<SupplyId, String> = emptyMap(),
    /** False on a held asset: the rows, the toggle and the facts draw, and nothing that writes is offered. */
    val offersWrites: Boolean = true,
    /** The open row's sheet (C26), or null; it closes by itself once the row is gone. */
    val rowSheet: RowSheetState? = null,
    /** The open install, edit or replace sheet (C26), or null. */
    val form: ComponentFormState? = null,
    /** The open remove sheet (C26), or null. */
    val removing: RemoveSheetState? = null,
)

/**
 * One instance that has held a row's position (C26, N-12), newest first on the row sheet: its [name], its [installed]
 * line (P47-15, or P47-16 when the day is not recorded), how it left — #86's "Replaced by {name} on {day}" or P47-17 —
 * in [closed] (null while it is current), and #86's "Replaces {name}" in [replaces] when it has a predecessor.
 */
data class HistoryLineState(
    val id: InstalledComponentId,
    val name: String,
    val installed: String,
    val closed: String?,
    val replaces: String?,
)

/**
 * The row sheet (C26): one row's facts, read from the stored row and never copied — its [name], the direct SupplyItem
 * ([supplyId], drawn in `SupplyLinkLine`'s words and opened on a tap), its [composition] (drawn by the composition
 * display), the serial or lot, the install day ([installedDay], null when not recorded: P47-16), the removal day — and
 * its [history]. A [current] row offers Install inside, Replace, Remove and Edit; a removed row offers Edit only.
 */
data class RowSheetState(
    val id: InstalledComponentId,
    val name: String,
    val current: Boolean,
    val supplyId: SupplyId?,
    val composition: List<CompositionEntry>,
    val serialOrLot: String,
    val installedDay: String?,
    val removedDay: String?,
    val history: List<HistoryLineState>,
)

/** What the install, edit or replace sheet writes (C26): the use case its Save calls, and the row it names. */
sealed interface ComponentFormTarget {
    /** P47-3: a new row at the top level, or inside [parentId] ("Install inside", P47-4). */
    data class Install(val parentId: InstalledComponentId?) : ComponentFormTarget

    /** P47-13: the whole editable set of [id] (R47-15); [sortOrder] rides along unchanged — the phone has no move control. */
    data class Edit(val id: InstalledComponentId, val sortOrder: Int) : ComponentFormTarget

    /** P47-10: closes [id] and fits a successor in its place on the date typed (C18). */
    data class Replace(val id: InstalledComponentId) : ComponentFormTarget
}

/**
 * What an open picker fills: the direct link, or a composition entry — the one at [ComponentFormState.pickingEntry],
 * or a new one appended when that is null ("Add supply").
 */
enum class PickFor { LINK, ENTRY }

/**
 * The install, edit and replace sheet (C26), one shape for the three [target]s. The fields hold exactly what the
 * person sees: on replace they start as an editable draft of the predecessor's name, direct link and composition
 * (R47-17b), and Save sends them as they stand — a cleared link is none, an emptied composition is none.
 * [composition] is the draft the composition editor draws and edits: the stored entries with their ids on edit (so an
 * edit keeps them), the predecessor's entries with **no** ids on replace (the successor's are minted fresh), none on
 * install. [date] is "Installed on" (P47-7), on replace the replacement date. Each problem is the sentence drawn under
 * its field: [dateProblem], [linkProblem], [compositionProblem] with the [markedEntries] it names, an entry's own line
 * in [entryProblems], and P47-19 in [problem].
 */
data class ComponentFormState(
    val target: ComponentFormTarget,
    val title: String,
    /** P47-5 when installing inside a row. */
    val inside: String?,
    val name: String,
    val supplyId: SupplyId?,
    val composition: List<CompositionInput>,
    val serialOrLot: String,
    val date: String,
    val notes: String,
    /** P47-12 above the button: a replace of a row with current children closes them too (R47-6). */
    val subtreeToo: Boolean,
    val picking: PickFor? = null,
    /** With [picking] at [PickFor.ENTRY]: the entry a pick sets the SupplyItem of; null appends a new entry. */
    val pickingEntry: Int? = null,
    val dateProblem: String? = null,
    val linkProblem: String? = null,
    val compositionProblem: String? = null,
    val markedEntries: Set<Int> = emptySet(),
    val entryProblems: Map<Int, String> = emptyMap(),
    val problem: String? = null,
    val saving: Boolean = false,
) {
    val replacing: Boolean get() = target is ComponentFormTarget.Replace

    /** Save is offered while the trimmed name says something (no sentence: P47 has none), and a replace has its date. */
    val canSave: Boolean get() = !saving && name.isNotBlank() && (!replacing || date.isNotBlank())
}

/** The remove sheet (C26): P47-11, "Removed on" (P47-8, required, prefilled today), and P47-12 when it has current children. */
data class RemoveSheetState(
    val rowId: InstalledComponentId,
    val title: String,
    val removedOn: String,
    val subtreeToo: Boolean,
    val dateProblem: String? = null,
    val problem: String? = null,
    val saving: Boolean = false,
) {
    val canSave: Boolean get() = !saving && removedOn.isNotBlank()
}

/**
 * #47 (C25–C27) — the one ViewModel behind the asset's Installed components section, in
 * `AssetSuppliesSectionViewModel`'s shape: the rows' flow and the SupplyItem catalog's flow for what is drawn, one
 * use-case call per write (`InstallComponent`, `UpdateInstalledComponent`, `ReplaceInstalledComponent`,
 * `RemoveInstalledComponent`), and one exhaustive `when` over [InstalledComponentProblem] for the sentence each refusal
 * draws. It reads and never writes a repository, and re-checks nothing the use cases own.
 */
class InstalledComponentsSectionViewModel(
    private val assetId: AssetId,
    installedComponents: InstalledComponentRepository,
    items: SupplyItemRepository,
    private val installComponent: InstallComponent,
    private val updateInstalledComponent: UpdateInstalledComponent,
    private val replaceInstalledComponent: ReplaceInstalledComponent,
    private val removeInstalledComponent: RemoveInstalledComponent,
    /** The day the remove and replace sheets start on. */
    private val today: Today,
    /** Where the writes run: `Dispatchers.IO` in the app, the test's own scheduler in a JVM test. */
    private val io: CoroutineContext = Dispatchers.IO,
) : ViewModel() {

    constructor(graph: AppGraph, assetId: AssetId) : this(
        assetId,
        graph.installedComponents,
        graph.supplyItems,
        graph.installComponent,
        graph.updateInstalledComponent,
        graph.replaceInstalledComponent,
        graph.removeInstalledComponent,
        graph.today,
    )

    private data class Catalog(
        val rows: List<InstalledComponentRowState>,
        val removed: List<InstalledComponentRowState>,
        val components: List<InstalledComponent>,
        val supplies: Map<SupplyId, SupplyListRow>,
        val choices: List<SupplyListRow>,
        val preferredUnits: Map<SupplyId, String>,
    )

    private data class Ui(
        val offersWrites: Boolean = true,
        val showRemoved: Boolean = false,
        val openRow: InstalledComponentId? = null,
        val form: ComponentFormState? = null,
        val removing: RemoveSheetState? = null,
    )

    private val ui = MutableStateFlow(Ui())

    private val catalog = combine(installedComponents.observeForAsset(assetId), items.observeAll()) { rows, all ->
        val byId = rows.associateBy { it.id }
        val itemsById = all.associateBy { it.id }
        Catalog(
            rows = InstalledComponentTree.current(rows).map { (row, depth) ->
                rowState(row, depth, inside = row.parentId?.let(byId::get)?.name?.let(::insideOf), itemsById)
            },
            removed = InstalledComponentTree.removedUnreplaced(rows).map { row ->
                val closedParent = row.parentId?.let(byId::get)?.takeUnless { it.isCurrent }
                rowState(row, depth = 0, inside = null, itemsById, lead = closedParent?.name?.let(::insideOf))
            },
            components = rows,
            supplies = listRowsOf(all).associateBy { it.id },
            choices = listRowsOf(all.filter { it.archivedAt == null }),
            preferredUnits = all.associate { it.id to it.preferredUnit },
        )
    }

    val state: StateFlow<InstalledComponentsState> =
        combine(catalog, ui) { c, u ->
            InstalledComponentsState(
                rows = c.rows,
                removed = c.removed,
                showRemoved = u.showRemoved,
                components = c.components,
                supplies = c.supplies,
                choices = c.choices,
                preferredUnits = c.preferredUnits,
                offersWrites = u.offersWrites,
                rowSheet = u.openRow?.let { rowSheetOf(c.components, it) },
                form = u.form,
                removing = u.removing,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), InstalledComponentsState())

    /** A refusal with no sheet to draw it under: the asset was transferred out while a sheet was saving. No replay. */
    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * The screen's `!offersWrites` (#77): read-only closes the write sheets and the picker, and keeps the toggle and an
     * open row sheet, which then offers no action.
     */
    fun setReadOnly(readOnly: Boolean) {
        ui.update { if (readOnly) it.copy(offersWrites = false, form = null, removing = null) else it.copy(offersWrites = true) }
    }

    /** P47-18: opens or closes the removed rows under the tree. */
    fun toggleRemoved() {
        ui.update { it.copy(showRemoved = !it.showRemoved) }
    }

    /** A row's tap: its sheet, with its facts and history. Read-only still opens it. */
    fun open(id: InstalledComponentId) {
        ui.update { it.copy(openRow = id) }
    }

    fun closeRow() {
        ui.update { it.copy(openRow = null) }
    }

    /** The header's add glyph (P47-3): a row at the top level. */
    fun startInstall() {
        openForm { blankForm(ComponentFormTarget.Install(parentId = null), inside = null) }
    }

    /** "Install inside" (P47-4) on a current row's sheet: the parent is that row, said by P47-5; there is no parent picker. */
    fun startInstallInside(parentId: InstalledComponentId) {
        openForm { rows ->
            rows.firstOrNull { it.id == parentId && it.isCurrent }
                ?.let { blankForm(ComponentFormTarget.Install(parentId), inside = insideOf(it.name)) }
        }
    }

    /** "Edit" (P47-13): the whole editable set as stored, on a current or a removed row (R47-15). */
    fun startEdit(id: InstalledComponentId) {
        openForm { rows ->
            rows.firstOrNull { it.id == id }?.let { row ->
                ComponentFormState(
                    target = ComponentFormTarget.Edit(row.id, row.sortOrder),
                    title = EDIT_COMPONENT,
                    inside = null,
                    name = row.name,
                    supplyId = row.supplyId,
                    composition = row.composition.map { CompositionInput(it.id, it.supplyId, formatNumber(it.quantity), it.unit) },
                    serialOrLot = row.serialOrLot,
                    date = row.installedOn.orEmpty(),
                    notes = row.notes,
                    subtreeToo = false,
                )
            }
        }
    }

    /**
     * "Replace" (P47-9, P47-10) on a current row: the name, the direct link and the composition start as an editable
     * draft of the predecessor's (R47-17b) — nothing is saved until Save, which sends what the fields then hold. The
     * draft's entries carry no id, so the successor's are minted fresh and the predecessor keeps its own. The serial or
     * lot and the notes start empty; the date is the replacement date, today.
     */
    fun startReplace(id: InstalledComponentId) {
        openForm { rows ->
            rows.firstOrNull { it.id == id && it.isCurrent }?.let { row ->
                ComponentFormState(
                    target = ComponentFormTarget.Replace(row.id),
                    title = replaceTitle(row.name),
                    inside = null,
                    name = row.name,
                    supplyId = row.supplyId,
                    composition = row.composition.map { CompositionInput(null, it.supplyId, formatNumber(it.quantity), it.unit) },
                    serialOrLot = "",
                    date = today.localDate().toString(),
                    notes = "",
                    subtreeToo = hasCurrentChildren(rows, row.id),
                )
            }
        }
    }

    /** "Remove" (P47-11) on a current row: the date prefilled today; nothing is erased, so nothing is typed to confirm. */
    fun startRemove(id: InstalledComponentId) {
        if (!ui.value.offersWrites) return
        val row = state.value.components.firstOrNull { it.id == id && it.isCurrent } ?: return
        val sheet = RemoveSheetState(
            rowId = row.id,
            title = removeTitle(row.name),
            removedOn = today.localDate().toString(),
            subtreeToo = hasCurrentChildren(state.value.components, row.id),
        )
        ui.update { if (it.offersWrites) it.copy(openRow = null, form = null, removing = sheet) else it }
    }

    fun onName(text: String) = form { it.copy(name = text) }

    fun onSerialOrLot(text: String) = form { it.copy(serialOrLot = text) }

    fun onDate(text: String) = form { it.copy(date = text, dateProblem = null) }

    fun onNotes(text: String) = form { it.copy(notes = text) }

    /** "Link supply" (P15-21): the picker, offered the unarchived SupplyItems only. */
    fun startLinkPick() = form { it.copy(picking = PickFor.LINK, pickingEntry = null) }

    /** "Add supply" (P15-13) under the composition: the picker, offered the unarchived SupplyItems only; a pick appends. */
    fun startAddEntry() = form { it.copy(picking = PickFor.ENTRY, pickingEntry = null) }

    /**
     * An entry's SupplyItem tapped: the picker for that entry, so it can be swapped in place — an archived draft entry
     * is removed or replaced (C-1). The entry keeps its quantity, its typed unit and its id.
     */
    fun startEntryPick(index: Int) = form { f ->
        if (index in f.composition.indices) f.copy(picking = PickFor.ENTRY, pickingEntry = index) else f
    }

    fun dismissPicker() = form { it.copy(picking = null, pickingEntry = null) }

    /** A picked SupplyItem fills what the picker was opened for. */
    fun pick(row: SupplyListRow) {
        val preferredUnit = state.value.preferredUnits[row.id].orEmpty()
        form { f ->
            when (f.picking) {
                PickFor.LINK -> f.copy(supplyId = row.id, linkProblem = null, picking = null)
                PickFor.ENTRY -> f.withPickedEntry(row.id, preferredUnit).copy(picking = null, pickingEntry = null)
                null -> f
            }
        }
    }

    /** "Remove link" (P15-23): the draft holds no link, and Save sends none. */
    fun unlink() = form { it.copy(supplyId = null, linkProblem = null) }

    /** An entry's "Qty", kept as typed (the use case reads it); typing clears that entry's P47-25 mark. */
    fun onEntryQuantity(index: Int, text: String) = form { f -> f.withEntry(index) { it.copy(quantity = text) }.unmarked(index) }

    fun onEntryUnit(index: Int, text: String) = form { f -> f.withEntry(index) { it.copy(unit = text) } }

    /** An entry's close glyph (P47-24): only that entry leaves the draft; the others keep their order, ids and marks. */
    fun removeEntry(index: Int) = form { f -> f.withoutEntry(index) }

    /** Cancel, or the sheet dismissed: nothing is written. */
    fun dismissForm() {
        ui.update { it.copy(form = null) }
    }

    fun onRemovedOn(text: String) {
        ui.update { u -> u.copy(removing = u.removing?.copy(removedOn = text, dateProblem = null)) }
    }

    fun dismissRemove() {
        ui.update { it.copy(removing = null) }
    }

    /** Save (or "Replace"): one use-case call carrying exactly what the fields hold; a success closes the sheet. */
    fun save() {
        val form = ui.value.form ?: return
        if (!ui.value.offersWrites || !form.canSave) return
        ui.update { u ->
            u.copy(form = u.form?.takeIf { it.target == form.target }?.copy(saving = true)?.cleared() ?: u.form)
        }
        viewModelScope.launch(io) {
            val outcome = try {
                write(form)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failed(t, close = { closeForm(form.target) }, keep = { answerForm(form.target, Refusal()) })
                return@launch
            }
            when (outcome) {
                is InstalledComponentResult.Ok -> closeForm(form.target)
                is InstalledComponentResult.Refused -> refusalOf(outcome.problems).let { refusal ->
                    if (refusal.unchanged) closeForm(form.target) else answerForm(form.target, refusal)
                }
            }
        }
    }

    /** The remove sheet's "Remove": one call; the row's current subtree closes with it (R47-6). */
    fun confirmRemove() {
        val sheet = ui.value.removing ?: return
        if (!ui.value.offersWrites || !sheet.canSave) return
        ui.update { u ->
            u.copy(
                removing = u.removing?.takeIf { it.rowId == sheet.rowId }?.copy(saving = true, dateProblem = null, problem = null)
                    ?: u.removing,
            )
        }
        viewModelScope.launch(io) {
            val outcome = try {
                removeInstalledComponent.run(sheet.rowId, sheet.removedOn.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failed(t, close = { closeRemove(sheet.rowId) }, keep = { answerRemove(sheet.rowId, Refusal()) })
                return@launch
            }
            when (outcome) {
                is InstalledComponentResult.Ok -> closeRemove(sheet.rowId)
                is InstalledComponentResult.Refused -> answerRemove(sheet.rowId, refusalOf(outcome.problems))
            }
        }
    }

    private suspend fun write(form: ComponentFormState): InstalledComponentResult {
        // An empty date field is an unknown install date: core reads "" as a malformed day.
        val installedOn = form.date.trim().ifEmpty { null }
        return when (val target = form.target) {
            is ComponentFormTarget.Install -> installComponent.run(
                InstallComponentCommand(
                    assetId, target.parentId, form.name, form.supplyId, form.composition, form.serialOrLot, installedOn,
                    form.notes, sortOrder = null,
                ),
            )
            is ComponentFormTarget.Edit -> updateInstalledComponent.run(
                target.id,
                UpdateInstalledComponentCommand(
                    form.name, form.supplyId, form.composition, form.serialOrLot, installedOn, form.notes, target.sortOrder,
                ),
            )
            is ComponentFormTarget.Replace -> replaceInstalledComponent.run(
                target.id,
                ReplaceComponentCommand(form.date.trim(), form.name, form.supplyId, form.composition, form.serialOrLot, form.notes),
            )
        }
    }

    /** Opens the sheet [make] builds from the stored rows, closing the row sheet; nothing while read-only. */
    private fun openForm(make: (List<InstalledComponent>) -> ComponentFormState?) {
        if (!ui.value.offersWrites) return
        val form = make(state.value.components) ?: return
        ui.update { if (it.offersWrites) it.copy(openRow = null, removing = null, form = form) else it }
    }

    private fun form(change: (ComponentFormState) -> ComponentFormState) {
        ui.update { u -> u.copy(form = u.form?.let(change)) }
    }

    /** P77-35 when the asset was transferred out meanwhile, as a snackbar; any other failure keeps the sheet as typed. */
    private fun failed(t: Throwable, close: () -> Unit, keep: () -> Unit) {
        val line = t.transferredOutOr("")
        if (line.isEmpty()) {
            keep()
        } else {
            close()
            _messages.tryEmit(line)
        }
    }

    private fun answerForm(target: ComponentFormTarget, refusal: Refusal) {
        ui.update { u ->
            u.copy(
                form = u.form?.let { f ->
                    if (f.target != target) {
                        f
                    } else {
                        f.copy(
                            saving = false,
                            dateProblem = refusal.date,
                            linkProblem = refusal.link,
                            compositionProblem = refusal.composition,
                            markedEntries = refusal.marked,
                            entryProblems = refusal.entries,
                            problem = refusal.sheet,
                        )
                    }
                },
            )
        }
    }

    private fun closeForm(target: ComponentFormTarget) {
        ui.update { u -> u.copy(form = u.form?.takeUnless { it.target == target }) }
    }

    private fun answerRemove(rowId: InstalledComponentId, refusal: Refusal) {
        ui.update { u ->
            u.copy(
                removing = u.removing?.let { r ->
                    if (r.rowId != rowId) r else r.copy(saving = false, dateProblem = refusal.date, problem = refusal.sheet)
                },
            )
        }
    }

    private fun closeRemove(rowId: InstalledComponentId) {
        ui.update { u -> u.copy(removing = u.removing?.takeUnless { it.rowId == rowId }) }
    }
}

/** What a refusal draws, field by field (C26); [unchanged] closes the sheet as saved. */
internal data class Refusal(
    val date: String? = null,
    val link: String? = null,
    val composition: String? = null,
    val marked: Set<Int> = emptySet(),
    val entries: Map<Int, String> = emptyMap(),
    val sheet: String? = null,
    val unchanged: Boolean = false,
)

/**
 * One ratified sentence per problem (§5), under the field it names: a date problem under the date — a malformed day
 * typed into the field says what every shipped date field says, "Enter a date as YYYY-MM-DD" — a SupplyItem gone or
 * archived under the link or its entry (P15-20 — an archived draft link is refused here until it is removed, C-1), a
 * quantity under the composition rows (P47-25), and a gone or closed row or parent as P47-19. Never an API message.
 */
internal fun refusalOf(problems: List<InstalledComponentProblem>): Refusal = problems.fold(Refusal()) { r, problem ->
    when (problem) {
        // Unreachable from a sheet: Save is disabled while the name is blank, and P47 has no sentence for it.
        InstalledComponentProblem.NameRequired -> r
        is InstalledComponentProblem.BadDate -> r.copy(date = ENTER_A_DATE_AS_YYYY_MM_DD)
        is InstalledComponentProblem.AfterToday -> r.copy(date = DATE_NOT_LATER_THAN_TODAY)
        is InstalledComponentProblem.RemovedBeforeInstalled -> r.copy(date = REMOVAL_BEFORE_INSTALL)
        is InstalledComponentProblem.QuantityInvalid ->
            r.copy(composition = COMPOSITION_QUANTITY_REQUIRED, marked = r.marked + problem.index)
        InstalledComponentProblem.SupplyItemMissing -> r.copy(link = SUPPLY_ITEM_GONE)
        InstalledComponentProblem.SupplyItemArchived -> r.copy(link = SUPPLY_ITEM_GONE)
        is InstalledComponentProblem.EntrySupplyItemMissing -> r.copy(entries = r.entries + (problem.index to SUPPLY_ITEM_GONE))
        is InstalledComponentProblem.EntrySupplyItemArchived -> r.copy(entries = r.entries + (problem.index to SUPPLY_ITEM_GONE))
        InstalledComponentProblem.OwnerMissing -> r.copy(sheet = COMPONENT_CHANGED)
        InstalledComponentProblem.ParentMissing -> r.copy(sheet = COMPONENT_CHANGED)
        InstalledComponentProblem.ParentOnAnotherAsset -> r.copy(sheet = COMPONENT_CHANGED)
        InstalledComponentProblem.ParentRemoved -> r.copy(sheet = COMPONENT_CHANGED)
        InstalledComponentProblem.NoSuchInstalledComponent -> r.copy(sheet = COMPONENT_CHANGED)
        InstalledComponentProblem.AlreadyRemoved -> r.copy(sheet = COMPONENT_CHANGED)
        InstalledComponentProblem.Unchanged -> r.copy(unchanged = true)
    }
}

/** A sheet's problems cleared as Save starts again. */
private fun ComponentFormState.cleared(): ComponentFormState = copy(
    dateProblem = null, linkProblem = null, compositionProblem = null, markedEntries = emptySet(),
    entryProblems = emptyMap(), problem = null,
)

/** #15 C34's rule: a pick fills a blank "Unit" with the SupplyItem's preferred unit, and never overwrites one typed. */
internal fun unitAfterPick(typed: String, preferred: String): String = typed.ifBlank { preferred }

/**
 * [supplyId] picked for the composition (C26): appended as a new entry with no id and an empty quantity, or set on
 * the entry at [ComponentFormState.pickingEntry], which keeps its id, quantity and unit and loses its own sentence.
 * Either way the unit follows [unitAfterPick].
 */
private fun ComponentFormState.withPickedEntry(supplyId: SupplyId, preferredUnit: String): ComponentFormState {
    val at = pickingEntry
    return when {
        at == null -> copy(composition = composition + CompositionInput(null, supplyId, "", unitAfterPick("", preferredUnit)))
        at in composition.indices -> withEntry(at) { it.copy(supplyId = supplyId, unit = unitAfterPick(it.unit, preferredUnit)) }
            .copy(entryProblems = entryProblems - at)
        else -> this
    }
}

private fun ComponentFormState.withEntry(index: Int, change: (CompositionInput) -> CompositionInput): ComponentFormState =
    if (index in composition.indices) copy(composition = composition.mapIndexed { i, e -> if (i == index) change(e) else e }) else this

/** [index]'s P47-25 mark cleared; P47-25 itself goes with the last mark. */
private fun ComponentFormState.unmarked(index: Int): ComponentFormState {
    if (index !in markedEntries) return this
    val marks = markedEntries - index
    return copy(markedEntries = marks, compositionProblem = compositionProblem.takeIf { marks.isNotEmpty() })
}

/** The draft without entry [index]: the later entries' marks and sentences move up one place with them. */
private fun ComponentFormState.withoutEntry(index: Int): ComponentFormState {
    if (index !in composition.indices) return this
    val shifted = { i: Int -> if (i > index) i - 1 else i }
    val marks = (markedEntries - index).mapTo(HashSet(), shifted)
    return copy(
        composition = composition.filterIndexed { i, _ -> i != index },
        markedEntries = marks,
        compositionProblem = compositionProblem.takeIf { marks.isNotEmpty() },
        entryProblems = (entryProblems - index).mapKeys { (i, _) -> shifted(i) },
    )
}

/** A blank install sheet: the name, link, serial or lot, install date and notes all empty (an unknown day is allowed, R47-8). */
private fun blankForm(target: ComponentFormTarget.Install, inside: String?) = ComponentFormState(
    target = target,
    title = INSTALL_COMPONENT,
    inside = inside,
    name = "",
    supplyId = null,
    composition = emptyList(),
    serialOrLot = "",
    date = "",
    notes = "",
    subtreeToo = false,
)

private fun hasCurrentChildren(rows: List<InstalledComponent>, id: InstalledComponentId): Boolean =
    rows.any { it.parentId == id && it.isCurrent }

/** [id]'s row sheet, or null once the row is gone; its history is the position's instances, newest first (N-12). */
private fun rowSheetOf(rows: List<InstalledComponent>, id: InstalledComponentId): RowSheetState? {
    val byId = rows.associateBy { it.id }
    val row = byId[id] ?: return null
    return RowSheetState(
        id = row.id,
        name = row.name,
        current = row.isCurrent,
        supplyId = row.supplyId,
        composition = row.composition,
        serialOrLot = row.serialOrLot,
        installedDay = row.installedOn?.let(ReplaceStrings::day),
        removedDay = row.removedOn?.let(ReplaceStrings::day),
        history = InstalledComponentTree.history(rows, id).map { instance ->
            HistoryLineState(
                id = instance.id,
                name = instance.name,
                installed = instance.installedOn?.let(::installedOnDay) ?: INSTALL_DATE_NOT_RECORDED,
                closed = instance.removedOn?.let { on ->
                    InstalledComponentTree.successorOf(rows, instance.id)?.let { ReplaceStrings.replacedBy(it.name, on) }
                        ?: removedOnDay(on)
                },
                replaces = instance.replacesId?.let(byId::get)?.let { ReplaceStrings.replaces(it.name) },
            )
        },
    )
}

/**
 * [row] as drawn: [lead] first when given (a removed row's closed parent), then the direct link and its part number,
 * the composition's first two entries and the count of the rest, the serial or lot, and the install day on a current
 * row or the removal day on a removed one. A link or entry whose SupplyItem [items] does not hold draws nothing.
 */
private fun rowState(
    row: InstalledComponent,
    depth: Int,
    inside: String?,
    items: Map<SupplyId, SupplyItem>,
    lead: String? = null,
): InstalledComponentRowState {
    val link = row.supplyId?.let(items::get)
    val entries = row.composition.mapNotNull { entry ->
        items[entry.supplyId]?.let { compositionLine(amountOf(entry), it.name) }
    }
    val pieces = listOfNotNull(
        lead,
        link?.let { LINKED_TO.format(it.name) },
        link?.partNumber?.takeIf { it.isNotBlank() },
    ) + entries.take(ENTRIES_NAMED) + listOfNotNull(
        if (entries.size > ENTRIES_NAMED) moreEntries(entries.size - ENTRIES_NAMED) else null,
        row.serialOrLot.takeIf { it.isNotBlank() },
        row.removedOn?.let(::removedOnDay) ?: row.installedOn?.let(::installedOnDay),
    )
    return InstalledComponentRowState(
        id = row.id,
        name = row.name,
        depth = depth,
        inside = inside,
        quiet = pieces.joinToString(QUIET_JOINER),
        archived = link?.archivedAt != null,
    )
}

/**
 * The quantity as the shipped material lines draw theirs (`formatNumber`: "4", never "4.0"), then the unit: P47-21's
 * first argument on the quiet line and the row sheet's composition lines.
 */
internal fun amountOf(entry: CompositionEntry): String =
    listOf(formatNumber(entry.quantity), entry.unit).filter { it.isNotBlank() }.joinToString(" ")
