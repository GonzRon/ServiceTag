package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.model.heldIds
import com.loosecannon.servicetag.core.model.openOuts
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.transfer.EntangledRef
import com.loosecannon.servicetag.core.transfer.TransferGraph
import com.loosecannon.servicetag.core.transfer.TransferRetention

/** What a withdrawal did. */
sealed interface WithdrawTransferResult {
    /** [records] (one WITHDRAWN per asset of the pack) were appended: none of the OUTs they name is open here. */
    data class Withdrawn(val records: List<TransferRecord>) : WithdrawTransferResult

    /** No OUT of that pack is open here for that asset: nothing to withdraw, and nothing was written. */
    data object NoOpenOut : WithdrawTransferResult

    /** P77-72: the estate the withdrawal would leave is not retained cleanly ([refs]). Nothing was written. */
    data class Entangled(val refs: List<EntangledRef>) : WithdrawTransferResult
}

/**
 * #77 (C23, R77-5, R77-WITHDRAW) — the explicit withdrawal of a Transfer Pack's local OUT disposition: the undo of a
 * mistaken mark, and the resolution of a double mark (`TRANSFER_DIVERGED`, C10) or of a return refused while another
 * OUT stays open (R77-B3-RETURN). It is **atomic and pack-wide**: [run] names an asset and one of its open OUTs, and
 * one write appends a WITHDRAWN for **every** asset whose OUT of that pack is still open here — never for one member
 * of a pack alone, which could leave a component naming its held parent or a group half held. Before anything is
 * appended the complete estate that would remain, `TransferGraph.retain(snapshot, heldAfter)`, must be retained,
 * by marking's own rule (R77-B2a-MARK) and with no exemption for a reference this withdrawal did not introduce;
 * otherwise the whole withdrawal is refused ([WithdrawTransferResult.Entangled]) and nothing is written. There are
 * no partial withdrawals: if only some of a pack left, withdraw it and make a new pack of what did.
 *
 * Nothing is updated or deleted; the assets stay archived and the OUTs stay as history. Phone only: no route or tool
 * reaches it, and a withdrawal never travels by an ordinary merge (M1) — it is repeated on each installation. The
 * one reminder sweep that follows is the caller's (R77-23).
 */
class WithdrawTransferRecord(
    private val repos: BackupRepositories,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun run(assetId: AssetId, packId: String): WithdrawTransferResult = try {
        uow.write { withdraw(assetId, packId) }
    } catch (refused: Refused) {
        refused.result
    }

    private suspend fun withdraw(assetId: AssetId, packId: String): WithdrawTransferResult {
        val records = repos.transfers.all()
        val open = openOuts(records)
        // Only an OUT still open here can be withdrawn: a closed or already withdrawn one is history.
        if (open.none { it.assetId == assetId && it.packId == packId }) return WithdrawTransferResult.NoOpenOut
        val now = clock.nowMillis()
        val withdrawn = open.filter { it.packId == packId }.sortedBy { it.assetId.value }.map { out ->
            TransferRecord(
                id = ids.newId(),
                assetId = out.assetId,
                kind = TransferKind.WITHDRAWN,
                packId = out.packId,
                lineage = out.lineage,
                at = now,
                packSha256 = out.packSha256,
                nameSnapshot = out.nameSnapshot,
                note = "",
            )
        }
        // R77-WITHDRAW: the whole estate left behind must retain cleanly, or nothing is written.
        val heldAfter = heldIds(records + withdrawn)
        val retention = TransferGraph.retain(readSnapshot(repos), heldAfter)
        if (retention is TransferRetention.Entangled) throw Refused(WithdrawTransferResult.Entangled(retention.refs))
        withdrawn.forEach { repos.transfers.append(it) }
        return WithdrawTransferResult.Withdrawn(withdrawn)
    }

    /** Thrown inside the write so the transaction rolls back; caught at [run], never escapes. */
    private class Refused(val result: WithdrawTransferResult) : RuntimeException(null, null, false, false)
}
