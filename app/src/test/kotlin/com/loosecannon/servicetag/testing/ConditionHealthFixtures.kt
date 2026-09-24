package com.loosecannon.servicetag.testing

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonMode

/**
 * Asset, condition, health-subject and replacement builders for `:app`'s 1.4 read-model tests, in
 * the shape of [scheduleOf]: every field a read model looks at is a named argument and the rest is
 * defaulted. Rows go into the tables through the production repositories, so a read model under
 * test reads exactly what the app would; derived state still comes only from the real recompute.
 *
 * The names are the spec's fictional fixtures (F1–F5: a UPS, a battery pack, a generator, a
 * snowblower and a mower, a hot tub) and nothing else.
 */

/** An Asset row. CALENDAR takes its two `MM-DD` bounds; a break takes both of its own. */
fun assetRow(
    id: String,
    name: String = id,
    parent: String? = null,
    status: AssetStatus = AssetStatus.ACTIVE,
    retiredOn: String? = null,
    seasonMode: SeasonMode = SeasonMode.YEAR_ROUND,
    seasonStart: String? = null,
    seasonEnd: String? = null,
    breakStart: String? = null,
    breakEnd: String? = null,
    aggregation: HealthAggregation = HealthAggregation.WORST,
    primary: String? = null,
): Asset = Asset(
    id = AssetId(id),
    name = name,
    status = status,
    retiredOn = retiredOn,
    parentAssetId = parent?.let(::AssetId),
    seasonMode = seasonMode,
    seasonStartMmdd = seasonStart,
    seasonEndMmdd = seasonEnd,
    blackoutStartMmdd = breakStart,
    blackoutEndMmdd = breakEnd,
    healthAggregation = aggregation,
    healthPrimarySubjectId = primary?.let(::HealthSubjectId),
    createdAt = dayMillis("2025-01-01"),
    updatedAt = dayMillis("2025-01-01"),
)

/** One condition fact. [createdAt] defaults to the day it happened, which keeps the order obvious. */
fun conditionRow(
    id: String,
    assetId: String,
    condition: OperationalCondition,
    occurredOn: String,
    reason: String = "",
    occurredTime: String? = null,
    eventId: String? = null,
    tzId: String = "UTC",
    createdAt: Long = dayMillis(occurredOn),
): AssetCondition = AssetCondition(
    id = id,
    assetId = AssetId(assetId),
    condition = condition,
    occurredOn = occurredOn,
    occurredTime = occurredTime,
    tzId = tzId,
    reason = reason,
    eventId = eventId?.let(::EventId),
    createdAt = createdAt,
)

/**
 * One health subject. The default thresholds are 0 / 40 / 75, so a subject is NOMINAL below 40
 * counted days, WARNING from 40 and CRITICAL from 75 (spec §6.3: the bands start exactly at `t2`
 * and `t3`).
 */
fun subjectRow(
    id: String,
    assetId: String,
    name: String = id,
    driver: HealthDriver = HealthDriver.AGE,
    kind: HealthSubjectKind = HealthSubjectKind.PART,
    scheduleId: String? = null,
    baselineProfileId: String? = null,
    nominalUntilDays: Int = 0,
    warningFromDays: Int = 40,
    criticalFromDays: Int = 75,
    weight: Int = 1,
    sortOrder: Int = 0,
    archivedAt: Long? = null,
): HealthSubject = HealthSubject(
    id = HealthSubjectId(id),
    assetId = AssetId(assetId),
    name = name,
    kind = kind,
    driver = driver,
    scheduleId = scheduleId?.let(::ScheduleId),
    baselineProfileId = baselineProfileId?.let(::ProfileId),
    nominalUntilDays = nominalUntilDays,
    warningFromDays = warningFromDays,
    criticalFromDays = criticalFromDays,
    weight = weight,
    sortOrder = sortOrder,
    archivedAt = archivedAt,
    createdAt = dayMillis("2025-01-01"),
    updatedAt = dayMillis("2025-01-01"),
)

/** A REPLACEMENT event: the baseline an AGE subject counts from (spec §6.4). */
fun replacementOf(id: String, assetId: String, occurredOn: String): AssetEvent = AssetEvent(
    id = EventId(id),
    assetId = AssetId(assetId),
    kind = EventKind.REPLACEMENT,
    title = "Battery replaced",
    profileId = null,
    occurredOn = occurredOn,
    occurredTime = null,
    tzId = "UTC",
    notes = "",
    source = EventSource.MANUAL,
    sourceRef = null,
    createdAt = dayMillis(occurredOn),
    updatedAt = dayMillis(occurredOn),
    measurements = emptyList(),
    consumables = emptyList(),
)
