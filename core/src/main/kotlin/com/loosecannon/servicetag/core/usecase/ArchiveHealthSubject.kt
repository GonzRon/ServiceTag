package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Archives a health subject, or restores it (spec §6.1, §6.5; master plan §10.1). Archiving is the
 * only way a subject leaves: nothing deletes one, and only its asset's or its schedule's CASCADE
 * removes the row.
 *
 * - **Archiving** the subject its asset's TRACK_ONE follows is 409 [HealthSubjectIsPrimary] (S137):
 *   the remedy is the aggregation first.
 * - **Restoring** re-runs **the whole link validation** a create runs ([linkProblems]; plan decision
 *   45): the schedule must still exist, be this asset's, be asset-targeted, have a time rule and not be
 *   archived, or the restore is 422 [HealthValidation] — so archiving a driving schedule with
 *   `unlinkHealthSubject` and then restoring the subject is refused, and no non-archived subject ever
 *   names an archived, retargeted or rule-less schedule locally. Then 409 [HealthScheduleTaken] when
 *   another non-archived subject has taken the schedule meanwhile (inv. 120).
 *
 * A request that changes nothing — archiving an archived subject, restoring a live one — writes
 * nothing. The check and the write share one `uow.write`, so a schedule archived, retargeted or
 * de-ruled by another writer between the editor's check and this tap is still refused here.
 */
class ArchiveHealthSubject(
    private val subjects: HealthSubjectRepository,
    private val assets: AssetRepository,
    private val schedules: ScheduleRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
) {
    suspend fun run(id: HealthSubjectId, archived: Boolean): HealthSubject = uow.write {
        val existing = subjects.get(id) ?: throw NoSuchHealthSubject(id)
        if ((existing.archivedAt != null) == archived) return@write existing

        if (archived) {
            if (assets.get(existing.assetId)?.followsOnly(id) == true) throw HealthSubjectIsPrimary(id)
        } else {
            val problems = linkProblems(
                existing.assetId, existing.driver, existing.scheduleId, existing.baselineProfileId, schedules,
            )
            if (problems.isNotEmpty()) throw HealthValidation(problems)
            requireScheduleFree(existing.scheduleId, id, subjects)
        }

        val now = clock.nowMillis()
        val saved = existing.copy(archivedAt = if (archived) now else null, updatedAt = now)
        subjects.upsert(saved)
        saved
    }
}
