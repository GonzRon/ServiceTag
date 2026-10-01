package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.journal.CategoryKey
import com.loosecannon.servicetag.core.model.AssetSupply
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AssetSupplyRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * #15 (C17; R15-3, R15-6) — the only way an applicability row is created: the asset's section, the API and the MCP
 * all arrive here, so every check lives here and none in a caller. The step order is the contract, one refusal each:
 * [AssetSupplyProblem.OwnerMissing], [AssetSupplyProblem.SupplyItemMissing], [AssetSupplyProblem.SupplyItemArchived]
 * (an archived item takes no new row), [AssetSupplyProblem.RoleRequired] (the role cleaned by [CategoryKey.display],
 * never a second cleaner), then [AssetSupplyProblem.Taken] (another row holds the cleaned triple; the schema's unique
 * index is only the last word). Nothing is minted or written before the last of them answers.
 *
 * The row stores the **cleaned** role and nothing derived: no role from the item's name or category (C37). Any Asset
 * takes applicability, a child Asset included. A held asset's insert throws at the guarded port (C14).
 */
class AddAssetSupply(
    private val assets: AssetRepository,
    private val items: SupplyItemRepository,
    private val rows: AssetSupplyRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun run(cmd: AddAssetSupplyCommand): AssetSupplyResult {
        if (assets.get(cmd.assetId) == null) return AssetSupplyResult.Refused(AssetSupplyProblem.OwnerMissing)
        val item = items.get(cmd.supplyId) ?: return AssetSupplyResult.Refused(AssetSupplyProblem.SupplyItemMissing)
        if (item.archivedAt != null) return AssetSupplyResult.Refused(AssetSupplyProblem.SupplyItemArchived)
        val role = CategoryKey.display(cmd.role)
        if (role.isEmpty()) return AssetSupplyResult.Refused(AssetSupplyProblem.RoleRequired)
        if (rows.forAsset(cmd.assetId).any { it.supplyId == cmd.supplyId && it.role == role }) {
            return AssetSupplyResult.Refused(AssetSupplyProblem.Taken)
        }

        val now = clock.nowMillis()
        val row = AssetSupply(
            id = ids.newId(),
            assetId = cmd.assetId,
            supplyId = cmd.supplyId,
            role = role,
            createdAt = now,
            updatedAt = now,
        )
        uow.write { rows.insert(row) }
        return AssetSupplyResult.Ok(row)
    }
}
