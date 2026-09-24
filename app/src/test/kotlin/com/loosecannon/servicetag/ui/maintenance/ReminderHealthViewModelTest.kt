package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.TerminationKind
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.reminders.ReminderHealthSeverity
import com.loosecannon.servicetag.prefs.AppPrefs
import com.loosecannon.servicetag.prefs.KeyValueStore
import com.loosecannon.servicetag.reminders.AppRestriction
import com.loosecannon.servicetag.reminders.DeliveryFactsSource
import com.loosecannon.servicetag.reminders.FakeAssetRepository
import com.loosecannon.servicetag.reminders.FakeDeliveryRepository
import com.loosecannon.servicetag.reminders.FakeGroupRepository
import com.loosecannon.servicetag.reminders.FakeReminderNotifications
import com.loosecannon.servicetag.reminders.FakeScheduleRepository
import com.loosecannon.servicetag.reminders.GrantablePermission
import com.loosecannon.servicetag.reminders.LocalReminderProvider
import com.loosecannon.servicetag.reminders.MutablePlatformState
import com.loosecannon.servicetag.reminders.NonceStore
import com.loosecannon.servicetag.reminders.NotificationPermission
import com.loosecannon.servicetag.reminders.QuickActionShapeSource
import com.loosecannon.servicetag.reminders.QuickActions
import com.loosecannon.servicetag.reminders.RecordingBackstop
import com.loosecannon.servicetag.reminders.RecordingDigestAlarm
import com.loosecannon.servicetag.reminders.ReminderHealthCheck
import com.loosecannon.servicetag.reminders.ReminderRepair
import com.loosecannon.servicetag.reminders.ScheduleStateReader
import com.loosecannon.servicetag.reminders.assetOf
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.scheduleOf
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class HealthPrefsStore : KeyValueStore {
    private val longs = mutableMapOf<String, Long>()
    private val strings = mutableMapOf<String, String>()
    override fun getLong(key: String): Long? = longs[key]
    override fun putLong(key: String, value: Long) { longs[key] = value }
    override fun getString(key: String): String? = strings[key]
    override fun putString(key: String, value: String) { strings[key] = value }
}

/**
 * The Health section's state and the badge behind it.
 *
 * The **findings** are `ReminderHealthCheckTest`'s; what is proved here is the layer above: the
 * badge threshold (#5, D3 §7.3), the seven ratified repair labels (§17.1a), which repairs this view
 * model performs itself and which it refuses to, and that every row carries wording and a position
 * rather than depending on a colour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderHealthViewModelTest {

    private val platform = MutablePlatformState()
    private val permission = GrantablePermission()
    private val alarm = RecordingDigestAlarm(isArmed = true)
    private val backstop = RecordingBackstop(isEnqueued = true)
    private val prefs = AppPrefs(HealthPrefsStore())
    private val schedules = FakeScheduleRepository()
    private val assets = FakeAssetRepository()
    private val groups = FakeGroupRepository()
    private val states = mutableMapOf<String, ScheduleState>()
    private val clock = Clock { 0L }

    /** How many times the one-tap enable drove B06's reconcile. */
    private var deliveryResumed = 0

    /**
     * One scheduler for `Dispatchers.Main`, for `runTest` and for the Room-backed fixture the
     * cold-launch test builds. `runTest` adopts the main dispatcher's scheduler, so a fixture with a
     * scheduler of its own would leave that test's flows waiting on a clock nothing advances.
     */
    private val scheduler = TestCoroutineScheduler()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun health(): ReminderHealth = ReminderHealth(
        ReminderHealthCheck(
            provider = LocalReminderProvider(
                facts = DeliveryFactsSource { null },
                delivery = FakeDeliveryRepository(),
                notifications = FakeReminderNotifications(),
                permission = permission,
                platform = platform,
                alarm = alarm,
                prefs = prefs,
                clock = clock,
                quickActions = QuickActions(
                    shapes = QuickActionShapeSource { null },
                    nonces = NonceStore(FakeDeliveryRepository(), IdGenerator { "nonce" }, clock),
                ),
            ),
            platform = platform,
            backstop = backstop,
            alarm = alarm,
            schedules = schedules,
            states = ScheduleStateReader { states[it.value] },
            assets = assets,
            groups = groups,
            io = Dispatchers.Unconfined,
        ),
    )

    /**
     * Refreshed on the way out, because the screen refreshes on every `ON_START` and the view model
     * deliberately does not run the check from its constructor.
     */
    private fun viewModel(health: ReminderHealth = health()) =
        ReminderHealthViewModel(health, prefs) { deliveryResumed++ }.also { it.refresh() }

    /**
     * The badge threshold, over the real summary: **≥ WARN** shows it, and an INFO-only set does
     * not. `REMINDERS_GLOBALLY_OFF` is the owner's own choice, and a badge for it would be a nag
     * that never clears — which is the whole reason it is INFO.
     */
    @Test
    fun theBadgeAppearsAtWarnAndNotForAnInfoOnlySet() = runTest {
        val health = health()
        assertNull("nothing has been asked yet, so nothing is claimed", health.worstSeverity())
        assertFalse(health.worstSeverity().showsBadge())

        health.refresh()
        assertNull("a healthy phone", health.worstSeverity())
        assertFalse(health.worstSeverity().showsBadge())

        prefs.remindersEnabled = false
        health.refresh()
        assertEquals(ReminderHealthSeverity.INFO, health.worstSeverity())
        assertFalse("INFO is deliberately below the line", health.worstSeverity().showsBadge())

        prefs.remindersEnabled = true
        backstop.drop()
        health.refresh()
        assertEquals(ReminderHealthSeverity.WARN, health.worstSeverity())
        assertTrue(health.worstSeverity().showsBadge())

        platform.enabled = false
        health.refresh()
        assertEquals(ReminderHealthSeverity.ERROR, health.worstSeverity())
        assertTrue(health.worstSeverity().showsBadge())
    }

    /**
     * The summary answers from its cache and does **no** platform work of its own (decision 32):
     * the phone can break underneath it and the badge does not change until something refreshes.
     * An implementation that re-read the standby bucket inside `worstSeverity()` would put that
     * decision out of reach whatever its callers did.
     */
    @Test
    fun theSummaryAnswersFromTheLastRefreshAndNotFromThePhone() = runTest {
        val health = health()
        health.refresh()
        assertNull(health.worstSeverity())

        platform.enabled = false
        assertNull("still the cached answer", health.worstSeverity())

        health.refresh()
        assertEquals(ReminderHealthSeverity.ERROR, health.worstSeverity())
    }

    /** The seven RATIFIED repair labels (§17.1a), verbatim, including the two that carry a target. */
    @Test
    fun theSevenRepairLabelsAreTheRatifiedWords() {
        assertEquals("Open notification settings", repairLabel(ReminderRepair.OPEN_NOTIFICATION_SETTINGS))
        assertEquals("Reschedule the check", repairLabel(ReminderRepair.ARM_DIGEST_ALARM))
        assertEquals("Restart the check", repairLabel(ReminderRepair.ENQUEUE_BACKSTOP))
        assertEquals("Open battery settings", repairLabel(ReminderRepair.OPEN_BATTERY_SETTINGS))
        assertEquals("Turn reminders on", repairLabel(ReminderRepair.TURN_REMINDERS_ON))
        assertEquals("Open the schedule", repairLabel("${ReminderRepair.OPEN_SCHEDULE}:sched-1"))
        assertEquals("Log meter reading", repairLabel("${ReminderRepair.LOG_METER_READING}:sched-1"))
        assertNull("a code this build has no label for gets no button", repairLabel("SOMETHING_ELSE"))
    }

    /**
     * Worst first, and every row carries a **sentence** and a **button label** — the wording half of
     * D12 §5's grayscale acceptance. A row that depended on its tint would pass an assertion on its
     * colour and fail a colour-blind owner.
     */
    @Test
    fun everyRowCarriesWordingAndAPositionRatherThanAColour() = runTest {
        platform.enabled = false
        platform.restriction = AppRestriction.BATTERY_RESTRICTED
        prefs.remindersEnabled = false

        val state = viewModel().state.first { it.loaded }
        assertEquals(
            listOf(ReminderHealthSeverity.ERROR, ReminderHealthSeverity.WARN, ReminderHealthSeverity.INFO),
            state.rows.map { it.severity },
        )
        assertEquals("and the section's own worst, for the badge", ReminderHealthSeverity.ERROR, state.worstSeverity)
        val rows = state.rows
        rows.forEach { row ->
            assertTrue("${row.code} has a sentence", row.message.endsWith("."))
            assertTrue("${row.code} has a label", !row.label.isNullOrBlank())
            assertTrue("${row.code} has something to do", row.action != null)
        }
    }

    /** The automatic repair, through the view model: the alarm comes back and its row goes away. */
    @Test
    fun theAutomaticRepairIsAppliedAndItsFindingClears() = runTest {
        alarm.cancel()
        val model = viewModel()
        val row = model.state.first { it.loaded }.rows.single { it.code == "DIGEST_ALARM_MISSING" }
        assertEquals(HealthAction.Automatic, row.action)

        model.repair(row)

        assertTrue(alarm.armed())
        assertEquals(emptyList<HealthRow>(), model.state.value.rows)
    }

    /** #27's one-tap enable: it writes B06's preference, and the informational row clears. */
    @Test
    fun theOneTapEnableWritesThePreferenceAndClearsTheRow() = runTest {
        prefs.remindersEnabled = false
        val model = viewModel()
        val row = model.state.first { it.loaded }.rows.single { it.code == "REMINDERS_GLOBALLY_OFF" }
        assertEquals(HealthAction.TurnRemindersOn, row.action)
        assertEquals("Turn reminders on", row.label)

        model.repair(row)

        assertTrue(prefs.remindersEnabled)
        assertEquals(
            "and delivery was restored, not left to the next scheduled run",
            1,
            deliveryResumed,
        )
        assertEquals(emptyList<HealthRow>(), model.state.value.rows)
    }

    /**
     * A row the owner has to decide is **not** repaired here: handed the blocked-notifications row,
     * the view model writes nothing and the row stays. Opening a system screen is the screen's to
     * do, and there is nothing for this to have fixed.
     */
    @Test
    fun aRowThatOnlyOpensAScreenIsNotRepairedHere() = runTest {
        platform.enabled = false
        val model = viewModel()
        val row = model.state.first { it.loaded }.rows.single { it.code == "NOTIFICATIONS_BLOCKED" }
        assertEquals(HealthAction.NotificationSettings, row.action)
        assertEquals("Open notification settings", row.label)

        model.repair(row)

        assertFalse("nothing was silently switched on", platform.enabled)
        assertEquals("and nothing was reconciled", 0, deliveryResumed)
        assertEquals(listOf("NOTIFICATIONS_BLOCKED"), model.state.value.rows.map { it.code })
    }

    /**
     * The two in-app repairs carry the schedule they open, which is the whole point of the code
     * convention: a "Open the schedule" button with nothing to open is a button that does nothing.
     */
    @Test
    fun theInAppRepairsCarryTheScheduleTheyOpen() = runTest {
        assets.upsert(assetOf("a1"))
        schedules.upsert(scheduleOf(id = "sched-1", assetId = "a1").copy(providers = emptyList()))
        schedules.upsert(
            scheduleOf(
                id = "sched-2",
                assetId = "a1",
                timeInterval = null,
                timeUnit = null,
                anchorOn = null,
                meterDefinitionId = "d1",
                meterInterval = 100.0,
            ),
        )
        // The meter schedule needs a derived row for the baseline question to have an answer at
        // all — without one the check skips it, which is how `LOG_METER_READING` went untested in
        // the first round (fix round 1, S7).
        states["sched-2"] = ScheduleState(
            scheduleId = ScheduleId("sched-2"),
            lastCompletedOn = null,
            lastCompletionEventId = null,
            lastCompletedMeter = null,
            currentMeter = null,
            computedDueMeter = null,
            lastTerminationEffectiveOn = null,
            lastTerminationKind = TerminationKind.NONE,
            computedDueOn = null,
            effectiveDueOn = null,
            policyPhase = PolicyPhase.ACTIVE,
            actionableDueOn = null,
            policyReason = PolicyReason.NONE,
            quiet = false,
            computedForOn = "2026-09-22",
            computedAt = dayMillis("2026-09-22"),
        )

        val rows = viewModel().state.first { it.loaded }.rows

        val provider = rows.single { it.code == "SCHEDULE_NO_PROVIDER" }
        assertEquals(HealthAction.OpenSchedule("sched-1"), provider.action)
        assertEquals("Open the schedule", provider.label)
        assertEquals(
            "1 schedules have reminders switched on but no way to deliver them.",
            provider.message,
        )

        val meter = rows.single { it.code == "NO_DATA" }
        assertEquals(HealthAction.LogMeterReading("sched-2"), meter.action)
        assertEquals("Log meter reading", meter.label)
        assertEquals("1 schedules need a meter reading before they can come due.", meter.message)
    }

    /**
     * The cold-launch case the badge exists for (fix round 1, S3/R13).
     *
     * `HealthSummary` is a cache — decision 32 forbids the check running per dashboard emission —
     * and both badge surfaces read it inside a store-driven `combine`, so a check that lands
     * **after** the first emission is invisible to them unless something tells them to look again.
     * That is exactly the shape of a launch: `ServiceTagApp` runs the check on `appScope`, which
     * races the shell's first emission, so without the change signal a phone with a ≥ WARN
     * condition could show no badge for the whole session.
     *
     * The first assertion is the failure this test exists to catch: nothing has been checked yet, so
     * the badge is honestly dark. The second is the fix: the refresh alone — no store write, no
     * `refresh()` on the shell — lights it.
     *
     * **How this one fails, so a slow CI is not mistaken for it.** Without the change signal the
     * second `first { … }` never returns, so the RED shape is a **`runTest` timeout**, not an
     * assertion message. That is inherent to proving that a flow re-emits: there is nothing to
     * assert against until the emission arrives. A timeout here means the badge never lit; it does
     * not mean the machine was busy.
     */
    @Test
    fun aCheckThatLandsAfterTheFirstEmissionStillLightsTheBadge() = runTest {
        val graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        try {
            val health = health()
            // A ≥ WARN condition waiting to be noticed.
            backstop.drop()
            val shell = MaintenanceViewModel(
                schedules = graph.schedules,
                states = graph.scheduleStates,
                groups = graph.groups,
                due = DueReadModel(
                    graph.schedules, graph.assets, graph.groups,
                    graph.definitions, graph.recomputeSchedules, graph.todayPort, graph.assetHealthReadModel, { null },
                ),
                health = health,
                notifications = object : NotificationPermission {
                    override fun granted(): Boolean = true
                    override fun shouldExplain(): Boolean = false
                    override suspend fun request(): Boolean = true
                },
            )

            assertFalse(
                "nothing has been checked yet, so the badge is dark",
                shell.state.first { it.loaded }.worstSeverity.showsBadge(),
            )

            health.refresh()

            val lit = shell.state.first { it.worstSeverity.showsBadge() }
            assertEquals(ReminderHealthSeverity.WARN, lit.worstSeverity)
        } finally {
            graph.close()
        }
    }

    /** The restriction is explained and never repaired: a settings screen, and no exemption asked. */
    @Test
    fun theRestrictionIsExplainedAndOnlyEverOpensSettings() = runTest {
        platform.restriction = AppRestriction.STANDBY_RESTRICTED
        val row = viewModel().state.first { it.loaded }.rows.single { it.code == "APP_RESTRICTED" }

        assertEquals(HealthAction.BatterySettings, row.action)
        assertEquals("Open battery settings", row.label)
        assertEquals(
            "This phone is holding ServiceTag back in the background, so reminders may arrive late or not at all.",
            row.message,
        )
    }
}
