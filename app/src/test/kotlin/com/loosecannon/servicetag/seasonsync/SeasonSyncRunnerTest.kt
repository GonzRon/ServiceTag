package com.loosecannon.servicetag.seasonsync

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException

/** The fictional fixtures (§ Global constraints): the accepted http endpoint, the home Wi-Fi, one token. */
internal const val HTTP_ADDRESS = "http://192.168.0.10:8123"
internal const val HTTPS_ADDRESS = "https://ha.example:8123"
internal const val HOME_WIFI = "ExampleHomeWifi"
internal val TOKEN = Secret("fictional-token-1")
internal const val HOUR = 3_600_000L

/** A case that holds a read on a gate: it fails within 20 s, never hangs, whatever the runner does. */
private fun gated(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000L) { block() } }

/** The entity of the fictional heater [id]: `input_boolean.example_heater_in_season` for the first. */
internal fun entityOf(id: String): String =
    if (id == "a") "input_boolean.example_heater_in_season" else "input_boolean.example_heater_${id}_in_season"

/** Saves the one connection with the token, as the Home Assistant screen would. */
internal suspend fun FakeGraph.connect(
    eligibility: NetworkEligibility = NetworkEligibility.HOME_NETWORK_ONLY,
    cadence: SyncCadence = SyncCadence.DAILY,
    background: BackgroundChecks = BackgroundChecks.OFF,
    address: String = if (eligibility == NetworkEligibility.ANY_NETWORK) HTTPS_ADDRESS else HTTP_ADDRESS,
): HaConnection = saveHaConnection.run(
    address, TOKEN, cadence, eligibility, HOME_WIFI.takeIf { eligibility == NetworkEligibility.HOME_NETWORK_ONLY },
    background,
)

/** A MANUAL "Example Heater" [id], linked to its entity; the link's own fresh read settled. */
internal suspend fun FakeGraph.heater(id: String): AssetId {
    val asset = AssetId(id)
    assets.upsert(assetRow(id, name = "Example Heater $id", seasonMode = SeasonMode.MANUAL))
    linkSeasonSync.run(asset, entityOf(id))
    settle()
    return asset
}

/** Joins every fresh read a command launched, then forgets the reads so far. */
internal suspend fun FakeGraph.settle() {
    awaitSeasonSyncReads()
    haStateReader.reads.clear()
}

internal suspend fun FakeGraph.binding(id: String): SeasonSyncBinding = checkNotNull(seasonSyncBindings.get(AssetId(id)))

internal fun FakeGraph.readEntities(): List<String> =
    synchronized(haStateReader.reads) { haStateReader.reads.map { it.entityId } }

/** Row 57 — C21: the runner, one serialized pass, over the Room-backed graph and the scripted reader. */
class SeasonSyncRunnerTest {

    private val graphs = mutableListOf<FakeGraph>()

    private fun graph(fake: FakeGraph = FakeGraph()): FakeGraph = fake.also { graphs += it }

    @After fun tearDown() = graphs.forEach { it.close() }

    @Test fun syncNowReadsEachActiveBindingOnce() = runBlocking {
        val graph = graph()
        val stored = graph.connect()
        graph.heater("a")
        graph.heater("b")
        graph.haStateReader.answer = { HaReadOutcome.Observed(HaSwitchState.OFF, null) }

        graph.seasonSyncRunner.syncNow()

        assertEquals(listOf(entityOf("a"), entityOf("b")).sorted(), graph.readEntities().sorted())
        assertTrue(graph.haStateReader.reads.all { it.baseUrl == stored.baseUrl && it.token == TOKEN })
        assertEquals(HaSwitchState.OFF, graph.binding("a").observedState)
        assertEquals(graph.now, graph.binding("b").lastSuccessAt)

        graph.haStateReader.reads.clear()
        graph.seasonSyncRunner.syncNow(AssetId("b"))
        assertEquals(listOf(entityOf("b")), graph.readEntities())
    }

    @Test fun aSyncNowDuringARunWaitsThenReadsFresh() = gated {
        val graph = graph()
        graph.connect()
        graph.heater("a")
        val inside = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        graph.haStateReader.answer = {
            if (inside.complete(Unit)) gate.await()
            HaReadOutcome.Observed(HaSwitchState.OFF, null)
        }
        val revision = graph.binding("a").revision

        val first = async(Dispatchers.Default) { graph.seasonSyncRunner.syncNow() }
        inside.await()
        val second = async(Dispatchers.Default) { graph.seasonSyncRunner.syncNow(AssetId("a")) }
        delay(200)
        assertEquals("the second waits for the running pass", 1, graph.haStateReader.reads.size)
        assertFalse(second.isCompleted)

        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(2, graph.haStateReader.reads.size)
        assertEquals("both recorded: the second read the revision the first wrote", revision + 2, graph.binding("a").revision)
    }

    @Test fun neverTwoRequestsInFlight() = runBlocking {
        val graph = graph()
        graph.connect()
        graph.heater("a")
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        val overlapped = CompletableDeferred<Unit>()
        graph.haStateReader.answer = {
            val now = inFlight.incrementAndGet()
            most.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            if (now > 1) overlapped.complete(Unit)
            withTimeoutOrNull(300) { overlapped.await() } // a second request, if one can start, starts in here
            inFlight.decrementAndGet()
            HaReadOutcome.NoDecision(SyncErrorKind.UNREACHABLE, null)
        }

        coroutineScope {
            launch(Dispatchers.Default) { graph.seasonSyncRunner.syncNow() }
            launch(Dispatchers.Default) { graph.seasonSyncRunner.runAll() }
            launch(Dispatchers.Default) { graph.seasonSyncRunner.syncNow(AssetId("a")) }
        }

        assertEquals(3, graph.haStateReader.reads.size)
        assertEquals("requests in flight at once", 1, most.get())
    }

    @Test fun refreshIfStaleSkipsWhileARunHoldsTheLockAndReadsOnlyStaleOnes() = gated {
        val graph = graph()
        graph.connect(cadence = SyncCadence.DAILY)
        graph.heater("a")
        graph.heater("b")
        val t0 = graph.now
        graph.haStateReader.answer = { HaReadOutcome.Observed(HaSwitchState.OFF, null) }
        graph.seasonSyncRunner.syncNow(AssetId("a"))
        graph.now = t0 + 23 * HOUR
        graph.seasonSyncRunner.syncNow(AssetId("b"))
        graph.haStateReader.reads.clear()

        val inside = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        graph.haStateReader.answer = {
            if (inside.complete(Unit)) gate.await()
            HaReadOutcome.Observed(HaSwitchState.OFF, null)
        }
        val run = async(Dispatchers.Default) { graph.seasonSyncRunner.syncNow(AssetId("b")) }
        inside.await()
        assertNull("a running pass is fresh: the resume skips", graph.seasonSyncRunner.refreshIfStale(t0 + 24 * HOUR))
        assertEquals(1, graph.haStateReader.reads.size)
        gate.complete(Unit)
        run.await()
        graph.haStateReader.reads.clear()

        graph.haStateReader.answer = { HaReadOutcome.Observed(HaSwitchState.OFF, null) }
        graph.seasonSyncRunner.refreshIfStale(t0 + 24 * HOUR)
        assertEquals("a's success is a day old, b's an hour", listOf(entityOf("a")), graph.readEntities())
    }

    @Test fun staleIsOneCadenceWithoutASuccess() = runBlocking {
        for (cadence in SyncCadence.entries) {
            val graph = graph()
            graph.connect(cadence = cadence)
            graph.heater("a")
            val period = cadence.hours * HOUR
            val t0 = graph.now

            graph.seasonSyncRunner.refreshIfStale(t0)
            assertEquals("$cadence: no success yet is stale", listOf(entityOf("a")), graph.readEntities())
            graph.haStateReader.reads.clear()

            graph.haStateReader.answer = { HaReadOutcome.Observed(HaSwitchState.OFF, null) }
            graph.seasonSyncRunner.syncNow()
            graph.now = t0 + period - 1
            graph.haStateReader.answer = { HaReadOutcome.NoDecision(SyncErrorKind.UNREACHABLE, null) }
            graph.seasonSyncRunner.syncNow()
            graph.haStateReader.reads.clear()

            graph.seasonSyncRunner.refreshIfStale(t0 + period - 1)
            assertEquals("$cadence: one millisecond short of a cadence is fresh", emptyList<String>(), graph.readEntities())
            graph.seasonSyncRunner.refreshIfStale(t0 + period)
            assertEquals("$cadence: a recent failure does not freshen", listOf(entityOf("a")), graph.readEntities())
        }
    }

    /** Fix round 1 (NOTE-5): a success dated after now — the clock moved back — is stale, not fresh until then. */
    @Test fun aClockMovedBackBeforeTheLastSuccessIsStale() = runBlocking {
        val graph = graph()
        graph.connect(cadence = SyncCadence.DAILY)
        graph.heater("a")
        graph.haStateReader.answer = { HaReadOutcome.Observed(HaSwitchState.OFF, null) }
        graph.seasonSyncRunner.syncNow()
        val success = checkNotNull(graph.binding("a").lastSuccessAt)
        graph.haStateReader.reads.clear()

        graph.seasonSyncRunner.refreshIfStale(success)
        assertEquals("the same moment is fresh", emptyList<String>(), graph.readEntities())
        graph.seasonSyncRunner.refreshIfStale(success - 1)
        assertEquals(listOf(entityOf("a")), graph.readEntities())
    }

    @Test fun inertBindingsAreNeitherReadNorWritten() = runBlocking {
        val graph = graph()
        val connection = graph.connect()
        graph.heater("a")
        graph.heater("b")
        graph.heater("c")
        graph.stopSeasonSync.run(AssetId("a"))
        graph.archiveAsset.run(AssetId("b"))
        val stopped = graph.binding("a")
        val notMaintained = graph.binding("b")

        graph.seasonSyncRunner.syncNow()

        assertEquals(listOf(entityOf("c")), graph.readEntities())
        assertEquals(stopped, graph.binding("a"))
        assertEquals(notMaintained, graph.binding("b"))

        graph.secretStore.delete(connection.id)
        graph.haStateReader.reads.clear()
        val before = graph.seasonSyncBindings.all()
        graph.seasonSyncRunner.syncNow()
        assertEquals("without a token every binding is NEEDS_TOKEN: nothing read", emptyList<String>(), graph.readEntities())
        assertEquals(before, graph.seasonSyncBindings.all())
    }

    @Test fun anUndecryptableTokenRecordsNeedsTokenAndSendsNothing() = runBlocking {
        val aead = TamperingAead()
        val graph = graph(FakeGraph(secretStore = KeystoreSecretStore(kotlin.io.path.createTempDirectory("no-backup").toFile(), aead)))
        graph.connect()
        graph.heater("a")
        aead.tampered = true

        graph.seasonSyncRunner.syncNow()

        assertEquals(emptyList<String>(), graph.readEntities())
        val binding = graph.binding("a")
        assertEquals(SyncErrorKind.NEEDS_TOKEN, binding.errorKind)
        assertNull(binding.lastSuccessAt)
    }
}

/** The JDK's AES-GCM, whose tag check fails once [tampered]: the file is there, it cannot be decrypted. */
internal class TamperingAead(private val real: KeyedAead = JdkAead()) : KeyedAead by real {
    @Volatile var tampered = false

    @Volatile var broken = false

    override fun open(alias: String, sealed: ByteArray): ByteArray =
        if (tampered) throw AEADBadTagException("tampered") else real.open(alias, sealed)

    override fun hasKey(alias: String): Boolean =
        if (broken) throw GeneralSecurityException("the key store cannot load") else real.hasKey(alias)
}
