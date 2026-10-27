package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.seasonsync.EditSeasonSyncEntity
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.LinkSeasonSync
import com.loosecannon.servicetag.core.seasonsync.ResumeSeasonSync
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncRepository
import com.loosecannon.servicetag.core.seasonsync.SecretStore
import com.loosecannon.servicetag.core.seasonsync.SetSeasonSyncMode
import com.loosecannon.servicetag.core.seasonsync.StopSeasonSync
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.core.seasonsync.UnlinkSeasonSync
import com.loosecannon.servicetag.core.seasonsync.currentSeasonSyncState
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.di.AppGraph
import kotlinx.serialization.json.JsonPrimitive

/**
 * #129 (L-C8; R16-9 narrowed for the asset link) — `POST /v1/assets/{id}/season-sync`: an asset's Home Assistant season
 * link linked, pointed at another helper, stopped, resumed, set to Follow or Force, or removed, each through the phone's
 * own command. Sync now, the connection, the token and Forget stay the phone's alone: nothing here reaches them, and no
 * answer carries the address, the home Wi-Fi's name or the token.
 *
 * Reached from the router as `handlers.seasonSyncWrites.act`. No handler launches a read: the link, the edit of an
 * enabled binding, the resume and a switch to FOLLOW already ask the runner for one, which it launches on its own scope
 * and returns, so no answer waits on Home Assistant. `GET` stays `SeasonHealthHandlers.getSeasonSync`; the answer here
 * is built again the same way, not shared (M6).
 */
internal class SeasonSyncHandlers(
    private val assets: AssetRepository,
    private val bindings: SeasonSyncRepository,
    private val haConnections: HaConnectionRepository,
    private val secrets: SecretStore,
    private val transfers: TransferRecordRepository,
    private val backgroundAllowed: () -> Boolean,
    private val link: LinkSeasonSync,
    private val changeEntity: EditSeasonSyncEntity,
    private val stop: StopSeasonSync,
    private val resume: ResumeSeasonSync,
    private val setMode: SetSeasonSyncMode,
    private val unlink: UnlinkSeasonSync,
) {
    constructor(graph: AppGraph) : this(
        graph.assets, graph.seasonSyncBindings, graph.haConnections, graph.secretStore, graph.transferRecords,
        graph.seasonSyncBackgroundAllowed, graph.linkSeasonSync, graph.editSeasonSyncEntity, graph.stopSeasonSync,
        graph.resumeSeasonSync, graph.setSeasonSyncMode, graph.unlinkSeasonSync,
    )

    /**
     * The checks, first failure wins:
     * 1. the body's shape: 400 for not JSON, an unknown key, an unknown `action` or a `mode` that is not a [SyncMode];
     * 2. the asset: 404 `no_such_asset`, before any key or confirmation;
     * 3. the action's keys, for every action in this order (M2): a confirmation it does not take (422
     *    `SEASON_SYNC_CONFIRMATION_REQUIRED`, naming the action); a key it needs and lacks or has and does not take
     *    (400); its own confirmation (the 422) — `confirmEntityId` exactly `entityId` for `LINK` and `CHANGE_ENTITY`,
     *    the JSON `true` for `UNLINK`'s `confirmUnlink`; `STOP`, `RESUME` and `SET_MODE` take none;
     * 4. the command, whose refusals write nothing: a `SeasonSyncLinkRefused` is 409 `SEASON_SYNC_REFUSED`, no binding
     *    409 `SEASON_SYNC_NOT_LINKED`, and a switch into MANUAL keeps its shipped 409s.
     *
     * The answer is 200: `GET`'s shape after the write, and `switchedFrom`. A held asset is not refused here (D4).
     */
    suspend fun act(assetId: String, request: ApiRequest): ApiResponse {
        val body = request.decode(SeasonSyncRequest.serializer())
        val action = enumOr400<SeasonSyncAction>(body.action, "action")
        val mode = body.mode?.let { enumOr400<SyncMode>(it, "mode") }
        val id = AssetId(assetId)
        assets.get(id) ?: throw NoSuchAsset(id)
        val switchedFrom: SeasonMode? = when (action) {
            SeasonSyncAction.LINK -> link.run(id, confirmedEntity(action, body)).switchedFrom
            SeasonSyncAction.CHANGE_ENTITY -> {
                changeEntity.run(id, confirmedEntity(action, body))
                null
            }
            SeasonSyncAction.STOP -> {
                takesNoKey(action, body)
                stop.run(id)
                null
            }
            SeasonSyncAction.RESUME -> {
                takesNoKey(action, body)
                resume.run(id).switchedFrom
            }
            SeasonSyncAction.SET_MODE -> {
                refuseConfirmations(action, body, entity = true, unlink = true)
                if (body.entityId != null) throw ApiFailure.badRequest("SET_MODE takes no entityId")
                setMode.run(id, mode ?: throw ApiFailure.badRequest("SET_MODE needs mode"))
                null
            }
            SeasonSyncAction.UNLINK -> {
                refuseConfirmations(action, body, entity = true, unlink = false)
                if (body.entityId != null) throw ApiFailure.badRequest("UNLINK takes no entityId")
                if (body.mode != null) throw ApiFailure.badRequest("UNLINK takes no mode")
                if (body.confirmUnlink != JsonPrimitive(true)) {
                    throw SeasonSyncConfirmationRequired(FIELD_CONFIRM_UNLINK, action, taken = false)
                }
                unlink.run(id)
                null
            }
        }
        return ok(SeasonSyncActionResponse.serializer(), read(id, switchedFrom))
    }

    /**
     * `LINK`'s and `CHANGE_ENTITY`'s keys: no `confirmUnlink`, then `entityId` and no `mode`, then the caller's own
     * confirmation.
     */
    private fun confirmedEntity(action: SeasonSyncAction, body: SeasonSyncRequest): String {
        refuseConfirmations(action, body, entity = false, unlink = true)
        val entityId = body.entityId ?: throw ApiFailure.badRequest("${action.name} needs entityId")
        if (body.mode != null) throw ApiFailure.badRequest("${action.name} takes no mode")
        if (body.confirmEntityId != entityId) {
            throw SeasonSyncConfirmationRequired(FIELD_CONFIRM_ENTITY_ID, action, taken = false)
        }
        return entityId
    }

    /** `STOP`'s and `RESUME`'s keys: no confirmation, then neither `entityId` nor `mode`. */
    private fun takesNoKey(action: SeasonSyncAction, body: SeasonSyncRequest) {
        refuseConfirmations(action, body, entity = true, unlink = true)
        if (body.entityId != null) throw ApiFailure.badRequest("${action.name} takes no entityId")
        if (body.mode != null) throw ApiFailure.badRequest("${action.name} takes no mode")
    }

    /** Step 3.1: a confirmation [action] does not take, `confirmEntityId` before `confirmUnlink`, is the 422. */
    private fun refuseConfirmations(action: SeasonSyncAction, body: SeasonSyncRequest, entity: Boolean, unlink: Boolean) {
        if (entity && body.confirmEntityId != null) {
            throw SeasonSyncConfirmationRequired(FIELD_CONFIRM_ENTITY_ID, action, taken = true)
        }
        if (unlink && body.confirmUnlink != null) {
            throw SeasonSyncConfirmationRequired(FIELD_CONFIRM_UNLINK, action, taken = true)
        }
    }

    /** `GET`'s three keys, read after the write, and [switchedFrom]. */
    private suspend fun read(id: AssetId, switchedFrom: SeasonMode?): SeasonSyncActionResponse {
        val stored = haConnections.get()
        val connection = if (stored == null) {
            SeasonSyncConnectionDto.NOT_CONFIGURED
        } else {
            stored.toSeasonSyncDto(secrets.has(stored.id), backgroundAllowed())
        }
        val binding = bindings.get(id)?.let { found ->
            found.toDto(currentSeasonSyncState(found, secrets, assets, transfers))
        }
        return SeasonSyncActionResponse(id.value, connection, binding, switchedFrom?.name)
    }
}

/** #129 (L-C8) — the six actions of `POST /v1/assets/{id}/season-sync`. Sync now is not one: it stays the phone's. */
internal enum class SeasonSyncAction { LINK, CHANGE_ENTITY, STOP, RESUME, SET_MODE, UNLINK }

/**
 * #129 (L-C8, M1) — a `LINK` or `CHANGE_ENTITY` without the caller's own `confirmEntityId` equal to its `entityId`, an
 * `UNLINK` without the caller's `confirmUnlink: true`, or, when [taken], a confirmation [action] does not take. Nothing
 * was written; nothing here fills either key. 422 `SEASON_SYNC_CONFIRMATION_REQUIRED` at [field].
 */
internal class SeasonSyncConfirmationRequired(val field: String, val action: SeasonSyncAction, val taken: Boolean) :
    IllegalArgumentException("season sync confirmation required: $field")
