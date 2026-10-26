package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
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
 * **The Season Start role** (#114, C11 erratum): restoring an archived role schedule is 409 [SeasonStartStrands] when
 * it would cover an asset that may not have a role, or one another live role covers ([seasonStartRestoreStrandOf]).
 * The row is read inside the write, so the rule judges the role as it is stored then.
 *
 * **Counting from the opening service** (#124, C42): archiving a live role schedule while a live schedule of an asset it
 * covers counts from its opening service is 409 [SeasonStartStrands] (`HAS_DEPENDENT_SCHEDULES`), after the health
 * guard's 422 and before anything is written; restoring such a dependent is 409 [SeasonStartRefused] unless a live role
 * covers its asset (`NO_SEASON_START_SERVICE`, or `YEAR_ROUND`) — a restore never brings back a schedule that would wait
 * for a service nothing can record.
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
    private val groups: GroupRepository,
    private val transfers: TransferRecordRepository,
) {
    suspend fun run(id: ScheduleId, archived: Boolean, unlinkHealthSubject: Boolean = false): MaintenanceSchedule =
        uow.write {
            val existing = schedules.get(id) ?: throw NoSuchSchedule(id)
            val saved = existing.copy(
                status = if (archived) ScheduleStatus.ARCHIVED else ScheduleStatus.ACTIVE,
            )
            if (archived && existing.status != ScheduleStatus.ARCHIVED) {
                val driven = guardDriven(id, unlinkHealthSubject, healthSubjects)
                if (existing.role == ScheduleRole.SEASON_START) archiveStrandOf(existing, saved)?.let { throw it }
                unlinkDriven(driven, assets, healthSubjects, clock.nowMillis())
            }
            if (!archived && existing.status == ScheduleStatus.ARCHIVED) {
                val after = schedules.all().map { if (it.id == id) saved else it }
                if (existing.role == ScheduleRole.SEASON_START) {
                    seasonStartRestoreStrandOf(setOf(id), assets.all(), transfers.heldIds(), after, groups.all())
                        ?.let { throw it }
                }
                // #124 (C42): a dependent comes back only to a live role it can count from.
                val asset = (saved.target as? ScheduleTarget.AssetTarget)?.let { assets.get(it.assetId) }
                openingServiceRefusalOf(saved, asset, after, groups.all())?.let { throw SeasonStartRefused(it) }
            }
            schedules.upsert(saved)
            recompute.forSchedule(id)
            // #124 (C41): a restored role moves readiness, so the schedules counting from it move with it (ArchiveGroup's rule).
            if (!archived && existing.status == ScheduleStatus.ARCHIVED && existing.role == ScheduleRole.SEASON_START) {
                assetsAimedAt(saved, groups.all()).forEach { recompute.forAsset(it) }
            }
            saved
        }

    /** #124 (C42): the strand of archiving the live role [before] while a live schedule of an asset it covers counts from it. */
    private suspend fun archiveStrandOf(before: MaintenanceSchedule, saved: MaintenanceSchedule): SeasonStartStrands? {
        val every = schedules.all()
        val after = every.map { if (it.id == saved.id) saved else it }
        val everyGroup = groups.all()
        return openingServiceStrandOf(assetsAimedAt(before, everyGroup), assets.all(), every, everyGroup, after, everyGroup)
    }
}
