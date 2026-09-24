package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Creates or edits one health subject (spec §6.1; master plan §10.1): **configuration only** — it
 * writes the `health_subject` row and nothing else, and never a health value (inv. 82).
 *
 * Two methods, so a subject cannot change asset (plan decision 34, inv. 120): [create] names the
 * asset, [update] keeps the stored one and has no way to be told another.
 *
 * The body is checked first, every problem collected into one 422 [HealthValidation], in spec §6.1's
 * order: the name (1–60 after trimming), the three thresholds (required, `0 ≤ t1 < t2 < t3 ≤ 36,500`,
 * never defaulted — inv. 121), the driver and **the whole link** ([linkProblems]: an archived,
 * retargeted, group-aimed, absent or rule-less schedule is refused), the baseline profile, the
 * weight (1–10). Then the state, 409 [HealthScheduleTaken], when the schedule already drives another
 * non-archived subject.
 *
 * A create appends to the asset's list when no `sortOrder` is given; an edit keeps the stored one.
 * The check and the write share one `uow.write`.
 */
class SaveHealthSubject(
    private val subjects: HealthSubjectRepository,
    private val assets: AssetRepository,
    private val schedules: ScheduleRepository,
    private val profiles: ProfileRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun create(assetId: AssetId, cmd: HealthSubjectCommand): HealthSubject = uow.write {
        assets.get(assetId) ?: throw NoSuchAsset(assetId)
        val clean = checked(assetId, self = null, cmd)
        val now = clock.nowMillis()
        val sortOrder = clean.sortOrder ?: ((subjects.forAsset(assetId).maxOfOrNull { it.sortOrder } ?: -1) + 1)
        val subject = HealthSubject(
            id = HealthSubjectId(ids.newId()),
            assetId = assetId,
            name = clean.name,
            kind = clean.kind,
            driver = clean.driver,
            scheduleId = clean.scheduleId,
            baselineProfileId = clean.baselineProfileId,
            nominalUntilDays = clean.nominalUntilDays!!,
            warningFromDays = clean.warningFromDays!!,
            criticalFromDays = clean.criticalFromDays!!,
            weight = clean.weight,
            sortOrder = sortOrder,
            archivedAt = null,
            createdAt = now,
            updatedAt = now,
        )
        subjects.upsert(subject)
        subject
    }

    suspend fun update(id: HealthSubjectId, cmd: HealthSubjectCommand): HealthSubject = uow.write {
        val existing = subjects.get(id) ?: throw NoSuchHealthSubject(id)
        val clean = checked(existing.assetId, self = id, cmd)
        val saved = existing.copy(
            name = clean.name,
            kind = clean.kind,
            driver = clean.driver,
            scheduleId = clean.scheduleId,
            baselineProfileId = clean.baselineProfileId,
            nominalUntilDays = clean.nominalUntilDays!!,
            warningFromDays = clean.warningFromDays!!,
            criticalFromDays = clean.criticalFromDays!!,
            weight = clean.weight,
            sortOrder = clean.sortOrder ?: existing.sortOrder,
            updatedAt = clock.nowMillis(),
        )
        subjects.upsert(saved)
        saved
    }

    /** The trimmed command, or the 422 and then the 409; [self] is the subject being edited. */
    private suspend fun checked(assetId: AssetId, self: HealthSubjectId?, cmd: HealthSubjectCommand): HealthSubjectCommand {
        val clean = cmd.copy(name = cmd.name.trim())
        val problems = listOfNotNull(
            subjectNameProblem(clean.name),
            thresholdsProblem(clean.nominalUntilDays, clean.warningFromDays, clean.criticalFromDays),
        ) + linkProblems(assetId, clean.driver, clean.scheduleId, clean.baselineProfileId, schedules) +
            listOfNotNull(
                baselineProblem(assetId, clean.driver, clean.baselineProfileId, profiles),
                weightProblem(clean.weight),
            )
        if (problems.isNotEmpty()) throw HealthValidation(problems)
        requireScheduleFree(clean.scheduleId, self, subjects)
        return clean
    }
}
