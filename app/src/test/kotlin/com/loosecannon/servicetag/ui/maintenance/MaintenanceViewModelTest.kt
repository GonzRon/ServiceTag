package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.reminders.ReminderHealthSeverity
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.reminders.NotificationPermission
import com.loosecannon.servicetag.testing.FakeGraph
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Maintenance destination's state. The rules about what is due and in what order belong to the
 * projection and are proved in `DueReadModelTest`; what is proved here is the one thing this
 * destination does that the dashboard deliberately does not — list a **paused** schedule — and the
 * three other sections' contents.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MaintenanceViewModelTest {

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

    private fun viewModel(
        health: HealthSummary = NoHealthFindings,
        notificationsGranted: Boolean = true,
    ) = MaintenanceViewModel(
        schedules = graph.schedules,
        states = graph.scheduleStates,
        groups = graph.groups,
        due = DueReadModel(
            graph.schedules, graph.scheduleStates, graph.assets, graph.groups,
            graph.definitions, graph.recomputeSchedules, graph.todayPort, { null },
        ),
        health = health,
        notifications = object : NotificationPermission {
            override fun granted(): Boolean = notificationsGranted
            override fun shouldExplain(): Boolean = false
            override suspend fun request(): Boolean = notificationsGranted
        },
    )

    private suspend fun seed(schedule: MaintenanceSchedule) {
        graph.schedules.upsert(schedule)
        graph.recomputeSchedules.forSchedule(schedule.id)
    }

    /**
     * Matrix row "a state with no home", the shell's half: the **same** PAUSED schedule the
     * dashboard puts in no section is listed here under Schedules with its ratified label, while
     * due work holds only what has a section (master plan §11.1, T8).
     */
    @Test fun schedulesListsThePausedOneThatDueWorkOmits() = runTest {
        val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
        seed(scheduleOf("s-overdue", assetId = mower.id.value, title = "Blade sharpen", anchorOn = "2026-01-01", leadDays = 0))
        seed(scheduleOf("s-paused", assetId = mower.id.value, title = "Winter service", anchorOn = "2026-01-01", status = ScheduleStatus.PAUSED))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.schedules.isNotEmpty() }
        assertEquals(listOf("Blade sharpen"), state.dueWork.map { it.title })
        assertEquals(listOf("Blade sharpen", "Winter service"), state.schedules.map { it.title })
        assertEquals(
            DueStatus.PAUSED,
            state.schedules.single { it.title == "Winter service" }.status,
        )
        assertEquals("PAUSED", statusLabel(state.schedules.single { it.title == "Winter service" }.status))
        assertFalse("and a paused schedule is in no due total", state.schedules.single { it.title == "Winter service" }.countsAsDue)
    }

    /**
     * An archived **schedule** is listed nowhere here either — carry-forward (a): this destination's
     * lists come from the same projection, which starts from `listedForDue()`.
     */
    @Test fun anArchivedScheduleAppearsInNeitherList() = runTest {
        val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
        seed(scheduleOf("s-live", assetId = mower.id.value, title = "Blade sharpen", anchorOn = "2026-01-01"))
        seed(scheduleOf("s-archived", assetId = mower.id.value, title = "Retired work", status = ScheduleStatus.ARCHIVED))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.schedules.isNotEmpty() }
        assertEquals(listOf("Blade sharpen"), state.schedules.map { it.title })
        assertEquals(emptyList<String>(), state.dueWork.filter { it.title == "Retired work" }.map { it.title })
    }

    /**
     * Every group is listed, **archived ones included and marked** (master plan decision 39), and
     * the count is the windows that are open now.
     *
     * What archiving takes away is the group's due work, which the projection drops; the group
     * itself stays findable, because #55 requires an archived group to keep its maintenance history
     * and history nobody can reach is not kept.
     */
    @Test fun everyGroupIsListedAndAnArchivedOneIsMarked() = runTest {
        val heads = (1..3).map { graph.createAsset.run(AssetCommand(name = "Sprinkler $it", category = "Irrigation")) }
        graph.groups.upsert(
            groupOf(
                "g1",
                name = "North run",
                members = heads.mapIndexed { index, asset ->
                    Triple(asset.id.value, "2026-01-01", if (index == 2) "2026-03-01" else null)
                },
            ),
        )
        graph.groups.upsert(groupOf("g2", name = "Old run", archivedAt = 5_000L))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.groups.isNotEmpty() }
        assertEquals(listOf("North run", "Old run"), state.groups.map { it.name })
        assertEquals(listOf(false, true), state.groups.map { it.archived })
        val live = state.groups.single { it.name == "North run" }
        assertEquals("the removed window is history, not membership", 2, live.memberCount)
    }

    /**
     * C5 (controller ruling, 2026-09-22): a phone with no groups is **a list with nothing in it**,
     * not the absence of a list — the group section is drawn either way, because its create
     * affordance is the only in-app way to make the first group.
     *
     * What the JVM can pin is the state the section is drawn from: `loaded` true with an empty
     * [MaintenanceState.groups], which is distinguishable from "not read yet" and is therefore a
     * state the screen can draw a section for. That the section really is on screen with its
     * affordance is a composition fact and is proved on the device by `MaintenanceTabTest`, since
     * this module has no JVM Compose runtime.
     */
    @Test fun aPhoneWithNoGroupsIsAListWithNothingInItRatherThanNoList() = runTest {
        val vm = viewModel()
        assertFalse("nothing has been read yet", vm.state.value.loaded)

        backgroundScope.launch { vm.state.collect() }
        val loaded = vm.state.first { it.loaded }
        assertEquals(emptyList<MaintenanceGroupRow>(), loaded.groups)
        assertTrue("and the phone has nothing scheduled either", loaded.isEmpty)
    }

    /**
     * The empty state is "no schedules", not "not read yet": `loaded` is what tells them apart, and
     * a line that flashed up before the first read would be the wrong sentence at the wrong moment.
     */
    @Test fun theEmptyStateWaitsUntilSomethingHasBeenRead() = runTest {
        val vm = viewModel()
        assertFalse("the seed state has read nothing", vm.state.value.isEmpty)

        backgroundScope.launch { vm.state.collect() }
        val loaded = vm.state.first { it.loaded }
        assertTrue(loaded.isEmpty)
        assertEquals(emptyList<DueItem>(), loaded.schedules)
    }

    /**
     * D-22: a denied notification permission is explained by one dismissible line and **disables
     * nothing** — the schedules are still listed, the reminders are still switched on, and the
     * dismissal is the owner's.
     */
    @Test fun theBlockedNotificationsLineIsExplanatoryAndDismissible() = runTest {
        val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
        seed(scheduleOf("s-live", assetId = mower.id.value, title = "Blade sharpen", anchorOn = "2026-01-01"))

        val vm = viewModel(notificationsGranted = false)
        backgroundScope.launch { vm.state.collect() }

        val blocked = vm.state.first { it.loaded }
        assertTrue(blocked.notificationsBlocked)
        assertEquals("nothing is taken away", listOf("Blade sharpen"), blocked.schedules.map { it.title })

        vm.dismissReminderNotice()
        val dismissed = vm.state.first { !it.notificationsBlocked }
        assertEquals(listOf("Blade sharpen"), dismissed.schedules.map { it.title })

        // And the granted phone never sees it at all.
        val granted = viewModel(notificationsGranted = true)
        backgroundScope.launch { granted.state.collect() }
        assertFalse(granted.state.first { it.loaded }.notificationsBlocked)
    }

    /** The badge threshold is one rule, shared with the dashboard: >= WARN, and INFO alone is not. */
    @Test fun theBadgeThresholdIsTheSameHere() = runTest {
        for ((severity, shows) in listOf(null to false, ReminderHealthSeverity.INFO to false, ReminderHealthSeverity.WARN to true, ReminderHealthSeverity.ERROR to true)) {
            val vm = viewModel(health = object : HealthSummary {
                override suspend fun worstSeverity(): ReminderHealthSeverity? = severity
            })
            backgroundScope.launch { vm.state.collect() }
            val state = vm.state.first { it.loaded }
            assertEquals(severity, state.worstSeverity)
            assertEquals("badge for $severity", shows, state.worstSeverity.showsBadge())
        }
    }
}
