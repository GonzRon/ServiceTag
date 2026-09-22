package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.GroupMember
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
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
 */
class SaveGroup(
    private val groups: GroupRepository,
    private val assets: AssetRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun run(id: GroupId?, cmd: GroupCommand): MaintenanceGroup {
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

        val saved = MaintenanceGroup(
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
        uow.write { groups.upsert(saved) }
        return saved
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
