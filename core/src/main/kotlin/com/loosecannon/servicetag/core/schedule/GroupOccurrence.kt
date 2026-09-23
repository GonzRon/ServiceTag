package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.GroupMember
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleTarget
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * One occurrence of one schedule, **derived**: nothing here is stored, and nothing here is a
 * snapshot. There is no occurrence table — the identity of a round and the set of members it obliges
 * are read back out of history every time, which is the only shape in which "historical completeness
 * never depends on today's membership list" can be true rather than hoped for.
 *
 * [openInstant] is the round's basis and the one value everything else hangs off: see
 * [GroupOccurrences.openInstantOn]. [required] is ordered as the group orders its members,
 * `(sortOrder, id)`; [completed] is every asset holding a completion of this round, ordered by id so
 * two devices deriving the same round agree on the list and not merely on the set.
 *
 * An asset-targeted schedule has an occurrence too — one required member, its own Asset — which is
 * why the engine folds both kinds of target through this one derivation instead of branching.
 */
data class GroupOccurrence(
    val scheduleId: ScheduleId,
    val occurrenceOn: LocalDate,
    val openInstant: Long,
    val openOn: LocalDate,
    val required: List<AssetId>,
    val completed: List<AssetId>,
) {
    /**
     * Whether there is anybody to ask. An occurrence whose required set is **empty** — an empty
     * group, or one whose windows all closed before this round opened — is not actionable: it is
     * never offered for completion, never counted as due, never closeable, and its schedule reports
     * `NO_DATA` (spec §2.4, invariants 74, 77).
     *
     * **Emptiness is not completeness**, which is why this is a separate question from [isComplete]
     * and not the negation of it: a vacuous "every required member is done" over an empty set is the
     * one answer that would silently advance a schedule nobody can act on.
     */
    val isActionable: Boolean get() = required.isNotEmpty()

    /**
     * Whether the round is finished: the completed set is **non-empty and covers** the required set.
     * Only then does a completion advance the schedule (invariant 30).
     */
    val isComplete: Boolean get() = required.isNotEmpty() && completed.containsAll(required)

    /** Done and total, in that order — what the ratified "3 of 5 complete" renders. */
    val progress: Pair<Int, Int> get() = required.count { it in completed } to required.size
}

/**
 * The occurrence derivation: pure functions of (schedule, events, closures, membership) and nothing
 * else. No clock, no repository, no today — a round's identity and its member basis are facts about
 * history, and a function that consulted the present could not reproduce them after an import.
 *
 * The three questions, and why each is answered the way it is:
 *
 * - **the open instant** is the maximum `created_at` over the **previous** occurrence's rows — its
 *   member completions and, if it was closed, its closure row — or the schedule's own `created_at`
 *   for the first occurrence. `created_at` is exported and compared by the merge, so the instant is
 *   identical on every device holding the same rows. `occurred_on` and `closed_on` are deliberately
 *   **not** used: a completion backdated a month must not move the membership basis, and "now" would
 *   make the basis unreproducible the moment the rows were imported somewhere else (invariant 73).
 * - **the required set** is the members whose `[addedAt, removedAt)` window covers that instant. A
 *   member added mid-round is therefore not required for the round already open and joins the next
 *   one; a member removed mid-round is **still** required for the open round (D-10). No membership
 *   operation can change a past round's required set, because none of them moves an instant already
 *   in the past (invariant 33).
 * - **the completed set** is the member assets holding an event with this `(schedule_id,
 *   occurrence_on)`.
 *
 * **Precondition on every function that takes a membership list.** The list must already be bounded
 * by [withLifecycle]: these functions read the windows as given and know nothing about the member
 * Assets, so a raw `MaintenanceGroup.members` yields a required set that includes archived and
 * retired members and therefore **silently disagrees with `schedule_state`**. Prefer
 * `RecomputeSchedules.occurrenceOf(schedule)`, which gathers the same inputs the engine rebuilds
 * from and applies the bound; call these directly only with a list you bounded yourself.
 */
object GroupOccurrences {

    /**
     * The whole derived occurrence keyed [occurrenceOn].
     *
     * The key is an ISO `YYYY-MM-DD` string because that is what the rows carry; the returned dates
     * are `LocalDate` because every consumer of an occurrence is doing calendar arithmetic with them
     * — the conversion happens once, here, rather than at each of them.
     *
     * [membership] must already be bounded by [withLifecycle] — see the class KDoc. A surface that
     * wants an occurrence should ask `RecomputeSchedules.occurrenceOf` for it.
     */
    fun on(
        schedule: MaintenanceSchedule,
        events: List<AssetEvent>,
        closures: List<OccurrenceClosure>,
        membership: List<GroupMember>,
        occurrenceOn: String,
    ): GroupOccurrence = OccurrenceBasis.of(schedule, events, closures, membership).occurrence(occurrenceOn)

    /**
     * Who has to do the work for the occurrence keyed [occurrenceOn]. [membership] must already be
     * bounded by [withLifecycle] — see the class KDoc.
     */
    fun requiredOn(
        schedule: MaintenanceSchedule,
        events: List<AssetEvent>,
        closures: List<OccurrenceClosure>,
        membership: List<GroupMember>,
        occurrenceOn: String,
    ): List<AssetId> = OccurrenceBasis.of(schedule, events, closures, membership).required(occurrenceOn)

    /** The instant the occurrence keyed [occurrenceOn] opened, and so the instant its windows are read at. */
    fun openInstantOn(
        schedule: MaintenanceSchedule,
        events: List<AssetEvent>,
        closures: List<OccurrenceClosure>,
        occurrenceOn: String,
    ): Long = OccurrenceBasis.of(schedule, events, closures, emptyList()).openInstant(occurrenceOn)

    /**
     * The membership windows as the occurrence derivation must read them: bounded by each member
     * Asset's **own lifecycle** as the store records it (D-16).
     *
     * A window is not edited — nothing here is ever written back, and [SaveGroup] remains the only
     * writer of a membership row. This is a derivation-time bound, applied to the list the pure
     * functions are handed:
     *
     * - a **retired** member's window is closed at its `retiredOn`, so a round that opened *before*
     *   the retirement still obliges it (D-10) and every round opening on or after that date does
     *   not;
     * - an **archived** member is dropped outright.
     *
     * **Known limit, against invariant 33's letter** (owner-flagged, `lifecycleChangedAt` deferred):
     * neither bound is a stable instant. `AssetStatus` carries no timestamp at all, so an archive
     * takes the member out of **every** round, past ones included; and `retiredOn` is deliberately
     * back-datable ("I replaced this in April"), so a retirement recorded after the fact moves the
     * bound and can change a round that has already opened. Both are treated as what they are — a
     * deliberate correction of what the equipment is — rather than papered over with a proxy
     * instant like `updated_at`, which any unrelated edit would move. The consequence to know: a
     * round that shrinks this way can become complete, and one that empties stops being a
     * termination at all.
     */
    fun withLifecycle(
        members: List<GroupMember>,
        lifecycleOf: (AssetId) -> MemberLifecycle?,
    ): List<GroupMember> = members.mapNotNull { row ->
        val lifecycle = lifecycleOf(row.assetId) ?: return@mapNotNull row
        if (lifecycle.archived) return@mapNotNull null
        val retiredAt = lifecycle.retiredAt ?: return@mapNotNull row
        row.copy(removedAt = row.removedAt?.let { minOf(it, retiredAt) } ?: retiredAt)
    }

    /**
     * An instant's calendar date.
     *
     * Converted at a **fixed** offset, UTC, because an occurrence key is a value two devices must
     * agree on with no zone in common: a device-local conversion would make a derived occurrence
     * depend on an ambient zone, so two phones holding identical rows could disagree about a
     * round's open date and therefore about the range a `closedOn` may lie in — the one divergence
     * spec §2.9 exists to prevent. The cost is that a round opened within a few hours of UTC
     * midnight can report the neighbouring day.
     *
     * It no longer matches the D-27 pin's floor, which reads the **owner's** zone (controller
     * ruling, 2026-09-22), and it should not: the two answer different questions. The floor is
     * about a day the owner lived through; this is about a key that has to survive being compared
     * between phones.
     */
    fun dateOf(instant: Long): LocalDate =
        Instant.ofEpochMilli(instant).atZone(ZoneOffset.UTC).toLocalDate()
}

/**
 * One member Asset's lifecycle, reduced to the two things a membership window has to respect.
 *
 * [retiredAt] is the retirement **date** at midnight UTC — the same conversion
 * [GroupOccurrences.dateOf] inverts — so "retired on or before the round's open date" and "the
 * window does not cover the open instant" are the same question.
 */
data class MemberLifecycle(val archived: Boolean, val retiredAt: Long?)

/**
 * One schedule's occurrence history, indexed once: the rows of each occurrence key, the keys in
 * order, and the open instant that follows from them. A fold over the keys — [ScheduleRecompute
 * .terminations] — asks about every key, and each answer needs the key before it, so building the
 * chain once is the difference between a linear pass and a quadratic one.
 */
internal class OccurrenceBasis private constructor(
    private val schedule: MaintenanceSchedule,
    private val membership: List<GroupMember>,
    private val completionsByKey: Map<String, List<AssetEvent>>,
    private val closuresByKey: Map<String, List<OccurrenceClosure>>,
) {
    /** Every occurrence key this schedule's history holds, oldest first. ISO dates sort as strings. */
    val keys: List<String> = (completionsByKey.keys + closuresByKey.keys).sorted()

    fun completions(key: String): List<AssetEvent> = completionsByKey[key].orEmpty()

    /**
     * The closure of [key], and on the vanishing chance that an archive carried two for one round,
     * the **oldest** of them: `UNIQUE(schedule_id, occurrence_on)` makes a second one impossible
     * locally, and picking the earliest keeps the derivation deterministic if one arrives anyway.
     */
    fun closure(key: String): OccurrenceClosure? = closuresByKey[key]?.minByOrNull { it.createdAt }

    /**
     * The instant [key] opened: the maximum `created_at` over the previous occurrence's rows, or the
     * schedule's own `created_at` when there is no previous occurrence.
     *
     * "Previous" is the largest key strictly below [key], which makes this total for a key that is
     * not in [keys] at all — the current round, whose rows do not exist yet, is exactly that case —
     * and needs no special branch for the first occurrence, for a round with no rows, or for a key
     * that an edited recurrence has moved behind an older one.
     */
    fun openInstant(key: String): Long {
        val previous = keys.lastOrNull { it < key } ?: return schedule.createdAt
        val instants = completions(previous).map { it.createdAt } +
            closuresByKey[previous].orEmpty().map { it.createdAt }
        return instants.maxOrNull() ?: schedule.createdAt
    }

    /**
     * The required set of [key]. An asset-targeted schedule requires its own Asset and nothing else;
     * a group-targeted one requires the members whose window covers the open instant, in the group's
     * own `(sortOrder, id)` order.
     *
     * The window is half-open — `addedAt <= instant < removedAt` — which is what lets remove and
     * re-add on the same instant produce one open window rather than two overlapping ones.
     */
    fun required(key: String): List<AssetId> = when (val target = schedule.target) {
        is ScheduleTarget.AssetTarget -> listOf(target.assetId)
        is ScheduleTarget.GroupTarget -> {
            val instant = openInstant(key)
            membership
                .filter { it.addedAt <= instant && (it.removedAt == null || it.removedAt > instant) }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                .map { it.assetId }
                .distinct()
        }
    }

    /** The assets holding a completion of [key]. */
    fun completed(key: String): List<AssetId> =
        completions(key).map { it.assetId }.distinct().sortedBy { it.value }

    fun occurrence(key: String): GroupOccurrence {
        val instant = openInstant(key)
        return GroupOccurrence(
            scheduleId = schedule.id,
            occurrenceOn = LocalDate.parse(key),
            openInstant = instant,
            openOn = GroupOccurrences.dateOf(instant),
            required = required(key),
            completed = completed(key),
        )
    }

    companion object {
        fun of(
            schedule: MaintenanceSchedule,
            events: List<AssetEvent>,
            closures: List<OccurrenceClosure>,
            membership: List<GroupMember>,
        ): OccurrenceBasis = OccurrenceBasis(
            schedule = schedule,
            membership = membership,
            completionsByKey = events
                .filter { it.scheduleId == schedule.id }
                .groupBy { ScheduleRecompute.occurrenceKeyOf(schedule, it) },
            closuresByKey = closures.filter { it.scheduleId == schedule.id }.groupBy { it.occurrenceOn },
        )
    }
}
