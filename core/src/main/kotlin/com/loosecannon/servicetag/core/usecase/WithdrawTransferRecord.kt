package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.model.openOuts
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/** What a withdrawal did. */
sealed interface WithdrawTransferResult {
    /** [record] (a WITHDRAWN) was appended: the OUT it names is no longer open here. */
    data class Withdrawn(val record: TransferRecord) : WithdrawTransferResult

    /** No OUT of that pack is open here for that asset: nothing to withdraw, and nothing was written. */
    data object NoOpenOut : WithdrawTransferResult
}

/**
 * #77 (C23, R77-5) — the explicit withdrawal of a transfer record: the undo of a mistaken mark, and the resolution of a
 * double mark (`TRANSFER_DIVERGED`, C10) or of a return refused while another OUT stays open (R77-B3-RETURN). One
 * write appends one WITHDRAWN naming the OUT of [run]'s pack, so the asset is no longer held **here**; nothing is
 * updated or deleted, the asset stays archived, and the OUT stays as history. Phone only: no route or tool reaches
 * it, and a withdrawal never travels by an ordinary merge (M1) — it is repeated on each installation. The one
 * reminder sweep that follows is the caller's (R77-23).
 */
class WithdrawTransferRecord(
    private val transfers: TransferRecordRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun run(assetId: AssetId, packId: String): WithdrawTransferResult = uow.write {
        // Only an OUT still open here can be withdrawn: a closed or already withdrawn one is history.
        val out = openOuts(transfers.forAsset(assetId)).firstOrNull { it.assetId == assetId && it.packId == packId }
            ?: return@write WithdrawTransferResult.NoOpenOut
        val record = TransferRecord(
            id = ids.newId(),
            assetId = assetId,
            kind = TransferKind.WITHDRAWN,
            packId = out.packId,
            lineage = out.lineage,
            at = clock.nowMillis(),
            packSha256 = out.packSha256,
            nameSnapshot = out.nameSnapshot,
            note = "",
        )
        transfers.append(record)
        WithdrawTransferResult.Withdrawn(record)
    }
}
