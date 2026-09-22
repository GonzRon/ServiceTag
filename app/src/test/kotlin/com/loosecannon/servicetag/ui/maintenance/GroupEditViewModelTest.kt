package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.core.usecase.GroupCommand
import com.loosecannon.servicetag.core.usecase.GroupMemberInput
import com.loosecannon.servicetag.core.usecase.GroupProblem
import com.loosecannon.servicetag.core.usecase.GroupValidation
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.groupOf
import com.loosecannon.servicetag.testing.scheduleOf
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertFailsWith

/**
 * The group form. Every assertion here is about what the form left **alone** as much as what it
 * wrote: the failure this file exists to prevent is a tidy-looking edit that reopens a window a past
 * round was derived from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupEditViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse("2026-04-15")
        // The windows this form writes are stamped from the clock, and the rounds they belong to
        // open on calendar instants; the two have to be on the same scale.
        graph.now = dayMillis("2026-04-15")
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(id: String? = null) =
        GroupEditViewModel(graph.groups, graph.assets, graph.saveGroup, id?.let(::GroupId))

    private suspend fun asset(name: String): Asset =
        graph.createAsset.run(AssetCommand(name = name, category = "Irrigation"))

    private suspend fun loaded(vm: GroupEditViewModel): GroupEditState = vm.state.first { it.loaded }

    private suspend fun openMembers(id: String) =
        graph.groups.get(GroupId(id))!!.members.filter { it.removedAt == null }

    /** A new group: one `SaveGroup` call, its members inserted as fresh windows. */
    @Test fun aNewGroupIsOneSaveWithTheSelectedAssetsAsMembers() = runTest {
        val one = asset("Sprinkler 1")
        asset("Sprinkler 2")

        val vm = viewModel()
        val state = loaded(vm)
        assertFalse("a blank name cannot be saved", state.canSave)
        assertEquals(listOf("Sprinkler 1", "Sprinkler 2"), state.candidates.map { it.name })
        assertTrue("nothing is selected on a new group", state.candidates.none { it.selected })

        vm.onName("North run")
        vm.toggle(one.id)
        assertTrue(vm.state.value.canSave)
        vm.save()

        val saved = vm.saved.first()
        val group = graph.groups.get(GroupId(saved))!!
        assertEquals("North run", group.name)
        assertEquals(listOf(one.id), group.members.map { it.assetId })
        assertNull(group.members.single().removedAt)
        // The editor writes membership and nothing else: a completion is the flow's, on the detail.
        assertTrue(graph.events.all().isEmpty())
    }

    /**
     * Matrix row "remove treated as delete": deselecting an open member **stamps** `removed_at` and
     * retains the row, and the group's recorded completions are untouched (invariants 8, 79).
     */
    @Test fun deselectingAMemberSoftRemovesItAndKeepsTheHistory() = runTest {
        val members = (1..2).map { asset("Sprinkler $it") }
        graph.groups.upsert(
            groupOf("g1", name = "North run", members = members.map { Triple(it.id.value, "2026-01-01", null) }),
        )
        graph.schedules.upsert(scheduleOf("s-group", assetId = null, groupId = "g1", anchorOn = "2026-01-01", leadDays = 0))
        graph.recomputeSchedules.forSchedule(ScheduleId("s-group"))
        graph.completeGroupMembers.run(
            ScheduleId("s-group"),
            listOf(members.first().id),
            CompletionCommand(occurredOn = "2026-04-14", tzId = "UTC"),
        )
        val eventsBefore = graph.events.all().map { it.id to it.occurrenceOn }

        val vm = viewModel("g1")
        loaded(vm)
        vm.toggle(members.first().id)
        vm.save()
        vm.saved.first()

        val stored = graph.groups.get(GroupId("g1"))!!.members
        assertEquals("nothing is deleted", 2, stored.size)
        val removed = stored.single { it.assetId == members.first().id }
        assertEquals(dayMillis("2026-04-15"), removed.removedAt)
        assertEquals("the window it had is otherwise untouched", dayMillis("2026-01-01"), removed.addedAt)
        assertNull(stored.single { it.assetId == members[1].id }.removedAt)
        assertEquals("the completions are history and stay exactly as they were", eventsBefore, graph.events.all().map { it.id to it.occurrenceOn })
    }

    /**
     * Matrix row "re-add" (invariants 33, 79). Re-selecting a removed asset makes a **second**
     * window with a new id and a new `added_at`; the first one's `removed_at` is **unchanged**, so
     * no round that was derived from it can move.
     */
    @Test fun reAddingAMemberMakesASecondWindowAndLeavesTheFirstClosed() = runTest {
        val one = asset("Sprinkler 1")
        graph.groups.upsert(
            groupOf("g1", name = "North run", members = listOf(Triple(one.id.value, "2026-01-01", "2026-02-01"))),
        )
        val closedBefore = graph.groups.get(GroupId("g1"))!!.members.single()

        val vm = viewModel("g1")
        val state = loaded(vm)
        assertFalse("a closed window is not membership", state.candidates.single { it.assetId == one.id }.selected)
        assertNull(state.candidates.single { it.assetId == one.id }.membershipId)

        vm.toggle(one.id)
        vm.save()
        vm.saved.first()

        val stored = graph.groups.get(GroupId("g1"))!!.members
        assertEquals("a second row, not a reopened one", 2, stored.size)
        val first = stored.single { it.id == closedBefore.id }
        assertEquals("no `removed_at` was cleared", closedBefore.removedAt, first.removedAt)
        assertEquals(closedBefore.addedAt, first.addedAt)
        val second = stored.single { it.id != closedBefore.id }
        assertNull(second.removedAt)
        assertEquals(dayMillis("2026-04-15"), second.addedAt)
        assertEquals("exactly one open row for the pair", 1, stored.count { it.removedAt == null })
    }

    /**
     * Matrix row "a duplicate open member" (invariant 80), at the UI boundary.
     *
     * The form is a selection over **assets**, one entry each, so there is no sequence of taps that
     * asks for a second open window on one `(group, asset)` pair — a selected member is sent with
     * its membership id, which is a keep. The control is the command the form cannot build: sent
     * straight to `SaveGroup` it is refused with `MemberAlreadyOpen`, and the store still holds one
     * open row.
     */
    @Test fun theFormCannotAskForASecondOpenWindowAndTheUseCaseRefusesOneAnyway() = runTest {
        val one = asset("Sprinkler 1")
        graph.groups.upsert(
            groupOf("g1", name = "North run", members = listOf(Triple(one.id.value, "2026-01-01", null))),
        )

        val vm = viewModel("g1")
        val state = loaded(vm)
        assertEquals("one entry per asset, whatever its windows", 1, state.candidates.count { it.assetId == one.id })
        val entry = state.candidates.single { it.assetId == one.id }
        assertTrue(entry.selected)
        assertNotNull("a selected open member is sent as a keep, never as an add", entry.membershipId)

        // Saving with no change keeps the window by id: same row, same `added_at`, still one open.
        val before = graph.groups.get(GroupId("g1"))!!.members.single()
        vm.save()
        vm.saved.first()
        val after = graph.groups.get(GroupId("g1"))!!.members
        assertEquals(1, after.size)
        assertEquals(before.id, after.single().id)
        assertEquals(before.addedAt, after.single().addedAt)

        // The command the form has no way to build.
        val refusal = assertFailsWith<GroupValidation> {
            graph.saveGroup.run(
                GroupId("g1"),
                GroupCommand(name = "North run", members = listOf(GroupMemberInput(assetId = one.id))),
            )
        }
        assertEquals(listOf(GroupProblem.MemberAlreadyOpen(one.id)), refusal.problems)
        assertEquals(1, openMembers("g1").size)
    }

    /**
     * A refusal is a visible outcome and not a tap that did nothing: the state carries what
     * `SaveGroup` refused. **Nothing renders it as a sentence** — §17 ratifies none — which is why
     * this asserts the state and not a string.
     *
     * The reachable refusal is a member asset that went away between the form loading and the save.
     */
    @Test fun aRefusedSaveIsCarriedInTheStateAndWritesNothing() = runTest {
        val one = asset("Sprinkler 1")
        graph.groups.upsert(groupOf("g1", name = "North run"))

        val vm = viewModel("g1")
        loaded(vm)
        vm.toggle(one.id)
        graph.assets.delete(one.id)

        vm.save()
        val refused = vm.state.first { it.problems.isNotEmpty() }
        assertEquals(listOf(GroupProblem.MemberAssetMissing(one.id)), refused.problems)
        assertFalse(refused.saving)
        assertTrue("nothing was written", graph.groups.get(GroupId("g1"))!!.members.isEmpty())
    }

    /**
     * A blank name is disabled rather than refused, and [GroupEditViewModel.save] is a no-op while
     * it is: there is no ratified sentence for the refusal, so the form does not let the owner ask
     * for it (invariant 7's other half — a name is a label, but a group still needs one).
     */
    @Test fun aBlankNameDisablesTheSaveAndWritesNothing() = runTest {
        asset("Sprinkler 1")
        val vm = viewModel()
        loaded(vm)

        vm.onName("   ")
        assertFalse(vm.state.value.canSave)
        vm.save()
        assertTrue(graph.groups.all().isEmpty())

        vm.onName("North run")
        assertTrue(vm.state.value.canSave)
    }

    /**
     * D-16: archiving or retiring a member **Asset** leaves its membership alone. The form therefore
     * still lists an archived member — dropping it would silently remove it on the next save, which
     * is precisely the edit the append-only rule exists to prevent — while an archived asset that is
     * *not* a member is not offered.
     */
    @Test fun anArchivedMemberStaysOnTheFormAndAnArchivedNonMemberIsNotOffered() = runTest {
        val member = asset("Sprinkler 1")
        val stranger = asset("Sprinkler 2")
        graph.groups.upsert(
            groupOf("g1", name = "North run", members = listOf(Triple(member.id.value, "2026-01-01", null))),
        )
        graph.archiveAsset.run(member.id)
        graph.archiveAsset.run(stranger.id)

        val state = loaded(viewModel("g1"))
        assertEquals(listOf<AssetId>(member.id), state.candidates.map { it.assetId })
        assertTrue(state.candidates.single().selected)
    }
}
