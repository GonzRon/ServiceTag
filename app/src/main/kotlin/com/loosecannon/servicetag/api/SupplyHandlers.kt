package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetSupply
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AssetSupplyRepository
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.usecase.AddAssetSupply
import com.loosecannon.servicetag.core.usecase.AddAssetSupplyCommand
import com.loosecannon.servicetag.core.usecase.ArchiveSupplyItem
import com.loosecannon.servicetag.core.usecase.AssetSupplyProblem
import com.loosecannon.servicetag.core.usecase.AssetSupplyResult
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.NoSuchSupplyItem
import com.loosecannon.servicetag.core.usecase.RemoveAssetSupply
import com.loosecannon.servicetag.core.usecase.SaveSupplyItem
import com.loosecannon.servicetag.core.usecase.SpecificationInput
import com.loosecannon.servicetag.core.usecase.SupplyItemCommand
import com.loosecannon.servicetag.core.usecase.UpdateAssetSupply
import com.loosecannon.servicetag.core.usecase.UpdateAssetSupplyCommand
import com.loosecannon.servicetag.di.AppGraph

/**
 * #15's SupplyItem and applicability rows (C22, C23), and **every write goes through exactly one use case**:
 * `SaveSupplyItem`, `ArchiveSupplyItem`, `AddAssetSupply`, `UpdateAssetSupply` or `RemoveAssetSupply`. Nothing here
 * re-checks a rule those own — the blank name, the specification rows and their key rule, the owned child ids, the
 * cleaned role, the triple, the archived item — and nothing here writes a repository; the reads are the lists and
 * the overlay's stored row.
 *
 * Three decisions carry the weight:
 *
 * - **The SupplyItem `PATCH` is an overlay assembled here** from the stored row (R15-16): a given key replaces the
 *   stored value, an absent or `null` one keeps it, and an absent `specifications` sends the stored rows back with
 *   their ids and keys, so they keep both. `""` is a value — it clears an optional text, and a blank name is the use
 *   case's 422 — never "unchanged". The use case receives one whole command either way.
 * - **A no-op edit is a 200 carrying the stored row**, on both `PATCH`es. `SaveSupplyItem` answers it `unchanged`;
 *   `UpdateAssetSupply` answers `Refused(Unchanged)` before any write — so a no-op re-role of a held asset's row never
 *   reaches the guard — and the row is re-read here for the 200 (the `UpdateReference` precedent).
 * - **Nothing deletes a SupplyItem** (R15-5): there is no such use case, so no route. An applicability row is
 *   configuration, not history, and `DELETE /v1/asset-supplies/{id}` removes one through `RemoveAssetSupply`.
 *
 * Built as `ApiHandlers`' collaborator, in [ReferenceHandlers]' shape, so the Developer API screen's wiring is
 * untouched: production builds it from `AppGraph` through `constructor(graph)`.
 */
internal class SupplyHandlers(
    private val supplyItems: SupplyItemRepository,
    private val assetSupplies: AssetSupplyRepository,
    private val assets: AssetRepository,
    private val saveSupplyItem: SaveSupplyItem,
    private val archiveSupplyItem: ArchiveSupplyItem,
    private val addAssetSupply: AddAssetSupply,
    private val updateAssetSupply: UpdateAssetSupply,
    private val removeAssetSupply: RemoveAssetSupply,
) {
    constructor(graph: AppGraph) : this(
        graph.supplyItems, graph.assetSupplies, graph.assets, graph.saveSupplyItem, graph.archiveSupplyItem,
        graph.addAssetSupply, graph.updateAssetSupply, graph.removeAssetSupply,
    )

    // --- SupplyItems ----------------------------------------------------------------------------

    /** Every SupplyItem, archived included (each carries its `archivedAt`), by name casefolded then id. */
    suspend fun list(): ApiResponse = ok(
        SupplyItemListResponse.serializer(),
        SupplyItemListResponse(supplyItems.all().sortedWith(BY_NAME).map { it.toDto() }),
    )

    /** The item and every applicability row naming it, in the port's `(assetId, role, id)` order. */
    suspend fun get(id: String): ApiResponse {
        val item = item(id)
        return ok(
            SupplyItemDetailResponse.serializer(),
            SupplyItemDetailResponse(item.toDto(), assetSupplies.forSupply(item.id).map { it.toDto() }),
        )
    }

    suspend fun create(request: ApiRequest): ApiResponse {
        val body = request.decode(CreateSupplyItemRequest.serializer())
        val saved = saveSupplyItem.run(null, body.toCommand())
        return createdResponse(SupplyItemResponse.serializer(), SupplyItemResponse(saved.item.toDto()))
    }

    /**
     * The overlay (C22, C-7): the stored row is read first because `SupplyItemCommand` is a **whole** item — there is
     * no partial command — and sending back a field the caller never named is how an edit leaves it alone. Whether
     * the save wrote or answered `unchanged`, the answer is 200 with the item as stored.
     */
    suspend fun update(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(UpdateSupplyItemRequest.serializer())
        val stored = item(id)
        val command = SupplyItemCommand(
            name = body.name ?: stored.name,
            category = body.category ?: stored.category,
            manufacturer = body.manufacturer ?: stored.manufacturer,
            model = body.model ?: stored.model,
            partNumber = body.partNumber ?: stored.partNumber,
            preferredUnit = body.preferredUnit ?: stored.preferredUnit,
            notes = body.notes ?: stored.notes,
            specifications = body.specifications?.map { it.toInput() } ?: stored.specifications.map {
                SpecificationInput(id = it.id, key = it.key, label = it.label, value = it.value, unit = it.unit)
            },
        )
        val saved = saveSupplyItem.run(stored.id, command)
        return ok(SupplyItemResponse.serializer(), SupplyItemResponse(saved.item.toDto()))
    }

    /** `{"archived": true｜false}`; reversible, and it leaves the item's applicability and every line link alone. */
    suspend fun archive(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(ArchiveRequest.serializer())
        val saved = archiveSupplyItem.run(SupplyId(id), body.archived)
        return ok(SupplyItemResponse.serializer(), SupplyItemResponse(saved.toDto()))
    }

    // --- applicability --------------------------------------------------------------------------

    /**
     * The twenty-sixth `/v1/assets/{id}/…` sub-resource: the asset's rows in the port's `(role, id)` order, and each
     * SupplyItem they name once, by name casefolded then id. A held asset reads as any other.
     */
    suspend fun listForAsset(assetId: String): ApiResponse {
        val asset = assets.get(AssetId(assetId)) ?: throw NoSuchAsset(AssetId(assetId))
        val rows = assetSupplies.forAsset(asset.id)
        val named = rows.map { it.supplyId }.distinct().mapNotNull { supplyItems.get(it) }.sortedWith(BY_NAME)
        return ok(
            AssetSupplyListResponse.serializer(),
            AssetSupplyListResponse(rows.map { it.toDto() }, named.map { it.toDto() }),
        )
    }

    suspend fun addAssetSupply(request: ApiRequest): ApiResponse {
        val body = request.decode(CreateAssetSupplyRequest.serializer())
        val row = addAssetSupply.run(
            AddAssetSupplyCommand(AssetId(body.assetId), SupplyId(body.supplyId), body.role),
        ).orRefuse()
        return createdResponse(AssetSupplyResponse.serializer(), AssetSupplyResponse(row.toDto()))
    }

    suspend fun updateAssetSupply(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(UpdateAssetSupplyRequest.serializer())
        val row = when (val result = updateAssetSupply.run(id, UpdateAssetSupplyCommand(body.role))) {
            is AssetSupplyResult.Ok -> result.row
            // The one refusal that is not one: nothing was written, so the stored row is the answer.
            is AssetSupplyResult.Refused ->
                if (result.problem == AssetSupplyProblem.Unchanged) {
                    assetSupplies.get(id) ?: throw assetSupplyFailure(AssetSupplyProblem.NoSuchAssetSupply)
                } else {
                    throw assetSupplyFailure(result.problem)
                }
        }
        return ok(AssetSupplyResponse.serializer(), AssetSupplyResponse(row.toDto()))
    }

    /** 204, the `DELETE /v1/events/{id}` answer (R15-5): the row goes, the SupplyItem and the asset stay. */
    suspend fun removeAssetSupply(id: String): ApiResponse {
        removeAssetSupply.run(id).orRefuse()
        return ApiResponse.empty(204, "No Content")
    }

    // --- status ---------------------------------------------------------------------------------

    /** `/v1/status`' `supplyItems` count: every SupplyItem, archived included, under the archive's list name. */
    suspend fun itemCount(): Int = supplyItems.all().size

    /** `/v1/status`' `assetSupplies` count: every applicability row, under the archive's list name. */
    suspend fun assetSupplyCount(): Int = assetSupplies.all().size

    // --- plumbing -------------------------------------------------------------------------------

    private suspend fun item(id: String): SupplyItem =
        supplyItems.get(SupplyId(id)) ?: throw NoSuchSupplyItem(SupplyId(id))

    /** The row, or the refusal as the status `ApiJson`'s one mapper names; the router answers it. */
    private fun AssetSupplyResult.orRefuse(): AssetSupply = when (this) {
        is AssetSupplyResult.Ok -> row
        is AssetSupplyResult.Refused -> throw assetSupplyFailure(problem)
    }

    private companion object {
        /** The list's order, one rule for both lists that carry items: name casefolded, then id. */
        val BY_NAME: Comparator<SupplyItem> = compareBy<SupplyItem> { it.name.lowercase() }.thenBy { it.id.value }
    }
}
