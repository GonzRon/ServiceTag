package com.loosecannon.servicetag.reminders

import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.reminders.HealthFinding
import com.loosecannon.servicetag.core.reminders.ProviderId
import com.loosecannon.servicetag.core.reminders.ReconcileReport
import com.loosecannon.servicetag.core.reminders.ReminderProvider
import com.loosecannon.servicetag.core.reminders.ReminderSubject
import com.loosecannon.servicetag.core.reminders.RemoteChange
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shadow `AlarmManager` this Robolectric-free JVM suite gets: it records what was asked of it
 * and answers `armed()` from its own state, which is every fact the re-arm rules turn on. The real
 * `AlarmManager.setAndAllowWhileIdle` call behind [AndroidDigestAlarm] is what the connected class
 * proves; what fails silently on a device, and what this stands in for, is the *decision* to arm.
 */
internal class RecordingDigestAlarm(private var isArmed: Boolean = false) : DigestAlarm {
    var arms = 0
        private set
    var cancels = 0
        private set

    override fun armed(): Boolean = isArmed

    override fun arm() {
        arms++
        isArmed = true
    }

    override fun cancel() {
        cancels++
        isArmed = false
    }
}

/** A provider that records the lists it was handed. The semantics of the counters are the port's. */
internal class RecordingProvider(
    override val id: ProviderId = ProviderId.LOCAL,
    private val findings: List<HealthFinding> = emptyList(),
) : ReminderProvider {
    val calls = mutableListOf<List<ReminderSubject>>()

    override suspend fun reconcile(subjects: List<ReminderSubject>): ReconcileReport {
        calls += subjects
        return ReconcileReport(subjects.size, 0, 0, emptyList())
    }

    override suspend fun pullChanges(): List<RemoteChange> = emptyList()
    override suspend fun health(): List<HealthFinding> = findings
}

/**
 * The three entry points into one run, and the one thing that distinguishes them: when the alarm
 * is re-armed.
 *
 * The worker class itself is `CoroutineWorker`, which cannot be constructed without a real
 * `Context` and a WorkManager runtime; what carries the risk is not the eight lines that unwrap it
 * but [ReminderRuns] — the order of recompute, reconcile and re-arm, and the difference between
 * "re-arm always" and "re-arm only if it is gone". The worker's own wiring and the unique-work
 * enqueue are proved on the emulator by `ReminderPlatformDeviceProofTest`.
 */
class BackstopWorkerTest {

    private val today = Today { LocalDate.parse("2026-06-15") }

    private fun runs(
        alarm: DigestAlarm,
        provider: ReminderProvider = RecordingProvider(),
        rebuilds: MutableList<Unit> = mutableListOf(),
        subjects: List<ReminderSubject> = emptyList(),
        enqueued: MutableList<Unit> = mutableListOf(),
    ) = ReminderRuns(
        rebuildAll = { rebuilds += Unit },
        subjectsFor = { _, _ -> subjects },
        provider = provider,
        alarm = alarm,
        today = today,
        enqueueReconcile = { enqueued += Unit },
    )

    /**
     * The matrix's "the backstop does not backstop" row, and #21 AC 3. With the alarm deliberately
     * cancelled — a force-stop, or an OEM that cleared it — one backstop run re-arms it **and**
     * reconciles, so whatever was missed while it was gone is posted by the same run. A worker that
     * only recomputed would leave the phone permanently silent with nothing saying so.
     */
    @Test
    fun oneBackstopRunReArmsACancelledAlarmAndReconcilesWhatWasMissed() = runTest {
        val alarm = RecordingDigestAlarm(isArmed = false)
        val provider = RecordingProvider()
        val rebuilds = mutableListOf<Unit>()

        runs(alarm, provider, rebuilds).onBackstop()

        assertTrue("the alarm is armed again", alarm.armed())
        assertEquals(1, alarm.arms)
        assertEquals("the sweep ran, so a missed day is recomputed before it is posted", 1, rebuilds.size)
        assertEquals(1, provider.calls.size)
    }

    /**
     * The other half of the same rule: the backstop re-arms **only if the alarm is gone**. Arming
     * an already-armed alarm would replace a pending alarm with the same instant twice a day for
     * ever — harmless in effect and wrong as a statement of what the backstop is for, and the
     * distinction is the only thing separating this entry point from the other two.
     */
    @Test
    fun anAlreadyArmedAlarmIsLeftAloneByTheBackstop() = runTest {
        val alarm = RecordingDigestAlarm(isArmed = true)

        runs(alarm).onBackstop()

        assertEquals(0, alarm.arms)
        assertTrue(alarm.armed())
    }

    /**
     * The matrix's "the alarm never re-arms after firing" row. The inexact alarm is one shot:
     * handling the fire is what arms tomorrow's, and without that step the phone reminds once and
     * never again.
     *
     * The **re-arm happens in the broadcast window and the sweep does not** (fix round 1, finding
     * 4): arming is one synchronous `AlarmManager` call, and if it travelled with the work then a
     * deferred worker would also mean no alarm tomorrow — the one failure the backstop exists to
     * catch, made more likely rather than less.
     */
    @Test
    fun handlingTheFireArmsTheNextDayAndHandsTheSweepToAWorker() = runTest {
        val alarm = RecordingDigestAlarm(isArmed = false)
        val provider = RecordingProvider()
        val rebuilds = mutableListOf<Unit>()
        val enqueued = mutableListOf<Unit>()

        runs(alarm, provider, rebuilds, enqueued = enqueued).onDigestFired()

        assertEquals(1, alarm.arms)
        assertEquals("the sweep is enqueued", 1, enqueued.size)
        assertEquals("and nothing of it ran inline", 0, rebuilds.size)
        assertEquals(0, provider.calls.size)
    }

    /**
     * Invariant 60 and #21 AC 2: the alarm is re-armed after each of `BOOT_COMPLETED`, `TIME_SET`
     * and `TIMEZONE_CHANGED` — unconditionally, because each of them either cleared the alarm or
     * moved the instant it should be set for, and `armed()` cannot tell a stale instant from a
     * current one. `DATE_CHANGED` takes the same path: `T` moved, so the next instant did too.
     *
     * And in every case the sweep is **handed to a worker**, never run inline: a receiver has
     * roughly ten seconds, `goAsync()` does not extend that, and a rebuild of every schedule
     * followed by a post per subject is not a ten-second job on a cold doze-woken process
     * (fix round 1, finding 4).
     */
    @Test
    fun everyPlatformEventReArmsTheAlarmAndHandsTheSweepToAWorker() = runTest {
        PlatformEventKind.entries.forEach { kind ->
            val alarm = RecordingDigestAlarm(isArmed = true)
            val provider = RecordingProvider()
            val rebuilds = mutableListOf<Unit>()
            val enqueued = mutableListOf<Unit>()

            runs(alarm, provider, rebuilds, enqueued = enqueued).onPlatformEvent(kind)

            assertEquals("$kind must re-arm even when an alarm is already pending", 1, alarm.arms)
            assertEquals("$kind enqueues the sweep", 1, enqueued.size)
            assertEquals("$kind must not recompute inside the broadcast window", 0, rebuilds.size)
            assertEquals("$kind must not reconcile inside the broadcast window", 0, provider.calls.size)
        }
    }

    /**
     * The other end of the same fix: what the worker runs. `reconcileAll` is the sweep the receivers
     * hand off, and it does the whole of it — rebuild, subjects, reconcile — with **no** alarm
     * arming of its own, because the receiver already did that and the backstop asks its own
     * question.
     */
    @Test
    fun theWorkerSideRunsTheWholeSweepAndArmsNothing() = runTest {
        val alarm = RecordingDigestAlarm(isArmed = true)
        val provider = RecordingProvider()
        val rebuilds = mutableListOf<Unit>()

        runs(alarm, provider, rebuilds).reconcileAll()

        assertEquals(1, rebuilds.size)
        assertEquals(1, provider.calls.size)
        assertEquals("the sweep is not an arming point", 0, alarm.arms)
    }

    /** The order is load-bearing: a reconcile before the recompute posts yesterday's answer. */
    @Test
    fun theRecomputeAlwaysRunsBeforeTheReconcile() = runTest {
        val order = mutableListOf<String>()
        val provider = object : ReminderProvider {
            override val id = ProviderId.LOCAL
            override suspend fun reconcile(subjects: List<ReminderSubject>): ReconcileReport {
                order += "reconcile"
                return ReconcileReport(0, 0, 0, emptyList())
            }
            override suspend fun pullChanges(): List<RemoteChange> = emptyList()
            override suspend fun health(): List<HealthFinding> = emptyList()
        }
        val alarm = RecordingDigestAlarm()

        ReminderRuns(
            rebuildAll = { order += "rebuild" },
            subjectsFor = { _, _ -> order += "subjects"; emptyList() },
            provider = provider,
            alarm = alarm,
            today = today,
            enqueueReconcile = { order += "enqueue" },
        ).onBackstop()

        assertEquals(
            "the backstop is already inside a worker, so it sweeps inline and enqueues nothing",
            listOf("rebuild", "subjects", "reconcile"),
            order,
        )
    }

    /**
     * The two periods and the two unique names, locked against an edit (spec 5.3).
     *
     * These assertions restate the constants' own literals and so cannot catch the failure that
     * actually matters — a `PeriodicWorkRequest` built with period and flex **swapped** (fix
     * round 1, nit 6). `WorkRequest.workSpec` is `@RestrictTo`, so the honest place for that is the
     * connected class, and `ReminderPlatformDeviceProofTest.theUniquePeriodicWorkIsEnqueuedExactlyOnceAcrossTwoLaunches`
     * now reads `WorkInfo.periodicityInfo` back off WorkManager and asserts both values. What is
     * left here is the value it does have: a later edit to either constant, or either name, fails.
     */
    @Test
    fun theBackstopPeriodIsTwelveHoursWithAFourHourFlex() {
        assertEquals(12L * 60L * 60L * 1000L, BackstopWorker.PERIOD_MILLIS)
        assertEquals(4L * 60L * 60L * 1000L, BackstopWorker.FLEX_MILLIS)
        assertEquals("reminder-backstop", BackstopWorker.UNIQUE_NAME)
        assertEquals("reminder-reconcile", ReconcileWorker.UNIQUE_NAME)
        assertNotEquals(
            "two unique names, so a coalescing sweep can never displace the periodic backstop",
            BackstopWorker.UNIQUE_NAME,
            ReconcileWorker.UNIQUE_NAME,
        )
    }
}
