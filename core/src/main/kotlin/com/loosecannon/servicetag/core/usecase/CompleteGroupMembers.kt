package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ClosureRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.schedule.GroupOccurrence

/**
 * Records that **named members** of a group round did the work: "Complete selected", and with
 * [all], "Complete all".
 *
 * One event per member, on the member's **own** Asset, through the shipped event path — so a group
 * schedule owns no event of its own and a group is never the thing serviced (invariant 34).
 * Completing one member writes nothing on any other (invariants 28, 29), which is why the member
 * list is a parameter and never derived from "everyone who looks done".
 *
 * Four facts carry the weight:
 *
 * - **every named asset is checked against the round's required set**, not against the group's
 *   membership as it stands: a member whose window did not cover this round's open instant is
 *   refused, and so is one that was never in the group at all. An unchecked id here would write a
 *   maintenance record onto somebody else's equipment.
 * - **a member already complete for this round is skipped**, not refused and not written again. The
 *   database would refuse the duplicate anyway — `UNIQUE(schedule_id, occurrence_on, asset_id)` is
 *   what makes idempotence a constraint rather than a discipline (invariants 31, 32) — and the
 *   owner tapping twice has asked for nothing wrong.
 * - **the whole batch is one `uow.write`**: "Complete all" either records every member or none.
 * - **the postponement is cleared only by the write that finishes the round.** A partial completion
 *   leaves it, because the date the owner agreed still governs the members who have not been round
 *   yet; and when the round does finish, the clear writes that one column and leaves `updated_at`
 *   alone, because only an explicit edit moves the D-27 pin's floor (invariants 19, 25, 68, 69).
 */
class CompleteGroupMembers(
    private val schedules: ScheduleRepository,
    private val groups: GroupRepository,
    private val events: EventRepository,
    private val closures: ClosureRepository,
    private val definitions: DefinitionRepository,
    private val profiles: ProfileRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val recompute: RecomputeSchedules,
) {
    /** "Complete selected": exactly the members named, minus any already done. */
    suspend fun run(id: ScheduleId, assetIds: List<AssetId>, cmd: CompletionCommand): List<AssetEvent> {
        val round = openRound(id)
        return write(round, assetIds, cmd)
    }

    /**
     * "Complete all": every required member that is not yet done, in one write.
     *
     * The list is derived here rather than passed in so that a surface cannot accidentally complete
     * a member the round does not oblige — and a second run writes nothing, because by then there is
     * nothing in the round left to do.
     */
    suspend fun all(id: ScheduleId, cmd: CompletionCommand): List<AssetEvent> {
        val round = openRound(id)
        val outstanding = round.occurrence.required.filterNot { it in round.occurrence.completed }
        return write(round, outstanding, cmd)
    }

    private suspend fun write(
        round: OpenRound,
        assetIds: List<AssetId>,
        cmd: CompletionCommand,
    ): List<AssetEvent> {
        val schedule = round.schedule
        val occurrence = round.occurrence
        val key = occurrence.occurrenceOn.toString()

        val everAMember = round.group.members.map { it.assetId }.toSet()
        assetIds.forEach { assetId ->
            if (assetId !in everAMember) throw NotAGroupMember(schedule.id, assetId)
            if (assetId !in occurrence.required) throw NotARequiredMember(schedule.id, assetId)
        }

        val outstanding = assetIds.distinct().filterNot { it in occurrence.completed }
        // Nothing to record is not an error: the members named are already done, which is the
        // answer a second tap deserves. Refusing here would make a repeat a failure the surface had
        // to explain, and writing would make it a duplicate the index would refuse.
        if (outstanding.isEmpty()) return emptyList()

        val now = clock.nowMillis()
        return uow.write {
            val written = outstanding.map { assetId -> record(schedule, assetId, key, cmd, now) }
            // Only the write that covers the required set terminates the round, and only a
            // termination consumes the postponement.
            val finished = (occurrence.completed + outstanding).containsAll(occurrence.required)
            if (finished && schedule.postponedDueOn != null) {
                schedules.upsert(schedule.copy(postponedDueOn = null))
            }
            recompute.forSchedule(schedule.id)
            written
        }
    }

    private suspend fun record(
        schedule: MaintenanceSchedule,
        assetId: AssetId,
        occurrenceOn: String,
        cmd: CompletionCommand,
        now: Long,
    ): AssetEvent {
        val eventCmd = EventCommand(
            assetId = assetId,
            // A group target carries no `profileId` (invariant 3), so there is no form behind a
            // group completion and nothing for `details_pending` to be owed to.
            profileId = null,
            kind = EventKind.MAINTENANCE,
            title = schedule.title,
            occurredOn = cmd.occurredOn,
            occurredTime = cmd.occurredTime,
            tzId = cmd.tzId,
            notes = cmd.notes,
            values = cmd.values,
            consumables = cmd.consumables,
            scheduleId = schedule.id,
            occurrenceOn = occurrenceOn,
            detailsPending = false,
        )
        // The shared validation path, unchanged: whatever a caller sent is checked against *this*
        // member's own definitions, so a value belonging to another Asset is refused here rather
        // than stored against the wrong one.
        val profile = resolveOwnedProfile(eventCmd, definitions, profiles)
        val event = buildEvent(
            cmd = eventCmd,
            definitions = definitions,
            profile = profile,
            existing = null,
            ids = ids,
            now = now,
            source = if (schedule.completionMode == CompletionMode.QUICK) {
                EventSource.SCHEDULE_QUICK_COMPLETE
            } else {
                EventSource.MANUAL
            },
        )
        events.upsert(event)
        return event
    }

    /**
     * The round both entry points act on, resolved once so the member list [all] derives and the
     * checks [write] runs come from the same history.
     */
    private suspend fun openRound(id: ScheduleId): OpenRound {
        val schedule = schedules.get(id) ?: throw NoSuchSchedule(id)
        if (schedule.status == ScheduleStatus.ARCHIVED) throw ScheduleArchived(id)
        val target = schedule.target as? ScheduleTarget.GroupTarget
            ?: throw MemberCompletionNotSupported(id)
        val group = groups.get(target.groupId) ?: throw NoSuchGroup(target.groupId)
        // A group target can carry no meter rule and must carry some rule, so it always has a time
        // rule and therefore always has a dated occurrence; a row that does not is not storable.
        val occurrence = recompute.occurrenceOf(schedule)
            ?: throw ScheduleValidation(listOf(ScheduleProblem.NoRuleSide))
        val key = occurrence.occurrenceOn.toString()

        // A closed round is a statement that it ended unfinished, and nothing may turn that into a
        // claim that it was finished (invariant 39). The work itself is still loggable — as an
        // ordinary journal event, with no schedule link.
        if (closures.find(id, key) != null) throw OccurrenceClosed(id, key)
        if (!occurrence.isActionable) throw OccurrenceNotActionable(id, key)
        return OpenRound(schedule, group, occurrence)
    }

    private data class OpenRound(
        val schedule: MaintenanceSchedule,
        val group: MaintenanceGroup,
        val occurrence: GroupOccurrence,
    )
}
