package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ClosureRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * D-8's answer to a round that will never be finished: **it ends it without claiming anybody did the
 * work.**
 *
 * It writes **one** `occurrence_closure` row and, in the ordinary case, nothing else at all. No
 * column on `maintenance_schedule` — so a schedule that was never postponed still re-imports as
 * `IDENTICAL` however many rounds it has closed (invariants 35, 68, 69) — and no `asset_event` on
 * any member, which is the whole point: a closed round is a statement that it ended unfinished, and
 * a fabricated event "so the round looks done" would be exactly the lie the fact exists to avoid
 * (invariant 36). The one exception is a postponement, which the termination consumes: that clears
 * its single column and leaves `updated_at` where it is, because only an edit moves the pin's floor.
 *
 * The row is **immutable**: there is no update and no delete anywhere above it, which is why the
 * four refusals matter more here than they would on an amendable row.
 *
 * - an asset-targeted schedule: in 1.2 the action is offered on group targets only, where a round
 *   can be partially done and stuck. An asset round is one member; completing it is the answer.
 * - a round that already carries a closure: the first row stands, unchanged. Closing the same round
 *   twice is refused here and by the unique index underneath.
 * - a round that is in fact **complete**: closing it would record that it ended unfinished when it
 *   did not. The engine already treats such a closure as inert (invariant 40); refusing keeps the
 *   exported history honest rather than merely harmless.
 * - a round that obliges **nobody**: there is no round for a closure to be about (invariant 77).
 *
 * And `closedOn`, which is the date the recurrence advances from: it defaults to today and may lie
 * anywhere from the round's **open date** through today, inclusive. Anything else is refused,
 * because the row can never be amended and an unbounded caller date would move a schedule's future
 * for good (invariant 78). The range is the same one D-25 gives a completion, so the API and the
 * in-app action cannot diverge.
 */
class CloseRound(
    private val schedules: ScheduleRepository,
    private val closures: ClosureRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val today: Today,
    private val recompute: RecomputeSchedules,
) {
    suspend fun run(id: ScheduleId, closedOn: String? = null): OccurrenceClosure {
        val schedule = schedules.get(id) ?: throw NoSuchSchedule(id)
        if (schedule.status == ScheduleStatus.ARCHIVED) throw ScheduleArchived(id)
        if (schedule.target !is ScheduleTarget.GroupTarget) throw CloseNotSupported(id)
        // As in the member completion: a group target always carries a time rule, so it always has
        // a dated round.
        val occurrence = recompute.occurrenceOf(schedule)
            ?: throw ScheduleValidation(listOf(ScheduleProblem.NoRuleSide))
        val key = occurrence.occurrenceOn.toString()

        if (closures.find(id, key) != null) throw OccurrenceAlreadyClosed(id, key)
        if (occurrence.isComplete) throw OccurrenceAlreadyComplete(id, key)
        if (!occurrence.isActionable) throw OccurrenceNotCloseable(id, key)

        val todayOn = today.localDate()
        val asked = closedOn?.trim() ?: todayOn.toString()
        val parsed = parseDate(asked) ?: throw BadScheduleDate(asked)
        if (parsed.isBefore(occurrence.openOn) || parsed.isAfter(todayOn)) {
            throw ClosedOnOutOfRange(asked, occurrence.openOn.toString(), todayOn.toString())
        }

        val closure = OccurrenceClosure(
            id = ids.newId(),
            scheduleId = schedule.id,
            occurrenceOn = key,
            closedOn = asked,
            createdAt = clock.nowMillis(),
        )
        uow.write {
            closures.insert(closure)
            if (schedule.postponedDueOn != null) {
                schedules.upsert(schedule.copy(postponedDueOn = null))
            }
            recompute.forSchedule(schedule.id)
        }
        return closure
    }
}
