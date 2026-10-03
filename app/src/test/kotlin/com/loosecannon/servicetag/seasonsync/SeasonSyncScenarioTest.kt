package com.loosecannon.servicetag.seasonsync

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonAction.END
import com.loosecannon.servicetag.core.model.SeasonAction.START
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncState
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.core.seasonsync.currentSeasonSyncState
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.ReplaceDraft
import com.loosecannon.servicetag.data.room.fileBackedDb
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.RecordingSeasonSyncWork
import com.loosecannon.servicetag.testing.ScriptedHaStateReader
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.scheduleOf
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HEATER = "heater"
private const val HELPER = "input_boolean.example_heater_in_season"
private const val APPLIANCE = "switch.example_heater"
private const val IN_SERVICE = "s-burner"
private const val CONTINUOUS = "s-flue"

/** Every case runs under one bound: a held read that never lands fails it, it never hangs the suite. */
private fun scenario(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(60_000L) { block() } }

/** A fictional Home Assistant as the scripted reader sees it: each entity's state, and whether it answers at all. */
private class ExampleHa {
    private val states = ConcurrentHashMap<String, HaReadOutcome>()

    @Volatile var reachable = true

    fun set(entity: String, on: Boolean, changed: String? = null) {
        states[entity] = HaReadOutcome.Observed(if (on) HaSwitchState.ON else HaSwitchState.OFF, changed)
    }

    fun answer(entity: String): HaReadOutcome = if (!reachable) {
        HaReadOutcome.NoDecision(SyncErrorKind.UNREACHABLE, null)
    } else {
        states[entity] ?: HaReadOutcome.NoDecision(SyncErrorKind.ENTITY_NOT_FOUND, null)
    }
}

/** Row 60 — the issue's acceptance criteria as one story, over the Room-backed graph, the scripted reader and the work double. */
class SeasonSyncScenarioTest {

    private val dbFile = File(kotlin.io.path.createTempDirectory("scenario").toFile(), "servicetag.db")
    private val open = mutableListOf<FakeGraph>()

    @After fun tearDown() {
        open.forEach { it.close() }
        dbFile.parentFile?.deleteRecursively()
    }

    private fun inMemory(): FakeGraph = FakeGraph().also { open += it }

    private fun onFile(store: KeystoreSecretStore = freshStore()): FakeGraph =
        FakeGraph(db = fileBackedDb(dbFile), secretStore = store).also { open += it }

    private fun freshStore() = KeystoreSecretStore(kotlin.io.path.createTempDirectory("no-backup").toFile(), JdkAead())

    /**
     * The process recreated: a new graph over the same database file and, unless [store] says otherwise (a platform
     * restore), the same token store; then the app's own start-up. The fake's ids count from one in every graph where
     * the app's are random, so the new graph skips past the old one's.
     */
    private suspend fun FakeGraph.restarted(ha: ExampleHa, store: KeystoreSecretStore = secretStore): FakeGraph {
        val (date, at) = today to now
        awaitSeasonSyncReads()
        close()
        open -= this
        return onFile(store).also { next ->
            repeat(1_000) { next.ids.newId() }
            next.today = date
            next.now = at
            next.answerFrom(ha)
            startSeasonSync(next.secretStore, next.haConnections, next.uow, next.seasonSyncRunner) {}
        }
    }

    private fun FakeGraph.answerFrom(ha: ExampleHa) {
        haStateReader.answer = { ha.answer(it) }
    }

    private fun FakeGraph.on(date: String, hour: Int = 9) {
        today = LocalDate.parse(date)
        now = dayMillis(date) + hour * HOUR
    }

    /** The MANUAL "Example Heater", its two schedules settled by the engine, linked to [HELPER]; the link's read landed. */
    private suspend fun FakeGraph.exampleHeater(breakStart: String? = null, breakEnd: String? = null): AssetId {
        assets.upsert(
            assetRow(HEATER, name = "Example Heater", seasonMode = SeasonMode.MANUAL, breakStart = breakStart, breakEnd = breakEnd),
        )
        for (schedule in listOf(
            scheduleOf(
                IN_SERVICE, assetId = HEATER, title = "Burner check", timeInterval = 1, timeUnit = RecurrenceUnit.WEEK,
                anchorOn = "2026-09-01", createdOn = "2026-09-01", servicePolicy = ServicePolicy.IN_SERVICE_AT_START,
                policyOffsetDays = 0,
            ),
            scheduleOf(CONTINUOUS, assetId = HEATER, title = "Flue inspection", anchorOn = "2026-09-01", createdOn = "2026-09-01"),
        )) {
            schedules.upsert(schedule)
            recomputeSchedules.forSchedule(schedule.id)
        }
        linkSeasonSync.run(AssetId(HEATER), HELPER)
        awaitSeasonSyncReads()
        return AssetId(HEATER)
    }

    private suspend fun FakeGraph.season(id: AssetId = AssetId(HEATER)): List<Pair<SeasonAction, String>> =
        seasonActivations.forAsset(id).sortedWith(compareBy({ it.occurredOn }, { it.createdAt })).map { it.action to it.occurredOn }

    private suspend fun FakeGraph.state(schedule: String) = checkNotNull(scheduleStates.get(ScheduleId(schedule)))

    private suspend fun FakeGraph.heaterState(): SeasonSyncState =
        currentSeasonSyncState(binding(HEATER), secretStore, assets, transferRecords)

    /**
     * Holds the next read while [meanwhile] runs — its answer is what HA said when the request went out — then lets it
     * land, and settles any read [meanwhile] asked for.
     */
    private suspend fun FakeGraph.whileAReadIsInFlight(ha: ExampleHa, meanwhile: suspend () -> Unit) = coroutineScope {
        val inside = CompletableDeferred<Unit>()
        val land = CompletableDeferred<Unit>()
        haStateReader.answer = { entity ->
            val said = ha.answer(entity)
            if (inside.complete(Unit)) land.await()
            said
        }
        val read = async(Dispatchers.Default) { seasonSyncRunner.syncNow() }
        inside.await()
        meanwhile()
        land.complete(Unit)
        read.await()
        awaitSeasonSyncReads()
        answerFrom(ha)
    }

    // AC1, AC2, AC3, AC7
    @Test fun theHeaterFollowsItsSeasonHelperAndNothingElse() = scenario {
        val graph = inMemory()
        val ha = ExampleHa().also { graph.answerFrom(it) }
        graph.on("2026-10-01")
        ha.set(HELPER, on = true)
        ha.set(APPLIANCE, on = false)
        graph.connect()
        val heater = graph.exampleHeater(breakStart = "12-20", breakEnd = "12-31")
        val seeded = graph.state(IN_SERVICE).computedDueOn to graph.state(CONTINUOUS).computedDueOn

        val start = graph.seasonActivations.forAsset(heater).single()
        assertEquals("on enters the season, dated the day applied", START to "2026-10-01", start.action to start.occurredOn)
        assertNull("the shipped operation's own row", start.eventId)
        assertEquals(graph.now, start.createdAt)
        graph.binding(HEATER).let {
            assertEquals(HaSwitchState.ON, it.observedState)
            assertEquals(graph.now, it.lastSuccessAt)
            assertEquals(START to "2026-10-01", it.appliedAction to it.appliedOn)
        }
        assertEquals(PolicyPhase.ACTIVE, graph.state(IN_SERVICE).policyPhase)
        assertEquals("in-service opens at HA's START", "2026-10-01", graph.state(IN_SERVICE).actionableDueOn)

        for (hour in 10..20) {
            graph.on("2026-10-02", hour)
            ha.set(APPLIANCE, on = hour % 2 == 0)
            graph.seasonSyncRunner.syncNow()
        }
        assertEquals("the appliance's own switch cycling moves nothing", listOf(START to "2026-10-01"), graph.season())
        assertEquals(setOf(HELPER), graph.readEntities().toSet())

        graph.on("2026-10-04")
        graph.seasonSyncRunner.syncNow()
        val lastSuccess = graph.now
        ha.reachable = false
        for (day in 5..11) {
            graph.on("2026-10-%02d".format(day))
            when (day) {
                8 -> ha.set(HELPER, on = false)
                9 -> ha.set(HELPER, on = true)
                10 -> ha.set(HELPER, on = false, changed = "2026-10-10T06:00:00+00:00")
            }
            graph.seasonSyncRunner.syncNow()
            assertEquals(SyncErrorKind.UNREACHABLE, graph.binding(HEATER).errorKind)
            assertEquals("offline on the $day: the last success stays", lastSuccess, graph.binding(HEATER).lastSuccessAt)
        }
        assertEquals(listOf(START to "2026-10-01"), graph.season())

        ha.reachable = true
        graph.on("2026-10-12")
        graph.seasonSyncRunner.syncNow()
        assertEquals(
            "the change made offline is found on the next success: one END, dated the day it is read, no replay",
            listOf(START to "2026-10-01", END to "2026-10-12"), graph.season(),
        )
        graph.binding(HEATER).let {
            assertEquals("HA's own change time is information only", "2026-10-10T06:00:00+00:00", it.observedChangedAt)
            assertEquals(graph.now, it.lastSuccessAt)
            assertNull(it.errorKind)
        }
        assertEquals(PolicyPhase.DORMANT, graph.state(IN_SERVICE).policyPhase)
        assertNull("out of season a MANUAL asset awaits its next START", graph.state(IN_SERVICE).actionableDueOn)

        ha.set(HELPER, on = true)
        graph.on("2026-11-02")
        graph.seasonSyncRunner.syncNow()
        assertEquals(PolicyPhase.ACTIVE, graph.state(IN_SERVICE).policyPhase)
        assertEquals("re-entry at the new START", "2026-11-02", graph.state(IN_SERVICE).actionableDueOn)

        ha.set(HELPER, on = false)
        graph.on("2026-12-18")
        graph.seasonSyncRunner.syncNow()
        ha.set(HELPER, on = true)
        graph.on("2026-12-24")
        graph.seasonSyncRunner.syncNow()
        assertEquals(START to "2026-12-24", graph.season().last())
        assertTrue("a START inside the break is still quiet", graph.state(IN_SERVICE).quiet)
        assertFalse("CONTINUOUS ignores the break", graph.state(CONTINUOUS).quiet)
        assertEquals(PolicyPhase.ACTIVE, graph.state(CONTINUOUS).policyPhase)

        assertEquals("no completion made up", emptyList<Any>(), graph.events.all())
        assertEquals("no occurrence closed", emptyList<Any>(), graph.closures.all())
        assertEquals("no health repair", emptyList<Any>(), graph.conditions.all())
        assertEquals(
            "one occurrence each, never a backlog",
            seeded, graph.state(IN_SERVICE).computedDueOn to graph.state(CONTINUOUS).computedDueOn,
        )
    }

    // AC4
    @Test fun repeatsAnOverlapARetryAndARestartWriteNoSecondTransition() = scenario {
        var graph = onFile()
        val ha = ExampleHa().also { graph.answerFrom(it) }
        graph.on("2026-10-01")
        ha.set(HELPER, on = true)
        graph.connect()
        graph.exampleHeater()
        val once = listOf(START to "2026-10-01")
        val applied = graph.binding(HEATER).appliedAt
        val schedulesSettled = graph.scheduleStates.all().toSet()

        repeat(10) {
            graph.now += HOUR
            graph.seasonSyncRunner.syncNow()
        }
        coroutineScope {
            launch(Dispatchers.Default) { graph.seasonSyncRunner.syncNow() }
            launch(Dispatchers.Default) { graph.seasonSyncRunner.runAll() }
            launch(Dispatchers.Default) { graph.seasonSyncRunner.syncNow(AssetId(HEATER)) }
            launch(Dispatchers.Default) { graph.seasonSyncRunner.refreshIfStale(graph.now + 25 * HOUR) }
        }
        val body = SeasonSyncWorkerBody(graph.seasonSyncRunner) {}
        assertEquals(SeasonSyncWorkResult.SUCCESS, body.run())
        assertEquals("a retry of the same run", SeasonSyncWorkResult.SUCCESS, body.run())
        assertEquals(once, graph.season())

        graph = graph.restarted(ha)
        graph.on("2026-10-02")
        graph.seasonSyncRunner.syncNow()
        graph.resumeRefresh.onResume()
        graph.awaitSeasonSyncReads()

        assertEquals("no second START after the restart", once, graph.season())
        assertEquals("no recurrence restarted", schedulesSettled, graph.scheduleStates.all().toSet())
        graph.binding(HEATER).let {
            assertEquals("the provenance is the one application", applied, it.appliedAt)
            assertEquals("a fresh observation all the same", graph.now, it.lastSuccessAt)
        }
    }

    // AC5
    @Test fun everyFailureKeepsTheSeasonAndTheLastSuccess() = scenario {
        val graph = inMemory()
        val ha = ExampleHa()
        graph.on("2026-10-01")
        ha.set(HELPER, on = true)
        graph.answerFrom(ha)
        graph.connect()
        graph.exampleHeater()
        val success = graph.now

        for (failure in listOf(
            HaReadOutcome.NoDecision(SyncErrorKind.UNREACHABLE, null),
            HaReadOutcome.NoDecision(SyncErrorKind.AUTH_REFUSED, null),
            HaReadOutcome.NoDecision(SyncErrorKind.ENTITY_NOT_FOUND, null),
            HaReadOutcome.NoDecision(SyncErrorKind.MALFORMED, null),
            HaReadOutcome.NoDecision(SyncErrorKind.UNSUPPORTED_STATE, "unknown"),
            HaReadOutcome.NoDecision(SyncErrorKind.UNSUPPORTED_STATE, "unavailable"),
        )) {
            graph.now += HOUR
            graph.haStateReader.answer = { failure }
            graph.seasonSyncRunner.syncNow()
            graph.binding(HEATER).let {
                assertEquals(failure.kind to failure.detail, it.errorKind to it.errorDetail)
                assertEquals(graph.now, it.errorAt)
                assertEquals(graph.now, it.lastAttemptAt)
                assertEquals("$failure: the last success stays", success, it.lastSuccessAt)
                assertEquals(HaSwitchState.ON, it.observedState)
            }
            assertEquals("$failure: the season stays", listOf(START to "2026-10-01"), graph.season())
        }

        graph.now += HOUR
        graph.answerFrom(ha)
        graph.seasonSyncRunner.syncNow()
        graph.binding(HEATER).let {
            assertEquals("an unchanged on is still a fresh observation", graph.now, it.lastSuccessAt)
            assertNull(it.errorKind)
        }
        assertEquals(listOf(START to "2026-10-01"), graph.season())
    }

    // AC6
    @Test fun overridesHoldThroughPollsStaleReadsAndARestartAndFollowWaitsForAFreshRead() = scenario {
        var graph = onFile()
        val ha = ExampleHa().also { graph.answerFrom(it) }
        graph.on("2026-10-01")
        ha.set(HELPER, on = true)
        graph.connect()
        val heater = graph.exampleHeater()

        graph.on("2026-10-03")
        graph.whileAReadIsInFlight(ha) { graph.setSeasonSyncMode.run(heater, SyncMode.FORCE_OUT) }
        assertEquals(
            "forced out at once; the on read before it landed after and was dropped",
            listOf(START to "2026-10-01", END to "2026-10-03"), graph.season(),
        )
        repeat(3) {
            graph.now += HOUR
            graph.seasonSyncRunner.syncNow()
        }
        assertEquals("polls answering on cannot undo it", END to "2026-10-03", graph.season().last())
        assertEquals("the answer is kept, not applied", HaSwitchState.ON, graph.binding(HEATER).observedState)

        ha.set(HELPER, on = false)
        graph.on("2026-10-05")
        graph.whileAReadIsInFlight(ha) { graph.setSeasonSyncMode.run(heater, SyncMode.FORCE_IN) }
        repeat(3) {
            graph.now += HOUR
            graph.seasonSyncRunner.syncNow()
        }
        assertEquals(START to "2026-10-05", graph.season().last())

        graph = graph.restarted(ha)
        graph.on("2026-10-06")
        graph.seasonSyncRunner.syncNow()
        assertEquals("the force survives a restart", SyncMode.FORCE_IN, graph.binding(HEATER).mode)
        assertEquals("and a poll answering off after it", START to "2026-10-05", graph.season().last())

        ha.reachable = false
        graph.on("2026-10-07")
        graph.setSeasonSyncMode.run(heater, SyncMode.FOLLOW)
        graph.awaitSeasonSyncReads()
        assertEquals(HaSwitchState.OFF, graph.binding(HEATER).observedState)
        assertEquals("the stored off is never applied", START to "2026-10-05", graph.season().last())

        ha.reachable = true
        graph.on("2026-10-08")
        graph.seasonSyncRunner.syncNow()
        assertEquals(
            listOf(START to "2026-10-01", END to "2026-10-03", START to "2026-10-05", END to "2026-10-08"),
            graph.season(),
        )

        graph.on("2026-10-09")
        graph.setSeasonSyncMode.run(heater, SyncMode.FORCE_IN)
        graph.on("2026-10-10")
        graph.whileAReadIsInFlight(ha) {
            ha.set(HELPER, on = true)
            graph.setSeasonSyncMode.run(heater, SyncMode.FOLLOW)
        }
        assertEquals(
            "back to Follow: the off read sent before it is dropped and the fresh on keeps the season",
            listOf(START to "2026-10-01", END to "2026-10-03", START to "2026-10-05", END to "2026-10-08", START to "2026-10-09"),
            graph.season(),
        )
        assertEquals(HaSwitchState.ON, graph.binding(HEATER).observedState)
    }

    // AC8
    @Test fun anOldRequestNeverMovesAnEditedStoppedDisconnectedArchivedOrReplacedAsset() = scenario {
        for (change in listOf("edit", "stop", "disconnect", "archive", "replace")) {
            val graph = inMemory()
            val ha = ExampleHa().also { graph.answerFrom(it) }
            graph.on("2026-10-01")
            ha.set(HELPER, on = true)
            val connection = graph.connect()
            val heater = graph.exampleHeater()
            ha.set(HELPER, on = false)
            ha.set("input_boolean.example_heater_season", on = true)
            graph.haStateReader.reads.clear()
            graph.on("2026-10-02")
            var successor: AssetId? = null

            graph.whileAReadIsInFlight(ha) {
                when (change) {
                    "edit" -> graph.editSeasonSyncEntity.run(heater, "input_boolean.example_heater_season")
                    "stop" -> graph.stopSeasonSync.run(heater)
                    "disconnect" -> graph.forgetHaConnection.run()
                    "archive" -> graph.archiveAsset.run(heater)
                    "replace" -> {
                        val draft = ReplaceDraft(heater, retiredOn = "2026-10-02", successor = AssetCommand(name = "Example Heater"))
                        val plan = graph.replaceAsset.plan(draft)
                        assertEquals(emptyList<Any>(), plan.problems)
                        successor = graph.replaceAsset.run(draft, plan).successor.id
                    }
                    else -> error(change)
                }
            }

            assertEquals("$change: the off read in flight wrote no END", listOf(START to "2026-10-01"), graph.season())
            val binding = graph.seasonSyncBindings.get(heater)
            when (change) {
                "edit" -> assertEquals(listOf(HELPER, "input_boolean.example_heater_season"), graph.readEntities())
                "stop" -> assertFalse(checkNotNull(binding).enabled)
                "disconnect" -> {
                    assertNull("the binding went with the connection", binding)
                    assertFalse(graph.secretStore.has(connection.id))
                    assertEquals(RecordingSeasonSyncWork.Call.Cancel, graph.seasonSyncWork.calls.last())
                }
                "archive", "replace" -> assertEquals(SyncErrorKind.NOT_MAINTAINED_HERE, checkNotNull(binding).errorKind)
            }
            successor?.let {
                assertNull("$change: no successor inherits the binding", graph.seasonSyncBindings.get(it))
                assertEquals(emptyList<Any>(), graph.season(it))
            }
            if (change == "archive") {
                graph.archiveAsset.unarchive(heater)
                graph.on("2026-10-04")
                graph.seasonSyncRunner.syncNow()
                graph.seasonSyncRunner.syncNow()
                assertEquals(
                    "maintained here again: the current state, once, that day",
                    listOf(START to "2026-10-01", END to "2026-10-04"), graph.season(),
                )
            }
        }
    }

    // AC9
    @Test fun aPlatformRestoreSendsNothingUntilTheTokenIsEnteredAgain() = scenario {
        var graph = onFile()
        val ha = ExampleHa().also { graph.answerFrom(it) }
        graph.on("2026-10-01")
        ha.set(HELPER, on = true)
        val connection = graph.connect()
        graph.exampleHeater()
        val restored = graph.seasonSyncBindings.all()

        graph = graph.restarted(ha, store = freshStore())
        graph.on("2026-10-02")
        ha.set(HELPER, on = false)
        assertEquals(SeasonSyncState.NEEDS_TOKEN, graph.heaterState())
        graph.seasonSyncRunner.syncNow()
        graph.seasonSyncRunner.runAll()
        graph.seasonSyncRunner.refreshIfStale(graph.now + 30 * 24 * HOUR)
        graph.resumeRefresh.onResume()
        graph.awaitSeasonSyncReads()
        assertEquals("nothing sent", emptyList<ScriptedHaStateReader.Read>(), graph.haStateReader.reads.toList())
        assertEquals("nothing written", restored, graph.seasonSyncBindings.all())

        graph.saveHaConnection.run(
            HTTP_ADDRESS, TOKEN, null, NetworkEligibility.HOME_NETWORK_ONLY, HOME_WIFI, null,
        )
        graph.awaitSeasonSyncReads()
        assertEquals(SeasonSyncState.ACTIVE, graph.heaterState())
        assertEquals(
            listOf(ScriptedHaStateReader.Read(connection.baseUrl, HELPER, TOKEN)), graph.haStateReader.reads.toList(),
        )
        assertEquals(listOf(START to "2026-10-01", END to "2026-10-02"), graph.season())
    }

    // C21–C23: home network with background checks Off, then Any network
    @Test fun homeOnlyWithBackgroundOffChecksOnOpenAndAnyNetworkRunsAtTheCadence() = scenario {
        val graph = inMemory()
        val ha = ExampleHa().also { graph.answerFrom(it) }
        graph.on("2026-10-01")
        ha.set(HELPER, on = true)
        graph.connect(NetworkEligibility.HOME_NETWORK_ONLY, SyncCadence.DAILY, BackgroundChecks.OFF)
        graph.exampleHeater()
        assertNull("no periodic work", graph.seasonSyncWork.enqueued)
        assertTrue(graph.seasonSyncWork.calls.none { it is RecordingSeasonSyncWork.Call.Ensure })
        graph.haStateReader.reads.clear()
        ha.set(HELPER, on = false)

        graph.on("2026-10-02", hour = 8)
        graph.resumeRefresh.onResume()
        graph.awaitSeasonSyncReads()
        assertEquals("opened within the cadence: nothing read", emptyList<String>(), graph.readEntities())

        graph.on("2026-10-02", hour = 9)
        graph.resumeRefresh.onResume()
        graph.awaitSeasonSyncReads()
        assertEquals("a cadence after the last success: read on open", listOf(HELPER), graph.readEntities())
        assertEquals(listOf(START to "2026-10-01", END to "2026-10-02"), graph.season())
        assertNull(graph.seasonSyncWork.enqueued)

        graph.saveHaConnection.run(HTTPS_ADDRESS, null, SyncCadence.WEEKLY, NetworkEligibility.ANY_NETWORK, null, null)
        graph.awaitSeasonSyncReads()
        val work = checkNotNull(graph.seasonSyncWork.enqueued)
        assertEquals(SeasonSyncWorkRequest.forCadence(SyncCadence.WEEKLY), work)
        assertEquals(Duration.ofDays(7), work.period)
    }
}
