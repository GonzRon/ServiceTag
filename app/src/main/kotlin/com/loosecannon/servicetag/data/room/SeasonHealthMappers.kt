package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.data.room.entities.AssetConditionEntity
import com.loosecannon.servicetag.data.room.entities.AssetSeasonActivationEntity
import com.loosecannon.servicetag.data.room.entities.HealthSubjectEntity

// Schema v8's half of the mapping layer, for its three new tables. Same rules as
// [MaintenanceMappers.kt]: enums travel as their Kotlin name and `valueOf` rejects anything else
// here, at the repository boundary; every other column passes through unchanged in both directions.

fun AssetSeasonActivationEntity.toDomain(): SeasonActivation = SeasonActivation(
    id = id,
    assetId = AssetId(assetId),
    action = SeasonAction.valueOf(action),
    occurredOn = occurredOn,
    eventId = eventId?.let(::EventId),
    createdAt = createdAt,
)

fun SeasonActivation.toEntity(): AssetSeasonActivationEntity = AssetSeasonActivationEntity(
    id = id,
    assetId = assetId.value,
    action = action.name,
    occurredOn = occurredOn,
    eventId = eventId?.value,
    createdAt = createdAt,
)

fun AssetConditionEntity.toDomain(): AssetCondition = AssetCondition(
    id = id,
    assetId = AssetId(assetId),
    condition = OperationalCondition.valueOf(condition),
    occurredOn = occurredOn,
    occurredTime = occurredTime,
    tzId = tzId,
    reason = reason,
    eventId = eventId?.let(::EventId),
    createdAt = createdAt,
)

fun AssetCondition.toEntity(): AssetConditionEntity = AssetConditionEntity(
    id = id,
    assetId = assetId.value,
    condition = condition.name,
    occurredOn = occurredOn,
    occurredTime = occurredTime,
    tzId = tzId,
    reason = reason,
    eventId = eventId?.value,
    createdAt = createdAt,
)

fun HealthSubjectEntity.toDomain(): HealthSubject = HealthSubject(
    id = HealthSubjectId(id),
    assetId = AssetId(assetId),
    name = name,
    kind = HealthSubjectKind.valueOf(kind),
    driver = HealthDriver.valueOf(driver),
    scheduleId = scheduleId?.let(::ScheduleId),
    baselineProfileId = baselineProfileId?.let(::ProfileId),
    nominalUntilDays = nominalUntilDays,
    warningFromDays = warningFromDays,
    criticalFromDays = criticalFromDays,
    weight = weight,
    sortOrder = sortOrder,
    archivedAt = archivedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun HealthSubject.toEntity(): HealthSubjectEntity = HealthSubjectEntity(
    id = id.value,
    assetId = assetId.value,
    name = name,
    kind = kind.name,
    driver = driver.name,
    scheduleId = scheduleId?.value,
    baselineProfileId = baselineProfileId?.value,
    nominalUntilDays = nominalUntilDays,
    warningFromDays = warningFromDays,
    criticalFromDays = criticalFromDays,
    weight = weight,
    sortOrder = sortOrder,
    archivedAt = archivedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
