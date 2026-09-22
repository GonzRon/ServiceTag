package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.ClosureRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.ScheduleStateRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.schedule.statusOf
import com.loosecannon.servicetag.core.usecase.ArchiveGroup
import com.loosecannon.servicetag.core.usecase.ArchiveSchedule
import com.loosecannon.servicetag.core.usecase.CloseRound
import com.loosecannon.servicetag.core.usecase.CompleteGroupMembers
import com.loosecannon.servicetag.core.usecase.CompleteSchedule
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.NoSuchGroup
import com.loosecannon.servicetag.core.usecase.NoSuchSchedule
import com.loosecannon.servicetag.core.usecase.PauseSchedule
import com.loosecannon.servicetag.core.usecase.PostponeSchedule
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import com.loosecannon.servicetag.core.usecase.SaveGroup
import com.loosecannon.servicetag.core.usecase.SaveSchedule
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.maintenance.DueReadModel

/**
 * One method per 1.2 `/v1` endpoint, and **every write goes through exactly one use case.**
 *
 * That is the whole design rule of this file, and it is what makes the rules unreachable rather
 * than merely unwritten: a group's membership windows are `SaveGroup`'s, a schedule's shape is
 * `SaveSchedule`'s, a completion is `CompleteSchedule`'s or `CompleteGroupMembers`', a closure is
 * `CloseRound`'s. Nothing here re-checks what one of them already checks, and nothing here upserts
 * a row a use case owns. The read methods call a repository or B08's `DueReadModel`, and **they
 * read derived state and never write it** (invariant 17) — `RecomputeSchedules` is here only for
 * its pure `stateOf`, which derives without upserting.
 *
 * Three decisions carry the weight, because in each the route has to say something the use case
 * cannot:
 *
 * - **`POST …/complete` dispatches on the schedule's target, never on the body.** An asset target
 *   goes to `CompleteSchedule`, a group target to `CompleteGroupMembers` with the body's `assetId`
 *   as the single member — which is the split `CompleteSchedule`'s own KDoc asks the route to make.
 * - **A group completion that writes nothing is 409 `SCHEDULE_OCCURRENCE_TAKEN`.**
 *   `CompleteGroupMembers` answers a repeat with an empty list on purpose — a second tap has asked
 *   for nothing wrong — but a 201 carrying no event is not a representable answer on this wire, so
 *   the route names the outcome rather than inventing a body.
 * - **A group completion with no `assetId` is 422.** The use case cannot see it: an empty member
 *   list is legal to it and answers "nothing to do". Master plan §9.2 makes `assetId` required for
 *   a group target, and this is the one place that can tell "no member named" from "that member is
 *   already done".
 *
 * Built as `ApiHandlers`' one new collaborator rather than as a second router argument, so the
 * Developer API screen's own wiring is untouched: it hands over an `ApiHandlers` and knows nothing
 * about what the router matches.
 */
internal class MaintenanceHandlers(
    private val groups: GroupRepository,
    private val schedules: ScheduleRepository,
    private val states: ScheduleStateRepository,
    private val closures: ClosureRepository,
    private val assets: AssetRepository,
    private val saveGroup: SaveGroup,
    private val archiveGroup: ArchiveGroup,
    private val saveSchedule: SaveSchedule,
    private val pauseSchedule: PauseSchedule,
    private val archiveSchedule: ArchiveSchedule,
    private val postponeSchedule: PostponeSchedule,
    private val completeSchedule: CompleteSchedule,
    private val completeGroupMembers: CompleteGroupMembers,
    private val closeRound: CloseRound,
    private val due: DueReadModel,
    private val recompute: RecomputeSchedules,
    private val today: Today,
) {
    constructor(graph: AppGraph) : this(
        graph.groups, graph.schedules, graph.scheduleStates, graph.closures, graph.assets,
        graph.saveGroup, graph.archiveGroup,
        graph.saveSchedule, graph.pauseSchedule, graph.archiveSchedule, graph.postponeSchedule,
        graph.completeSchedule, graph.completeGroupMembers, graph.closeRound,
        graph.dueReadModel, graph.recomputeSchedules, graph.today,
    )

    // --- groups ---------------------------------------------------------------------------

    /** Archived groups included, by name — the list a person recognises, not a lifecycle filter. */
    suspend fun listGroups(): ApiResponse = ok(
        GroupListResponse.serializer(),
        GroupListResponse(groups.all().sortedBy { it.name.lowercase() }.map { it.toDto() }),
    )

    suspend fun getGroup(id: String): ApiResponse =
        ok(GroupResponse.serializer(), GroupResponse(group(id).toDto()))

    suspend fun createGroup(request: ApiRequest): ApiResponse {
        val body = request.decode(GroupCommandRequest.serializer())
        val saved = saveGroup.run(null, body.toCommand())
        return createdResponse(GroupResponse.serializer(), GroupResponse(saved.toDto()))
    }

    /** A **full replace**: an omitted member is soft-removed, never deleted (invariant 8). */
    suspend fun updateGroup(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(GroupCommandRequest.serializer())
        val saved = saveGroup.run(GroupId(id), body.toCommand())
        return ok(GroupResponse.serializer(), GroupResponse(saved.toDto()))
    }

    /** Archive is not delete: the group is hidden and its history is kept. Answers with the row. */
    suspend fun archiveGroup(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(ArchiveRequest.serializer())
        val saved = archiveGroup.run(GroupId(id), body.archived)
        return ok(GroupResponse.serializer(), GroupResponse(saved.toDto()))
    }

    /** #55's asset → groups direction: the groups this asset is an **open** member of. */
    suspend fun listAssetGroups(assetId: String): ApiResponse {
        asset(assetId)
        return ok(
            GroupListResponse.serializer(),
            GroupListResponse(groups.forAsset(AssetId(assetId)).map { it.toDto() }),
        )
    }

    // --- schedules ------------------------------------------------------------------------

    suspend fun listSchedules(): ApiResponse = scheduleList(schedules.all())

    /** This asset's own schedules — never a group's, even one it is a member of. */
    suspend fun listAssetSchedules(assetId: String): ApiResponse {
        asset(assetId)
        return scheduleList(schedules.forAsset(AssetId(assetId)))
    }

    /** Group-targeted only. One of them may require an asset; that is not this question. */
    suspend fun listGroupSchedules(groupId: String): ApiResponse {
        group(groupId)
        return scheduleList(schedules.forGroup(GroupId(groupId)))
    }

    /** `{schedule, state, status, computedForOn}` — the derived read projection (§9.3). */
    suspend fun getSchedule(id: String): ApiResponse {
        val schedule = schedule(id)
        val state = stateOf(schedule)
        val t = today.localDate()
        return ok(
            ScheduleDetailResponse.serializer(),
            ScheduleDetailResponse(
                schedule = schedule.toDto(),
                state = state.stateDto(),
                status = statusOf(schedule, state, t).name,
                computedForOn = t.toString(),
            ),
        )
    }

    suspend fun createSchedule(request: ApiRequest): ApiResponse {
        val body = request.decode(ScheduleCommandRequest.serializer())
        val saved = saveSchedule.run(null, body.toCommand())
        return createdResponse(ScheduleResponse.serializer(), ScheduleResponse(saved.toDto()))
    }

    /**
     * A **full replace**. A rule change clears the postponement, abandons an open partial
     * occurrence (D-9), moves the D-27 pin's floor to the edit date and rebuilds — all of it
     * `SaveSchedule`'s, none of it re-stated here.
     */
    suspend fun updateSchedule(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(ScheduleCommandRequest.serializer())
        val saved = saveSchedule.run(ScheduleId(id), body.toCommand())
        return ok(ScheduleResponse.serializer(), ScheduleResponse(saved.toDto()))
    }

    suspend fun pauseSchedule(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(PauseRequest.serializer())
        val saved = pauseSchedule.run(ScheduleId(id), body.paused)
        return ok(ScheduleResponse.serializer(), ScheduleResponse(saved.toDto()))
    }

    suspend fun archiveSchedule(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(ArchiveRequest.serializer())
        val saved = archiveSchedule.run(ScheduleId(id), body.archived)
        return ok(ScheduleResponse.serializer(), ScheduleResponse(saved.toDto()))
    }

    /** Changes no rule and creates no event; `null` clears the postponement. */
    suspend fun postponeSchedule(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(PostponeRequest.serializer())
        val saved = postponeSchedule.run(ScheduleId(id), body.postponedDueOn)
        return ok(
            ScheduleAndStateResponse.serializer(),
            ScheduleAndStateResponse(saved.toDto(), stateOf(saved).stateDto()),
        )
    }

    /**
     * The only route that writes a completion event. It dispatches on the schedule's **target**:
     * see this class's KDoc for why the two refusals below belong to the route and not to a use
     * case.
     */
    suspend fun completeSchedule(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(CompletionRequest.serializer())
        val scheduleId = ScheduleId(id)
        val schedule = schedule(id)
        val event = when (schedule.target) {
            is ScheduleTarget.AssetTarget -> completeSchedule.run(scheduleId, body.toCommand())
            is ScheduleTarget.GroupTarget -> {
                val member = body.assetId ?: throw ApiFailure(
                    422, "Unprocessable Content", "COMPLETION_MEMBER_REQUIRED",
                    "a group-targeted schedule is completed one member at a time, so assetId is required",
                    listOf("MemberRequired"),
                )
                completeGroupMembers.run(scheduleId, listOf(AssetId(member)), body.toCommand())
                    .firstOrNull() ?: throw ApiFailure(
                    409, "Conflict", "SCHEDULE_OCCURRENCE_TAKEN",
                    "that member is already recorded for this occurrence",
                )
            }
        }
        val after = schedule(id)
        return createdResponse(
            CompletionResponse.serializer(),
            CompletionResponse(event.toDto(), after.toDto(), stateOf(after).stateDto()),
        )
    }

    /** Group-targeted only. Writes one `occurrence_closure` row and nothing else (invariant 35). */
    suspend fun closeRound(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(CloseRoundRequest.serializer())
        val closure = closeRound.run(ScheduleId(id), body.closedOn)
        val after = schedule(id)
        return createdResponse(
            CloseRoundResponse.serializer(),
            CloseRoundResponse(closure.toDto(), after.toDto(), stateOf(after).stateDto()),
        )
    }

    /**
     * Read-only history, oldest first by the moment it was recorded.
     *
     * There is no route that deletes or amends one of these rows, and there is none anywhere: a
     * closure is immutable exported history, and a client that could edit one could rewrite a
     * schedule's past (invariants 37, 43).
     */
    suspend fun listClosures(id: String): ApiResponse {
        schedule(id)
        return ok(
            ClosureListResponse.serializer(),
            ClosureListResponse(
                closures.forSchedule(ScheduleId(id))
                    .sortedWith(compareBy({ it.createdAt }, { it.id }))
                    .map { it.toDto() },
            ),
        )
    }

    // --- the due projection ---------------------------------------------------------------

    /**
     * B08's projection, on the wire. **The same call the dashboard makes**, so the two orders and
     * the meaning of `rank` cannot drift; it starts from `listedForDue()`, so an ARCHIVED schedule
     * appears here in no circumstance.
     *
     * The two termination fields are read off `schedule_state`, which `DueItem` does not carry —
     * a read of derived state, never a write.
     */
    suspend fun listDue(): ApiResponse {
        val items = due.items()
        val stored = states.all().associateBy { it.scheduleId.value }
        val rows = schedules.all().associateBy { it.id.value }
        return ok(
            DueListResponse.serializer(),
            DueListResponse(
                items.map { item ->
                    val state = stored[item.scheduleId.value]
                        ?: rows[item.scheduleId.value]?.let { recompute.stateOf(it) }
                    item.dueDto(state?.stateDto())
                },
            ),
        )
    }

    // --- the three counts `/v1/status` gained ----------------------------------------------

    suspend fun counts(): Map<String, Int> = mapOf(
        "groups" to groups.all().size,
        "schedules" to schedules.all().size,
        "closures" to closures.all().size,
    )

    // --- plumbing ---------------------------------------------------------------------------

    private suspend fun scheduleList(rows: List<MaintenanceSchedule>): ApiResponse = ok(
        ScheduleListResponse.serializer(),
        ScheduleListResponse(
            rows.sortedWith(compareBy({ it.title.lowercase() }, { it.id.value })).map { it.toDto() },
        ),
    )

    private suspend fun asset(id: String) = assets.get(AssetId(id)) ?: throw NoSuchAsset(AssetId(id))

    private suspend fun group(id: String) = groups.get(GroupId(id)) ?: throw NoSuchGroup(GroupId(id))

    private suspend fun schedule(id: String) =
        schedules.get(ScheduleId(id)) ?: throw NoSuchSchedule(ScheduleId(id))

    /**
     * The stored derived row, or the same derivation done here when there is none.
     *
     * The fallback is B08's, for B08's reason: `stateOf` is pure and `rebuild` stays the only
     * writer. A row is missing only before anything has ever rebuilt this schedule, and answering
     * with a derived-but-unstored state is strictly better than answering with nothing.
     */
    private suspend fun stateOf(schedule: MaintenanceSchedule): ScheduleState =
        states.get(schedule.id) ?: recompute.stateOf(schedule)
}
