package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.schedule.SeasonContext
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.LinkScheduleSeason
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonActivationRepository
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonBindingRepository
import com.loosecannon.servicetag.core.seasonsync.SecretStore
import com.loosecannon.servicetag.core.seasonsync.UnlinkScheduleSeason
import com.loosecannon.servicetag.core.usecase.NoSuchSchedule
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.seasonsync.currentScheduleSeasonState
import kotlinx.serialization.json.JsonPrimitive

/**
 * #125 (U-C7; the controller's decision, narrowing R16-9 and G17 for schedule links only) — a schedule's own Home
 * Assistant season over `/v1`: `GET` and `POST /v1/schedules/{id}/season-sync`, mirroring the asset's season sync read.
 * Follow only: no Force, and a group's schedule is refused (inv. 106). Force, the token, the connection and Forget stay
 * the phone's alone here, and an asset's own link travels over `POST /v1/assets/{id}/season-sync` (#129); nothing here
 * reaches them, and no answer carries the address, the home Wi-Fi's name or the token.
 *
 * Reached from the router as `handlers.scheduleSeason.*`. [postLinkCheck] is the runner's `requestScheduleRead`, which
 * launches the schedule's first read on the app's scope and returns: the link's answer never waits on Home Assistant.
 */
internal class ScheduleSeasonHandlers(
    private val schedules: ScheduleRepository,
    private val assets: AssetRepository,
    private val transfers: TransferRecordRepository,
    private val bindings: ScheduleSeasonBindingRepository,
    private val activations: ScheduleSeasonActivationRepository,
    private val haConnections: HaConnectionRepository,
    private val secrets: SecretStore,
    private val backgroundAllowed: () -> Boolean,
    private val recompute: RecomputeSchedules,
    private val today: Today,
    private val link: LinkScheduleSeason,
    private val unlink: UnlinkScheduleSeason,
    private val postLinkCheck: (ScheduleId) -> Unit,
) {
    constructor(graph: AppGraph) : this(
        graph.schedules, graph.assets, graph.transferRecords, graph.scheduleSeasonBindings,
        graph.scheduleSeasonActivations, graph.haConnections, graph.secretStore, graph.seasonSyncBackgroundAllowed,
        graph.recomputeSchedules, graph.today, graph.linkScheduleSeason, graph.unlinkScheduleSeason,
        graph.seasonSyncRunner::requestScheduleRead,
    )

    /**
     * `GET /v1/schedules/{id}/season-sync`, read only: the schedule's source, its phase today, the connection's
     * settings, its binding at its derived state (or `null`) and its own season rows. A schedule that is not there is
     * 404 `NO_SUCH_SCHEDULE`. A key store that cannot load answers the shipped 500, never a guessed state. Nothing
     * here writes: no recompute, no state row.
     */
    suspend fun get(scheduleId: String): ApiResponse =
        ok(ScheduleSeasonResponse.serializer(), read(ScheduleId(scheduleId)))

    /**
     * `POST /v1/schedules/{id}/season-sync`. After the body's shape (400: not JSON, an unknown key, an unknown `action`), a
     * schedule that is not there is 404 `NO_SUCH_SCHEDULE`, before any confirmation.
     *
     * - `LINK` needs `entityId` (else 400) and the caller's `confirmEntityId`, exactly `entityId` (else 422
     *   `SCHEDULE_SEASON_CONFIRMATION_REQUIRED`), and takes no `confirmUnlink`. Then `LinkScheduleSeason` writes the
     *   binding and the `OWN` source in one write, and the schedule's first read is launched, never awaited.
     * - `UNLINK` needs the caller's `confirmUnlink`, the JSON `true` (else the 422), and takes neither `entityId` (400)
     *   nor `confirmEntityId` (the 422). Then `UnlinkScheduleSeason` returns the schedule to its asset's season,
     *   keeping its own rows as history; an `ASSET` schedule's unlink writes nothing.
     *
     * Each refusal comes before anything is written; a `ScheduleSeasonRefusal` is 409 `SCHEDULE_SEASON_REFUSED`. The
     * answer is 200 and the read route's shape.
     */
    suspend fun act(scheduleId: String, request: ApiRequest): ApiResponse {
        val body = request.decode(ScheduleSeasonRequest.serializer())
        val action = enumOr400<ScheduleSeasonAction>(body.action, "action")
        val id = ScheduleId(scheduleId)
        schedules.get(id) ?: throw NoSuchSchedule(id)
        when (action) {
            ScheduleSeasonAction.LINK -> {
                if (body.confirmUnlink != null) throw ScheduleSeasonConfirmationRequired(FIELD_CONFIRM_UNLINK, action)
                val entityId = body.entityId ?: throw ApiFailure.badRequest("LINK needs entityId")
                if (body.confirmEntityId != entityId) throw ScheduleSeasonConfirmationRequired(FIELD_CONFIRM_ENTITY_ID)
                link.run(id, entityId)
                postLinkCheck(id)
            }
            ScheduleSeasonAction.UNLINK -> {
                if (body.entityId != null) throw ApiFailure.badRequest("UNLINK takes no entityId")
                if (body.confirmEntityId != null) throw ScheduleSeasonConfirmationRequired(FIELD_CONFIRM_ENTITY_ID, action)
                if (body.confirmUnlink != JsonPrimitive(true)) throw ScheduleSeasonConfirmationRequired(FIELD_CONFIRM_UNLINK)
                unlink.run(id)
            }
        }
        return ok(ScheduleSeasonResponse.serializer(), read(id))
    }

    private suspend fun read(id: ScheduleId): ScheduleSeasonResponse {
        val schedule = schedules.get(id) ?: throw NoSuchSchedule(id)
        val stored = haConnections.get()
        val connection = if (stored == null) {
            SeasonSyncConnectionDto.NOT_CONFIGURED
        } else {
            stored.toSeasonSyncDto(secrets.has(stored.id), backgroundAllowed())
        }
        val binding = bindings.get(id)?.let { found ->
            found.toDto(currentScheduleSeasonState(found, secrets, schedules, assets, transfers))
        }
        val phase = recompute.seasonFor(schedule)?.let { SeasonContext.of(it).phaseAt(today.localDate()) }
        val rows = activations.forSchedule(id).sortedWith(compareBy({ it.occurredOn }, { it.createdAt }, { it.id }))
        return ScheduleSeasonResponse(
            scheduleId = id.value,
            seasonSource = schedule.seasonSource.name,
            seasonPhase = phase?.name,
            connection = connection,
            binding = binding,
            activations = rows.map { it.toDto() },
        )
    }
}

/** #125 (U-C7) — the two actions of `POST /v1/schedules/{id}/season-sync`. Follow only: there is no Force. */
internal enum class ScheduleSeasonAction { LINK, UNLINK }

/**
 * #125 (U-C7) — a link without the caller's own `confirmEntityId` equal to its `entityId`, an unlink without the
 * caller's `confirmUnlink: true`, or a confirmation the action does not check ([takenBy] names that action). Nothing was
 * written; nothing here fills either key. 422 `SCHEDULE_SEASON_CONFIRMATION_REQUIRED` at [field].
 */
internal class ScheduleSeasonConfirmationRequired(val field: String, val takenBy: ScheduleSeasonAction? = null) :
    IllegalArgumentException("schedule season confirmation required: $field")

internal const val FIELD_CONFIRM_ENTITY_ID = "confirmEntityId"
internal const val FIELD_CONFIRM_UNLINK = "confirmUnlink"
