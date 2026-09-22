package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Takes a schedule out of every due list without deleting it, or puts it back.
 *
 * Archiving is a column, exactly as it is for an Asset, a definition and a profile: the completion
 * events keep pointing at the schedule, the closures keep their rounds, and nothing in 1.2 deletes
 * a schedule row at all. An archived schedule is excluded from the dashboard, the Maintenance lists
 * and the due totals — the filter lives in one place, the domain's `listedForDue`, so those
 * surfaces cannot disagree about it.
 *
 * **The reminder subject builder is the exception**, and deliberately: it reads every schedule and
 * an archived one arrives as a `Withdrawn` subject, so the provider is told to let go rather than
 * left to infer it from an absence (spec §2.5). Archiving therefore still reaches a provider — as a
 * withdrawal, on the next reconcile.
 *
 * Like [PauseSchedule] it leaves `updated_at` alone, for the same reason: the stamp's date is the
 * D-27 pin's floor and archiving is not a rule change (invariant 25).
 *
 * One `uow.write`.
 */
class ArchiveSchedule(
    private val schedules: ScheduleRepository,
    private val uow: UnitOfWork,
    private val recompute: RecomputeSchedules,
) {
    suspend fun run(id: ScheduleId, archived: Boolean): MaintenanceSchedule {
        val existing = schedules.get(id) ?: throw NoSuchSchedule(id)
        val saved = existing.copy(
            status = if (archived) ScheduleStatus.ARCHIVED else ScheduleStatus.ACTIVE,
        )
        uow.write {
            schedules.upsert(saved)
            recompute.forSchedule(id)
        }
        return saved
    }
}
