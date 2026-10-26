package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.hapublish.DecommissionRepository
import com.loosecannon.servicetag.core.hapublish.DeliverySteps
import com.loosecannon.servicetag.core.hapublish.FIELD_BOUNDARY_ENTITY_ID
import com.loosecannon.servicetag.core.hapublish.FIELD_ENTITY_ID
import com.loosecannon.servicetag.core.hapublish.FIELD_PLAN_ID
import com.loosecannon.servicetag.core.hapublish.INVALID_SERVICE_DATE
import com.loosecannon.servicetag.core.hapublish.PublishAction
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishBindingRepository
import com.loosecannon.servicetag.core.hapublish.PublishDelivery
import com.loosecannon.servicetag.core.hapublish.PublishDeliveryRepository
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.PublishRefusal
import com.loosecannon.servicetag.core.hapublish.PublishRefused
import com.loosecannon.servicetag.core.hapublish.STATUS_DEGRADED
import com.loosecannon.servicetag.core.hapublish.STATUS_DOWN
import com.loosecannon.servicetag.core.hapublish.STATUS_UP
import com.loosecannon.servicetag.core.hapublish.Snap
import com.loosecannon.servicetag.core.hapublish.VALIDATION_MAX_AGE_MILLIS
import com.loosecannon.servicetag.core.hapublish.planIdOf
import com.loosecannon.servicetag.core.hapublish.validateDateTarget
import com.loosecannon.servicetag.core.hapublish.validateTarget
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncRepository
import com.loosecannon.servicetag.core.seasonsync.SecretStore
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.hapublish.PublishControls
import com.loosecannon.servicetag.hapublish.Validation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * #113/#114 (C35, G-R1) — the thirtieth `/v1/assets/{id}/…` sub-resource, read only: the asset's publishing, one
 * `uow.read` snapshot of its bindings, their deliveries, the stored connection, the asset row and the held ids, with
 * each binding's `PublishState` derived now by C17's own rules ([DeliverySteps.stateOf]) and the obligations the
 * asset left. The token is asked about through [SecretStore.has] alone and never read; the address, the Wi-Fi name and
 * the installation id are compared, never answered. An asset that is not there is the shipped 404 `no_such_asset`.
 * The reads write nothing.
 *
 * **The controls (C35b, O-21; B5w1: the plan, Enable and Change target).** [plan] is the validation itself — the
 * runner's [validate], one or two fresh reads of Home Assistant — and writes nothing; a plan the validators accept is
 * held in [plans] under its `planId` ([planIdOf], C25a). [act] commits a held plan through [controls], the phone's own
 * facade, so every commit asks for delivery (I21); it reads Home Assistant no second time, and the command's own
 * transaction answers `STALE_PLAN` when the connection, its generation, the binding or a helper's shape moved since
 * (I34). No body takes a value, a date to publish or READY (I33).
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
    private val seasonSync: SeasonSyncRepository,
    private val validate: suspend (AssetId, PublishKind, String, String?) -> Validation,
    private val controls: PublishControls,
    private val plans: HaPublishingPlans,
) {
    constructor(graph: AppGraph) : this(
        graph.assets, graph.publishBindings, graph.publishDeliveries, graph.publishDecommissions, graph.haConnections,
        graph.secretStore, graph.transferRecords, graph.installationIdentity::id, graph.uow, graph.seasonSyncBindings,
        graph.publishRunner::validate, graph.publishControls, HaPublishingPlans(graph.clock),
    )

    suspend fun get(assetId: String): ApiResponse {
        val id = AssetId(assetId)
        val here = installationId()
        val answer = uow.read {
            val asset = assets.get(id) ?: throw NoSuchAsset(id)
            val connection = connections.get()
            val hasToken = connection != null && secrets.has(connection.id)
            val held = transfers.heldIds()
            val rows = bindings.forAsset(id).map { binding ->
                // B5 review N4: Enable writes both rows in one write; a missing delivery is answered empty, never dropped.
                val delivery = deliveries.get(binding.id) ?: emptyDelivery(binding)
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
                val delivery = deliveries.get(binding.id) ?: emptyDelivery(binding)
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

    /**
     * C35b, C25a — `POST /v1/assets/{id}/ha-publishing-plan`, which writes nothing (the `replace-plan` precedent): the
     * runner's validation of the named helpers **is** the plan step. A read or validator failure is a 200 with
     * `refusal` and `planId: null` (the replace-plan `problems` precedent); a plan the validators accept carries its
     * `planId` and is held until it expires, is committed, or the screen closes. `refusal` is the validators' verdict
     * alone: C25's transaction refusals answer at the action. An asset that is not there is 404 `no_such_asset`.
     */
    suspend fun plan(assetId: String, request: ApiRequest): ApiResponse {
        val body = request.decode(HaPublishingPlanRequest.serializer())
        val kind = enumOr400<PublishKind>(body.kind, "kind")
        val action = enumOr400<PublishAction>(body.action, "action")
        val id = AssetId(assetId)
        val boundaryRequired = uow.read {
            val asset = assets.get(id) ?: throw NoSuchAsset(id)
            when (kind) {
                PublishKind.EQUIPMENT_STATUS -> false
                PublishKind.SEASON_SERVICE_DATE -> when (asset.seasonMode) {
                    SeasonMode.YEAR_ROUND, SeasonMode.CALENDAR -> false
                    SeasonMode.MANUAL -> seasonSync.get(id)?.enabled == true
                }
            }
        }
        val validation = validate(id, kind, body.entityId, body.boundaryEntityId)
        val validated = validation as? Validation.Validated
        val refusal: Pair<String, String?>? = when (validation) {
            Validation.NoConnection -> PublishRefusal.NO_CONNECTION.name to null
            is Validation.ReadFailed -> validation.kind.name to validation.field
            is Validation.Validated ->
                validateTarget(kind, body.entityId, validation.target.remote)?.let { it.name to FIELD_ENTITY_ID }
                    ?: validation.boundary?.let { read ->
                        validateDateTarget(read.target, read.remote)?.let { it.name to FIELD_BOUNDARY_ENTITY_ID }
                    }
        }
        val planId = validated?.takeIf { refusal == null }?.let { held ->
            planIdOf(
                id, kind, action, body.entityId, held.boundary?.target, held.target.connectionId,
                held.target.configGeneration, held.target.bindingRevision, held.target.remote, held.boundary?.remote,
            ).also { plans.put(it, HaPublishingPlans.Plan(id, kind, action, body.entityId, held)) }
        }
        val answer = buildJsonObject {
            put("planId", planId)
            put("assetId", assetId)
            put("kind", kind.name)
            put("action", action.name)
            put("entityId", body.entityId)
            put("boundaryEntityId", body.boundaryEntityId)
            put("boundaryRequired", boundaryRequired)
            val remote = validated?.target?.remote
            if (remote == null) {
                put("remote", JsonNull)
            } else {
                putJsonObject("remote") {
                    put("domain", remote.entityId.substringBefore('.', missingDelimiterValue = ""))
                    val options = remote.options
                    if (options == null) put("options", JsonNull) else putJsonArray("options") { options.forEach { add(JsonPrimitive(it)) } }
                    put("hasDate", remote.hasDate)
                    put("hasTime", remote.hasTime)
                }
            }
            val boundary = validated?.boundary
            if (boundary == null) {
                put("boundary", JsonNull)
            } else {
                putJsonObject("boundary") {
                    put("value", boundary.remote.state)
                    put("readAt", boundary.readAt)
                    put("hasDate", boundary.remote.hasDate)
                    put("hasTime", boundary.remote.hasTime)
                }
            }
            when (kind) {
                PublishKind.EQUIPMENT_STATUS -> putJsonObject("mapping") {
                    OperationalCondition.entries.forEach { put(it.name, statusOptionOf(it)) }
                }
                PublishKind.SEASON_SERVICE_DATE -> put("invalidDate", INVALID_SERVICE_DATE)
            }
            if (refusal == null) {
                put("refusal", JsonNull)
            } else {
                putJsonObject("refusal") {
                    put("reason", refusal.first)
                    put("field", refusal.second)
                }
            }
            put("readAt", validated?.target?.readAt)
            put("expiresAt", planId?.let { validated?.target?.readAt?.plus(VALIDATION_MAX_AGE_MILLIS) })
        }
        return ok(JsonObject.serializer(), answer)
    }

    /**
     * C35b — `POST /v1/assets/{id}/ha-publishing`, B5w1's two actions. `ENABLE` and `CHANGE_TARGET` need the caller's
     * `planId` and `confirmDestination`: either missing → 422 `HA_PUBLISH_CONFIRMATION_REQUIRED`; a plan [plans] does
     * not hold for this asset, kind and action (unknown, expired, committed, or the screen reopened) → 409
     * `STALE_PLAN`; a `confirmDestination` other than the plan's `entityId` → the 422; each before anything is written.
     * Then [PublishControls.enable] or [PublishControls.changeTarget] commits the plan's own validation with
     * `expectedPlanId`, activating this installation (O-21(c)), and asks for delivery; no second read of Home
     * Assistant. Success is 200 `{binding}`, the binding as the read route answers it.
     */
    suspend fun act(assetId: String, request: ApiRequest): ApiResponse {
        val body = request.decode(HaPublishingActionRequest.serializer())
        val kind = enumOr400<PublishKind>(body.kind, "kind")
        val action = enumOr400<PublishAction>(body.action, "action")
        val id = AssetId(assetId)
        uow.read { assets.get(id) ?: throw NoSuchAsset(id) }
        val planId = body.planId ?: throw PublishConfirmationRequired(FIELD_PLAN_ID)
        val confirmed = body.confirmDestination ?: throw PublishConfirmationRequired(FIELD_CONFIRM_DESTINATION)
        val plan = plans.find(planId, id, kind, action) ?: throw PublishRefused(PublishRefusal.STALE_PLAN, FIELD_PLAN_ID)
        if (confirmed != plan.entityId) throw PublishConfirmationRequired(FIELD_CONFIRM_DESTINATION)
        val (target, boundary) = plan.validated
        val committed = when (action) {
            PublishAction.ENABLE -> controls.enable(id, kind, plan.entityId, target, boundary, planId)
            PublishAction.CHANGE_TARGET -> controls.changeTarget(id, kind, plan.entityId, target, boundary, planId)
        }
        plans.remove(planId)
        return ok(HaPublishingBindingResponse.serializer(), HaPublishingBindingResponse(bindingDtoOf(committed)))
    }

    /** [binding] as the read route answers it, its state derived now from one snapshot. */
    private suspend fun bindingDtoOf(binding: PublishBinding): HaPublishBindingDto {
        val here = installationId()
        return uow.read {
            val stored = bindings.forAsset(binding.assetId).first { it.id == binding.id }
            val asset = assets.get(binding.assetId)
            val connection = connections.get()
            val hasToken = connection != null && secrets.has(connection.id)
            val delivery = deliveries.get(stored.id) ?: emptyDelivery(stored)
            val present = connection != null && connection.id == stored.connectionId
            val state = DeliverySteps.stateOf(Snap(stored, delivery, present, hasToken, here, asset, transfers.heldIds()))
            stored.toDto(delivery, state)
        }
    }

    private companion object {
        const val FIELD_CONFIRM_DESTINATION = "confirmDestination"

        /** A binding's delivery before any is stored: nothing desired, nothing sent or read. */
        fun emptyDelivery(binding: PublishBinding) = PublishDelivery(binding.id, null, 0, binding.createdAt)

        /** The option C15's mapper publishes for each recorded condition, shown in the status plan's `mapping`. */
        fun statusOptionOf(condition: OperationalCondition): String = when (condition) {
            OperationalCondition.OPERATIONAL -> STATUS_UP
            OperationalCondition.DEGRADED -> STATUS_DEGRADED
            OperationalCondition.DOWN -> STATUS_DOWN
        }
    }
}

/**
 * #113/#114 (C35b) — 422 `HA_PUBLISH_CONFIRMATION_REQUIRED`: an action's confirmation at [field] is missing, or
 * `confirmDestination` is not the plan's own `entityId`. Nothing was written. The caller supplies both; nothing here
 * fills either.
 */
internal class PublishConfirmationRequired(val field: String) :
    IllegalArgumentException("publishing confirmation required: $field")

/** A control's answer (C35b): the binding as `GET /v1/assets/{id}/ha-publishing` answers it. */
@Serializable
internal data class HaPublishingBindingResponse(val binding: HaPublishBindingDto)
