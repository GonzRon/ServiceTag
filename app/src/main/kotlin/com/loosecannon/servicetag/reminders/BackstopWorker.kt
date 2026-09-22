package com.loosecannon.servicetag.reminders

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
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
            repairHealth()
            Result.success()
        } catch (e: Exception) {
            // A transient failure — a database busy, a read that raced a write — is worth one more
            // attempt on WorkManager's own backoff. Failing outright would leave the next run a
            // full period away.
            Log.w(TAG, "the backstop run failed; WorkManager will retry it", e)
            Result.retry()
        }
    }

    /**
     * #27's second run point: this worker already runs on a schedule and already re-arms the alarm,
     * so the finding and its repair are one pass (master plan decision 32). Only the two
     * unambiguous repairs are applied, by [ReminderHealthCheck] itself, and running them again on
     * the next period changes nothing.
     *
     * It has its own `try`, deliberately: the sweep above has already succeeded by the time this
     * runs, and a health check that could turn that into a retry would cost the phone the whole
     * recompute and every notification it just posted.
     */
    private suspend fun repairHealth() {
        val check = ReminderHealthDispatch.check ?: return
        try {
            check.runAndRepair()
        } catch (e: Exception) {
            Log.w(TAG, "the health check failed; the sweep itself succeeded", e)
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

/**
 * The sweep, out of the broadcast window: rebuild every schedule's derived state, ask for the
 * subjects it implies, reconcile (fix round 1, finding 4).
 *
 * It exists because a `BroadcastReceiver` has roughly ten seconds and `goAsync()` does not extend
 * that. The digest alarm's receiver and the four platform receivers each now do one cheap
 * `AlarmManager` call and one enqueue of this worker, and return — so the work that actually reads
 * the database and posts notifications runs under WorkManager's own budget, on a process the system
 * will not kill part-way through, and survives being deferred rather than being lost.
 *
 * Enqueued as **unique one-shot** work with `KEEP`, so a boot that also changes the time zone, or a
 * digest fire that lands next to a date change, coalesces into one sweep instead of three. That is
 * safe because the sweep is idempotent by construction: `reconcile` receives the whole desired
 * state, so running it once instead of three times loses nothing.
 */
class ReconcileWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val run = ReminderRunDispatch.run
        if (run == null) {
            Log.w(TAG, "a reconcile ran before a reminder policy was assigned; nothing to do")
            return Result.success()
        }
        return try {
            run.reconcileAll()
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "the reconcile run failed; WorkManager will retry it", e)
            Result.retry()
        }
    }

    companion object {
        /** One name, so overlapping platform events coalesce into one sweep. */
        const val UNIQUE_NAME = "reminder-reconcile"

        private const val TAG = "ReconcileWorker"

        internal fun request(): OneTimeWorkRequest = OneTimeWorkRequest.Builder(ReconcileWorker::class.java).build()

        /**
         * `KEEP`, not `REPLACE`: a second event arriving while a sweep is queued or running should
         * join it, not restart it. Once the previous one has finished, `KEEP` no longer has
         * anything to keep and the new request runs, which is what makes this safe to call from
         * every receiver on every event.
         */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request())
        }
    }
}
