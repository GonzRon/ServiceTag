package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Pauses or resumes a schedule: the lifecycle column, and nothing else.
 *
 * A paused schedule keeps its overrides and its history, produces no due date to act on and never
 * notifies (invariant 22). Resuming runs the recompute, and a schedule that fell overdue while it
 * was paused is shown overdue again — the honest answer, and the reason this operation does not
 * touch `updated_at`: the stamp's date is the D-27 pin's floor, so bumping it would quietly push a
 * never-terminated schedule's due date forward every time someone paused and resumed it
 * (invariant 25). `status` is a backup-format field, so the change itself is not hidden from a
 * merge.
 *
 * One `uow.write`.
 */
class PauseSchedule(
    private val schedules: ScheduleRepository,
    private val uow: UnitOfWork,
    private val recompute: RecomputeSchedules,
) {
    suspend fun run(id: ScheduleId, paused: Boolean): MaintenanceSchedule {
        val existing = schedules.get(id) ?: throw NoSuchSchedule(id)
        val saved = existing.copy(
            status = if (paused) ScheduleStatus.PAUSED else ScheduleStatus.ACTIVE,
        )
        uow.write {
            schedules.upsert(saved)
            recompute.forSchedule(id)
        }
        return saved
    }
}
