package com.loosecannon.servicetag.ui.supplies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.model.SupplySpecification
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AssetSupplyRepository
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.usecase.ArchiveSupplyItem
import com.loosecannon.servicetag.core.usecase.NoSuchSupplyItem
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.asset.CATEGORY_FIELD
import com.loosecannon.servicetag.ui.asset.MANUFACTURER_FIELD
import com.loosecannon.servicetag.ui.asset.MODEL_FIELD
import com.loosecannon.servicetag.ui.attachments.NOTES_LABEL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How long the repository flows stay hot after the last collector leaves (a rotation, typically). */
private const val SUBSCRIPTION_GRACE_MS = 5_000L

/** One identity fact the detail draws (C30): a shipped or ratified [label] over a non-blank [value]. */
data class SupplyFact(val label: String, val value: String)

/** One specification as the detail draws it (C30): [text] is "label — value unit", the unit only when there is one. */
data class SupplySpecLine(val id: String, val text: String)

/**
 * One applicability row naming this SupplyItem (C30's "Used by"): the asset that takes it, by name, and the role
 * it takes it in. [id] is the applicability row's; a tap opens [assetId]'s own screen.
 */
data class SupplyUseRow(val id: String, val assetId: AssetId, val assetName: String, val role: String)

/**
 * One SupplyItem, in full (C30): [facts] are the identity fields that are there, in Category, Manufacturer, Model,
 * Part number, Preferred unit, Notes order; [specifications] in the stored `(sortOrder, id)` order; [usedBy] by the
 * asset's name, then the role. Below "Used by" sit the item's own Documents and References (#69, C26): the shipped
 * sections hold their own state, keyed by [id], and [resourcesReadOnly] is the one fact about them held here.
 */
data class SupplyDetailState(
    val id: SupplyId,
    val name: String,
    val archived: Boolean,
    val facts: List<SupplyFact>,
    val specifications: List<SupplySpecLine>,
    val usedBy: List<SupplyUseRow>,
    /**
     * #69 (C26, R69-10): whether the two sections draw open-only. Never, an archived item included: a SupplyItem is
     * never held, and an archived one keeps gaining files and links on this screen. A state field rather than a
     * literal in the composable, so the JVM proves it (row 50).
     */
    val resourcesReadOnly: Boolean,
)

/**
 * The SupplyItem detail screen's state (#15, C30), on `GroupDetailViewModel`'s shape.
 *
 * It **adds no rule**. The item and its specifications come from the catalog's live flow; "Used by" from the
 * applicability rows naming the item, each with its asset's name from the assets' live flow. The one write is
 * `archived_at`, through [ArchiveSupplyItem] — one column, reversible, and the item's applicability rows and line
 * links untouched (R15-6). Nothing here deletes a SupplyItem (R15-5).
 *
 * **"Used by" is re-read, not observed:** the applicability port observes an *asset's* rows, never an item's, and
 * a row added or removed on an asset's screen moves neither the catalog nor the asset table. So the screen calls
 * [refresh] each time it comes back into composition — the Maintenance destination's pattern for a fact nothing
 * observes — and a change made elsewhere is there when the owner returns.
 */
class SupplyDetailViewModel(
    items: SupplyItemRepository,
    private val assetSupplies: AssetSupplyRepository,
    assets: AssetRepository,
    private val archiveSupplyItem: ArchiveSupplyItem,
    private val id: SupplyId,
) : ViewModel() {

    constructor(graph: AppGraph, id: String) : this(
        graph.supplyItems,
        graph.assetSupplies,
        graph.assets,
        graph.archiveSupplyItem,
        SupplyId(id),
    )

    private val catalog = items.observeAll()
    private val refreshes = MutableStateFlow(0)

    val state: StateFlow<SupplyDetailState?> =
        combine(catalog, assets.observeAll(), refreshes) { all, assetRows, _ ->
            all.firstOrNull { it.id == id }?.let { it to assetRows }
        }
            .map { found -> found?.let { (item, assetRows) -> detailOf(item, assetRows) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), null)

    /**
     * Separate from [state] because "not read yet" and "gone" both read as a null state, and only the second sends
     * the owner back: a restored back stack or a replacing import can name an item no longer there.
     */
    val missing: StateFlow<Boolean> = catalog
        .map { all -> all.none { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), false)

    /** Re-read "Used by". The screen calls it on returning into composition. */
    fun refresh() = refreshes.update { it + 1 }

    /** Archive or unarchive: one column, no cascade (C16, R15-6). */
    fun setArchived(archived: Boolean) {
        viewModelScope.launch {
            try {
                archiveSupplyItem.run(id, archived)
            } catch (e: NoSuchSupplyItem) {
                // Gone between the draw and the tap: [missing] already takes the owner back, and there is nothing
                // left to archive.
            }
        }
    }

    private suspend fun detailOf(item: SupplyItem, assetRows: List<Asset>): SupplyDetailState {
        val names = assetRows.associate { it.id to it.name }
        return SupplyDetailState(
            id = item.id,
            name = item.name,
            archived = item.archivedAt != null,
            facts = factsOf(item),
            specifications = item.specifications.map { SupplySpecLine(it.id, specificationLine(it)) },
            usedBy = assetSupplies.forSupply(item.id)
                .map { SupplyUseRow(it.id, it.assetId, names[it.assetId].orEmpty(), it.role) }
                .sortedWith(compareBy({ it.assetName.lowercase() }, { it.assetName }, { it.role }, { it.id })),
            resourcesReadOnly = false,
        )
    }
}

/** The identity fields that are there, under their one-home labels (C30, C31; §5), in the editor's field order. */
private fun factsOf(item: SupplyItem): List<SupplyFact> = listOf(
    CATEGORY_FIELD to item.category,
    MANUFACTURER_FIELD to item.manufacturer,
    MODEL_FIELD to item.model,
    PART_NUMBER_FIELD to item.partNumber,
    PREFERRED_UNIT_FIELD to item.preferredUnit,
    NOTES_LABEL to item.notes,
).filter { (_, value) -> value.isNotBlank() }.map { (label, value) -> SupplyFact(label, value) }

/** "label — value unit" (C30), the unit only when it is there. */
private fun specificationLine(spec: SupplySpecification): String =
    if (spec.unit.isBlank()) "${spec.label} — ${spec.value}" else "${spec.label} — ${spec.value} ${spec.unit}"
