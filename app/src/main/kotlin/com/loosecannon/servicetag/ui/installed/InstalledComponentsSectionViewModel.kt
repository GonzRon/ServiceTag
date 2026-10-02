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
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.journal.formatNumber
import com.loosecannon.servicetag.ui.supplies.LINKED_TO
import com.loosecannon.servicetag.ui.supplies.SupplyListRow
import com.loosecannon.servicetag.ui.supplies.listRowsOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** How long the repository flows stay hot after the last collector leaves (a rotation, typically). */
private const val SUBSCRIPTION_GRACE_MS = 5_000L

/** The joiner of the quiet line's parts (C25), the Supplies list's detail joiner. */
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
    /** False on a held asset: the rows, the toggle and the facts draw, and nothing that writes is offered. */
    val offersWrites: Boolean = true,
)

/**
 * #47 (C25, C27) — the one ViewModel behind the asset's Installed components section, in
 * `AssetSuppliesSectionViewModel`'s shape: the rows' flow and the SupplyItem catalog's flow for what is drawn. It reads
 * and never writes a repository; the section's writes are use-case calls.
 */
class InstalledComponentsSectionViewModel(
    assetId: AssetId,
    installedComponents: InstalledComponentRepository,
    items: SupplyItemRepository,
) : ViewModel() {

    constructor(graph: AppGraph, assetId: AssetId) : this(assetId, graph.installedComponents, graph.supplyItems)

    private data class Catalog(
        val rows: List<InstalledComponentRowState>,
        val removed: List<InstalledComponentRowState>,
        val components: List<InstalledComponent>,
        val supplies: Map<SupplyId, SupplyListRow>,
        val choices: List<SupplyListRow>,
    )

    private data class Ui(val offersWrites: Boolean = true, val showRemoved: Boolean = false)

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
        )
    }

    val state: StateFlow<InstalledComponentsState> =
        combine(catalog, ui) { c, u ->
            InstalledComponentsState(c.rows, c.removed, u.showRemoved, c.components, c.supplies, c.choices, u.offersWrites)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), InstalledComponentsState())

    /** The screen's `!offersWrites` (#77): a held asset still draws its rows, its toggle and its facts. */
    fun setReadOnly(readOnly: Boolean) {
        ui.update { it.copy(offersWrites = !readOnly) }
    }

    /** P47-18: opens or closes the removed rows under the tree. */
    fun toggleRemoved() {
        ui.update { it.copy(showRemoved = !it.showRemoved) }
    }
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

/** The quantity as the shipped material lines draw theirs (`formatNumber`: "4", never "4.0"), then the unit. */
private fun amountOf(entry: CompositionEntry): String =
    listOf(formatNumber(entry.quantity), entry.unit).filter { it.isNotBlank() }.joinToString(" ")
