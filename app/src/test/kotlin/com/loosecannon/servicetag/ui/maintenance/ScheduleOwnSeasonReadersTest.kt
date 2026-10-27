package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.health.NotTrackedReason
import com.loosecannon.servicetag.core.health.SubjectHealth
import com.loosecannon.servicetag.core.health.SubjectValue
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonActivation
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.reminders.NotificationPermission
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.testing.subjectRow
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #125 (H1b, U-C2) — the readers follow a schedule's own season: the health read model's links, the due read model's
 * `seasonMode` and `dormantUntil`, and the schedule editor's options. The owner's story, fictional: the "Example
 * Heater" is in use 11-01…03-31 (`CALENDAR`); its opening service (the Season Start role, `IN_SERVICE_AT_START`, 0, its
 * yearly due long reached) follows the winterize switch, which comes on 10-20; it is done 10-22; the weekly check
 * (`IN_SERVICE_AFTER_OPENING_SERVICE`, 7) keeps the asset's season.
 *
 * Over the real tables (H2): the opening service is stored `OWN` in `maintenance_schedule.season_source`, its switch's
 * rows in `schedule_season_activation`, and the readers are `FakeGraph`'s own, wired as `AppGraph` wires them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleOwnSeasonReadersTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------- the graph's own readers, over the real tables

    private val schedules get() = graph.schedules
    private val rows get() = graph.scheduleSeasonActivations
    private val recompute get() = graph.recomputeSchedules
    private val health get() = graph.assetHealthReadModel
    private val due get() = graph.dueReadModel

    // ---------------------------------------------------------------- the story

    private suspend fun heater() {
        graph.assets.upsert(
            assetRow(
                HEATER.value, name = "Example Heater", seasonMode = SeasonMode.CALENDAR, seasonStart = "11-01",
                seasonEnd = "03-31",
            ),
        )
        graph.schedules.upsert(
            scheduleOf(
                OPEN.value, assetId = HEATER.value, title = "Example opening service", timeInterval = 1,
                timeUnit = RecurrenceUnit.YEAR, anchorOn = "2026-01-15", servicePolicy = ServicePolicy.IN_SERVICE_AT_START,
                policyOffsetDays = 0,
            ).copy(role = ScheduleRole.SEASON_START, roleSinceOn = "2026-04-01", seasonSource = ScheduleSeasonSource.OWN),
        )
        graph.schedules.upsert(
            scheduleOf(
                WEEKLY.value, assetId = HEATER.value, title = "Example weekly check", timeInterval = 1,
                timeUnit = RecurrenceUnit.WEEK, anchorOn = "2026-01-05",
                servicePolicy = ServicePolicy.IN_SERVICE_AFTER_OPENING_SERVICE, policyOffsetDays = 7,
            ),
        )
        for ((index, schedule) in listOf(OPEN, WEEKLY).withIndex()) {
            graph.healthSubjects.upsert(
                subjectRow(
                    "h-${schedule.value}", HEATER.value, driver = HealthDriver.MAINTENANCE_OVERDUE,
                    kind = HealthSubjectKind.MEDIUM, scheduleId = schedule.value, nominalUntilDays = 2,
                    warningFromDays = 7, criticalFromDays = 14, sortOrder = index,
                ),
            )
        }
    }

    /**
     * `T` and the clock move together, to mid-morning of [date], and the day's states are rebuilt as the daily job
     * would, through the readers' own recompute.
     */
    private suspend fun on(date: String) {
        graph.today = LocalDate.parse(date)
        graph.now = dayMillis(date) + 36_000_000L
        recompute.forAsset(HEATER)
    }

    /** The switch's answer, as the applier writes it: one row dated the day it is applied. */
    private suspend fun switchOn(date: String) {
        rows.insert(ScheduleSeasonActivation("start-$date", OPEN, SeasonAction.START, date, dayMillis(date)))
        recompute.forAsset(HEATER)
    }

    private suspend fun row(id: ScheduleId): DueItem = due.forAsset(HEATER).single { it.scheduleId == id }

    private suspend fun subject(id: ScheduleId): SubjectHealth =
        health.forAsset(HEATER).result.subjects.single { it.subject.scheduleId == id }

    @Test fun beforeTheSwitchTheOpeningServicesRowWaitsForItsOwnStartWhichIsNeverPredicted() = runTest {
        heater()
        on("2026-10-15")

        val open = row(OPEN)
        assertEquals(PolicyPhase.DORMANT to PolicyReason.AWAITING_START, open.policyPhase to open.policyReason)
        assertEquals("its own season is MANUAL", SeasonMode.MANUAL, open.seasonMode)
        assertNull("a Home Assistant start is never predicted", open.dormantUntil)
        assertEquals(WHY_UNTIL_YOU_START, whyLine(open) { it.toString() })
        val weekly = row(WEEKLY)
        assertEquals(SeasonMode.CALENDAR to LocalDate.parse("2026-11-01"), weekly.seasonMode to weekly.dormantUntil)
    }

    @Test fun withTheSwitchOnTheOpeningServicesRowAndHealthReadItsOwnSeason() = runTest {
        heater()
        on("2026-10-20")
        switchOn("2026-10-20")
        on("2026-10-21")

        val open = row(OPEN)
        assertEquals(
            Triple(PolicyPhase.ACTIVE, LocalDate.parse("2026-10-20"), DueStatus.OVERDUE),
            Triple(open.policyPhase, open.actionableDueOn, open.status),
        )
        assertEquals(SeasonMode.MANUAL, open.seasonMode)
        assertNull(open.dormantUntil)
        assertEquals("late one day against its own start", 1L, (subject(OPEN).value as SubjectValue.Scored).trackedDays)
        assertEquals(
            "the weekly check keeps the asset's window",
            SubjectValue.NotTracked(NotTrackedReason.OUT_OF_SEASON),
            subject(WEEKLY).value,
        )
    }

    @Test fun throughStandbyTheWeeklyCheckWaitsAndFromTheInUseSeasonItIsDueOnTheEighth() = runTest {
        heater()
        on("2026-10-20")
        switchOn("2026-10-20")
        on("2026-10-22")
        graph.completeSchedule.run(OPEN, CompletionCommand(occurredOn = "2026-10-22", tzId = "UTC"))

        // Standby: the weekly check is dormant, held at 11-01 + 7, and its asset's health counts it nowhere.
        on("2026-10-25")
        val standby = row(WEEKLY)
        assertEquals(
            Triple(PolicyPhase.DORMANT, DueStatus.INACTIVE_SEASON, LocalDate.parse("2026-11-08")),
            Triple(standby.policyPhase, standby.status, standby.actionableDueOn),
        )
        assertEquals(SeasonMode.CALENDAR to LocalDate.parse("2026-11-01"), standby.seasonMode to standby.dormantUntil)
        assertEquals("the owner's standby line", "Out of season until 2026-11-01", whyLine(standby) { it.toString() })
        assertEquals(SubjectValue.NotTracked(NotTrackedReason.OUT_OF_SEASON), subject(WEEKLY).value)
        val view = health.forAsset(HEATER)
        assertEquals("the done opening service is all the aggregate reads", 100, view.result.aggregate?.score)
        val open = row(OPEN)
        assertEquals(PolicyPhase.ACTIVE to SeasonMode.MANUAL, open.policyPhase to open.seasonMode)
        assertEquals(0L, (subject(OPEN).value as SubjectValue.Scored).trackedDays)

        // The in-use season: the first weekly check is the later of 11-01 and 10-22, plus 7.
        on("2026-11-01")
        val weekly = row(WEEKLY)
        assertEquals(
            Triple(PolicyPhase.ACTIVE, LocalDate.parse("2026-11-08"), PolicyReason.SEASON_START),
            Triple(weekly.policyPhase, weekly.actionableDueOn, weekly.policyReason),
        )
        assertNull(weekly.dormantUntil)
        on("2026-11-09")
        assertEquals(DueStatus.OVERDUE, row(WEEKLY).status)
        assertEquals(1L, (subject(WEEKLY).value as SubjectValue.Scored).trackedDays)
    }

    // ---------------------------------------------------------------- the editor (M4)

    @Test fun theEditorOffersAnOwnSeasonSchedulesOptionsNeverItsAssetsWindow() = runTest {
        heater()
        val permission = object : NotificationPermission {
            override fun granted(): Boolean = true
            override fun shouldExplain(): Boolean = false
            override suspend fun request(): Boolean = true
        }
        fun editor(id: ScheduleId) = ScheduleEditViewModel(
            schedules, graph.assets, graph.groups, graph.definitions, graph.profiles, graph.saveSchedule, permission,
            graph.todayPort, id, targetAssetId = null, targetGroupId = null, recompute = recompute,
        )

        val opened = editor(OPEN).state.first { it.loaded }
        assertEquals("its own season: MANUAL, no window, the asset's (absent) break", SeasonMode.MANUAL, opened.season?.mode)
        assertEquals(listOf(PolicyOption.WHEN_SEASON_STARTS, PolicyOption.WHENEVER_DUE), opened.policyOptions)
        assertEquals(PolicyOption.WHEN_SEASON_STARTS, opened.policyOption)

        val weekly = editor(WEEKLY).state.first { it.loaded }
        assertEquals("the asset's schedule keeps the window", SeasonMode.CALENDAR, weekly.season?.mode)
        assertEquals(
            listOf(PolicyOption.BEFORE_SEASON, PolicyOption.WHEN_SEASON_STARTS, PolicyOption.WHENEVER_DUE),
            weekly.policyOptions,
        )
    }

    /**
     * H1b review m1: the editor's `PreServiceNeedsDates` refresh reads the schedule's own season too. A check on its own
     * season is offered "Before the break" while its asset has a break (its own season's only boundary); the break is
     * removed while the form is open, so the save is refused 409, and the refreshed form is the own season's — MANUAL,
     * no boundary, the answer cleared — never the asset's CALENDAR window.
     */
    @Test fun theEditorsRefreshAfterARefusedSaveReadsTheOwnSeasonToo() = runTest {
        heater()
        graph.assets.upsert(graph.assets.get(HEATER)!!.copy(blackoutStartMmdd = "06-01", blackoutEndMmdd = "08-31"))
        graph.schedules.upsert(
            scheduleOf(
                CHECK.value, assetId = HEATER.value, title = "Example burner check", timeInterval = 3,
                timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-01-15",
                servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0,
            ).copy(seasonSource = ScheduleSeasonSource.OWN),
        )
        val permission = object : NotificationPermission {
            override fun granted(): Boolean = true
            override fun shouldExplain(): Boolean = false
            override suspend fun request(): Boolean = true
        }
        val vm = ScheduleEditViewModel(
            schedules, graph.assets, graph.groups, graph.definitions, graph.profiles, graph.saveSchedule, permission,
            graph.todayPort, CHECK, targetAssetId = null, targetGroupId = null, recompute = recompute,
        )
        val opened = vm.state.first { it.loaded }
        assertEquals(SeasonMode.MANUAL, opened.season?.mode)
        assertTrue("the break is its own season's boundary", PolicyOption.BEFORE_BREAK in opened.policyOptions)
        vm.onPolicy(PolicyOption.BEFORE_BREAK)
        vm.onDaysBefore("14")
        graph.assets.upsert(graph.assets.get(HEATER)!!.copy(blackoutStartMmdd = null, blackoutEndMmdd = null))

        vm.save()
        val refused = vm.state.first { !it.saving }

        assertEquals("the own season, never the asset's window", SeasonMode.MANUAL, refused.season?.mode)
        assertTrue(refused.noBoundary)
        assertNull(refused.policyOption)
        assertEquals(ServicePolicy.IN_SERVICE_AT_START, graph.schedules.get(CHECK)!!.servicePolicy)
    }

    private companion object {
        val CHECK = ScheduleId("s-check")
        val HEATER = AssetId("heater")
        val OPEN = ScheduleId("s-open")
        val WEEKLY = ScheduleId("s-weekly")
    }
}
