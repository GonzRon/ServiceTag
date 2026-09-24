package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.AssetEventDto
import com.loosecannon.servicetag.core.backup.MaintenanceGroupDto
import com.loosecannon.servicetag.core.backup.OccurrenceClosureDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.LegacySeasonMapping
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.core.usecase.ConsumableInput
import com.loosecannon.servicetag.core.usecase.GroupCommand
import com.loosecannon.servicetag.core.usecase.GroupMemberInput
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.ui.maintenance.DueItem
import kotlinx.serialization.Serializable

/*
 * The 1.2 maintenance shapes on the wire (master plan §9).
 *
 * The two rules `api/ApiDtos.kt:30`–`41` states hold here unchanged. **Responses reuse the backup
 * format's own row DTOs** — `MaintenanceGroupDto`, `OccurrenceClosureDto`, `AssetEventDto` — so a
 * row read here and the same row inside `data.json` are the same JSON object. The schedule is the
 * one documented exception: every response that carries one carries a [ScheduleRowResponse], the
 * format-8 row plus 1.3's season triple derived from its policy (master plan §11.3). **Requests are declared here and nowhere else**, with plain
 * `String`/`Int`/`Double?` fields, each converting to its domain command in exactly one place.
 *
 * One shape is neither: [ScheduleStateDto] is a **derived read projection**. It is declared here
 * and deliberately **not** in `core.backup` — no command accepts one and no archive carries one,
 * because derived state that travels in an archive is derived state that can arrive stale
 * (invariants 17, 64). `MaintenanceCommandShapeTest` asserts both halves structurally.
 */

// --- responses ----------------------------------------------------------------------------------

@Serializable
internal data class GroupListResponse(val groups: List<MaintenanceGroupDto>)

@Serializable
internal data class GroupResponse(val group: MaintenanceGroupDto)

@Serializable
internal data class ScheduleListResponse(val schedules: List<ScheduleRowResponse>)

@Serializable
internal data class ScheduleResponse(val schedule: ScheduleRowResponse)

/**
 * `schedule_state`'s columns, as a client reads them — never as a command writes them.
 *
 * Every field is recomputable from configuration, events, closures, membership and `T`, and
 * `ScheduleRecompute.rebuild` is the only thing that ever writes the row (invariant 17). This
 * projection exists so a client can see what the engine derived without having to re-derive it, and
 * for no other reason.
 */
@Serializable
internal data class ScheduleStateDto(
    val scheduleId: String,
    val lastCompletedOn: String?,
    val lastCompletionEventId: String?,
    val lastCompletedMeter: Double?,
    val currentMeter: Double?,
    val computedDueMeter: Double?,
    val lastTerminationEffectiveOn: String?,
    val lastTerminationKind: String,
    val computedDueOn: String?,
    val effectiveDueOn: String?,
    val seasonActive: Boolean,
    val computedForOn: String,
    val computedAt: Long,
)

internal fun ScheduleState.stateDto(): ScheduleStateDto = ScheduleStateDto(
    scheduleId = scheduleId.value,
    lastCompletedOn = lastCompletedOn,
    lastCompletionEventId = lastCompletionEventId?.value,
    lastCompletedMeter = lastCompletedMeter,
    currentMeter = currentMeter,
    computedDueMeter = computedDueMeter,
    lastTerminationEffectiveOn = lastTerminationEffectiveOn,
    lastTerminationKind = lastTerminationKind.name,
    computedDueOn = computedDueOn,
    effectiveDueOn = effectiveDueOn,
    // The 1.3 field, answered as 1.3 answered it: a DORMANT schedule is the one out of season.
    seasonActive = policyPhase == PolicyPhase.ACTIVE,
    computedForOn = computedForOn,
    computedAt = computedAt,
)

/**
 * `GET /v1/schedules/{id}`: the row, the derived state, the computed status word, and the `today`
 * that word was computed for. [status] is never stored (invariant 18), so [computedForOn] is what
 * lets a client know which day it is reading.
 */
@Serializable
internal data class ScheduleDetailResponse(
    val schedule: ScheduleRowResponse,
    val state: ScheduleStateDto,
    val status: String,
    val computedForOn: String,
)

/** `POST /v1/schedules/{id}/postpone`: the row and what the engine now derives from it. */
@Serializable
internal data class ScheduleAndStateResponse(
    val schedule: ScheduleRowResponse,
    val state: ScheduleStateDto,
)

/** `POST /v1/schedules/{id}/complete` — the only route that writes a completion event. */
@Serializable
internal data class CompletionResponse(
    val event: AssetEventDto,
    val schedule: ScheduleRowResponse,
    val state: ScheduleStateDto,
)

/** `POST /v1/schedules/{id}/close-round`: the one immutable row it wrote, and the effect. */
@Serializable
internal data class CloseRoundResponse(
    val closure: OccurrenceClosureDto,
    val schedule: ScheduleRowResponse,
    val state: ScheduleStateDto,
)

/** `GET /v1/schedules/{id}/closures` — read-only history, oldest first. */
@Serializable
internal data class ClosureListResponse(val closures: List<OccurrenceClosureDto>)

/**
 * One row of `GET /v1/due`, master plan §9.3's shape.
 *
 * It is B08's `DueItem` on the wire, plus the two termination fields the projection reads off
 * `schedule_state` — so the API and the app order identically and [rank] means the same thing in
 * both. A group-targeted schedule is **one** item carrying [membersRequired]/[membersComplete] and
 * is counted once (D-15). Every field is present with its default encoded, so a client never has to
 * distinguish absent from default.
 */
@Serializable
internal data class DueItemDto(
    val scheduleId: String,
    /** `"ASSET"` or `"GROUP"` — which of [assetId] and [groupId] is the one that is set. */
    val targetKind: String,
    val assetId: String?,
    val groupId: String?,
    val title: String,
    val status: String,
    val effectiveDueOn: String?,
    val computedDueMeter: Double?,
    val currentMeter: Double?,
    val lastCompletedOn: String?,
    val lastTerminationEffectiveOn: String?,
    val lastTerminationKind: String,
    val completionMode: String,
    val membersRequired: Int?,
    val membersComplete: Int?,
    /** The 0-based index of this item in the attention order — dense and ascending (decision 9). */
    val rank: Int,
)

internal fun DueItem.dueDto(state: ScheduleStateDto?): DueItemDto = DueItemDto(
    scheduleId = scheduleId.value,
    targetKind = when (target) {
        is ScheduleTarget.AssetTarget -> "ASSET"
        is ScheduleTarget.GroupTarget -> "GROUP"
    },
    assetId = (target as? ScheduleTarget.AssetTarget)?.assetId?.value,
    groupId = (target as? ScheduleTarget.GroupTarget)?.groupId?.value,
    title = title,
    status = status.name,
    effectiveDueOn = effectiveDueOn?.toString(),
    computedDueMeter = computedDueMeter,
    currentMeter = currentMeter,
    lastCompletedOn = lastCompletedOn?.toString(),
    lastTerminationEffectiveOn = state?.lastTerminationEffectiveOn,
    // A schedule with no state row has no recorded termination, which is what `NONE` means.
    lastTerminationKind = state?.lastTerminationKind ?: "NONE",
    completionMode = completionMode.name,
    membersRequired = membersRequired,
    membersComplete = membersComplete,
    rank = rank,
)

@Serializable
internal data class DueListResponse(val items: List<DueItemDto>)

// --- requests -----------------------------------------------------------------------------------

/**
 * One line of the group command's member list.
 *
 * [id] names an existing membership window to **keep**; a line with no [id] is an **add**, and an
 * add for an asset that already holds an open window is 422 `MEMBER_ALREADY_OPEN`. `addedAt` and
 * `removedAt` are server-stamped and appear here deliberately not at all: a caller says who the
 * members are now, and when each of them joined and left is a fact the use case records.
 */
@Serializable
internal data class GroupMemberRequest(
    val id: String? = null,
    val assetId: String,
    val sortOrder: Int = 0,
)

/** `SaveGroup.run(id, cmd)` as one body. A **full replace**: an omitted member is soft-removed. */
@Serializable
internal data class GroupCommandRequest(
    val name: String,
    val description: String = "",
    val members: List<GroupMemberRequest> = emptyList(),
)

internal fun GroupCommandRequest.toCommand() = GroupCommand(
    name = name,
    description = description,
    members = members.map { GroupMemberInput(AssetId(it.assetId), it.id, it.sortOrder) },
)

/** One `schedule_provider` row: a set of enabled providers, not an enum column. */
@Serializable
internal data class ScheduleProviderRequest(val provider: String, val enabled: Boolean = true)

/**
 * `SaveSchedule.run(id, cmd)` as one body.
 *
 * The two target ids are named [targetAssetId] and [targetGroupId] — not `assetId`/`groupId`, which
 * is what the *row* reports — because that is `ScheduleCommand`'s own naming and master plan §9.2's:
 * exactly one of them is set, and the pair is a choice of target rather than two fields of a row.
 * It is the schedule's equivalent of the event command's documented `values`/`measurements` gap.
 *
 * `postponedDueOn` is **not** here: the postponement is an operation with its own route, which is
 * the distinction that stops a postpone from quietly becoming a reschedule.
 */
@Serializable
internal data class ScheduleCommandRequest(
    val targetAssetId: String? = null,
    val targetGroupId: String? = null,
    val title: String,
    val description: String = "",
    val timeInterval: Int? = null,
    val timeUnit: String? = null,
    val timeBasis: String = "FIXED",
    val anchorOn: String? = null,
    val leadDays: Int = 0,
    val meterDefinitionId: String? = null,
    val meterInterval: Double? = null,
    val anchorMeter: Double? = null,
    val meterLead: Double? = null,
    val seasonBehavior: String = "IGNORE",
    val seasonReentry: String? = null,
    val seasonReentryOffsetDays: Int? = null,
    val completionMode: String = "QUICK",
    val profileId: String? = null,
    val remindersEnabled: Boolean = true,
    val providers: List<ScheduleProviderRequest> = emptyList(),
)

internal fun ScheduleCommandRequest.toCommand(): ScheduleCommand {
    // Parsed in the shipped field order, so a body with two bad enum names still names the first.
    val unit = timeUnit?.let { enumOr400<RecurrenceUnit>(it, "timeUnit") }
    val basis = enumOr400<TimeBasis>(timeBasis, "timeBasis")
    // The three 1.3 season fields reach the command only through the legacy mapping, which
    // normalises them; an unknown `seasonBehavior` name is still the shipped 400.
    val policy = LegacySeasonMapping.toPolicy(
        behavior = enumOr400<SeasonBehavior>(seasonBehavior, "seasonBehavior"),
        reentry = seasonReentry,
        offsetDays = seasonReentryOffsetDays,
        hasTimeRule = timeInterval != null,
    )
    return ScheduleCommand(
        targetAssetId = targetAssetId?.let(::AssetId),
        targetGroupId = targetGroupId?.let(::GroupId),
        title = title,
        description = description,
        timeInterval = timeInterval,
        timeUnit = unit,
        timeBasis = basis,
        anchorOn = anchorOn,
        leadDays = leadDays,
        meterDefinitionId = meterDefinitionId?.let(::DefinitionId),
        meterInterval = meterInterval,
        anchorMeter = anchorMeter,
        meterLead = meterLead,
        servicePolicy = policy.servicePolicy,
        policyOffsetDays = policy.policyOffsetDays,
        completionMode = enumOr400<CompletionMode>(completionMode, "completionMode"),
        profileId = profileId?.let(::ProfileId),
        remindersEnabled = remindersEnabled,
        // The provider name stays text all the way to the use case: an unknown one is a *validation*
        // problem it reports by name (`UnknownProvider`), not a 400 about a bad enum, because the
        // column is TEXT and the set grows without a migration.
        providers = providers.map { ScheduleProviderRow(it.provider, it.enabled) },
    )
}

/**
 * One completion of one occurrence.
 *
 * `occurrenceOn` is **not** here — it is stamped from the schedule's own computed due date at write
 * time, never sent by a caller (invariant 21). [assetId] names the member who did the work: it is
 * required for a group-targeted schedule and must be absent or the schedule's own asset otherwise.
 */
@Serializable
internal data class CompletionRequest(
    val occurredOn: String,
    val occurredTime: String? = null,
    val tzId: String,
    val notes: String = "",
    val values: Map<String, String> = emptyMap(),
    val consumables: List<ConsumableRequest> = emptyList(),
    val assetId: String? = null,
)

internal fun CompletionRequest.toCommand() = CompletionCommand(
    occurredOn = occurredOn,
    occurredTime = occurredTime,
    tzId = tzId,
    notes = notes,
    values = values.mapKeys { (id, _) -> DefinitionId(id) },
    consumables = consumables.map { ConsumableInput(it.name, it.quantity, it.unit) },
    assetId = assetId?.let(::AssetId),
)

/** `null` clears the postponement; a date sets one. The same shape either way. */
@Serializable
internal data class PostponeRequest(val postponedDueOn: String? = null)

/** `true` pauses, `false` resumes. */
@Serializable
internal data class PauseRequest(val paused: Boolean)

/** Omitted means today. Any date from the round's open date through today is accepted. */
@Serializable
internal data class CloseRoundRequest(val closedOn: String? = null)
