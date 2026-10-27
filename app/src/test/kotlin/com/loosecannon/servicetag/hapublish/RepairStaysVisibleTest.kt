package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.RemoteRead
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.STATUS
import com.loosecannon.servicetag.testing.FakeGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #113 (A05, I23; row 38a) — a repair that did not complete stays visible to the next ordinary drain. From a verified
 * `UP`, Home Assistant is reset to `DOWN`; Publish now's repair fails — before transmission, with an unknown outcome,
 * with a failing read-back, or cancelled after its write-ahead and followed by a graph rebuild — and then **only an
 * ordinary `drain()`**, no reconcile and no source change, puts `UP` back. Room-backed graph, scripted Home Assistant.
 */
class RepairStaysVisibleTest {

    private var rig = PublishRig()

    @After fun close() = rig.graph.close()

    /** A status binding verified `UP` at its current pair, then Home Assistant reset to `DOWN`. */
    private suspend fun PublishRig.verifiedThenReset(): PublishBinding {
        val binding = enableStatus(desired = "UP", remote = "UP")
        graph.publishRunner.publishNow(heater)
        assertEquals("UP", delivery(binding).lastVerifiedValue)
        assertTrue(graph.haPublish.posts.isEmpty())
        graph.haPublish.helpers[STATUS] = PublishRig.statusHelper("DOWN")
        return binding
    }

    /** Home Assistant answers again; one ordinary drain must repair the helper. */
    private suspend fun PublishRig.anOrdinaryDrainRepairs() {
        graph.haPublish.onRead = { graph.haPublish.answer(it) }
        graph.haPublish.onCall = { graph.haPublish.act(it) }
        graph.publishRunner.drain()
        assertEquals("UP", graph.haPublish.state(STATUS))
    }

    @Test
    fun aRepairRefusedBeforeTransmissionIsRepairedByAnOrdinaryDrain() = rig.run {
        verifiedThenReset()
        graph.haPublish.onCall = { CallOutcome.Refused(PublishErrorKind.UNREACHABLE, null) }
        graph.publishRunner.publishNow(heater)
        assertEquals("DOWN", graph.haPublish.state(STATUS))

        anOrdinaryDrainRepairs()
    }

    @Test
    fun aRepairWithAnUnknownOutcomeIsRepairedByAnOrdinaryDrain() = rig.run {
        verifiedThenReset()
        graph.haPublish.onCall = { CallOutcome.Unknown(null) }
        graph.publishRunner.publishNow(heater)
        assertEquals("DOWN", graph.haPublish.state(STATUS))

        anOrdinaryDrainRepairs()
    }

    @Test
    fun aRepairWhoseReadBackFailsIsRepairedByAnOrdinaryDrain() = rig.run {
        verifiedThenReset()
        var posted = false
        graph.haPublish.onCall = { posted = true; CallOutcome.Accepted } // accepted, yet the helper did not move
        graph.haPublish.onRead = {
            if (posted) RemoteRead.Failed(PublishErrorKind.UNREACHABLE, null) else graph.haPublish.answer(it)
        }
        graph.publishRunner.publishNow(heater)
        assertEquals("DOWN", graph.haPublish.state(STATUS))

        anOrdinaryDrainRepairs()
    }

    @Test
    fun aRepairCancelledAfterItsWriteAheadIsRepairedAfterARebuild() = rig.run {
        verifiedThenReset()
        val writing = CompletableDeferred<Unit>()
        graph.haPublish.onCall = { writing.complete(Unit); awaitCancellation() }
        coroutineScope {
            val repair = launch(Dispatchers.Default) { graph.publishRunner.publishNow(heater) }
            writing.await()
            repair.cancel()
            repair.join()
        }
        assertEquals("DOWN", graph.haPublish.state(STATUS))

        // The process dies: the same database, token store and installation, a new graph; HA still holds DOWN.
        val before = graph
        val rebuilt = PublishRig(FakeGraph(db = before.db, secretStore = before.secretStore, installationDir = before.installationDir))
        rebuilt.graph.haPublish.helpers.putAll(before.haPublish.helpers)
        rig = rebuilt

        rebuilt.anOrdinaryDrainRepairs()
        assertEquals(listOf("GET $STATUS", "POST $STATUS=UP", "GET $STATUS"), rebuilt.graph.haPublish.log)
    }
}
