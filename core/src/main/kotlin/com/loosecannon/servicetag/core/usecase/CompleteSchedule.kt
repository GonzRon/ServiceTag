package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Records that the current occurrence was done.
 *
 * **A completion is an insert plus a recompute, and that is all it is.** There is no "advance"
 * operation with logic of its own anywhere in 1.2: the event goes in through the same validation
 * path as every other event, the engine derives the next due date from the history that now
 * includes it, and deleting the event later puts the due date back with no bookkeeping to undo.
 *
 * Three details carry the weight:
 *
 * - `occurrence_on` is stamped from the schedule's **computed** due date, never the postponed one
 *   (invariant 21), and it is what makes a repeat a no-op at the database level rather than a
 *   discipline in code.
 * - **the write to the schedule row is conditional** (invariants 68, 69). A completion that clears
 *   nothing writes no column and does not bump `updated_at`, so a schedule that was never
 *   postponed re-imports as `IDENTICAL` however many occurrences it has advanced through. Only a
 *   completion that actually clears a postponement touches the row, and only then bumps the stamp.
 * - a **minimal** completion against a `FORM` schedule is allowed and marks itself
 *   `details_pending`: the notification's one-tap action and a bodyless API call both land here,
 *   and demanding the form's required fields would make the quick path impossible.
 *
 * A group-targeted schedule is refused: which member did the work and whether that member is
 * required for this occurrence is the groups brief's derivation, and guessing would mean writing an
 * event against an Asset this brief cannot prove is a member (invariants 28, 29).
 *
 * One `uow.write` covering the event, the conditional clear and the recompute.
 */
class CompleteSchedule(
    private val schedules: ScheduleRepository,
    private val events: EventRepository,
    private val definitions: DefinitionRepository,
    private val profiles: ProfileRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val recompute: RecomputeSchedules,
) {
    suspend fun run(id: ScheduleId, cmd: CompletionCommand): AssetEvent {
        val schedule = schedules.get(id) ?: throw NoSuchSchedule(id)
        if (schedule.status == ScheduleStatus.ARCHIVED) throw ScheduleArchived(id)
        val target = schedule.target as? ScheduleTarget.AssetTarget
            ?: throw GroupCompletionNotSupported(id)
        if (cmd.assetId != null && cmd.assetId != target.assetId) {
            throw EventOwnership(
                "schedule ${id.value} is for asset ${target.assetId.value}, not ${cmd.assetId.value}",
            )
        }

        val profileRow = schedule.profileId?.let { profiles.get(it) }
        val minimal = cmd.values.isEmpty() && cmd.consumables.isEmpty()
        val detailsPending = schedule.completionMode == CompletionMode.FORM && minimal

        val now = clock.nowMillis()
        return uow.write {
            val eventCmd = EventCommand(
                assetId = target.assetId,
                profileId = schedule.profileId,
                kind = profileRow?.eventKind ?: EventKind.MAINTENANCE,
                title = schedule.title,
                occurredOn = cmd.occurredOn,
                occurredTime = cmd.occurredTime,
                tzId = cmd.tzId,
                notes = cmd.notes,
                values = cmd.values,
                consumables = cmd.consumables,
                scheduleId = schedule.id,
                // Computed here rather than read out of the table, and inside the transaction that
                // writes the event, so the key it claims is the occurrence the engine says is open.
                occurrenceOn = recompute.stateOf(schedule).computedDueOn,
                detailsPending = detailsPending,
            )
            val profile = resolveOwnedProfile(eventCmd, definitions, profiles)
            val event = buildEvent(
                cmd = eventCmd,
                definitions = definitions,
                // A minimal completion is measured against no profile, so the form's required
                // fields are not demanded of it; the event still names the profile it owes them to.
                profile = if (detailsPending) null else profile,
                existing = null,
                ids = ids,
                now = now,
                // One tap is a quick complete; a form completion stays an ordinary manual entry,
                // and `schedule_id` is what carries the relationship either way.
                source = if (schedule.completionMode == CompletionMode.QUICK) {
                    EventSource.SCHEDULE_QUICK_COMPLETE
                } else {
                    EventSource.MANUAL
                },
            )
            events.upsert(event)
            if (schedule.postponedDueOn != null) {
                schedules.upsert(schedule.copy(postponedDueOn = null, updatedAt = now))
            }
            recompute.forAsset(target.assetId)
            event
        }
    }
}
