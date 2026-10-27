package com.loosecannon.servicetag.seasonsync

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.RecordingSeasonSyncWork
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.scheduleOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A case that holds a read on a gate: it fails within 20 s, never hangs, whatever the runner does. */
private fun gated(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000L) { block() } }

/**
 * #129 (L-C1, L-C3; row k10; hazards Z2, Z3) — Remove link against the runner over the Room-backed graph and the
 * scripted reader: the work follows C22 after the unlink, an enabled #125 schedule binding keeping it; a read in
 * flight when the binding goes records nothing; and a read of the old helper that lands after a relink to another
 * helper, at the reused revision 1, is dropped by the entity guard. The shipped #16 runner tests stay unmodified.
 * Every asset, helper, address and token is fictional.
 */
class SeasonUnlinkRunnerTest {

    private val graphs = mutableListOf<FakeGraph>()

    private fun graph(): FakeGraph = FakeGraph().also { graphs += it }

    @After fun tearDown() = graphs.forEach { it.close() }

    /** A `YEAR_ROUND` asset's Season Start schedule [id] following its own fictional switch (#125). */
    private suspend fun FakeGraph.followingSchedule(id: String): ScheduleId {
        val asset = "heater-$id"
        assets.upsert(assetRow(asset, name = "Example Heater $id"))
        uow.write { schedules.upsert(scheduleOf(id, assetId = asset, title = "Example season start $id")) }
        linkScheduleSeason.run(ScheduleId(id), "input_boolean.example_${id}_winterize_season")
        settle()
        return ScheduleId(id)
    }

    private suspend fun eventually(what: String, done: suspend () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < until) { "never: $what" }
            delay(20)
        }
    }

    // (a)
    @Test fun unlinkingTheLastEnabledBindingCancelsTheWork() = runBlocking {
        val graph = graph()
        graph.connect(eligibility = NetworkEligibility.ANY_NETWORK)
        val heater = graph.heater("a")
        assertNotNull("linked on any network: the periodic work is enqueued", graph.seasonSyncWork.enqueued)

        assertNotNull(graph.unlinkSeasonSync.run(heater))
        graph.settle()

        assertNull(graph.seasonSyncBindings.get(heater))
        assertNull("the last enabled binding gone: the work is cancelled", graph.seasonSyncWork.enqueued)
        assertEquals(RecordingSeasonSyncWork.Call.Cancel, graph.seasonSyncWork.calls.last())
    }

    // (b)
    @Test fun anEnabledScheduleBindingKeepsTheWorkThroughTheUnlink() = runBlocking {
        val graph = graph()
        graph.connect(eligibility = NetworkEligibility.ANY_NETWORK)
        val heater = graph.heater("a")
        graph.followingSchedule("start")
        assertNotNull(graph.seasonSyncWork.enqueued)

        graph.unlinkSeasonSync.run(heater)
        graph.settle()

        assertNull("the asset's binding is gone", graph.seasonSyncBindings.get(heater))
        assertTrue(graph.scheduleSeasonBindings.anyEnabled())
        assertNotNull("C22 over both kinds: the schedule binding keeps the work", graph.seasonSyncWork.enqueued)
    }

    // (c) — pin / characterisation, no RED expected: the applier already drops a read for a missing binding.
    @Test fun aReadInFlightWhenTheBindingIsRemovedRecordsNothing() = gated {
        val graph = graph()
        graph.connect()
        val heater = graph.heater("a")
        val inside = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        graph.haStateReader.answer = {
            inside.complete(Unit)
            gate.await()
            HaReadOutcome.Observed(HaSwitchState.ON, null)
        }

        val read = async(Dispatchers.Default) { graph.seasonSyncRunner.syncNow(heater) }
        inside.await()
        graph.unlinkSeasonSync.run(heater)
        gate.complete(Unit)
        read.await()
        graph.settle()

        assertNull("the binding stays gone", graph.seasonSyncBindings.get(heater))
        assertTrue("an ON over an out-of-season asset wrote no START", graph.seasonActivations.forAsset(heater).isEmpty())
        assertEquals(SeasonMode.MANUAL, graph.assets.get(heater)!!.seasonMode)
    }

    // (d)
    @Test fun anOldHelpersAnswerAfterARelinkToAnotherHelperIsDropped() = gated {
        val graph = graph()
        graph.connect()
        val heater = AssetId("a")
        graph.assets.upsert(assetRow(heater.value, name = "Example Heater a", seasonMode = SeasonMode.MANUAL))
        val insideA = CompletableDeferred<Unit>()
        val gateA = CompletableDeferred<Unit>()
        val insideB = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        graph.haStateReader.answer = { entity ->
            if (entity == HELPER_A) {
                insideA.complete(Unit)
                gateA.await()
                HaReadOutcome.Observed(HaSwitchState.ON, null)
            } else {
                insideB.complete(Unit)
                gateB.await()
                HaReadOutcome.Observed(HaSwitchState.OFF, null)
            }
        }

        graph.linkSeasonSync.run(heater, HELPER_A)
        insideA.await()
        graph.unlinkSeasonSync.run(heater)
        val relinked = graph.linkSeasonSync.run(heater, HELPER_B).binding
        assertEquals("a relink starts again at revision 1", 1L, relinked.revision)

        gateA.complete(Unit)
        insideB.await() // the pass that read A has recorded and let go of the lock; B's own read now holds it

        val b = graph.binding("a")
        assertEquals(HELPER_B, b.entityId)
        assertEquals("A's answer was dropped: B's status untouched", relinked, b)
        assertNull(b.observedState)
        assertTrue("no row from A's ON", graph.seasonActivations.forAsset(heater).isEmpty())

        gateB.complete(Unit)
        eventually("B's own read is recorded") { graph.binding("a").observedState == HaSwitchState.OFF }
        graph.settle()
        assertTrue("B's OFF over an out-of-season asset writes nothing", graph.seasonActivations.forAsset(heater).isEmpty())
    }

    private companion object {
        const val HELPER_A = "input_boolean.example_heater_in_season"
        const val HELPER_B = "input_boolean.example_heater_season"
    }
}
