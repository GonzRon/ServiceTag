package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.schedule.statusOf
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
import com.loosecannon.servicetag.core.testing.InMemorySeasonActivationRepository
import com.loosecannon.servicetag.core.testing.InMemoryTagRepository
import com.loosecannon.servicetag.core.testing.dayMillis
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per-member group completion: "Complete selected" and "Complete all".
 *
 * Every test asserts what was **not** written as well as what was. The failure this file exists to
 * prevent is a group completion that behaves like a schedule-level one — a single event that stands
 * in for everybody, or a loop that records work nobody did.
 */
class GroupCompletionTest {

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
    private val uow = FakeUnitOfWork(
        assets, tags, defs, profiles, events, attachments, groups, closures, schedules, states,
    )

    private var seq = 0
    private val ids = IdGenerator { "id-${++seq}" }
    private var now = dayMillis("2026-01-01")
    private val clock = Clock { now }
    private var today = LocalDate.parse("2026-04-15")
    private val todayPort = Today { today }

    /** Counts writes to the schedule row, which is what the conditional-write rule is about. */
    private var scheduleWrites = 0
    private val countedSchedules = object : ScheduleRepository by schedules {
        override suspend fun upsert(schedule: MaintenanceSchedule) {
            scheduleWrites += 1
            schedules.upsert(schedule)
        }
    }

    private val recompute =
        RecomputeSchedules(
            schedules, states, events, closures, groups, assets, InMemorySeasonActivationRepository(), todayPort, clock,
        ) { ZoneOffset.UTC }
    private val saveGroup = SaveGroup(groups, assets, uow, ids, clock)
    private val saveSchedule =
        SaveSchedule(countedSchedules, assets, groups, defs, profiles, uow, ids, clock, recompute)
    private val postpone = PostponeSchedule(countedSchedules, uow, recompute)
    private val completeMembers = CompleteGroupMembers(
        countedSchedules, groups, events, closures, defs, profiles, uow, ids, clock, recompute,
    )
    private val closeRound =
        CloseRound(countedSchedules, closures, uow, ids, clock, todayPort, recompute)
    private val logEvent = LogEvent(events, defs, profiles, assets, uow, ids, clock, recompute)
    private val archiveAsset = ArchiveAsset(assets, uow, clock) { recompute.forAsset(it) }

    private suspend fun seedAsset(id: String): AssetId {
        val asset = Asset(id = AssetId(id), name = "Feeder $id", createdAt = 1L, updatedAt = 1L)
        assets.upsert(asset)
        return asset.id
    }

    private suspend fun seedGroup(assetIds: List<AssetId>): GroupId = saveGroup.run(
        null,
        GroupCommand(
            name = "North run",
            members = assetIds.mapIndexed { index, id ->
                GroupMemberInput(assetId = id, sortOrder = index)
            },
        ),
    ).id

    private suspend fun seedSchedule(
        groupId: GroupId,
        title: String = "Top up feeders",
        basis: TimeBasis = TimeBasis.FIXED,
        interval: Int = 3,
        anchorOn: String = "2026-01-01",
    ): MaintenanceSchedule = saveSchedule.run(
        null,
        ScheduleCommand(
            targetAssetId = null,
            targetGroupId = groupId,
            title = title,
            timeInterval = interval,
            timeUnit = RecurrenceUnit.MONTH,
            timeBasis = basis,
            anchorOn = anchorOn,
        ),
    )

    private fun completion(occurredOn: String) =
        CompletionCommand(occurredOn = occurredOn, tzId = "UTC")

    private suspend fun eventsFor(assetId: AssetId): List<AssetEvent> =
        events.all().filter { it.assetId == assetId }

    private suspend fun keyOf(schedule: MaintenanceSchedule): String =
        assertNotNull(recompute.occurrenceOf(schedule)).occurrenceOn.toString()

    /**
     * Completing one member records **one** event, on that member's own Asset, and leaves every
     * other member's history byte-identical. The round stays open and the schedule stays due, which
     * is #55 AC 6 and D-7: a partially done round is not a done round.
     *
     * The schedule row is not touched at all, which is the assertion that fails if an implementation
     * "advances" the schedule on a member's completion.
     */
    @Test
    fun completingOneMemberLeavesEveryOtherMembersHistoryUntouchedAndTheRoundOpen() = runTest {
        val a1 = seedAsset("a1")
        val a2 = seedAsset("a2")
        val a3 = seedAsset("a3")
        val schedule = seedSchedule(seedGroup(listOf(a1, a2, a3)))
        val writesAfterSave = scheduleWrites

        val done = completeMembers.run(schedule.id, listOf(a1), completion("2026-04-15")).single()
        assertEquals(a1, done.assetId)
        assertEquals(schedule.id, done.scheduleId)
        assertEquals("2026-01-01", done.occurrenceOn)
        assertEquals(EventSource.SCHEDULE_QUICK_COMPLETE, done.source)
        assertEquals(EventKind.MAINTENANCE, done.kind)
        assertEquals(schedule.title, done.title)

        assertEquals(emptyList(), eventsFor(a2))
        assertEquals(emptyList(), eventsFor(a3))
        assertEquals(1, events.all().size)
        assertEquals(writesAfterSave, scheduleWrites, "a member completion writes no schedule column")
        assertEquals(schedule, schedules.get(schedule.id))

        val round = assertNotNull(recompute.occurrenceOf(schedule))
        assertEquals(1 to 3, round.progress)
        assertTrue(round.isActionable)
        assertEquals(false, round.isComplete)

        val state = assertNotNull(states.get(schedule.id))
        assertEquals("2026-01-01", state.computedDueOn, "the round is still the one that was open")
        assertEquals(DueStatus.OVERDUE, statusOf(schedule, state, today))
    }

    /**
     * "Complete all" writes one event per **not-yet-completed required** member, in one
     * transaction, and a member already done is skipped rather than written again or refused
     * (invariants 31, 32).
     *
     * The last third is the honest form of "run it again": within one round, a repeat writes
     * nothing. Once the round is finished the schedule has advanced, so a further "Complete all"
     * is a completion of the *next* round — legitimate, and asserted here to be exactly that rather
     * than a second event on the round just closed out.
     */
    @Test
    fun completeAllWritesOneEventPerOutstandingMemberAndARepeatWritesNothing() = runTest {
        val a1 = seedAsset("a1")
        val a2 = seedAsset("a2")
        val a3 = seedAsset("a3")
        val schedule = seedSchedule(seedGroup(listOf(a1, a2, a3)))

        // A repeat inside one call is absorbed: one event, not two, and not an error.
        assertEquals(1, completeMembers.run(schedule.id, listOf(a1, a1), completion("2026-04-15")).size)
        // And a repeat across calls, for the same round, writes nothing at all.
        val commitsBefore = uow.commits
        assertEquals(emptyList(), completeMembers.run(schedule.id, listOf(a1), completion("2026-04-15")))
        assertEquals(commitsBefore, uow.commits, "a no-op opens no transaction")

        val batch = completeMembers.all(schedule.id, completion("2026-04-15"))
        assertEquals(setOf(a2, a3), batch.map { it.assetId }.toSet())
        assertEquals(commitsBefore + 1, uow.commits, "Complete all is one write")

        val firstRound = events.all().filter { it.occurrenceOn == "2026-01-01" }
        assertEquals(3, firstRound.size)
        assertEquals(listOf(a1, a2, a3), firstRound.map { it.assetId }.sortedBy { it.value })
        assertEquals(3, firstRound.map { it.assetId }.distinct().size, "one event per member")

        // The round finished, so the schedule advanced; a further Complete all is the next round's.
        val state = assertNotNull(states.get(schedule.id))
        assertEquals("2026-07-01", state.computedDueOn)
        val firstIds = firstRound.map { it.id }.toSet()
        val next = completeMembers.all(schedule.id, completion("2026-04-15"))
        assertEquals(3, next.size)
        assertTrue(next.all { it.occurrenceOn == "2026-07-01" })
        assertEquals(firstIds, events.all().filter { it.occurrenceOn == "2026-01-01" }.map { it.id }.toSet())
    }

    /**
     * An asset the round does not oblige is refused and **nothing is written** — not even for the
     * members in the same call that were fine, because validation runs before the transaction.
     *
     * The two refusals are different facts: `a9` was never in the group, and `a4` is a member whose
     * window opened after this round did. A surface that could not tell them apart would eventually
     * tell an owner the wrong one.
     */
    @Test
    fun completingANonMemberOrAnUnrequiredMemberIsRefusedAndWritesNothing() = runTest {
        val a1 = seedAsset("a1")
        val a4 = seedAsset("a4")
        val a9 = seedAsset("a9")
        val groupId = seedGroup(listOf(a1))
        val schedule = seedSchedule(groupId)

        now = dayMillis("2026-02-01")
        val group = assertNotNull(groups.get(groupId))
        saveGroup.run(
            groupId,
            GroupCommand(
                name = group.name,
                members = group.members.map { GroupMemberInput(assetId = it.assetId, id = it.id) } +
                    GroupMemberInput(assetId = a4, sortOrder = 1),
            ),
        )

        assertFailsWith<NotAGroupMember> {
            completeMembers.run(schedule.id, listOf(a9), completion("2026-04-15"))
        }
        assertFailsWith<NotARequiredMember> {
            completeMembers.run(schedule.id, listOf(a4), completion("2026-04-15"))
        }
        assertFailsWith<NotARequiredMember> {
            completeMembers.run(schedule.id, listOf(a1, a4), completion("2026-04-15"))
        }
        assertEquals(emptyList(), events.all())
    }

    /**
     * Once a round is closed no completion may claim it (invariant 39), and the work is still
     * loggable — as an ordinary journal event with no `schedule_id`, which is spec §2.9's deliberate
     * consequence rather than a gap.
     *
     * **How this state is reached, and why it matters that it is hard to.** A completion's
     * `occurrence_on` is stamped from the current round, and since the engine fix a terminated round
     * is never the current one, so a locally closed round is unreachable through this path at all —
     * the guard is defence in depth. The one state that still reaches it is an imported closure on a
     * round that is **not** a termination: a round that obliges nobody, which is the shape a merge
     * can deliver. That is what is built here, by seeding the row the way an import does.
     *
     * The last two assertions discriminate the guard from the emptiness: with the closure the answer
     * is `OccurrenceClosed`, and without it the very same round answers `OccurrenceNotActionable`.
     */
    @Test
    fun aCompletionOfAClosedRoundIsRefusedWhileAnOrdinaryEventStillSucceeds() = runTest {
        val a1 = seedAsset("a1")
        val groupId = seedGroup(listOf(a1))
        val schedule = seedSchedule(groupId)

        // The member leaves, and finishing the round it was still required for opens an empty one.
        now = dayMillis("2026-02-01")
        saveGroup.run(groupId, GroupCommand(name = "North run"))
        completeMembers.all(schedule.id, completion("2026-04-15"))
        val open = assertNotNull(recompute.occurrenceOf(schedule))
        assertEquals(emptyList(), open.required)
        assertFailsWith<OccurrenceNotActionable> {
            completeMembers.run(schedule.id, listOf(a1), completion("2026-04-16"))
        }

        closures.insert(
            OccurrenceClosure(
                id = "imported-closure",
                scheduleId = schedule.id,
                occurrenceOn = open.occurrenceOn.toString(),
                closedOn = "2026-04-10",
                createdAt = dayMillis("2026-04-10"),
            ),
        )
        assertEquals(
            open.occurrenceOn,
            assertNotNull(recompute.occurrenceOf(schedule)).occurrenceOn,
            "a closure on a round that obliges nobody is not a termination, so the round stays",
        )

        val eventsBefore = events.all()
        assertFailsWith<OccurrenceClosed> {
            completeMembers.run(schedule.id, listOf(a1), completion("2026-04-16"))
        }
        assertEquals(eventsBefore, events.all())

        val logged = logEvent.run(
            EventCommand(
                assetId = a1,
                profileId = null,
                kind = EventKind.MAINTENANCE,
                title = "Topped up anyway",
                occurredOn = "2026-04-16",
                occurredTime = null,
                tzId = "UTC",
                notes = "",
                values = emptyMap(),
                consumables = emptyList(),
            ),
        )
        assertNull(logged.scheduleId)
        assertNull(logged.occurrenceOn)
        assertEquals(eventsBefore.size + 1, events.all().size)
    }

    /**
     * An asset in two groups with the same operation has two schedules, two rounds and two
     * obligations. Completing one leaves the other due, with **no** semantic deduplication by
     * operation name (D-11) — which would silently drop a real obligation on real equipment.
     */
    @Test
    fun oneAssetInTwoGroupsKeepsTwoObligations() = runTest {
        val a1 = seedAsset("a1")
        val north = seedSchedule(seedGroup(listOf(a1)), title = "Top up feeders")
        val south = seedSchedule(seedGroup(listOf(a1)), title = "Top up feeders")
        assertTrue(north.id != south.id)

        completeMembers.all(north.id, completion("2026-04-15"))

        assertEquals("2026-07-01", assertNotNull(states.get(north.id)).computedDueOn)
        val other = assertNotNull(states.get(south.id))
        assertEquals("2026-01-01", other.computedDueOn, "the other group's round is untouched")
        assertEquals(DueStatus.OVERDUE, statusOf(south, other, today))
        assertEquals(1, events.all().size, "one obligation was met, not two")
    }

    /**
     * A postponement moves the date the round is offered on and nothing else: the key a completion
     * claims is the **computed** date (invariant 21), a partial completion leaves the agreed date
     * alone, and the write that finishes the round consumes it — writing that one column and leaving
     * `updated_at` where it is, because only an edit moves the pin's floor (invariants 19, 25, 69).
     */
    @Test
    fun theOccurrenceKeyIsTheUnpostponedDateAndThePostponementDiesWithTheRound() = runTest {
        val a1 = seedAsset("a1")
        val a2 = seedAsset("a2")
        val schedule = seedSchedule(seedGroup(listOf(a1, a2)))
        val postponed = postpone.run(schedule.id, "2026-05-01")
        assertEquals("2026-05-01", postponed.postponedDueOn)
        assertEquals(schedule.updatedAt, postponed.updatedAt)

        val writesBefore = scheduleWrites
        val first = completeMembers.run(schedule.id, listOf(a1), completion("2026-04-15")).single()
        assertEquals("2026-01-01", first.occurrenceOn, "the computed date, never the postponed one")
        assertEquals("2026-05-01", assertNotNull(schedules.get(schedule.id)).postponedDueOn)
        assertEquals(writesBefore, scheduleWrites, "a partial completion writes no schedule column")

        val second = completeMembers.run(schedule.id, listOf(a2), completion("2026-04-16")).single()
        assertEquals("2026-01-01", second.occurrenceOn)
        val after = assertNotNull(schedules.get(schedule.id))
        assertNull(after.postponedDueOn, "the termination consumed the postponement")
        assertEquals(schedule.updatedAt, after.updatedAt, "the pin's floor did not move")
        assertEquals(writesBefore + 1, scheduleWrites, "exactly one column write, on the termination")
    }

    /**
     * The empty-required-set rule, end to end (invariants 74, 77):
     *
     * - a group-targeted schedule on a group with **no** members is refused outright, because its
     *   first round could never terminate;
     * - a round whose members were all removed before it opened reports `NO_DATA`, is not counted as
     *   due, cannot be completed and **cannot be closed**.
     *
     * Emptiness never means complete: the vacuous `⊇` over an empty set is the R1 defect, and it
     * would advance this schedule on nobody's work.
     */
    @Test
    fun anEmptyGroupIsRefusedAtCreationAndAnEmptiedRoundIsNotActionable() = runTest {
        val a1 = seedAsset("a1")
        val barren = saveGroup.run(null, GroupCommand(name = "Nobody home")).id
        val problems = assertFailsWith<ScheduleValidation> { seedSchedule(barren) }.problems
        assertTrue(ScheduleProblem.EmptyGroupTarget in problems)
        assertEquals(emptyList(), schedules.all())

        val groupId = seedGroup(listOf(a1))
        val schedule = seedSchedule(groupId)

        // The member leaves, and only then is the round it was still required for finished off.
        now = dayMillis("2026-02-01")
        saveGroup.run(groupId, GroupCommand(name = "North run"))
        completeMembers.all(schedule.id, completion("2026-04-15"))

        val state = assertNotNull(states.get(schedule.id))
        assertNull(state.computedDueOn)
        assertNull(state.effectiveDueOn)
        assertEquals(DueStatus.NO_DATA, statusOf(schedule, state, today))
        assertEquals(false, statusOf(schedule, state, today).countsAsDue)

        val round = assertNotNull(recompute.occurrenceOf(schedule))
        assertEquals(emptyList(), round.required)
        assertFailsWith<OccurrenceNotActionable> {
            completeMembers.run(schedule.id, listOf(a1), completion("2026-04-16"))
        }
        assertFailsWith<OccurrenceNotCloseable> { closeRound.run(schedule.id) }
        assertEquals(1, events.all().size, "nothing was written by either refusal")
        assertEquals(emptyList(), closures.all())
    }

    /**
     * The empty-group refusal counts the **lifecycle-bounded** windows, not the raw open ones: a
     * group whose only open window names an **archived** Asset obliges nobody, so its first round
     * could never terminate and the schedule is refused exactly as if the group had no members at
     * all (D-16, invariants 74, 77).
     *
     * Un-archiving restores it, which is the assertion that says the windows themselves were never
     * touched — the bound is a derivation, and the group is unchanged underneath it.
     */
    @Test
    fun aGroupWhoseOnlyOpenMemberIsArchivedIsAlsoAnEmptyGroupTarget() = runTest {
        val a1 = seedAsset("a1")
        val groupId = seedGroup(listOf(a1))
        val windows = assertNotNull(groups.get(groupId)).members

        archiveAsset.run(a1)
        val problems = assertFailsWith<ScheduleValidation> { seedSchedule(groupId) }.problems
        assertTrue(ScheduleProblem.EmptyGroupTarget in problems)
        assertEquals(emptyList(), schedules.all())
        assertEquals(windows, assertNotNull(groups.get(groupId)).members)

        archiveAsset.unarchive(a1)
        val schedule = seedSchedule(groupId)
        assertEquals(listOf(a1), assertNotNull(recompute.occurrenceOf(schedule)).required)
    }

    /** An asset-targeted schedule has no member list to be given one. */
    @Test
    fun namingMembersOfAnAssetTargetedScheduleIsRefused() = runTest {
        val a1 = seedAsset("a1")
        val schedule = saveSchedule.run(
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
        assertFailsWith<MemberCompletionNotSupported> {
            completeMembers.run(schedule.id, listOf(a1), completion("2026-04-15"))
        }
        assertFailsWith<CloseNotSupported> { closeRound.run(schedule.id) }
        assertEquals(emptyList(), events.all())
        assertEquals(emptyList(), closures.all())
    }

    /** A completion aimed at an archived schedule is a refusal about state, as it is for an asset. */
    @Test
    fun completingAnArchivedGroupScheduleIsRefused() = runTest {
        val a1 = seedAsset("a1")
        val schedule = seedSchedule(seedGroup(listOf(a1)))
        val archive = ArchiveSchedule(countedSchedules, uow, recompute)
        archive.run(schedule.id, archived = true)

        assertFailsWith<ScheduleArchived> {
            completeMembers.run(schedule.id, listOf(a1), completion("2026-04-15"))
        }
        assertFailsWith<ScheduleArchived> { closeRound.run(schedule.id) }
        assertEquals(emptyList(), events.all())
        assertEquals(emptyList(), closures.all())
    }
}
