package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.Measurement
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.model.TimeBasis
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Fixture builders for the scheduling tests: a schedule, a completion, a standalone reading and a
 * closure, each with every field the engine reads exposed as a named argument and everything else
 * defaulted. Dates are the ISO strings the domain stores; the two instants a schedule carries are
 * given as dates too, because the D-27 pin's floor is `updated_at`'s date and a test that wants to
 * move the floor wants to say which day, not which millisecond.
 */

/** Midnight UTC of [date] — the inverse of the conversion the pin's floor uses. */
fun dayMillis(date: String): Long =
    LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun scheduleOf(
    id: String = "s1",
    assetId: String? = "a1",
    groupId: String? = null,
    title: String = "Quarterly inspection",
    timeInterval: Int? = null,
    timeUnit: RecurrenceUnit? = null,
    timeBasis: TimeBasis = TimeBasis.FIXED,
    anchorOn: String? = null,
    leadDays: Int = 14,
    meterDefinitionId: String? = null,
    meterInterval: Double? = null,
    anchorMeter: Double? = null,
    meterLead: Double? = null,
    seasonBehavior: SeasonBehavior = SeasonBehavior.IGNORE,
    completionMode: CompletionMode = CompletionMode.QUICK,
    profileId: String? = null,
    status: ScheduleStatus = ScheduleStatus.ACTIVE,
    postponedDueOn: String? = null,
    createdOn: String = "2026-01-01",
    updatedOn: String = createdOn,
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
    seasonBehavior = seasonBehavior,
    seasonReentry = null,
    seasonReentryOffsetDays = null,
    completionMode = completionMode,
    profileId = profileId?.let(::ProfileId),
    remindersEnabled = true,
    status = status,
    postponedDueOn = postponedDueOn,
    createdAt = dayMillis(createdOn),
    updatedAt = dayMillis(updatedOn),
    providers = listOf(ScheduleProviderRow("LOCAL", enabled = true)),
)

fun completionOf(
    id: String,
    occurredOn: String,
    occurrenceOn: String?,
    assetId: String = "a1",
    scheduleId: String = "s1",
    meter: Pair<String, Double>? = null,
    detailsPending: Boolean = false,
): AssetEvent = AssetEvent(
    id = EventId(id),
    assetId = AssetId(assetId),
    kind = EventKind.MAINTENANCE,
    title = "Done",
    profileId = null,
    occurredOn = occurredOn,
    occurredTime = null,
    tzId = "UTC",
    notes = "",
    source = EventSource.SCHEDULE_QUICK_COMPLETE,
    sourceRef = null,
    createdAt = dayMillis(occurredOn),
    updatedAt = dayMillis(occurredOn),
    measurements = meter?.let { (definitionId, value) -> listOf(measurementOf(definitionId, value)) }
        .orEmpty(),
    consumables = emptyList(),
    scheduleId = ScheduleId(scheduleId),
    occurrenceOn = occurrenceOn,
    detailsPending = detailsPending,
)

/** A standalone "log hours" event: a reading of the meter with no schedule link at all. */
fun readingOf(
    id: String,
    occurredOn: String,
    definitionId: String,
    value: Double,
    assetId: String = "a1",
    createdAt: Long = dayMillis(occurredOn),
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
    createdAt = createdAt,
    updatedAt = createdAt,
    measurements = listOf(measurementOf(definitionId, value)),
    consumables = emptyList(),
)

fun measurementOf(definitionId: String, value: Double): Measurement = Measurement(
    id = "m-$definitionId-$value",
    definitionId = DefinitionId(definitionId),
    valueNum = value,
    valueText = null,
    unit = "h",
    sortOrder = 0,
)

fun closureOf(
    id: String,
    occurrenceOn: String,
    closedOn: String,
    scheduleId: String = "s1",
    createdAt: Long = dayMillis(closedOn),
): OccurrenceClosure = OccurrenceClosure(
    id = id,
    scheduleId = ScheduleId(scheduleId),
    occurrenceOn = occurrenceOn,
    closedOn = closedOn,
    createdAt = createdAt,
)
