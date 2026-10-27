package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.RemoteRead
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.STATUS
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.statusHelper
import com.loosecannon.servicetag.testing.FakeGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #113 (C, I29; row 57d) — a reconcile the start could not complete stays visible to the **ordinary** one-time worker
 * run (a projection, then `drain()`, never reconcile mode). From a verified `UP`, the start's read-back fails:
 * `reconcilePending` is set and `needsDelivery` is not; Home Assistant is reset meanwhile. The worker reads, repairs and
 * clears it; while the read keeps failing it retries until WorkManager's tenth retry, then succeeds; and a run
 * cancelled mid-read leaves it for the next run after a graph rebuild. Room-backed graph, scripted Home Assistant.
 */
class ReconcilePendingTest {

    private val rig = ScenarioRig()
    private val graph get() = rig.graph
    private val ha get() = rig.graph.haPublish

    @After fun close() = rig.close()

    private val unreachable: suspend (String) -> RemoteRead = { RemoteRead.Failed(PublishErrorKind.UNREACHABLE, null) }

    /** Verified `UP` under a PERIODIC rule; the start's read-back fails; then Home Assistant is reset to `DOWN`. */
    private suspend fun ScenarioRig.startsReadBackFailed(): PublishBinding {
        yearRound()
        connect(background = BackgroundChecks.ON)
        graph.backgroundAllowed = true
        ha.helpers[STATUS] = statusHelper("UP")
        val binding = enable(PublishKind.EQUIPMENT_STATUS)
        record(OperationalCondition.OPERATIONAL)
        graph.publishProjector.reconcileDesired(graph.today)
        graph.publishRunner.publishNow(heater)
        assertEquals("UP", delivery(binding).lastVerifiedValue)

        ha.onRead = unreachable
        graph.publishTriggers.startPass()
        assertTrue("reconcilePending set", delivery(binding).reconcilePending)
        assertFalse("needsDelivery not set", delivery(binding).needsDelivery)
        ha.helpers[STATUS] = statusHelper("DOWN") // Home Assistant reset meanwhile
        ha.log.clear()
        return binding
    }

    private fun FakeGraph.worker() = PublishWorkerBody(publishDispatch)

    @Test
    fun anOrdinaryWorkerRunReadsRepairsAndClearsIt() = rig.run {
        val binding = startsReadBackFailed()
        ha.onRead = { ha.answer(it) }

        assertEquals(PublishWorkResult.SUCCESS, graph.worker().once(0))

        assertEquals(listOf("GET $STATUS", "POST $STATUS=UP", "GET $STATUS"), ha.log)
        assertFalse(delivery(binding).reconcilePending)
        assertFalse(delivery(binding).needsDelivery)
    }

    @Test
    fun whileTheReadKeepsFailingTheWorkerRetriesThenSucceedsAtItsTenthRetry() = rig.run {
        val binding = startsReadBackFailed()

        for (attempt in 0 until PublishWorkerBody.MAX_RUN_ATTEMPTS) {
            assertEquals("attempt $attempt", PublishWorkResult.RETRY, graph.worker().once(attempt))
        }
        assertEquals(PublishWorkResult.SUCCESS, graph.worker().once(PublishWorkerBody.MAX_RUN_ATTEMPTS))
        assertEquals(PublishWorkerBody.MAX_RUN_ATTEMPTS + 1, ha.log.count { it == "GET $STATUS" })
        assertTrue("still pending for the periodic work, start, resume or Publish now", delivery(binding).reconcilePending)
        assertEquals("DOWN", ha.state(STATUS))
    }

    @Test
    fun aRunCancelledMidReadLeavesItForTheNextRunAfterARebuild() = rig.run {
        val binding = startsReadBackFailed()
        val reading = CompletableDeferred<Unit>()
        ha.onRead = { reading.complete(Unit); awaitCancellation() }
        coroutineScope {
            val run = launch(Dispatchers.Default) { graph.worker().once(0) }
            reading.await()
            run.cancel()
            run.join()
        }
        assertTrue(delivery(binding).reconcilePending)

        val rebuilt = rebuild()
        rebuilt.backgroundAllowed = true
        assertEquals(PublishWorkResult.SUCCESS, rebuilt.worker().once(0))

        assertEquals(listOf("GET $STATUS", "POST $STATUS=UP", "GET $STATUS"), rebuilt.haPublish.log)
        assertFalse(delivery(binding).reconcilePending)
    }
}
