package com.loosecannon.servicetag.testing

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.Measurement
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.model.GroupMember
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.model.AssetId
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Schedule and group builders for `:app`'s JVM suite. `:core` has its own set for the engine tests
 * and the two test source sets do not see each other, so this is the `:app` side of the same shape:
 * every field a read model looks at is a named argument and everything else is defaulted.
 *
 * Rows built here go into the tables through the production repositories, and derived state is
 * always produced by the real recompute — never hand-written — so a test can never assert against a
 * `schedule_state` row the engine would not have written.
 */

/**
 * Midnight **UTC** of [date] — the conversion a membership window and an occurrence key both use.
 *
 * It is also why `FakeGraph` hands its `RecomputeSchedules` `ZoneOffset.UTC`: UTC is the zone in
 * which these instants carry the date they are written as, and the pin's floor is read in whatever
 * zone the caller supplies — the owner's, in the app — so a suite that left it unsaid would be
 * asserting against whatever zone the machine happens to be in.
 */
fun dayMillis(date: String): Long =
    LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun scheduleOf(
    id: String,
    assetId: String? = null,
    groupId: String? = null,
    title: String = "Quarterly inspection",
    timeInterval: Int? = 3,
    timeUnit: RecurrenceUnit? = RecurrenceUnit.MONTH,
    timeBasis: TimeBasis = TimeBasis.FIXED,
    anchorOn: String? = "2026-01-01",
    leadDays: Int = 14,
    meterDefinitionId: String? = null,
    meterInterval: Double? = null,
    anchorMeter: Double? = null,
    meterLead: Double? = null,
    servicePolicy: ServicePolicy = ServicePolicy.CONTINUOUS,
    policyOffsetDays: Int? = null,
    completionMode: CompletionMode = CompletionMode.QUICK,
    status: ScheduleStatus = ScheduleStatus.ACTIVE,
    postponedDueOn: String? = null,
    createdOn: String = "2026-01-01",
): MaintenanceSchedule = MaintenanceSchedule(
    id = ScheduleId(id),
    target = groupId?.let { ScheduleTarget.GroupTarget(GroupId(it)) }
        ?: ScheduleTarget.AssetTarget(AssetId(assetId!!)),
    title = title,
    description = "",
    timeInterval = timeInterval,
    timeUnit = timeUnit,
    timeBasis = timeBasis,
    anchorOn = anchorOn,
    leadDays = leadDays,
    meterDefinitionId = meterDefinitionId?.let(::DefinitionId),
    meterInterval = meterInterval,
    anchorMeter = anchorMeter,
    meterLead = meterLead,
    servicePolicy = servicePolicy,
    policyOffsetDays = policyOffsetDays,
    completionMode = completionMode,
    profileId = null,
    remindersEnabled = true,
    status = status,
    postponedDueOn = postponedDueOn,
    createdAt = dayMillis(createdOn),
    updatedAt = dayMillis(createdOn),
    ruleChangedAt = dayMillis(createdOn),
    providers = listOf(ScheduleProviderRow("LOCAL", enabled = true)),
)

/** A meter definition on [assetId] — a monotonic counter with a unit the meter line quotes. */
fun meterDefinitionOf(
    id: String,
    assetId: String,
    key: String = "engine_hours",
    label: String = "Engine hours",
    unit: String = "h",
    createdOn: String = "2026-01-01",
): MeasurementDefinition = MeasurementDefinition(
    id = DefinitionId(id),
    assetId = AssetId(assetId),
    key = key,
    label = label,
    unit = unit,
    valueType = ValueType.NUMBER,
    decimals = 0,
    rangeLow = null,
    rangeHigh = null,
    isMeter = true,
    sortOrder = 0,
    archivedAt = null,
    createdAt = dayMillis(createdOn),
    updatedAt = dayMillis(createdOn),
)

/** A standalone "log hours" reading: the meter's current value, with no schedule behind it. */
fun readingOf(
    id: String,
    assetId: String,
    definitionId: String,
    value: Double,
    unit: String = "h",
    occurredOn: String = "2026-04-01",
): AssetEvent = AssetEvent(
    id = EventId(id),
    assetId = AssetId(assetId),
    kind = EventKind.MEASUREMENT,
    title = "Log hours",
    profileId = null,
    occurredOn = occurredOn,
    occurredTime = null,
    tzId = "UTC",
    notes = "",
    source = EventSource.MANUAL,
    sourceRef = null,
    createdAt = dayMillis(occurredOn),
    updatedAt = dayMillis(occurredOn),
    measurements = listOf(
        Measurement(
            id = "m-$id",
            definitionId = DefinitionId(definitionId),
            valueNum = value,
            valueText = null,
            unit = unit,
            sortOrder = 0,
        ),
    ),
    consumables = emptyList(),
)

/**
 * A group and its membership windows, given as `(assetId, addedOn, removedOn)` triples: every
 * boundary in these tests is the day the owner added or removed a member, and the stored instant is
 * that day at midnight UTC.
 */
fun groupOf(
    id: String,
    name: String = "North run",
    archivedAt: Long? = null,
    createdOn: String = "2026-01-01",
    members: List<Triple<String, String, String?>> = emptyList(),
): MaintenanceGroup = MaintenanceGroup(
    id = GroupId(id),
    name = name,
    description = "",
    archivedAt = archivedAt,
    createdAt = dayMillis(createdOn),
    updatedAt = dayMillis(createdOn),
    members = members.mapIndexed { index, (assetId, addedOn, removedOn) ->
        GroupMember(
            id = "$id-m${index + 1}",
            assetId = AssetId(assetId),
            sortOrder = index,
            addedAt = dayMillis(addedOn),
            removedAt = removedOn?.let(::dayMillis),
        )
    },
)
