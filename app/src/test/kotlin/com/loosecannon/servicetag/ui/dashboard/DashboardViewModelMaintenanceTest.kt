package com.loosecannon.servicetag.ui.dashboard

import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.reminders.ReminderHealthSeverity
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.testing.groupOf
import com.loosecannon.servicetag.testing.replacementOf
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.testing.subjectRow
import com.loosecannon.servicetag.ui.maintenance.AttentionKind
import com.loosecannon.servicetag.ui.maintenance.AttentionSection
import com.loosecannon.servicetag.ui.maintenance.DueReadModel
import com.loosecannon.servicetag.ui.maintenance.HealthSummary
import com.loosecannon.servicetag.ui.maintenance.NoHealthFindings
import com.loosecannon.servicetag.ui.maintenance.showsBadge
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What 1.2 adds to the dashboard: the attention sections in their fixed order, the promotion that
 * keeps a part's due work visible, F2's two filters, the due total and the health badge. #39's own
 * behaviour — the "parts live on their systems" line and the backup nudge — stays in
 * `DashboardViewModelTest`; its search box moved to the Assets screen in B07.
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
            graph.schedules, graph.assets, graph.groups,
            graph.definitions, graph.recomputeSchedules, graph.todayPort, graph.assetHealthReadModel, { null },
        ),
        attention = graph.attentionReadModel,
        assetHealth = graph.assetHealthReadModel,
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
        seed(scheduleOf("s-season", assetId = blower.id.value, title = "Pre-season check", anchorOn = "2026-01-01", servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0))

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
     * schedule is listed, at its attention rank, naming its parent — and a component whose schedule
     * is merely `OK` is still governed by the systems-only rule and is not.
     */
    @Test fun aComponentsDueWorkIsVisibleAndItsQuietWorkIsNot() = runTest {
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
        seed(scheduleOf("s-season", assetId = blower.id.value, title = "Parked work", anchorOn = "2026-01-01", servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0))

        graph.completeGroupMembers.run(
            ScheduleId("s-group"),
            members.take(3).map { it.id },
            CompletionCommand(occurredOn = "2026-04-14", tzId = "UTC"),
        )

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.sections.isNotEmpty() }
        assertEquals(
            "the group's one outstanding round, and nothing else",
            1,
            state.sections.flatMap { it.items }.count { it.countsAsDue },
        )

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

        for ((severity, shows) in listOf(null to false, ReminderHealthSeverity.INFO to false, ReminderHealthSeverity.WARN to true, ReminderHealthSeverity.ERROR to true)) {
            val vm = viewModel(health = object : HealthSummary {
                override suspend fun worstSeverity(): ReminderHealthSeverity? = severity
            })
            backgroundScope.launch { vm.state.collect() }
            val state = vm.state.first { it.sections.isNotEmpty() }
            assertEquals(severity, state.worstSeverity)
            assertEquals("badge for $severity", shows, state.worstSeverity.showsBadge())
        }
    }

    /**
     * Controller ruling, fix round 1: **an asset appears exactly once** on the dashboard — in its
     * schedule's section when any of its schedules is drawn there, and in the asset list otherwise.
     * An asset whose only schedule is `PAUSED` is therefore still on the landing screen: §11.1 puts
     * a paused schedule in no *section*, and says nothing about removing the asset. Before the fix
     * this phone drew no section, no asset row and no message at all.
     */
    @Test fun anAssetWhoseOnlyScheduleIsPausedIsStillOnTheDashboard() = runTest {
        val mower = asset("Mower")
        seed(scheduleOf("s-paused", assetId = mower.id.value, title = "Winter service", anchorOn = "2026-01-01", status = ScheduleStatus.PAUSED))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.assets.isNotEmpty() }
        assertEquals(emptyList<AttentionSection>(), state.sections.map { it.section })
        assertEquals(listOf("Mower"), state.assets.map { it.asset.name })
        // It does have a schedule, so the row must not claim otherwise — the screen omits the
        // shipped "No schedule yet" line for exactly this row.
        assertTrue("the row knows it has a schedule", state.assets.single().hasSchedule)
        assertTrue(state.sections.flatMap { it.items }.none { it.countsAsDue })
    }

    /**
     * The same ruling's other half, and the reason the narrowing exists at all: an asset whose
     * schedule **is** drawn appears once, as that schedule's row, and not a second time as an asset
     * row under the false "No schedule yet".
     */
    @Test fun anAssetWithADrawnScheduleAppearsOnlyAsThatScheduleSRow() = runTest {
        val mower = asset("Mower")
        seed(scheduleOf("s-overdue", assetId = mower.id.value, title = "Blade sharpen", anchorOn = "2026-01-01", leadDays = 0))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.sections.isNotEmpty() }
        assertEquals(listOf("Blade sharpen"), state.sections.single().items.map { it.title })
        assertEquals(emptyList<String>(), state.assets.map { it.asset.name })
    }

    /**
     * A phone whose only schedule obliges nobody is the other half of the same ruling: the row is in
     * no section (invariant 74), so its asset stays in the asset list rather than vanishing with it.
     */
    @Test fun anAssetWhoseOnlyRoundObligesNobodyIsStillOnTheDashboard() = runTest {
        val head = asset("Sprinkler 1", category = "Irrigation")
        graph.groups.upsert(groupOf("g-empty", name = "Emptied run"))
        seed(scheduleOf("s-empty", groupId = "g-empty", title = "Nobody's round", anchorOn = "2026-01-01"))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.assets.isNotEmpty() }
        assertEquals(emptyList<AttentionSection>(), state.sections.map { it.section })
        // The group's schedule is not on this asset at all, so the asset is simply unscheduled.
        assertEquals(listOf("Sprinkler 1"), state.assets.map { it.asset.name })
        assertFalse(state.assets.single().hasSchedule)
        assertEquals(head.id, state.assets.single().asset.id)
        assertTrue(state.sections.flatMap { it.items }.none { it.countsAsDue })
    }

    /**
     * Decision 32, at the seam this brief owns: the store's half — the projection and the health
     * summary — is read when the tables or a [DashboardViewModel.refresh] move, and **not** on a
     * filter change. The real check reads the standby bucket, so a per-emission call would probe
     * the platform on every choice made.
     */
    @Test fun aFilterChangeReadsNeitherTheProjectionNorTheHealthSummary() = runTest {
        val mower = asset("Mower")
        seed(scheduleOf("s-overdue", assetId = mower.id.value, title = "Blade sharpen", anchorOn = "2026-01-01", leadDays = 0))

        var healthCalls = 0
        val vm = viewModel(health = object : HealthSummary {
            override suspend fun worstSeverity(): ReminderHealthSeverity? {
                healthCalls += 1
                return null
            }
        })
        backgroundScope.launch { vm.state.collect() }
        vm.state.first { it.sections.isNotEmpty() }
        val afterFirstRead = healthCalls

        // A filter change: the list narrows, and the store is not read again.
        vm.onCategoryChange("Yard")
        vm.state.first { it.filters.category == "Yard" }
        assertEquals("a filter change must not probe the platform", afterFirstRead, healthCalls)

        // A refresh is the screen coming back into composition, and that does read again. The
        // wait is on the scheduler and not on an emission: the re-read produces an equal
        // `DashboardState`, which a `StateFlow` conflates away, so waiting for one would hang.
        vm.refresh()
        advanceUntilIdle()
        assertTrue("a refresh reads the store again", healthCalls > afterFirstRead)
    }

    /**
     * Q1 (B07 fix round 2, controller ruling). B07 simplified two `DashboardScreen` guards down to
     * `state.hiddenComponents > 0` and `state.filters.isActive`, dropping their query half — and
     * with `DashboardSearchTest` renamed away, nothing rendered them anymore. This pins what they
     * now encode at the state that feeds them: the list is the systems, a component stays on the
     * one it belongs to and is only counted, and an unrelated asset's due row is exactly what the
     * projection ranked — nothing about its name or category narrows it away now that the query is
     * gone.
     */
    @Test fun theListHidesComponentsAndTheDueRowsAreNotNarrowed() = runTest {
        val tub = asset("Hot tub", category = "Water")
        asset("Circulation pump", category = "Water", parent = tub.id)
        val mower = asset("Mower", category = "Yard")
        seed(scheduleOf("s-mower", assetId = mower.id.value, title = "Blade sharpen", anchorOn = "2026-01-01", leadDays = 0))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.sections.isNotEmpty() }
        // The list is the systems: the pump stays on Hot tub, counted rather than listed.
        assertEquals(listOf("Hot tub"), state.assets.map { it.asset.name })
        assertEquals(1, state.hiddenComponents)
        // The due row is exactly what the projection ranked — an asset with a different name and
        // category does not narrow it away.
        assertEquals(listOf("Blade sharpen"), state.sections.flatMap { it.items }.map { it.title })
    }

    // ------------------------------------------------------------ 1.4: condition and health rows

    /** An AGE subject on [assetId] whose only replacement was [daysAgo] days before `T` (0 / 40 / 75). */
    private suspend fun ageSubject(id: String, assetId: String, daysAgo: Long, name: String) {
        graph.events.upsert(replacementOf("e-$id", assetId, graph.today.minusDays(daysAgo).toString()))
        graph.healthSubjects.upsert(subjectRow(id, assetId, name = name))
    }

    /** A section's rows as tokens: `schedule:<title>`, `<CONDITION>:<asset>`, `<BAND>:<subject>`. */
    private fun AttentionGroup.drawn(): List<String> = entries.map { entry ->
        when (entry) {
            is SectionEntry.Schedule -> "schedule:${entry.item.title}"
            is SectionEntry.AssetLevel -> when (entry.item.kind) {
                AttentionKind.CONDITION -> "${entry.item.condition}:${entry.item.assetName}"
                AttentionKind.HEALTH -> "${entry.item.band}:${entry.item.subjectName}"
            }
        }
    }

    /**
     * Spec §10.2's ATTENTION: DOWN units ▸ the schedule rows ▸ DEGRADED units ▸ independent CRITICAL
     * health. A DOWN asset that also has due work shows its condition row first and its schedule
     * row among the schedules, and is not in the plain list.
     */
    @Test fun downThenSchedulesThenDegradedThenCritical() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.conditions.insert(conditionRow("c1", "gen", OperationalCondition.DOWN, "2026-04-10", reason = "Won't start"))
        graph.assets.upsert(assetRow("tub", name = "Hot tub"))
        graph.conditions.insert(conditionRow("c2", "tub", OperationalCondition.DEGRADED, "2026-04-12"))
        graph.assets.upsert(assetRow("ups", name = "UPS"))
        ageSubject("h-crit", "ups", daysAgo = 90, name = "Battery age")
        graph.assets.upsert(assetRow("mow", name = "Mower"))
        seed(scheduleOf("s-mow", assetId = "mow", title = "Blade sharpen", leadDays = 0))
        seed(scheduleOf("s-gen", assetId = "gen", title = "Engine oil service", leadDays = 0))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.anyInService }
        assertEquals(listOf(AttentionSection.ATTENTION), state.sections.map { it.section })
        assertEquals(
            listOf("DOWN:Generator", "schedule:Blade sharpen", "schedule:Engine oil service", "DEGRADED:Hot tub", "CRITICAL:Battery age"),
            state.sections.single().drawn(),
        )
        // S22 reads the row's `since`; the badge's word is the row's own condition.
        assertEquals("2026-04-10", state.sections.single().assetRows.first().since)
        assertEquals(emptyList<String>(), state.assets.map { it.asset.name })
    }

    /** UPCOMING: the DUE SOON rows, then independent WARNING health after them. */
    @Test fun warningHealthFollowsDueSoon() = runTest {
        graph.assets.upsert(assetRow("mow", name = "Mower"))
        seed(scheduleOf("s-soon", assetId = "mow", title = "Blade sharpen", anchorOn = "2026-04-20", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, leadDays = 14))
        // Named to sort first by name: the group order, not the name, puts it after the schedule.
        graph.assets.upsert(assetRow("ups", name = "Alpha UPS"))
        ageSubject("h-warn", "ups", daysAgo = 50, name = "Battery age")

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.anyInService }
        assertEquals(listOf(AttentionSection.UPCOMING), state.sections.map { it.section })
        assertEquals(listOf("schedule:Blade sharpen", "WARNING:Battery age"), state.sections.single().drawn())
    }

    /**
     * Spec §4.5, inv. 103: a row the break holds is in its own quiet section between CURRENT and
     * OUT OF SEASON, counts for nothing, and answers the DEFERRED status option — never OVERDUE or
     * DUE.
     */
    @Test fun deferredSitsBetweenCurrentAndOutOfSeasonAndCountsForNothing() = runTest {
        graph.today = LocalDate.parse("2026-12-10")
        graph.assets.upsert(assetRow("gen", name = "Generator", breakStart = "12-01", breakEnd = "02-28"))
        graph.assets.upsert(assetRow("mow", name = "Mower", seasonMode = SeasonMode.CALENDAR, seasonStart = "04-15", seasonEnd = "10-31"))
        seed(
            scheduleOf(
                "s-held", assetId = "gen", title = "Engine oil service", timeInterval = 6, anchorOn = "2026-12-20",
                createdOn = "2026-06-01", servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0,
            ),
        )
        seed(scheduleOf("s-ok", assetId = "gen", title = "Air filter", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = "2027-06-01", createdOn = "2026-06-01"))
        seed(scheduleOf("s-dormant", assetId = "mow", title = "Blade sharpen", anchorOn = "2026-06-01", createdOn = "2026-06-01", servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.sections.size == 3 }
        assertEquals(
            listOf(AttentionSection.CURRENT, AttentionSection.DEFERRED, AttentionSection.OUT_OF_SEASON),
            state.sections.map { it.section },
        )
        val deferred = state.sections.single { it.section == AttentionSection.DEFERRED }
        assertEquals(listOf("s-held"), deferred.items.map { it.scheduleId.value })
        assertEquals(DueStatus.DEFERRED, deferred.items.single().status)
        assertEquals("DEFERRED counts for nothing", 0, state.sections.flatMap { it.items }.count { it.countsAsDue })

        vm.onStatusChange(DueStatus.DEFERRED)
        val held = vm.state.first { it.filters.status == DueStatus.DEFERRED }
        assertEquals(listOf(AttentionSection.DEFERRED), held.sections.map { it.section })

        for (due in listOf(DueStatus.OVERDUE, DueStatus.DUE)) {
            vm.onStatusChange(due)
            val under = vm.state.first { it.filters.status == due }
            assertFalse("never under $due", "s-held" in under.sections.flatMap { it.items }.map { it.scheduleId.value })
        }
    }

    /**
     * Inv. 122: a DOWN unit with nothing due reaches ATTENTION — a component naming its parent —
     * and neither is left in the plain asset list nor hidden with the parts.
     */
    @Test fun aDownComponentWithNothingDueIsInAttentionWithItsParent() = runTest {
        graph.assets.upsert(assetRow("tub", name = "Hot tub"))
        graph.assets.upsert(assetRow("pack", name = "Battery pack", parent = "tub"))
        graph.conditions.insert(conditionRow("c1", "pack", OperationalCondition.DOWN, "2026-04-12", reason = "Will not hold a charge"))
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.conditions.insert(conditionRow("c2", "gen", OperationalCondition.DOWN, "2026-04-01"))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.anyInService }
        // The plain list is the systems with nothing drawn: the tub, and not the generator.
        assertEquals("a DOWN unit is not left in the plain list", listOf("Hot tub"), state.assets.map { it.asset.name })
        assertEquals(listOf(AttentionSection.ATTENTION), state.sections.map { it.section })
        val attention = state.sections.single()
        assertEquals(listOf("DOWN:Battery pack", "DOWN:Generator"), attention.drawn())
        val pack = attention.assetRows.first()
        assertEquals("the component names its parent", "Hot tub", pack.parentName)
        assertEquals("Will not hold a charge", pack.reason)
    }

    /** Plan decision 40: an asset drawn as any row in a section is not repeated in the plain list. */
    @Test fun anAssetDrawnAsARowIsNotRepeatedInTheAssetList() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.conditions.insert(conditionRow("c1", "gen", OperationalCondition.DEGRADED, "2026-04-01"))
        graph.assets.upsert(assetRow("ups", name = "UPS"))
        ageSubject("h-crit", "ups", daysAgo = 90, name = "Battery age")
        graph.assets.upsert(assetRow("tub", name = "Hot tub"))
        ageSubject("h-warn", "tub", daysAgo = 50, name = "Filter age")
        graph.assets.upsert(assetRow("mow", name = "Mower"))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.anyInService }
        assertEquals(
            listOf("DEGRADED:Generator", "CRITICAL:Battery age", "WARNING:Filter age"),
            state.sections.flatMap { it.drawn() },
        )
        assertEquals(listOf("Mower"), state.assets.map { it.asset.name })
    }

    /**
     * Spec §10.2: overdue-driven health rides its schedule's row. The subject's band is on the
     * schedule row, and no independent row is drawn for it anywhere.
     */
    @Test fun overdueHealthRidesItsScheduleRow() = runTest {
        graph.assets.upsert(assetRow("ups", name = "UPS"))
        seed(scheduleOf("s-check", assetId = "ups", title = "Battery check", leadDays = 0))
        graph.healthSubjects.upsert(
            subjectRow(
                "h-check", "ups", name = "Battery", driver = HealthDriver.MAINTENANCE_OVERDUE, scheduleId = "s-check",
                nominalUntilDays = 0, warningFromDays = 10, criticalFromDays = 50,
            ),
        )

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.anyInService }
        assertEquals(listOf("schedule:Battery check"), state.sections.flatMap { it.drawn() })
        val health = state.sections.single().items.single().health
        assertNotNull("the band rides the row", health)
        assertEquals(HealthBand.CRITICAL, health!!.band)
        assertEquals("Battery", health.subjectName)
    }

    /**
     * In-service rows only, each unit on its own lifecycle: a retired or archived unit draws no
     * condition or health row, and a DEGRADED component of a retired parent is listed, naming it.
     */
    @Test fun retiredAndArchivedUnitsDoNotAppear() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator", retiredOn = "2026-03-01"))
        graph.conditions.insert(conditionRow("c1", "gen", OperationalCondition.DOWN, "2026-04-01"))
        graph.assets.upsert(assetRow("snow", name = "Snowblower", status = AssetStatus.ARCHIVED))
        graph.conditions.insert(conditionRow("c2", "snow", OperationalCondition.DEGRADED, "2026-04-01"))
        graph.assets.upsert(assetRow("ups", name = "UPS", retiredOn = "2026-03-01"))
        ageSubject("h-crit", "ups", daysAgo = 90, name = "Battery age")
        graph.assets.upsert(assetRow("tub", name = "Hot tub", retiredOn = "2026-03-01"))
        graph.assets.upsert(assetRow("pack", name = "Battery pack", parent = "tub"))
        graph.conditions.insert(conditionRow("c3", "pack", OperationalCondition.DEGRADED, "2026-04-05"))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val state = vm.state.first { it.anyInService }
        assertEquals(listOf("DEGRADED:Battery pack"), state.sections.flatMap { it.drawn() })
        assertEquals("Hot tub", state.sections.single().assetRows.single().parentName)
        assertEquals(emptyList<String>(), state.assets.map { it.asset.name })
    }

    /**
     * Filters narrow and never re-section: the plain-list exclusion is decided before them, so an
     * asset whose schedule row a filter hides is hidden, not moved into the plain list — even under
     * a status and a chip its condition matches, which lets asset rows through (M14).
     */
    @Test fun aFilterNeverMovesAnAssetIntoThePlainList() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.conditions.insert(conditionRow("c1", "gen", OperationalCondition.OPERATIONAL, "2026-04-01"))
        seed(scheduleOf("s-ok", assetId = "gen", title = "Air filter", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = "2026-12-01"))
        graph.assets.upsert(assetRow("mow", name = "Mower"))
        graph.conditions.insert(conditionRow("c2", "mow", OperationalCondition.OPERATIONAL, "2026-04-01"))

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        val all = vm.state.first { it.anyInService }
        assertEquals(listOf("schedule:Air filter"), all.sections.flatMap { it.drawn() })
        assertEquals(listOf("Mower"), all.assets.map { it.asset.name })

        vm.onStatusChange(DueStatus.OVERDUE)
        vm.onConditionToggle(ConditionChip.OPERATIONAL)
        val narrowed = vm.state.first { it.filters.status == DueStatus.OVERDUE && it.filters.conditions.isNotEmpty() }
        assertEquals(emptyList<AttentionSection>(), narrowed.sections.map { it.section })
        assertEquals("the generator is hidden, not re-listed", listOf("Mower"), narrowed.assets.map { it.asset.name })
    }

    /**
     * Inv. 119: a DOWN asset's health is never drawn without its condition. The DOWN row leads
     * ATTENTION and the CRITICAL subject follows in its own group; a chip or a category that keeps
     * one keeps the other, because both read the same asset's condition and category.
     */
    @Test fun aDownAssetsHealthIsNeverDrawnWithoutItsCondition() = runTest {
        graph.assets.upsert(assetRow("ups", name = "UPS"))
        graph.conditions.insert(conditionRow("c1", "ups", OperationalCondition.DOWN, "2026-04-10"))
        ageSubject("h-crit", "ups", daysAgo = 90, name = "Battery age")

        val vm = viewModel()
        backgroundScope.launch { vm.state.collect() }

        assertEquals(listOf("DOWN:UPS", "CRITICAL:Battery age"), vm.state.first { it.anyInService }.sections.flatMap { it.drawn() })

        vm.onConditionToggle(ConditionChip.DOWN)
        val down = vm.state.first { it.filters.conditions.isNotEmpty() }
        assertEquals(listOf("DOWN:UPS", "CRITICAL:Battery age"), down.sections.flatMap { it.drawn() })
    }
}
