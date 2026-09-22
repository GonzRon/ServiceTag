package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.isRetired
import java.time.LocalDate

/**
 * The **derived** status word, computed at read time from the row plus today and stored nowhere.
 *
 * It is called `DueStatus` and not `ScheduleStatus` because [ScheduleStatus] is already the stored
 * lifecycle column (`ACTIVE | PAUSED | ARCHIVED`), and one name for two different things is exactly
 * how a derived status ends up in a column by accident. The two names are also what makes the
 * structural grep for "no stored status" possible at all.
 *
 * `OVERDUE > DUE > DUE_SOON > OK` is the worst-of order the combined rule folds the two sides with;
 * the four words after them are not degrees of the same thing and never take part in that fold.
 */
enum class DueStatus {
    OK, DUE_SOON, DUE, OVERDUE, INACTIVE_SEASON, PAUSED, NO_DATA;

    /**
     * Whether this status puts the schedule in a due total. `NO_DATA` is a repair, not an
     * obligation.
     *
     * This is **not** the dashboard's "actionable", which is a wider set — a status in ATTENTION or
     * UPCOMING, so `DUE_SOON` as well, and `NO_DATA` in its repairable meter-baseline form. That
     * predicate is surface-specific and belongs to the surface; wiring this one into a promotion
     * rule would quietly narrow it.
     */
    val countsAsDue: Boolean get() = this == DUE || this == OVERDUE

    /**
     * Whether a reminder provider may deliver for it. `INACTIVE_SEASON` and `PAUSED` never notify
     * (invariant 22), and the snooze does not appear here at all: it suppresses delivery without
     * changing what the schedule's status is.
     */
    val notifies: Boolean get() = this == DUE_SOON || this == DUE || this == OVERDUE
}

/**
 * The schedules every **due** list and dashboard section is built from: an archived schedule is out
 * of all of them, and this is the one place that filter lives.
 *
 * **Not the reminder subject builder.** That one read side deliberately does *not* apply this
 * filter: it reads every schedule, and an ARCHIVED one arrives as a `Withdrawn` subject, because a
 * provider has to be **told** to let go of something it is holding and cannot infer it from an
 * absence (spec §2.5). Filtering here and there would leave a standing reminder for retired
 * equipment with nothing left to clear it.
 */
fun List<MaintenanceSchedule>.listedForDue(): List<MaintenanceSchedule> =
    filter { it.status != ScheduleStatus.ARCHIVED }

/**
 * Whether what this schedule is aimed at is still **in service** — #5 AC 3's bound, D-16's
 * lifecycle rule — and the second of the two bounds every surface that answers "what needs
 * attention" applies. [listedForDue] drops an archived *schedule*; this drops a live schedule on a
 * thing that has left service.
 *
 * An out-of-service target's obligations are not what "needs attention" means, and they are not
 * merely hidden: `BuildReminderSubjects` hands a retired obligation to the provider as
 * `SubjectState.Withdrawn`, so the app has already **stopped trying to deliver** it. A surface that
 * reported a delivery problem for one would be reporting a problem nothing is trying to solve.
 *
 * **One function, not one per surface** (master plan decision 27): a second copy of this predicate
 * is the drift that decision exists to prevent, and it is the reason this is here rather than
 * re-derived beside each caller. The lookups are parameters rather than repositories so this stays
 * pure and testable, and so a caller that has already read every asset and group once — as every
 * caller does — pays for one read and not one per schedule.
 *
 * **Transcribed unchanged** from the one place that already had it (`DueReadModel`'s `World`), so
 * this is a move and not a new rule — including its one asymmetry: a **missing asset** answers
 * false while a **missing group** answers true. Both are unreachable in the real store, because
 * `maintenance_schedule`'s `asset_id` and `group_id` foreign keys are each `CASCADE`, so a schedule
 * cannot outlive either target. Recorded rather than quietly normalised: changing it would change
 * the shipped projection's answer, which is not a thing to do while moving code.
 */
fun MaintenanceSchedule.targetInService(
    assetOf: (AssetId) -> Asset?,
    groupOf: (GroupId) -> MaintenanceGroup?,
): Boolean = when (val aim = target) {
    is ScheduleTarget.AssetTarget ->
        assetOf(aim.assetId)?.let { it.status == AssetStatus.ACTIVE && !it.isRetired } == true
    is ScheduleTarget.GroupTarget -> groupOf(aim.groupId)?.archivedAt == null
}

/**
 * `status(schedule, state, T)` of D5 §1 — a pure function, never stored (invariant 18).
 *
 * The order of the three early answers is the order of the questions a person would ask: has this
 * been switched off, is its season shut, is there anything to measure against at all. Only then are
 * the two rule sides folded, worst-of.
 *
 * An **archived** schedule is excluded from every list before a status is ever asked for (see
 * [listedForDue]); if one arrives here anyway the answer is [DueStatus.PAUSED] — the one word that
 * neither counts as due nor notifies — because a total function that fails safe is better than one
 * that throws inside a read model, and a due word for an archived row would be a lie.
 *
 * `seasonActive` is read off the state rather than recomputed here: the window lives on the Asset
 * and this function has no Asset. The daily rebuild is what keeps it fresh, and a boundary crossed
 * between rebuilds is the one exception invariant 23 names.
 */
fun statusOf(schedule: MaintenanceSchedule, state: ScheduleState, today: LocalDate): DueStatus {
    if (schedule.status != ScheduleStatus.ACTIVE) return DueStatus.PAUSED
    if (!state.seasonActive) return DueStatus.INACTIVE_SEASON

    val timeEvaluable = schedule.timeInterval != null && state.effectiveDueOn != null
    val meterEvaluable = schedule.meterDefinitionId != null && state.computedDueMeter != null
    if (!timeEvaluable && !meterEvaluable) return DueStatus.NO_DATA

    val time = if (!timeEvaluable) DueStatus.OK else timeStatus(
        due = LocalDate.parse(state.effectiveDueOn),
        today = today,
        leadDays = schedule.leadDays,
    )
    val meter = if (!meterEvaluable) DueStatus.OK else meterStatus(
        due = state.computedDueMeter,
        current = state.currentMeter,
        lead = schedule.meterLead,
    )
    return if (time.ordinal >= meter.ordinal) time else meter
}

/** A schedule due today is DUE all day; the lead only ever produces DUE_SOON. */
private fun timeStatus(due: LocalDate, today: LocalDate, leadDays: Int): DueStatus = when {
    due.isBefore(today) -> DueStatus.OVERDUE
    due == today -> DueStatus.DUE
    !due.isAfter(today.plusDays(leadDays.toLong())) -> DueStatus.DUE_SOON
    else -> DueStatus.OK
}

/**
 * Meters have no "overdue" degree — a counter past its threshold is DUE and the surface shows how
 * far over — and with no reading at all there is nothing to compare, which is OK rather than a
 * guess. A null [lead] means no lead, not a lead of zero, and so never produces DUE_SOON.
 */
private fun meterStatus(due: Double, current: Double?, lead: Double?): DueStatus = when {
    current == null -> DueStatus.OK
    current >= due -> DueStatus.DUE
    lead != null && current >= due - lead -> DueStatus.DUE_SOON
    else -> DueStatus.OK
}
