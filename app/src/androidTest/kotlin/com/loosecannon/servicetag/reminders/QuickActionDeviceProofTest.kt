package com.loosecannon.servicetag.reminders

import android.Manifest
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.loosecannon.servicetag.MainActivity
import com.loosecannon.servicetag.ServiceTagApp
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.reminders.ProviderId
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.maintenance.snoozeLine
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one fact about a quick action that only a device holds: the **real** `PendingIntent`, fired
 * from the **real** notification shade, reaching this app's own non-exported receiver and coming
 * out the other side as one completion event and an empty shade.
 *
 * Nothing here is hand-built. The notification is posted by the production run
 * (`ReminderRuns.reconcileAll`), the action is read back off the platform's own
 * `StatusBarNotification`, and `send()` is what the owner's tap does — so every link in the chain
 * is the shipped one: `AndroidQuickActionIntents`' `FLAG_IMMUTABLE` broadcast, the manifest
 * declaration, `QuickActionReceiver`, the WorkManager hand-off that keeps the work out of the
 * broadcast budget, the persisted nonce, `CompleteSchedule`, and the reconcile that takes the
 * notification down because the schedule is no longer due.
 *
 * Which actions appear, what each one is aimed at and every way a nonce can be refused are all
 * proved off-device by `QuickActionsTest` and `QuickActionReceiverTest`; this class deliberately
 * proves the one thing they cannot.
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class QuickActionDeviceProofTest {

    @get:Rule
    val notificationPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    /** #87: sees whatever activity a sent `PendingIntent` starts (the Compose root registry is process-wide). */
    @get:Rule
    val rule = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val app get() = context.applicationContext as ServiceTagApp

    @Before
    fun freshInstall() {
        clearInstall()
        // Whatever a previous class left in the shade is not this test's subject.
        NotificationManagerCompat.from(context).cancelAll()
    }

    /**
     * #11 AC 1, end to end: **one** tap on "Done" is one completion event, and the notification is
     * gone afterwards.
     *
     * The schedule is a plain `QUICK` one with a time rule and no meter, because that is the only
     * shape whose "Done" is a broadcast at all (§12.1 and its carve-out). Its anchor is a year and
     * five days back with no lead, so the current occurrence fell five days ago and it arrives
     * OVERDUE — an anchor exactly a year back would put that occurrence on today and read DUE.
     * Completing it puts the next occurrence a year out, which is what makes "the notification is
     * gone" the honest consequence of the completion rather than something this test cancels.
     */
    @Test
    fun aRealDoneActionCompletesTheScheduleOnceAndClearsItsNotification() {
        val graph = app.graph
        val today = LocalDate.now()
        val seeded = runBlocking {
            val filter = graph.createAsset.run(AssetCommand(name = "Pump house filter", category = "Water"))
            filter.id to seedOverdue(graph, "device-quick-done", filter.id, "Filter change", today)
        }
        val (assetId, scheduleId) = seeded

        assertEquals(
            "the seeded schedule is overdue, so the run has something to post",
            DueStatus.OVERDUE,
            runBlocking { graph.dueReadModel.forAsset(assetId).single().status },
        )
        assertEquals(0, runBlocking { graph.events.forAsset(assetId).size })

        // The production run posts it, with its actions and its freshly issued nonce.
        runBlocking { graph.reminderRuns.reconcileAll() }
        assertTrue("a notification for the seeded schedule", await { standingFor(scheduleId) != null })

        val posted = standingFor(scheduleId)!!
        val attached = posted.notification.actions?.toList() ?: emptyList()
        val labels = attached.map { it.title.toString() }
        assertEquals(
            "the ratified three, in order, on a real notification",
            listOf(DigestPolicy.ACTION_DONE, DigestPolicy.ACTION_SNOOZE_ONE_DAY, DigestPolicy.ACTION_OPEN),
            labels,
        )
        val nonce = runBlocking { graph.scheduleLocalDelivery.get(scheduleId)?.actionNonce }
        assertNotNull("the action it carries is authorised by a persisted nonce (D-21)", nonce)

        // The owner's tap: the platform fires the pending intent we built, at the component we
        // named, with the extras this build put on it.
        val done = attached.single { it.title.toString() == DigestPolicy.ACTION_DONE }
        done.actionIntent.send()

        assertTrue(
            "one completion event exists",
            await { runBlocking { graph.events.forAsset(assetId).size } == 1 },
        )
        assertTrue(
            "and the schedule is no longer due, so the reconcile took the notification down",
            await { standingFor(scheduleId) == null },
        )
        assertEquals(
            "exactly one, never two: the nonce was consumed in the same write",
            1,
            runBlocking { graph.events.forAsset(assetId).size },
        )
        assertEquals(
            "and the spent nonce is gone with it (invariant 56)",
            null,
            runBlocking { graph.scheduleLocalDelivery.get(scheduleId)?.actionNonce },
        )
    }

    /**
     * The `snoozedUntilOf` wiring, against the real graph and the real Room row — the one half of
     * carry-forward (a) no JVM test can reach, because the seam is a line of `AppGraph`.
     *
     * "Snooze 1 day" writes the device-local instant through `ReminderSnooze`; the projection every
     * surface reads must then carry it, the status must still be OVERDUE and the ratified
     * "Snoozed until \<date\>" must have something to draw (invariant 20, D-13).
     */
    @Test
    fun aSnoozedScheduleReachesTheProjectionThroughTheRealGraph() {
        val graph = app.graph
        val today = LocalDate.now()
        val seeded = runBlocking {
            val heater = graph.createAsset.run(AssetCommand(name = "Pool heater", category = "Water"))
            heater.id to seedOverdue(graph, "device-quick-snooze", heater.id, "Anode check", today)
        }
        val (assetId, scheduleId) = seeded
        val until = System.currentTimeMillis() + ONE_DAY_MILLIS

        runBlocking { graph.reminderSnooze.snooze(scheduleId, until) }
        val row = runBlocking { graph.dueReadModel.forAsset(assetId).single() }

        assertEquals("the projection reads the row's own instant", until, row.snoozedUntil)
        assertEquals("the obligation has not moved", DueStatus.OVERDUE, row.status)
        assertTrue("it still counts as due", row.countsAsDue)
        assertNotNull(
            "so the ratified badge has something to draw",
            snoozeLine(row, System.currentTimeMillis(), ZoneId.systemDefault()),
        )
    }

    /**
     * #87 row 11 (AC 2, 3, 4, 6, 7): the item's **body** is the "Open" action's own pending intent
     * (one platform record, code 2304), and sending it with no activity running — the cold
     * `onCreate` path — opens that schedule and nothing more: no completion flow, no event, the
     * nonce the run issued untouched, both notifications still standing.
     */
    @Test
    fun anItemBodyOpensItsScheduleExactlyAsOpenDoesAndWritesNothing() {
        val graph = app.graph
        val (assetId, scheduleId) = runBlocking {
            val filter = graph.createAsset.run(AssetCommand(name = "Pump house filter", category = "Water"))
            filter.id to seedOverdue(graph, BODY_ITEM_SCHEDULE, filter.id, "Filter change", LocalDate.now())
        }
        runBlocking { graph.reminderRuns.reconcileAll() }
        assertTrue("the item and the summary", await { standingFor(scheduleId) != null && standingSummary() != null })

        val item = standingFor(scheduleId)!!.notification
        val actions = item.actions?.toList().orEmpty()
        val open = actions.single { it.title.toString() == DigestPolicy.ACTION_OPEN }.actionIntent
        val done = actions.single { it.title.toString() == DigestPolicy.ACTION_DONE }.actionIntent
        val body = item.contentIntent
        assertNotNull("the item's body opens something (#87 AC 2)", body)
        assertTrue("immutable (invariant 54)", body!!.isImmutable)
        assertTrue("an activity, directly, never through a receiver (invariant 55)", body.isActivity)
        assertEquals("the Open action's own pending intent (AC 3)", open, body)
        assertNotEquals("never the Done broadcast", done, body)
        val nonce = runBlocking { graph.scheduleLocalDelivery.get(scheduleId)?.actionNonce }
        assertNotNull(nonce)

        assertTrue("no activity running: the cold path", await { liveMainActivities().isEmpty() })
        send(body)

        rule.awaitText(SERVICE_RECORD)
        rule.awaitText("Filter change")
        rule.waitForIdle()
        rule.onAllNodesWithText("When was this done?").assertCountEquals(0)
        assertTheTapWroteNothing(assetId, scheduleId, nonce)
        finishMainActivity()
    }

    /**
     * #87 row 12 (AC 1, 4, 6, 7): the summary's body is its own immutable activity intent to
     * `servicetag://dashboard` (code 2306), never the item's "Open". "Open" goes first (the shipped
     * path, cold) so the schedule detail is up and the landing is discriminating; the summary body
     * then arrives through the warm `onNewIntent` path and resets the stack to the Dashboard, whose
     * ATTENTION section holds the seeded row — and writes nothing.
     */
    @Test
    fun theSummaryBodyOpensTheDashboardAndWritesNothing() {
        val graph = app.graph
        val (assetId, scheduleId) = runBlocking {
            val heater = graph.createAsset.run(AssetCommand(name = "Example Heater", category = "Water"))
            heater.id to seedOverdue(graph, BODY_SUMMARY_SCHEDULE, heater.id, "Flush tank", LocalDate.now())
        }
        runBlocking { graph.reminderRuns.reconcileAll() }
        assertTrue("the item and the summary", await { standingFor(scheduleId) != null && standingSummary() != null })

        val open = standingFor(scheduleId)!!.notification.actions?.toList().orEmpty()
            .single { it.title.toString() == DigestPolicy.ACTION_OPEN }.actionIntent
        val body = standingSummary()!!.notification.contentIntent
        assertNotNull("the summary's body opens something (#87 AC 1)", body)
        assertTrue("immutable (invariant 54)", body!!.isImmutable)
        assertTrue("an activity, directly, never through a receiver (invariant 55)", body.isActivity)
        assertEquals(
            "aimed at the Dashboard",
            AndroidQuickActionIntents(context).pendingIntentFor(QuickActionTarget.OpenDashboardAttention),
            body,
        )
        assertNotEquals("a summary never names one schedule", open, body)
        val nonce = runBlocking { graph.scheduleLocalDelivery.get(scheduleId)?.actionNonce }
        assertNotNull(nonce)

        assertTrue("no activity running: the cold path", await { liveMainActivities().isEmpty() })
        send(open)
        rule.awaitText(SERVICE_RECORD)

        send(body)
        // The pop animation composes the outgoing detail for a moment, so its going is awaited.
        rule.waitUntil(SCREEN_TIMEOUT_MILLIS) {
            rule.onAllNodes(hasText(SERVICE_RECORD)).fetchSemanticsNodes().isEmpty()
        }
        rule.awaitText("ATTENTION")
        rule.awaitText("Flush tank")
        rule.onNode(hasText("ServiceTag") and hasNoClickAction()).assertIsDisplayed()
        assertTheTapWroteNothing(assetId, scheduleId, nonce)
        finishMainActivity()
    }

    /**
     * An asset-targeted `QUICK` schedule that is genuinely **five days overdue**, written straight
     * through the production repository and then derived by the real recompute — the same shape
     * `DueReadModelTest.seed` uses, and for the same reason.
     *
     * It cannot go through `SaveSchedule`, and that is a fact about the engine rather than a
     * shortcut: D-27 pins a FIXED series at the schedule's own creation stamp, so **a schedule
     * created now has no occurrence in the past** however far back its anchor reaches — an anchor a
     * year back reads DUE (the occurrence lands on today) and a year and five days back reads OK
     * (the only occurrence on or after the pin is next year's). A schedule that has existed for two
     * years is what an overdue one looks like, so that is what this writes.
     */
    private suspend fun seedOverdue(
        graph: AppGraph,
        id: String,
        assetId: AssetId,
        title: String,
        today: LocalDate,
    ): ScheduleId {
        val createdAt = today.minusYears(2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val schedule = MaintenanceSchedule(
            id = ScheduleId(id),
            target = ScheduleTarget.AssetTarget(assetId),
            title = title,
            description = "",
            timeInterval = 1,
            timeUnit = RecurrenceUnit.YEAR,
            timeBasis = TimeBasis.FIXED,
            anchorOn = today.minusYears(1).minusDays(5).toString(),
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
            createdAt = createdAt,
            updatedAt = createdAt,
            ruleChangedAt = createdAt,
            providers = listOf(ScheduleProviderRow(ProviderId.LOCAL.name, true)),
        )
        graph.schedules.upsert(schedule)
        graph.recomputeSchedules.forSchedule(schedule.id)
        return schedule.id
    }

    /**
     * This provider's per-item notifications carry one id and are told apart by their tag, whose
     * first field is the schedule id (`itemTag`). Reading it back off the platform is how the shade
     * itself answers "is this subject showing?".
     */
    private fun standingFor(id: ScheduleId): StatusBarNotification? =
        NotificationManagerCompat.from(context).activeNotifications
            .firstOrNull { it.id == AndroidReminderNotifications.ITEM_ID && it.tag?.substringBefore('|') == id.value }

    /**
     * A bounded poll, not a timing acceptance step: nothing here waits for an alarm, a period or a
     * date to turn over. What it waits for is two one-way hand-offs the platform owns — `notify`
     * and `cancel` reaching the shade, and WorkManager picking up a one-shot request — and the
     * bound exists only so a platform round trip cannot hang the suite.
     */
    private fun await(until: () -> Boolean): Boolean {
        repeat(POLL_ATTEMPTS) {
            if (until()) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return until()
    }

    /** The one summary, by its one id; its tag is its counts. */
    private fun standingSummary(): StatusBarNotification? =
        NotificationManagerCompat.from(context).activeNotifications
            .firstOrNull { it.id == AndroidReminderNotifications.SUMMARY_ID }

    /** C7: a body tap is navigation only — no event, the run's nonce untouched, no auto-cancel. */
    private fun assertTheTapWroteNothing(assetId: AssetId, scheduleId: ScheduleId, nonce: String?) {
        val graph = app.graph
        assertEquals("no completion event", 0, runBlocking { graph.events.forAsset(assetId).size })
        assertEquals(
            "the nonce the run issued is untouched",
            nonce,
            runBlocking { graph.scheduleLocalDelivery.get(scheduleId)?.actionNonce },
        )
        assertNotNull("the item still stands", standingFor(scheduleId))
        assertNotNull("and so does the summary", standingSummary())
    }

    /**
     * `send()` from this process. On API 34+ a pending intent may start an activity from a process
     * with no visible window only when its **sender** opts in; the real sender is the system shade,
     * so the opt-in lives here and never in production (plan §7, audit §6.9).
     */
    @Suppress("DEPRECATION")
    private fun send(intent: PendingIntent) {
        val options = ActivityOptions.makeBasic()
        val sdk = Build.VERSION.SDK_INT
        when {
            sdk >= Build.VERSION_CODES.BAKLAVA -> ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            else -> null
        }?.let { options.setPendingIntentBackgroundActivityStartMode(it) }
        intent.send(context, 0, null, null, null, null, options.toBundle())
    }

    /**
     * Finishes the `MainActivity` a send started, on the main thread, and waits — bounded — until it
     * is destroyed, so no screen outlives its case and the next case's send is genuinely cold.
     */
    private fun finishMainActivity() {
        val started = liveMainActivities()
        assertTrue("a send started MainActivity", started.isNotEmpty())
        onMain { started.forEach { it.finish() } }
        assertTrue("MainActivity destroyed after finish()", await { onMain { started.all { it.isDestroyed } } })
    }

    /** Every `MainActivity` not yet destroyed; the lifecycle registry is main-thread only. */
    private fun liveMainActivities(): List<MainActivity> = onMain {
        val registry = ActivityLifecycleMonitorRegistry.getInstance()
        Stage.entries.filter { it != Stage.DESTROYED }
            .flatMap { registry.getActivitiesInStage(it) }
            .filterIsInstance<MainActivity>()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private companion object {
        const val POLL_ATTEMPTS = 150
        const val POLL_INTERVAL_MILLIS = 200L

        /** #87: the schedule detail's one matcher, present and absent alike (`SectionHeader` upper-cases). */
        const val SERVICE_RECORD = "SERVICE RECORD"
        const val SCREEN_TIMEOUT_MILLIS = 10_000L

        /** Canonical uuids: `servicetag://schedule/<id>` accepts nothing else. */
        const val BODY_ITEM_SCHEDULE = "5d1c7b3e-2a4f-4c8d-9e6b-1f0a3c5e7d92"
        const val BODY_SUMMARY_SCHEDULE = "7a2e9c4b-6d3f-4b1a-8c5e-0d9f2b4a6e13"
    }
}
