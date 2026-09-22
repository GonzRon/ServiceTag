package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetTree
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.ClosureRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.schedule.GroupOccurrences

/**
 * The delete was refused because the asset holds a membership window that a recorded group round
 * was derived from (invariant 8, D-16). [groups] names the groups it happened in, so a caller can
 * say whose history it is.
 *
 * It is a **refusal**, not a validation problem: the command is well formed and the store's state
 * is what forbids it, which is why it carries no [GroupProblem] and answers 409 on the wire rather
 * than 422. Without it the membership rows would go silently, by the membership table's cascade
 * from its asset — and a past occurrence's `required(D)` is derived from exactly those rows, so the
 * cascade would rewrite a round already recorded as done.
 */
class AssetMembershipReferenced(val assetId: AssetId, val groups: List<GroupId>) :
    IllegalStateException(
        "asset ${assetId.value} holds a membership window a recorded group round covered: " +
            groups.map { it.value },
    )

/**
 * The one destructive asset action, behind the typed confirmation flow. Children-first (spec §5):
 * a parent that still has children is refused with them named, so nobody loses a sub-assembly to
 * a cascade they did not picture. Everything that hangs off the asset itself — its tags, links,
 * definitions, profiles, events and attachment rows — goes with it, by the schema's own cascades;
 * the attachment *bytes* are nothing the schema can cascade, so they are swept here.
 *
 * **Membership second** (1.2, invariant 8). Its group membership rows would go by that same
 * cascade, and a past occurrence's required set is derived from them and from nothing else — so an
 * asset whose windows are part of the basis of a recorded round is refused for the same reason a
 * parent with children is: the destruction is larger than the one the person pictured. Archiving or
 * retiring the asset is the operation that does what they meant, and neither touches a window.
 */
class DeleteAsset(
    private val assets: AssetRepository,
    private val events: EventRepository,
    private val attachments: AttachmentRepository,
    private val storage: AttachmentStorage,
    private val uow: UnitOfWork,
    private val groups: GroupRepository,
    private val schedules: ScheduleRepository,
    private val closures: ClosureRepository,
) {
    suspend fun run(id: AssetId) {
        val all = assets.all()
        if (all.none { it.id == id }) throw NoSuchAsset(id)
        val children = AssetTree.children(all, id)
        if (children.isNotEmpty()) throw AssetHasChildren(id, children.map { it.id })
        val referenced = groupsReferencing(id)
        if (referenced.isNotEmpty()) throw AssetMembershipReferenced(id, referenced)

        // The locators are read *before* the cascade takes the rows with it, inside the same
        // transaction that deletes them: after the commit there is nothing left to ask.
        val doomed = uow.write {
            val own = attachments.forAsset(id)
            val theirs = events.forAsset(id)
                .flatMap { event -> attachments.forOwner(AttachmentOwner.OfEvent(event.id)) }
            val locators = (own + theirs).map { it.storageLocator }
            assets.delete(id)
            locators
        }

        // Bytes after the commit, best effort: a file the store will not delete is an orphan,
        // not a reason to keep an asset the person deleted.
        storage.sweepBytes(doomed)
    }

    /**
     * The groups holding a recorded round **this asset's own windows covered**, which is invariant
     * 8's condition word for word: *a membership row is never hard-deleted while any completion or
     * closure references an occurrence its window covered*.
     *
     * The narrowness is the point. Removing this asset's windows can only change `required(D)` for
     * those `D` whose open instant one of them covered; for every other `D` the asset was never in
     * `required(D)` and the set is unchanged. Refusing more widely would make an asset added to a
     * long-serviced group permanently undeletable for no invariant's sake.
     *
     * The open instant comes from [GroupOccurrences.openInstantOn] — the engine's own derivation,
     * from the same rows — and the window test is [openAt], the same `[addedAt, removedAt)` rule
     * the occurrence derivation applies. Neither is re-implemented here, and neither needs the
     * lifecycle bound: whether a window *covered* an instant is a fact about the window, while
     * whether its Asset is still in service is the separate question D-16 answers.
     *
     * "Recorded" means one of the two facts §7's `terminations` reads: a member completion carrying
     * the schedule's id, or a closure row. A group whose schedules have neither has no round to
     * rewrite, so an asset can still be deleted out of a group nobody has serviced yet.
     */
    private suspend fun groupsReferencing(id: AssetId): List<GroupId> =
        groups.allWindowsFor(id)
            .filter { group ->
                val mine = group.members.filter { it.assetId == id }
                if (mine.isEmpty()) return@filter false
                // Every member's events, because a round's rows may sit on any of them.
                val memberEvents = group.members.map { it.assetId }.distinct()
                    .flatMap { events.forAsset(it) }
                schedules.forGroup(group.id).any { schedule ->
                    val closureRows = closures.forSchedule(schedule.id)
                    val recorded = (
                        memberEvents.filter { it.scheduleId == schedule.id }
                            .mapNotNull { it.occurrenceOn } + closureRows.map { it.occurrenceOn }
                        ).distinct()
                    recorded.any { key ->
                        val openInstant = GroupOccurrences.openInstantOn(
                            schedule = schedule,
                            events = memberEvents,
                            closures = closureRows,
                            occurrenceOn = key,
                        )
                        mine.openAt(openInstant).isNotEmpty()
                    }
                }
            }
            .map { it.id }
}
