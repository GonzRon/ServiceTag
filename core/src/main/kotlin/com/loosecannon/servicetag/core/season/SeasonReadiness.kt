package com.loosecannon.servicetag.core.season

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.schedule.SeasonContext
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** #114 (C9): whether an asset is ready for its season, and why not when it is not. */
sealed interface Readiness {
    /** The latest qualifying service of [scheduleId] (O-3), dated on or after [boundary]'s cutoff. */
    data class Ready(val serviceOn: LocalDate, val scheduleId: ScheduleId, val boundary: ReadinessBoundary) : Readiness

    /** [scheduleId] and [boundary] are null when the reason is reached before either is known. */
    data class NotReady(val reason: NotReadyReason, val scheduleId: ScheduleId?, val boundary: ReadinessBoundary?) : Readiness
}

/** Where a role schedule's services start counting from, and who said so (C9, F). */
data class ReadinessBoundary(
    /** Services dated on or after it count. */
    val cutoffOn: LocalDate,
    /** Where [cutoffOn] came from. */
    val source: BoundarySource,
    /** [BoundarySource.HA_SEASON_ENDED] only: the time of the read that gave the date. */
    val checkedAt: Long? = null,
    /** Set whenever the Home Assistant boundary is [HaBoundary.Unverified], whatever won. */
    val unverifiedSince: Long? = null,
)

enum class BoundarySource { CALENDAR_WINDOW, PHONE_SEASON_END, HA_SEASON_ENDED, HA_UNVERIFIED, COMMISSIONING }

/**
 * Home Assistant's own season-ended date for a `MANUAL` asset (C9a), as its caller judged it: built by the pure
 * freshness rule outside readiness, so readiness never reads a clock. A `CALENDAR` asset ignores it.
 */
sealed interface HaBoundary {
    /** No boundary helper decides this asset: the phone's END does. */
    data object NotUsed : HaBoundary

    /** HA's date, read at the binding's current revision and recently enough to stand. */
    data class Fresh(val on: LocalDate, val readAt: Long) : HaBoundary

    /** HA decides this asset but its date cannot be trusted now: the later of [cachedOn] and the phone's END. */
    data class Unverified(val cachedOn: LocalDate?, val since: Long) : HaBoundary
}

enum class NotReadyReason {
    NO_ROLE, ROLE_NOT_ALLOWED, TWO_ROLES, SCHEDULE_ARCHIVED, NOT_DONE_SINCE_SEASON_END,
    NOT_DONE_SINCE_COMMISSIONING, DETAILS_PENDING, FUTURE_DATED, INHERITED_SERVICE_ONLY,
    /** O-16 (rev 6): the only services in range were written before the day they name. */
    RECORDED_IN_ADVANCE,
}

/**
 * #114 (C9): whether [asset] is ready for its season — one pure function, no clock (`today` is a parameter and
 * [haBoundary]'s freshness was decided by its caller, C9a).
 *
 * **Coverage.** The role schedules covering [asset] are the `SEASON_START` schedules that are not ARCHIVED (PAUSED
 * is live) and either target it or target a non-archived group of which it is an open member. Then, the first that
 * holds answers: role schedules target it but none covers (an archived schedule, or an archived group) →
 * `SCHEDULE_ARCHIVED`; none → `NO_ROLE`; two or more → `TWO_ROLES`; a `YEAR_ROUND` asset, or a child with no child
 * row of its own (O-5: any child row counts) → `ROLE_NOT_ALLOWED`.
 *
 * **The cutoff** is the later of the role's `roleSinceOn` and the asset's own season end on or before [today]:
 * a `CALENDAR` asset's window (HA's date never overrides it), else HA's date while [HaBoundary.Fresh], the later
 * of HA's cached date and the phone's END while [HaBoundary.Unverified], and the phone's END while
 * [HaBoundary.NotUsed]. `COMMISSIONING` when `roleSinceOn` is later or there is no season end; a tie reports the
 * season end's source.
 *
 * **Qualifying** is a completion of that schedule by this asset — never another schedule's, never a parent's or a
 * child's, and under a group role each member reads only its own events — that is not `detailsPending`, not
 * `imported` (a Transfer Pack brought it), dated in `[cutoff, today]`, and written (its `updatedAt` in its own
 * `tzId`, else in [zone]) on or after the day it names (O-16). The latest qualifying date answers (O-3).
 *
 * Never read: condition, health, today's phase, reminders, closures, `occurrenceOn`, `createdAt`, and any label
 * an asset or a schedule carries (G22). A malformed `occurredOn` is skipped and an unresolvable `tzId` falls back to
 * [zone]; nothing here throws on stored data.
 */
fun seasonReadiness(
    asset: Asset,
    assets: Collection<Asset>,
    schedules: Collection<MaintenanceSchedule>,
    groups: Collection<MaintenanceGroup>,
    events: Collection<AssetEvent>,
    activations: List<SeasonActivation>,
    today: LocalDate,
    zone: ZoneId,
    haBoundary: HaBoundary = HaBoundary.NotUsed,
): Readiness {
    // Open membership is the stored window still running (`removedAt == null`, GroupCommands' openMembers).
    val memberOf = groups.filter { group -> group.members.any { it.assetId == asset.id && it.removedAt == null } }
        .associateBy { it.id }
    val targeting = schedules.filter { it.role == ScheduleRole.SEASON_START && targets(it.target, asset, memberOf) }
    val covering = targeting.filter { schedule ->
        schedule.status != ScheduleStatus.ARCHIVED && isLiveTarget(schedule.target, memberOf)
    }
    if (covering.isEmpty() && targeting.isNotEmpty()) {
        return Readiness.NotReady(NotReadyReason.SCHEDULE_ARCHIVED, targeting.minBy { it.id.value }.id, null)
    }
    val s = covering.singleOrNull()
        ?: return Readiness.NotReady(if (covering.isEmpty()) NotReadyReason.NO_ROLE else NotReadyReason.TWO_ROLES, null, null)
    val leafChild = asset.parentAssetId != null && assets.none { it.parentAssetId == asset.id }
    if (asset.seasonMode == SeasonMode.YEAR_ROUND || leafChild) {
        return Readiness.NotReady(NotReadyReason.ROLE_NOT_ALLOWED, s.id, null)
    }
    // R1 says a role always carries its boundary; a row that breaks it reads as no role, defensively.
    val roleSince = s.roleSinceOn?.let(::dayOrNull) ?: return Readiness.NotReady(NotReadyReason.NO_ROLE, null, null)

    val boundary = boundaryOf(asset, activations.filter { it.assetId == asset.id }, roleSince, today, haBoundary)
    val cutoff = boundary.cutoffOn

    val own = events.filter { it.assetId == asset.id && it.scheduleId == s.id }
        .mapNotNull { event -> dayOrNull(event.occurredOn)?.let { Dated(event, it) } }
    val counted = own.filter { !it.event.detailsPending && !it.event.imported }

    counted.filter { it.on in cutoff..today && it.on <= writeDayOf(it.event, zone) }
        .maxOfOrNull { it.on }
        ?.let { return Readiness.Ready(it, s.id, boundary) }

    val reason = if (counted.any { it.on > today }) {
        NotReadyReason.FUTURE_DATED
    } else if (counted.any { it.on in cutoff..today && writeDayOf(it.event, zone) < it.on }) {
        NotReadyReason.RECORDED_IN_ADVANCE
    } else if (own.any { it.event.detailsPending && !it.event.imported && it.on >= cutoff }) {
        NotReadyReason.DETAILS_PENDING
    } else if (own.any { it.event.imported && it.on in cutoff..today }) {
        NotReadyReason.INHERITED_SERVICE_ONLY
    } else if (boundary.source == BoundarySource.COMMISSIONING) {
        NotReadyReason.NOT_DONE_SINCE_COMMISSIONING
    } else {
        NotReadyReason.NOT_DONE_SINCE_SEASON_END
    }
    return Readiness.NotReady(reason, s.id, boundary)
}

/** Whether [target] names [asset] itself or a group it is an open member of (archived or not). */
private fun targets(target: ScheduleTarget, asset: Asset, memberOf: Map<GroupId, MaintenanceGroup>): Boolean = when (target) {
    is ScheduleTarget.AssetTarget -> target.assetId == asset.id
    is ScheduleTarget.GroupTarget -> target.groupId in memberOf
}

/** An asset target is always live; a group target only while its group is not archived (F-28). */
private fun isLiveTarget(target: ScheduleTarget, memberOf: Map<GroupId, MaintenanceGroup>): Boolean = when (target) {
    is ScheduleTarget.AssetTarget -> true
    is ScheduleTarget.GroupTarget -> memberOf[target.groupId]?.archivedAt == null
}

/** The asset's own season end and its source (C9, C9a, G22), then the cutoff against [roleSince]. */
private fun boundaryOf(
    asset: Asset,
    ownActivations: List<SeasonActivation>,
    roleSince: LocalDate,
    today: LocalDate,
    haBoundary: HaBoundary,
): ReadinessBoundary {
    val context = SeasonContext.of(asset.seasonInputs(ownActivations))
    val seasonEnd: ReadinessBoundary? = when (asset.seasonMode) {
        // Refused before here (ROLE_NOT_ALLOWED); a YEAR_ROUND asset never had a season to end.
        SeasonMode.YEAR_ROUND -> null
        SeasonMode.CALENDAR -> context.lastSeasonEndOnOrBefore(today)
            ?.let { ReadinessBoundary(it, BoundarySource.CALENDAR_WINDOW) }
        SeasonMode.MANUAL -> manualSeasonEnd(context.lastSeasonEndOnOrBefore(today), haBoundary)
    }
    val unverifiedSince = when (haBoundary) {
        HaBoundary.NotUsed, is HaBoundary.Fresh -> null
        is HaBoundary.Unverified -> haBoundary.since.takeIf { asset.seasonMode == SeasonMode.MANUAL }
    }
    return if (seasonEnd == null || roleSince > seasonEnd.cutoffOn) {
        ReadinessBoundary(roleSince, BoundarySource.COMMISSIONING, unverifiedSince = unverifiedSince)
    } else {
        seasonEnd
    }
}

/** A `MANUAL` asset's season end: HA's while fresh, the later of HA's cache and the phone's END while unverified. */
private fun manualSeasonEnd(phoneEnd: LocalDate?, haBoundary: HaBoundary): ReadinessBoundary? = when (haBoundary) {
    HaBoundary.NotUsed -> phoneEnd?.let { ReadinessBoundary(it, BoundarySource.PHONE_SEASON_END) }
    is HaBoundary.Fresh -> ReadinessBoundary(haBoundary.on, BoundarySource.HA_SEASON_ENDED, checkedAt = haBoundary.readAt)
    is HaBoundary.Unverified -> listOfNotNull(haBoundary.cachedOn, phoneEnd).maxOrNull()
        ?.let { ReadinessBoundary(it, BoundarySource.HA_UNVERIFIED, unverifiedSince = haBoundary.since) }
}

/** The day an event was written: its `updatedAt` in its own zone, or in [fallback] when that id does not resolve. */
private fun writeDayOf(event: AssetEvent, fallback: ZoneId): LocalDate {
    val own = try {
        ZoneId.of(event.tzId)
    } catch (_: DateTimeException) {
        null
    }
    return Instant.ofEpochMilli(event.updatedAt).atZone(own ?: fallback).toLocalDate()
}

private fun dayOrNull(iso: String): LocalDate? = try {
    LocalDate.parse(iso)
} catch (_: DateTimeException) {
    null
}

private data class Dated(val event: AssetEvent, val on: LocalDate)
