package com.loosecannon.servicetag.hapublish

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import java.time.Duration

/**
 * #113/#114 (C32) — what the one-time **`"ha-publish"`** work is asked to be, decided here and nowhere else, so a JVM
 * test reads the decision the real seam carries out: any connected network, an exponential backoff from 30 seconds
 * (capped by the platform), [ExistingWorkPolicy.APPEND_OR_REPLACE] (F-26: a run whose snapshot predates a newer
 * desired value is followed by another, never dropped), and **never expedited** (G16: no foreground work).
 */
data class PublishOnceRequest(
    val networkType: NetworkType,
    val backoffPolicy: BackoffPolicy,
    val backoffDelay: Duration,
    val existingPolicy: ExistingWorkPolicy,
    val expedited: Boolean,
) {
    companion object {
        val C32: PublishOnceRequest = PublishOnceRequest(
            networkType = NetworkType.CONNECTED,
            backoffPolicy = BackoffPolicy.EXPONENTIAL,
            backoffDelay = Duration.ofSeconds(30),
            existingPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            expedited = false,
        )
    }
}

/**
 * #113/#114 (C32, R-N4) — what the periodic **`"ha-publish-reconcile"`** work is asked to be: the connection's cadence
 * ([SyncCadence.hours], requested, never a deadline), any connected network, and [ExistingPeriodicWorkPolicy.UPDATE],
 * so a changed cadence replaces the old period.
 */
data class PublishReconcileRequest(
    val period: Duration,
    val networkType: NetworkType,
    val existingPolicy: ExistingPeriodicWorkPolicy,
) {
    companion object {
        fun forCadence(cadence: SyncCadence): PublishReconcileRequest = PublishReconcileRequest(
            period = Duration.ofHours(cadence.hours),
            networkType = NetworkType.CONNECTED,
            existingPolicy = ExistingPeriodicWorkPolicy.UPDATE,
        )
    }
}

/**
 * #113/#114 (C32) — the two unique publish works, as a seam (#16's `SeasonSyncWork` shape): the one-time delivery,
 * the periodic reconcile, and the one call that takes both away. Blocking platform calls, made off the caller's
 * thread; [PublishDispatch] decides which to call (C30's rule), and this seam holds no opinion of its own.
 */
interface PublishWork {
    /** The one-time work, as [PublishOnceRequest.C32] says. */
    fun enqueueOnce()

    /** The periodic reconcile at [request]'s period. */
    fun ensureReconcile(request: PublishReconcileRequest)

    /** Both works: what `FOREGROUND_ONLY`, `PAUSED` and "nothing to publish" leave. */
    fun cancel()
}

/** The real seam, over WorkManager's records of [PublishWorker.ONCE_NAME] and [PublishWorker.RECONCILE_NAME]. */
class WorkManagerPublishWork(private val context: Context) : PublishWork {

    private val workManager: WorkManager get() = WorkManager.getInstance(context.applicationContext)

    override fun enqueueOnce() {
        val request = PublishOnceRequest.C32
        workManager.enqueueUniqueWork(PublishWorker.ONCE_NAME, request.existingPolicy, request.oneTime())
    }

    override fun ensureReconcile(request: PublishReconcileRequest) {
        workManager.enqueueUniquePeriodicWork(PublishWorker.RECONCILE_NAME, request.existingPolicy, request.periodic())
    }

    override fun cancel() {
        workManager.cancelUniqueWork(PublishWorker.ONCE_NAME)
        workManager.cancelUniqueWork(PublishWorker.RECONCILE_NAME)
    }
}

/** [PublishOnceRequest] as WorkManager's request, field for field; never `setExpedited` (G16). */
internal fun PublishOnceRequest.oneTime(): OneTimeWorkRequest =
    OneTimeWorkRequest.Builder(PublishWorker::class.java)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
        .setBackoffCriteria(backoffPolicy, backoffDelay)
        .setInputData(workDataOf(PublishWorker.KEY_RECONCILE to false))
        .build()

/** [PublishReconcileRequest] as WorkManager's request, field for field. */
internal fun PublishReconcileRequest.periodic(): PeriodicWorkRequest =
    PeriodicWorkRequest.Builder(PublishWorker::class.java, period)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
        .setInputData(workDataOf(PublishWorker.KEY_RECONCILE to true))
        .build()
