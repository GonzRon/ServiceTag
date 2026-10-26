package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.GroupMember
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Creates or edits one group, membership included — and the **only** writer of a membership window.
 *
 * Membership is append-only in its temporal fields, which is the whole reason this use case is more
 * than a row write. Three rules do all the work, and every one of them exists to keep a past round's
 * required set from ever changing (invariants 33, 79, 80):
 *
 * - a member sent **with** an id is *kept*: its `added_at` and `removed_at` are copied across
 *   untouched, and only `sort_order` can move.
 * - an open member the command **omits** is *soft-removed*: `removed_at` is stamped now. Nothing is
 *   deleted — the row a past occurrence keyed off has to survive (invariant 8).
 * - a member sent **without** an id is an *insert*: a new durable id and a new `added_at`. Re-adding
 *   an asset therefore produces a **second** window rather than reopening the first, because
 *   reopening would rewrite the window a past round was derived from. No code path here reopens an
 *   existing window, and that absence is deliberate.
 *
 * A member whose window is already closed and is not named is left exactly as it is: it is history,
 * and an edit that says nothing about it says nothing about it.
 *
 * Validation is collected rather than fail-fast, so an editor can mark every bad row at once, and it
 * runs entirely before the single `uow.write`. Two groups may share a `name`: a name is descriptive,
 * never identity (invariant 7), and nothing here or anywhere else looks a group up by one.
 *
 * #114 (C11): inside the write, 409 [SeasonStartStrands] for a member added to a group a role schedule targets
 * ([seasonStartStrandOf]) and, #124 (C42), for a member taken out of one while a live schedule of it counts from its
 * opening service (`HAS_DEPENDENT_SCHEDULES`); [saveInTransaction] (Replace asset) is not hooked.
 */
class SaveGroup(
    private val groups: GroupRepository,
    private val assets: AssetRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val schedules: ScheduleRepository,
    private val transfers: TransferRecordRepository,
) {
    suspend fun run(id: GroupId?, cmd: GroupCommand): MaintenanceGroup {
        val saved = prepare(id, cmd)
        uow.write {
            seasonStartStrandOf(saved)?.let { throw it }
            groups.upsert(saved)
        }
        return saved
    }

    /**
     * #114 (C11) — a member this save adds to a group a live (not ARCHIVED) Season Start role schedule targets must
     * pass C10's asset rule (`MEMBER_NOT_ELIGIBLE`) and have no other role covering it (`MEMBER_ALREADY_COVERED`); the
     * first failing one by name then id is named. A kept member is never checked, and a removed one only for #124's
     * dependents ([removalStrandOf]); every other indirect break is read by readiness (C9). `ReplaceAsset`'s
     * in-transaction add is not hooked.
     */
    private suspend fun seasonStartStrandOf(saved: MaintenanceGroup): SeasonStartStrands? {
        val roles = schedules.forGroup(saved.id)
            .filter { it.role == ScheduleRole.SEASON_START && it.status != ScheduleStatus.ARCHIVED }
        if (roles.isEmpty()) return null
        val stored = groups.get(saved.id)
        val known = stored?.members.orEmpty().map { it.id }.toSet()
        val added = saved.members.filter { it.id !in known && it.removedAt == null }.map { it.assetId }.toSet()
        if (added.isEmpty()) return removalStrandOf(stored, saved)
        val all = assets.all()
        val held = transfers.heldIds()
        val everySchedule = schedules.all()
        val everyGroup = groups.all()
        return all.filter { it.id in added }
            .sortedWith(compareBy({ it.name }, { it.id.value }))
            .firstNotNullOfOrNull { asset ->
                val covering = seasonStartRolesCovering(asset.id, everySchedule, everyGroup)
                val cause = if (seasonStartAssetRefusal(asset, all, held) != null) {
                    SeasonStartStrandCause.MEMBER_NOT_ELIGIBLE
                } else if (covering.isNotEmpty()) {
                    SeasonStartStrandCause.MEMBER_ALREADY_COVERED
                } else {
                    return@firstNotNullOfOrNull null
                }
                val named = (roles + covering).distinctBy { it.id }.sortedWith(compareBy({ it.title }, { it.id.value }))
                SeasonStartStrands(asset.id, cause, named.map { StrandedSchedule(it.id, it.title) })
            }
            ?: removalStrandOf(stored, saved)
    }

    /**
     * #124 (C42) — after C11's own causes: an open member this save takes out of a role group is 409
     * [SeasonStartStrands] (`HAS_DEPENDENT_SCHEDULES`) while a live schedule of it counts from its opening service and no
     * other live role would cover it ([openingServiceStrandOf], over the groups as the save leaves them).
     */
    private suspend fun removalStrandOf(stored: MaintenanceGroup?, saved: MaintenanceGroup): SeasonStartStrands? {
        val stillOpen = saved.openMembers().map { it.assetId }.toSet()
        val removed = stored?.openMembers().orEmpty().map { it.assetId }.filter { it !in stillOpen }
        if (removed.isEmpty()) return null
        val before = groups.all()
        val after = before.map { if (it.id == saved.id) saved else it }
        val every = schedules.all()
        return openingServiceStrandOf(removed, assets.all(), every, before, every, after)
    }

    /**
     * #86 (C15): the same reads, refusals and row as [run], written inside the caller's transaction, so
     * `ReplaceAsset` adds its new window in its own one write.
     */
    internal suspend fun saveInTransaction(id: GroupId?, cmd: GroupCommand): MaintenanceGroup =
        prepare(id, cmd).also { groups.upsert(it) }

    /** [run]'s reads and refusals, and the row it writes. */
    private suspend fun prepare(id: GroupId?, cmd: GroupCommand): MaintenanceGroup {
        val existing = id?.let { groups.get(it) ?: throw NoSuchGroup(it) }
        val problems = problemsOf(cmd, existing)
        if (problems.isNotEmpty()) throw GroupValidation(problems)

        val now = clock.nowMillis()
        val keptIds = cmd.members.mapNotNull { it.id }.toSet()
        val sortOrders = cmd.members.filter { it.id != null }.associate { it.id!! to it.sortOrder }

        val carried = existing?.members.orEmpty().map { row ->
            when {
                row.id in keptIds -> row.copy(sortOrder = sortOrders.getValue(row.id))
                // Omitted and still running: the owner took it out of the group. Closing the window
                // is the *only* mutation any operation ever makes to one.
                row.removedAt == null -> row.copy(removedAt = now)
                else -> row
            }
        }
        val added = cmd.members.filter { it.id == null }.map { input ->
            GroupMember(
                id = ids.newId(),
                assetId = input.assetId,
                sortOrder = input.sortOrder,
                addedAt = now,
                removedAt = null,
            )
        }

        return MaintenanceGroup(
            id = existing?.id ?: GroupId(ids.newId()),
            name = cmd.name.trim(),
            description = cmd.description.trim(),
            archivedAt = existing?.archivedAt,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            // The canonical child order, the one the backup encoder and the merge planner both
            // normalise to; `sortOrder` is not promised unique, so the id breaks the tie.
            members = (carried + added).sortedWith(compareBy({ it.sortOrder }, { it.id })),
        )
    }

    /**
     * Everything wrong with [cmd], collected.
     *
     * The member-asset check is a read of [AssetRepository] and not a foreign-key hope: the FK would
     * refuse the row anyway, but only after the transaction had begun and without saying which line
     * of the form was wrong. It is also invariant 8's precondition — a membership row may only ever
     * name an Asset that exists, because the row outlives every round it covered.
     */
    private suspend fun problemsOf(cmd: GroupCommand, existing: MaintenanceGroup?): List<GroupProblem> {
        val problems = mutableListOf<GroupProblem>()
        if (cmd.name.isBlank()) problems += GroupProblem.BlankName

        cmd.members.map { it.assetId }.distinct().forEach { assetId ->
            if (assets.get(assetId) == null) problems += GroupProblem.MemberAssetMissing(assetId)
        }

        val own = existing?.members.orEmpty().associateBy { it.id }
        cmd.members.mapNotNull { it.id }.forEach { memberId ->
            if (memberId !in own) problems += GroupProblem.ForeignMember(memberId)
        }

        // An add for an asset that already has an open window is the caller saying "add" when it
        // meant "keep": refused by name, so the answer names the asset the form must fix. The second
        // half catches two adds for one asset in one command, which the first cannot see and which
        // would leave two open windows just as surely.
        val openAssets = existing?.openMembers().orEmpty().map { it.assetId }.toSet()
        val addedAssets = mutableSetOf<AssetId>()
        cmd.members.filter { it.id == null }.forEach { input ->
            if (input.assetId in openAssets || !addedAssets.add(input.assetId)) {
                problems += GroupProblem.MemberAlreadyOpen(input.assetId)
            }
        }
        return problems.distinct()
    }
}
