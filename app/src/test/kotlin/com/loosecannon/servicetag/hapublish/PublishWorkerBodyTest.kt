package com.loosecannon.servicetag.hapublish

import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.RemoteRead
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.CurrentNetwork
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SecretStore
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.STATUS
import com.loosecannon.servicetag.seasonsync.NetworkReading
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.RecordingPublishWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import kotlin.test.assertFailsWith

/**
 * #113/#114 (C32; row 67) — the publish worker's result over the Room-backed graph and a scripted Home Assistant: the
 * one-time work retries TRANSIENT work, bounded by WorkManager's `runAttemptCount`; a NEEDS_OWNER error is success; a
 * database failure retries; any other failure is success with GH2; cancellation propagates; the periodic reconcile is
 * always success; nothing runs outside C30's PERIODIC rule. And the two works' requests, as C32 states them.
 */
class PublishWorkerBodyTest {

    private val rig = PublishRig()
    private val graph get() = rig.graph
    private val ha get() = rig.graph.haPublish
    private val logged = mutableListOf<String>()

    @After fun close() = graph.close()

    private fun FakeGraph.body(dispatch: PublishDispatch? = publishDispatch) = PublishWorkerBody(dispatch) { logged += it }

    /** A status binding owed `UP` while Home Assistant holds `DOWN`, under a rule that answers PERIODIC. */
    private suspend fun PublishRig.owedUpInTheBackground() {
        enableStatus(desired = "UP", remote = "DOWN")
        connect(backgroundChecks = BackgroundChecks.ON)
        graph.backgroundAllowed = true
    }

    // Row 67

    @Test
    fun transientRetriesBoundedByTenAttempts() = rig.run {
        owedUpInTheBackground()
        ha.onRead = { RemoteRead.Failed(PublishErrorKind.UNREACHABLE, null) }

        for (attempt in 0 until PublishWorkerBody.MAX_RUN_ATTEMPTS) {
            assertEquals("attempt $attempt", PublishWorkResult.RETRY, graph.body().once(attempt))
        }
        assertEquals(PublishWorkResult.SUCCESS, graph.body().once(PublishWorkerBody.MAX_RUN_ATTEMPTS))
        assertEquals("every run read the helper", PublishWorkerBody.MAX_RUN_ATTEMPTS + 1, ha.log.count { it == "GET $STATUS" })
        assertEquals(emptyList<String>(), logged)

        ha.onRead = { ha.answer(it) }
        assertEquals(PublishWorkResult.SUCCESS, graph.body().once(0))
        assertEquals("UP", ha.state(STATUS))
    }

    @Test
    fun needsOwnerSucceeds() = rig.run {
        owedUpInTheBackground()
        ha.onCall = { CallOutcome.Refused(PublishErrorKind.WRITE_FORBIDDEN, null) }

        assertEquals(PublishWorkResult.SUCCESS, graph.body().once(0))
        assertEquals(listOf("GET $STATUS", "POST $STATUS=UP"), ha.log)
        assertEquals(emptyList<String>(), logged)
    }

    @Test
    fun aDatabaseFailureRetries() = rig.run {
        owedUpInTheBackground()
        graph.desiredStateReconciler.answer = { throw PublishDatabaseFailed(IllegalStateException("database is locked")) }

        assertEquals(PublishWorkResult.RETRY, graph.body().once(0))
        assertEquals(PublishWorkResult.RETRY, graph.body().once(PublishWorkerBody.MAX_RUN_ATTEMPTS))
        assertEquals(List(2) { "ha publish run failed; WorkManager retries it" }, logged)
        assertTrue("nothing was sent", ha.log.isEmpty())
    }

    @Test
    fun cancellationPropagates() = rig.run {
        owedUpInTheBackground()
        ha.onRead = { throw CancellationException("the work was stopped") }

        assertFailsWith<CancellationException> { graph.body().once(0) }
        assertFailsWith<CancellationException> { graph.body().reconcile() }
        assertEquals(emptyList<String>(), logged)
    }

    @Test
    fun anUnassignedDispatchSucceeds() = rig.run {
        assertEquals(PublishWorkResult.SUCCESS, PublishWorkerBody(null) { logged += it }.once(0))
        assertEquals(PublishWorkResult.SUCCESS, PublishWorkerBody(null) { logged += it }.reconcile())
        assertEquals(emptyList<String>(), logged)
    }

    /**
     * B4a's carry: a key store failure reaches the worker unwrapped — GH2 and success, never a retry; and the dispatch's
     * `requestDelivery()` and Publish now's launch each end in GH2 on their own scope too (B4b2 review M4), so an
     * unguarded throw never reaches a scope with no handler.
     */
    @Test
    fun aKeyStoreFailureIsSuccessLoggedByGh2() = rig.run {
        owedUpInTheBackground()
        val broken = object : SecretStore by graph.secretStore {
            override suspend fun get(key: String): Secret? = throw IllegalStateException("fictional: the key store")
        }
        val runner = PublishRunner(
            graph.publishBindings, graph.publishDeliveries, graph.publishDecommissions, graph.haConnections,
            graph.seasonSyncBindings, broken, graph.assets, graph.transferRecords, graph.seasonActivations,
            graph.installationIdentity::id, ha, BoundaryCacheWriter { _, _, _, _ -> false }, graph.desiredStateReconciler,
            graph.uow, graph.clock, graph.todayPort,
        )
        val dispatch = PublishDispatch(
            runner, graph.desiredStateReconciler, graph.publishWork, graph.haConnections, graph.currentNetworkReader,
            graph.seasonSyncBackgroundAllowed, graph.todayPort, graph.publishScope,
        )

        assertEquals(PublishWorkResult.SUCCESS, graph.body(dispatch).once(0))
        assertEquals(PublishWorkResult.SUCCESS, graph.body(dispatch).reconcile())
        assertEquals(List(2) { PublishWorkerBody.COULD_NOT_RUN }, logged)

        val jobs = SupervisorJob()
        val scope = CoroutineScope(jobs + Dispatchers.Default)
        val guardedDispatch = PublishDispatch(
            runner, graph.desiredStateReconciler, graph.publishWork, graph.haConnections, graph.currentNetworkReader,
            graph.seasonSyncBackgroundAllowed, graph.todayPort, scope, log = { logged += it },
        )
        // The stamp reads the working store; the launched pass meets the broken one.
        val controls = PublishControls(
            graph.enablePublishing, graph.changePublishTarget, graph.disablePublishing, graph.revalidatePublishing,
            graph.dismissDecommission, graph.saveHaConnection, graph.forgetHaConnection, graph.publishBindings,
            graph.publishDeliveries, graph.haConnections, graph.secretStore, graph.assets, graph.transferRecords,
            graph.installationIdentity::id, runner, graph.desiredStateReconciler, graph.todayPort, guardedDispatch,
            graph.uow, graph.ids, graph.clock, scope, log = { logged += it },
        )
        guardedDispatch.requestDelivery()
        assertTrue("Publish now covered the binding", controls.requestPublishNow(heater).bindings.isNotEmpty())
        while (jobs.children.any { it.isActive }) jobs.children.forEach { it.join() }

        assertEquals(List(4) { PublishWorkerBody.COULD_NOT_RUN }, logged)
        assertTrue(logged.none { line -> listOf("192.168.0.10", STATUS, "fictional").any { it in line } })
    }

    @Test
    fun theReconcileWorkReadsEveryHelperBackAndAlwaysSucceeds() = rig.run {
        enableStatus(desired = "UP", remote = "UP")
        connect(backgroundChecks = BackgroundChecks.ON)
        graph.backgroundAllowed = true
        graph.publishRunner.publishNow(heater)
        ha.log.clear()
        ha.helpers[STATUS] = PublishRig.statusHelper("DOWN") // Home Assistant reset

        assertEquals(PublishWorkResult.SUCCESS, graph.body().reconcile())
        assertEquals(listOf("GET $STATUS", "POST $STATUS=UP", "GET $STATUS"), ha.log)

        ha.onRead = { RemoteRead.Failed(PublishErrorKind.UNREACHABLE, null) }
        assertEquals(PublishWorkResult.SUCCESS, graph.body().reconcile())
        graph.desiredStateReconciler.answer = { throw PublishDatabaseFailed(IllegalStateException("database is locked")) }
        assertEquals(PublishWorkResult.SUCCESS, graph.body().reconcile())
        assertEquals(listOf(PublishWorkerBody.COULD_NOT_RUN), logged)
    }

    /** #113: background work honours the connection's setting and its Android grants, whatever is queued. */
    @Test
    fun neitherWorkRequestsAnythingOutsideThePeriodicRule() = rig.run {
        enableStatus(desired = "UP", remote = "DOWN") // background checks Off: FOREGROUND_ONLY
        assertEquals(PublishWorkResult.SUCCESS, graph.body().once(0))
        assertEquals(PublishWorkResult.SUCCESS, graph.body().reconcile())

        connect(backgroundChecks = BackgroundChecks.ON) // PAUSED: Android does not grant the background read
        assertEquals(PublishWorkResult.SUCCESS, graph.body().once(0))
        assertEquals(PublishWorkResult.SUCCESS, graph.body().reconcile())
        assertTrue("nothing was read or sent", ha.log.isEmpty())

        graph.backgroundAllowed = true
        assertEquals(PublishWorkResult.SUCCESS, graph.body().once(0))
        assertEquals("UP", ha.state(STATUS))
    }

    /**
     * R-N4, C32 — the periodic `"ha-publish-reconcile"` follows the rule on every request (B4b2 review I2): ensured at
     * the connection's cadence by the start's check under PERIODIC, re-periodised by a cadence saved through the facade,
     * cancelled when Android's grant is revoked (PAUSED) and when the last binding is disabled (NONE).
     */
    @Test
    fun theWorksFollowTheRuleOnEveryRequest() = rig.run {
        enableStatus(desired = "UP", remote = "UP")
        connect(backgroundChecks = BackgroundChecks.ON)
        graph.backgroundAllowed = true

        graph.publishDispatch.start()
        graph.awaitDispatched()
        val daily = PublishReconcileRequest.forCadence(SyncCadence.DAILY)
        assertEquals(RecordingPublishWork.Call.EnsureReconcile(daily), graph.publishWork.calls.last())
        assertEquals(daily, graph.publishWork.reconcile)

        graph.publishControls.saveConnection(
            "http://192.168.0.10:8123", Secret("fictional-token-1"), SyncCadence.WEEKLY,
            NetworkEligibility.HOME_NETWORK_ONLY, "ExampleHomeWifi", BackgroundChecks.ON,
        )
        graph.awaitDispatched()
        val weekly = PublishReconcileRequest.forCadence(SyncCadence.WEEKLY)
        assertEquals(RecordingPublishWork.Call.EnsureReconcile(weekly), graph.publishWork.calls.last())
        assertEquals(weekly, graph.publishWork.reconcile)

        graph.backgroundAllowed = false // PAUSED
        graph.deliveryRequester.requestDelivery()
        graph.awaitDispatched()
        assertEquals(RecordingPublishWork.Call.Cancel, graph.publishWork.calls.last())
        assertEquals(null, graph.publishWork.reconcile)

        graph.backgroundAllowed = true
        graph.deliveryRequester.requestDelivery()
        graph.awaitDispatched()
        assertEquals(weekly, graph.publishWork.reconcile)
        graph.publishControls.disable(heater, PublishKind.EQUIPMENT_STATUS) // NONE: nothing enabled, nothing owed
        graph.awaitDispatched()
        assertEquals(RecordingPublishWork.Call.Cancel, graph.publishWork.calls.last())
        assertEquals(null, graph.publishWork.reconcile)
    }

    /**
     * C21, C30 — the dispatch's in-process decision is the gate's own rule (B4b2 review M2), away from the home Wi-Fi:
     * an https address in the any-network mode drains in process; in the home-network mode it sends nothing and, under
     * PERIODIC, hands the delivery to the one-time work; an http address sends nothing whatever its setting (fail
     * closed). The scripted Home Assistant does not model the gate, so a GET in its log is an in-process drain.
     */
    @Test
    fun awayFromHomeTheDispatchFollowsTheGatesRule() {
        data class Case(val url: String, val eligibility: NetworkEligibility, val inProcess: Boolean, val queued: Boolean)
        for (case in listOf(
            Case("https://192.168.0.10:8123", NetworkEligibility.ANY_NETWORK, inProcess = true, queued = false),
            Case("https://192.168.0.10:8123", NetworkEligibility.HOME_NETWORK_ONLY, inProcess = false, queued = true),
            Case("http://192.168.0.10:8123", NetworkEligibility.ANY_NETWORK, inProcess = false, queued = true),
        )) {
            val away = PublishRig()
            try {
                away.run {
                    asset()
                    // Saved as the owner may; an http address in the any-network mode only as a restored row can hold it.
                    val saved = graph.saveHaConnection.run(
                        case.url, Secret("fictional-token-1"), null, NetworkEligibility.HOME_NETWORK_ONLY, "ExampleHomeWifi",
                        BackgroundChecks.ON,
                    )
                    graph.haConnections.upsert(saved.copy(networkEligibility = case.eligibility))
                    graph.backgroundAllowed = true // PERIODIC
                    graph.haPublish.helpers[STATUS] = PublishRig.statusHelper("DOWN")
                    val binding = graph.enablePublishing.run(
                        heater, PublishKind.EQUIPMENT_STATUS, STATUS, fresh(STATUS, PublishKind.EQUIPMENT_STATUS), null,
                        graph.installationIdentity.id(),
                    )
                    check(graph.publishDeliveries.writeDesired(binding.id, binding.revision, 0, "UP", graph.now))
                    graph.currentNetwork = NetworkReading(CurrentNetwork.Wifi("ExampleCafeWifi"), null)
                    graph.publishWork.calls.clear()

                    graph.deliveryRequester.requestDelivery()
                    graph.awaitDispatched()

                    assertEquals(case.toString(), case.inProcess, "GET $STATUS" in graph.haPublish.log)
                    assertEquals(case.toString(), case.queued, RecordingPublishWork.Call.EnqueueOnce in graph.publishWork.calls)
                }
            } finally {
                away.graph.close()
            }
        }
    }

    // C32 — the two requests

    @Test
    fun theOneTimeWorkIsConnectedExponentialFromThirtySecondsAndAppendOrReplace() {
        val once = PublishOnceRequest.C32
        assertEquals(NetworkType.CONNECTED, once.networkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, once.backoffPolicy)
        assertEquals(Duration.ofSeconds(30), once.backoffDelay)
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, once.existingPolicy)
        assertEquals("ha-publish", PublishWorker.ONCE_NAME)
    }

    @Test
    fun theReconcileWorkRunsAtTheCadenceConnectedAndUpdatesItsPeriod() {
        for (cadence in SyncCadence.entries) {
            val request = PublishReconcileRequest.forCadence(cadence)
            assertEquals(Duration.ofHours(cadence.hours), request.period)
            assertEquals(NetworkType.CONNECTED, request.networkType)
            assertEquals(ExistingPeriodicWorkPolicy.UPDATE, request.existingPolicy)
        }
        assertEquals("ha-publish-reconcile", PublishWorker.RECONCILE_NAME)
    }
}
