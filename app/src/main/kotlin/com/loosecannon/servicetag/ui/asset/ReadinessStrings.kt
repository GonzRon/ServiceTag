package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.season.BoundarySource
import com.loosecannon.servicetag.core.season.NotReadyReason
import com.loosecannon.servicetag.core.season.Readiness
import com.loosecannon.servicetag.core.season.ReadinessBoundary
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.l10n.localizedDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * #114 (C33, §5) — the season card's readiness lines and the completion line, each read from
 * `res/values/strings_season_start.xml` when drawn, and the maps from C9's answer to its sentence. Every map is an
 * exhaustive `when` over the core's closed sets (G11), so a new reason or source does not compile until it has its
 * sentence (F-16). Dates are drawn in the shipped date display.
 */
internal object ReadinessStrings {

    /**
     * The readiness line: P114-7 for `Ready`, and P114-8…13, P114-29, P114-40, P114-46 or P114-54 by reason. Null is no
     * line: `NO_ROLE` without a date binding (R-N1), or a reason whose date is missing from a malformed answer.
     * [evidenceOn] is the service a `FUTURE_DATED` or `RECORDED_IN_ADVANCE` line names.
     */
    fun line(readiness: Readiness, hasDateBinding: Boolean, evidenceOn: LocalDate?): String? = when (readiness) {
        is Readiness.Ready -> localized(R.string.season_start_ready, localizedDate(readiness.serviceOn))
        is Readiness.NotReady -> notReady(readiness, hasDateBinding, evidenceOn)
    }

    private fun notReady(readiness: Readiness.NotReady, hasDateBinding: Boolean, evidenceOn: LocalDate?): String? =
        when (readiness.reason) {
            NotReadyReason.NO_ROLE ->
                if (hasDateBinding) localized(R.string.season_start_not_ready_no_role) else null
            NotReadyReason.ROLE_NOT_ALLOWED -> localized(R.string.season_start_not_ready_role_not_allowed)
            NotReadyReason.TWO_ROLES -> localized(R.string.season_start_not_ready_two_roles)
            NotReadyReason.SCHEDULE_ARCHIVED -> localized(R.string.season_start_not_ready_schedule_archived)
            NotReadyReason.NOT_DONE_SINCE_SEASON_END -> readiness.boundary?.let {
                localized(R.string.season_start_not_ready_since_season_end, localizedDate(it.cutoffOn))
            }
            NotReadyReason.NOT_DONE_SINCE_COMMISSIONING -> readiness.boundary?.let {
                localized(R.string.season_start_not_ready_since_commissioning, localizedDate(it.cutoffOn))
            }
            NotReadyReason.DETAILS_PENDING -> localized(R.string.season_start_not_ready_details_pending)
            NotReadyReason.FUTURE_DATED -> evidenceOn?.let {
                localized(R.string.season_start_not_ready_future_dated, localizedDate(it))
            }
            NotReadyReason.INHERITED_SERVICE_ONLY -> localized(R.string.season_start_not_ready_inherited_service_only)
            NotReadyReason.RECORDED_IN_ADVANCE -> evidenceOn?.let {
                localized(R.string.season_start_not_ready_recorded_in_advance, localizedDate(it))
            }
        }

    /**
     * The source line (F): where the season end came from, by [ReadinessBoundary.source] — P114-52, P114-51, P114-44
     * with its check date, P114-45 with its since date and the season end in use, or P114-53. Null only when an HA
     * source arrives without its stamp, which C9 never builds. Times are drawn as days in [zone].
     */
    fun sourceLine(boundary: ReadinessBoundary, zone: ZoneId): String? = when (boundary.source) {
        BoundarySource.CALENDAR_WINDOW ->
            localized(R.string.season_start_source_calendar_window, localizedDate(boundary.cutoffOn))
        BoundarySource.PHONE_SEASON_END ->
            localized(R.string.season_start_source_phone_season_end, localizedDate(boundary.cutoffOn))
        BoundarySource.HA_SEASON_ENDED -> boundary.checkedAt?.let { checked ->
            localized(
                R.string.season_start_source_ha_season_ended,
                localizedDate(boundary.cutoffOn),
                localizedDate(dayOf(checked, zone)),
            )
        }
        BoundarySource.HA_UNVERIFIED -> unverifiedLine(boundary, zone)
        BoundarySource.COMMISSIONING ->
            localized(R.string.season_start_source_commissioning, localizedDate(boundary.cutoffOn))
    }

    /**
     * O-19 — P114-45 under another source's line while Home Assistant's season end is unverified, whatever won the
     * cutoff (C9a: "unverified since" on the card); null while it is not, and for `HA_UNVERIFIED`, whose own source
     * line is already P114-45.
     */
    fun unverifiedDisclosure(boundary: ReadinessBoundary, zone: ZoneId): String? = when (boundary.source) {
        BoundarySource.HA_UNVERIFIED -> null
        BoundarySource.CALENDAR_WINDOW,
        BoundarySource.PHONE_SEASON_END,
        BoundarySource.HA_SEASON_ENDED,
        BoundarySource.COMMISSIONING,
        -> unverifiedLine(boundary, zone)
    }

    private fun unverifiedLine(boundary: ReadinessBoundary, zone: ZoneId): String? = boundary.unverifiedSince?.let {
        localized(
            R.string.season_start_source_ha_unverified,
            localizedDate(dayOf(it, zone)),
            localizedDate(boundary.cutoffOn),
        )
    }

    /** P114-50 — the cutoff line: services dated on or after [ReadinessBoundary.cutoffOn] count. */
    fun cutoffLine(boundary: ReadinessBoundary): String =
        localized(R.string.season_start_cutoff, localizedDate(boundary.cutoffOn))

    /**
     * The completion line, before the write (C33): P114-14 with [assetName] when [schedule] is an asset's Season Start
     * service, P114-15 when it is a group's, and null for every other schedule — keyed on the role alone, never on the
     * schedule's policy. Null too for an asset's role when its name is not known.
     */
    fun completionLine(schedule: MaintenanceSchedule, assetName: String?): String? = when (schedule.role) {
        ScheduleRole.NONE -> null
        ScheduleRole.SEASON_START -> when (schedule.target) {
            is ScheduleTarget.AssetTarget ->
                assetName?.let { localized(R.string.season_start_completion_marks_asset_ready, it) }
            is ScheduleTarget.GroupTarget -> localized(R.string.season_start_completion_marks_member_ready)
        }
    }

    private fun dayOf(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}
