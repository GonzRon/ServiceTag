package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.MainActivity
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The one ratified sentence this suite drives: `DIGEST_ALARM_MISSING`'s (master plan §17.1a). */
private const val ALARM_FINDING =
    "The daily reminder check is not scheduled, so today's maintenance may go unannounced."

/**
 * #27's Health section, on a device — the three things no JVM test can show.
 *
 * That it is **reachable**: through the real bottom bar, the real Maintenance destination and the
 * real Navigation 3 back stack, which until this brief popped straight back off a placeholder.
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
        rule.onNode(hasText("Maintenance") and hasClickAction()).performClick()
        rule.awaitText(REMINDERS_SECTION)
        // The row is the **last** thing in a scrolling destination, so on a store carrying due work
        // it sits below the fold — and a node that is in the tree but off screen takes a click that
        // goes nowhere, which reads exactly like a route that failed to open. A fresh install still
        // has no due work of its own, but the layout fact holds regardless of what the store
        // contains: scroll to it, the way a person would, rather than assume it is on screen.
        rule.onNode(hasText(REMINDERS_SECTION) and hasClickAction()).performScrollTo().performClick()
        rule.awaitText(ALARM_FINDING)
    }

    /**
     * Reachable, and listing.
     *
     * The ratified section label is both the row that opens the screen and the screen's own title,
     * so "Reminders" alone proves nothing — the assertion is that it is now a **title** and no
     * longer a clickable row, which is only true once the destination is really up. Until this
     * brief `Route.ReminderHealth` popped itself off the stack a frame after being pushed, and the
     * shell's clickable row would still have been there to find.
     */
    @Test fun theHealthSectionIsReachableFromMaintenanceAndListsItsFindings() {
        openHealth()

        rule.onNode(hasText(REMINDERS_SECTION) and hasNoClickAction()).assertIsDisplayed()
        rule.onAllNodesWithText(REMINDERS_SECTION).assertCountEquals(1)
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
}

/** The same budget the shell's own waits use. */
private const val TIMEOUT_MS = 5_000L
