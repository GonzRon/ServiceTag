package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetSupply
import com.loosecannon.servicetag.core.model.SupplyId

/**
 * #15 (C17): two outcomes and no exception, the [ReferenceResult] shape — every [AssetSupplyProblem] is something a
 * caller draws or maps to a status code. A held asset's write is the one exception that still escapes: the guarded
 * port throws `AssetTransferredOut` at the write, after every check here has answered (C14).
 */
sealed interface AssetSupplyResult {
    data class Ok(val row: AssetSupply) : AssetSupplyResult
    data class Refused(val problem: AssetSupplyProblem) : AssetSupplyResult
}

/**
 * What the asset's "Supplies" section, `POST /v1/asset-supplies` and the MCP hand to [AddAssetSupply]: this Asset (any
 * Asset, a child Asset included) takes this SupplyItem in this [role]. No defaults; the role is free text, cleaned by
 * the use case and never filled from anything but this command (R15-3, C37).
 */
data class AddAssetSupplyCommand(val assetId: AssetId, val supplyId: SupplyId, val role: String)

/** What a re-role can change: the role, and nothing else — moving a row to another asset or item is remove plus add. */
data class UpdateAssetSupplyCommand(val role: String)

/**
 * One thing wrong with an applicability command, C2's code and status beside each. No member carries a sentence.
 */
sealed interface AssetSupplyProblem {
    /** `no_such_asset` (404): the command's asset is not there. */
    data object OwnerMissing : AssetSupplyProblem

    /** `NO_SUCH_SUPPLY_ITEM` (404): the command's `supplyId` names no SupplyItem. */
    data object SupplyItemMissing : AssetSupplyProblem

    /** `SUPPLY_ITEM_ARCHIVED` (409, `supplyId`): an archived SupplyItem takes no **new** applicability row (R15-6). */
    data object SupplyItemArchived : AssetSupplyProblem

    /** `ASSET_SUPPLY_ROLE_REQUIRED` (422, `role`): the role is blank once cleaned. */
    data object RoleRequired : AssetSupplyProblem

    /** `ASSET_SUPPLY_TAKEN` (409, `role`): another row already holds this `(assetId, supplyId, role)`. */
    data object Taken : AssetSupplyProblem

    /** `NO_SUCH_ASSET_SUPPLY` (404): the row a re-role or a remove was aimed at is not there. */
    data object NoSuchAssetSupply : AssetSupplyProblem

    /** The re-role names the role the row already holds: nothing is written, `updatedAt` holds (200 with the row). */
    data object Unchanged : AssetSupplyProblem
}
