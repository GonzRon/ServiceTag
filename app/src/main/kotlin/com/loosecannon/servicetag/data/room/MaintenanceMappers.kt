package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.GroupMember
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.model.TerminationKind
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.data.room.dao.GroupWithMembers
import com.loosecannon.servicetag.data.room.dao.ScheduleWithProviders
import com.loosecannon.servicetag.data.room.entities.MaintenanceGroupEntity
import com.loosecannon.servicetag.data.room.entities.MaintenanceGroupMemberEntity
import com.loosecannon.servicetag.data.room.entities.MaintenanceScheduleEntity
import com.loosecannon.servicetag.data.room.entities.OccurrenceClosureEntity
import com.loosecannon.servicetag.data.room.entities.ScheduleProviderEntity
import com.loosecannon.servicetag.data.room.entities.ScheduleStateEntity

// Schema v6's half of the mapping layer. Same rules as [JournalMappers.kt]: enums travel as their
// Kotlin name and `valueOf` rejects anything else here, at the repository boundary. Child-row ids
// pass through unchanged in both directions — the database never mints one, the domain always does.
// `@Relation` returns children in no particular order, so every read sorts: members by
// `(sortOrder, id)`, the order the backup format writes them in and the merge normalises on, and
// providers by `provider`, the only key they have.

fun GroupWithMembers.toDomain(): MaintenanceGroup = MaintenanceGroup(
    id = GroupId(group.id),
    name = group.name,
    description = group.description,
    archivedAt = group.archivedAt,
    createdAt = group.createdAt,
    updatedAt = group.updatedAt,
    members = members
        .sortedWith(compareBy({ it.sortOrder }, { it.id }))
        .map { it.toDomain() },
)

fun MaintenanceGroupMemberEntity.toDomain(): GroupMember = GroupMember(
    id = id,
    assetId = AssetId(assetId),
    sortOrder = sortOrder,
    addedAt = addedAt,
    removedAt = removedAt,
)

fun MaintenanceGroup.toEntity(): MaintenanceGroupEntity = MaintenanceGroupEntity(
    id = id.value,
    name = name,
    description = description,
    archivedAt = archivedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun GroupMember.toEntity(groupId: GroupId): MaintenanceGroupMemberEntity =
    MaintenanceGroupMemberEntity(
        id = id,
        groupId = groupId.value,
        assetId = assetId.value,
        sortOrder = sortOrder,
        addedAt = addedAt,
        removedAt = removedAt,
    )

/**
 * The two nullable target columns are one value in the domain: exactly one of them is set. A row
 * with both, or with neither, is one no writer above this layer can produce and the backup decoder
 * refuses, so it is reported here rather than being half-assembled into a schedule that cannot be.
 */
fun ScheduleWithProviders.toDomain(): MaintenanceSchedule = MaintenanceSchedule(
    id = ScheduleId(schedule.id),
    target = schedule.target(),
    title = schedule.title,
    description = schedule.description,
    timeInterval = schedule.timeInterval,
    timeUnit = schedule.timeUnit?.let { enumValueOf<RecurrenceUnit>(it) },
    timeBasis = enumValueOf<TimeBasis>(schedule.timeBasis),
    anchorOn = schedule.anchorOn,
    leadDays = schedule.leadDays,
    meterDefinitionId = schedule.meterDefinitionId?.let(::DefinitionId),
    meterInterval = schedule.meterInterval,
    anchorMeter = schedule.anchorMeter,
    meterLead = schedule.meterLead,
    seasonBehavior = enumValueOf<SeasonBehavior>(schedule.seasonBehavior),
    seasonReentry = schedule.seasonReentry,
    seasonReentryOffsetDays = schedule.seasonReentryOffsetDays,
    completionMode = enumValueOf<CompletionMode>(schedule.completionMode),
    profileId = schedule.profileId?.let(::ProfileId),
    remindersEnabled = schedule.remindersEnabled,
    status = enumValueOf<ScheduleStatus>(schedule.status),
    postponedDueOn = schedule.postponedDueOn,
    createdAt = schedule.createdAt,
    updatedAt = schedule.updatedAt,
    providers = providers.sortedBy { it.provider }.map { it.toDomain() },
)

private fun MaintenanceScheduleEntity.target(): ScheduleTarget {
    val asset = assetId
    val group = groupId
    return when {
        asset != null && group == null -> ScheduleTarget.AssetTarget(AssetId(asset))
        group != null && asset == null -> ScheduleTarget.GroupTarget(GroupId(group))
        else -> error("schedule $id must name exactly one target, an asset or a group")
    }
}

fun ScheduleProviderEntity.toDomain(): ScheduleProviderRow =
    ScheduleProviderRow(provider = provider, enabled = enabled)

fun MaintenanceSchedule.toEntity(): MaintenanceScheduleEntity = MaintenanceScheduleEntity(
    id = id.value,
    assetId = (target as? ScheduleTarget.AssetTarget)?.assetId?.value,
    groupId = (target as? ScheduleTarget.GroupTarget)?.groupId?.value,
    title = title,
    description = description,
    timeInterval = timeInterval,
    timeUnit = timeUnit?.name,
    timeBasis = timeBasis.name,
    anchorOn = anchorOn,
    leadDays = leadDays,
    meterDefinitionId = meterDefinitionId?.value,
    meterInterval = meterInterval,
    anchorMeter = anchorMeter,
    meterLead = meterLead,
    seasonBehavior = seasonBehavior.name,
    seasonReentry = seasonReentry,
    seasonReentryOffsetDays = seasonReentryOffsetDays,
    completionMode = completionMode.name,
    profileId = profileId?.value,
    remindersEnabled = remindersEnabled,
    status = status.name,
    postponedDueOn = postponedDueOn,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ScheduleProviderRow.toEntity(scheduleId: ScheduleId): ScheduleProviderEntity =
    ScheduleProviderEntity(
        scheduleId = scheduleId.value,
        provider = provider,
        enabled = enabled,
    )

fun OccurrenceClosureEntity.toDomain(): OccurrenceClosure = OccurrenceClosure(
    id = id,
    scheduleId = ScheduleId(scheduleId),
    occurrenceOn = occurrenceOn,
    closedOn = closedOn,
    createdAt = createdAt,
)

fun OccurrenceClosure.toEntity(): OccurrenceClosureEntity = OccurrenceClosureEntity(
    id = id,
    scheduleId = scheduleId.value,
    occurrenceOn = occurrenceOn,
    closedOn = closedOn,
    createdAt = createdAt,
)

fun ScheduleStateEntity.toDomain(): ScheduleState = ScheduleState(
    scheduleId = ScheduleId(scheduleId),
    lastCompletedOn = lastCompletedOn,
    lastCompletionEventId = lastCompletionEventId?.let(::EventId),
    lastCompletedMeter = lastCompletedMeter,
    currentMeter = currentMeter,
    computedDueMeter = computedDueMeter,
    lastTerminationEffectiveOn = lastTerminationEffectiveOn,
    lastTerminationKind = enumValueOf<TerminationKind>(lastTerminationKind),
    computedDueOn = computedDueOn,
    effectiveDueOn = effectiveDueOn,
    seasonActive = seasonActive,
    computedForOn = computedForOn,
    computedAt = computedAt,
)

fun ScheduleState.toEntity(): ScheduleStateEntity = ScheduleStateEntity(
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
    seasonActive = seasonActive,
    computedForOn = computedForOn,
    computedAt = computedAt,
)
