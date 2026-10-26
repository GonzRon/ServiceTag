package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
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
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonRefusal
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonRefused

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
 * **The Season Start role** (#114, C11, C12). The form carries no role, so a save keeps the stored role and its
 * boundary and a create has none. An edit of a role schedule that changes its target, or its completion mode from
 * FORM to QUICK without [run]'s `seasonStartQuickElected`, is 409 [SeasonStartStrands], after the PRE_SERVICE 409.
 * Both read the row as stored **inside** the write, so a role set after [run]'s first read is kept and judged, never
 * wiped.
 *
 * **A schedule on its own season** (#125, H1b). A retarget to a group of a schedule following its own Home Assistant
 * season, linked or stranded, is 409 [ScheduleSeasonRefused] `GROUP_TARGET` (inv. 106), after the role's strand: the
 * remedy is the unlink. A move to another asset keeps the link.
 *
 * **Counting from the opening service** (#124, C42). Last, a save that chooses `IN_SERVICE_AFTER_OPENING_SERVICE` — a
 * create, an edit into it, or a retarget while it holds it — is 409 [SeasonStartRefused]: `COUNTS_FROM_ITSELF` on a role
 * schedule, `YEAR_ROUND` on an asset in use all year, `NO_SEASON_START_SERVICE` on an asset no live role covers (C9's
 * coverage, a group's role included). [createInTransaction] (Replace asset) refuses alike.
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
    suspend fun run(
        id: ScheduleId?,
        cmd: ScheduleCommand,
        unlinkHealthSubject: Boolean = false,
        seasonStartQuickElected: Boolean = false,
    ): MaintenanceSchedule {
        val prepared = prepare(id, cmd)
        return uow.write { commit(prepared, cmd, unlinkHealthSubject, seasonStartQuickElected) }
    }

    /**
     * #86 (C15): the create path — the same reads, refusals and write as [run] with no id — inside the caller's
     * transaction, so `ReplaceAsset` copies a schedule in its own one write.
     */
    internal suspend fun createInTransaction(cmd: ScheduleCommand): MaintenanceSchedule =
        commit(prepare(null, cmd), cmd, unlinkHealthSubject = false, seasonStartQuickElected = false)

    /** What [run] reads and decides before its write: the stored row, the target asset and the row to save. */
    private class Prepared(
        val existing: MaintenanceSchedule?,
        val targetAsset: Asset?,
        val saved: MaintenanceSchedule,
        val now: Long,
    )

    private suspend fun prepare(id: ScheduleId?, cmd: ScheduleCommand): Prepared {
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
            // #114 (C12): the command never carries a role (K11), so a save keeps the stored one and a create —
            // `ReplaceAsset`'s copy among them — has none.
            role = existing?.role ?: ScheduleRole.NONE,
            roleSinceOn = existing?.roleSinceOn,
            // #125 (U-C2): no command carries the source either; a save keeps it and a create follows its asset.
            seasonSource = existing?.seasonSource ?: ScheduleSeasonSource.ASSET,
        )
        val saved = if (existing != null && ruleChanged(existing, candidate)) {
            candidate.copy(postponedDueOn = null, ruleChangedAt = now)
        } else {
            candidate
        }
        return Prepared(existing, targetAsset, saved, now)
    }

    /** [run]'s write body: the guard, the 409, the unlink, the row and its recompute. */
    private suspend fun commit(
        prepared: Prepared,
        cmd: ScheduleCommand,
        unlinkHealthSubject: Boolean,
        seasonStartQuickElected: Boolean,
    ): MaintenanceSchedule {
        val existing = prepared.existing
        val targetAsset = prepared.targetAsset
        // The role is the stored row's as of this write (M-3): a role set since `prepare` read is neither wiped nor
        // left unjudged.
        val stored = existing?.let { schedules.get(it.id) }
        val saved = prepared.saved.copy(
            role = stored?.role ?: ScheduleRole.NONE,
            roleSinceOn = stored?.roleSinceOn,
            seasonSource = stored?.seasonSource ?: ScheduleSeasonSource.ASSET,
        )
        // The guard's 422: its remedy is the flag in this very body.
        val driven = if (existing != null && breaksHealthLink(existing, saved)) {
            guardDriven(existing.id, unlinkHealthSubject, healthSubjects)
        } else {
            emptyList()
        }
        // A 409, after every 422: the body is well formed, and the remedy is the asset — give it a
        // season or a break for PRE_SERVICE to count back from (spec §4.3, §9.2; inv. 99). #125: read through the
        // schedule's effective season, so an `OWN` schedule counts back only from the asset's break.
        if (targetAsset != null && cmd.servicePolicy == ServicePolicy.PRE_SERVICE &&
            targetAsset.boundaryKindFor(saved.seasonSource) == BoundaryKind.NONE
        ) {
            throw PreServiceNeedsDates(targetAsset.id)
        }
        if (stored != null) seasonStartStrandOf(stored, saved, seasonStartQuickElected)?.let { throw it }
        // #125 (H1b): a schedule following its own season cannot become a group's, which reads no season (inv. 106).
        // The stored source decides, read inside this write as the role is; the remedy is the unlink first.
        if (saved.seasonSource == ScheduleSeasonSource.OWN && saved.target is ScheduleTarget.GroupTarget) {
            throw ScheduleSeasonRefused(ScheduleSeasonRefusal.GROUP_TARGET)
        }
        // #124 (C42): a save choosing to count from the opening service needs a live role to count from, never itself.
        if (choosesOpeningService(stored, saved)) {
            openingServiceRefusalOf(saved, targetAsset, schedules.all(), groups.all())?.let { throw SeasonStartRefused(it) }
        }
        unlinkDriven(driven, assets, healthSubjects, prepared.now)
        schedules.upsert(saved)
        recompute.forSchedule(saved.id)
        return saved
    }

    /**
     * #114 (C11) — what an edit of a live or archived role schedule may not do: change its target (O-6,
     * `ROLE_SCHEDULE_RETARGET`; clear the role first), or go from FORM to QUICK without the owner's election (O-2,
     * F-22, `QUICK_NEEDS_ELECTION`). Null for any other edit, and for every edit of a schedule with no role.
     */
    private fun seasonStartStrandOf(
        before: MaintenanceSchedule,
        after: MaintenanceSchedule,
        quickElected: Boolean,
    ): SeasonStartStrands? {
        if (before.role != ScheduleRole.SEASON_START) return null
        val cause = if (before.target != after.target) {
            SeasonStartStrandCause.ROLE_SCHEDULE_RETARGET
        } else if (before.completionMode == CompletionMode.FORM && after.completionMode == CompletionMode.QUICK && !quickElected) {
            SeasonStartStrandCause.QUICK_NEEDS_ELECTION
        } else {
            return null
        }
        val assetId = (before.target as? ScheduleTarget.AssetTarget)?.assetId
        return SeasonStartStrands(assetId, cause, listOf(StrandedSchedule(before.id, before.title)))
    }

    /**
     * #124 (C42) — whether this save **chooses** to count from the opening service: a create with it, an edit into it, or
     * an edit that moves a schedule holding it to another target. An edit that keeps both is not judged again (an
     * already-broken row blocks no unrelated edit).
     */
    private fun choosesOpeningService(before: MaintenanceSchedule?, after: MaintenanceSchedule): Boolean =
        after.servicePolicy == ServicePolicy.IN_SERVICE_AFTER_OPENING_SERVICE &&
            (before == null || before.servicePolicy != after.servicePolicy || before.target != after.target)

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
