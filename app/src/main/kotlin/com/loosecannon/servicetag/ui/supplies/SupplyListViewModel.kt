package com.loosecannon.servicetag.ui.supplies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.di.AppGraph
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** How long the repository flow stays hot after the last collector leaves (a rotation, typically). */
private const val SUBSCRIPTION_GRACE_MS = 5_000L

/**
 * One SupplyItem as the Supplies list draws it (C30): its name, the quiet [detail] line — the manufacturer and the
 * P15-4 field joined by a middle dot, each only when it is there, empty when neither is — and whether it is archived.
 *
 * [archived] is carried, never filtered on (R15-6): an archived SupplyItem stays on its assets and its linked
 * lines, so the one list naming every SupplyItem names it too, marked, in its own place.
 */
data class SupplyListRow(
    val id: SupplyId,
    val name: String,
    val detail: String,
    val archived: Boolean,
)

/**
 * The Supplies list's state (#15, C30): every SupplyItem, archived included, in `(name casefolded, id)` order —
 * the maintenance group list's order (`MaintenanceViewModel`), stated here rather than left to the query's
 * `COLLATE NOCASE`, which folds ASCII only.
 *
 * It reads the catalog's live flow and writes nothing: the add button and a row only navigate, and the editor and
 * the detail write through `SaveSupplyItem` / `ArchiveSupplyItem`.
 */
class SupplyListViewModel(items: SupplyItemRepository) : ViewModel() {

    constructor(graph: AppGraph) : this(graph.supplyItems)

    /** Null until the store has answered once, so an empty catalog's P15-3 is never drawn before it is true. */
    val rows: StateFlow<List<SupplyListRow>?> = items.observeAll()
        .map { all ->
            all.sortedWith(compareBy({ it.name.lowercase() }, { it.id.value })).map(::listRowOf)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), null)
}

private fun listRowOf(item: SupplyItem) = SupplyListRow(
    id = item.id,
    name = item.name,
    detail = listOf(item.manufacturer, item.partNumber).filter { it.isNotBlank() }.joinToString(" · "),
    archived = item.archivedAt != null,
)
