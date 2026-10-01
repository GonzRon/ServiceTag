package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.journal.CategoryKey
import com.loosecannon.servicetag.core.ports.AssetSupplyRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * #15 (C17; R15-3, R15-6) — re-roles one applicability row, and nothing else: its asset and its item are fixed. The
 * step order is the contract: [AssetSupplyProblem.NoSuchAssetSupply], [AssetSupplyProblem.RoleRequired] (the role
 * cleaned by [CategoryKey.display]), [AssetSupplyProblem.Taken] (another row holds the cleaned triple), then
 * [AssetSupplyProblem.Unchanged] (the cleaned role is the row's own: nothing written, `updatedAt` held, so a
 * re-imported archive stays identical — the [UpdateReference] rule), then the write, moving `updatedAt`.
 *
 * An archived item's row may be re-roled: it is not a new link (R15-6). Because `Unchanged` answers before any
 * write, a no-op re-role on a held asset's row is `Unchanged` and never the guard's `AssetTransferredOut` (409): only
 * a real write reaches the guarded port (C14).
 */
class UpdateAssetSupply(
    private val rows: AssetSupplyRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
) {
    suspend fun run(id: String, cmd: UpdateAssetSupplyCommand): AssetSupplyResult {
        val row = rows.get(id) ?: return AssetSupplyResult.Refused(AssetSupplyProblem.NoSuchAssetSupply)
        val role = CategoryKey.display(cmd.role)
        if (role.isEmpty()) return AssetSupplyResult.Refused(AssetSupplyProblem.RoleRequired)
        if (rows.forAsset(row.assetId).any { it.id != row.id && it.supplyId == row.supplyId && it.role == role }) {
            return AssetSupplyResult.Refused(AssetSupplyProblem.Taken)
        }
        if (role == row.role) return AssetSupplyResult.Refused(AssetSupplyProblem.Unchanged)

        val updated = row.copy(role = role, updatedAt = clock.nowMillis())
        uow.write { rows.update(updated) }
        return AssetSupplyResult.Ok(updated)
    }
}
