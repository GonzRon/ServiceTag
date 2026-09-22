package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.TerminationKind
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.testing.FakeAttachmentStorage
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentRepository
import com.loosecannon.servicetag.core.testing.InMemoryClosureRepository
import com.loosecannon.servicetag.core.testing.InMemoryDefinitionRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryGroupRepository
import com.loosecannon.servicetag.core.testing.InMemoryProfileRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.InMemoryTagRepository
import com.loosecannon.servicetag.core.testing.dayMillis
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Close this round" — the D-8 fact.
 *
 * The tests are as much about what a close does **not** write as about the row it does: a closed
 * round must never become a claim that somebody did the work, and the row can never be amended, so
 * every refusal is load-bearing rather than defensive.
 */
class CloseRoundTest {

    private val assets = InMemoryAssetRepository()
    private val defs = InMemoryDefinitionRepository()
    private val profiles = InMemoryProfileRepository()
    private val events = InMemoryEventRepository()
    private val attachments = InMemoryAttachmentRepository()
    private val tags = InMemoryTagRepository()
    private val groups = InMemoryGroupRepository()
    private val closures = InMemoryClosureRepository()
    private val states = InMemoryScheduleStateRepository()
    private val schedules = InMemoryScheduleRepository(closures, states)
    private val storage = FakeAttachmentStorage()
    private val uow = FakeUnitOfWork(
        assets, tags, defs, profiles, events, attachments, groups, closures, schedules, states,
    )

    private var seq = 0
    private val ids = IdGenerator { "id-${++seq}" }
    private var now = dayMillis("2026-01-01")
    private val clock = Clock { now }
    private var today = LocalDate.parse("2026-02-15")
    private val todayPort = Today { today }

    private var scheduleWrites = 0
    private val countedSchedules = object : ScheduleRepository by schedules {
        override suspend fun upsert(schedule: MaintenanceSchedule) {
            scheduleWrites += 1
            schedules.upsert(schedule)
        }
    }

    private val recompute =
        RecomputeSchedules(schedules, states, events, closures, groups, assets, todayPort, clock)
    private val saveGroup = SaveGroup(groups, assets, uow, ids, clock)
    private val saveSchedule =
        SaveSchedule(countedSchedules, assets, groups, defs, profiles, uow, ids, clock, recompute)
    private val postpone = PostponeSchedule(countedSchedules, uow, recompute)
    private val completeMembers = CompleteGroupMembers(
        countedSchedules, groups, events, closures, defs, profiles, uow, ids, clock, recompute,
    )
    private val closeRound =
        CloseRound(countedSchedules, closures, uow, ids, clock, todayPort, recompute)
    private val deleteEvent = DeleteEvent(events, attachments, storage, uow, recompute)

    private suspend fun seedAsset(id: String): AssetId {
        val asset = Asset(id = AssetId(id), name = "Feeder $id", createdAt = 1L, updatedAt = 1L)
        assets.upsert(asset)
        return asset.id
    }

    private suspend fun seedGroup(assetIds: List<AssetId>, name: String = "North run"): GroupId =
        saveGroup.run(
            null,
            GroupCommand(
                name = name,
                members = assetIds.mapIndexed { index, id ->
                    GroupMemberInput(assetId = id, sortOrder = index)
                },
            ),
        ).id

    private suspend fun seedSchedule(
        groupId: GroupId,
        basis: TimeBasis = TimeBasis.FIXED,
        interval: Int = 3,
        anchorOn: String = "2026-01-01",
    ): MaintenanceSchedule = saveSchedule.run(
        null,
        ScheduleCommand(
            targetAssetId = null,
            targetGroupId = groupId,
            title = "Top up feeders",
            timeInterval = interval,
            timeUnit = RecurrenceUnit.MONTH,
            timeBasis = basis,
            anchorOn = anchorOn,
        ),
    )

    private fun completion(occurredOn: String) =
        CompletionCommand(occurredOn = occurredOn, tzId = "UTC")

    /**
     * All three halves of "closing writes too much", in one test: **one** closure row, **no** column
     * on `maintenance_schedule` — its `updated_at` byte-identical — and **no** `asset_event` on any
     * member, with every member's history unchanged (invariants 35, 36, 68).
     *
     * The natural wrong implementations each fail one of the three: stamping the schedule "so the
     * next date is stored" fails the second, and fabricating an event "so the round looks done"
     * fails the third. The round is left partially complete on purpose, which is the state the
     * action exists for.
     */
    @Test
    fun closingWritesExactlyOneClosureRowAndNothingElse() = runTest {
        val a1 = seedAsset("a1")
        val a2 = seedAsset("a2")
        val schedule = seedSchedule(seedGroup(listOf(a1, a2)))
        completeMembers.run(schedule.id, listOf(a1), completion("2026-02-01"))
        val historyBefore = events.all()
        val writesBefore = scheduleWrites

        val closure = closeRound.run(schedule.id, closedOn = "2026-02-15")

        assertEquals(listOf(closure), closures.all())
        assertEquals(schedule.id, closure.scheduleId)
        assertEquals("2026-01-01", closure.occurrenceOn, "the round's own key, not today")
        assertEquals("2026-02-15", closure.closedOn)
        assertEquals(now, closure.createdAt)

        assertEquals(writesBefore, scheduleWrites, "no column on the schedule row")
        assertEquals(schedule, schedules.get(schedule.id))
        assertEquals(schedule.updatedAt, assertNotNull(schedules.get(schedule.id)).updatedAt)
        assertEquals(historyBefore, events.all(), "no event on any member, and none changed")

        // And the recurrence advanced from the closure, without claiming the work was done.
        val state = assertNotNull(states.get(schedule.id))
        assertEquals("2026-04-01", state.computedDueOn)
        assertEquals("2026-02-15", state.lastTerminationEffectiveOn)
        assertEquals(TerminationKind.CLOSED, state.lastTerminationKind)
        assertEquals("2026-02-01", state.lastCompletedOn, "the one member who did work still shows")
    }

    /**
     * A second closure for **one round** is refused and the first row stands, unchanged
     * (invariant 38).
     *
     * **How this state is reached.** Since the engine fix a closed round always advances, so closing
     * twice in a row closes two *different* rounds — which is legitimate, and asserted in the test
     * below. The only way the open round can already carry a closure is an imported row on a round
     * that is **not** a termination: one that obliges nobody, the shape a merge can deliver. That is
     * built here by seeding the row the way an import does.
     *
     * The last pair of assertions discriminates the guard from the emptiness: with the row the
     * answer is `OccurrenceAlreadyClosed`, and the same round without it answers
     * `OccurrenceNotCloseable`.
     */
    @Test
    fun closingTwiceIsRefusedAndTheFirstRowStands() = runTest {
        val a1 = seedAsset("a1")
        val groupId = seedGroup(listOf(a1))
        val schedule = seedSchedule(groupId)

        now = dayMillis("2026-02-01")
        saveGroup.run(groupId, GroupCommand(name = "North run"))
        completeMembers.all(schedule.id, completion("2026-02-10"))
        val open = assertNotNull(recompute.occurrenceOf(schedule))
        assertEquals(emptyList(), open.required)
        assertFailsWith<OccurrenceNotCloseable> { closeRound.run(schedule.id) }

        val imported = OccurrenceClosure(
            id = "imported-closure",
            scheduleId = schedule.id,
            occurrenceOn = open.occurrenceOn.toString(),
            closedOn = "2026-02-05",
            createdAt = dayMillis("2026-02-05"),
        )
        closures.insert(imported)

        today = LocalDate.parse("2026-02-20")
        assertFailsWith<OccurrenceAlreadyClosed> { closeRound.run(schedule.id, closedOn = "2026-02-20") }
        assertEquals(listOf(imported), closures.all(), "the first closure is untouched")
    }

    /**
     * Each round gets its own closure row, and closing a later one never touches an earlier one: two
     * rows, two keys, and the first byte-identical afterwards. A closed round advances, which is what
     * makes "closing twice" a question about one round rather than about the action.
     */
    @Test
    fun eachRoundGetsItsOwnClosureAndAnOlderOneIsNeverAmended() = runTest {
        val a1 = seedAsset("a1")
        val schedule = seedSchedule(seedGroup(listOf(a1)))

        val first = closeRound.run(schedule.id, closedOn = "2026-02-15")
        assertEquals("2026-01-01", first.occurrenceOn)
        assertEquals("2026-04-01", assertNotNull(states.get(schedule.id)).computedDueOn)

        today = LocalDate.parse("2026-04-20")
        now = dayMillis("2026-04-20")
        val second = closeRound.run(schedule.id, closedOn = "2026-04-20")
        assertEquals("2026-04-01", second.occurrenceOn)
        assertEquals("2026-07-01", assertNotNull(states.get(schedule.id)).computedDueOn)

        assertEquals(listOf(first, second), closures.all().sortedBy { it.occurrenceOn })
        assertEquals(first, assertNotNull(closures.find(schedule.id, "2026-01-01")))
    }

    /**
     * The two reachable refusals about the round itself: an asset target has no stuck round to close,
     * and a round that obliges nobody is not a round (invariant 77).
     *
     * `OccurrenceAlreadyComplete` is the third refusal the brief names, and since the engine fix it
     * is **unreachable by construction** — see the test below, which asserts the property rather
     * than the refusal. It stays in the code as defence: a closure recorded against a round that was
     * in fact finished would be a false statement in exported history, and the engine's own
     * precedence rule only makes such a row inert, not absent.
     */
    @Test
    fun closingIsRefusedForAnAssetTargetAndForARoundThatObligesNobody() = runTest {
        val a1 = seedAsset("a1")
        val asset = saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = a1,
                targetGroupId = null,
                title = "Filter change",
                timeInterval = 3,
                timeUnit = RecurrenceUnit.MONTH,
                anchorOn = "2026-01-01",
            ),
        )
        assertFailsWith<CloseNotSupported> { closeRound.run(asset.id) }

        val a2 = seedAsset("a2")
        val groupId = seedGroup(listOf(a2), name = "South run")
        val emptied = seedSchedule(groupId)
        now = dayMillis("2026-02-01")
        saveGroup.run(groupId, GroupCommand(name = "South run"))
        completeMembers.all(emptied.id, completion("2026-02-10"))
        assertFailsWith<OccurrenceNotCloseable> { closeRound.run(emptied.id) }

        assertEquals(emptyList(), closures.all(), "neither refusal wrote a row")
    }

    /**
     * The property `OccurrenceAlreadyComplete` guards, asserted directly: **the open round is never
     * one that is already complete.** A completion that covers the required set terminates the round,
     * and since the engine fix a termination always advances the schedule strictly past its own key —
     * on both bases, and including the backdated case that used to land back on it.
     */
    @Test
    fun theOpenRoundIsNeverOneThatIsAlreadyComplete() = runTest {
        val a1 = seedAsset("a1")
        val schedule = seedSchedule(
            seedGroup(listOf(a1)),
            basis = TimeBasis.COMPLETION,
            interval = 1,
            anchorOn = "2026-02-01",
        )
        today = LocalDate.parse("2026-02-10")
        assertEquals("2026-02-01", assertNotNull(recompute.occurrenceOf(schedule)).occurrenceOn.toString())

        // Backdated a whole interval — the shape that used to leave the finished round current.
        completeMembers.run(schedule.id, listOf(a1), completion("2026-01-01"))

        val open = assertNotNull(recompute.occurrenceOf(schedule))
        assertEquals("2026-03-01", open.occurrenceOn.toString())
        assertEquals(false, open.isComplete)
        assertEquals(emptyList(), open.completed)
        assertEquals(listOf(a1), open.required)
    }

    /**
     * `closedOn` defaults to today, and the accepted range is the round's **open date** through
     * today, inclusive (invariant 78). Both ends are accepted and both neighbours refused.
     *
     * Each accepted value needs its own round, because a close advances the schedule — the ends of
     * the range are asserted on three separate groups rather than by reopening one, which would need
     * the amendment the fact forbids.
     */
    @Test
    fun closedOnDefaultsToTodayAndSpansTheOpenDateThroughToday() = runTest {
        suspend fun round(suffix: String): MaintenanceSchedule {
            val asset = seedAsset("a-$suffix")
            return seedSchedule(seedGroup(listOf(asset), name = "Run $suffix"))
        }

        // Every schedule below was created on 2026-01-01, so every round opened on 2026-01-01.
        today = LocalDate.parse("2026-02-15")
        val byDefault = round("default")
        assertEquals("2026-02-15", closeRound.run(byDefault.id).closedOn)

        val atOpen = round("open")
        assertEquals("2026-01-01", closeRound.run(atOpen.id, closedOn = "2026-01-01").closedOn)

        val atToday = round("today")
        assertEquals("2026-02-15", closeRound.run(atToday.id, closedOn = "2026-02-15").closedOn)

        val refused = round("refused")
        val before = assertFailsWith<ClosedOnOutOfRange> {
            closeRound.run(refused.id, closedOn = "2025-12-31")
        }
        assertEquals("2026-01-01", before.earliestOn)
        assertFailsWith<ClosedOnOutOfRange> { closeRound.run(refused.id, closedOn = "2026-02-16") }
        assertFailsWith<BadScheduleDate> { closeRound.run(refused.id, closedOn = "next week") }
        assertNull(closures.find(refused.id, "2026-01-01"))
        assertEquals(3, closures.all().size)
    }

    /**
     * The floor is the round's open date **clamped to today**, and the clamp is what keeps the range
     * from ever being empty.
     *
     * The open instant's calendar date is taken at UTC, deliberately, for the engine's purity; today
     * is the device-local date. In a negative UTC offset the two differ by a day on the round's
     * opening evening — the round below opens at `2026-01-02T00:00Z` while the device says
     * `2026-01-01` — and an unclamped floor would refuse the **default** and every value a caller
     * could offer instead, leaving B14's "When was this done?" affordance an empty range. With the
     * clamp the default is accepted and stored as today, and the refusal names the floor it used.
     */
    @Test
    fun theDefaultIsAcceptedWhenTodayIsBehindTheOpenInstantsUtcDate() = runTest {
        val a1 = seedAsset("a1")
        now = dayMillis("2026-01-02")
        val schedule = seedSchedule(seedGroup(listOf(a1)))
        today = LocalDate.parse("2026-01-01")

        val round = assertNotNull(recompute.occurrenceOf(schedule))
        assertEquals("2026-01-02", round.openOn.toString(), "the open date is ahead of today")

        val closure = closeRound.run(schedule.id)
        assertEquals("2026-01-01", closure.closedOn, "the default is today, and today is accepted")
        assertEquals(listOf(closure), closures.all())

        // The next round opens at the same instant, so the clamped floor is what a refusal reports.
        val refused = assertFailsWith<ClosedOnOutOfRange> {
            closeRound.run(schedule.id, closedOn = "2025-12-31")
        }
        assertEquals("2026-01-01", refused.earliestOn)
        assertEquals("2026-01-01", refused.today)
        assertEquals(listOf(closure), closures.all())
    }

    /**
     * A closure clears a postponement, because the round it belonged to has ended (invariant 19) —
     * and it writes that one column only, leaving `updated_at` alone, because only an edit moves the
     * D-27 pin's floor (invariants 25, 69).
     */
    @Test
    fun closingClearsAPostponementAndNothingElse() = runTest {
        val a1 = seedAsset("a1")
        val schedule = seedSchedule(seedGroup(listOf(a1)))
        val postponed = postpone.run(schedule.id, "2026-03-01")
        val writesBefore = scheduleWrites

        closeRound.run(schedule.id, closedOn = "2026-02-15")

        val after = assertNotNull(schedules.get(schedule.id))
        assertNull(after.postponedDueOn)
        assertEquals(postponed.updatedAt, after.updatedAt)
        assertEquals(postponed.copy(postponedDueOn = null), after)
        assertEquals(writesBefore + 1, scheduleWrites, "one column write, and only because it was set")
    }

    /**
     * Deleting a later round's completion **reopens** it: `last` falls back to the earlier closure
     * and the due date returns to the closure-derived value (invariants 24, 41). There is no stored
     * "last termination" pointer to go stale, which is why this needs no bookkeeping to undo.
     */
    @Test
    fun deletingALaterCompletionReopensItsRoundAndFallsBackToTheClosure() = runTest {
        val a1 = seedAsset("a1")
        val schedule = seedSchedule(seedGroup(listOf(a1)))

        val closure = closeRound.run(schedule.id, closedOn = "2026-02-15")
        assertEquals("2026-04-01", assertNotNull(states.get(schedule.id)).computedDueOn)

        today = LocalDate.parse("2026-04-20")
        now = dayMillis("2026-04-20")
        val done = completeMembers.run(schedule.id, listOf(a1), completion("2026-04-20")).single()
        assertEquals("2026-04-01", done.occurrenceOn)
        assertEquals("2026-07-01", assertNotNull(states.get(schedule.id)).computedDueOn)

        deleteEvent.run(done.id)

        val state = assertNotNull(states.get(schedule.id))
        assertEquals("2026-04-01", state.computedDueOn, "back to the closure-derived date")
        assertEquals("2026-02-15", state.lastTerminationEffectiveOn)
        assertEquals(TerminationKind.CLOSED, state.lastTerminationKind)
        assertNull(state.lastCompletedOn)
        assertEquals(listOf(closure), closures.all(), "the closure was neither tidied nor amended")
    }
}
