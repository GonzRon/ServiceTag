package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.GroupMember
import com.loosecannon.servicetag.core.model.MaintenanceGroup
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

/**
 * Midnight **UTC** of [date].
 *
 * It is the reason every `rebuild` in this suite is handed `ZoneOffset.UTC` explicitly: UTC is the
 * zone in which these instants carry the date they are written as, so it is the zone in which a
 * test's `createdOn` and `updatedOn` mean what they say. The engine reads the pin's floor in
 * whatever zone its caller supplies — the owner's, in the app — so a suite that left it unsaid
 * would be asserting against whatever zone the machine happens to be in.
 */
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

/**
 * [createdAt] is separate from [occurredOn] on purpose: a **backdated** completion is one whose
 * `occurred_on` lies in the past and whose `created_at` is now, and the occurrence's open instant
 * reads the second and never the first. A fixture that tied them together could not express the
 * case invariant 73 exists for.
 */
fun completionOf(
    id: String,
    occurredOn: String,
    occurrenceOn: String?,
    assetId: String = "a1",
    scheduleId: String = "s1",
    meter: Pair<String, Double>? = null,
    detailsPending: Boolean = false,
    createdAt: Long = dayMillis(occurredOn),
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
    createdAt = createdAt,
    updatedAt = createdAt,
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

/**
 * A group and its membership windows. [members] are `(assetId, addedOn, removedOn)` triples given as
 * **dates**, because every window boundary in these tests is "the day the owner added or removed
 * it"; the stored instants are those dates at midnight UTC, the same conversion [dayMillis] makes
 * everywhere else here.
 */
fun groupOf(
    id: String = "g1",
    name: String = "North run",
    description: String = "",
    archivedAt: Long? = null,
    createdOn: String = "2026-01-01",
    updatedOn: String = createdOn,
    members: List<Triple<String, String, String?>> = emptyList(),
): MaintenanceGroup = MaintenanceGroup(
    id = GroupId(id),
    name = name,
    description = description,
    archivedAt = archivedAt,
    createdAt = dayMillis(createdOn),
    updatedAt = dayMillis(updatedOn),
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
