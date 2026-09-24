package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
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
 * **The health link guard** (spec §6.1, D-30; inv. 130): archiving a schedule a non-archived health
 * subject depends on is 422 [ScheduleDrivesHealthSubject], naming the subject, unless
 * `unlinkHealthSubject` is set — then the subject is archived in the same transaction, or the whole
 * write is 409 [HealthSubjectIsPrimary] when it is the subject its asset's TRACK_ONE follows (plan
 * decision 14). Restoring a schedule is never guarded and restores no subject; the flag with nothing
 * to unlink changes nothing.
 *
 * One `uow.write`.
 */
class ArchiveSchedule(
    private val schedules: ScheduleRepository,
    private val uow: UnitOfWork,
    private val recompute: RecomputeSchedules,
    private val healthSubjects: HealthSubjectRepository,
    private val assets: AssetRepository,
    private val clock: Clock,
) {
    suspend fun run(id: ScheduleId, archived: Boolean, unlinkHealthSubject: Boolean = false): MaintenanceSchedule {
        val existing = schedules.get(id) ?: throw NoSuchSchedule(id)
        val saved = existing.copy(
            status = if (archived) ScheduleStatus.ARCHIVED else ScheduleStatus.ACTIVE,
        )
        return uow.write {
            if (archived && existing.status != ScheduleStatus.ARCHIVED) {
                val driven = guardDriven(id, unlinkHealthSubject, healthSubjects)
                unlinkDriven(driven, assets, healthSubjects, clock.nowMillis())
            }
            schedules.upsert(saved)
            recompute.forSchedule(id)
            saved
        }
    }
}
