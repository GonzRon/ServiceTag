package com.loosecannon.servicetag.hapublish

import android.util.Log
import com.loosecannon.servicetag.core.hapublish.DeliveryTrigger
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.seasonsync.EndpointCheck
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.HaEndpointPolicy
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.eligibleNow
import com.loosecannon.servicetag.seasonsync.CurrentNetworkReader
import com.loosecannon.servicetag.seasonsync.SeasonSyncSchedule
import com.loosecannon.servicetag.seasonsync.homeNetworkRequired
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * #113/#114 (A03, C32) — **the dispatch**: the app's [DeliveryRequester], and the two works' rule.
 *
 * [requestDelivery] is the one post-commit call — every publishing control's (through `PublishControls`) and the
 * projector observer's — idempotent, cheap and never awaited. It launches on [scope]:
 * 1. the works' rule ([reschedule]): C30's schedule ([PublishRunner.schedule], #16's rule over "any binding enabled or
 *    any tombstone pending" and the background grant) — `PERIODIC` ensures the periodic reconcile at the connection's
 *    cadence; `FOREGROUND_ONLY`, `PAUSED` and `NONE` cancel both works, so no background request is ever left queued;
 * 2. then, **when the connection's network rule allows a request now** ([allowsRequestNow]: any network, or the
 *    captured home Wi-Fi in the home-network mode or for an `http` address, as the request gate reads it), an
 *    in-process [PublishRunner.drain], single flight behind the runner's lock; **otherwise**, or when that drain leaves
 *    TRANSIENT work, the one-time work ([PublishWork.enqueueOnce]) and its backoff — only when the rule answers
 *    `PERIODIC`. It never launches a drain of its own afterwards (no self-trigger, C): under `FOREGROUND_ONLY` the work
 *    waits for start, resume, the midnight tick, Publish now or the next control commit.
 *
 * The in-process passes of the start, resume and midnight triggers go through [readBack] and [drainNow], under the
 * same network rule and hand-off. The workers' passes are here too, reached through [HaPublishDispatch]: [oncePass]
 * and [reconcilePass], each only while the rule answers `PERIODIC` (#113: background work honours the connection's
 * setting and its Android grants).
 */
class PublishDispatch(
    private val runner: PublishRunner,
    private val reconciler: DesiredStateReconciler,
    private val work: PublishWork,
    private val connections: HaConnectionRepository,
    private val currentNetwork: CurrentNetworkReader,
    private val backgroundAllowed: () -> Boolean,
    private val today: Today,
    private val scope: CoroutineScope,
    private val io: CoroutineContext = Dispatchers.IO,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) : DeliveryRequester {

    /**
     * Serializes [reschedule]'s read-and-act on the two works, so two of them never interleave. A hand-off
     * ([drainNow], [readBack]) acts on a rule read outside it, so a drain begun under PERIODIC may still enqueue the
     * one-time work after a newer request cancelled both; that run re-reads the rule ([oncePass]) and sends nothing.
     */
    private val scheduleLock = Mutex()

    override fun requestDelivery() {
        scope.launch { guarded { deliver() } }
    }

    /** Launches [reschedule] and returns: the start's check (`ServiceTagApp`). */
    fun start() {
        scope.launch { guarded { reschedule() } }
    }

    /** [requestDelivery]'s body, awaited. */
    internal suspend fun deliver() {
        reschedule()
        drainNow(DeliveryTrigger.CHANGE)
    }

    /**
     * Reconcile mode for the start and resume passes (C31, F-3): every enabled helper read back in process while the
     * network rule allows a request now; otherwise nothing is read and, while the rule answers PERIODIC, the one-time
     * work takes the delivery. TRANSIENT work a read-back leaves goes the same way. Null when nothing was read. Work
     * handed off away from home runs on the same network (`CONNECTED` is met), so the gate refuses it and the pass
     * records `NOT_ON_LOCAL_NETWORK`, a WAIT error the next start, resume, commit or periodic reconcile retries (C32).
     */
    suspend fun readBack(): PublishPass? {
        val schedule = runner.schedule(backgroundAllowed())
        val connection = stored() ?: return null
        if (!allowsRequestNow(connection)) {
            handOff(schedule)
            return null
        }
        return runner.publishNow(null).also { if (it.transientWorkRemains) handOff(schedule) }
    }

    /** A drain under [trigger] for the resume and midnight passes, under the same rule as [readBack]. */
    suspend fun drainNow(trigger: DeliveryTrigger) {
        val schedule = runner.schedule(backgroundAllowed())
        val connection = stored() ?: return
        if (!allowsRequestNow(connection) || runner.drain(trigger)) handOff(schedule)
    }

    /** C32 — the works' rule, applied now: the periodic reconcile ensured, or both works cancelled. */
    suspend fun reschedule(): SeasonSyncSchedule = scheduleLock.withLock {
        val connection = stored()
        val schedule = runner.schedule(backgroundAllowed())
        withContext(io) {
            when (schedule) {
                SeasonSyncSchedule.PERIODIC ->
                    connection?.let { work.ensureReconcile(PublishReconcileRequest.forCadence(it.cadence)) }
                SeasonSyncSchedule.FOREGROUND_ONLY, SeasonSyncSchedule.PAUSED, SeasonSyncSchedule.NONE -> work.cancel()
            }
        }
        schedule
    }

    /** The one-time work's pass: a projection, then a scheduled drain; true when TRANSIENT work remains. */
    suspend fun oncePass(): Boolean {
        if (!backgroundAllowedByTheRule()) return false
        reconciler.reconcileDesired(today.localDate())
        return runner.drain(DeliveryTrigger.SCHEDULED)
    }

    /** The periodic work's pass: a projection, reconcile mode (every enabled helper read back), then a drain. */
    suspend fun reconcilePass() {
        if (!backgroundAllowedByTheRule()) return
        reconciler.reconcileDesired(today.localDate())
        runner.publishNow(null)
        runner.drain(DeliveryTrigger.SCHEDULED)
    }

    private suspend fun backgroundAllowedByTheRule(): Boolean = when (runner.schedule(backgroundAllowed())) {
        SeasonSyncSchedule.PERIODIC -> true
        SeasonSyncSchedule.FOREGROUND_ONLY, SeasonSyncSchedule.PAUSED, SeasonSyncSchedule.NONE -> false
    }

    private suspend fun handOff(schedule: SeasonSyncSchedule) = when (schedule) {
        SeasonSyncSchedule.PERIODIC -> withContext(io) { work.enqueueOnce() }
        SeasonSyncSchedule.FOREGROUND_ONLY, SeasonSyncSchedule.PAUSED, SeasonSyncSchedule.NONE -> Unit
    }

    /**
     * The connection's network rule, now: any network in the any-network mode for an https address; the captured
     * home Wi-Fi in the home-network mode **or for any `http` address whatever the stored setting** — the request
     * gate's own [homeNetworkRequired] (C21). An address the rule refuses allows nothing. The gate still checks every
     * request.
     */
    private suspend fun allowsRequestNow(connection: HaConnection): Boolean {
        val scheme = when (val check = HaEndpointPolicy.classify(connection.baseUrl)) {
            is EndpointCheck.Allowed -> check.endpoint.scheme
            is EndpointCheck.Refused -> return false
        }
        if (!homeNetworkRequired(scheme, connection.networkEligibility)) return true
        val asHome = connection.copy(networkEligibility = NetworkEligibility.HOME_NETWORK_ONLY)
        return eligibleNow(asHome, currentNetwork.read().network)
    }

    private suspend fun stored(): HaConnection? = try {
        connections.get()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw PublishDatabaseFailed(e)
    }

    /** [scope] carries no handler: every failure stops here, logged by GH2 alone; the next trigger repeats it. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            log(PublishWorkerBody.COULD_NOT_RUN)
        }
    }

    private companion object {
        const val TAG = "HaPublish"
    }
}
