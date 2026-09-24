package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.core.usecase.GroupCommand
import com.loosecannon.servicetag.core.usecase.GroupMemberInput
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.groupOf
import com.loosecannon.servicetag.testing.scheduleOf
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The group detail screen's state: who is in the group now, and what its rounds are asking.
 *
 * Every row here writes real rows through the production use cases and lets the real engine derive
 * the occurrence, so "the progress the screen shows" is the progress `occurrenceOf` computes. The
 * failure this file exists to prevent is a screen that counts today's membership: that number is
 * right on the day the group was made and wrong for ever afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupDetailViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse("2026-04-15")
        // The clock has to agree with `T`: a membership window is stamped from it, and a
        // `removed_at` at the fake graph's default instant would sit *before* every round this
        // file opens — which would take the member out of rounds it is in fact required for.
        graph.now = dayMillis("2026-04-15")
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(id: String) = GroupDetailViewModel(
        groups = graph.groups,
        assets = graph.assets,
        schedules = graph.schedules,
        states = graph.scheduleStates,
        recompute = graph.recomputeSchedules,
        archiveGroup = graph.archiveGroup,
        today = graph.todayPort,
        completion = graph.completionFlow,
        id = GroupId(id),
    )

    /** Answers the flow's affordance as soon as it opens, with the date given. */
    private suspend fun answer(occurredOn: String) {
        graph.completionFlow.prompt.first { it != null }
        assertTrue(graph.completionFlow.submit(CompletionAnswer(occurredOn = occurredOn)))
    }

    /** Five sprinklers, a group, and a group-targeted round open on them. */
    private suspend fun aRound(members: Int = 5): Pair<String, List<Asset>> {
        val assets = (1..members).map { asset("Sprinkler $it") }
        graph.groups.upsert(
            groupOf("g1", name = "North run", members = assets.map { Triple(it.id.value, "2026-01-01", null) }),
        )
        seed(scheduleOf("s-group", assetId = null, groupId = "g1", title = "Head check", anchorOn = "2026-01-01", leadDays = 0))
        return "s-group" to assets
    }

    private fun readModel(): DueReadModel = DueReadModel(
        graph.schedules, graph.assets, graph.groups,
        graph.definitions, graph.recomputeSchedules, graph.todayPort, graph.assetHealthReadModel, { null },
    )

    private suspend fun asset(name: String): Asset =
        graph.createAsset.run(AssetCommand(name = name, category = "Irrigation"))

    private suspend fun seed(schedule: MaintenanceSchedule) {
        graph.schedules.upsert(schedule)
        graph.recomputeSchedules.forSchedule(schedule.id)
    }

    /**
     * Matrix row "progress misreported". Five required members and three done reads the RATIFIED
     * "3 of 5 complete"; the round is still **open**; and the group is **one** row on the screen
     * however many members are outstanding (D-15).
     *
     * The second half is the one that matters: a sixth member added **now** does not move the open
     * round's denominator, because `required(D)` is the windows that covered the round's open
     * instant and not the membership list as it stands (invariant 33, D-10).
     */
    @Test fun progressComesFromTheRoundAndNotFromTodaysMembership() = runTest {
        val members = (1..5).map { asset("Sprinkler $it") }
        graph.groups.upsert(
            groupOf("g1", name = "North run", members = members.map { Triple(it.id.value, "2026-01-01", null) }),
        )
        seed(scheduleOf("s-group", assetId = null, groupId = "g1", title = "Head check", anchorOn = "2026-01-01", leadDays = 0))
        graph.completeGroupMembers.run(
            ScheduleId("s-group"),
            members.take(3).map { it.id },
            CompletionCommand(occurredOn = "2026-04-14", tzId = "UTC"),
        )

        val vm = viewModel("g1")
        backgroundScope.launch { vm.state.collect() }
        val state = vm.state.first { it != null && it.schedules.isNotEmpty() }!!

        assertEquals("one row for the group, not one per member", 1, state.schedules.size)
        val row = state.schedules.single()
        assertEquals("3 of 5 complete", row.progress)
        assertTrue("three of five is a round still running", row.roundOpen)
        assertEquals(5, row.checklist.size)
        assertEquals(3, row.checklist.count { it.complete })
        assertEquals(members.map { it.name }.toSet(), row.checklist.map { it.name }.toSet())

        // A sixth member, added now. It joins the members list and leaves the open round alone.
        val late = asset("Sprinkler 6")
        val open = graph.groups.get(GroupId("g1"))!!.members.filter { it.removedAt == null }
        graph.saveGroup.run(
            GroupId("g1"),
            GroupCommand(
                name = "North run",
                members = open.map { GroupMemberInput(assetId = it.assetId, id = it.id) } +
                    GroupMemberInput(assetId = late.id),
            ),
        )

        val after = vm.state.first { it != null && it.members.size == 6 }!!
        assertEquals("3 of 5 complete", after.schedules.single().progress)
        assertEquals(5, after.schedules.single().checklist.size)
    }

    /**
     * Matrix row "remove treated as delete", read from the screen's side: a soft-removed member
     * leaves the **members** list and stays in the **round**, because the window it held covered
     * that round's open instant (D-10, invariants 8, 79).
     */
    @Test fun aRemovedMemberLeavesTheListAndStaysInTheOpenRound() = runTest {
        val members = (1..3).map { asset("Sprinkler $it") }
        graph.groups.upsert(
            groupOf("g1", name = "North run", members = members.map { Triple(it.id.value, "2026-01-01", null) }),
        )
        seed(scheduleOf("s-group", assetId = null, groupId = "g1", title = "Head check", anchorOn = "2026-01-01", leadDays = 0))

        val vm = viewModel("g1")
        backgroundScope.launch { vm.state.collect() }
        assertEquals(3, vm.state.first { it != null && it.members.size == 3 }!!.members.size)

        val open = graph.groups.get(GroupId("g1"))!!.members.filter { it.removedAt == null }
        graph.saveGroup.run(
            GroupId("g1"),
            GroupCommand(
                name = "North run",
                members = open.drop(1).map { GroupMemberInput(assetId = it.assetId, id = it.id) },
            ),
        )

        val after = vm.state.first { it != null && it.members.size == 2 }!!
        assertFalse(after.members.any { it.name == members.first().name })
        assertEquals("the round still obliges the member it opened with", 3, after.schedules.single().checklist.size)
        assertEquals("3 rows are still stored, none deleted", 3, graph.groups.get(GroupId("g1"))!!.members.size)
    }

    /**
     * Matrix rows "archive losing history" and "an archived group still due" (D-16).
     *
     * Archiving writes one column. Every membership row, the member completion and the closure are
     * still there afterwards, the detail screen still reads them — which is what makes the retained
     * history reachable — and the schedule is in **no** due total and **no** dashboard section.
     */
    @Test fun archivingKeepsEveryRowAndTakesTheScheduleOutOfTheDueLists() = runTest {
        val members = (1..2).map { asset("Sprinkler $it") }
        graph.groups.upsert(
            groupOf("g1", name = "North run", members = members.map { Triple(it.id.value, "2026-01-01", null) }),
        )
        seed(scheduleOf("s-group", assetId = null, groupId = "g1", title = "Head check", anchorOn = "2026-01-01", leadDays = 0))
        graph.completeGroupMembers.run(
            ScheduleId("s-group"),
            listOf(members.first().id),
            CompletionCommand(occurredOn = "2026-04-14", tzId = "UTC"),
        )
        graph.closures.insert(
            OccurrenceClosure(
                id = "c1",
                scheduleId = ScheduleId("s-group"),
                occurrenceOn = "2026-07-01",
                closedOn = "2026-07-02",
                createdAt = dayMillis("2026-07-02"),
            ),
        )

        val vm = viewModel("g1")
        backgroundScope.launch { vm.state.collect() }
        assertTrue(readModel().items().any { it.scheduleId.value == "s-group" })

        vm.setArchived(true)

        val archived = vm.state.first { it?.archived == true }!!
        assertEquals("the members are still here", 2, archived.members.size)
        assertEquals("and so are the schedules", 1, archived.schedules.size)
        assertEquals(2, graph.groups.get(GroupId("g1"))!!.members.size)
        assertEquals(1, graph.events.all().count { it.scheduleId?.value == "s-group" })
        assertEquals(1, graph.closures.forSchedule(ScheduleId("s-group")).size)

        // And nothing of it is due any more: the projection drops an archived group's schedules.
        assertTrue(readModel().items().none { it.scheduleId.value == "s-group" })
    }

    /**
     * Matrix row "an empty group getting a schedule", the read half (invariant 74). A round that
     * obliges nobody carries **no** progress line — "0 of 0 complete" would read as done — and an
     * empty checklist, and the screen offers no way to create a schedule on it.
     *
     * The schedule is written straight to the repository because `saveSchedule` refuses a group
     * target with nobody in it, which is the correct refusal: this row only exists on a phone whose
     * members were all removed after the fact.
     */
    @Test fun anEmptyGroupsRoundObligesNobodyAndReportsNoProgress() = runTest {
        graph.groups.upsert(groupOf("g-empty", name = "Emptied run"))
        seed(scheduleOf("s-vacuous", assetId = null, groupId = "g-empty", title = "Nobody's round", anchorOn = "2026-01-01", leadDays = 0))

        val vm = viewModel("g-empty")
        backgroundScope.launch { vm.state.collect() }
        val state = vm.state.first { it != null && it.schedules.isNotEmpty() }!!

        assertTrue(state.members.isEmpty())
        val row = state.schedules.single()
        assertTrue(row.requiredSetEmpty)
        assertNull("emptiness never means complete", row.progress)
        assertTrue(row.checklist.isEmpty())
        assertEquals(DueStatus.NO_DATA, row.status)
    }

    /**
     * Matrix row "name as identity" (invariant 7). Two groups with one name coexist, and each is
     * opened by its **id**: the detail screen asks the store for a `GroupId` and nothing anywhere
     * resolves a group by what it is called.
     */
    @Test fun twoGroupsSharingANameAreBothReachable() = runTest {
        val one = asset("Sprinkler 1")
        val two = asset("Sprinkler 2")
        graph.groups.upsert(groupOf("g1", name = "North run", members = listOf(Triple(one.id.value, "2026-01-01", null))))
        graph.groups.upsert(groupOf("g2", name = "North run", members = listOf(Triple(two.id.value, "2026-01-01", null))))

        val first = viewModel("g1")
        val second = viewModel("g2")
        backgroundScope.launch { first.state.collect() }
        backgroundScope.launch { second.state.collect() }

        assertEquals(listOf("Sprinkler 1"), first.state.first { it != null && it.members.isNotEmpty() }!!.members.map { it.name })
        assertEquals(listOf("Sprinkler 2"), second.state.first { it != null && it.members.isNotEmpty() }!!.members.map { it.name })
        assertEquals("North run", first.state.value!!.name)
        assertEquals("North run", second.state.value!!.name)
    }

    /**
     * Matrix row "a member completed by the wrong path", the **selected** half (invariants 28, 29).
     *
     * Two of five named, and exactly two events are written — on those two assets, both carrying
     * the round's own occurrence key. The other three members' history is asserted **byte-identical**
     * either side of the write, which is the fact a "complete the group" button would break.
     *
     * The write itself is [CompletionFlow]'s and therefore `CompleteGroupMembers`': this screen
     * calls the one completion mechanism and re-implements none of it (master plan decision 36).
     */
    @Test fun completeSelectedMarksThoseMembersAndLeavesEveryOtherHistoryAlone() = runTest {
        val (scheduleId, assets) = aRound()
        val vm = viewModel("g1")
        backgroundScope.launch { vm.state.collect() }
        val before = vm.state.first { it != null && it.schedules.isNotEmpty() }!!
        val key = before.schedules.single().checklist.size
        assertEquals(5, key)
        val untouched = assets.drop(2).map { it.id }
        val historyBefore = untouched.associateWith { graph.events.forAsset(it) }

        vm.completeSelected(ScheduleId(scheduleId), assets.take(2).map { it.id })
        answer("2026-04-15")
        vm.busy.first { !it }

        val written = vm.state.first { it != null && it.schedules.single().progress == "2 of 5 complete" }!!
        val row = written.schedules.single()
        assertTrue("two of five is a round still running", row.roundOpen)
        assertEquals(2, row.checklist.count { it.complete })
        assertEquals(
            assets.take(2).map { it.name }.toSet(),
            row.checklist.filter { it.complete }.map { it.name }.toSet(),
        )

        val events = graph.events.all()
        assertEquals(2, events.size)
        assertEquals(assets.take(2).map { it.id }.toSet(), events.map { it.assetId }.toSet())
        assertTrue("both carry the schedule", events.all { it.scheduleId == ScheduleId(scheduleId) })
        assertEquals("and one occurrence key between them", 1, events.map { it.occurrenceOn }.distinct().size)
        assertEquals(historyBefore, untouched.associateWith { graph.events.forAsset(it) })
    }

    /**
     * "Complete all": one event per outstanding required member, in one write, and **no** event for
     * anybody the round does not oblige. The member list is derived inside the use case, which is
     * why a surface cannot complete somebody who is not required (invariants 28, 29, 31).
     */
    @Test fun completeAllWritesOneEventPerOutstandingRequiredMember() = runTest {
        val (scheduleId, assets) = aRound(members = 3)
        // A fourth sprinkler that is in no group at all: the round must not touch it.
        val stranger = asset("Sprinkler 9")
        val vm = viewModel("g1")
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it != null && it.schedules.isNotEmpty() }

        vm.completeAll(ScheduleId(scheduleId))
        answer("2026-04-15")
        vm.busy.first { !it }

        val events = vm.state.first { it != null }.let { graph.events.all() }
        assertEquals(3, events.size)
        assertEquals(assets.map { it.id }.toSet(), events.map { it.assetId }.toSet())
        assertEquals(1, events.map { it.occurrenceOn }.distinct().size)
        assertTrue("a non-member is never written to", graph.events.forAsset(stranger.id).isEmpty())
    }

    /**
     * A **single** member, through the same flow: `CompletionFlow.complete(scheduleId, assetId)`.
     * One event, on that member, and nothing on anybody else — the shape the brief asks a member
     * completion to take.
     */
    @Test fun completingOneMemberWritesThatMemberOnly() = runTest {
        val (scheduleId, assets) = aRound(members = 3)
        val vm = viewModel("g1")
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it != null && it.schedules.isNotEmpty() }

        vm.completeMember(ScheduleId(scheduleId), assets.first().id)
        answer("2026-04-15")
        vm.busy.first { !it }

        val written = vm.state.first { it != null && it.schedules.single().progress == "1 of 3 complete" }!!
        assertEquals(listOf(assets.first().name), written.schedules.single().checklist.filter { it.complete }.map { it.name })
        assertEquals(1, graph.events.all().size)
        assertEquals(assets.first().id, graph.events.all().single().assetId)
        assertTrue(assets.drop(1).all { graph.events.forAsset(it.id).isEmpty() })
    }

    /**
     * The writes this screen does **not** make. Archiving is one column and no cascade; nothing here
     * creates an `Asset` or gives one a parent, which is the "fake parent asset" shortcut arrived at
     * through the UI (invariants 4, 5). A member completion is the flow's, and only the flow's.
     */
    @Test fun archivingWritesNoEventAndCreatesNoAsset() = runTest {
        val members = (1..2).map { asset("Sprinkler $it") }
        graph.groups.upsert(
            groupOf("g1", name = "North run", members = members.map { Triple(it.id.value, "2026-01-01", null) }),
        )
        seed(scheduleOf("s-group", assetId = null, groupId = "g1", title = "Head check", anchorOn = "2026-01-01", leadDays = 0))

        val vm = viewModel("g1")
        backgroundScope.launch { vm.state.collect() }
        assertNotNull(vm.state.first { it != null })
        val eventsBefore = graph.events.all().size
        val assetsBefore = graph.assets.all().size

        vm.setArchived(true)
        vm.state.first { it?.archived == true }
        vm.setArchived(false)
        vm.state.first { it?.archived == false }

        assertEquals(eventsBefore, graph.events.all().size)
        assertEquals(assetsBefore, graph.assets.all().size)
        assertTrue("no asset here has a parent set by this screen", graph.assets.all().all { it.parentAssetId == null })
    }

    /** A group that is no longer there sends the screen back rather than drawing an empty one. */
    @Test fun aGroupThatIsNotThereIsReportedMissing() = runTest {
        graph.groups.upsert(groupOf("g1", name = "North run"))
        val vm = viewModel("g-gone")
        backgroundScope.launch { vm.missing.collect() }
        assertTrue(vm.missing.first { it })
    }
}
