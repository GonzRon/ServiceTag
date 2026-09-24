package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.AssetConditionDto
import com.loosecannon.servicetag.core.backup.AssetDto
import com.loosecannon.servicetag.core.backup.HealthSubjectDto
import com.loosecannon.servicetag.core.backup.SeasonActivationDto
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.health.SubjectValue
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.usecase.ActivationCommand
import com.loosecannon.servicetag.core.usecase.BreakCommand
import com.loosecannon.servicetag.core.usecase.ConditionCommand
import com.loosecannon.servicetag.core.usecase.HealthPolicyCommand
import com.loosecannon.servicetag.core.usecase.HealthSubjectCommand
import com.loosecannon.servicetag.core.usecase.SeasonModeCommand
import com.loosecannon.servicetag.core.usecase.SeasonView
import com.loosecannon.servicetag.ui.health.AssetHealthView
import com.loosecannon.servicetag.ui.maintenance.AttentionItem
import kotlinx.serialization.Serializable

/*
 * The 1.4 shapes on the wire (spec §9.1; master plan §11.1–§11.2).
 *
 * The two rules `ApiDtos.kt` states hold here unchanged. **Rows reuse the backup format's own DTOs**
 * — `SeasonActivationDto`, `AssetConditionDto`, `HealthSubjectDto`, `AssetDto` — so an activation, a
 * condition or a subject read here and the same row inside `data.json` are the same JSON object.
 * **Requests are declared here and nowhere else**, with plain `String`/`Int` fields, each converting
 * to its domain command in exactly one place; an unknown enum name is the shipped 400 naming the
 * field. `docs/api/command-shapes.json` pins every request's key set.
 *
 * Three shapes are neither: the season, the health and the attention responses are **derived read
 * projections**, computed for the day in their `computedForOn` (the attention list for today) and
 * stored nowhere — no archive carries one, no command accepts one, and no health value exists in any
 * table (inv. 111). Every field of them is present, `null` when absent.
 */

// --- requests -----------------------------------------------------------------------------------

/**
 * `POST /v1/assets/{id}/season-mode`: how the asset's season is decided. The window is `MM-DD` and
 * belongs to CALENDAR alone; [manualPhase] (`IN_SEASON`｜`OUT_OF_SEASON`) is an action parameter,
 * taken exactly on a switch into MANUAL (spec §3.4).
 */
@Serializable
internal data class SeasonModeRequest(
    val seasonMode: String,
    val seasonStartMmdd: String? = null,
    val seasonEndMmdd: String? = null,
    val manualPhase: String? = null,
)

internal fun SeasonModeRequest.toCommand() = SeasonModeCommand(
    seasonMode = enumOr400<SeasonMode>(seasonMode, "seasonMode"),
    seasonStartMmdd = seasonStartMmdd,
    seasonEndMmdd = seasonEndMmdd,
    manualPhase = manualPhase?.let { enumOr400<SeasonPhase>(it, "manualPhase") },
)

/**
 * `POST /v1/assets/{id}/maintenance-break`: both bounds, or both `null` to clear the break. Both
 * keys are required, so a body that forgot one is a 400 rather than a break silently cleared.
 */
@Serializable
internal data class BreakRequest(
    val blackoutStartMmdd: String?,
    val blackoutEndMmdd: String?,
)

internal fun BreakRequest.toCommand() = BreakCommand(blackoutStartMmdd, blackoutEndMmdd)

/** `POST /v1/assets/{id}/season`: one manual `START` or `END`. [occurredOn] omitted is today. */
@Serializable
internal data class ActivationRequest(
    val action: String,
    val occurredOn: String? = null,
    val eventId: String? = null,
)

internal fun ActivationRequest.toCommand() = ActivationCommand(
    action = enumOr400<SeasonAction>(action, "action"),
    occurredOn = occurredOn,
    eventId = eventId?.let(::EventId),
)

/**
 * `POST /v1/assets/{id}/conditions`: one immutable condition fact. [occurredOn] omitted is today,
 * [occurredTime] is `HH:MM` or absent, and [reason] may be empty.
 */
@Serializable
internal data class ConditionRequest(
    val condition: String,
    val occurredOn: String? = null,
    val occurredTime: String? = null,
    val tzId: String,
    val reason: String = "",
    val eventId: String? = null,
)

internal fun ConditionRequest.toCommand() = ConditionCommand(
    condition = enumOr400<OperationalCondition>(condition, "condition"),
    occurredOn = occurredOn,
    occurredTime = occurredTime,
    tzId = tzId,
    reason = reason,
    eventId = eventId?.let(::EventId),
)

/** `POST /v1/assets/{id}/health-policy`: [healthPrimarySubjectId] with `TRACK_ONE` and never else. */
@Serializable
internal data class HealthPolicyRequest(
    val healthAggregation: String,
    val healthPrimarySubjectId: String? = null,
)

internal fun HealthPolicyRequest.toCommand() = HealthPolicyCommand(
    healthAggregation = enumOr400<HealthAggregation>(healthAggregation, "healthAggregation"),
    healthPrimarySubjectId = healthPrimarySubjectId?.let(::HealthSubjectId),
)

/**
 * `POST /v1/health-subjects`: the subject command, with the asset it belongs to. The three
 * thresholds have **no default** (inv. 121): one left out is 422 `HEALTH_THRESHOLDS_INVALID`, never
 * a value filled in. [sortOrder] omitted appends to the asset's list.
 */
@Serializable
internal data class HealthSubjectCreateRequest(
    val assetId: String,
    val name: String,
    val kind: String,
    val driver: String,
    val scheduleId: String? = null,
    val baselineProfileId: String? = null,
    val nominalUntilDays: Int? = null,
    val warningFromDays: Int? = null,
    val criticalFromDays: Int? = null,
    val weight: Int = 1,
    val sortOrder: Int? = null,
)

/**
 * `PATCH /v1/health-subjects/{id}`: the same command **without `assetId`** — a subject never changes
 * asset (inv. 120), so naming one is the 400 an unknown field is. A full replace, except that
 * [sortOrder] omitted keeps the subject's place.
 */
@Serializable
internal data class HealthSubjectUpdateRequest(
    val name: String,
    val kind: String,
    val driver: String,
    val scheduleId: String? = null,
    val baselineProfileId: String? = null,
    val nominalUntilDays: Int? = null,
    val warningFromDays: Int? = null,
    val criticalFromDays: Int? = null,
    val weight: Int = 1,
    val sortOrder: Int? = null,
)

internal fun HealthSubjectCreateRequest.toCommand() = subjectCommand(
    name, kind, driver, scheduleId, baselineProfileId,
    nominalUntilDays, warningFromDays, criticalFromDays, weight, sortOrder,
)

internal fun HealthSubjectUpdateRequest.toCommand() = subjectCommand(
    name, kind, driver, scheduleId, baselineProfileId,
    nominalUntilDays, warningFromDays, criticalFromDays, weight, sortOrder,
)

private fun subjectCommand(
    name: String,
    kind: String,
    driver: String,
    scheduleId: String?,
    baselineProfileId: String?,
    nominalUntilDays: Int?,
    warningFromDays: Int?,
    criticalFromDays: Int?,
    weight: Int,
    sortOrder: Int?,
) = HealthSubjectCommand(
    name = name,
    kind = enumOr400<HealthSubjectKind>(kind, "kind"),
    driver = enumOr400<HealthDriver>(driver, "driver"),
    scheduleId = scheduleId?.let(::ScheduleId),
    baselineProfileId = baselineProfileId?.let(::ProfileId),
    nominalUntilDays = nominalUntilDays,
    warningFromDays = warningFromDays,
    criticalFromDays = criticalFromDays,
    weight = weight,
    sortOrder = sortOrder,
)

// --- the season -----------------------------------------------------------------------------------

/**
 * `GET /v1/assets/{id}/season`, and the `season` beside every season write: the asset's mode, its
 * window and break, and what they mean on [computedForOn] — the phase, the next boundary a CALENDAR
 * season will cross (null for YEAR_ROUND and MANUAL, whose next boundary is never predicted), and
 * whether today is inside the break. [activations] is the asset's whole manual history, oldest first
 * by `(occurredOn, createdAt, id)`; the phase reads it only in MANUAL.
 */
@Serializable
internal data class SeasonResponse(
    val seasonMode: String,
    val seasonStartMmdd: String?,
    val seasonEndMmdd: String?,
    val seasonPhase: String,
    val nextBoundaryOn: String?,
    val blackoutStartMmdd: String?,
    val blackoutEndMmdd: String?,
    val inBreak: Boolean,
    val activations: List<SeasonActivationDto>,
    val computedForOn: String,
)

internal fun SeasonView.toResponse() = SeasonResponse(
    seasonMode = seasonMode.name,
    seasonStartMmdd = seasonStartMmdd,
    seasonEndMmdd = seasonEndMmdd,
    seasonPhase = phase.name,
    nextBoundaryOn = nextBoundaryOn?.toString(),
    blackoutStartMmdd = blackoutStartMmdd,
    blackoutEndMmdd = blackoutEndMmdd,
    inBreak = inBreak,
    activations = activations.map { it.toDto() },
    computedForOn = computedForOn.toString(),
)

/** `POST /v1/assets/{id}/season` (201): the one row it wrote, and the season it now makes. */
@Serializable
internal data class ActivationResponse(val activation: SeasonActivationDto, val season: SeasonResponse)

/** `POST …/season-mode` and `POST …/maintenance-break`: the asset as stored, and its season now. */
@Serializable
internal data class AssetSeasonResponse(val asset: AssetDto, val season: SeasonResponse)

// --- condition ------------------------------------------------------------------------------------

/**
 * `GET /v1/assets/{id}/conditions`: the asset's whole history, **oldest first by the ordering key**
 * `(occurredOn, occurredTime with none first, createdAt, id)`, and [current] — the last of them, or
 * null when nothing has been recorded (which is not OPERATIONAL: nothing is inferred).
 */
@Serializable
internal data class ConditionsResponse(val conditions: List<AssetConditionDto>, val current: AssetConditionDto?)

/**
 * `POST /v1/assets/{id}/conditions` (201): the row written, and the asset's [current] condition
 * after it — which is **not** the new row when the new row was backdated before the latest one.
 */
@Serializable
internal data class ConditionResponse(val condition: AssetConditionDto, val current: AssetConditionDto?)

// --- health ---------------------------------------------------------------------------------------

/** The asset's current condition as the health response carries it: [since] starts the latest run. */
@Serializable
internal data class HealthConditionDto(
    val condition: String,
    val since: String,
    val reason: String,
    val occurredOn: String,
    val eventId: String?,
)

/**
 * The asset's combined health, or null when nothing contributes (NOT TRACKED — never a 100).
 * [trackedDays] is always null here: an aggregate has no day count of its own, and its subjects
 * carry theirs. [fallback] is true when TRACK_ONE's primary is missing or archived and WORST was
 * taken instead.
 */
@Serializable
internal data class HealthAggregateDto(
    val score: Int,
    val band: String,
    val trackedDays: Long?,
    val fallback: Boolean,
)

/**
 * One live subject: a [score] in a [band] with the [trackedDays] behind it, or [notTracked] with its
 * reason — `NO_REPLACEMENT`, `PROFILE_REMOVED`, `OUT_OF_SEASON`, `SCHEDULE_PAUSED`, `LINK_INVALID`,
 * `SCHEDULE_ARCHIVED`, or `UNSCORABLE`, which no command or restore can reach (a subject whose own
 * configuration is malformed, screened out rather than scored). Exactly one side is set.
 */
@Serializable
internal data class HealthSubjectValueDto(
    val subjectId: String,
    val name: String,
    val kind: String,
    val driver: String,
    val scheduleId: String?,
    val score: Int?,
    val band: String?,
    val trackedDays: Long?,
    val notTracked: String?,
)

/** A subject scoring CRITICAL, listed whatever the aggregation, so nothing hides behind an average. */
@Serializable
internal data class HealthCriticalDto(val subjectId: String, val name: String, val score: Int)

/** A DOWN or DEGRADED in-service component, at any depth under the asset. */
@Serializable
internal data class HealthComponentDto(
    val assetId: String,
    val name: String,
    val condition: String,
    val reason: String,
    val occurredOn: String,
)

/**
 * `GET /v1/assets/{id}/health`: the health of one asset for [computedForOn], computed at read time
 * and stored nowhere (inv. 111). It always carries the asset's current [condition], every CRITICAL
 * subject in [critical] and every DOWN or DEGRADED in-service component in [components], so neither
 * an aggregate nor a band can hide them (inv. 119).
 */
@Serializable
internal data class HealthResponse(
    val assetId: String,
    val computedForOn: String,
    val condition: HealthConditionDto?,
    val aggregation: String,
    val aggregate: HealthAggregateDto?,
    val subjects: List<HealthSubjectValueDto>,
    val critical: List<HealthCriticalDto>,
    val components: List<HealthComponentDto>,
)

internal fun AssetHealthView.toResponse(): HealthResponse = HealthResponse(
    assetId = assetId.value,
    computedForOn = computedForOn.toString(),
    condition = condition?.let {
        HealthConditionDto(
            condition = it.condition.name,
            since = it.since.toString(),
            reason = it.reason,
            occurredOn = it.occurredOn.toString(),
            eventId = it.eventId?.value,
        )
    },
    aggregation = aggregation.name,
    aggregate = result.aggregate?.let {
        HealthAggregateDto(score = it.score, band = it.band.name, trackedDays = it.trackedDays, fallback = result.fallback)
    },
    subjects = result.subjects.map { health ->
        val scored = health.value as? SubjectValue.Scored
        HealthSubjectValueDto(
            subjectId = health.subject.id.value,
            name = health.subject.name,
            kind = health.subject.kind.name,
            driver = health.subject.driver.name,
            scheduleId = health.subject.scheduleId?.value,
            score = scored?.score,
            band = scored?.band?.name,
            trackedDays = scored?.trackedDays,
            notTracked = (health.value as? SubjectValue.NotTracked)?.reason?.name,
        )
    },
    critical = result.critical.mapNotNull { health ->
        (health.value as? SubjectValue.Scored)?.let { HealthCriticalDto(health.subject.id.value, health.subject.name, it.score) }
    },
    components = components.map {
        HealthComponentDto(it.assetId.value, it.name, it.condition.name, it.reason, it.occurredOn.toString())
    },
)

// --- health subjects ------------------------------------------------------------------------------

/** `GET /v1/assets/{id}/health-subjects`: every subject of the asset, archived included. */
@Serializable
internal data class SubjectListResponse(val subjects: List<HealthSubjectDto>)

@Serializable
internal data class SubjectResponse(val subject: HealthSubjectDto)

// --- attention ------------------------------------------------------------------------------------

/**
 * One asset-level row no schedule stands behind (spec §9.1; master plan §11.2): a DOWN or DEGRADED
 * in-service asset or component (`kind` `CONDITION`), or an AGE health subject scoring CRITICAL or
 * WARNING (`kind` `HEALTH`). A CONDITION row carries [condition], [reason] and [occurredOn] and null
 * health fields; a HEALTH row the reverse. [parentAssetId] names a component's parent and is null on
 * a top-level asset. [rank] is dense from 0 over DOWN, DEGRADED, CRITICAL, WARNING, then asset name
 * and id. Every field is present, `null` when absent.
 */
@Serializable
internal data class AttentionItemDto(
    val kind: String,
    val section: String,
    val assetId: String,
    val parentAssetId: String?,
    val condition: String?,
    val reason: String?,
    val occurredOn: String?,
    val healthSubjectId: String?,
    val subjectName: String?,
    val band: String?,
    val score: Int?,
    val rank: Int,
)

internal fun AttentionItem.toDto() = AttentionItemDto(
    kind = kind.name,
    section = section.name,
    assetId = assetId.value,
    parentAssetId = parentAssetId?.value,
    condition = condition?.name,
    reason = reason,
    occurredOn = occurredOn,
    healthSubjectId = healthSubjectId?.value,
    subjectName = subjectName,
    band = band?.name,
    score = score,
    rank = rank,
)

/** `GET /v1/attention`: the asset-level rows, in [AttentionItemDto.rank] order. */
@Serializable
internal data class AttentionResponse(val items: List<AttentionItemDto>)
