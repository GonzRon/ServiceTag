package com.loosecannon.servicetag.core.reminders

import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.ScheduleStateRepository
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.schedule.statusOf
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import java.time.DateTimeException
import java.time.LocalDate
import java.time.Year

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
    private val states: ScheduleStateRepository,
    private val groups: GroupRepository,
    private val assets: AssetRepository,
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
     * None in two cases, both of which mean "there is nothing here to hold". A schedule whose group
     * has been archived is hidden with its group, and that filter is answered here because
     * `archived_at` is not an input to derived state and archiving deliberately recomputes nothing —
     * which view hides an archived group is the view's own question. And a schedule with no derived
     * state row has nothing to project from: the recompute runs after every write that could create
     * one, so a consistent store never has one, and deriving a due date here instead would make this
     * a second recurrence engine.
     */
    private suspend fun subjectOf(schedule: MaintenanceSchedule, today: LocalDate): ReminderSubject? {
        if (targetGroupIsArchived(schedule)) return null
        val state = states.get(schedule.id) ?: return null

        val subjectState = subjectStateOf(schedule, state, today)
        val dueOn = when (subjectState) {
            is SubjectState.Parked -> null
            SubjectState.Active, SubjectState.Completed, SubjectState.Withdrawn ->
                state.effectiveDueOn?.let(LocalDate::parse)
        }
        val rule = ruleFactsOf(schedule)
        val body = bodyOf(schedule, state, subjectState, today)
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
     * Parked, not absent.
     *
     * A paused schedule and a seasonally inactive one are both obligations that have not gone away,
     * and filtering either out of the list is how a provider comes to keep something standing with
     * nothing left to clear it. The order of the season and pause questions is the order [statusOf]
     * asks them in, so the state and the status word can never disagree about which one applies.
     *
     * Withdrawal is asked **first**, so a retired obligation is never reported parked and never
     * reported active — a schedule the owner has archived has no season and no pause worth naming.
     */
    private suspend fun subjectStateOf(
        schedule: MaintenanceSchedule,
        state: ScheduleState,
        today: LocalDate,
    ): SubjectState = when {
        schedule.status == ScheduleStatus.ARCHIVED -> SubjectState.Withdrawn
        schedule.status == ScheduleStatus.PAUSED -> SubjectState.Parked(null)
        !state.seasonActive -> SubjectState.Parked(seasonReentryOn(schedule, today))
        else -> SubjectState.Active
    }

    /**
     * The season's next start date, so a provider can say when a parked subject comes back without
     * knowing what a season is. Null when there is no window to read — a schedule that follows no
     * season is never parked this way, and a half-set window is read as year-round exactly as the
     * engine reads it.
     */
    private suspend fun seasonReentryOn(schedule: MaintenanceSchedule, today: LocalDate): LocalDate? {
        if (schedule.seasonBehavior != SeasonBehavior.FOLLOW_ASSET) return null
        val target = schedule.target as? ScheduleTarget.AssetTarget ?: return null
        val start = assets.get(target.assetId)?.seasonStartMmdd ?: return null
        return nextOnOrAfter(start, today)
    }

    private fun ruleFactsOf(schedule: MaintenanceSchedule): RuleFacts {
        val hasSeries = schedule.timeInterval != null
        return RuleFacts(
            basis = if (hasSeries) schedule.timeBasis else null,
            interval = schedule.timeInterval,
            unit = schedule.timeUnit,
            hasMeter = schedule.meterDefinitionId != null,
            seasonal = schedule.seasonBehavior == SeasonBehavior.FOLLOW_ASSET,
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
        subjectState: SubjectState,
        today: LocalDate,
    ): String {
        val cleared = when (subjectState) {
            SubjectState.Active, is SubjectState.Parked -> false
            SubjectState.Completed, SubjectState.Withdrawn -> true
        }
        // A subject the provider is being told to let go of has nothing to show, and there is no
        // ratified word for "withdrawn" to show it with. The derived status word is not asked for
        // either: for an archived row it is the fail-safe `PAUSED`, which would be a lie here.
        if (cleared) return ""
        return listOfNotNull(
            statusTerm(statusOf(schedule, state, today), schedule, state),
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

    /**
     * The first [mmdd] on or after [today], this year or next. A February 29 window behaves as
     * February 28 in a common year, exactly as the season check reads it.
     */
    private fun nextOnOrAfter(mmdd: String, today: LocalDate): LocalDate? {
        if (mmdd.length != 5) return null
        val month = mmdd.substring(0, 2).toIntOrNull() ?: return null
        val day = mmdd.substring(3, 5).toIntOrNull() ?: return null
        return listOf(today.year, today.year + 1)
            .mapNotNull { year -> dayIn(year, month, day) }
            .firstOrNull { !it.isBefore(today) }
    }

    private fun dayIn(year: Int, month: Int, day: Int): LocalDate? = try {
        val clamped = if (month == 2 && day == 29 && !Year.isLeap(year.toLong())) 28 else day
        LocalDate.of(year, month, clamped)
    } catch (e: DateTimeException) {
        null
    }

    private companion object {
        /** A separator, not a word: the fragments either side of it are each ratified on their own. */
        const val SEPARATOR = " · "
    }
}
