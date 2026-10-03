package com.loosecannon.servicetag.seasonsync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlin.coroutines.cancellation.CancellationException

/**
 * #16 (C22; R16-7) — the periodic season sync: one [SeasonSyncRunner.runAll] per period, under the work
 * [WorkManagerSeasonSync] enqueues. The decision about the result is [SeasonSyncWorkerBody]'s, a plain class, because
 * WorkManager's test harness runs only on a device.
 */
class SeasonSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result = when (SeasonSyncWorkerBody(SeasonSyncDispatch.runner).run()) {
        SeasonSyncWorkResult.SUCCESS -> Result.success()
        SeasonSyncWorkResult.RETRY -> Result.retry()
    }

    companion object {
        /** The unique name (C1): one season sync work per install. */
        const val UNIQUE_NAME = "season-sync"
    }
}

/** What one periodic run asks WorkManager for. */
enum class SeasonSyncWorkResult { SUCCESS, RETRY }

/**
 * #16 (C22) — the worker's body. **Retry only for a local database failure** (a [SeasonSyncDatabaseFailed]: a Room
 * read or write of the pass, the client's stored-connection lookup included), bounded by the work's backoff; never
 * for a network outcome, since every Home Assistant answer and failure is recorded on its binding and the next period
 * reads again (not the backstop's blanket retry, H6). Each other end, decided and pinned:
 * - an unassigned dispatch, a pass that recorded its outcomes, and any other exception: success;
 * - a key store that cannot load ([SeasonSyncKeyStoreFailed]): success, logged without the exception (B4's rule);
 * - cancellation propagates, and an `Error` is never caught.
 * Neither log line attaches the exception: a store failure can carry a file path, and G3 names the step only.
 */
class SeasonSyncWorkerBody(
    private val runner: SeasonSyncRunner?,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    suspend fun run(): SeasonSyncWorkResult {
        val runner = runner ?: return SeasonSyncWorkResult.SUCCESS
        return try {
            runner.runAll()
            SeasonSyncWorkResult.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (_: SeasonSyncDatabaseFailed) {
            log(RUN_FAILED)
            SeasonSyncWorkResult.RETRY
        } catch (_: SeasonSyncKeyStoreFailed) {
            log(KEY_STORE_FAILED)
            SeasonSyncWorkResult.SUCCESS
        } catch (_: Exception) {
            // A recorded silent site (B6a review MINOR-1): a schedule check that failed after the pass, or a throw from
            // the reader outside its transport. No line until a G-list line for it is ratified: letting it escape would
            // hand the exception to WorkManager's log; the stale marker is the visible trace and the next period repeats.
            SeasonSyncWorkResult.SUCCESS
        }
    }

    companion object {
        /** G3: a local database failure, the one retried end. */
        const val RUN_FAILED = "season sync run failed; WorkManager retries it"

        /** G3's shape, for a key store that cannot load: success, so the next period runs the pass again. */
        const val KEY_STORE_FAILED = "the Home Assistant key store failed; the next run repeats it"

        private const val TAG = "SeasonSyncWorker"
    }
}
