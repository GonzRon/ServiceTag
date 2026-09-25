package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.core.usecase.GroupCommand
import com.loosecannon.servicetag.core.usecase.GroupMemberInput
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.groupOf
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.ui.maintenance.statusLabel
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Asset detail's two 1.2 sections: the asset's schedules, and the groups it belongs to — the two
 * directions #55 calls its navigation minimum (spec §2.6).
 *
 * The rule under test is master plan decision 38: the schedules section lists the asset's **own**
 * schedules and not the group work it shares, because a group obligation is counted once and
 * reached through the group. Showing it in both places would make one obligation look like two.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetMaintenanceSectionsTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse("2026-04-15")
        graph.now = dayMillis("2026-04-15")
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private fun detailModel(id: AssetId) = AssetDetailViewModel(
        graph.assets, graph.tags,
        graph.definitions, graph.profiles, graph.events,
        graph.schedules, graph.scheduleStates, graph.groups, graph.dueReadModel,
        graph.conditions, graph.seasonActivations, graph.healthSubjects,
        graph.assetHealthReadModel, graph.getAssetSeason, graph.recordSeasonActivation,
        graph.archiveAsset, graph.retireAsset, graph.deleteAsset,
        graph.applyTemplate, graph.uow, graph.clock, graph.todayPort, id,
    )

    private suspend fun seed(schedule: MaintenanceSchedule) {
        graph.schedules.upsert(schedule)
        graph.recomputeSchedules.forSchedule(schedule.id)
    }

    /**
     * Matrix rows "the asset's sections wrong" and "a group schedule shown twice".
     *
     * The head is a member of a group that has its own schedule, and has one schedule of its own.
     * Its schedules section holds **one** row — its own, with the ratified status word — and the
     * group's work is reached through the groups section instead (decision 38, D-15).
     */
    @Test fun theSchedulesSectionHoldsTheAssetsOwnWorkAndTheGroupsSectionTheRest() = runTest {
        val head = graph.createAsset.run(AssetCommand(name = "Sprinkler 1", category = "Irrigation"))
        graph.groups.upsert(
            groupOf("g1", name = "North run", members = listOf(Triple(head.id.value, "2026-01-01", null))),
        )
        seed(scheduleOf("s-own", assetId = head.id.value, title = "Nozzle clean", anchorOn = "2026-01-01", leadDays = 0))
        seed(scheduleOf("s-group", assetId = null, groupId = "g1", title = "Head check", anchorOn = "2026-01-01", leadDays = 0))

        val vm = detailModel(head.id)
        backgroundScope.launch { vm.state.collect() }
        val state = vm.state.first { it != null && it.schedules.isNotEmpty() }!!

        assertEquals(listOf("Nozzle clean"), state.schedules.map { it.title })
        assertTrue(
            "the asset's own section carries asset targets only",
            state.schedules.all { it.target is ScheduleTarget.AssetTarget },
        )
        assertEquals("OVERDUE", statusLabel(state.schedules.single().status))
        assertEquals(DueStatus.OVERDUE, state.schedules.single().status)
        assertEquals(listOf("North run"), state.groups.map { it.name })
        assertEquals(listOf(GroupId("g1")), state.groups.map { it.id })
    }

    /**
     * The groups section is the **open** windows and not the closed ones: a soft-removed member must
     * not go on looking like a current one (invariants 8, 79). The group's own screen is where its
     * retained history lives.
     */
    @Test fun aRemovedMembersAssetIsInNoGroup() = runTest {
        val head = graph.createAsset.run(AssetCommand(name = "Sprinkler 1", category = "Irrigation"))
        graph.groups.upsert(
            groupOf("g1", name = "North run", members = listOf(Triple(head.id.value, "2026-01-01", null))),
        )

        val vm = detailModel(head.id)
        backgroundScope.launch { vm.state.collect() }
        assertEquals(listOf("North run"), vm.state.first { it != null && it.groups.isNotEmpty() }!!.groups.map { it.name })

        graph.saveGroup.run(GroupId("g1"), GroupCommand(name = "North run", members = emptyList()))

        val after = vm.state.first { it != null && it.groups.isEmpty() }!!
        assertTrue(after.groups.isEmpty())
        assertEquals("the window is retained, it is just closed", 1, graph.groups.get(GroupId("g1"))!!.members.size)
    }

    /**
     * Carry-forward (a): the section starts from `listedForDue()` like every other due query, so an
     * archived schedule is listed nowhere — here included.
     */
    @Test fun anArchivedScheduleIsNotInTheAssetsSection() = runTest {
        val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
        seed(scheduleOf("s-live", assetId = mower.id.value, title = "Blade sharpen", anchorOn = "2026-01-01"))
        seed(scheduleOf("s-archived", assetId = mower.id.value, title = "Retired work", status = ScheduleStatus.ARCHIVED))

        val vm = detailModel(mower.id)
        backgroundScope.launch { vm.state.collect() }
        val state = vm.state.first { it != null && it.schedules.isNotEmpty() }!!
        assertEquals(listOf("Blade sharpen"), state.schedules.map { it.title })
    }

    /**
     * Invariant 8 at the asset screen: an asset whose membership windows are part of the basis of a
     * recorded group round is **not** deleted. The affordance does not proceed and the asset is
     * still there — the refusal itself has no ratified sentence, so it says the shipped line the
     * screen already uses for a delete that could not happen.
     */
    @Test fun anAssetInARecordedGroupRoundIsNotDeleted() = runTest {
        val head = graph.createAsset.run(AssetCommand(name = "Sprinkler 1", category = "Irrigation"))
        graph.saveGroup.run(
            null,
            GroupCommand(name = "North run", members = listOf(GroupMemberInput(assetId = head.id))),
        )
        val group = graph.groups.all().single()
        val schedule = graph.saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = null,
                targetGroupId = group.id,
                title = "Head check",
                timeInterval = 3,
                timeUnit = RecurrenceUnit.MONTH,
                anchorOn = "2026-04-15",
            ),
        )
        graph.completeGroupMembers.run(
            schedule.id,
            listOf(head.id),
            CompletionCommand(occurredOn = "2026-04-15", tzId = "UTC"),
        )

        val vm = detailModel(head.id)
        vm.delete()

        assertEquals("Could not delete this asset.", vm.messages.first())
        assertTrue("the asset is still there", graph.assets.all().any { it.id == head.id })
        assertEquals(1, graph.groups.get(group.id)!!.members.size)
        assertEquals(1, graph.events.all().size)
    }
}
