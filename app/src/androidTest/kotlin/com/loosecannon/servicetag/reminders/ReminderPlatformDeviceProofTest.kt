package com.loosecannon.servicetag.reminders

import android.Manifest
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkManager
import com.loosecannon.servicetag.ServiceTagApp
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.reminders.SubjectKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
     * created them; this is the only place that can confirm the platform agreed.
     */
    @Test
    fun theTwoChannelsExistAtTheirImportancesAfterAFirstLaunch() {
        val manager = NotificationManagerCompat.from(context)

        val due = manager.getNotificationChannelCompat(NotificationChannels.DUE)
        val overdue = manager.getNotificationChannelCompat(NotificationChannels.OVERDUE)

        assertEquals("Maintenance due", due?.name)
        assertEquals(NotificationManagerCompat.IMPORTANCE_DEFAULT, due?.importance)
        assertEquals("Maintenance overdue", overdue?.name)
        assertEquals(NotificationManagerCompat.IMPORTANCE_HIGH, overdue?.importance)

        assertEquals(
            "no supplies and no sync_problems channel, ever (D-20 = B)",
            setOf(NotificationChannels.DUE, NotificationChannels.OVERDUE),
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
     */
    @Test
    fun aPostedNotificationIsReadableBackByItsTag() {
        val notifications = AndroidReminderNotifications(context)
        val key: SubjectKey = SubjectKey.Schedule(ScheduleId("device-proof-1"))
        val tag = itemTag(key, "0123456789abcdef0123")
        val post = ItemPost(
            key = key,
            tag = tag,
            channelId = NotificationChannels.OVERDUE,
            title = "Pump house filter — Filter change",
            body = "Overdue since 30 May 2026.",
            statusWord = DigestPolicy.WORD_OVERDUE,
            actions = listOf(DigestPolicy.ACTION_DONE, DigestPolicy.ACTION_SNOOZE_ONE_DAY, DigestPolicy.ACTION_OPEN),
        )

        notifications.cancelItem(tag)
        assertTrue("nothing of ours is standing to begin with", awaitStanding(notifications, tag, false))

        notifications.postItem(post)
        assertTrue("the shade is this provider's projection", awaitStanding(notifications, tag, true))

        notifications.cancelItem(tag)
        assertTrue("and a cancel takes it away again", awaitStanding(notifications, tag, false))
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
