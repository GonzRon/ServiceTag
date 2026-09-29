package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.model.heldIds
import com.loosecannon.servicetag.core.model.lineageFor
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.transfer.EntangledRef
import com.loosecannon.servicetag.core.transfer.TransferGraph
import com.loosecannon.servicetag.core.transfer.TransferRefusal
import com.loosecannon.servicetag.core.transfer.TransferRetention
import com.loosecannon.servicetag.core.transfer.TransferSelection
import com.loosecannon.servicetag.core.transfer.TransferPackDraft

/**
 * What the ready screen holds of a sealed Transfer Pack (R77-16): its identity, the file's sha256, and the
 * content creation sealed — the roots, every asset, each asset's lineage and the inner data's content hash.
 */
data class CreatedPack(
    val packId: String,
    val packSha256: String,
    val rootIds: List<AssetId>,
    val assetIds: List<AssetId>,
    val lineage: Map<String, List<String>>,
    val contentSha256: String,
    val note: String,
) {
    companion object {
        /** The pack [draft] became once sealed as a file whose sha256 is [packSha256]. */
        fun of(draft: TransferPackDraft, packSha256: String) = CreatedPack(
            packId = draft.packId,
            packSha256 = packSha256,
            rootIds = draft.rootAssetIds.map(::AssetId),
            assetIds = draft.assetIds.map(::AssetId),
            lineage = draft.lineage,
            contentSha256 = draft.contentSha256,
            note = draft.note,
        )
    }
}

sealed interface MarkTransferredOutResult {
    /** Every pack asset archived and recorded: one OUT per asset, in [records]. */
    data class Marked(val records: List<TransferRecord>) : MarkTransferredOutResult

    /** P77-57: [assetIds] are already marked transferred out here. Nothing was written. */
    data class AlreadyTransferred(val assetIds: List<AssetId>) : MarkTransferredOutResult

    /** P77-51: [assetId] (or the pack around it) changed after the pack was made. Nothing was written. */
    data class PackOutdated(val assetId: AssetId) : MarkTransferredOutResult

    /** P77-17: [assetId] is lent out on [loanId] (R77-6). Nothing was written. */
    data class OpenLoan(val assetId: AssetId, val loanId: String) : MarkTransferredOutResult

    /** P77-58: rows that stay here name rows of the pack (the controller's ruling). Nothing was written. */
    data class Entangled(val refs: List<EntangledRef>) : MarkTransferredOutResult
}

/**
 * #77 (C8; AC 9, 10, 15; R77-6, R77-16) — marks a sealed pack's assets transferred out, in **one** write,
 * from the ready screen only. Inside that write it re-reads every canonical table ([readSnapshot]), and
 * refuses — writing nothing — when:
 *
 * - any pack asset is already held here ([MarkTransferredOutResult.AlreadyTransferred], P77-57);
 * - the pack no longer describes the estate: a root gone, the selection refused, its asset set different,
 *   its re-encoded content hash different (a row added, changed or scanned since creation), or an asset's
 *   `lineageFor` no longer the lineage the pack carries ([MarkTransferredOutResult.PackOutdated], P77-51);
 *   an open loan on a pack asset is [MarkTransferredOutResult.OpenLoan] instead (R77-6, P77-17);
 * - the estate the mark would leave is not retained cleanly — `TransferGraph.retain(snapshot, held ∪ pack)` is
 *   `Entangled`, whether the staying row names a pack row or a row of an asset held before (R77-B2a-MARK) — which
 *   would otherwise break the next ordinary backup ([MarkTransferredOutResult.Entangled], P77-58).
 *
 * Otherwise each pack asset is archived with a new `updatedAt` and its lifecycle rebuild asked for, each group
 * wholly in the pack and not yet archived is archived (an empty group is wholly in nothing), and then — last —
 * one OUT per asset is appended, carrying the pack's lineage for it. Nothing else is written: no history row,
 * no tag, no loan. A refusal is thrown inside the write, so nothing it read can have been written.
 */
class MarkTransferredOut(
    private val repos: BackupRepositories,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val onLifecycleChanged: suspend (AssetId) -> Unit,
) {
    /** @throws IllegalArgumentException when [pack] has no id or no file hash — it was never sealed. */
    suspend fun run(pack: CreatedPack): MarkTransferredOutResult {
        require(pack.packId.isNotBlank()) { "a Transfer Pack has an id" }
        require(pack.packSha256.isNotBlank()) { "a sealed Transfer Pack has a sha256" }
        return try {
            uow.write { mark(pack) }
        } catch (refused: Refused) {
            refused.result
        }
    }

    private suspend fun mark(pack: CreatedPack): MarkTransferredOutResult.Marked {
        val records = repos.transfers.all()
        val held = heldIds(records)
        pack.assetIds.filter { it in held }.takeIf { it.isNotEmpty() }?.let {
            refuse(MarkTransferredOutResult.AlreadyTransferred(it))
        }

        val snapshot = readSnapshot(repos)
        val present = snapshot.assets.map { it.id }.toSet()
        pack.rootIds.firstOrNull { it.value !in present }?.let { refuse(MarkTransferredOutResult.PackOutdated(it)) }
        val selected = when (val selection = TransferGraph.select(snapshot, pack.rootIds)) {
            is TransferSelection.Refused -> refuse(refusalOf(selection.refusals, snapshot, pack))
            is TransferSelection.Selected -> selection
        }
        if (selected.assetIds != pack.assetIds) {
            val changed = (selected.assetIds - pack.assetIds.toSet()) + (pack.assetIds - selected.assetIds.toSet())
            refuse(MarkTransferredOutResult.PackOutdated(changed.minBy { it.value }))
        }
        // The inner data's hash is deterministic — sorted rows, no set id and no stamp — so the same rows are
        // the same hash; the stamps here are placeholders and never reach it.
        val content = BackupCodec.decode(BackupCodec.encode(selected.data, "", 0, 0L, pack.packId)).manifest.dataSha256
        if (content != pack.contentSha256) refuse(MarkTransferredOutResult.PackOutdated(pack.rootIds.first()))
        pack.assetIds.firstOrNull { lineageFor(records, it) != pack.lineage[it.value] }?.let {
            refuse(MarkTransferredOutResult.PackOutdated(it))
        }
        // R77-B2a-MARK: the **whole** estate the mark would leave must retain cleanly — no exemption for a reference
        // an earlier transfer left, so a mark never lands on an estate whose next ordinary backup cannot be made.
        entangled(snapshot, held + pack.assetIds).takeIf { it.isNotEmpty() }?.let {
            refuse(MarkTransferredOutResult.Entangled(it))
        }

        val now = clock.nowMillis()
        val names = snapshot.assets.associate { it.id to it.name }
        for (id in pack.assetIds) {
            val asset = repos.assets.get(id) ?: refuse(MarkTransferredOutResult.PackOutdated(id))
            repos.assets.upsert(asset.copy(status = AssetStatus.ARCHIVED, updatedAt = now))
            onLifecycleChanged(id)
        }
        val packIds = pack.assetIds.map { it.value }.toSet()
        snapshot.maintenanceGroups
            .filter { it.archivedAt == null && TransferGraph.whollyIn(it, packIds) }
            .forEach { dto ->
                val group = repos.groups.get(GroupId(dto.id)) ?: return@forEach
                repos.groups.upsert(group.copy(archivedAt = now, updatedAt = now))
            }
        // Last: every row the OUTs describe is already written (B2b's guard lets the marking's own writes pass).
        val outs = pack.assetIds.map { id ->
            TransferRecord(
                id = ids.newId(), assetId = id, kind = TransferKind.OUT, packId = pack.packId,
                lineage = pack.lineage[id.value].orEmpty(), at = now, packSha256 = pack.packSha256,
                nameSnapshot = names.getValue(id.value), note = pack.note,
            )
        }
        outs.forEach { repos.transfers.append(it) }
        return MarkTransferredOutResult.Marked(outs)
    }

    private fun entangled(snapshot: BackupData, held: Set<AssetId>): List<EntangledRef> =
        when (val retention = TransferGraph.retain(snapshot, held)) {
            is TransferRetention.Retained -> emptyList()
            is TransferRetention.Entangled -> retention.refs
        }

    /** An open loan is its own refusal (P77-17); anything else the selection refuses means the pack is stale. */
    private fun refusalOf(refusals: List<TransferRefusal>, snapshot: BackupData, pack: CreatedPack): MarkTransferredOutResult {
        refusals.filterIsInstance<TransferRefusal.OpenLoan>().firstOrNull()?.let {
            return MarkTransferredOutResult.OpenLoan(it.assetId, it.loanId)
        }
        val packIds = pack.assetIds.map { it.value }.toSet()
        val named = when (val first = refusals.first()) {
            is TransferRefusal.ParentNotSelected -> first.childId
            is TransferRefusal.OutsideReference -> first.assetId
            is TransferRefusal.MixedGroup -> snapshot.maintenanceGroups.single { it.id == first.groupId }
                .members.map { it.assetId }.filter { it in packIds }.minOrNull()?.let(::AssetId) ?: pack.rootIds.first()
            is TransferRefusal.OpenLoan -> first.assetId
        }
        return MarkTransferredOutResult.PackOutdated(named)
    }

    private fun refuse(result: MarkTransferredOutResult): Nothing = throw Refused(result)

    /** Thrown inside the write so the transaction rolls back; caught at [run], never escapes. */
    private class Refused(val result: MarkTransferredOutResult) : RuntimeException(null, null, false, false)
}
