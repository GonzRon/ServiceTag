package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.season.Readiness
import com.loosecannon.servicetag.core.season.ReadinessBoundary
import kotlinx.serialization.Serializable

/**
 * #114 (C29) — `GET /v1/assets/{id}/season-readiness`: C9's answer for one asset, today, as eleven flat keys. A read
 * projection like the season and health responses: derived when it is read, stored nowhere, carried by no archive and
 * taken by no command. No key is a value to publish and none can be sent back: readiness is derived only (G17).
 *
 * - [readyForSeason] and [seasonServiceDoneOn]: whether a qualifying service of the covering role schedule is dated
 *   on or after [cutoffOn], and the latest such day (`YYYY-MM-DD`); null whenever it is not ready.
 * - [reason]: null when ready, else a `NotReadyReason` name.
 * - [roleScheduleId], [roleTarget] (`ASSET` or `GROUP`) and [roleSinceOn] (its commissioning boundary): the role
 *   schedule the answer is about — for `SCHEDULE_ARCHIVED` the archived one — or null when no single role decides it
 *   (`NO_ROLE`, `TWO_ROLES`).
 * - [cutoffOn] and [boundarySource] (F): two fields — services dated on or after the cutoff count, and the source
 *   says who set it; null when the answer stops before a boundary (no role, two, or a role not allowed or archived).
 * - [boundaryCheckedAt]: the epoch millis of the Home Assistant read, for `HA_SEASON_ENDED` only.
 * - [boundaryUnverifiedSince]: the epoch millis since which the asset's Home Assistant boundary is unverified,
 *   whatever won the cutoff (D).
 */
@Serializable
internal data class SeasonReadinessResponse(
    val assetId: String,
    val readyForSeason: Boolean,
    val seasonServiceDoneOn: String?,
    val reason: String?,
    val roleScheduleId: String?,
    val roleTarget: String?,
    val roleSinceOn: String?,
    val cutoffOn: String?,
    val boundarySource: String?,
    val boundaryCheckedAt: Long?,
    val boundaryUnverifiedSince: Long?,
)

/** What a role schedule targets, on the wire. */
internal enum class RoleTarget { ASSET, GROUP }

/**
 * This answer for [assetId] on the wire. [schedules] are the rows the answer was computed from, so the role schedule
 * it names is read from the same snapshot; each key is an enum's `name`, never a `toString`.
 */
internal fun Readiness.toResponse(assetId: AssetId, schedules: Collection<MaintenanceSchedule>): SeasonReadinessResponse {
    val (serviceOn, scheduleId, boundary, reason) = when (this) {
        is Readiness.Ready -> Parts(serviceOn.toString(), scheduleId, boundary, null)
        is Readiness.NotReady -> Parts(null, scheduleId, boundary, reason.name)
    }
    val role = scheduleId?.let { id -> schedules.firstOrNull { it.id == id } }
    return SeasonReadinessResponse(
        assetId = assetId.value,
        readyForSeason = serviceOn != null,
        seasonServiceDoneOn = serviceOn,
        reason = reason,
        roleScheduleId = scheduleId?.value,
        roleTarget = role?.target?.let(::roleTargetOf)?.name,
        roleSinceOn = role?.roleSinceOn,
        cutoffOn = boundary?.cutoffOn?.toString(),
        boundarySource = boundary?.source?.name,
        boundaryCheckedAt = boundary?.checkedAt,
        boundaryUnverifiedSince = boundary?.unverifiedSince,
    )
}

private data class Parts(
    val serviceOn: String?,
    val scheduleId: ScheduleId?,
    val boundary: ReadinessBoundary?,
    val reason: String?,
)

private fun roleTargetOf(target: ScheduleTarget): RoleTarget = when (target) {
    is ScheduleTarget.AssetTarget -> RoleTarget.ASSET
    is ScheduleTarget.GroupTarget -> RoleTarget.GROUP
}
