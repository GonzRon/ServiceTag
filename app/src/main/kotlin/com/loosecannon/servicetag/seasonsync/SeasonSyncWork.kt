package com.loosecannon.servicetag.seasonsync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import java.time.Duration

/**
 * #16 (C22; R16-7, R16-Q-D as amended) — what the one periodic season sync work is asked to be, decided here and
 * nowhere else, so a JVM test reads the decision the real seam carries out: the period is the connection's cadence
 * ([SyncCadence.hours]: 12 h, 24 h, 7 d, 30 d — requested, never a deadline, limit 7), any connected network, an
 * exponential backoff from five minutes, and [ExistingPeriodicWorkPolicy.UPDATE], so a changed cadence replaces the
 * old period and no obsolete interval survives.
 */
data class SeasonSyncWorkRequest(
    val period: Duration,
    val networkType: NetworkType,
    val backoffPolicy: BackoffPolicy,
    val backoffDelay: Duration,
    val existingPolicy: ExistingPeriodicWorkPolicy,
) {
    companion object {
        /** The backstop's backoff shape, from five minutes (C22). */
        val BACKOFF_DELAY: Duration = Duration.ofMinutes(5)

        fun forCadence(cadence: SyncCadence): SeasonSyncWorkRequest = SeasonSyncWorkRequest(
            period = Duration.ofHours(cadence.hours),
            networkType = NetworkType.CONNECTED,
            backoffPolicy = BackoffPolicy.EXPONENTIAL,
            backoffDelay = BACKOFF_DELAY,
            existingPolicy = ExistingPeriodicWorkPolicy.UPDATE,
        )
    }
}

/**
 * #16 (C22) — the season sync's unique periodic work, as a seam (`ReminderHealthCheck`'s `BackstopWork` shape):
 * whether one is pending, the one call that puts it there as [request] says, and the one that takes it away. All
 * three are blocking platform calls, which is why the runner makes them off the caller's thread. The runner decides
 * which to call (C22's rule); this seam holds no opinion of its own.
 */
interface SeasonSyncWork {
    fun isEnqueued(): Boolean

    fun ensure(request: SeasonSyncWorkRequest)

    fun cancel()
}

/** The real seam, over WorkManager's record of [SeasonSyncWorker.UNIQUE_NAME]; B9's device class reads it back. */
class WorkManagerSeasonSync(private val context: Context) : SeasonSyncWork {

    private val workManager: WorkManager get() = WorkManager.getInstance(context.applicationContext)

    /** Pending means a work info that has not finished, as the backstop's seam reads it. */
    override fun isEnqueued(): Boolean =
        workManager.getWorkInfosForUniqueWork(SeasonSyncWorker.UNIQUE_NAME).get().any { !it.state.isFinished }

    override fun ensure(request: SeasonSyncWorkRequest) {
        workManager.enqueueUniquePeriodicWork(SeasonSyncWorker.UNIQUE_NAME, request.existingPolicy, request.periodic())
    }

    override fun cancel() {
        workManager.cancelUniqueWork(SeasonSyncWorker.UNIQUE_NAME)
    }
}

/** [SeasonSyncWorkRequest] as WorkManager's request, field for field. */
internal fun SeasonSyncWorkRequest.periodic(): PeriodicWorkRequest =
    PeriodicWorkRequest.Builder(SeasonSyncWorker::class.java, period)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
        .setBackoffCriteria(backoffPolicy, backoffDelay)
        .build()
