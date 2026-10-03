package com.loosecannon.servicetag.seasonsync

import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.CurrentNetwork
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome
import com.loosecannon.servicetag.core.seasonsync.HaStateReader
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncRepository
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.fetch.InetHostResolver
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.RecordingSeasonSyncWork.Call
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URL
import java.time.Duration
import kotlin.test.assertFailsWith

/** Rows 58–59 — C22: the worker's result, and when the unique periodic work is enqueued or cancelled. */
class SeasonSyncWorkerBodyTest {

    private val graphs = mutableListOf<FakeGraph>()
    private val logged = mutableListOf<String>()

    private fun graph(fake: FakeGraph = FakeGraph()): FakeGraph = fake.also { graphs += it }

    @After fun tearDown() = graphs.forEach { it.close() }

    private fun FakeGraph.body() = SeasonSyncWorkerBody(seasonSyncRunner) { logged += it }

    /** The graph's runner over other parts: a failing repository, or the real client; its G5 line recorded. */
    private fun FakeGraph.runnerOver(
        bindings: SeasonSyncRepository = seasonSyncBindings,
        reader: HaStateReader = haStateReader,
        work: SeasonSyncWork = seasonSyncWork,
    ) = SeasonSyncRunner(
        bindings, haConnections, secretStore, assets, transferRecords, reader, recordSeasonSyncResult, work,
        seasonSyncBackgroundAllowed, seasonSyncScope, clock, log = { logged += it },
    )

    /** A work store whose every call fails, as one not ready yet. */
    private fun FakeGraph.failingWork() = object : SeasonSyncWork by seasonSyncWork {
        override fun isEnqueued(): Boolean = throw IllegalStateException("fictional: the work store is not ready")

        override fun ensure(request: SeasonSyncWorkRequest) = throw IllegalStateException("fictional: not ready")

        override fun cancel() = throw IllegalStateException("fictional: not ready")
    }

    /** G5 alone (R16-Q-J): exactly one line, and none of the fixture's address, entity, Wi-Fi name or token. */
    private fun assertOnlyG5() {
        assertEquals(listOf(CHECK_NOT_RUN), logged)
        val fixture = listOf("192.168.0.10", "input_boolean.example_heater_in_season", HOME_WIFI, "fictional-token-1")
        assertTrue(logged.none { line -> fixture.any { it in line } })
    }

    private fun request(period: Duration) = SeasonSyncWorkRequest(
        period, NetworkType.CONNECTED, BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(5), ExistingPeriodicWorkPolicy.UPDATE,
    )

    private suspend fun FakeGraph.start() =
        startSeasonSync(secretStore, haConnections, uow, seasonSyncRunner) { logged += it }

    // Row 58 — the result

    @Test fun everySyncErrorKindIsSuccess() = runBlocking {
        val graph = graph()
        graph.connect()
        graph.heater("a")
        for (kind in SyncErrorKind.entries) {
            graph.haStateReader.answer = { HaReadOutcome.NoDecision(kind, null) }
            assertEquals("$kind", SeasonSyncWorkResult.SUCCESS, graph.body().run())
            assertEquals(kind, graph.binding("a").errorKind)
        }
        assertEquals(emptyList<String>(), logged)
    }

    @Test fun aLocalDatabaseFailureIsRetry() = runBlocking {
        val graph = graph()
        graph.connect()
        graph.heater("a")
        val broken = object : SeasonSyncRepository by graph.seasonSyncBindings {
            override suspend fun all() = throw IllegalStateException("database is locked")
        }

        assertEquals(SeasonSyncWorkResult.RETRY, SeasonSyncWorkerBody(graph.runnerOver(bindings = broken)) { logged += it }.run())
        assertEquals(listOf("season sync run failed; WorkManager retries it"), logged)
    }

    /** B5's review: the client's one throwing path is its stored-connection lookup, a database read: retried. */
    @Test fun aSettingsLookupFailureInsideTheClientIsRetry() = runBlocking {
        val graph = graph()
        graph.connect()
        graph.heater("a")
        val broken = object : HaConnectionRepository by graph.haConnections {
            override suspend fun get() = throw IllegalStateException("database is locked")
        }
        val client = HomeAssistantStateClient(
            settings = storedHaConnection(broken), networkPermissionGranted = { true },
            currentNetwork = graph.currentNetworkReader, resolver = InetHostResolver({ true }),
            open = { throw AssertionError("nothing is opened") },
        )

        assertEquals(SeasonSyncWorkResult.RETRY, SeasonSyncWorkerBody(graph.runnerOver(reader = client)) { logged += it }.run())
        assertEquals(listOf(SeasonSyncWorkerBody.RUN_FAILED), logged)
    }

    @Test fun cancellationPropagates() = runBlocking {
        val graph = graph()
        graph.connect()
        graph.heater("a")
        graph.haStateReader.answer = { throw CancellationException("the work was stopped") }

        assertFailsWith<CancellationException> { graph.body().run() }
        assertEquals(emptyList<String>(), logged)
    }

    @Test fun anUnassignedDispatchIsSuccess() = runBlocking {
        assertEquals(SeasonSyncWorkResult.SUCCESS, SeasonSyncWorkerBody(null) { logged += it }.run())
        assertEquals(emptyList<String>(), logged)
    }

    /** B4's rule: a key store that cannot load is loud in the store, and success here, logged without the exception. */
    @Test fun aKeyStoreThatCannotLoadIsSuccessLoggedByTheStepOnly() = runBlocking {
        val aead = TamperingAead()
        val graph = graph(FakeGraph(secretStore = KeystoreSecretStore(kotlin.io.path.createTempDirectory("no-backup").toFile(), aead)))
        graph.connect()
        graph.heater("a")
        aead.broken = true

        assertEquals(SeasonSyncWorkResult.SUCCESS, graph.body().run())
        assertEquals(listOf(SeasonSyncWorkerBody.KEY_STORE_FAILED), logged)
        assertTrue(logged.none { line -> listOf("fictional-token-1", "192.168.0.10", "input_boolean").any { it in line } })
        assertTrue(graph.haStateReader.reads.isEmpty())
    }

    /** Decided: an `Error` is never caught; any other exception is no retry (C22: retry only for the database). */
    @Test fun anErrorPropagatesAndAnyOtherExceptionIsSuccess() = runBlocking {
        val graph = graph()
        graph.connect()
        graph.heater("a")
        graph.haStateReader.answer = { throw NotImplementedError("fictional") }
        assertFailsWith<NotImplementedError> { graph.body().run() }

        graph.haStateReader.answer = { throw IllegalArgumentException("fictional") }
        assertEquals(SeasonSyncWorkResult.SUCCESS, graph.body().run())
        assertEquals(listOf(CHECK_NOT_RUN), logged)
    }

    // Row 59 — enqueue and cancel

    @Test fun startEnsuresWhenAnyBindingIsEnabledAndCancelsWhenNone() = runBlocking {
        val graph = graph()
        graph.connect(NetworkEligibility.ANY_NETWORK)
        val heater = graph.heater("a")
        graph.stopSeasonSync.run(heater)
        graph.seasonSyncWork.calls.clear()

        graph.start()
        assertEquals("none enabled", listOf<Call>(Call.Cancel), graph.seasonSyncWork.calls.toList())
        assertNull(graph.seasonSyncWork.enqueued)

        graph.resumeSeasonSync.run(heater)
        graph.settle()
        graph.seasonSyncWork.calls.clear()
        graph.start()
        assertEquals(listOf<Call>(Call.Ensure(request(Duration.ofHours(24)))), graph.seasonSyncWork.calls.toList())
    }

    /** B3c's review: the sweep's ids are read through a read, never a write (a write would deadlock a link). */
    @Test fun startSweepsOrphanTokensThroughAReadAndWritesNothing() = runBlocking {
        val graph = graph()
        val connection = graph.connect()
        val orphan = "00000000-0000-4000-8000-999999999999"
        graph.secretStore.put(orphan, Secret("fictional-token-2"))
        val commits = graph.commits

        graph.start()

        assertEquals("no write", commits, graph.commits)
        assertFalse(graph.secretStore.has(orphan))
        assertTrue(graph.secretStore.has(connection.id))
        assertEquals(emptyList<String>(), logged)
    }

    @Test fun linkStopResumeAndForgetEnsureOrCancelAfterCommit() = runBlocking {
        val graph = graph()
        graph.connect(NetworkEligibility.ANY_NETWORK)
        val work = graph.seasonSyncWork

        val heater = graph.heater("a")
        assertEquals(listOf<Call>(Call.Ensure(request(Duration.ofHours(24)))), work.calls.toList())
        work.calls.clear()
        graph.stopSeasonSync.run(heater)
        assertEquals(listOf<Call>(Call.Cancel), work.calls.toList())
        work.calls.clear()
        graph.resumeSeasonSync.run(heater)
        graph.settle()
        assertEquals(listOf<Call>(Call.Ensure(request(Duration.ofHours(24)))), work.calls.toList())
        work.calls.clear()
        graph.forgetHaConnection.run()
        assertEquals(listOf<Call>(Call.Cancel), work.calls.toList())
        assertNull(work.enqueued)
    }

    @Test fun theRequestedPeriodIsTheCadence() = runBlocking {
        val expected = mapOf(
            SyncCadence.EVERY_12_HOURS to Duration.ofHours(12),
            SyncCadence.DAILY to Duration.ofHours(24),
            SyncCadence.WEEKLY to Duration.ofDays(7),
            SyncCadence.MONTHLY to Duration.ofDays(30),
        )
        for ((cadence, period) in expected) {
            val graph = graph()
            graph.connect(NetworkEligibility.ANY_NETWORK, cadence)
            graph.heater("a")
            assertEquals("$cadence", request(period), graph.seasonSyncWork.enqueued)
        }
    }

    @Test fun aCadenceChangeEnqueuesWithUpdate() = runBlocking {
        val graph = graph()
        graph.connect(NetworkEligibility.ANY_NETWORK, SyncCadence.DAILY)
        graph.heater("a")
        assertEquals(Duration.ofHours(24), graph.seasonSyncWork.enqueued?.period)

        graph.saveHaConnection.run(HTTPS_ADDRESS, null, SyncCadence.WEEKLY, NetworkEligibility.ANY_NETWORK, null, null)

        assertEquals("no obsolete interval survives", Duration.ofDays(7), graph.seasonSyncWork.enqueued?.period)
    }

    @Test fun switchingToHomeOnlyWithBackgroundOffCancels() = runBlocking {
        val graph = graph()
        graph.connect(NetworkEligibility.ANY_NETWORK)
        graph.heater("a")
        assertTrue(graph.seasonSyncWork.isEnqueued())

        graph.connect(NetworkEligibility.HOME_NETWORK_ONLY, background = BackgroundChecks.OFF, address = HTTPS_ADDRESS)
        graph.settle()

        assertEquals(Call.Cancel, graph.seasonSyncWork.calls.last())
        assertNull(graph.seasonSyncWork.enqueued)
        assertEquals(SeasonSyncSchedule.FOREGROUND_ONLY, graph.seasonSyncRunner.reconcile())
    }

    @Test fun switchingToAnyNetworkEnsures() = runBlocking {
        val graph = graph()
        graph.connect(NetworkEligibility.HOME_NETWORK_ONLY, address = HTTPS_ADDRESS)
        graph.heater("a")
        assertNull("background checks Off: no work", graph.seasonSyncWork.enqueued)

        graph.connect(NetworkEligibility.ANY_NETWORK)
        graph.settle()

        assertEquals(request(Duration.ofHours(24)), graph.seasonSyncWork.enqueued)
    }

    @Test fun homeOnlyWithBackgroundOnAndTheGrantEnsures() = runBlocking {
        val graph = graph()
        graph.backgroundAllowed = true
        graph.connect(NetworkEligibility.HOME_NETWORK_ONLY, background = BackgroundChecks.ON)
        graph.heater("a")

        assertEquals(request(Duration.ofHours(24)), graph.seasonSyncWork.enqueued)
        assertEquals(SeasonSyncSchedule.PERIODIC, graph.seasonSyncRunner.reconcile())
    }

    @Test fun homeOnlyWithBackgroundOnWithoutTheGrantCancelsAndReportsPaused() = runBlocking {
        val graph = graph()
        graph.backgroundAllowed = false
        graph.connect(NetworkEligibility.HOME_NETWORK_ONLY, background = BackgroundChecks.ON)
        graph.heater("a")

        assertNull("work that could send nothing is not enqueued", graph.seasonSyncWork.enqueued)
        assertTrue(graph.seasonSyncWork.calls.none { it is Call.Ensure })
        assertEquals(SeasonSyncSchedule.PAUSED, graph.seasonSyncRunner.reconcile())
    }

    @Test fun aGrantWithdrawnIsSeenOnTheNextStartOrResume() = runBlocking {
        val graph = graph()
        graph.backgroundAllowed = true
        graph.connect(NetworkEligibility.HOME_NETWORK_ONLY, background = BackgroundChecks.ON)
        graph.heater("a")
        assertTrue(graph.seasonSyncWork.isEnqueued())

        graph.backgroundAllowed = false
        graph.seasonSyncRunner.refreshIfStale(graph.now)
        assertNull("withdrawn: cancelled on the resume", graph.seasonSyncWork.enqueued)

        graph.backgroundAllowed = true
        graph.seasonSyncRunner.refreshIfStale(graph.now)
        assertTrue("granted again: enqueued on the resume", graph.seasonSyncWork.isEnqueued())

        graph.backgroundAllowed = false
        graph.start()
        assertNull("withdrawn: cancelled on the start", graph.seasonSyncWork.enqueued)
    }

    /** Fix round 1 (NOTE-1): the worker re-reads the grant **after** its pass, and a withdrawal cancels the work. */
    @Test fun runAllChecksTheScheduleAfterItsPass() = runBlocking {
        val graph = graph()
        graph.backgroundAllowed = true
        graph.connect(NetworkEligibility.HOME_NETWORK_ONLY, background = BackgroundChecks.ON)
        graph.heater("a")
        val enqueuedDuringTheRead = mutableListOf<Boolean>()
        graph.haStateReader.answer = {
            enqueuedDuringTheRead += graph.seasonSyncWork.isEnqueued()
            HaReadOutcome.NoDecision(SyncErrorKind.UNREACHABLE, null)
        }
        graph.backgroundAllowed = false

        graph.seasonSyncRunner.runAll()

        assertEquals("the pass ran first, the work still pending", listOf(true), enqueuedDuringTheRead)
        assertNull("then the withdrawn grant cancelled it", graph.seasonSyncWork.enqueued)
    }

    /** Fix round 1 (NOTE-1): a resume's failing schedule check comes after its stale read and never stops it. */
    @Test fun aFailingScheduleCheckNeverStopsTheResumesStaleRead() = runBlocking {
        val graph = graph()
        graph.connect()
        graph.heater("a")
        val failing = object : SeasonSyncWork by graph.seasonSyncWork {
            override fun isEnqueued(): Boolean = throw IllegalStateException("fictional: the work store is not ready")
        }
        val runner = graph.runnerOver(work = failing)

        assertFailsWith<IllegalStateException> { runner.refreshIfStale(graph.now) }

        assertEquals(listOf(entityOf("a")), graph.readEntities())
        assertEquals(SyncErrorKind.UNREACHABLE, graph.binding("a").errorKind)
    }

    /**
     * The worker's run goes through the client's network check: another Wi-Fi, or a hidden name (a background read
     * without the grant), opens nothing and records the cause; the captured Wi-Fi proceeds to the one GET.
     */
    @Test fun runAllInHomeModeChecksTheNetworkFirst() = runBlocking {
        val graph = graph()
        graph.connect(NetworkEligibility.HOME_NETWORK_ONLY, background = BackgroundChecks.ON)
        graph.heater("a")
        val opened = mutableListOf<URL>()
        val client = HomeAssistantStateClient(
            settings = storedHaConnection(graph.haConnections), networkPermissionGranted = { true },
            currentNetwork = graph.currentNetworkReader, resolver = InetHostResolver({ true }),
            open = { opened += it; throw IOException("no socket in a JVM test") },
        )
        val runner = graph.runnerOver(reader = client)

        graph.currentNetwork = NetworkReading(CurrentNetwork.Wifi("AnotherWifi"), null)
        runner.runAll()
        assertTrue(opened.isEmpty())
        assertEquals(SyncErrorKind.NOT_ON_LOCAL_NETWORK, graph.binding("a").errorKind)

        graph.currentNetwork = NetworkReading(CurrentNetwork.WifiUnnamed, UnconfirmedCause.NOT_FOREGROUND)
        runner.runAll()
        assertTrue(opened.isEmpty())
        assertEquals(SyncErrorKind.NOT_ON_LOCAL_NETWORK, graph.binding("a").errorKind)
        assertEquals("NOT_FOREGROUND", graph.binding("a").errorDetail)

        graph.currentNetwork = NetworkReading(CurrentNetwork.Wifi(HOME_WIFI), null)
        runner.runAll()
        assertEquals(1, opened.size)
        assertNotEquals(SyncErrorKind.NOT_ON_LOCAL_NETWORK, graph.binding("a").errorKind)
        assertEquals(AssetId("a"), graph.binding("a").assetId)
    }

    // Row 80 — G5 at each of the four silent sites (C33(6); R16-Q-J)

    /** A command's fresh read (`requestFreshRead`, launched quietly) whose pass fails: G5, and nothing written. */
    @Test fun aCommandsFreshReadThatFailsLogsG5() = runBlocking {
        val graph = graph()
        graph.connect()
        graph.heater("a")
        val before = graph.binding("a")
        val broken = object : SeasonSyncRepository by graph.seasonSyncBindings {
            override suspend fun all() = throw IllegalStateException("database is locked")
        }

        graph.runnerOver(bindings = broken).requestFreshRead(AssetId("a"))
        graph.awaitSeasonSyncReads()

        assertOnlyG5()
        assertEquals(before, graph.binding("a"))
        assertTrue(graph.readEntities().isEmpty())
    }

    /** The resume refresh (`launchRefreshIfStale`) whose schedule check fails after its stale read: G5, read kept. */
    @Test fun aResumeRefreshThatFailsLogsG5() = runBlocking {
        val graph = graph()
        graph.connect()
        graph.heater("a")

        graph.runnerOver(work = graph.failingWork()).launchRefreshIfStale()
        graph.awaitSeasonSyncReads()

        assertOnlyG5()
        assertEquals(listOf(entityOf("a")), graph.readEntities())
        assertEquals(SyncErrorKind.UNREACHABLE, graph.binding("a").errorKind)
    }

    /** The start-up's schedule check that fails: G5, the sweep before it done, and the start returns as shipped. */
    @Test fun aStartUpReconcileThatFailsLogsG5() = runBlocking {
        val graph = graph()
        val connection = graph.connect()
        graph.heater("a")
        val orphan = "00000000-0000-4000-8000-999999999998"
        graph.secretStore.put(orphan, Secret("fictional-token-2"))

        val runner = graph.runnerOver(work = graph.failingWork())

        startSeasonSync(graph.secretStore, graph.haConnections, graph.uow, runner) { logged += it }

        assertOnlyG5()
        assertFalse(graph.secretStore.has(orphan))
        assertTrue(graph.secretStore.has(connection.id))
    }

    /** The worker's schedule check that fails after its pass: G5, success (no retry), and the pass recorded. */
    @Test fun aWorkerScheduleCheckThatFailsLogsG5AndSucceeds() = runBlocking {
        val graph = graph()
        graph.connect()
        graph.heater("a")

        val result = SeasonSyncWorkerBody(graph.runnerOver(work = graph.failingWork())) { logged += it }.run()

        assertEquals(SeasonSyncWorkResult.SUCCESS, result)
        assertOnlyG5()
        assertEquals(listOf(entityOf("a")), graph.readEntities())
        assertEquals(SyncErrorKind.UNREACHABLE, graph.binding("a").errorKind)
    }
}
