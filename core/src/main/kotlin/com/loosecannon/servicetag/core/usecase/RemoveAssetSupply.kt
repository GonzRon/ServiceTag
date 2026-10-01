package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.ports.AssetSupplyRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * #15 (C17; R15-5) — removes one applicability row. A row is configuration, not history, so it is a hard delete,
 * removable on the phone and over the API alike; the SupplyItem it named stays, archived or not. An absent row is
 * [AssetSupplyProblem.NoSuchAssetSupply], never a silent success: the caller has a stale view. The answer carries
 * the removed row. A held asset's row throws at the guarded port (C14).
 */
class RemoveAssetSupply(
    private val rows: AssetSupplyRepository,
    private val uow: UnitOfWork,
) {
    suspend fun run(id: String): AssetSupplyResult {
        val row = rows.get(id) ?: return AssetSupplyResult.Refused(AssetSupplyProblem.NoSuchAssetSupply)
        uow.write { rows.delete(id) }
        return AssetSupplyResult.Ok(row)
    }
}
