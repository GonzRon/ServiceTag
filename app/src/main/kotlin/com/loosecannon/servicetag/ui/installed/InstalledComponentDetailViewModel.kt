package com.loosecannon.servicetag.ui.installed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.ports.InstalledComponentRepository
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.supplies.listRowsOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private const val SUBSCRIPTION_GRACE_MS = 5_000L

/**
 * #69 (C27, R69-8) — the SupplyItems [row] names, each once: its direct link first, then its composition entries as
 * the composition lists them. The installed-component screen draws one open-only group per id, and Share's component
 * level lists the same ids (C30), so the sequence and the de-duplication live here and nowhere else.
 */
internal fun suppliesNamedBy(row: InstalledComponent): List<SupplyId> =
    (listOfNotNull(row.supplyId) + row.composition.map { it.supplyId }).distinct()

/**
 * One SupplyItem's open-only group on the installed-component screen (C27): the heading P69-3 naming it ([heading]; a
 * tap opens the SupplyItem), then its own files ([files]) and links ([links]). Nothing in a group is added, edited or
 * removed here; that happens on the SupplyItem's own detail.
 */
data class SupplyGroupState(
    val supplyId: SupplyId,
    val heading: String,
    val files: AttachmentOwner.OfSupplyItem,
    val links: ReferenceOwner.OfSupplyItem,
)

/**
 * The installed-component screen (C27), two ownerships, first the component's own files ([files]) and links
 * ([links]) under P69-2, open only while its asset is held ([readOnly], #77), a removed component's included; then
 * [supplyGroups], one per SupplyItem [suppliesNamedBy] names, an archived one included.
 */
data class InstalledComponentDetailState(
    val id: InstalledComponentId,
    val name: String,
    val files: AttachmentOwner.OfInstalledComponent,
    val links: ReferenceOwner.OfInstalledComponent,
    val readOnly: Boolean,
    val supplyGroups: List<SupplyGroupState>,
)

/**
 * #69 (C27, N-8) — the installed-component screen's one ViewModel. Every read is observed: the row through
 * `observeForAsset` of its asset, filtered by id (one `get` first, to learn the asset); the held state through
 * `observeHeldIds`, the flow the asset detail reads; the SupplyItems' names through the Supplies list's rows. So a
 * hold or a release while the screen is open flips [InstalledComponentDetailState.readOnly], and a component whose
 * asset is deleted (its rows leave by CASCADE) sets [missing]. It writes nothing; the sections own their writes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstalledComponentDetailViewModel(
    private val id: InstalledComponentId,
    installedComponents: InstalledComponentRepository,
    items: SupplyItemRepository,
    transfers: TransferRecordRepository,
) : ViewModel() {

    constructor(graph: AppGraph, id: String) : this(
        InstalledComponentId(id),
        graph.installedComponents,
        graph.supplyItems,
        graph.transferRecords,
    )

    /** The row, live; null once it is gone, or when it never was (a restored back stack). */
    private val row: Flow<InstalledComponent?> = flow { emit(installedComponents.get(id)) }.flatMapLatest { first ->
        if (first == null) {
            flowOf(null)
        } else {
            installedComponents.observeForAsset(first.assetId).map { rows -> rows.firstOrNull { it.id == id } }
        }
    }

    val state: StateFlow<InstalledComponentDetailState?> =
        combine(row, items.observeAll(), transfers.observeHeldIds()) { found, all, held ->
            found?.let { detailOf(it, all, held) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), null)

    /**
     * Separate from [state] because "not read yet" and "gone" both read as a null state, and only the second sends
     * the person back (`SupplyDetailViewModel.missing`'s shape).
     */
    val missing: StateFlow<Boolean> = row
        .map { it == null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), false)
}

private fun detailOf(
    row: InstalledComponent,
    all: List<SupplyItem>,
    held: Set<AssetId>,
): InstalledComponentDetailState {
    val names = listRowsOf(all).associateBy { it.id }
    return InstalledComponentDetailState(
        id = row.id,
        name = row.name,
        files = AttachmentOwner.OfInstalledComponent(row.id),
        links = ReferenceOwner.OfInstalledComponent(row.id),
        readOnly = row.assetId in held,
        supplyGroups = suppliesNamedBy(row).mapNotNull { names[it] }.map { item ->
            SupplyGroupState(
                supplyId = item.id,
                heading = fromSupply(item.name),
                files = AttachmentOwner.OfSupplyItem(item.id),
                links = ReferenceOwner.OfSupplyItem(item.id),
            )
        },
    )
}
