package com.loosecannon.servicetag.ui.asset

import android.Manifest
import android.service.notification.StatusBarNotification
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.reminders.DeadlineKind
import com.loosecannon.servicetag.core.reminders.SubjectKey
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.reminders.AndroidReminderNotifications
import com.loosecannon.servicetag.reminders.NotificationPermission
import com.loosecannon.servicetag.reminders.WARRANTY_NOTIFICATION_RATIONALE
import com.loosecannon.servicetag.reminders.keyOfTag
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.maintenance.ENTER_THE_NUMBER_OF_DAYS
import com.loosecannon.servicetag.ui.maintenance.NOT_NOW
import com.loosecannon.servicetag.ui.maintenance.REMIND_ME_N_DAYS_EARLY
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val WAIT_MS = 10_000L

/**
 * #79 (C11, R79-12, R79-16; §3 row 28): the asset editor's warranty reminder lead on a real Compose
 * tree. The field sits under "Expires on" with its ratified label and P79-8 under it; a zero is
 * refused with the shipped "Enter the number of days." and a lead with no date with P79-9, writing
 * nothing. After a save that first sets a lead while notifications are not granted, P79-12 asks with
 * "OK" and "Not now"; "Not now" requests nothing, writes nothing more and finishes the editor.
 *
 * The rationale case never taps "OK": that hands over to the system dialog, which is not this suite's
 * to drive. The editor it draws is planted in the activity's store under the screen's own key with a
 * fake permission that is never granted, so the rationale is up whatever the installer granted.
 *
 * The production wiring has a case of its own: with the permission granted, a lead saved through
 * `AssetEditScreen(graph = app.graph)` — the editor's secondary constructor, which hands it the
 * graph's permission and sweep — posts the warning and stamps it once, so the hop editor →
 * `reminderReconcile` → `ReminderRuns` → provider → shade is proved end to end.
 *
 * Emulator only (`emulator-5554`) — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class AssetEditorWarrantyReminderTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val notificationPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Before fun freshInstall() = clearInstall()

    private fun editor(assetId: String?, onDone: (String) -> Unit = {}) {
        rule.setContent {
            ServiceTagTheme {
                AssetEditScreen(graph = app.graph, assetId = assetId, onDone = onDone, onBack = {})
            }
        }
    }

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label))

    private fun saveAsset() = rule.onNodeWithText("Save asset").performScrollTo()

    private fun top(label: String) = field(label).getUnclippedBoundsInRoot().top

    private fun storedAssets() = runBlocking { app.graph.assets.all() }

    /** The shade's one per-item post for [key], read back by its tag, as the platform proof reads it. */
    private fun warningFor(key: SubjectKey.Deadline): StatusBarNotification? =
        NotificationManagerCompat.from(app).activeNotifications.firstOrNull {
            it.id == AndroidReminderNotifications.ITEM_ID && it.tag?.let(::keyOfTag) == key
        }

    @Test fun theLeadFieldHelperAndRefusal() {
        editor(null)
        rule.awaitText("WARRANTY")
        field("Name").performTextReplacement("Example Heater")

        field(REMIND_ME_N_DAYS_EARLY).performScrollTo().assertIsDisplayed()
        rule.onNode(hasText(LEAVE_BLANK_FOR_NO_REMINDER)).assertIsDisplayed()
        check(top("Expires on") < top(REMIND_ME_N_DAYS_EARLY)) { "the lead is under Expires on" }
        check(top(REMIND_ME_N_DAYS_EARLY) < top("Warranty notes")) { "and above Warranty notes" }

        field(REMIND_ME_N_DAYS_EARLY).performTextReplacement("0")
        saveAsset().performClick()
        rule.awaitText(ENTER_THE_NUMBER_OF_DAYS)
        rule.onAllNodesWithText(LEAVE_BLANK_FOR_NO_REMINDER).assertCountEquals(0)
        assertTrue("a refused lead writes nothing", storedAssets().isEmpty())

        field(REMIND_ME_N_DAYS_EARLY).performScrollTo().performTextReplacement("30")
        saveAsset().performClick()
        rule.awaitText(ADD_THE_WARRANTY_DATE_FIRST)
        assertTrue("a lead with no date writes nothing", storedAssets().isEmpty())
    }

    @Test fun theRationaleTextAndNotNowWritesNothingMore() {
        val expiry = app.graph.today.localDate().plusDays(400).toString()
        val id = runBlocking {
            app.graph.createAsset.run(AssetCommand(name = "Example Heater", warrantyExpiresOn = expiry)).id.value
        }
        val requests = mutableListOf<String>()
        var sweeps = 0
        val neverGranted = object : NotificationPermission {
            override fun granted(): Boolean = false
            override fun shouldExplain(): Boolean = false
            override suspend fun request(): Boolean = false.also { requests += "request" }
        }
        rule.runOnUiThread {
            val g = app.graph
            val planted = AssetEditViewModel(
                g.assets, g.healthSubjects, g.saveAssetSettings, g.schedules, g.categories,
                g.attachments, g.attachmentStorage, g.addAttachment, g.today, AssetId(id),
                notifications = neverGranted,
                reconcile = { sweeps++ },
            )
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = planted as T
            }
            ViewModelProvider(rule.activity, factory)[id, AssetEditViewModel::class.java]
        }
        var done: String? = null
        editor(id) { done = it }
        rule.awaitText("WARRANTY")

        field(REMIND_ME_N_DAYS_EARLY).performScrollTo().performTextReplacement("30")
        saveAsset().performClick()
        rule.awaitText(WARRANTY_NOTIFICATION_RATIONALE)
        rule.onNodeWithText(WARRANTY_NOTIFICATION_RATIONALE).assertIsDisplayed()
        rule.onNodeWithText("OK").assertIsDisplayed()
        rule.onNodeWithText(NOT_NOW).assertIsDisplayed()
        val atTheQuestion = storedAssets().single()
        assertEquals("the lead is written before the question", 30, atTheQuestion.warrantyReminderLeadDays)
        assertEquals("the save swept once", 1, sweeps)

        rule.onNodeWithText(NOT_NOW).performClick()
        rule.waitUntil(WAIT_MS) { done != null }
        assertEquals(id, done)
        assertTrue("\"Not now\" requests nothing", requests.isEmpty())
        assertEquals("and writes nothing more", listOf(atTheQuestion), storedAssets())
        rule.onAllNodesWithText(WARRANTY_NOTIFICATION_RATIONALE).assertCountEquals(0)
    }

    /**
     * m1 of the #79a branch review: the editor's graph wiring. A dated, in-service asset 10 days from
     * expiry takes a lead of 30 in the real editor — no planted view model — so the save sweeps through
     * `graph.reminderReconcile`, the permission (granted by the rule) asks nothing, and the warning is in
     * the shade under its `WARRANTY_EXPIRY:<id>|…` tag with exactly one device-local stamp behind it.
     */
    @Test fun aLeadSavedInTheRealEditorSweepsThroughTheGraphAndPostsTheWarning() {
        NotificationManagerCompat.from(app).cancelAll()
        val expiry = app.graph.today.localDate().plusDays(10).toString()
        val id = runBlocking {
            app.graph.createAsset.run(AssetCommand(name = "Example Heater", warrantyExpiresOn = expiry)).id.value
        }
        val key = SubjectKey.Deadline(DeadlineKind.WARRANTY_EXPIRY, id)
        var done: String? = null
        editor(id) { done = it }
        rule.awaitText("WARRANTY")

        field(REMIND_ME_N_DAYS_EARLY).performScrollTo().performTextReplacement("30")
        saveAsset().performClick()
        rule.waitUntil(WAIT_MS) { done != null }
        assertEquals(30, storedAssets().single().warrantyReminderLeadDays)
        rule.onAllNodesWithText(WARRANTY_NOTIFICATION_RATIONALE).assertCountEquals(0)

        val posted = runCatching { rule.waitUntil(WAIT_MS) { warningFor(key) != null } }.isSuccess
        assertTrue("the editor's save swept through the graph and posted the warning", posted)
        assertEquals(key, keyOfTag(warningFor(key)!!.tag))
        assertEquals(
            "and stamped it, once, in the device-local table",
            listOf(id),
            runBlocking { app.graph.deadlineLocalDelivery.all() }.map { it.subjectId },
        )
        NotificationManagerCompat.from(app).cancelAll()
    }
}
