package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Creates or edits one schedule: **the recurrence edit** of the operations table, and the only
 * writer of the rule columns.
 *
 * Validation is collected, not fail-fast, so an editor gets every [ScheduleProblem] at once, and
 * every one of them is a bad-rule refusal rather than a refusal about state. The target's Asset or
 * group must exist, for the reason every shipped use case checks its owner: the foreign key would
 * refuse the row anyway, and refusing here says which field is wrong.
 *
 * **What an edit does beyond the rule:**
 *
 * - it **clears the postponement** when a rule field actually changed (invariant 19). A title or a
 *   lead edit leaves it alone: the current occurrence's agreed date has nothing to do with the
 *   words on it.
 * - it **abandons an open, partially complete occurrence** (D-9) — by doing nothing to it. The
 *   member completions already recorded stay exactly as they are, truthful history that no advance
 *   ever rewrites (invariant 14), and the edited rule simply produces the new current occurrence.
 *   There is no tidying step, and adding one would be the bug.
 * - it **moves the pin's floor to the edit date**, because `updated_at` is that floor (spec §2.1).
 *   Without it, re-anchoring an old never-terminated schedule would pin it immediately overdue.
 *   This is why the other operations leave `updated_at` alone: only an edit moves the floor
 *   (invariant 25).
 *
 * A group-targeted schedule is refused on a group with **no open membership**. Its first round would
 * oblige nobody, and no later membership change rescues it: a member added afterwards joins the
 * round *after* the one already open, and a round that obliges nobody can neither be completed nor
 * closed, so the schedule would report `NO_DATA` for ever (invariants 74, 77).
 *
 * One `uow.write`: the row and the recompute commit together or not at all.
 */
class SaveSchedule(
    private val schedules: ScheduleRepository,
    private val assets: AssetRepository,
    private val groups: GroupRepository,
    private val definitions: DefinitionRepository,
    private val profiles: ProfileRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val recompute: RecomputeSchedules,
) {
    suspend fun run(id: ScheduleId?, cmd: ScheduleCommand): MaintenanceSchedule {
        val existing = id?.let { schedules.get(it) ?: throw NoSuchSchedule(it) }

        val profileAssetId = cmd.profileId?.let { profiles.get(it)?.assetId }
        val meter = cmd.meterDefinitionId?.let { definitions.get(it) }
        // Resolved before validation, not after, so "this group has no members" is collected with
        // every other bad-rule problem instead of arriving as a second round trip after the editor
        // has already fixed the first batch.
        val group = cmd.targetGroupId?.let { groups.get(it) }
        val problems = scheduleProblems(cmd, profileAssetId, meter, group?.openMembers()?.size)
        if (problems.isNotEmpty()) throw ScheduleValidation(problems)

        when (val target = cmd.target()!!) {
            is ScheduleTarget.AssetTarget -> assets.get(target.assetId) ?: throw NoSuchAsset(target.assetId)
            is ScheduleTarget.GroupTarget -> group ?: throw NoSuchGroup(target.groupId)
        }

        val now = clock.nowMillis()
        val candidate = MaintenanceSchedule(
            id = existing?.id ?: ScheduleId(ids.newId()),
            target = cmd.target()!!,
            title = cmd.title.trim(),
            description = cmd.description.trim(),
            timeInterval = cmd.timeInterval,
            timeUnit = cmd.timeUnit,
            timeBasis = cmd.timeBasis,
            anchorOn = cmd.anchorOn,
            leadDays = cmd.leadDays,
            meterDefinitionId = cmd.meterDefinitionId,
            meterInterval = cmd.meterInterval,
            anchorMeter = cmd.anchorMeter,
            meterLead = cmd.meterLead,
            seasonBehavior = cmd.seasonBehavior,
            seasonReentry = cmd.seasonReentry,
            seasonReentryOffsetDays = cmd.seasonReentryOffsetDays,
            completionMode = cmd.completionMode,
            profileId = cmd.profileId,
            remindersEnabled = cmd.remindersEnabled,
            status = existing?.status ?: ScheduleStatus.ACTIVE,
            postponedDueOn = existing?.postponedDueOn,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            providers = cmd.providers.sortedBy { it.provider },
        )
        val saved = if (existing != null && ruleChanged(existing, candidate)) {
            candidate.copy(postponedDueOn = null)
        } else {
            candidate
        }

        uow.write {
            schedules.upsert(saved)
            recompute.forSchedule(saved.id)
        }
        return saved
    }

    /**
     * Whether this edit touched the recurrence itself. The lead is deliberately **not** a rule
     * field: it moves when a schedule starts *warning*, not when it is due, so changing it must
     * not throw away an agreed postponement.
     */
    private fun ruleChanged(before: MaintenanceSchedule, after: MaintenanceSchedule): Boolean =
        before.timeInterval != after.timeInterval ||
            before.timeUnit != after.timeUnit ||
            before.timeBasis != after.timeBasis ||
            before.anchorOn != after.anchorOn ||
            before.meterDefinitionId != after.meterDefinitionId ||
            before.meterInterval != after.meterInterval ||
            before.anchorMeter != after.anchorMeter ||
            before.seasonBehavior != after.seasonBehavior
}
