package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.MainActivity
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The ratified sentence this suite provokes on every test: `DIGEST_ALARM_MISSING`'s (master plan §17.1a). */
private const val ALARM_FINDING =
    "The daily reminder check is not scheduled, so today's maintenance may go unannounced."

/** 1.4.1's ratified P141-1b and P141-2: one providerless schedule, and the button that fixes it. */
private const val DELIVERY_FINDING = "1 schedule has reminders turned on, but reminder delivery isn't configured."
private const val FIX_DELIVERY = "Fix reminder delivery"

/**
 * #27's Health section, on a device — the four things no JVM test can show.
 *
 * That it is **reachable**: since 1.7.1 (#103) through the Dashboard's Settings door, the real
 * Settings screen's Utilities row and the real Navigation 3 back stack — the Maintenance tab no
 * longer has a Reminders row — where until B10 `Route.ReminderHealth` popped straight back off a
 * placeholder.
 * That it **lists** a real finding read from the real platform. And that tapping an **`Automatic`**
 * repair really re-arms the real `AlarmManager` alarm, so the finding is gone on the next run —
 * which is the one claim whose mechanism is entirely `PendingIntent.FLAG_NO_CREATE` and cannot be
 * faked off-device.
 *
 * The finding is **provoked**, not waited for: cancelling the alarm before the screen opens is what
 * makes this deterministic rather than a bet on whether a reconcile has run yet. Other findings may
 * legitimately be present on an emulator — a denied `POST_NOTIFICATIONS` is one — so every
 * assertion here names its own sentence rather than counting rows.
 *
 * Emulator only, pinned: the suite touches the process's real alarm.
 */
@RunWith(AndroidJUnit4::class)
class ReminderHealthScreenTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    /**
     * One method, not two: JUnit 4 does not order sibling `@Before`s by source position, and
     * `clearInstall()` wiping the preference store `cancelTheAlarm()` used to write into (fix
     * round 1, Q2) would have been silently order-dependent as separate methods. Fresh install
     * first, then the alarm-gone condition this class actually tests, in the one order that
     * matters: reminders left switched on, the alarm cancelled.
     */
    @Before fun freshInstallWithTheAlarmCancelled() {
        clearInstall()
        app.graph.prefs.remindersEnabled = true
        app.graph.digestAlarm.cancel()
    }

    /**
     * Put the real alarm back (fix round 1, nit 7). This class is the only one that cancels it, and
     * a later class on the same emulator inheriting a cancelled alarm would be a fixture nobody
     * declared — the second test leaves it armed by its own repair, but the first does not.
     */
    @After fun rearmTheAlarm() {
        if (!app.graph.digestAlarm.armed()) app.graph.digestAlarm.arm()
    }

    private fun openHealth() {
        // #103: Settings › Utilities › Reminder health. The row sits below the fold on a settings
        // screen, and a node that is in the tree but off screen takes a click that goes nowhere,
        // which reads exactly like a route that failed to open: scroll to it, the way a person would.
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.awaitText("Utilities")
        rule.onNode(hasText(REMINDER_HEALTH_TITLE) and hasClickAction()).performScrollTo().performClick()
        rule.awaitText(ALARM_FINDING)
    }

    /**
     * Reachable, and listing.
     *
     * The ratified word is both the Utilities row that opens the screen and the screen's own title
     * (P171-2, P171-3), so the word alone proves nothing — the assertion is that it is now a
     * **title** and no longer a clickable row, which is only true once the destination is really up.
     * Until B10 `Route.ReminderHealth` popped itself off the stack a frame after being pushed, and
     * the clickable row would still have been there to find.
     */
    @Test fun theHealthPageIsReachableFromSettingsAndListsItsFindings() {
        openHealth()

        rule.onNode(hasText(REMINDER_HEALTH_TITLE) and hasNoClickAction()).assertIsDisplayed()
        rule.onAllNodesWithText(REMINDER_HEALTH_TITLE).assertCountEquals(1)
        rule.onNodeWithText(ALARM_FINDING).assertIsDisplayed()
        // The icon beside the wording: D12 §5's acceptance is that the hierarchy survives grayscale,
        // so the glyph is asserted to exist rather than the tint to be a particular colour.
        rule.onNodeWithTag(healthIconTag("DIGEST_ALARM_MISSING")).assertIsDisplayed()
        rule.onNodeWithText("Reschedule the check").assertIsDisplayed()
    }

    /**
     * The automatic repair, end to end on the platform: the real alarm is re-armed and the finding
     * is gone on the next run. Nothing else on the screen is touched, and nothing is repaired that
     * the owner has to decide.
     */
    @Test fun tappingAnAutomaticRepairClearsItsFindingOnTheNextRun() {
        openHealth()

        rule.onNodeWithText("Reschedule the check").performClick()

        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodesWithText(ALARM_FINDING).fetchSemanticsNodes().isEmpty()
        }
        rule.onAllNodesWithText("Reschedule the check").assertCountEquals(0)
    }

    /**
     * #80's one tap, over the real store: a Room-backed ACTIVE schedule with reminders on and no
     * delivery set up draws P141-1b and P141-2; one tap runs the canonical repair, the sweep and
     * the refresh, the row is gone, and the stored schedule now carries the one enabled row the
     * editor would have written.
     */
    @Test fun tappingFixReminderDeliveryClearsTheFinding() {
        val graph = app.graph
        val today = LocalDate.now()
        val stamp = System.currentTimeMillis()
        runBlocking {
            val asset = graph.createAsset.run(AssetCommand(name = "Pump A"))
            graph.schedules.upsert(
                MaintenanceSchedule(
                    id = ScheduleId(SCHEDULE_ID),
                    target = ScheduleTarget.AssetTarget(asset.id),
                    title = "Service check",
                    description = "",
                    timeInterval = 3,
                    timeUnit = RecurrenceUnit.MONTH,
                    timeBasis = TimeBasis.FIXED,
                    anchorOn = today.toString(),
                    leadDays = 0,
                    meterDefinitionId = null,
                    meterInterval = null,
                    anchorMeter = null,
                    meterLead = null,
                    servicePolicy = ServicePolicy.CONTINUOUS,
                    policyOffsetDays = null,
                    completionMode = CompletionMode.QUICK,
                    profileId = null,
                    remindersEnabled = true,
                    status = ScheduleStatus.ACTIVE,
                    postponedDueOn = null,
                    createdAt = stamp,
                    updatedAt = stamp,
                    ruleChangedAt = stamp,
                    providers = emptyList(),
                ),
            )
        }
        openHealth()
        rule.awaitText(DELIVERY_FINDING)
        rule.onNodeWithText(DELIVERY_FINDING).assertIsDisplayed()

        rule.onNodeWithText(FIX_DELIVERY).performScrollTo().performClick()

        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodesWithText(DELIVERY_FINDING).fetchSemanticsNodes().isEmpty()
        }
        rule.onAllNodesWithText(FIX_DELIVERY).assertCountEquals(0)
        assertEquals(
            listOf(ScheduleProviderRow("LOCAL", enabled = true)),
            runBlocking { graph.schedules.get(ScheduleId(SCHEDULE_ID)) }?.providers,
        )
    }
}

/** The seeded schedule's id; the store is wiped before every test, so it cannot collide. */
private const val SCHEDULE_ID = "p141-providerless"

/** The same budget the shell's own waits use. */
private const val TIMEOUT_MS = 5_000L
