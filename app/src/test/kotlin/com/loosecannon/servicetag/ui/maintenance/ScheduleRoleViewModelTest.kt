package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.usecase.SeasonStartRefusal
import com.loosecannon.servicetag.core.usecase.SeasonStartStrandCause
import com.loosecannon.servicetag.core.usecase.SeasonStartStrands
import com.loosecannon.servicetag.core.usecase.SetScheduleRole
import com.loosecannon.servicetag.core.usecase.StrandedSchedule
import com.loosecannon.servicetag.reminders.NotificationPermission
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.groupOf
import com.loosecannon.servicetag.testing.scheduleOf
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #114 row 76 (C33, K11, O-2, C10a) — the Season Start role on the phone: its own action and confirm on the schedule
 * detail, never the editor's save; the confirm carries the date and, on a one-tap schedule only, the note P114-5 and the
 * owner's election; every refusal has its sentence (§5), every strand cause too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleRoleViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse("2026-04-15")
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private fun roleModel(id: String) = ScheduleRoleViewModel(
        graph.schedules, graph.assets, graph.groups, graph.setScheduleRole, graph.todayPort, ScheduleId(id),
    )

    private fun editModel(id: String) = ScheduleEditViewModel(
        schedules = graph.schedules,
        assets = graph.assets,
        groups = graph.groups,
        definitions = graph.definitions,
        profiles = graph.profiles,
        saveSchedule = graph.saveSchedule,
        notifications = object : NotificationPermission {
            override fun granted(): Boolean = true
            override fun shouldExplain(): Boolean = false
            override suspend fun request(): Boolean = true
        },
        today = graph.todayPort,
        scheduleId = ScheduleId(id),
        targetAssetId = null,
        targetGroupId = null,
    )

    private fun TestScope.savedId(vm: ScheduleEditViewModel): CompletableDeferred<ScheduleId> {
        val out = CompletableDeferred<ScheduleId>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { out.complete(vm.saved.first()) }
        return out
    }

    /** "Example Heater", a top-level MANUAL asset, with a FORM and a one-tap schedule; "Example Boiler" all year. */
    private suspend fun heater() {
        graph.assets.upsert(assetRow("heater", name = "Example Heater", seasonMode = SeasonMode.MANUAL))
        graph.assets.upsert(assetRow("boiler", name = "Example Boiler"))
        graph.schedules.upsert(
            scheduleOf("annual", assetId = "heater", title = "Example annual service", completionMode = CompletionMode.FORM),
        )
        graph.schedules.upsert(scheduleOf("quick", assetId = "heater", title = "Example quick check"))
    }

    private suspend fun ScheduleRoleViewModel.opened(): SeasonStartConfirm {
        state.first { it.loaded }
        ask()
        return state.first { it.confirm != null }.confirm!!
    }

    private suspend fun TestScope.confirmed(vm: ScheduleRoleViewModel): ScheduleRoleState {
        vm.confirm()
        advanceUntilIdle()
        return vm.state.first { !it.busy }
    }

    /** K11: the role is its own action; the editor's save keeps the stored role and never sets one. */
    @Test fun theActionIsNotInTheEditorsSave() = runTest {
        heater()
        val role = roleModel("annual")
        role.opened()
        assertEquals(ScheduleRole.SEASON_START, confirmed(role).role)

        val editor = editModel("annual")
        editor.state.first { it.loaded }
        editor.onTitle("Example annual service, revised")
        val saved = savedId(editor)
        editor.save()
        saved.await()
        val stored = graph.schedules.get(ScheduleId("annual"))!!
        assertEquals("Example annual service, revised", stored.title)
        assertEquals(ScheduleRole.SEASON_START, stored.role)
        assertEquals("2026-04-15", stored.roleSinceOn)

        val plain = editModel("quick")
        plain.state.first { it.loaded }
        plain.onTitle("Example quick check, revised")
        val plainSaved = savedId(plain)
        plain.save()
        plainSaved.await()
        assertEquals(ScheduleRole.NONE, graph.schedules.get(ScheduleId("quick"))!!.role)
        // The form has no role to carry: no field of its state names one.
        assertTrue(ScheduleEditState::class.java.declaredFields.none { it.name.startsWith("role") })

        role.stop()
        advanceUntilIdle()
        assertEquals(ScheduleRole.NONE, graph.schedules.get(ScheduleId("annual"))!!.role)
        assertNull(graph.schedules.get(ScheduleId("annual"))!!.roleSinceOn)
    }

    /** The confirm: P114-4 / P114-4g with the day (today by default, O-4); P114-5 on a one-tap schedule only (O-2). */
    @Test fun confirmCarriesTheDateAndQuickNote() = runTest {
        heater()
        graph.assets.upsert(assetRow("pump", name = "Example Pump", seasonMode = SeasonMode.MANUAL))
        graph.groups.upsert(groupOf("pre", name = "Example pre-season", members = listOf(Triple("pump", "2026-01-01", null))))
        graph.schedules.upsert(scheduleOf("pre-check", groupId = "pre", title = "Example pre-season check"))

        val form = roleModel("annual")
        val asked = form.opened()
        assertEquals("2026-04-15", asked.sinceOn)
        assertEquals(
            "Use \"Example annual service\" as Example Heater's Season Start service? Completing it marks Example Heater " +
                "ready for the season. Services dated on or after 15 Apr 2026 count.",
            asked.question,
        )
        assertNull("no one-tap note on a FORM schedule", asked.quickNote)
        assertFalse(asked.quickElected)

        form.onSinceOn("2026-03-01")
        assertEquals(
            "Use \"Example annual service\" as Example Heater's Season Start service? Completing it marks Example Heater " +
                "ready for the season. Services dated on or after 1 Mar 2026 count.",
            form.state.value.confirm!!.question,
        )
        val set = confirmed(form)
        assertNull(set.confirm)
        assertEquals("2026-03-01", graph.schedules.get(ScheduleId("annual"))!!.roleSinceOn)
        assertEquals("Season Start service · services count from 1 Mar 2026", set.roleLine)

        val quick = roleModel("quick").opened()
        assertEquals(
            "This schedule is completed with one tap and no service details. A one-tap completion will still mark the " +
                "asset ready.",
            quick.quickNote,
        )

        val group = roleModel("pre-check").opened()
        assertEquals(
            "Use \"Example pre-season check\" as the Season Start service for every asset in Example pre-season? " +
                "Completing it for an asset marks that asset ready for the season. Services dated on or after " +
                "15 Apr 2026 count.",
            group.question,
        )
    }

    /** C10a: the confirm on a one-tap schedule is the owner's election, passed every time — a move of the day too. */
    @Test fun aQuickConfirmPassesTheElection() = runTest {
        heater()
        val vm = roleModel("quick")
        assertTrue(vm.opened().quickElected)
        val set = confirmed(vm)
        assertNull("the role was set, not refused for want of the election", set.confirm)
        assertEquals(ScheduleRole.SEASON_START, graph.schedules.get(ScheduleId("quick"))!!.role)

        // Moving the day asks again: the stored day, P114-5 again, and the election passed again.
        assertTrue(vm.state.value.canMove)
        val again = vm.opened()
        assertEquals("2026-04-15", again.sinceOn)
        assertTrue(again.quickNote != null && again.quickElected)
        vm.onSinceOn("2026-04-01")
        assertNull(confirmed(vm).confirm)
        assertEquals("2026-04-01", graph.schedules.get(ScheduleId("quick"))!!.roleSinceOn)
    }

    /**
     * A5b review, minor 4 (C10a, O-2): the schedule turns one-tap behind an open confirm on a FORM schedule. The confirm
     * passed no election, so it is refused `QUICK_NOT_ELECTED` and stays open saying P114-5; the next confirm is the
     * election, and the role is set.
     */
    @Test fun aScheduleTurnedOneTapBehindTheOpenConfirmMakesTheNextConfirmTheElection() = runTest {
        heater()
        val vm = roleModel("annual")
        val open = vm.opened()
        assertFalse(open.quickElected)
        assertNull(open.quickNote)
        graph.schedules.upsert(graph.schedules.get(ScheduleId("annual"))!!.copy(completionMode = CompletionMode.QUICK))

        val refused = confirmed(vm).confirm
        assertEquals(SeasonStartStrings.quickNote(), refused?.problem)
        assertTrue("the next confirm is the election", refused!!.quickElected)
        assertEquals(ScheduleRole.NONE, graph.schedules.get(ScheduleId("annual"))!!.role)

        assertNull(confirmed(vm).confirm)
        assertEquals(ScheduleRole.SEASON_START, graph.schedules.get(ScheduleId("annual"))!!.role)
    }

    /**
     * A5b review, minor 5: a failure the confirm has no sentence for never closes it silently — it stays open as typed,
     * saying P190-2, nothing written, so the owner can try again or cancel. Only a schedule gone meanwhile closes it.
     */
    @Test fun anUnexpectedFailureKeepsTheConfirmOpen() = runTest {
        heater()
        val failing = object : com.loosecannon.servicetag.core.ports.AssetRepository by graph.assets {
            override suspend fun all(): List<com.loosecannon.servicetag.core.model.Asset> = error("the store failed")
        }
        val setRole = SetScheduleRole(
            graph.schedules, failing, graph.groups, graph.transferRecords, graph.uow, graph.clock, graph.todayPort,
            graph.recomputeSchedules,
        )
        val vm = ScheduleRoleViewModel(graph.schedules, graph.assets, graph.groups, setRole, graph.todayPort, ScheduleId("annual"))
        val open = vm.opened()

        val after = confirmed(vm)
        assertEquals("still open, as typed", open.sinceOn, after.confirm?.sinceOn)
        assertEquals("Could not save this schedule.", after.confirm?.problem)
        assertFalse(after.busy)
        assertEquals(ScheduleRole.NONE, graph.schedules.get(ScheduleId("annual"))!!.role)

        // A schedule gone meanwhile (`NoSuchSchedule`) closes it: there is nothing left to confirm.
        val vanished = object : com.loosecannon.servicetag.core.ports.ScheduleRepository by graph.schedules {
            override suspend fun get(id: ScheduleId): com.loosecannon.servicetag.core.model.MaintenanceSchedule? = null
        }
        val goneRole = SetScheduleRole(
            vanished, graph.assets, graph.groups, graph.transferRecords, graph.uow, graph.clock, graph.todayPort,
            graph.recomputeSchedules,
        )
        val gone = ScheduleRoleViewModel(graph.schedules, graph.assets, graph.groups, goneRole, graph.todayPort, ScheduleId("annual"))
        gone.opened()
        assertNull(confirmed(gone).confirm)
    }

    /** Each refusal the confirm can meet keeps it open with its sentence (P114-17, -20, -21, -22, -23, -28, -5). */
    @Test fun eachRefusalHasItsSentence() = runTest {
        heater()
        graph.groups.upsert(
            groupOf("both", name = "Example pair", members = listOf(Triple("heater", "2026-01-01", null), Triple("boiler", "2026-01-01", null))),
        )
        graph.groups.upsert(groupOf("empty", name = "Example empty"))
        graph.schedules.upsert(scheduleOf("pair", groupId = "both", title = "Example pair check"))
        graph.schedules.upsert(scheduleOf("none", groupId = "empty", title = "Example empty check"))
        graph.schedules.upsert(scheduleOf("boiler-check", assetId = "boiler", title = "Example boiler check"))

        suspend fun TestScope.refusal(id: String, sinceOn: String? = null): String? {
            val vm = roleModel(id)
            vm.opened()
            sinceOn?.let(vm::onSinceOn)
            return confirmed(vm).confirm?.problem
        }

        assertEquals(
            "Example Boiler is in use all year, so it has no season to start. Give it a calendar or manual season first.",
            refusal("boiler-check"),
        )
        assertEquals(
            "Every asset in this group must be able to have a Season Start service. Example Boiler is in use all year, " +
                "so it has no season to start. Give it a calendar or manual season first.",
            refusal("pair"),
        )
        assertEquals("This group has no assets to mark ready.", refusal("none"))
        assertEquals("The date services count from cannot be after today.", refusal("annual", "2026-04-16"))
        assertEquals("Enter the date services count from as a calendar date.", refusal("annual", "the fifteenth"))
        assertEquals(ScheduleRole.NONE, graph.schedules.get(ScheduleId("annual"))!!.role)

        val first = roleModel("annual")
        first.opened()
        confirmed(first)
        assertEquals(
            "Example Heater already has a Season Start service: \"Example annual service\". Stop using that one first.",
            refusal("quick"),
        )

        // Every SeasonStartRefusal has its own sentence, the ones the phone cannot reach today included.
        val heaterId = AssetId("heater")
        val expected = mapOf(
            SeasonStartRefusal.ScheduleArchived to "This schedule is archived. Restore it first.",
            SeasonStartRefusal.QuickNotElected to "This schedule is completed with one tap and no service details. " +
                "A one-tap completion will still mark the asset ready.",
            SeasonStartRefusal.NotMaintainedHere(heaterId) to
                "Example Heater is archived, retired or transferred out, so it cannot have a Season Start service.",
            SeasonStartRefusal.YearRound(heaterId) to
                "Example Heater is in use all year, so it has no season to start. Give it a calendar or manual season first.",
            SeasonStartRefusal.LeafChild(heaterId) to
                "Example Heater is a child asset with no child assets of its own, so it cannot have a Season Start service.",
            SeasonStartRefusal.AlreadyCovered(heaterId, ScheduleId("other"), "Example other service") to
                "Example Heater already has a Season Start service: \"Example other service\". Stop using that one first.",
            SeasonStartRefusal.GroupArchived to "This group has no assets to mark ready.",
            SeasonStartRefusal.GroupHasNoMembers to "This group has no assets to mark ready.",
            SeasonStartRefusal.DateInFuture to "The date services count from cannot be after today.",
            // #124 (C42): the role action's P124-7, and P124-5.
            SeasonStartRefusal.CountsFromItself to "This schedule counts from the Season Start service, so it cannot be " +
                "one. Change when it starts counting first.",
            SeasonStartRefusal.NoSeasonStartService(heaterId) to "Example Heater has no Season Start service to count " +
                "from. Use one of its schedules as its Season Start service first.",
        )
        expected.forEach { (reason, sentence) ->
            val name = SeasonStartStrings.assetOf(reason)?.let { "Example Heater" }
            assertEquals(reason.toString(), sentence, SeasonStartStrings.refused(reason, name, throughAGroup = false))
        }
        assertEquals(11, expected.size)
        // The editor's save says P124-6 for the Season Start service itself, and every other refusal as above.
        assertEquals(
            "This schedule is the Season Start service, so it cannot count from itself.",
            SeasonStartStrings.saveRefused(SeasonStartRefusal.CountsFromItself, null),
        )
        expected.filterKeys { it != SeasonStartRefusal.CountsFromItself }.forEach { (reason, sentence) ->
            val name = SeasonStartStrings.assetOf(reason)?.let { "Example Heater" }
            assertEquals(reason.toString(), sentence, SeasonStartStrings.saveRefused(reason, name))
        }
    }

    /** C11 drawn: every strand cause has its sentence (P114-24…27, P114-43; the restores' P114-20 and P114-16…18). */
    @Test fun everyStrandCauseHasItsSentence() {
        val heater = AssetId("heater")
        val role = listOf(StrandedSchedule(ScheduleId("annual"), "Example annual service"))
        fun strands(cause: SeasonStartStrandCause, refusal: SeasonStartRefusal? = null) =
            SeasonStartStrands(heater, cause, role, refusal)
        val expected = mapOf(
            SeasonStartStrandCause.NEEDS_SEASONS to "Example Heater has a Season Start service, so it needs seasons. " +
                "Stop using \"Example annual service\" as its Season Start service first.",
            SeasonStartStrandCause.WOULD_BE_LEAF_CHILD to "This change would leave Example Heater a child asset with no " +
                "child assets while it has a Season Start service. Stop using \"Example annual service\" as its Season " +
                "Start service first.",
            SeasonStartStrandCause.ROLE_SCHEDULE_RETARGET to "This schedule is a Season Start service, so its asset or " +
                "group cannot change. Stop using it as the Season Start service first.",
            SeasonStartStrandCause.QUICK_NEEDS_ELECTION to "This schedule is a Season Start service. Confirm that " +
                "one-tap completions should count before changing it to one tap.",
            SeasonStartStrandCause.MEMBER_NOT_ELIGIBLE to "Example Heater cannot join this group: the group's Season " +
                "Start service would cover it, and it already has one or cannot have one.",
            SeasonStartStrandCause.MEMBER_ALREADY_COVERED to "Example Heater cannot join this group: the group's Season " +
                "Start service would cover it, and it already has one or cannot have one.",
            SeasonStartStrandCause.RESTORE_NOT_ELIGIBLE to "Example Heater is in use all year, so it has no season to " +
                "start. Give it a calendar or manual season first.",
            SeasonStartStrandCause.RESTORE_ALREADY_COVERED to "Example Heater already has a Season Start service: " +
                "\"Example annual service\". Stop using that one first.",
            // #124 (C42): P124-8, the first schedule the strand names (here the one in the list).
            SeasonStartStrandCause.HAS_DEPENDENT_SCHEDULES to "\"Example annual service\" counts from Example Heater's " +
                "Season Start service. Change when it starts counting first.",
        )
        assertEquals(SeasonStartStrandCause.entries.toSet(), expected.keys)
        expected.forEach { (cause, sentence) ->
            val refusal = SeasonStartRefusal.YearRound(heater).takeIf { cause == SeasonStartStrandCause.RESTORE_NOT_ELIGIBLE }
            assertEquals(cause.name, sentence, SeasonStartStrings.strand(strands(cause, refusal), "Example Heater"))
        }
    }

    /**
     * #124 row 149 (N2b, C42): the role action on a schedule that counts from the Season Start service is refused with
     * P124-7, and "Stop using as Season Start service" while a schedule counts from it says P124-8 — the role kept.
     */
    @Test fun aScheduleCountingFromTheServiceBlocksTheRoleActionWithItsSentence() = runTest {
        heater()
        graph.setScheduleRole.run(ScheduleId("annual"), ScheduleRole.SEASON_START)
        graph.schedules.upsert(
            scheduleOf("filter", assetId = "heater", title = "Example filter check", completionMode = CompletionMode.FORM)
                .copy(servicePolicy = ServicePolicy.IN_SERVICE_AFTER_OPENING_SERVICE, policyOffsetDays = 0),
        )

        val dependent = roleModel("filter")
        dependent.opened()
        assertEquals(
            "This schedule counts from the Season Start service, so it cannot be one. Change when it starts counting first.",
            confirmed(dependent).confirm?.problem,
        )
        assertEquals(ScheduleRole.NONE, graph.schedules.get(ScheduleId("filter"))!!.role)

        val role = roleModel("annual")
        role.state.first { it.loaded && it.canStop }
        val said = CompletableDeferred<String>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { said.complete(role.messages.first()) }
        role.stop()
        advanceUntilIdle()
        assertEquals(
            "\"Example filter check\" counts from Example Heater's Season Start service. Change when it starts counting first.",
            said.await(),
        )
        assertEquals(ScheduleRole.SEASON_START, graph.schedules.get(ScheduleId("annual"))!!.role)
        assertTrue("the action stays offered", role.state.first { !it.busy }.canStop)
    }

    /** P114-1 on the role schedule's row (keyed on the role), and nothing on any other row. */
    @Test fun theRowCarriesTheLabel() = runTest {
        heater()
        graph.setScheduleRole.run(ScheduleId("annual"), ScheduleRole.SEASON_START)
        val rows = graph.dueReadModel.items().associateBy { it.scheduleId.value }
        assertEquals("Season Start service", seasonStartLine(rows.getValue("annual")))
        assertNull(seasonStartLine(rows.getValue("quick")))
    }
}
