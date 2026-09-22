package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.reminders.Severity
import com.loosecannon.servicetag.prefs.AppPrefs
import com.loosecannon.servicetag.prefs.KeyValueStore
import com.loosecannon.servicetag.reminders.AppRestriction
import com.loosecannon.servicetag.reminders.DeliveryFactsSource
import com.loosecannon.servicetag.reminders.FakeDeliveryRepository
import com.loosecannon.servicetag.reminders.FakeReminderNotifications
import com.loosecannon.servicetag.reminders.FakeScheduleRepository
import com.loosecannon.servicetag.reminders.GrantablePermission
import com.loosecannon.servicetag.reminders.LocalReminderProvider
import com.loosecannon.servicetag.reminders.MutablePlatformState
import com.loosecannon.servicetag.reminders.NonceStore
import com.loosecannon.servicetag.reminders.QuickActionShapeSource
import com.loosecannon.servicetag.reminders.QuickActions
import com.loosecannon.servicetag.reminders.RecordingBackstop
import com.loosecannon.servicetag.reminders.RecordingDigestAlarm
import com.loosecannon.servicetag.reminders.ReminderHealthCheck
import com.loosecannon.servicetag.reminders.ReminderRepair
import com.loosecannon.servicetag.reminders.ScheduleStateReader
import com.loosecannon.servicetag.testing.scheduleOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
class HealthViewModelTest {

    private val platform = MutablePlatformState()
    private val permission = GrantablePermission()
    private val alarm = RecordingDigestAlarm(isArmed = true)
    private val backstop = RecordingBackstop(isEnqueued = true)
    private val prefs = AppPrefs(HealthPrefsStore())
    private val schedules = FakeScheduleRepository()
    private val clock = Clock { 0L }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
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
            states = ScheduleStateReader { null },
            io = Dispatchers.Unconfined,
        ),
    )

    private fun viewModel(health: ReminderHealth = health()) = HealthViewModel(health, prefs)

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
        assertEquals(Severity.INFO, health.worstSeverity())
        assertFalse("INFO is deliberately below the line", health.worstSeverity().showsBadge())

        prefs.remindersEnabled = true
        backstop.drop()
        health.refresh()
        assertEquals(Severity.WARN, health.worstSeverity())
        assertTrue(health.worstSeverity().showsBadge())

        platform.enabled = false
        health.refresh()
        assertEquals(Severity.ERROR, health.worstSeverity())
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
        assertEquals(Severity.ERROR, health.worstSeverity())
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

        val rows = viewModel().state.first { it.loaded }.rows
        assertEquals(
            listOf(Severity.ERROR, Severity.WARN, Severity.INFO),
            rows.map { it.severity },
        )
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
        assertEquals(listOf("NOTIFICATIONS_BLOCKED"), model.state.value.rows.map { it.code })
    }

    /**
     * The two in-app repairs carry the schedule they open, which is the whole point of the code
     * convention: a "Open the schedule" button with nothing to open is a button that does nothing.
     */
    @Test
    fun theInAppRepairsCarryTheScheduleTheyOpen() = runTest {
        schedules.upsert(scheduleOf(id = "sched-1", assetId = "a1").copy(providers = emptyList()))
        val rows = viewModel().state.first { it.loaded }.rows

        val row = rows.single { it.code == "SCHEDULE_NO_PROVIDER" }
        assertEquals(HealthAction.OpenSchedule("sched-1"), row.action)
        assertEquals("Open the schedule", row.label)
        assertEquals("1 schedules have reminders switched on but no way to deliver them.", row.message)
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
