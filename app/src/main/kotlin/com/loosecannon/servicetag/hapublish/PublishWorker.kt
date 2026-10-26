package com.loosecannon.servicetag.hapublish

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlin.coroutines.cancellation.CancellationException

/**
 * #113/#114 (C32) — both publish works, one class: the one-time **`"ha-publish"`** (a delivery) and the periodic
 * **`"ha-publish-reconcile"`** (every enabled helper read back), told apart by [KEY_RECONCILE]. The decision about the
 * result is [PublishWorkerBody]'s, a plain class, because WorkManager's test harness runs only on a device.
 */
class PublishWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val body = PublishWorkerBody(HaPublishDispatch.dispatch)
        val result = if (inputData.getBoolean(KEY_RECONCILE, false)) body.reconcile() else body.once(runAttemptCount)
        return when (result) {
            PublishWorkResult.SUCCESS -> Result.success()
            PublishWorkResult.RETRY -> Result.retry()
        }
    }

    companion object {
        /** C1's unique names: one of each per install. */
        const val ONCE_NAME = "ha-publish"
        const val RECONCILE_NAME = "ha-publish-reconcile"

        /** The input key that marks the periodic reconcile. */
        const val KEY_RECONCILE = "reconcile"
    }
}

/** What one run asks WorkManager for. */
enum class PublishWorkResult { SUCCESS, RETRY }

/**
 * #113/#114 (C32) — the worker's body, over the graph's [dispatch] (null before `ServiceTagApp` assigns it: success,
 * nothing done).
 *
 * - **[once]**, the one-time work: [PublishDispatch.oncePass] (a projection, then `drain(SCHEDULED)`); **retry while
 *   TRANSIENT work remains** — an error of that class, an unknown outcome, or the `needsDelivery` / `reconcilePending` a
 *   transient failure left (B1's carry: from the recorded outcome) — **and [runAttemptCount] is below
 *   [MAX_RUN_ATTEMPTS]**, else success: bounded, and the periodic work, start, resume or Publish now resumes it.
 * - **[reconcile]**, the periodic work: [PublishDispatch.reconcilePass]; always success.
 * - A [PublishDatabaseFailed] in the one-time work: retry, logged by GH1. Any other exception — a key store that
 *   cannot load among them, which the runner lets through as #16 does — is success, logged by GH2: the next resume,
 *   scheduled run or start repeats it. Cancellation propagates; an `Error` is never caught. No line carries the
 *   exception, an address, an entity or the token.
 */
class PublishWorkerBody(
    private val dispatch: PublishDispatch?,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    suspend fun once(runAttemptCount: Int): PublishWorkResult {
        val dispatch = dispatch ?: return PublishWorkResult.SUCCESS
        return try {
            val remains = dispatch.oncePass()
            if (remains && runAttemptCount < MAX_RUN_ATTEMPTS) PublishWorkResult.RETRY else PublishWorkResult.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (_: PublishDatabaseFailed) {
            log(RUN_FAILED)
            PublishWorkResult.RETRY
        } catch (_: Exception) {
            log(COULD_NOT_RUN)
            PublishWorkResult.SUCCESS
        }
    }

    suspend fun reconcile(): PublishWorkResult {
        val dispatch = dispatch ?: return PublishWorkResult.SUCCESS
        try {
            dispatch.reconcilePass()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            log(COULD_NOT_RUN)
        }
        return PublishWorkResult.SUCCESS
    }

    companion object {
        /** C32: the one-time work retries TRANSIENT work while WorkManager's `runAttemptCount` is below this. */
        const val MAX_RUN_ATTEMPTS = 10

        /** GH1: a local database failure, the one retried end. */
        const val RUN_FAILED = "ha publish run failed; WorkManager retries it" // l10n-ok: log line

        /** GH2: any other end of a worker run or a delivery request. */
        const val COULD_NOT_RUN =
            "ha publish could not run; it will retry on the next resume, scheduled run, or app start" // l10n-ok: log line

        private const val TAG = "HaPublish"
    }
}
