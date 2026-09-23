package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.GroupMember
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ClosureRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.ScheduleStateRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.schedule.GroupOccurrence
import com.loosecannon.servicetag.core.schedule.GroupOccurrences
import com.loosecannon.servicetag.core.schedule.ScheduleRecompute
import com.loosecannon.servicetag.core.schedule.SeasonWindow
import java.time.ZoneId

/**
 * The collaborator that turns "this changed" into "these schedules' derived state was rebuilt", and
 * **the only caller of [ScheduleStateRepository.upsert]** (invariant 17).
 *
 * It exists because "every event insert, update and delete rebuilds" is a closure over group
 * membership that no single use case can compute: an event on one Asset touches that Asset's own
 * schedules *and* every group schedule whose required set contains it. Putting that closure in each
 * event use case would duplicate it four times and let the four drift; putting it here means each
 * of them asks one question — [forAsset] — and the answer is always the whole closure.
 *
 * It reads the clock exactly once per rebuild, to stamp `computedAt`, and the zone once, for the
 * pin's floor. The engine itself cannot do either: `rebuild` is a pure function, and a pure function
 * has no clock and no idea where it is — which is what makes it idempotent.
 *
 * [groupSchedulesRequiring] is the group half of that closure, and it is deliberately a **superset**
 * of "requires it": see [GroupRepository.allWindowsFor]. Rebuilding a schedule that turns out not to
 * require the Asset costs one pure recomputation of the same answer, because `rebuild` is
 * idempotent; missing one leaves a due date derived from history the engine has not read.
 */
class RecomputeSchedules(
    private val schedules: ScheduleRepository,
    private val states: ScheduleStateRepository,
    private val events: EventRepository,
    private val closures: ClosureRepository,
    private val groups: GroupRepository,
    private val assets: AssetRepository,
    private val today: Today,
    private val clock: Clock,
    /**
     * The owner's zone, read once per rebuild and handed to the engine beside `T`.
     *
     * The pin has to read one stored instant as a date, and that date is the owner's rather than
     * UTC's (controller ruling, 2026-09-22); `ScheduleRecompute.pinFloor` says why. Reading the
     * device belongs **here**, at the seam that already reads the clock and the [Today] port, which
     * is exactly what keeps `rebuild` a pure function of its arguments (invariant 16). A lambda
     * rather than a captured value because the zone can change under a long-lived process, and it
     * is the idiom the digest alarm and the completion flow already use for the same reason.
     *
     * Required, not defaulted (1.2.1): every construction site names its zone explicitly, so a
     * caller cannot forget it is choosing device-local time over UTC.
     */
    private val zone: () -> ZoneId,
) {
    /** The Asset's own schedules, plus every group schedule that requires it. */
    suspend fun forAsset(assetId: AssetId) {
        (schedules.forAsset(assetId) + groupSchedulesRequiring(assetId))
            .distinctBy { it.id.value }
            .forEach { rebuild(it) }
    }

    /** One schedule, after an edit or an operation on it. A schedule that has gone is a no-op. */
    suspend fun forSchedule(id: ScheduleId) {
        schedules.get(id)?.let { rebuild(it) }
    }

    /**
     * Every schedule in the database. This is the digest's and the backstop's sweep, and it is what
     * both import paths call: rather than enumerate which schedules an imported event, membership
     * row, closure or meter reading could have touched, the import rebuilds all of them inside its
     * own transaction. At this scale it is cheap and provably complete.
     */
    suspend fun all() {
        schedules.all().forEach { rebuild(it) }
    }

    /**
     * Every group schedule that could require [assetId] for some round of its own: the schedules of
     * every group the Asset has **ever** been a member of.
     *
     * Wider than "requires it now", on purpose. A member removed mid-round is still required for the
     * round already open (D-10), and a member whose window closed long ago is still required for the
     * rounds it covered — both of which an event on that Asset can still complete. The narrow
     * question is answered per occurrence, inside the engine, where the round's own open instant is
     * known; this one only has to be certain it misses nothing.
     */
    private suspend fun groupSchedulesRequiring(assetId: AssetId): List<MaintenanceSchedule> =
        groups.allWindowsFor(assetId).flatMap { schedules.forGroup(it.id) }

    /**
     * The schedule's **current occurrence**, derived from the same history [stateOf] reads.
     *
     * One gathering point, for the same reason the recompute itself is one: the occurrence a use
     * case validates against and the occurrence the engine terminates must be derived from the same
     * rows, or a member could be refused for a round the engine thinks it is required for. Null only
     * for a schedule with no time rule, which a group target can never be.
     */
    suspend fun occurrenceOf(schedule: MaintenanceSchedule): GroupOccurrence? {
        val inputs = inputsFor(schedule)
        val closureRows = closures.forSchedule(schedule.id)
        val key = ScheduleRecompute.currentOccurrenceOn(
            schedule = schedule,
            events = inputs.events,
            closures = closureRows,
            membership = inputs.membership,
            zone = zone(),
        ) ?: return null
        return GroupOccurrences.on(
            schedule = schedule,
            events = inputs.events,
            closures = closureRows,
            membership = inputs.membership,
            occurrenceOn = key,
        )
    }

    /**
     * The schedule's derived state **as it is now**, computed rather than read back out of the
     * table. A completion stamps its `occurrence_on` from this, so that the key it claims is the
     * occurrence the engine currently says is open and never a state row that a concurrent write
     * left a day behind.
     */
    suspend fun stateOf(schedule: MaintenanceSchedule): ScheduleState {
        val inputs = inputsFor(schedule)
        val state = ScheduleRecompute.rebuild(
            schedule = schedule,
            events = inputs.events,
            closures = closures.forSchedule(schedule.id),
            membership = inputs.membership,
            today = today.localDate(),
            zone = zone(),
            season = inputs.season,
        )
        // `rebuild` leaves `computedAt` at 0 because it has no clock; this is where it is stamped.
        return state.copy(computedAt = clock.nowMillis())
    }

    private suspend fun rebuild(schedule: MaintenanceSchedule) {
        states.upsert(stateOf(schedule))
    }

    /**
     * What one schedule's rebuild reads. An asset-targeted schedule reads its Asset's events and
     * its Asset's season window; a group-targeted one reads every member's events and the group's
     * membership rows, and no window at all, because a group target is `IGNORE` season only.
     *
     * A group's members are read here and **bounded by their Assets' lifecycles** here, and by
     * nothing else: which of the remaining windows the current occurrence *requires* is the engine's
     * derivation, and this only has to hand it everything that derivation needs. The lifecycle bound
     * lives at this seam because it is the one place that holds both the membership rows and the
     * Assets — the engine takes no repository — and because putting it here means the round a use
     * case validates against and the round the engine terminates are bounded identically.
     */
    private suspend fun inputsFor(schedule: MaintenanceSchedule): RebuildInputs =
        when (val target = schedule.target) {
            is ScheduleTarget.AssetTarget -> {
                val asset = assets.get(target.assetId)
                RebuildInputs(
                    events = events.forAsset(target.assetId),
                    membership = emptyList(),
                    season = asset?.let { SeasonWindow(it.seasonStartMmdd, it.seasonEndMmdd) },
                )
            }
            is ScheduleTarget.GroupTarget -> {
                val group = groups.get(target.groupId)
                val members = group?.members.orEmpty()
                RebuildInputs(
                    // Every member's events, including a member the lifecycle bound removes: its
                    // completions still terminate the rounds its window did cover.
                    events = members.map { it.assetId }.distinct().flatMap { events.forAsset(it) },
                    membership = group?.let { boundedMembers(it, assets) }.orEmpty(),
                    season = null,
                )
            }
        }

    private data class RebuildInputs(
        val events: List<AssetEvent>,
        val membership: List<GroupMember>,
        val season: SeasonWindow?,
    )
}
