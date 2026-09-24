package com.loosecannon.servicetag.reminders

import android.Manifest
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
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
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.maintenance.snoozeLine
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    private companion object {
        const val POLL_ATTEMPTS = 150
        const val POLL_INTERVAL_MILLIS = 200L
    }
}
