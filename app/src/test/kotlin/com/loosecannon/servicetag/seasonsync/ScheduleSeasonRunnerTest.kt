package com.loosecannon.servicetag.seasonsync

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonBinding
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.scheduleOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** A case that holds a read on a gate: it fails within 20 s, never hangs, whatever the runner does. */
private fun gated(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000L) { block() } }

/**
 * #125 (U-C5; row h12; hazard Z3) — the runner's schedule pass, over the Room-backed graph and the scripted reader: a
 * schedule that follows its own Home Assistant switch is polled beside the asset bindings, behind the one lock, under
 * #16's stale rule and work rule. The shipped #16 runner, worker and scenario tests stay green unchanged. Every asset,
 * entity, address and token is fictional.
 */
class ScheduleSeasonRunnerTest {

    private val graphs = mutableListOf<FakeGraph>()

    private fun graph(): FakeGraph = FakeGraph().also { graphs += it }

    @After fun tearDown() = graphs.forEach { it.close() }

    /** The winterize switch of the fictional heater [id]. */
    private fun switchOf(id: String): String = "input_boolean.example_${id}_winterize_season"

    /** A `YEAR_ROUND` "Example Heater" with a Season Start schedule [id] that follows its own switch. */
    private suspend fun FakeGraph.followingSchedule(id: String): ScheduleId {
        val asset = "heater-$id"
        assets.upsert(assetRow(asset, name = "Example Heater $id"))
        uow.write { schedules.upsert(scheduleOf(id, assetId = asset, title = "Example season start $id")) }
        linkScheduleSeason.run(ScheduleId(id), switchOf(id))
        return ScheduleId(id)
    }

    private suspend fun FakeGraph.scheduleBinding(id: String): ScheduleSeasonBinding =
        checkNotNull(scheduleSeasonBindings.get(ScheduleId(id)))

    @Test fun onePassReadsTheAssetAndTheScheduleBindings() = runBlocking {
        val graph = graph()
        graph.connect()
        graph.heater("a")
        val start = graph.followingSchedule("start")
        graph.haStateReader.answer = { entity ->
            if (entity == switchOf("start")) HaReadOutcome.Observed(HaSwitchState.ON, null) else HaReadOutcome.Observed(HaSwitchState.OFF, null)
        }

        val pass = graph.seasonSyncRunner.runAll()

        assertEquals(listOf(entityOf("a"), switchOf("start")), graph.readEntities())
        assertEquals(2, pass.outcomes.size)
        val binding = graph.scheduleBinding("start")
        assertEquals(HaSwitchState.ON, binding.observedState)
        assertEquals(graph.now, binding.lastSuccessAt)
        val rows = graph.scheduleSeasonActivations.forSchedule(start)
        assertEquals("ON starts the schedule's own season, dated the apply day", listOf(SeasonAction.START), rows.map { it.action })
        assertEquals(graph.today.toString(), rows.single().occurredOn)
        assertTrue("the asset's own season is untouched", graph.seasonActivations.forAsset(AssetId("heater-start")).isEmpty())

        graph.haStateReader.reads.clear()
        graph.seasonSyncRunner.syncNow()
        assertEquals("Sync now with no asset reads both kinds", listOf(entityOf("a"), switchOf("start")), graph.readEntities())
        graph.haStateReader.reads.clear()
        graph.seasonSyncRunner.syncNow(AssetId("a"))
        assertEquals("an asset's Sync now reads that asset only", listOf(entityOf("a")), graph.readEntities())
        graph.haStateReader.reads.clear()
        graph.seasonSyncRunner.syncSchedule(start)
        assertEquals("a schedule's read reads that schedule only", listOf(switchOf("start")), graph.readEntities())
    }

    @Test fun neverTwoRequestsInFlightAcrossBothKinds() = gated {
        val graph = graph()
        graph.connect()
        graph.heater("a")
        graph.heater("b")
        graph.followingSchedule("start")
        graph.followingSchedule("drain")
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        graph.haStateReader.answer = {
            most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            delay(20)
            inFlight.decrementAndGet()
            HaReadOutcome.Observed(HaSwitchState.OFF, null)
        }

        listOf(
            async(Dispatchers.Default) { graph.seasonSyncRunner.runAll() },
            async(Dispatchers.Default) { graph.seasonSyncRunner.syncNow() },
            async(Dispatchers.Default) { graph.seasonSyncRunner.syncSchedule(ScheduleId("drain")) },
            async(Dispatchers.Default) { graph.seasonSyncRunner.refreshIfStale(graph.now) },
        ).awaitAll()

        assertEquals("one lock: never two reads at once", 1, most.get())
        assertTrue("every pass ran: ${graph.readEntities()}", graph.readEntities().count { it == switchOf("drain") } >= 3)
    }

    @Test fun aScheduleReadWaitsForARunningPass() = gated {
        val graph = graph()
        graph.connect()
        graph.heater("a")
        val start = graph.followingSchedule("start")
        val inside = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        graph.haStateReader.answer = {
            if (inside.complete(Unit)) gate.await()
            HaReadOutcome.Observed(HaSwitchState.OFF, null)
        }
        val revision = graph.scheduleBinding("start").revision

        val run = async(Dispatchers.Default) { graph.seasonSyncRunner.runAll() }
        inside.await()
        val read = async(Dispatchers.Default) { graph.seasonSyncRunner.syncSchedule(start) }
        delay(200)
        assertEquals("the schedule's read waits for the running pass", 1, graph.haStateReader.reads.size)
        assertFalse(read.isCompleted)

        gate.complete(Unit)
        run.await()
        read.await()
        assertEquals(listOf(entityOf("a"), switchOf("start"), switchOf("start")), graph.readEntities())
        assertEquals("both recorded, each at the revision the last wrote", revision + 2, graph.scheduleBinding("start").revision)
    }

    /**
     * H3 review I1: an unlink and a relink restart the binding at revision 1, so the revision alone cannot tell a read
     * of the old switch from one of the new. A read of switch A still in flight when the schedule is relinked to B is
     * dropped: its `ON` never becomes B's observation, and it writes no row of the schedule's own season.
     */
    @Test fun aReadInFlightAcrossAnUnlinkAndARelinkIsDropped() = gated {
        val graph = graph()
        graph.connect()
        val start = graph.followingSchedule("start")
        val relinked = "input_boolean.example_start_other_switch"
        val inside = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        graph.haStateReader.answer = { entity ->
            if (entity == switchOf("start")) {
                inside.complete(Unit)
                gate.await()
                HaReadOutcome.Observed(HaSwitchState.ON, null)
            } else {
                HaReadOutcome.Observed(HaSwitchState.OFF, null)
            }
        }

        graph.seasonSyncRunner.requestScheduleRead(start)
        inside.await()
        graph.unlinkScheduleSeason.run(start)
        graph.linkScheduleSeason.run(start, relinked)
        assertEquals("the relink starts again at revision 1", 1L, graph.scheduleBinding("start").revision)
        graph.seasonSyncRunner.requestScheduleRead(start)
        gate.complete(Unit)
        graph.awaitSeasonSyncReads()

        val binding = graph.scheduleBinding("start")
        assertEquals(relinked, binding.entityId)
        assertEquals("B's own answer, never A's", HaSwitchState.OFF, binding.observedState)
        assertEquals(
            "no row from a switch the schedule no longer follows",
            emptyList<SeasonAction>(), graph.scheduleSeasonActivations.forSchedule(start).map { it.action },
        )
        assertEquals(listOf(switchOf("start"), relinked), graph.readEntities())
    }

    @Test fun theResumeRefreshCoversAStaleScheduleBinding() = runBlocking {
        val graph = graph()
        graph.connect(cadence = SyncCadence.DAILY)
        val start = graph.followingSchedule("start")
        val t0 = graph.now

        graph.seasonSyncRunner.refreshIfStale(t0)
        assertEquals("no success yet is stale", listOf(switchOf("start")), graph.readEntities())
        graph.haStateReader.reads.clear()

        graph.haStateReader.answer = { HaReadOutcome.Observed(HaSwitchState.OFF, null) }
        graph.seasonSyncRunner.syncSchedule(start)
        graph.haStateReader.reads.clear()

        graph.seasonSyncRunner.refreshIfStale(t0 + 24 * HOUR - 1)
        assertEquals("one millisecond short of a cadence is fresh", emptyList<String>(), graph.readEntities())
        graph.seasonSyncRunner.refreshIfStale(t0 + 24 * HOUR)
        assertEquals("a cadence old is stale", listOf(switchOf("start")), graph.readEntities())
    }

    @Test fun anUnreachableHelperRecordsItsKindAndChangesNoSeason() = runBlocking {
        val graph = graph()
        graph.connect()
        val start = graph.followingSchedule("start")
        graph.haStateReader.answer = { HaReadOutcome.NoDecision(SyncErrorKind.UNREACHABLE, null) }
        val schedule = graph.schedules.get(start)

        graph.seasonSyncRunner.runAll()

        val binding = graph.scheduleBinding("start")
        assertEquals(SyncErrorKind.UNREACHABLE, binding.errorKind)
        assertEquals(graph.now, binding.errorAt)
        assertNull("a failure never freshens", binding.lastSuccessAt)
        assertEquals(graph.now, binding.lastAttemptAt)
        assertTrue("no season row", graph.scheduleSeasonActivations.forSchedule(start).isEmpty())
        assertEquals("the schedule still follows its own season", ScheduleSeasonSource.OWN, graph.schedules.get(start)!!.seasonSource)
        assertEquals(schedule, graph.schedules.get(start))
    }

    @Test fun inertScheduleBindingsAreNeitherReadNorWritten() = runBlocking {
        val graph = graph()
        val connection = graph.connect()
        graph.followingSchedule("start")
        graph.followingSchedule("drain")
        graph.archiveAsset.run(AssetId("heater-drain"))
        val notMaintained = graph.scheduleBinding("drain")

        graph.seasonSyncRunner.runAll()
        assertEquals(listOf(switchOf("start")), graph.readEntities())
        assertEquals(notMaintained, graph.scheduleBinding("drain"))

        graph.secretStore.delete(connection.id)
        graph.haStateReader.reads.clear()
        val before = graph.scheduleSeasonBindings.all()
        graph.seasonSyncRunner.runAll()
        assertEquals("without a token every binding is NEEDS_TOKEN: nothing read", emptyList<String>(), graph.readEntities())
        assertEquals(before, graph.scheduleSeasonBindings.all())
    }

    /** C22 counts a schedule binding in "any enabled": alone, it enqueues the work; its unlink cancels it. */
    @Test fun aScheduleBindingAloneKeepsThePeriodicWork() = runBlocking {
        val graph = graph()
        graph.connect(eligibility = NetworkEligibility.ANY_NETWORK)
        assertNull("no binding, no work", graph.seasonSyncWork.enqueued)

        val start = graph.followingSchedule("start")
        assertNotNull("the link alone enqueues the work", graph.seasonSyncWork.enqueued)
        assertEquals(SeasonSyncSchedule.PERIODIC, graph.seasonSyncRunner.reconcile())

        graph.unlinkScheduleSeason.run(start)
        assertNull("the unlink of the last binding cancels it", graph.seasonSyncWork.enqueued)
    }

    /** #16's Stop cancels when no ASSET binding is left; an enabled schedule binding keeps the work (Z3). */
    @Test fun stoppingTheLastAssetSyncKeepsTheWorkForAScheduleBinding() = runBlocking {
        val graph = graph()
        graph.connect(eligibility = NetworkEligibility.ANY_NETWORK)
        graph.heater("a")
        graph.followingSchedule("start")
        assertNotNull(graph.seasonSyncWork.enqueued)

        graph.stopSeasonSync.run(AssetId("a"))

        assertNotNull("the schedule's binding still polls", graph.seasonSyncWork.enqueued)

        graph.forgetHaConnection.run()
        assertNull("Forget takes every binding, the schedule's too, and the work", graph.seasonSyncWork.enqueued)
        assertTrue(graph.scheduleSeasonBindings.all().isEmpty())
    }

    @Test fun theWorkerBodyRunsTheSchedulePass() = runBlocking {
        val graph = graph()
        graph.connect()
        graph.followingSchedule("start")
        graph.haStateReader.answer = { HaReadOutcome.Observed(HaSwitchState.ON, null) }

        assertEquals(SeasonSyncWorkResult.SUCCESS, SeasonSyncWorkerBody(graph.seasonSyncRunner).run())

        assertEquals(listOf(switchOf("start")), graph.readEntities())
        assertEquals(listOf(SeasonAction.START), graph.scheduleSeasonActivations.forSchedule(ScheduleId("start")).map { it.action })
    }

    @Test fun thePostLinkCheckIsLaunchedAndNeverAwaited() = gated {
        val graph = graph()
        graph.connect()
        val start = graph.followingSchedule("start")
        val gate = CompletableDeferred<Unit>()
        graph.haStateReader.answer = {
            gate.await()
            HaReadOutcome.Observed(HaSwitchState.ON, null)
        }

        graph.seasonSyncRunner.requestScheduleRead(start)
        assertTrue("the request returned before Home Assistant answered", graph.scheduleSeasonActivations.forSchedule(start).isEmpty())

        gate.complete(Unit)
        graph.awaitSeasonSyncReads()
        assertEquals(listOf(SeasonAction.START), graph.scheduleSeasonActivations.forSchedule(start).map { it.action })
    }
}
