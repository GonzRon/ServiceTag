package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.hapublish.DecommissionRepository
import com.loosecannon.servicetag.core.hapublish.DeliverySteps
import com.loosecannon.servicetag.core.hapublish.PublishBindingRepository
import com.loosecannon.servicetag.core.hapublish.PublishDeliveryRepository
import com.loosecannon.servicetag.core.hapublish.Snap
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.SecretStore
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.di.AppGraph
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * #113/#114 (C35, G-R1) — the thirtieth `/v1/assets/{id}/…` sub-resource, read only: the asset's publishing, one
 * `uow.read` snapshot of its bindings, their deliveries, the stored connection, the asset row and the held ids, with
 * each binding's `PublishState` derived now by C17's own rules ([DeliverySteps.stateOf]) and the obligations the
 * asset left. The token is asked about through [SecretStore.has] alone and never read; the address, the Wi-Fi name and
 * the installation id are compared, never answered. An asset that is not there is the shipped 404 `no_such_asset`.
 * Nothing here writes.
 */
internal class HaPublishingHandlers(
    private val assets: AssetRepository,
    private val bindings: PublishBindingRepository,
    private val deliveries: PublishDeliveryRepository,
    private val decommissions: DecommissionRepository,
    private val connections: HaConnectionRepository,
    private val secrets: SecretStore,
    private val transfers: TransferRecordRepository,
    private val installationId: () -> String,
    private val uow: UnitOfWork,
) {
    constructor(graph: AppGraph) : this(
        graph.assets, graph.publishBindings, graph.publishDeliveries, graph.publishDecommissions, graph.haConnections,
        graph.secretStore, graph.transferRecords, graph.installationIdentity::id, graph.uow,
    )

    suspend fun get(assetId: String): ApiResponse {
        val id = AssetId(assetId)
        val here = installationId()
        val answer = uow.read {
            val asset = assets.get(id) ?: throw NoSuchAsset(id)
            val connection = connections.get()
            val hasToken = connection != null && secrets.has(connection.id)
            val held = transfers.heldIds()
            val rows = bindings.forAsset(id).mapNotNull { binding ->
                val delivery = deliveries.get(binding.id) ?: return@mapNotNull null
                val present = connection != null && connection.id == binding.connectionId
                val state = DeliverySteps.stateOf(Snap(binding, delivery, present, hasToken, here, asset, held))
                binding.toDto(delivery, state)
            }
            val owed = decommissions.all().filter { it.assetId == id }.sortedBy { it.requestedAt }.map { it.toDto() }
            HaPublishingResponse(assetId, rows, owed)
        }
        return ok(HaPublishingResponse.serializer(), answer)
    }

    /**
     * C35a (G-R2) — `GET /v1/ha-publishing`, one `uow.read`: every binding by asset then kind, led by its `assetId`,
     * and every obligation by request time then id — the only read of an orphan tombstone, whose asset is gone and
     * which [get] answers 404 for. `writesEnabled` is whether any binding is enabled. Nothing here writes.
     */
    suspend fun list(): ApiResponse {
        val here = installationId()
        val answer = uow.read {
            val connection = connections.get()
            val hasToken = connection != null && secrets.has(connection.id)
            val held = transfers.heldIds()
            val all = bindings.all().sortedWith(compareBy({ it.assetId.value }, { it.kind }))
            val rows = all.mapNotNull { binding ->
                val asset = assets.get(binding.assetId) ?: return@mapNotNull null
                val delivery = deliveries.get(binding.id) ?: return@mapNotNull null
                val present = connection != null && connection.id == binding.connectionId
                val state = DeliverySteps.stateOf(Snap(binding, delivery, present, hasToken, here, asset, held))
                val dto = ApiJson.encodeToJsonElement(HaPublishBindingDto.serializer(), binding.toDto(delivery, state)).jsonObject
                JsonObject(mapOf("assetId" to JsonPrimitive(binding.assetId.value)) + dto)
            }
            val owed = decommissions.all().sortedWith(compareBy({ it.requestedAt }, { it.id })).map { it.toCollectionDto() }
            HaPublishingCollectionResponse(all.any { it.enabled }, rows, owed)
        }
        return ok(HaPublishingCollectionResponse.serializer(), answer)
    }
}
