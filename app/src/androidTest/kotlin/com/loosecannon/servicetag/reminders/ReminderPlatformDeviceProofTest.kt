package com.loosecannon.servicetag.reminders

import android.Manifest
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.loosecannon.servicetag.ServiceTagApp
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.reminders.DeadlineKind
import com.loosecannon.servicetag.core.reminders.SubjectKey
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.LoanTerms
import com.loosecannon.servicetag.core.usecase.WarrantyReminderCommand
import com.loosecannon.servicetag.ui.clearInstall
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The three platform facts no JVM test in this brief can reach, on the emulator: the two channels
 * as the real `NotificationManager` holds them, the unique periodic work as WorkManager holds it,
 * and the alarm as the real `AlarmManager` and `PendingIntent` registry hold it.
 *
 * Every one of them is a **state** question rather than a timing one — does the channel exist at
 * this importance, is there exactly one worker under this name, is a pending intent registered —
 * so nothing here waits on a fire, an overnight run, or a wall clock. The behaviour that decides
 * *when* to arm and *what* to post is proved off-device by `DigestAlarmTest`, `DigestPolicyTest`,
 * `BackstopWorkerTest` and `LocalReminderProviderTest`.
 *
 * The instrumented run wipes the app's data first, which is the point: it is a first launch.
 */
@RunWith(AndroidJUnit4::class)
class ReminderPlatformDeviceProofTest {

    @get:Rule
    val notificationPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val app get() = context.applicationContext as ServiceTagApp

    /**
     * Invariant 53 against the platform: after a first launch both channels exist, at the
     * importances D-20 fixed, and the app has created **no others**. `ServiceTagApp.onCreate`
     * created them; this is the only place that can confirm the platform agreed. #79 (R79-14b)
     * amends invariant 53 with the third, `warranty_reminders`, at the default importance, and #72
     * (R72-9) with the fourth, `loan_reminders`, at the default importance too.
     */
    @Test
    fun theFourChannelsExistAtTheirImportancesAfterAFirstLaunch() {
        val manager = NotificationManagerCompat.from(context)

        val due = manager.getNotificationChannelCompat(NotificationChannels.DUE)
        val overdue = manager.getNotificationChannelCompat(NotificationChannels.OVERDUE)
        val warranty = manager.getNotificationChannelCompat(NotificationChannels.WARRANTY)
        val loans = manager.getNotificationChannelCompat(NotificationChannels.LOANS)

        assertEquals("Maintenance due", due?.name)
        assertEquals(NotificationManagerCompat.IMPORTANCE_DEFAULT, due?.importance)
        assertEquals("Maintenance overdue", overdue?.name)
        assertEquals(NotificationManagerCompat.IMPORTANCE_HIGH, overdue?.importance)
        assertEquals("Warranty reminders", warranty?.name)
        assertEquals("Reminders before a warranty expires.", warranty?.description)
        assertEquals(NotificationManagerCompat.IMPORTANCE_DEFAULT, warranty?.importance)
        assertEquals("Loan reminders", loans?.name)
        assertEquals("Reminders when a lent item is due back.", loans?.description)
        assertEquals(NotificationManagerCompat.IMPORTANCE_DEFAULT, loans?.importance)

        assertEquals(
            "no supplies and no sync_problems channel, ever (D-20 = B)",
            setOf(NotificationChannels.DUE, NotificationChannels.OVERDUE, NotificationChannels.WARRANTY, NotificationChannels.LOANS),
            manager.notificationChannelsCompat.map { it.id }.toSet(),
        )
    }

    /**
     * The backstop as WorkManager holds it: **one** worker under the unique name, and enqueuing
     * again — a second process start — neither adds a second nor replaces the first. `KEEP` is what
     * makes `ServiceTagApp.onCreate` safe to call on every launch, and `REPLACE` would restart the
     * 12 h period every time the owner opened the app.
     */
    @Test
    fun theUniquePeriodicWorkIsEnqueuedExactlyOnceAcrossTwoLaunches() {
        val work = WorkManager.getInstance(context)

        BackstopWorker.enqueue(context)
        val first = work.getWorkInfosForUniqueWork(BackstopWorker.UNIQUE_NAME).get()
        assertEquals("one worker after the first launch", 1, first.size)

        BackstopWorker.enqueue(context)
        val second = work.getWorkInfosForUniqueWork(BackstopWorker.UNIQUE_NAME).get()

        assertEquals("still one worker after the second", 1, second.size)
        assertEquals("and it is the same one: KEEP did not restart the period", first.single().id, second.single().id)

        // Read back off WorkManager, which is the only place the two values can be seen apart
        // (`WorkRequest.workSpec` is `@RestrictTo`): a builder called with period and flex **swapped**
        // is the failure the unit test's constant assertions cannot catch (fix round 1, nit 6).
        val periodicity = second.single().periodicityInfo
        assertEquals(BackstopWorker.PERIOD_MILLIS, periodicity?.repeatIntervalMillis)
        assertEquals(BackstopWorker.FLEX_MILLIS, periodicity?.flexIntervalMillis)
    }

    /**
     * The receivers' hand-off, against the real WorkManager (fix round 1, finding 4).
     *
     * A `BroadcastReceiver` has roughly ten seconds and `goAsync()` does not extend it, so the
     * digest fire and the four platform events now enqueue this one-shot and return. Two facts are
     * worth a device: that the enqueue really produces **one** unique work however many events
     * arrive together, and that the worker's own body succeeds when it runs — driven directly
     * through the WorkManager test harness rather than waited for, so nothing here touches a clock.
     */
    @Test
    fun theReceiversSweepIsOneUniqueWorkAndItsWorkerSucceeds() {
        val work = WorkManager.getInstance(context)

        ReconcileWorker.enqueue(context)
        ReconcileWorker.enqueue(context)
        val infos = work.getWorkInfosForUniqueWork(ReconcileWorker.UNIQUE_NAME).get()

        assertEquals("two events coalesce into one sweep", 1, infos.size)
        assertNotEquals(
            "and it never displaces the periodic backstop",
            BackstopWorker.UNIQUE_NAME,
            ReconcileWorker.UNIQUE_NAME,
        )

        val worker = TestListenableWorkerBuilder<ReconcileWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
    }

    /**
     * The alarm against the real `AlarmManager` and the real `PendingIntent` registry: arming makes
     * `armed()` true, arming again replaces rather than adds, and cancelling makes it false again.
     *
     * `armed()` is the question `DIGEST_ALARM_MISSING` asks, and it is answered by
     * `PendingIntent.getBroadcast(FLAG_NO_CREATE)`, whose semantics only the platform has. A
     * `cancel()` that forgot to cancel the pending intent itself would leave this reporting an
     * armed alarm that will never fire — which is the failure this row exists for.
     */
    @Test
    fun theAlarmIsArmedReplacedAndCancelledAgainstTheRealPlatform() {
        val alarm = app.graph.digestAlarm

        alarm.cancel()
        assertFalse("nothing is pending once it has been cancelled", alarm.armed())

        alarm.arm()
        assertTrue(alarm.armed())
        alarm.arm()
        assertTrue("arming twice is one alarm, not two", alarm.armed())

        alarm.cancel()
        assertFalse(alarm.armed())

        // Leave the app as a launch would: the backstop and the platform events both arm it, and a
        // later test class in the same run should not inherit a cancelled alarm.
        alarm.arm()
    }

    /**
     * The stateless mechanism, end to end on a real `NotificationManager`: a posted notification is
     * readable back by its own tag, and the tag carries the content hash.
     *
     * This is what makes invariant 44 a platform fact rather than a fake's courtesy — "what am I
     * already showing?" is answered by the shade, so nothing has to be persisted for a second
     * reconcile to be inert or for a cleared app to rebuild itself.
     *
     * The actions are deliberately **empty** here: this row is about the tag being the identity, and
     * a posted notification's actions are `QuickActionDeviceProofTest`'s whole subject.
     */
    @Test
    fun aPostedNotificationIsReadableBackByItsTag() {
        val notifications = AndroidReminderNotifications(context, AndroidQuickActionIntents(context))
        val key: SubjectKey = SubjectKey.Schedule(ScheduleId("device-proof-1"))
        val tag = itemTag(key, "0123456789abcdef0123")
        val post = ItemPost(
            key = key,
            tag = tag,
            channelId = NotificationChannels.OVERDUE,
            title = "Pump house filter — Filter change",
            body = "Overdue since 30 May 2026.",
            statusWord = DigestPolicy.WORD_OVERDUE,
            meter = false,
            actions = listOf(DigestPolicy.ACTION_DONE, DigestPolicy.ACTION_SNOOZE_ONE_DAY, DigestPolicy.ACTION_OPEN),
        )

        notifications.cancelItem(tag)
        assertTrue("nothing of ours is standing to begin with", awaitStanding(notifications, tag, false))

        notifications.postItem(post, emptyList())
        assertTrue("the shade is this provider's projection", awaitStanding(notifications, tag, true))

        notifications.cancelItem(tag)
        assertTrue("and a cancel takes it away again", awaitStanding(notifications, tag, false))
    }

    /**
     * #79 (C6, M4): the composition, through the real graph. An in-service asset with a lead, inside
     * its window, is posted by `reminderRuns.reconcileAll()` — the one sweep every receiver, the
     * alarm and the backstop share — so the deadline builder is proved to be in `subjectsFor`, the
     * facts and the stamp table in the provider, and nothing else needed wiring.
     */
    @Test
    fun theGraphsSweepPostsAWarrantyInItsWindow() {
        clearInstall()
        NotificationManagerCompat.from(context).cancelAll()
        val graph = app.graph
        val expiry = LocalDate.now().plusDays(10)
        val asset = runBlocking {
            val heater = graph.createAsset.run(AssetCommand(name = "Example Heater", warrantyExpiresOn = expiry.toString()))
            graph.setWarrantyReminder.run(heater.id, WarrantyReminderCommand(30))
        }
        val key = SubjectKey.Deadline(DeadlineKind.WARRANTY_EXPIRY, asset.id.value)

        runBlocking { graph.reminderRuns.reconcileAll() }

        assertTrue("the sweep posted the warning", awaitTrue { warningFor(key) != null })
        val posted = warningFor(key)!!
        assertEquals(key, keyOfTag(posted.tag))
        assertEquals(NotificationChannels.WARRANTY, posted.notification.channelId)
        assertEquals(
            "and stamped it, once, in the device-local table",
            listOf(asset.id.value),
            runBlocking { graph.deadlineLocalDelivery.all() }.map { it.subjectId },
        )

        NotificationManagerCompat.from(context).cancelAll()
        clearInstall()
    }

    /**
     * #79 (C7, C8; invariants 54, 57): a warranty warning on a real `NotificationManager`, read back
     * by its tag — the ratified word as sub-text, its own channel, and exactly one action, "Open",
     * an **immutable activity** intent. Nothing on it writes, so it is never a broadcast.
     */
    @Test
    fun aWarrantyPostIsReadableBackByItsTagWithOneImmutableOpenAction() {
        val notifications = AndroidReminderNotifications(context, AndroidQuickActionIntents(context))
        val key = SubjectKey.Deadline(DeadlineKind.WARRANTY_EXPIRY, "0b4f3c2a-6d1e-4f8a-9b7c-5e2d1a0f3b6c")
        val tag = itemTag(key, "fedcba9876543210ffff")
        val post = ItemPost(
            key = key,
            tag = tag,
            channelId = NotificationChannels.WARRANTY,
            title = "Example Heater — Warranty",
            body = "Warranty expires 30 Jun 2031.",
            statusWord = DigestPolicy.WORD_EXPIRES_SOON,
            meter = false,
            actions = listOf(DigestPolicy.ACTION_OPEN),
        )

        notifications.cancelItem(tag)
        notifications.postItem(post, app.graph.quickActions.forDeadline(key))
        assertTrue("the shade holds it under its own tag", awaitStanding(notifications, tag, true))

        val standing = NotificationManagerCompat.from(context).activeNotifications
            .single { it.id == AndroidReminderNotifications.ITEM_ID && it.tag == tag }
        assertEquals(key, keyOfTag(standing.tag))
        assertEquals(NotificationChannels.WARRANTY, standing.notification.channelId)
        assertEquals("EXPIRES SOON", standing.notification.extras.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT)?.toString())
        val actions = standing.notification.actions?.toList().orEmpty()
        assertEquals(listOf("Open"), actions.map { it.title.toString() })
        assertTrue("the one action is immutable (invariant 54)", actions.single().actionIntent.isImmutable)
        assertTrue("and opens a screen directly, never through a receiver (invariant 55)", actions.single().actionIntent.isActivity)

        notifications.cancelItem(tag)
        assertTrue(awaitStanding(notifications, tag, false))
    }

    /**
     * #72 (C10, C11): the composition, through the real graph. A loan due **yesterday** — so the
     * owner's digest hour on its due day has passed whatever it is set to — is posted by
     * `reminderRuns.reconcileAll()`, the one sweep every receiver, the alarm and the backstop share:
     * the loan builder is in `subjectsFor`, the facts source reads the loan, and the post rides its own
     * channel under a `LOAN_DUE_BACK:<asset>/<loan>` tag, stamped once in the device-local table.
     */
    @Test
    fun theGraphsSweepPostsALoanPastItsDueDay() {
        val (asset, loan) = freshLoanDueYesterday()
        val key = SubjectKey.Deadline(DeadlineKind.LOAN_DUE_BACK, "${asset.id.value}/${loan.id.value}")

        runBlocking { app.graph.reminderRuns.reconcileAll() }

        assertTrue("the sweep posted the loan reminder", awaitTrue { warningFor(key) != null })
        val posted = warningFor(key)!!
        assertTrue(posted.tag.startsWith("LOAN_DUE_BACK:${asset.id.value}/${loan.id.value}|"))
        assertEquals(key, keyOfTag(posted.tag))
        assertEquals(NotificationChannels.LOANS, posted.notification.channelId)
        assertEquals("NOT RETURNED", posted.notification.extras.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT)?.toString())
        assertEquals(
            "and stamped it, once, in the device-local table",
            listOf("LOAN_DUE_BACK" to key.subjectId),
            runBlocking { app.graph.deadlineLocalDelivery.all() }.map { it.kind to it.subjectId },
        )

        NotificationManagerCompat.from(context).cancelAll()
        clearInstall()
    }

    /**
     * #72 (C12; invariants 54, 55, 57): the loan reminder the real sweep posted carries exactly one
     * action, "Open" — an **immutable activity** intent, and the very one the shipped intents build
     * for the loan's **asset**, never one aimed at the loan's own id.
     */
    @Test
    fun aLoanPostHasOneImmutableOpenActionAimedAtItsAsset() {
        val (asset, loan) = freshLoanDueYesterday()
        val key = SubjectKey.Deadline(DeadlineKind.LOAN_DUE_BACK, "${asset.id.value}/${loan.id.value}")

        runBlocking { app.graph.reminderRuns.reconcileAll() }

        assertTrue(awaitTrue { warningFor(key) != null })
        val actions = warningFor(key)!!.notification.actions?.toList().orEmpty()
        assertEquals(listOf("Open"), actions.map { it.title.toString() })
        val open = actions.single().actionIntent
        assertTrue("the one action is immutable (invariant 54)", open.isImmutable)
        assertTrue("and opens a screen directly, never through a receiver (invariant 55)", open.isActivity)
        val intents = AndroidQuickActionIntents(context)
        assertEquals("aimed at the asset", intents.pendingIntentFor(QuickActionTarget.OpenAsset(asset.id)), open)
        assertNotEquals(
            "never at the loan",
            intents.pendingIntentFor(QuickActionTarget.OpenAsset(AssetId(loan.id.value))),
            open,
        )

        NotificationManagerCompat.from(context).cancelAll()
        clearInstall()
    }

    /**
     * #72 (C11, AC 8): marked returned, the loan leaves the subjects, so the next sweep takes its
     * reminder down and forgets its stamp — through the real use case, graph and shade.
     */
    @Test
    fun returningTheLoanThenSweepingTakesItDown() {
        val (asset, loan) = freshLoanDueYesterday()
        val key = SubjectKey.Deadline(DeadlineKind.LOAN_DUE_BACK, "${asset.id.value}/${loan.id.value}")
        runBlocking { app.graph.reminderRuns.reconcileAll() }
        assertTrue(awaitTrue { warningFor(key) != null })

        runBlocking {
            app.graph.returnLoan.run(loan.id, LocalDate.now().toString())
            app.graph.reminderRuns.reconcileAll()
        }

        assertTrue("the return took it down", awaitTrue { warningFor(key) == null })
        assertEquals(emptyList<String>(), runBlocking { app.graph.deadlineLocalDelivery.all() }.map { it.subjectId })

        NotificationManagerCompat.from(context).cancelAll()
        clearInstall()
    }

    /**
     * A clean install holding one in-service asset, lent three days ago and due back yesterday, with a
     * Once reminder. The digest hour is set to 00:00 (fix round 1, R1-8): a loan is quiet between
     * local midnight and the day's digest hour, and `clearInstall()` resets the hour to 09:00, so
     * without this the loan cases would fail whenever the class runs before 09:00 local. The closing
     * `clearInstall()` restores the default.
     */
    private fun freshLoanDueYesterday(): Pair<Asset, AssetLoan> {
        clearInstall()
        NotificationManagerCompat.from(context).cancelAll()
        val graph = app.graph
        graph.prefs.digestHour = 0
        val today = LocalDate.now()
        return runBlocking {
            val drill = graph.createAsset.run(AssetCommand(name = "Example Drill"))
            val loan = graph.lendAsset.run(
                drill.id,
                "Sample Borrower",
                LoanTerms(
                    lentOn = today.minusDays(3).toString(),
                    dueOn = today.minusDays(1).toString(),
                    reminderMode = LoanReminderMode.ONCE,
                ),
            )
            drill to loan
        }
    }

    /** #79 (R79-14c): the boot count a deadline's stamp records is readable on a real phone. */
    @Test
    fun theBootCountIsReadable() {
        val count = app.graph.platformState.bootCount()
        assertNotNull("Settings.Global.BOOT_COUNT", count)
        assertTrue("a phone that is running has started at least once", count!! >= 1)
    }

    private fun warningFor(key: SubjectKey.Deadline): StatusBarNotification? =
        NotificationManagerCompat.from(context).activeNotifications.firstOrNull {
            it.id == AndroidReminderNotifications.ITEM_ID && it.tag?.let(::keyOfTag) == key
        }

    private fun awaitTrue(until: () -> Boolean): Boolean {
        repeat(POLL_ATTEMPTS) {
            if (until()) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return until()
    }

    /**
     * `notify` and `cancel` are one-way calls into the system's notification service, so the shade
     * catches up a moment later — this polls for that, bounded, rather than asserting on the
     * instant after the call.
     *
     * It is **not** a timing acceptance step: nothing here waits for an alarm, a worker period or a
     * date to turn over, and the bound exists only so a platform round trip cannot hang the suite.
     * Production reads the standing set at the **start** of a run, minutes or hours after the last
     * one wrote it, so the settling delay is never on a path that matters.
     */
    private fun awaitStanding(
        notifications: ReminderNotifications,
        tag: String,
        expected: Boolean,
    ): Boolean {
        repeat(POLL_ATTEMPTS) {
            if ((tag in notifications.standingItems()) == expected) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return (tag in notifications.standingItems()) == expected
    }

    private companion object {
        const val POLL_ATTEMPTS = 50
        const val POLL_INTERVAL_MILLIS = 100L
    }
}
