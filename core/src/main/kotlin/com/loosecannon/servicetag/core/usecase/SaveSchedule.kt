package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.schedule.BoundaryKind

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
 * - it **clears the postponement** when a rule field actually changed (invariant 19). A title, a
 *   lead or a policy edit leaves it alone: the current occurrence's agreed date has nothing to do
 *   with the words on it.
 * - it **abandons an open, partially complete occurrence** (D-9) — by doing nothing to it. The
 *   member completions already recorded stay exactly as they are, truthful history that no advance
 *   ever rewrites (invariant 14), and the edited rule simply produces the new current occurrence.
 *   There is no tidying step, and adding one would be the bug.
 * - it **moves the pin's floor to the edit date when a rule field changed**, because
 *   `rule_changed_at` is that floor (D-28, #64). Without it, re-anchoring an old never-terminated
 *   schedule would pin it immediately overdue. Any other edit keeps the stored instant, so a title,
 *   lead or policy edit moves nothing (invariant 87). `updated_at` is still stamped on every save:
 *   it is bookkeeping, and the engine never reads it.
 *
 * A group-targeted schedule is refused on a group that would **oblige nobody** — no open window, or
 * none whose Asset's own lifecycle leaves it standing (D-16). Its first round would oblige nobody,
 * and no later membership change rescues it: a member added afterwards joins the round *after* the
 * one already open, and a round that obliges nobody can neither be completed nor closed, so the
 * schedule would report `NO_DATA` for ever (invariants 74, 77). The count is taken at the instant
 * the round this command is about opened — now for a create, the open round's own instant for an
 * edit — so an edit of a schedule whose round is live is never refused for a member who left after
 * that round opened and is still required for it.
 *
 * **The service policy** is refused, after every bad-rule problem, when it is PRE_SERVICE on an asset
 * with neither a calendar season nor a break: 409 [PreServiceNeedsDates], whose remedy is the asset.
 * The policy and its offset are not rule fields (see [ruleChanged]).
 *
 * **The health link guard** (spec §6.1, D-30; inv. 130). While a non-archived health subject depends
 * on the schedule, an edit that removes its time rule or changes its target — to another asset or to
 * a group — is 422 [ScheduleDrivesHealthSubject], naming the subject, unless [run]'s
 * `unlinkHealthSubject` is set: then the subject is archived in the same transaction, or the whole
 * write is 409 [HealthSubjectIsPrimary] when that subject is the one its asset's TRACK_ONE follows
 * (plan decision 14). Any other edit — a title, a lead, a policy, a new cadence — is not guarded, and
 * the flag with nothing to unlink changes nothing. The guard's 422 comes after every bad-rule problem
 * and the target's 404, and before the 409s.
 *
 * One `uow.write`: the guard's read of the subjects, the row, any unlinked subject and the recompute
 * commit together or not at all.
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
    private val healthSubjects: HealthSubjectRepository,
) {
    suspend fun run(id: ScheduleId?, cmd: ScheduleCommand, unlinkHealthSubject: Boolean = false): MaintenanceSchedule {
        val existing = id?.let { schedules.get(it) ?: throw NoSuchSchedule(it) }

        val profileAssetId = cmd.profileId?.let { profiles.get(it)?.assetId }
        val meter = cmd.meterDefinitionId?.let { definitions.get(it) }
        // Resolved before validation, not after, so "this group has no members" is collected with
        // every other bad-rule problem instead of arriving as a second round trip after the editor
        // has already fixed the first batch.
        val now = clock.nowMillis()
        val group = cmd.targetGroupId?.let { groups.get(it) }
        // The **lifecycle-bounded** windows covering the instant the round this command is about
        // would open at, not the raw open ones: a group whose only member is archived, or retired by
        // then, obliges nobody, and the editor has to hear that here rather than store a schedule
        // the engine will report `NO_DATA` for ever. Counted with the same derivation the recompute
        // uses, so the two cannot disagree.
        //
        // On a **create** that instant is now — the first round opens with the schedule. On an
        // **edit of the same group** it is the instant the round already open opened at, because an
        // edit does not open a new round: it changes the rule of the one that is running (D-9). A
        // member removed mid-round is still required for it (D-10), so counting *today's* windows
        // would refuse the edit of a schedule whose round is live and obliges somebody — and leave
        // the owner unable to change its rule at all. A create is still refused, which is the half
        // that keeps a vacuous schedule from ever being stored (invariants 74, 77).
        val obligedAt = existing
            ?.takeIf { it.target == cmd.target() }
            ?.let { recompute.occurrenceOf(it)?.openInstant }
            ?: now
        // **The better of the two instants**, so neither of them can trap the owner. Counting only
        // at the open instant would refuse an edit for ever once a round had gone vacuous — the
        // last member removed after the previous round terminated leaves an open instant that
        // precedes the removal, and no later re-adding moves it — which closes the one door D-9
        // makes the escape hatch, since a recurrence edit is the thing that abandons a stuck
        // occurrence. Counting only at `now` is the refusal carry-forward (f) removed. A group that
        // obliges somebody at *either* instant has somebody to work with; one that obliges nobody
        // at both is the vacuous schedule invariants 74 and 77 refuse. On a create the two are the
        // same instant, so the create refusal is unchanged.
        val obliged = group?.let { bounded ->
            boundedMembers(bounded, assets).let { members ->
                maxOf(members.openAt(obligedAt).size, members.openAt(now).size)
            }
        }
        val problems = scheduleProblems(cmd, profileAssetId, meter, obliged)
        if (problems.isNotEmpty()) throw ScheduleValidation(problems)

        val targetAsset = when (val target = cmd.target()!!) {
            is ScheduleTarget.AssetTarget -> assets.get(target.assetId) ?: throw NoSuchAsset(target.assetId)
            is ScheduleTarget.GroupTarget -> {
                group ?: throw NoSuchGroup(target.groupId)
                null
            }
        }

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
            servicePolicy = cmd.servicePolicy,
            policyOffsetDays = cmd.policyOffsetDays,
            completionMode = cmd.completionMode,
            profileId = cmd.profileId,
            remindersEnabled = cmd.remindersEnabled,
            status = existing?.status ?: ScheduleStatus.ACTIVE,
            postponedDueOn = existing?.postponedDueOn,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            // A create stamps the floor with its own instant; an edit keeps the stored one unless
            // it changed the rule, just below.
            ruleChangedAt = existing?.ruleChangedAt ?: now,
            providers = cmd.providers.sortedBy { it.provider },
        )
        val saved = if (existing != null && ruleChanged(existing, candidate)) {
            candidate.copy(postponedDueOn = null, ruleChangedAt = now)
        } else {
            candidate
        }

        return uow.write {
            // The guard's 422: its remedy is the flag in this very body.
            val driven = if (existing != null && breaksHealthLink(existing, saved)) {
                guardDriven(existing.id, unlinkHealthSubject, healthSubjects)
            } else {
                emptyList()
            }
            // A 409, after every 422: the body is well formed, and the remedy is the asset — give it a
            // season or a break for PRE_SERVICE to count back from (spec §4.3, §9.2; inv. 99).
            if (targetAsset != null && cmd.servicePolicy == ServicePolicy.PRE_SERVICE &&
                targetAsset.boundaryKind() == BoundaryKind.NONE
            ) {
                throw PreServiceNeedsDates(targetAsset.id)
            }
            unlinkDriven(driven, assets, healthSubjects, now)
            schedules.upsert(saved)
            recompute.forSchedule(saved.id)
            saved
        }
    }

    /**
     * Whether this edit would strand a subject the schedule drives (spec §6.1): it removes the time
     * rule the subject's clock counts from, or moves the schedule to another asset or to a group.
     */
    private fun breaksHealthLink(before: MaintenanceSchedule, after: MaintenanceSchedule): Boolean =
        (before.hasTimeRule() && !after.hasTimeRule()) || before.target != after.target

    /**
     * Whether this edit touched the recurrence itself. The lead is deliberately **not** a rule
     * field: it moves when a schedule starts *warning*, not when it is due, so changing it must
     * not throw away an agreed postponement. Neither is the service policy or its offset: they
     * decide when the work is *actionable*, not when it is due (spec §4.3, "Rule fields").
     */
    private fun ruleChanged(before: MaintenanceSchedule, after: MaintenanceSchedule): Boolean =
        before.timeInterval != after.timeInterval ||
            before.timeUnit != after.timeUnit ||
            before.timeBasis != after.timeBasis ||
            before.anchorOn != after.anchorOn ||
            before.meterDefinitionId != after.meterDefinitionId ||
            before.meterInterval != after.meterInterval ||
            before.anchorMeter != after.anchorMeter
}
