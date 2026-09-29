package com.loosecannon.servicetag.ui.transfer

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.transfer.TransferPack
import com.loosecannon.servicetag.core.transfer.TransferRefusal
import com.loosecannon.servicetag.core.usecase.CreateTransferPack
import com.loosecannon.servicetag.core.usecase.CreateTransferPackResult
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.transfer.`import`.TransferImportStrings
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One asset the selection offers (C17): a row of the tree, parents first, each component under its parent. A
 * component whose parent is checked is **forced** ([forcedBy] names that parent): checked, disabled, and described
 * P77-61 — it goes with the asset it belongs to (R77-8).
 */
data class TransferChoice(
    val id: String,
    val name: String,
    /** The parent's name for the shipped inline `Part of <parent>`; null for a root. */
    val parentName: String?,
    val depth: Int,
    val checked: Boolean,
    val forcedBy: String? = null,
) {
    val enabled: Boolean get() = forcedBy == null

    /** P77-61, the forced row's state description; null for a row the owner can change. */
    val stateDescription: String? get() = forcedBy?.let(TransferStrings::includedWith)
}

/**
 * The review (C17): what the selection would carry (P77-6…11, a zero line hidden but the assets'), and every refusal
 * (P77-15…18, and P77-57 / P77-59), which block Create. [note] is the one optional line for the new owner (R77-15).
 */
data class TransferReview(
    val counts: List<String> = emptyList(),
    val refusals: List<String> = emptyList(),
    val note: String = "",
) {
    val canCreate: Boolean get() = refusals.isEmpty() && TransferPack.noteAccepted(note)
}

data class TransferSelectionState(
    val loading: Boolean = true,
    val choices: List<TransferChoice> = emptyList(),
    /** Non-null once Review ran; null again when the selection changes. */
    val review: TransferReview? = null,
) {
    /** P77-56: nothing to offer. */
    val nothingToOffer: String? get() = if (!loading && choices.isEmpty()) TransferStrings.NOTHING_TO_OFFER else null

    /** The roots Create takes: every checked row not forced by a checked parent. */
    val roots: List<AssetId> get() = choices.filter { it.checked && it.forcedBy == null }.map { AssetId(it.id) }

    /** Review is disabled while nothing is checked. */
    val canReview: Boolean get() = roots.isNotEmpty()
}

/**
 * #77 (C17; R77-8, R77-10) — what is leaving: every asset **not held** here (archived and retired included), as a
 * tree, the detail's asset preselected; then the review, which runs C2 through the creation use case — a read that
 * writes nothing — for its counts and refusals. A held component forced in by a parent that is not held is refused
 * here with P77-57, before any file exists (B4 hand-off 2).
 */
class TransferSelectionViewModel(
    assets: AssetRepository,
    transfers: TransferRecordRepository,
    private val groups: GroupRepository,
    private val createPack: CreateTransferPack,
    preselect: AssetId? = null,
    private val io: CoroutineContext = Dispatchers.IO,
) : ViewModel() {

    constructor(graph: AppGraph, preselect: String?) : this(
        graph.assets, graph.transferRecords, graph.groups, graph.createTransferPack, preselect?.let(::AssetId),
    )

    private val picked = MutableStateFlow(setOfNotNull(preselect))
    private val review = MutableStateFlow<TransferReview?>(null)
    private var everything: List<Asset> = emptyList()
    private var held: Set<AssetId> = emptySet()

    val state: StateFlow<TransferSelectionState> =
        combine(assets.observeAll(), transfers.observeHeldIds(), picked, review) { all, heldNow, roots, reviewed ->
            everything = all
            held = heldNow
            TransferSelectionState(loading = false, choices = transferChoicesOf(all, heldNow, roots), review = reviewed)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), TransferSelectionState())

    /** Checks or clears a row the owner may change; a forced row does nothing. Any change drops the review. */
    fun toggle(id: String) {
        val choice = state.value.choices.firstOrNull { it.id == id } ?: return
        if (!choice.enabled) return
        picked.update { if (AssetId(id) in it) it - AssetId(id) else it + AssetId(id) }
        review.value = null
    }

    /** Back from the review to the tree, the checks kept. */
    fun backToSelection() {
        review.value = null
    }

    /** The note, one line of at most [TransferPack.MAX_NOTE_CHARS] characters (P77-13). */
    fun onNote(text: String) = review.update { current ->
        current?.copy(note = text.replace(LINE_BREAKS, " ").take(TransferPack.MAX_NOTE_CHARS))
    }

    /** Runs the selection (C2) for its counts and refusals; nothing is written. */
    fun review() {
        val roots = state.value.roots
        if (roots.isEmpty()) return
        viewModelScope.launch {
            val result = try {
                withContext(io) { createPack.run(roots, "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only a root deleted since the list was drawn gets here; the list redraws without it.
                Log.w(TAG, "the review could not be read", e)
                return@launch
            }
            review.value = reviewOf(result)
        }
    }

    private suspend fun reviewOf(result: CreateTransferPackResult): TransferReview = when (result) {
        is CreateTransferPackResult.Refused -> TransferReview(refusals = result.refusals.map { refusalLine(it) })
        is CreateTransferPackResult.TooLarge -> TransferReview(refusals = listOf(TransferStrings.TOO_LARGE))
        is CreateTransferPackResult.Created -> {
            val draft = result.draft
            // Hand-off 2: a held component forced in by a parent that is not held — refused before any file.
            val alreadyHeld = draft.assetIds.any { AssetId(it) in held }
            TransferReview(
                counts = countLinesOf(draft.counts),
                refusals = if (alreadyHeld) listOf(TransferStrings.ALREADY_TRANSFERRED) else emptyList(),
            )
        }
    }

    private suspend fun refusalLine(refusal: TransferRefusal): String =
        refusalLineOf(refusal, { nameOf(it) }, { groups.get(it)?.name ?: it.value })

    private fun nameOf(id: AssetId): String = everything.firstOrNull { it.id == id }?.name ?: id.value

    private companion object {
        const val TAG = "TransferSelection"
        val LINE_BREAKS = Regex("[\\r\\n]+")
    }
}

/**
 * The tree the selection draws (C17): every asset not in [held], roots first by name, each followed by its
 * components (by name, recursively). A component whose parent row is checked is forced; [picked] are the owner's
 * own checks. A component whose parent is held is listed as a root of its own (it is refused as P77-16 at review).
 */
internal fun transferChoicesOf(all: List<Asset>, held: Set<AssetId>, picked: Set<AssetId>): List<TransferChoice> {
    val offered = all.filter { it.id !in held }
    val offeredIds = offered.mapTo(HashSet()) { it.id }
    val names = all.associate { it.id to it.name }
    val children = offered.groupBy { it.parentAssetId }
    val out = mutableListOf<TransferChoice>()
    fun visit(asset: Asset, depth: Int, forcedBy: String?) {
        val checked = forcedBy != null || asset.id in picked
        out += TransferChoice(
            id = asset.id.value,
            name = asset.name,
            parentName = asset.parentAssetId?.let { names[it] },
            depth = depth,
            checked = checked,
            forcedBy = forcedBy,
        )
        children[asset.id].orEmpty().sortedBy { it.name.lowercase() }
            .forEach { visit(it, depth + 1, if (checked) asset.name else null) }
    }
    offered.filter { it.parentAssetId == null || it.parentAssetId !in offeredIds }
        .sortedBy { it.name.lowercase() }
        .forEach { visit(it, 0, null) }
    return out
}

/** P77-6…11 from a manifest's counts, through their one home (B3's [TransferImportStrings.countLines]). */
internal fun countLinesOf(counts: Map<String, Int>): List<String> = TransferImportStrings.countLines(
    assets = counts["assets"] ?: 0,
    tags = counts["nfcTags"] ?: 0,
    records = counts["assetEvents"] ?: 0,
    schedules = counts["maintenanceSchedules"] ?: 0,
    documents = counts["attachments"] ?: 0,
    cases = counts["serviceCases"] ?: 0,
)
