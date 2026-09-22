package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Moves **this occurrence** to another date, or puts it back, and changes nothing else.
 *
 * It writes `postponed_due_on` and no other column: not the rule, not the anchor, not
 * `computed_due_on`, which is kept for audit and is still what the *next* occurrence comes from
 * (invariant 21). That is the whole distinction the operations table draws, and the one an
 * implementation loses by treating a postpone as a small edit — completing a postponed schedule
 * advances from the rule and from the completion, never from the postponed date.
 *
 * **A null [postponedDueOn] clears the override**, which is what the route built on this use case
 * accepts, and the only other way an override goes is a rule-changing edit or a completion. A
 * postpone of a schedule with **no time rule** is refused: there is no occurrence date to move, and
 * writing the column anyway would hand the sort key a date the schedule does not have
 * (invariant 10). Clearing is allowed whatever the rule, so a row that arrived carrying an override
 * it should never have had can still be cleaned up.
 *
 * It does not bump `updated_at`. The stamp's date is the D-27 pin's floor (spec §2.1), so a write
 * that is not a rule change must leave it where it is or a never-terminated schedule's due date
 * would move with it (invariant 25). The row's real change is in `postponed_due_on`, which is a
 * backup-format field and is what a merge compares, so nothing downstream loses the fact.
 *
 * One `uow.write`.
 */
class PostponeSchedule(
    private val schedules: ScheduleRepository,
    private val uow: UnitOfWork,
    private val recompute: RecomputeSchedules,
) {
    suspend fun run(id: ScheduleId, postponedDueOn: String?): MaintenanceSchedule {
        val existing = schedules.get(id) ?: throw NoSuchSchedule(id)
        if (postponedDueOn != null) {
            if (existing.timeInterval == null) {
                throw ScheduleValidation(listOf(ScheduleProblem.PostponeNeedsTimeRule))
            }
            parseDate(postponedDueOn) ?: throw BadScheduleDate(postponedDueOn)
        }
        val saved = existing.copy(postponedDueOn = postponedDueOn)
        uow.write {
            schedules.upsert(saved)
            recompute.forSchedule(id)
        }
        return saved
    }
}
