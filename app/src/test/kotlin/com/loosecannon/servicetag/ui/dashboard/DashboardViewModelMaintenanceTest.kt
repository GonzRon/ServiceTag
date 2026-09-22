package com.loosecannon.servicetag.ui.dashboard

import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.groupOf
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.ui.maintenance.AttentionSection
import com.loosecannon.servicetag.ui.maintenance.DueReadModel
import com.loosecannon.servicetag.ui.maintenance.HealthSummary
import com.loosecannon.servicetag.ui.maintenance.NoHealthFindings
import com.loosecannon.servicetag.ui.maintenance.Severity
import com.loosecannon.servicetag.ui.maintenance.showsBadge
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
 * What 1.2 adds to the dashboard: the attention sections in their fixed order, the promotion that
 * keeps a part's due work visible, F2's two filters, the due total and the health badge. #39's own
 * behaviour — the six searched fields, the "parts live on their systems" line and the backup nudge
 * — stays in `DashboardViewModelTest`, unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelMaintenanceTest {

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

    private fun viewModel(health: HealthSummary = NoHealthFindings) = DashboardViewModel(
        assets = graph.assets,
        schedules = graph.schedules,
        states = graph.scheduleStates,
        due = DueReadModel(
            graph.schedules, graph.scheduleStates, graph.assets, graph.groups,
            graph.definitions, graph.recomputeSchedules, graph.todayPort,
        ),
        health = health,
        prefs = graph.prefs,
    )

    private suspend fun seed(schedule: MaintenanceSchedule) {
        graph.schedules.upsert(schedule)
        graph.recomputeSchedules.forSchedule(schedule.id)
    }

    private suspend fun asset(name: String, category: String = "Yard", parent: com.loosecannon.servicetag.core.model.AssetId? = null) =
        graph.createAsset.run(AssetCommand(name = name, category = category, parentAssetId = parent))

    /**
     * Matrix row "section order drifting". The sections come out in D12 §10's fixed order and a
     * section with no rows is **not** in the list at all — ordering by anything else, or drawing an
     * empty header, is exactly what D12 §10 forbids (`:706-707`).
     */
    @Test fun sectionsComeOutInTheFixedOrderAndEmptyOnesAreOmitted() = runTest {
        val blower = graph.createAsset.run(
            AssetCommand(name = "Snowblower", category = "Yard", seasonStartMmdd = "11-01", seasonEndMmdd = "02-28"),
        )
        val mower = asset("Mower")
        seed(scheduleOf("s-overdue", assetId = mower.id.value, title = "Blade sharpen", anchorOn = "2026-01-01", leadDays = 0))
        seed(scheduleOf("s-season", assetId = blower.id.value, title = "Pre-season check", anchorOn = "2026-01-01", seasonBehavior = SeasonBehavior.FOLLOW_ASSET))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.sections.size == 2 }
        // ATTENTION before OUT OF SEASON, and no UPCOMING or CURRENT header for rows that are not
        // there. CURRENT has no asset rows either: both assets carry a schedule.
        assertEquals(
            listOf(AttentionSection.ATTENTION, AttentionSection.OUT_OF_SEASON),
            state.sections.map { it.section },
        )
        assertEquals(emptyList<String>(), state.assets.map { it.asset.name })
    }

    /**
     * Matrix row "a part's due work hidden" (invariant 75, #5 AC 1). A component's **OVERDUE**
     * schedule is listed with a blank query, at its attention rank, naming its parent — and a
     * component whose schedule is merely `OK` is still governed by the blank-query rule and is not.
     */
    @Test fun aComponentsDueWorkIsVisibleWithABlankQueryAndItsQuietWorkIsNot() = runTest {
        val tub = asset("Hot tub", category = "Water")
        val pump = asset("Circulation pump", category = "Water", parent = tub.id)
        val heater = asset("Heater element", category = "Water", parent = tub.id)

        seed(scheduleOf("s-pump", assetId = pump.id.value, title = "Impeller check", anchorOn = "2026-01-01", leadDays = 0))
        seed(scheduleOf("s-heater", assetId = heater.id.value, title = "Element inspect", anchorOn = "2026-12-01", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, leadDays = 0))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.sections.isNotEmpty() }
        val attention = state.sections.single { it.section == AttentionSection.ATTENTION }
        assertEquals(listOf("Impeller check"), attention.items.map { it.title })
        // The promoted row says whose part it is; a name with no home is what #39 was about.
        assertEquals("Hot tub", attention.items.single().parentName)
        assertEquals("the promoted row is at the top of the order", 0, attention.items.single().rank)

        // The quiet component is nowhere: not in CURRENT, not as an asset row.
        val listedTitles = state.sections.flatMap { it.items }.map { it.title }
        assertFalse("a non-actionable component row stays hidden", "Element inspect" in listedTitles)
        assertEquals(listOf("Hot tub"), state.assets.map { it.asset.name })
    }

    /**
     * Matrix row "F2: a filter that reorders". Both controls narrow; neither touches the section
     * order or the `rank` of the rows that survive — a filter applied inside the projection would
     * renumber them and break the order `/v1/due`'s clients and B09's sheet share. "All categories"
     * and "All statuses" put the whole list back.
     */
    @Test fun aFilterNarrowsTheListAndLeavesTheOrderAndRanksAlone() = runTest {
        val mower = asset("Mower", category = "Yard")
        val tub = asset("Hot tub", category = "Water")
        seed(scheduleOf("s-mower", assetId = mower.id.value, title = "Blade sharpen", anchorOn = "2026-01-01", leadDays = 0))
        seed(scheduleOf("s-tub", assetId = tub.id.value, title = "Filter clean", anchorOn = "2026-01-01", leadDays = 0))
        seed(scheduleOf("s-soon", assetId = tub.id.value, title = "Water test", anchorOn = "2026-04-20", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, leadDays = 14))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val all = vm.state.first { it.sections.size == 2 }
        val ranksBefore = all.sections.flatMap { it.items }.associate { it.scheduleId.value to it.rank }
        assertEquals(listOf("Blade sharpen", "Filter clean", "Water test"), all.sections.flatMap { it.items }.map { it.title })
        assertEquals(listOf("Water", "Yard"), all.filters.categories)

        vm.onCategoryChange("Water")
        val byCategory = vm.state.first { it.filters.category == "Water" }
        assertEquals(listOf("Filter clean", "Water test"), byCategory.sections.flatMap { it.items }.map { it.title })
        assertEquals(
            listOf(AttentionSection.ATTENTION, AttentionSection.UPCOMING),
            byCategory.sections.map { it.section },
        )
        byCategory.sections.flatMap { it.items }.forEach { row ->
            assertEquals(
                "a filter must not renumber ${row.scheduleId.value}",
                ranksBefore[row.scheduleId.value],
                row.rank,
            )
        }

        vm.onStatusChange(DueStatus.DUE_SOON)
        val both = vm.state.first { it.filters.status == DueStatus.DUE_SOON }
        assertEquals(listOf("Water test"), both.sections.flatMap { it.items }.map { it.title })
        assertEquals(listOf(AttentionSection.UPCOMING), both.sections.map { it.section })
        assertEquals(ranksBefore["s-soon"], both.sections.single().items.single().rank)

        // "All categories" / "All statuses" are the absence of a filter, and they restore the list.
        vm.onCategoryChange(null)
        vm.onStatusChange(null)
        val restored = vm.state.first { !it.filters.isActive }
        assertEquals(listOf("Blade sharpen", "Filter clean", "Water test"), restored.sections.flatMap { it.items }.map { it.title })
        assertEquals(ranksBefore, restored.sections.flatMap { it.items }.associate { it.scheduleId.value to it.rank })
    }

    /** F2 is applied **after** the search, so the two narrow together rather than fighting. */
    @Test fun theFilterIsAppliedAfterTheSearch() = runTest {
        val mower = asset("Mower", category = "Yard")
        val tub = asset("Hot tub", category = "Water")
        seed(scheduleOf("s-mower", assetId = mower.id.value, title = "Blade sharpen", anchorOn = "2026-01-01", leadDays = 0))
        seed(scheduleOf("s-tub", assetId = tub.id.value, title = "Filter clean", anchorOn = "2026-01-01", leadDays = 0))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.sections.isNotEmpty() }

        vm.onQueryChange("tub")
        vm.onCategoryChange("Yard")
        val narrowed = vm.state.first { it.query == "tub" && it.filters.category == "Yard" }
        // The search keeps the tub's row and the category filter then rejects it: nothing is left,
        // which is the only honest answer to "the Yard row named tub".
        assertEquals(emptyList<String>(), narrowed.sections.flatMap { it.items }.map { it.title })
    }

    /**
     * Matrix rows "a group row fanning out or double-counting", "an empty group counted" and "a
     * paused or out-of-season schedule in a due total" — the counting half, on the surface that
     * shows the total (invariants 22, 74, D-15).
     */
    @Test fun theDueTotalCountsAGroupOnceAndCountsNeitherPausedNorParkedNorEmpty() = runTest {
        val blower = graph.createAsset.run(
            AssetCommand(name = "Snowblower", category = "Yard", seasonStartMmdd = "11-01", seasonEndMmdd = "02-28"),
        )
        val members = (1..5).map { asset("Sprinkler $it", category = "Irrigation") }
        graph.groups.upsert(groupOf("g1", members = members.map { Triple(it.id.value, "2026-01-01", null) }))
        graph.groups.upsert(groupOf("g-empty", name = "Emptied run"))

        seed(scheduleOf("s-group", groupId = "g1", title = "Head check", anchorOn = "2026-01-01", leadDays = 0))
        seed(scheduleOf("s-empty", groupId = "g-empty", title = "Nobody's round", anchorOn = "2026-01-01"))
        seed(scheduleOf("s-paused", assetId = blower.id.value, title = "Paused work", anchorOn = "2026-01-01", status = ScheduleStatus.PAUSED))
        seed(scheduleOf("s-season", assetId = blower.id.value, title = "Parked work", anchorOn = "2026-01-01", seasonBehavior = SeasonBehavior.FOLLOW_ASSET))

        graph.completeGroupMembers.run(
            ScheduleId("s-group"),
            members.take(3).map { it.id },
            CompletionCommand(occurredOn = "2026-04-14", tzId = "UTC"),
        )

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.sections.isNotEmpty() }
        assertEquals("the group's one outstanding round, and nothing else", 1, state.dueCount)

        val group = state.sections.flatMap { it.items }.single { it.scheduleId.value == "s-group" }
        assertEquals(5, group.membersRequired)
        assertEquals(3, group.membersComplete)

        val listed = state.sections.flatMap { it.items }.map { it.scheduleId.value }
        assertFalse("an empty required set is in no section", "s-empty" in listed)
        assertFalse("a paused schedule is in no section", "s-paused" in listed)
        assertTrue("a parked one is in OUT OF SEASON, just not in the total", "s-season" in listed)
    }

    /**
     * Matrix row "the badge threshold" (#27, D3 §7.3), against a fake [HealthSummary] — B10 has not
     * landed, and the badge must not wait for it. `WARN` and `ERROR` light it; an `INFO`-only set
     * and no findings at all do not, because a badge that never clears has stopped saying anything.
     */
    @Test fun theBadgeAppearsAtWarnAndNotForInfoAlone() = runTest {
        val mower = asset("Mower")
        seed(scheduleOf("s-1", assetId = mower.id.value, title = "Blade sharpen", anchorOn = "2026-01-01"))

        for ((severity, shows) in listOf(null to false, Severity.INFO to false, Severity.WARN to true, Severity.ERROR to true)) {
            val vm = viewModel(health = object : HealthSummary {
                override suspend fun worstSeverity(): Severity? = severity
            })
            backgroundScope.launch { vm.state.collect() }
            val state = vm.state.first { it.sections.isNotEmpty() }
            assertEquals(severity, state.worstSeverity)
            assertEquals("badge for $severity", shows, state.worstSeverity.showsBadge())
        }
    }

    /**
     * Matrix row "the search regressing", with schedules in play: typing still reaches the six
     * fields, and it reaches the schedule rows through the same predicate rather than a second one.
     */
    @Test fun theSearchStillReachesTheSixFieldsOnceSchedulesExist() = runTest {
        val mower = graph.createAsset.run(
            AssetCommand(name = "Mower", category = "Yard", model = "SP2610X15", location = "Shed"),
        )
        val tub = asset("Hot tub", category = "Water")
        seed(scheduleOf("s-mower", assetId = mower.id.value, title = "Blade sharpen", anchorOn = "2026-01-01", leadDays = 0))
        seed(scheduleOf("s-tub", assetId = tub.id.value, title = "Filter clean", anchorOn = "2026-01-01", leadDays = 0))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.sections.isNotEmpty() }

        for (typed in listOf("mower", "YARD", "sp2610", "shed")) {
            vm.onQueryChange(typed)
            val hit = vm.state.first { it.query == typed }
            assertEquals(
                "typing \"$typed\" should reach the mower's schedule",
                listOf("Blade sharpen"),
                hit.sections.flatMap { it.items }.map { it.title },
            )
        }

        // And not on the prose fields, which are deliberately out of the set.
        vm.onQueryChange("impeller")
        assertEquals(emptyList<String>(), vm.state.first { it.query == "impeller" }.sections.flatMap { it.items })
    }
}
