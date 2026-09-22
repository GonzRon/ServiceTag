package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.GroupMember
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.TerminationKind
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.testing.closureOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The group occurrence, derived: the open instant, the required set, the completed set, and the
 * `terminations` fold that reads closures and completions together.
 *
 * Every test here is against the pure functions, with the rows as the only input. That is the point
 * rather than a convenience: #55's hardest requirement is that a past round's membership basis never
 * depends on today's membership list, and the only way to assert that is to hand the same function
 * two different presents and get one answer.
 */
class GroupOccurrenceTest {

    private fun on(date: String) = LocalDate.parse(date)

    /** A group-targeted quarterly schedule, pinned on its anchor. */
    private fun quarterly(
        anchorOn: String = "2026-01-01",
        createdOn: String = "2026-01-01",
        basis: TimeBasis = TimeBasis.FIXED,
        interval: Int = 3,
        postponedDueOn: String? = null,
    ): MaintenanceSchedule = scheduleOf(
        assetId = null,
        groupId = "g1",
        timeInterval = interval,
        timeUnit = RecurrenceUnit.MONTH,
        timeBasis = basis,
        anchorOn = anchorOn,
        createdOn = createdOn,
        postponedDueOn = postponedDueOn,
    )

    /**
     * Invariant 73 — the open instant is a **stated exported value**, not a moment in the life of the
     * process that asked. It is the maximum `created_at` over the previous round's rows, whichever of
     * them there are, and the schedule's own `created_at` for the first round.
     *
     * Asserted as a property over the rows rather than against literals: each expected value is
     * recomputed here from the same list the function was handed, so a change that made the instant
     * depend on `occurred_on`, on `closed_on` or on "now" has nowhere to hide. The closure's
     * `created_at` is deliberately the largest in round two, because it is the row a naive
     * "maximum over the completions" would miss.
     */
    @Test
    fun theOpenInstantIsTheMaximumCreatedAtOfThePreviousRoundsRows() {
        val schedule = quarterly()
        val membership = groupOf(
            members = listOf(Triple("a1", "2025-12-01", null), Triple("a2", "2025-12-01", null)),
        ).members
        val events = listOf(
            completionOf("e1", occurredOn = "2026-01-10", occurrenceOn = "2026-01-01", assetId = "a1"),
            completionOf("e2", occurredOn = "2026-01-20", occurrenceOn = "2026-01-01", assetId = "a2"),
            completionOf("e3", occurredOn = "2026-04-05", occurrenceOn = "2026-04-01", assetId = "a1"),
        )
        val closures = listOf(closureOf("oc1", occurrenceOn = "2026-04-01", closedOn = "2026-04-30"))

        fun rowsOf(key: String): List<Long> =
            events.filter { it.occurrenceOn == key }.map { it.createdAt } +
                closures.filter { it.occurrenceOn == key }.map { it.createdAt }

        fun occurrence(key: String) =
            GroupOccurrences.on(schedule, events, closures, membership, key)

        // The first round has no previous round to read, so the schedule's own creation is the basis.
        assertEquals(schedule.createdAt, occurrence("2026-01-01").openInstant)
        assertEquals(rowsOf("2026-01-01").max(), occurrence("2026-04-01").openInstant)
        // Round two was closed, so its closure row is one of the rows that terminated it — and here
        // the latest of them.
        assertEquals(rowsOf("2026-04-01").max(), occurrence("2026-07-01").openInstant)
        assertEquals(dayMillis("2026-04-30"), occurrence("2026-07-01").openInstant)

        // Not a date off the rows, and not today: the two wrong answers, named.
        val occ = occurrence("2026-07-01")
        assertEquals(on("2026-04-30"), occ.openOn)
        assertEquals(GroupOccurrences.dateOf(occ.openInstant), occ.openOn)
    }

    /**
     * A completion backdated a month does **not** move the next round's membership basis. This is
     * why the instant reads `created_at` and not `occurred_on`: a member who joined after the work
     * was done but before it was *recorded* is still part of the next round, and an owner who logs
     * "I did this in November" in February must not silently drop them.
     *
     * The discriminating member is `a2`, whose window opens between the two dates.
     */
    @Test
    fun aBackdatedCompletionDoesNotMoveTheMembershipBasis() {
        val schedule = quarterly()
        val membership = groupOf(
            members = listOf(Triple("a1", "2025-12-01", null), Triple("a2", "2026-01-15", null)),
        ).members
        val recordedLate = completionOf(
            "e1",
            occurredOn = "2025-11-01",
            occurrenceOn = "2026-01-01",
            assetId = "a1",
            createdAt = dayMillis("2026-02-01"),
        )

        assertEquals(
            listOf(AssetId("a1")),
            GroupOccurrences.requiredOn(schedule, listOf(recordedLate), emptyList(), membership, "2026-01-01"),
        )
        assertEquals(
            dayMillis("2026-02-01"),
            GroupOccurrences.openInstantOn(schedule, listOf(recordedLate), emptyList(), "2026-04-01"),
        )
        assertEquals(
            listOf(AssetId("a1"), AssetId("a2")),
            GroupOccurrences.requiredOn(schedule, listOf(recordedLate), emptyList(), membership, "2026-04-01"),
        )
    }

    /**
     * The required set across churn, all three facts in one history (invariant 33, D-10):
     *
     * - `a3` is added **after** the open round opened and is not required for it; it joins the next.
     * - `a2` is removed **after** the open round opened and is **still** required for it.
     * - neither fact changes when the next round's basis moves: `a2` drops out of round two because
     *   its window closed before round two opened, not because it is "gone now".
     */
    @Test
    fun theRequiredSetFollowsTheWindowsAndNotTodaysList() {
        val schedule = quarterly()
        val membership = groupOf(
            members = listOf(
                Triple("a1", "2025-12-01", null),
                Triple("a2", "2025-12-01", "2026-02-01"),
                Triple("a3", "2026-02-01", null),
            ),
        ).members
        val done = listOf(
            completionOf("e1", occurredOn = "2026-03-01", occurrenceOn = "2026-01-01", assetId = "a1"),
            completionOf("e2", occurredOn = "2026-03-01", occurrenceOn = "2026-01-01", assetId = "a2"),
        )

        val open = GroupOccurrences.on(schedule, done, emptyList(), membership, "2026-01-01")
        assertEquals(listOf(AssetId("a1"), AssetId("a2")), open.required)
        assertTrue(open.isComplete, "both required members did the work")

        val next = GroupOccurrences.on(schedule, done, emptyList(), membership, "2026-04-01")
        assertEquals(listOf(AssetId("a1"), AssetId("a3")), next.required)
        assertEquals(emptyList(), next.completed)
        assertEquals(0 to 2, next.progress)
    }

    /**
     * An empty required set is **never** a termination and never a due date, whatever rows the round
     * carries (invariants 74, 77). The group here has one window and it closed before the schedule
     * was created, so the first round obliges nobody.
     *
     * Emptiness is not completeness: the same round is handed a completion *and* a closure, and it
     * still does not terminate. Without the rule a vacuous "every required member is done" advances
     * the schedule on nobody's work — the R1 defect.
     */
    @Test
    fun anEmptyRequiredSetIsNeverATerminationAndTheScheduleReportsNoData() {
        val schedule = quarterly(createdOn = "2026-01-01")
        val membership = groupOf(
            members = listOf(Triple("a1", "2025-10-01", "2025-12-01")),
        ).members
        val events = listOf(
            completionOf("e1", occurredOn = "2026-01-05", occurrenceOn = "2026-01-01", assetId = "a1"),
        )
        val closures = listOf(closureOf("oc1", occurrenceOn = "2026-01-01", closedOn = "2026-01-06"))

        val occurrence = GroupOccurrences.on(schedule, events, closures, membership, "2026-01-01")
        assertEquals(emptyList(), occurrence.required)
        assertFalse(occurrence.isActionable)
        assertFalse(occurrence.isComplete, "emptiness is never completeness")
        assertEquals(0 to 0, occurrence.progress)

        assertEquals(emptyList(), ScheduleRecompute.terminations(schedule, events, closures, membership))

        val state = ScheduleRecompute.rebuild(schedule, events, closures, membership, on("2026-06-01"))
        assertNull(state.computedDueOn)
        assertNull(state.effectiveDueOn)
        assertEquals(TerminationKind.NONE, state.lastTerminationKind)
        assertEquals(DueStatus.NO_DATA, statusOf(schedule, state, on("2026-06-01")))
        assertFalse(DueStatus.NO_DATA.countsAsDue)
    }

    /**
     * A postponement on a round that obliges nobody is dropped along with the due date. Without it
     * the sort key would carry a date for a round nothing can act on, which is what "never counted
     * as due" forbids — and a postponed empty round is reachable, because the members can all be
     * removed after the postponement was agreed.
     */
    @Test
    fun anEmptyRoundDropsItsPostponementToo() {
        val schedule = quarterly(createdOn = "2026-01-01", postponedDueOn = "2026-02-20")
        val membership = groupOf(members = listOf(Triple("a1", "2025-10-01", "2025-12-01"))).members

        val state = ScheduleRecompute.rebuild(schedule, emptyList(), emptyList(), membership, on("2026-02-20"))
        assertNull(state.effectiveDueOn)
        assertEquals(DueStatus.NO_DATA, statusOf(schedule, state, on("2026-02-20")))
    }

    /**
     * The ordinary close: no completions at all, so the effective date is the `closed_on`, and
     * **both** bases advance from it with no special case. FIXED takes the smallest series date
     * strictly after `max(D, E)`; COMPLETION takes `E + interval`.
     *
     * Without a defined effective date for an empty completion set this is where the R1 defect
     * showed itself — an undefined `max` over nothing either throws or hands back the anchor.
     */
    @Test
    fun aClosedRoundWithNoCompletionsTerminatesAtItsClosedOnAndBothBasesAdvanceFromIt() {
        val membership = groupOf(members = listOf(Triple("a1", "2025-12-01", null))).members
        val closures = listOf(closureOf("oc1", occurrenceOn = "2026-01-01", closedOn = "2026-02-15"))

        val fixed = quarterly()
        assertEquals(
            listOf(Termination("2026-01-01", "2026-02-15", TerminationKind.CLOSED)),
            ScheduleRecompute.terminations(fixed, emptyList(), closures, membership),
        )
        val fixedState = ScheduleRecompute.rebuild(fixed, emptyList(), closures, membership, on("2026-03-01"))
        assertEquals("2026-04-01", fixedState.computedDueOn)
        assertEquals("2026-02-15", fixedState.lastTerminationEffectiveOn)
        assertEquals(TerminationKind.CLOSED, fixedState.lastTerminationKind)
        // A closed round is not a claim that anybody did the work, so "last done" stays empty.
        assertNull(fixedState.lastCompletedOn)

        val completion = quarterly(basis = TimeBasis.COMPLETION)
        val completionState =
            ScheduleRecompute.rebuild(completion, emptyList(), closures, membership, on("2026-03-01"))
        assertEquals("2026-05-15", completionState.computedDueOn)
    }

    /**
     * `max(closed_on, the latest completion)` keeps the effective date **monotone**: a member who
     * backdates their completion to a date later than the close does not move the next round
     * backwards, and one who backdates it earlier does not move it either.
     */
    @Test
    fun aCompletionLaterThanTheClosureKeepsTheEffectiveDateMonotone() {
        val schedule = quarterly()
        val membership = groupOf(
            members = listOf(Triple("a1", "2025-12-01", null), Triple("a2", "2025-12-01", null)),
        ).members
        val closures = listOf(closureOf("oc1", occurrenceOn = "2026-01-01", closedOn = "2026-02-15"))
        val late = completionOf("e1", occurredOn = "2026-03-10", occurrenceOn = "2026-01-01", assetId = "a1")

        assertEquals(
            listOf(Termination("2026-01-01", "2026-03-10", TerminationKind.CLOSED)),
            ScheduleRecompute.terminations(schedule, listOf(late), closures, membership),
        )
    }

    /**
     * A full completion **beats** a closure for the same round (invariant 40), so a stray closure on
     * a round that was in fact finished is inert. Local writes cannot create the ambiguity — a
     * closed round refuses further completions — but a merge can, and closure-first precedence would
     * let one imported row change a schedule's whole future.
     */
    @Test
    fun aFullCompletionBeatsAClosureForTheSameRound() {
        val schedule = quarterly()
        val membership = groupOf(
            members = listOf(Triple("a1", "2025-12-01", null), Triple("a2", "2025-12-01", null)),
        ).members
        val done = listOf(
            completionOf("e1", occurredOn = "2026-02-10", occurrenceOn = "2026-01-01", assetId = "a1"),
            completionOf("e2", occurredOn = "2026-02-20", occurrenceOn = "2026-01-01", assetId = "a2"),
        )
        val stray = listOf(closureOf("oc1", occurrenceOn = "2026-01-01", closedOn = "2026-05-01"))

        assertEquals(
            listOf(Termination("2026-01-01", "2026-02-20", TerminationKind.COMPLETED)),
            ScheduleRecompute.terminations(schedule, done, stray, membership),
        )
        val state = ScheduleRecompute.rebuild(schedule, done, stray, membership, on("2026-03-01"))
        assertEquals("2026-04-01", state.computedDueOn)
        assertEquals(TerminationKind.COMPLETED, state.lastTerminationKind)
    }

    /**
     * A closure is older history once a later round advances, and a recurrence **edit** does not
     * touch it (invariant 14): its `occurrence_on` may lie off the new series, which is harmless
     * because `nextDue` is computed against the new series from `max(D, E)`.
     *
     * The second half asserts the rows handed in came back untouched, which is the assertion that
     * would catch an implementation that "tidied up" old closures after an edit — the one thing an
     * implementer is warned off and the one that would destroy exported history.
     */
    @Test
    fun aClosureSurvivesLaterRoundsAndARecurrenceEdit() {
        val membership = groupOf(members = listOf(Triple("a1", "2025-12-01", null))).members
        val closures = listOf(closureOf("oc1", occurrenceOn = "2026-01-01", closedOn = "2026-02-15"))
        val before: List<OccurrenceClosure> = closures.map { it.copy() }
        val done = listOf(
            completionOf("e1", occurredOn = "2026-04-20", occurrenceOn = "2026-04-01", assetId = "a1"),
        )

        assertEquals(
            listOf(
                Termination("2026-01-01", "2026-02-15", TerminationKind.CLOSED),
                Termination("2026-04-01", "2026-04-20", TerminationKind.COMPLETED),
            ),
            ScheduleRecompute.terminations(quarterly(), done, closures, membership),
        )

        // The edit: a new anchor and a monthly cadence, on which 2026-01-01 is not a series date.
        val edited = quarterly(anchorOn = "2026-02-10", interval = 1, createdOn = "2026-05-01")
        val state = ScheduleRecompute.rebuild(edited, done, closures, membership, on("2026-05-01"))
        assertEquals(2, ScheduleRecompute.terminations(edited, done, closures, membership).size)
        assertEquals("2026-05-10", state.computedDueOn)
        assertEquals(before, closures)
    }

    /**
     * And after an event is deleted (invariants 24, 41). Round two's completion goes, so round two is
     * open again, `last` falls back to round one's closure, and the due date returns to the
     * closure-derived value — with no stored pointer to undo, because there is none.
     */
    @Test
    fun deletingALaterCompletionReopensItsRoundAndFallsBackToTheClosure() {
        val schedule = quarterly()
        val membership = groupOf(members = listOf(Triple("a1", "2025-12-01", null))).members
        val closures = listOf(closureOf("oc1", occurrenceOn = "2026-01-01", closedOn = "2026-02-15"))
        val done = listOf(
            completionOf("e1", occurredOn = "2026-04-20", occurrenceOn = "2026-04-01", assetId = "a1"),
        )

        val advanced = ScheduleRecompute.rebuild(schedule, done, closures, membership, on("2026-05-01"))
        assertEquals("2026-07-01", advanced.computedDueOn)

        val reopened = ScheduleRecompute.rebuild(schedule, emptyList(), closures, membership, on("2026-05-01"))
        assertEquals("2026-04-01", reopened.computedDueOn)
        assertEquals("2026-02-15", reopened.lastTerminationEffectiveOn)
        assertEquals(TerminationKind.CLOSED, reopened.lastTerminationKind)
    }

    /**
     * Closure is the **more durable** of the two facts, which is what D-8 asked for: a closed round
     * stays closed whatever `required(D)` later becomes, while a completed round reopens when a
     * membership change grows `required(D)` past what was done.
     *
     * The growth is a merge's doing — a membership row arriving with an `added_at` before the round
     * opened — which is exactly why the two are asserted side by side on one history.
     */
    @Test
    fun aClosedRoundStaysClosedUnderMembershipChurnWhileACompletedOneReopens() {
        val schedule = quarterly()
        val one = groupOf(members = listOf(Triple("a1", "2025-12-01", null))).members
        val two = groupOf(
            members = listOf(Triple("a1", "2025-12-01", null), Triple("a2", "2025-11-01", null)),
        ).members
        val closures = listOf(closureOf("oc1", occurrenceOn = "2026-01-01", closedOn = "2026-02-15"))
        val done = listOf(
            completionOf("e1", occurredOn = "2026-02-10", occurrenceOn = "2026-01-01", assetId = "a1"),
        )

        assertEquals(
            TerminationKind.CLOSED,
            ScheduleRecompute.terminations(schedule, emptyList(), closures, two).single().kind,
        )
        assertEquals(
            TerminationKind.COMPLETED,
            ScheduleRecompute.terminations(schedule, done, emptyList(), one).single().kind,
        )
        assertEquals(
            emptyList(),
            ScheduleRecompute.terminations(schedule, done, emptyList(), two),
            "a membership change that grows the required set reopens a completed round",
        )
    }

    /**
     * A postponement moves the date the schedule is *offered* on and nothing else: the occurrence key
     * a completion will claim stays the computed one (invariant 21). Stamping the postponed date
     * would make a postpone move a round's identity, and the round would reopen the moment the
     * postponement was cleared.
     */
    @Test
    fun aPostponementNeverMovesTheOccurrenceKey() {
        val schedule = quarterly(postponedDueOn = "2026-02-20")
        val membership = groupOf(members = listOf(Triple("a1", "2025-12-01", null))).members

        val state = ScheduleRecompute.rebuild(schedule, emptyList(), emptyList(), membership, on("2026-02-01"))
        assertEquals("2026-01-01", state.computedDueOn)
        assertEquals("2026-02-20", state.effectiveDueOn)
        assertEquals(
            "2026-01-01",
            ScheduleRecompute.currentOccurrenceOn(schedule, emptyList(), emptyList(), membership),
        )
    }

    /**
     * The lifecycle bound (D-16), as arithmetic: a retirement closes a window **at** its date, so
     * the boundary is `retiredOn <= openOn` and nothing wider. Asserted on three rounds a day apart
     * either side of the retirement, because a bound that is off by one day is the plausible error
     * and nothing else here would catch it.
     *
     * An archived member is dropped outright — the known limit, because `AssetStatus` carries no
     * instant to bound it with — and the **input list is not mutated**, which is what says the bound
     * is a derivation and not an edit.
     */
    @Test
    fun theLifecycleBoundClosesAWindowAtItsRetirementDateAndDropsAnArchivedMember() {
        val members = groupOf(
            members = listOf(Triple("a1", "2025-12-01", null), Triple("a2", "2025-12-01", null)),
        ).members
        val copy = members.map { it.copy() }
        val retiredOnTheTwentieth = GroupOccurrences.withLifecycle(members) { assetId ->
            when (assetId) {
                AssetId("a2") -> MemberLifecycle(archived = false, retiredAt = dayMillis("2026-02-20"))
                else -> null
            }
        }

        fun requiredFor(openOn: String, bounded: List<GroupMember>) =
            GroupOccurrences.requiredOn(
                quarterly(createdOn = openOn), emptyList(), emptyList(), bounded, "2026-04-01",
            )

        assertEquals(listOf(AssetId("a1"), AssetId("a2")), requiredFor("2026-02-19", retiredOnTheTwentieth))
        assertEquals(listOf(AssetId("a1")), requiredFor("2026-02-20", retiredOnTheTwentieth))
        assertEquals(listOf(AssetId("a1")), requiredFor("2026-02-21", retiredOnTheTwentieth))

        val archived = GroupOccurrences.withLifecycle(members) { assetId ->
            when (assetId) {
                AssetId("a1") -> MemberLifecycle(archived = true, retiredAt = null)
                else -> null
            }
        }
        assertEquals(listOf(AssetId("a2")), requiredFor("2026-02-19", archived))

        // An already-closed window is only ever narrowed, never widened by a later retirement.
        val closedEarlier = groupOf(members = listOf(Triple("a2", "2025-12-01", "2026-01-15"))).members
        val stillClosedOnTheFifteenth = GroupOccurrences.withLifecycle(closedEarlier) {
            MemberLifecycle(archived = false, retiredAt = dayMillis("2026-02-20"))
        }
        assertEquals(dayMillis("2026-01-15"), stillClosedOnTheFifteenth.single().removedAt)

        assertEquals(copy, members, "the bound reads the windows, it never edits them")
    }

    /**
     * The three derived predicates, over one round: what "3 of 5 complete" renders, when a round is
     * finished, and the one case where a non-member's imported completion must not inflate progress.
     */
    @Test
    fun theDerivedSetsAnswerProgressAndCompleteness() {
        val schedule = quarterly()
        val membership = groupOf(
            members = listOf(
                Triple("a1", "2025-12-01", null),
                Triple("a2", "2025-12-01", null),
                Triple("a3", "2025-12-01", null),
            ),
        ).members
        val partial = listOf(
            completionOf("e1", occurredOn = "2026-01-05", occurrenceOn = "2026-01-01", assetId = "a1"),
            // A completion for an asset no window covers: countable as a row, not as progress.
            completionOf("e2", occurredOn = "2026-01-05", occurrenceOn = "2026-01-01", assetId = "a9"),
        )

        val occurrence = GroupOccurrences.on(schedule, partial, emptyList(), membership, "2026-01-01")
        assertTrue(occurrence.isActionable)
        assertFalse(occurrence.isComplete)
        assertEquals(1 to 3, occurrence.progress)
        assertEquals(listOf(AssetId("a1"), AssetId("a9")), occurrence.completed)

        val all = partial + listOf(
            completionOf("e3", occurredOn = "2026-01-06", occurrenceOn = "2026-01-01", assetId = "a2"),
            completionOf("e4", occurredOn = "2026-01-07", occurrenceOn = "2026-01-01", assetId = "a3"),
        )
        val finished = GroupOccurrences.on(schedule, all, emptyList(), membership, "2026-01-01")
        assertTrue(finished.isComplete)
        assertEquals(3 to 3, finished.progress)
    }
}
