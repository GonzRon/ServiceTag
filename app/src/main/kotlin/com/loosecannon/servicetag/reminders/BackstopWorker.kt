package com.loosecannon.servicetag.reminders

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The backstop: every 12 h with a 4 h flex window, recompute every schedule's state, post whatever
 * was missed, and re-arm the alarm if it has gone (spec 5.3).
 *
 * It exists because **WorkManager survives a reboot and a force-stop and alarms do not**. The four
 * platform receivers cover the events the system tells us about; this covers the ones it does not —
 * an OEM that dropped the alarm, a force-stop the owner performed, a doze window that swallowed a
 * fire. Its period is the reason a missed digest is a delay and not a silence, and it is why no
 * acceptance step in this brief has to wait overnight for a real one.
 *
 * Enqueued as **unique** work with `KEEP`, so a second process start does not add a second worker
 * and does not reset the first one's window either.
 */
class BackstopWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val run = ReminderRunDispatch.run
        if (run == null) {
            // WorkManager initialises after `Application.onCreate`, so this is unreachable in a
            // healthy process; reporting success rather than retry is what keeps an unreachable
            // branch from turning into a retry loop if it ever becomes reachable.
            Log.w(TAG, "the backstop ran before a reminder policy was assigned; nothing to do")
            return Result.success()
        }
        return try {
            run.onBackstop()
            Result.success()
        } catch (e: Exception) {
            // A transient failure — a database busy, a read that raced a write — is worth one more
            // attempt on WorkManager's own backoff. Failing outright would leave the next run a
            // full period away.
            Log.w(TAG, "the backstop run failed; WorkManager will retry it", e)
            Result.retry()
        }
    }

    companion object {
        /** Spec 5.3: 12 h. */
        const val PERIOD_MILLIS: Long = 12L * 60L * 60L * 1000L

        /** Spec 5.3: a 4 h flex window, so the platform can batch the run with other work. */
        const val FLEX_MILLIS: Long = 4L * 60L * 60L * 1000L

        /** The unique name. One worker per install, however many times this is called. */
        const val UNIQUE_NAME = "reminder-backstop"

        private const val TAG = "BackstopWorker"

        internal fun request(): PeriodicWorkRequest = PeriodicWorkRequest.Builder(
            BackstopWorker::class.java,
            PERIOD_MILLIS,
            TimeUnit.MILLISECONDS,
            FLEX_MILLIS,
            TimeUnit.MILLISECONDS,
        ).build()

        /**
         * Idempotent: `KEEP` leaves an already-enqueued worker exactly as it is, which is what lets
         * `ServiceTagApp.onCreate` call this on every process start. `REPLACE` would restart the
         * period on every launch and a frequently-opened app would never reach the end of one.
         */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request())
        }
    }
}
