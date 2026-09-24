package com.loosecannon.servicetag.core.reminders

import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.ScheduleStateRepository
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.schedule.statusOf
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import java.time.LocalDate

/**
 * The desired state of every provider's list, derived from schedule state and **nothing a provider
 * remembers**.
 *
 * That is the load-bearing property: the answer is a function of configuration, derived due state
 * and today, so a provider that has lost every scrap of its own bookkeeping is handed the same list
 * it would have been handed otherwise and can rebuild itself from nothing. Take one fact about what
 * a provider currently holds as an input and that stops being true — which is why this class holds
 * read ports only, and why it never writes derived state: the recompute is the one writer and this
 * is one of its readers.
 *
 * The list is **not** filtered by `listedForDue`: a retired obligation arrives as
 * [SubjectState.Withdrawn] so a provider is told to let go of it, rather than disappearing and
 * leaving the provider to infer that from an absence. What is genuinely absent is a schedule nobody
 * asked to be reminded about — reminders switched off, or no enabled row for this provider.
 */
class BuildReminderSubjects(
    private val schedules: ScheduleRepository,
    /**
     * Held for the graph's constructor shape only. State is read through
     * [RecomputeSchedules.readState], which derives a stale or missing row instead of skipping it,
     * so this class never reads the table directly.
     */
    @Suppress("unused") private val states: ScheduleStateRepository,
    private val groups: GroupRepository,
    /**
     * Held for the graph's constructor shape only: nothing about an Asset is read here any more.
     * Where a parked subject comes back is the policy's answer, carried on the state.
     */
    @Suppress("unused") private val assets: AssetRepository,
    private val occurrences: RecomputeSchedules,
) {

    /**
     * The subjects [provider] should be holding on [today].
     *
     * The provider is a **parameter**, which is what makes a second one additive: nothing about the
     * shape of this answer, or of the port that consumes it, knows how many there are. The order is
     * by schedule id — a stable order is what lets two consecutive answers be compared at all.
     */
    suspend fun forProvider(provider: ProviderId, today: LocalDate): List<ReminderSubject> =
        schedules.all()
            .filter { it.remindersEnabled && it.isEnabledFor(provider) }
            .sortedBy { it.id.value }
            .mapNotNull { subjectOf(it, today) }

    /**
     * One list per provider, keyed by the provider.
     *
     * **Every** provider is keyed, including one with no enabled row anywhere, because `reconcile`
     * is the whole write surface: a provider that is not asked is a provider left holding whatever
     * it held yesterday, and an empty list is how it is told to let go of all of it.
     */
    suspend fun all(today: LocalDate): Map<ProviderId, List<ReminderSubject>> =
        ProviderId.entries.associateWith { forProvider(it, today) }

    /** A provider row is a set membership, matched by name: an unknown name is nobody's row. */
    private fun MaintenanceSchedule.isEnabledFor(provider: ProviderId): Boolean =
        providers.any { it.enabled && it.provider == provider.name }

    /**
     * One subject, or none.
     *
     * None in exactly one case: a schedule whose group has been archived is hidden with its group,
     * and that filter is answered here because `archived_at` is not an input to derived state and
     * archiving deliberately recomputes nothing — which view hides an archived group is the view's
     * own question.
     *
     * State comes from [RecomputeSchedules.readState]: the stored row when it is today's, otherwise
     * derived in memory and never written (invariant 105). A missing or stale row is therefore
     * **derived, never skipped** — skipping it would drop a real obligation from the provider's list
     * for as long as the table lagged, and trusting a stale one would report yesterday's season.
     *
     * An **Active** subject's date is the **actionable** date, the one its status word is measured
     * against, so a provider never says "overdue since" a date the status was not judged by. A
     * withdrawn subject keeps the effective date it was last shown with; a parked one has none.
     */
    private suspend fun subjectOf(schedule: MaintenanceSchedule, today: LocalDate): ReminderSubject? {
        if (targetGroupIsArchived(schedule)) return null
        val state = occurrences.readState(schedule)
        // The fail-safe PAUSED an archived row folds to is never asked for: withdrawal comes first.
        val status = statusOf(schedule, state, today)

        val subjectState = subjectStateOf(schedule, state, status)
        val dueOn = when (subjectState) {
            is SubjectState.Parked -> null
            SubjectState.Active -> state.actionableDueOn?.let(LocalDate::parse)
            SubjectState.Completed, SubjectState.Withdrawn -> state.effectiveDueOn?.let(LocalDate::parse)
        }
        val rule = ruleFactsOf(schedule)
        val body = bodyOf(schedule, state, status, subjectState)
        return ReminderSubject(
            key = SubjectKey.Schedule(schedule.id),
            title = schedule.title,
            body = body,
            dueOn = dueOn,
            leadDays = schedule.leadDays,
            state = subjectState,
            rule = rule,
            contentHash = ContentHash.of(schedule.title, body, dueOn, schedule.leadDays, subjectState, rule),
        )
    }

    private suspend fun targetGroupIsArchived(schedule: MaintenanceSchedule): Boolean {
        val target = schedule.target as? ScheduleTarget.GroupTarget ?: return false
        return groups.get(target.groupId)?.archivedAt != null
    }

    /**
     * Parked, not absent — spec §4.7's table, row by row, mapped from what the engine already
     * derived and nothing this class works out for itself.
     *
     * A paused schedule, a dormant one, a deferred one and a quiet one with something to say are all
     * obligations that have not gone away, and filtering any of them out of the list is how a
     * provider comes to keep something standing with nothing left to clear it (invariant 47). The
     * order of the pause and season questions is the order [statusOf] asks them in, so the state and
     * the status word can never disagree about which one applies.
     *
     * - ARCHIVED → [SubjectState.Withdrawn], asked **first**, so a retired obligation is never
     *   reported parked and never reported active.
     * - PAUSED → parked with no date: a pause has none.
     * - DORMANT → parked until the actionable date, the day the season lets it back in — none for
     *   a MANUAL asset, whose next START is never predicted.
     * - DEFERRED → parked until the actionable date the break is holding it for.
     * - quiet with a status that would notify → parked until the first day after the break, which
     *   the recompute evaluates on request and stores nowhere (invariants 22, 102).
     * - otherwise [SubjectState.Active].
     */
    private suspend fun subjectStateOf(
        schedule: MaintenanceSchedule,
        state: ScheduleState,
        status: DueStatus,
    ): SubjectState = when {
        schedule.status == ScheduleStatus.ARCHIVED -> SubjectState.Withdrawn
        schedule.status == ScheduleStatus.PAUSED -> SubjectState.Parked(null)
        state.policyPhase == PolicyPhase.DORMANT -> SubjectState.Parked(state.actionableDueOn?.let(LocalDate::parse))
        status == DueStatus.DEFERRED -> SubjectState.Parked(state.actionableDueOn?.let(LocalDate::parse))
        state.quiet && status.notifies -> SubjectState.Parked(occurrences.quietUntil(schedule))
        else -> SubjectState.Active
    }

    private fun ruleFactsOf(schedule: MaintenanceSchedule): RuleFacts {
        val hasSeries = schedule.timeInterval != null
        return RuleFacts(
            basis = if (hasSeries) schedule.timeBasis else null,
            interval = schedule.timeInterval,
            unit = if (hasSeries) schedule.timeUnit else null,
            hasMeter = schedule.meterDefinitionId != null,
            seasonal = schedule.servicePolicy != ServicePolicy.CONTINUOUS,
        )
    }

    /**
     * What a provider shows beside the title, composed from already-ratified material only: the
     * status word, and for a group the progress of its current round. No sentence is drafted here
     * and no connective word is added — the fragments are joined by a separator, and a provider's
     * own wrapper wording is the provider's.
     *
     * A group is **one** subject with its progress in this line, never one per member, which is what
     * keeps an obligation counted once.
     */
    private suspend fun bodyOf(
        schedule: MaintenanceSchedule,
        state: ScheduleState,
        status: DueStatus,
        subjectState: SubjectState,
    ): String {
        // A subject the provider is being told to let go of has nothing to show, and there is no
        // ratified word for "withdrawn" to show it with. The derived status word is not shown
        // either: for an archived row it is the fail-safe `PAUSED`, which would be a lie here.
        if (subjectState.isCleared) return ""
        return listOfNotNull(
            statusTerm(status, schedule, state),
            progressOf(schedule),
        ).joinToString(SEPARATOR)
    }

    /**
     * The ratified status word for a derived status.
     *
     * `NO_DATA` is the one case with a condition on it. The word covers the **repairable missing
     * baseline** and only that; the other way a schedule reports `NO_DATA` is a round with nobody
     * required in it, which is not actionable and must never be told it needs a reading merely
     * because its status enum happens to read the same. That case gets no word at all.
     */
    private fun statusTerm(
        status: DueStatus,
        schedule: MaintenanceSchedule,
        state: ScheduleState,
    ): String? = when (status) {
        DueStatus.OK -> "OK"
        DueStatus.DUE_SOON -> "DUE SOON"
        DueStatus.DUE -> "DUE"
        DueStatus.OVERDUE -> "OVERDUE"
        DueStatus.INACTIVE_SEASON -> "OUT OF SEASON"
        DueStatus.PAUSED -> "PAUSED"
        DueStatus.NO_DATA ->
            if (schedule.meterDefinitionId != null && state.computedDueMeter == null) "NO BASELINE" else null
        DueStatus.DEFERRED -> "DEFERRED"
    }

    /**
     * "3 of 5 complete" for a group-targeted schedule's current round, and nothing for an
     * asset-targeted one or a round with nobody in it.
     *
     * The round comes from the recompute rather than from a membership list read here, because the
     * occurrence derivation requires windows already bounded by each member Asset's lifecycle: an
     * unbounded list yields a required set that silently disagrees with derived state.
     */
    private suspend fun progressOf(schedule: MaintenanceSchedule): String? {
        if (schedule.target !is ScheduleTarget.GroupTarget) return null
        val occurrence = occurrences.occurrenceOf(schedule) ?: return null
        if (!occurrence.isActionable) return null
        val (done, total) = occurrence.progress
        return "$done of $total complete"
    }

    private companion object {
        /** A separator, not a word: the fragments either side of it are each ratified on their own. */
        const val SEPARATOR = " · "
    }
}
