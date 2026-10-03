package com.loosecannon.servicetag.seasonsync

import android.util.Log
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome
import com.loosecannon.servicetag.core.seasonsync.HaStateReader
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.RecordSeasonSyncResult
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncRepository
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncScheduler
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncState
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SecretStore
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.core.seasonsync.currentSeasonSyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * #16 (C22; R16-20 as amended) — what the periodic work should be, as [SeasonSyncRunner] last applied it:
 * - [PERIODIC]: enqueued at the cadence — "Any network", or the home network with background checks On and the grant;
 * - [FOREGROUND_ONLY]: the home network with background checks Off — no work; the cadence sets only staleness;
 * - [PAUSED]: background checks On without the grant Android needs — no work, Off's behaviour, and the phone's
 *   paused status (P16-75);
 * - [NONE]: no connection, or no enabled binding.
 */
enum class SeasonSyncSchedule { PERIODIC, FOREGROUND_ONLY, PAUSED, NONE }

/** C22's rule over the stored connection, whether any binding is enabled, and whether Android grants a background read. */
fun seasonSyncScheduleOf(connection: HaConnection?, anyEnabled: Boolean, backgroundAllowed: Boolean): SeasonSyncSchedule {
    if (connection == null || !anyEnabled) return SeasonSyncSchedule.NONE
    return when (connection.networkEligibility) {
        NetworkEligibility.ANY_NETWORK -> SeasonSyncSchedule.PERIODIC
        NetworkEligibility.HOME_NETWORK_ONLY -> when (connection.backgroundChecks) {
            BackgroundChecks.OFF -> SeasonSyncSchedule.FOREGROUND_ONLY
            BackgroundChecks.ON -> if (backgroundAllowed) SeasonSyncSchedule.PERIODIC else SeasonSyncSchedule.PAUSED
        }
    }
}

/**
 * Why a pass stopped short, by the store that failed, so the worker's result is decided by type (C22). No message,
 * and the log lines never attach one: the cause can name a file, and G3 names the step only.
 */
sealed class SeasonSyncPassFailed(cause: Throwable) : Exception(null, cause)

/** A Room read or write of the pass, the client's stored-connection lookup included: the worker retries it. */
class SeasonSyncDatabaseFailed(cause: Throwable) : SeasonSyncPassFailed(cause)

/** The token store itself failed (a key store that cannot load, B4): never a retry. */
class SeasonSyncKeyStoreFailed(cause: Throwable) : SeasonSyncPassFailed(cause)

/** What one pass read: each request's outcome (or NEEDS_TOKEN, sent nowhere), in binding order. */
data class SeasonSyncPass(val outcomes: List<HaReadOutcome>)

/**
 * #16 (C21; R16-7, R16-Q-D as amended) — **the one poller.** One per graph, one [Mutex]: a single flight, so two
 * requests are never in flight at once. For each binding a pass snapshots `(binding, revision, the stored address,
 * the token)`, asks [reader], and hands the answer to [record] at that revision. Only an ACTIVE binding is read
 * (C17); a stopped one, one whose token is not on this phone, or one whose asset is not maintained here is neither
 * read nor written. An ACTIVE one whose token cannot be decrypted records NEEDS_TOKEN and sends nothing.
 *
 * Correctness never rests on the lock: the applier's revision guard drops an old result after an edit, a stop or a
 * newer read (C13). The lock only spares duplicate GETs.
 *
 * It is also the commands' [SeasonSyncScheduler] (B3c): [ensure] applies C22's rule, so a command that leaves a
 * binding enabled still cancels the work when the connection says foreground only; [requestFreshRead] launches one
 * asset's read on [scope] and returns.
 *
 * [log] takes G5's line when a launched pass fails (C33(6)); `AppGraph` keeps the default, a JVM test records it.
 */
class SeasonSyncRunner(
    private val bindings: SeasonSyncRepository,
    private val connections: HaConnectionRepository,
    secrets: SecretStore,
    private val assets: AssetRepository,
    private val transfers: TransferRecordRepository,
    private val reader: HaStateReader,
    private val record: RecordSeasonSyncResult,
    private val work: SeasonSyncWork,
    private val backgroundAllowed: () -> Boolean,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val io: CoroutineContext = Dispatchers.IO,
    private val log: (String) -> Unit = { Log.w("SeasonSyncRunner", it) },
) : SeasonSyncScheduler {

    private val lock = Mutex()

    /** Serializes every read-and-act on the periodic work; never held with [lock], so the two cannot deadlock. */
    private val scheduleLock = Mutex()

    /** The store, each call's failure typed as the store's (a key store that cannot load is loud, B4). */
    private val secrets: SecretStore = KeyStoreSteps(secrets)

    /**
     * **Sync now** (explicit): waits for a running pass, then reads [assetId]'s binding, or every active binding,
     * whatever their staleness; the eligibility still applies to each request (C19).
     */
    suspend fun syncNow(assetId: AssetId? = null): SeasonSyncPass = lock.withLock {
        pass { assetId == null || it.assetId == assetId }
    }

    /**
     * The resume hook's refresh (C23): unless a pass already holds the lock, which is fresh by definition, it reads
     * each active binding that is **stale** — no success yet, the last one at least one cadence old
     * ([HaConnection.cadence]), or one dated after [now] (a clock moved back). A failure never freshens. Then, as
     * [runAll] does, it re-reads the grant and corrects the work if it drifted, so a failing schedule check never
     * stops the read. At most one attempt per resume; answers null when it skipped the read.
     */
    suspend fun refreshIfStale(now: Long): SeasonSyncPass? {
        val pass = if (lock.tryLock()) {
            try {
                staleOnes(now)
            } finally {
                lock.unlock()
            }
        } else {
            null
        }
        reconcile(onlyWhenDrifted = true)
        return pass
    }

    private suspend fun staleOnes(now: Long): SeasonSyncPass {
        val cadence = database { connections.get() }?.let { Duration.ofHours(it.cadence.hours).toMillis() }
            ?: return SeasonSyncPass(emptyList())
        return pass { binding -> binding.lastSuccessAt.let { it == null || now < it || now - it >= cadence } }
    }

    /**
     * The worker's run: waits for the lock and reads every active binding. In the home-network mode each request
     * still passes the client's network check first, so a different or unconfirmed network sends nothing and records
     * its cause (C19 step 1a). Then the grant is re-read: withdrawn, the work is cancelled and the connection falls back
     * to foreground-only behaviour (R16-20 as amended).
     */
    suspend fun runAll(): SeasonSyncPass {
        val pass = lock.withLock { pass { true } }
        reconcile(onlyWhenDrifted = true)
        return pass
    }

    /** Applies C22's rule to the periodic work from the stored connection: enqueue (UPDATE) or cancel. */
    suspend fun reconcile(): SeasonSyncSchedule = reconcile(onlyWhenDrifted = false)

    override suspend fun ensure() {
        reconcile(onlyWhenDrifted = false)
    }

    override suspend fun cancel() {
        scheduleLock.withLock { withContext(io) { work.cancel() } }
    }

    override suspend fun requestFreshRead(assetId: AssetId) {
        launchQuietly { syncNow(assetId) }
    }

    /** [refreshIfStale] at this moment, launched on [scope]: what the graph's [ResumeRefresh] calls. */
    fun launchRefreshIfStale() {
        launchQuietly { refreshIfStale(clock.nowMillis()) }
    }

    private suspend fun pass(wanted: (SeasonSyncBinding) -> Boolean): SeasonSyncPass {
        val connection = database { connections.get() } ?: return SeasonSyncPass(emptyList())
        val outcomes = mutableListOf<HaReadOutcome>()
        for (binding in database { bindings.all() }) {
            if (!wanted(binding) || state(binding) != SeasonSyncState.ACTIVE) continue
            val startedAt = clock.nowMillis()
            val outcome = when (val token: Secret? = secrets.get(binding.connectionId)) {
                null -> HaReadOutcome.NoDecision(SyncErrorKind.NEEDS_TOKEN, null)
                else -> reader.read(connection.baseUrl, binding.entityId, token)
            }
            val fetchedAt = clock.nowMillis()
            database { record.run(binding.assetId, binding.revision, startedAt, fetchedAt, outcome) }
            outcomes += outcome
        }
        return SeasonSyncPass(outcomes)
    }

    private suspend fun state(binding: SeasonSyncBinding): SeasonSyncState =
        database { currentSeasonSyncState(binding, secrets, assets, transfers) }

    /**
     * Under [scheduleLock], so a resume's or a run's drift check that read the old row can never act after a command's
     * own check (a stray work after Forget or the last Stop, a cancelled one after a switch to periodic).
     */
    private suspend fun reconcile(onlyWhenDrifted: Boolean): SeasonSyncSchedule = scheduleLock.withLock {
        val connection = database { connections.get() }
        val schedule = seasonSyncScheduleOf(connection, database { bindings.anyEnabled() }, backgroundAllowed())
        withContext(io) {
            when (schedule) {
                SeasonSyncSchedule.PERIODIC -> if (connection != null && (!onlyWhenDrifted || !work.isEnqueued())) {
                    work.ensure(SeasonSyncWorkRequest.forCadence(connection.cadence))
                }
                SeasonSyncSchedule.FOREGROUND_ONLY, SeasonSyncSchedule.PAUSED, SeasonSyncSchedule.NONE ->
                    if (!onlyWhenDrifted || work.isEnqueued()) work.cancel()
            }
        }
        schedule
    }

    /**
     * A foreground pass, off the caller — a command's fresh read and the resume refresh: [scope] carries no handler,
     * so every failure stops here, logged by G5's line alone. The binding's status is what the owner sees, and the
     * next resume, Sync now or period repeats the pass.
     */
    private fun launchQuietly(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                log(CHECK_NOT_RUN)
            }
        }
    }
}

/**
 * #16 (C19, C22) — the client's stored-connection lookup for `AppGraph`: a database read, so its failure is a
 * [SeasonSyncDatabaseFailed] and the worker retries it (B5's review: the client's one throwing path).
 */
fun storedHaConnection(connections: HaConnectionRepository): suspend () -> HaConnection? =
    { database { connections.get() } }

/**
 * #16 (C18, C22) — the start-up, launched by `ServiceTagApp.onCreate` off the main thread before any activity exists:
 * the orphan sweep, then the periodic work from the stored connection. The sweep's ids are read **under the store's
 * lock and through a read, never a write** — link and resume ask the store inside their write, so a write here would
 * wait on theirs while holding the lock theirs waits for; and a save's token, put after its commit, waits for the
 * sweep and is never swept. A sweep failure is logged by G3's line without the exception; a failed schedule check is
 * logged by G5's, also without it, and repeated by the next start, resume or command.
 */
suspend fun startSeasonSync(
    store: KeystoreSecretStore,
    connections: HaConnectionRepository,
    uow: UnitOfWork,
    runner: SeasonSyncRunner,
    log: (String) -> Unit,
) {
    try {
        store.sweepOrphans { uow.read { setOfNotNull(connections.get()?.id) } }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        log(KEY_SWEEP_FAILED)
    }
    try {
        runner.reconcile()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        log(CHECK_NOT_RUN)
    }
}

/** G3: the start-up sweep's line. */
const val KEY_SWEEP_FAILED = "the Home Assistant key sweep failed; the next start repeats it"

/**
 * G5 (R16-Q-J): the one line of a check that failed where nothing else would say so — a command's fresh read, the
 * resume refresh, the start-up schedule check and the worker's other ends. The line alone: no address, host, entity,
 * Wi-Fi name, token or exception.
 */
const val CHECK_NOT_RUN =
    "a Home Assistant check could not run; it will retry on the next resume, scheduled run, or app start"

private suspend fun <T> database(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: SeasonSyncPassFailed) {
    throw e
} catch (e: Exception) {
    throw SeasonSyncDatabaseFailed(e)
}

/** The port, each call's failure a [SeasonSyncKeyStoreFailed]. */
private class KeyStoreSteps(private val store: SecretStore) : SecretStore {
    override suspend fun put(key: String, secret: Secret) = step { store.put(key, secret) }

    override suspend fun get(key: String): Secret? = step { store.get(key) }

    override suspend fun has(key: String): Boolean = step { store.has(key) }

    override suspend fun delete(key: String) = step { store.delete(key) }

    override suspend fun keys(): Set<String> = step { store.keys() }

    private suspend fun <T> step(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SeasonSyncKeyStoreFailed(e)
    }
}
