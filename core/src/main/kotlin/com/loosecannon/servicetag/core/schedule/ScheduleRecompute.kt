package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.journal.EventChronology
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.GroupMember
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.Season
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.model.TerminationKind
import com.loosecannon.servicetag.core.model.TimeBasis
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** An Asset's season window, as the engine needs it: the two `MM-DD` strings, or nulls for year-round. */
data class SeasonWindow(val startMmdd: String?, val endMmdd: String?)

/**
 * One terminated occurrence: the key `D` it satisfied, the effective date `E` the recurrence
 * advances from, and how it ended. `D` is read off the terminating row, never reconstructed from
 * the date the work was done (invariant 70).
 */
data class Termination(val occurrenceOn: String, val effectiveOn: String, val kind: TerminationKind)

/**
 * The recompute: the **only** write path into `schedule_state` (invariant 17) and a pure,
 * idempotent function of (configuration, events, closures, membership, `T`) (invariants 15, 16).
 *
 * Nothing here reads a clock, a repository or a device. `T` arrives as an argument, the season
 * window arrives as an argument, and `computedAt` is left at 0 for the caller to stamp — a pure
 * function cannot know the time, and a function that did would not be idempotent.
 *
 * There is no "advance" operation anywhere in 1.2. Completing a schedule is "insert the completion
 * event, then rebuild"; closing a round is "insert the closure row, then rebuild"; editing the rule,
 * deleting a completion and importing a backup are all "rebuild". That is why deleting the latest
 * completion moves the due date **back** with no bookkeeping, and why two devices holding the same
 * history derive the same state.
 */
object ScheduleRecompute {

    /**
     * The derived state of one schedule for one day.
     *
     * [events] are the target's events — for a group-targeted schedule, every required member's —
     * and include the ordinary readings the meter side needs, not only this schedule's completions.
     * [closures] are this schedule's, [membership] is empty for an asset target, and [season] is
     * the target Asset's window: null means year-round, and a schedule that IGNOREs the season is
     * seasonally active whatever it says.
     *
     * [season] is a parameter rather than a lookup because the window lives on the Asset and this
     * function takes no repository; it carries a default so a caller with no window to supply — a
     * group target, or an asset with none — says so by saying nothing.
     */
    fun rebuild(
        schedule: MaintenanceSchedule,
        events: List<AssetEvent>,
        closures: List<OccurrenceClosure>,
        membership: List<GroupMember>,
        today: LocalDate,
        season: SeasonWindow? = null,
    ): ScheduleState {
        val completions = completionsOf(schedule, events)
        val newest = completions.maxWithOrNull(EventChronology)
        val last = terminations(schedule, events, closures, membership).lastOrNull()

        val meterId = schedule.meterDefinitionId
        // **A named divergence from D5 §3**, which reads `last?.measurementOf(meter) ?: anchor_meter`
        // — the *last* completion's own reading, or the anchor. This takes the latest completion
        // that actually carries a reading, and the two differ only when the newest completion
        // carries none and an older one does: D5 falls back to the anchor, this keeps the newer of
        // the two real readings. That is the better answer — a threshold that never regresses past
        // work — and D5 holds the case cannot arise because the form makes the meter required on a
        // metered schedule. Recorded here rather than left implicit so the D5 edit can carry it.
        val lastCompletedMeter = meterId?.let { latestReading(completions, it) ?: schedule.anchorMeter }
        val currentMeter = meterId?.let { latestReading(events, it) }
        val meterInterval = schedule.meterInterval
        val computedDueMeter = if (meterId != null && lastCompletedMeter != null && meterInterval != null) {
            RecurrenceMath.meterThreshold(lastCompletedMeter, meterInterval)
        } else {
            null
        }

        val computedDueOn = computedDueOn(schedule, last)
        return ScheduleState(
            scheduleId = schedule.id,
            lastCompletedOn = newest?.occurredOn,
            lastCompletionEventId = newest?.id,
            lastCompletedMeter = lastCompletedMeter,
            currentMeter = currentMeter,
            computedDueMeter = computedDueMeter,
            lastTerminationEffectiveOn = last?.effectiveOn,
            lastTerminationKind = last?.kind ?: TerminationKind.NONE,
            computedDueOn = computedDueOn,
            effectiveDueOn = schedule.postponedDueOn ?: computedDueOn,
            seasonActive = seasonActive(schedule, season, today),
            computedForOn = today.toString(),
            // The caller's stamp. See the class KDoc: a pure function has no clock.
            computedAt = 0L,
        )
    }

    /**
     * Every terminated occurrence of [schedule], oldest first. The fold is **total**: an occurrence
     * that is neither complete nor closed is simply still open, and an occurrence with an empty
     * required set is never a termination at all (invariant 77) — emptiness never means complete.
     *
     * A full completion **beats** a closure for the same occurrence, which makes a stray closure for
     * a round that was in fact finished inert. Local writes cannot create that ambiguity but a merge
     * can, and this precedence settles it without a cross-table check in the planner. When a closure
     * does terminate a round, its effective date is `max(closedOn, the latest completion date)`, so
     * the effective date stays monotone even if a member backdated their completion after the close.
     *
     * A closure whose `occurrence_on` lies off the current series after a recurrence edit is
     * harmless and must not be tidied up (invariant 14): `nextDue` is computed against the *new*
     * series from `max(D, E)`, so old history keeps producing the same answer.
     */
    fun terminations(
        schedule: MaintenanceSchedule,
        events: List<AssetEvent>,
        closures: List<OccurrenceClosure>,
        membership: List<GroupMember>,
    ): List<Termination> {
        val required = requiredMembers(schedule, membership)
        if (required.isEmpty()) return emptyList()

        val byKey = completionsOf(schedule, events).groupBy { occurrenceKeyOf(schedule, it) }
        val closureByKey = closures.filter { it.scheduleId == schedule.id }.groupBy { it.occurrenceOn }

        return (byKey.keys + closureByKey.keys).sorted().mapNotNull { key ->
            val done = byKey[key].orEmpty()
            val closure = closureByKey[key]?.minByOrNull { it.createdAt }
            val latestDone = done.maxOfOrNull { it.occurredOn }
            when {
                latestDone != null && done.map { it.assetId }.toSet().containsAll(required) ->
                    Termination(key, latestDone, TerminationKind.COMPLETED)
                closure != null ->
                    Termination(key, maxOf(closure.closedOn, latestDone ?: closure.closedOn), TerminationKind.CLOSED)
                else -> null
            }
        }.sortedWith(compareBy({ it.occurrenceOn }, { it.effectiveOn }))
    }

    /** This schedule's completions — an event is a completion of it exactly when it names it. */
    private fun completionsOf(schedule: MaintenanceSchedule, events: List<AssetEvent>): List<AssetEvent> =
        events.filter { it.scheduleId == schedule.id }

    /**
     * Who has to do the work for one occurrence.
     *
     * An asset-targeted occurrence has one required member: the schedule's own Asset. **A
     * group-targeted occurrence's required set is derived from the membership windows and the
     * previous occurrence's terminating rows, and that derivation is the groups brief's** — this
     * function is the seam it fills. Until then a group occurrence has an empty required set, which
     * the fold reads as "not a termination" rather than as "complete": the honest answer, and the
     * one invariant 77 already prescribes for an empty set.
     */
    private fun requiredMembers(schedule: MaintenanceSchedule, membership: List<GroupMember>): Set<AssetId> =
        when (val target = schedule.target) {
            is ScheduleTarget.AssetTarget -> setOf(target.assetId)
            is ScheduleTarget.GroupTarget -> emptySet()
        }

    /**
     * The occurrence key a completion satisfied. Normally it is read straight off the row, which is
     * what `occurrence_on` is for beyond idempotence.
     *
     * A completion with **no** key — a pre-1.2 row, or one re-pointed to a schedule by hand — falls
     * back to the largest series date `<= occurred_on`, or the anchor if the completion predates the
     * series. **The fallback is approximate**, and in exactly one way: an early completion is
     * attributed to the occurrence *before* the one it actually satisfied, so the schedule advances
     * one occurrence less far than it should. That is the documented cost of a row with no key; it
     * is not a reason to throw, which would make old data unusable, and not a reason to use today,
     * which would make it wrong.
     */
    private fun occurrenceKeyOf(schedule: MaintenanceSchedule, completion: AssetEvent): String {
        completion.occurrenceOn?.let { return it }
        val interval = schedule.timeInterval
        val unit = schedule.timeUnit
        val anchorText = schedule.anchorOn
        if (interval == null || unit == null || anchorText == null) return completion.occurredOn
        if (schedule.timeBasis != TimeBasis.FIXED) return completion.occurredOn
        val anchor = LocalDate.parse(anchorText)
        val at = RecurrenceMath.largestSeriesDateAtOrBefore(
            anchor, LocalDate.parse(completion.occurredOn), interval, unit,
        )
        return (at ?: anchor).toString()
    }

    /**
     * The time side.
     *
     * With a termination: FIXED takes the smallest series date strictly after `max(D, E)`, so a very
     * late termination skips forward and produces exactly **one** next occurrence rather than a
     * backlog of missed ones (invariant 13); COMPLETION takes `E + interval`, which is why an early
     * completion moves the whole series and a FIXED one does not.
     *
     * With no termination at all, FIXED is **pinned from immutable configuration** (D-27): the
     * smallest series date at or after `max(anchorOn, the row's own floor)`. It never re-floats on
     * today — a due date computed as "the first series date >= T" would make a real obligation
     * quietly disappear every interval — and the same pin comes back after the sole completion is
     * deleted. COMPLETION with no termination is due **at** the anchor: the anchor is where the
     * owner states it is first due.
     */
    private fun computedDueOn(schedule: MaintenanceSchedule, last: Termination?): String? {
        val interval = schedule.timeInterval ?: return null
        val unit = schedule.timeUnit ?: return null
        val anchor = LocalDate.parse(schedule.anchorOn ?: return null)
        return when (schedule.timeBasis) {
            TimeBasis.FIXED -> if (last == null) {
                RecurrenceMath.firstSeriesDateAtOrAfter(anchor, maxOf(anchor, pinFloor(schedule)), interval, unit)
            } else {
                val bound = maxOf(LocalDate.parse(last.occurrenceOn), LocalDate.parse(last.effectiveOn))
                RecurrenceMath.firstSeriesDateAfter(anchor, bound, interval, unit)
            }
            TimeBasis.COMPLETION -> if (last == null) {
                anchor
            } else {
                RecurrenceMath.plusInterval(LocalDate.parse(last.effectiveOn), interval, unit)
            }
        }.toString()
    }

    /**
     * The pin's floor: the date of the row's `updated_at`, which is its creation date until
     * something edits it (spec §2.1). Without the floor, re-anchoring an old never-terminated
     * schedule would pin it immediately overdue; with it, only an explicit edit moves the floor,
     * to the edit date (invariant 25). The schedule operations are written so that the writes which
     * are *not* rule changes leave `updated_at` alone, which is what keeps that true.
     *
     * This is the one instant the engine has to see a date for, and it is converted at **UTC** on
     * purpose: a device-local conversion would make `rebuild` depend on an ambient zone and stop it
     * being a pure function of its arguments (invariant 16). The cost is that a row stamped within
     * a few hours of local midnight can floor on the neighbouring day, which can only ever matter
     * if a series date falls exactly there.
     */
    private fun pinFloor(schedule: MaintenanceSchedule): LocalDate =
        Instant.ofEpochMilli(schedule.updatedAt).atZone(ZoneOffset.UTC).toLocalDate()

    /**
     * The newest reading of [definitionId] by [EventChronology] — **the latest, never the maximum**.
     *
     * The filter and the extraction ask the same question, `definitionId` **and** a non-null
     * `valueNum`: an event carrying two measurements of one definition whose first has no numeric
     * value would otherwise pass the filter and yield null, silently losing the reading and dropping
     * the schedule back a step.
     */
    private fun latestReading(events: List<AssetEvent>, definitionId: DefinitionId): Double? {
        fun reading(event: AssetEvent): Double? = event.measurements
            .firstOrNull { it.definitionId == definitionId && it.valueNum != null }
            ?.valueNum
        return events.filter { reading(it) != null }.maxWithOrNull(EventChronology)?.let { reading(it) }
    }

    /** A schedule that IGNOREs the season is always active; otherwise the Asset's window decides. */
    private fun seasonActive(
        schedule: MaintenanceSchedule,
        season: SeasonWindow?,
        today: LocalDate,
    ): Boolean {
        if (schedule.seasonBehavior == SeasonBehavior.IGNORE) return true
        val start = season?.startMmdd
        val end = season?.endMmdd
        // Both-or-neither is the Asset's own invariant; a half-set window is read as year-round
        // rather than thrown at, because the engine is not where that rule is enforced.
        if (start == null || end == null) return true
        return Season.inSeason(start, end, today)
    }
}
