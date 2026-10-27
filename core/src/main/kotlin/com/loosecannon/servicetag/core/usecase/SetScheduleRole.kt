package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.maintainedHere
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.season.isLeafChild

/**
 * #114 (C10, C10a) — why [SetScheduleRole] refused `SEASON_START`: a code the phone maps to one sentence (§5), never a
 * sentence itself. Nothing was written.
 */
sealed interface SeasonStartRefusal {
    /** The schedule is archived (P114-19). */
    data object ScheduleArchived : SeasonStartRefusal

    /** A QUICK schedule without the owner's explicit election (C10a, O-2; P114-5). */
    data object QuickNotElected : SeasonStartRefusal

    /** [assetId] is archived, retired or transferred out from here (P114-18). */
    data class NotMaintainedHere(val assetId: AssetId) : SeasonStartRefusal

    /** [assetId] is in use all year (P114-17). */
    data class YearRound(val assetId: AssetId) : SeasonStartRefusal

    /** [assetId] is a child with no child row of its own (O-5; P114-16). */
    data class LeafChild(val assetId: AssetId) : SeasonStartRefusal

    /** Another live role, [scheduleId] titled [title], already covers [assetId] (P114-20). */
    data class AlreadyCovered(val assetId: AssetId, val scheduleId: ScheduleId, val title: String) : SeasonStartRefusal

    /** The group target is archived (P114-22). */
    data object GroupArchived : SeasonStartRefusal

    /** The group target has no open member (P114-22). */
    data object GroupHasNoMembers : SeasonStartRefusal

    /** `sinceOn` is after today (P114-23). */
    data object DateInFuture : SeasonStartRefusal

    /**
     * #124 (C42) — a schedule would count from the opening service of [assetId], which no live role covers (C9's
     * coverage, a group's role included; P124-5).
     */
    data class NoSeasonStartService(val assetId: AssetId) : SeasonStartRefusal

    /**
     * #124 (C42) — the Season Start service would count from itself: the editor's choice on the role schedule
     * (P124-6), or the role on a schedule that counts from the opening service (P124-7).
     */
    data object CountsFromItself : SeasonStartRefusal
}

/** #114 (C10) — 409: `SEASON_START` refused for [reason]; nothing was written. The message is the code only. */
class SeasonStartRefused(val reason: SeasonStartRefusal) :
    IllegalStateException("the Season Start role was refused: ${reason.javaClass.simpleName}")

/** #114 (C10) — 422: `sinceOn` is not an ISO `YYYY-MM-DD` date (P114-28); [field] names the body key. */
class SeasonStartBadDate(val field: String) : IllegalArgumentException("$field is not an ISO YYYY-MM-DD date")

/**
 * #114 (C10, C10a) — sets or clears a schedule's passive Season Start role, in one `uow.write`. The phone's role
 * action, and later `/v1` and MCP (A4w), reach it; the schedule form never carries a role (K11).
 *
 * - **`NONE`** clears the role and its boundary. Nothing refuses it but [NoSuchSchedule] and #124's strand: clearing a
 *   live role while a live schedule of an asset it covers counts from the opening service is 409 [SeasonStartStrands]
 *   (`HAS_DEPENDENT_SCHEDULES`, C42). An archived schedule may be cleared, and clearing a schedule that has no role
 *   writes nothing.
 * - **`SEASON_START`** takes [sinceOn] as its boundary, today when absent (O-4). In order: a malformed date is 422
 *   [SeasonStartBadDate]; then 409 [SeasonStartRefused] for an archived schedule, a schedule that counts from the
 *   opening service (#124, `COUNTS_FROM_ITSELF`), a QUICK schedule without
 *   [quickElected] (O-2's explicit election, C10a; FORM and `NONE` ignore it), the target's own rule — a group target
 *   archived or with no open member, then **every open member**, the first failing one by name then id — and last a
 *   date after today. An asset fails when it is not maintained here, is `YEAR_ROUND`, is a child with no child row of
 *   its own (O-5), or another live role already covers it (C9's coverage, this schedule left out). Setting the role it
 *   already has, on the same day, writes nothing; a new date moves only `roleSinceOn`.
 *
 * The write moves `role`, `roleSinceOn` and `updatedAt` only — never `ruleChangedAt`, `postponedDueOn` or a provider
 * row. The role changes no rule, so the role schedule's own state is unchanged (C7, row 21); but it moves readiness, so
 * the same write rebuilds each asset it covers — the target, or the group's open members — for the schedules counting
 * from their opening service (#124, C41). No event is touched, so clearing and setting the role again never re-admits
 * an imported service (B, row 21a).
 */
class SetScheduleRole(
    private val schedules: ScheduleRepository,
    private val assets: AssetRepository,
    private val groups: GroupRepository,
    private val transfers: TransferRecordRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
    private val today: Today,
    private val recompute: RecomputeSchedules,
) {
    suspend fun run(
        id: ScheduleId,
        role: ScheduleRole,
        sinceOn: String? = null,
        quickElected: Boolean = false,
    ): MaintenanceSchedule = uow.write {
        val schedule = schedules.get(id) ?: throw NoSuchSchedule(id)
        when (role) {
            ScheduleRole.NONE -> {
                clearStrandOf(schedule)?.let { throw it }
                write(schedule, ScheduleRole.NONE, null)
            }
            ScheduleRole.SEASON_START -> {
                val day = today.localDate()
                val since = sinceOn?.let { parseDate(it) ?: throw SeasonStartBadDate("sinceOn") } ?: day
                refusalOf(schedule, quickElected)?.let { throw SeasonStartRefused(it) }
                if (since > day) throw SeasonStartRefused(SeasonStartRefusal.DateInFuture)
                write(schedule, ScheduleRole.SEASON_START, since.toString())
            }
        }
    }

    /** The row with [role] and [sinceOn], or the stored row untouched when it already says both. */
    private suspend fun write(schedule: MaintenanceSchedule, role: ScheduleRole, sinceOn: String?): MaintenanceSchedule {
        if (schedule.role == role && schedule.roleSinceOn == sinceOn) return schedule
        val saved = schedule.copy(role = role, roleSinceOn = sinceOn, updatedAt = clock.nowMillis())
        schedules.upsert(saved)
        coveredBy(saved).forEach { recompute.forAsset(it) }
        return saved
    }

    /** #124 (C41) — the assets [schedule]'s role covers, or covered: its asset, or its group's open members. */
    private suspend fun coveredBy(schedule: MaintenanceSchedule): List<AssetId> =
        assetsAimedAt(schedule, listOfNotNull((schedule.target as? ScheduleTarget.GroupTarget)?.let { groups.get(it.groupId) }))

    /**
     * #124 (C42) — clearing a live role that a live schedule of a covered asset counts from is 409 [SeasonStartStrands]
     * (`HAS_DEPENDENT_SCHEDULES`, naming those schedules): change when they start counting first.
     */
    private suspend fun clearStrandOf(schedule: MaintenanceSchedule): SeasonStartStrands? {
        if (schedule.role != ScheduleRole.SEASON_START) return null
        val before = schedules.all()
        val after = before.map { if (it.id == schedule.id) it.copy(role = ScheduleRole.NONE, roleSinceOn = null) else it }
        val everyGroup = groups.all()
        return openingServiceStrandOf(coveredBy(schedule), assets.all(), before, everyGroup, after, everyGroup)
    }

    /** C10's table in its order, but for the date (checked last); null when the schedule may take the role. */
    private suspend fun refusalOf(schedule: MaintenanceSchedule, quickElected: Boolean): SeasonStartRefusal? {
        if (schedule.status == ScheduleStatus.ARCHIVED) return SeasonStartRefusal.ScheduleArchived
        // #124 (C42): a schedule that counts from the opening service cannot be the one it counts from.
        if (schedule.servicePolicy == ServicePolicy.IN_SERVICE_AFTER_OPENING_SERVICE) return SeasonStartRefusal.CountsFromItself
        if (schedule.completionMode == CompletionMode.QUICK && !quickElected) return SeasonStartRefusal.QuickNotElected
        val all = assets.all()
        val held = transfers.heldIds()
        val everySchedule = schedules.all()
        val everyGroup = groups.all()
        fun refusalFor(asset: Asset): SeasonStartRefusal? = seasonStartAssetRefusal(asset, all, held)
            ?: seasonStartRolesCovering(asset.id, everySchedule, everyGroup, excluding = schedule.id).firstOrNull()
                ?.let { SeasonStartRefusal.AlreadyCovered(asset.id, it.id, it.title) }
        return when (val target = schedule.target) {
            is ScheduleTarget.AssetTarget -> refusalFor(assets.get(target.assetId) ?: throw NoSuchAsset(target.assetId))
            is ScheduleTarget.GroupTarget -> {
                val group = groups.get(target.groupId) ?: throw NoSuchGroup(target.groupId)
                val open = group.openMembers().map { it.assetId }.toSet()
                if (group.archivedAt != null) {
                    SeasonStartRefusal.GroupArchived
                } else if (open.isEmpty()) {
                    SeasonStartRefusal.GroupHasNoMembers
                } else {
                    all.filter { it.id in open }
                        .sortedWith(compareBy({ it.name }, { it.id.value }))
                        .firstNotNullOfOrNull(::refusalFor)
                }
            }
        }
    }
}

/**
 * #114 (C10) — the asset half of C10's rule, which a group's new member must pass too (C11): null when [asset] may be
 * covered by a role; otherwise why not — not maintained here, then `YEAR_ROUND`, then a child with no child row of its
 * own among [assets] (O-5: any child row counts, whatever its lifecycle).
 */
internal fun seasonStartAssetRefusal(asset: Asset, assets: Collection<Asset>, held: Set<AssetId>): SeasonStartRefusal? =
    if (!asset.maintainedHere(held)) {
        SeasonStartRefusal.NotMaintainedHere(asset.id)
    } else if (asset.seasonMode == SeasonMode.YEAR_ROUND) {
        SeasonStartRefusal.YearRound(asset.id)
    } else if (isLeafChild(asset, assets)) {
        SeasonStartRefusal.LeafChild(asset.id)
    } else {
        null
    }
