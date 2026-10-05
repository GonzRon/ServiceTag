package com.loosecannon.servicetag.seasonsync

import com.loosecannon.servicetag.core.fetch.HostResolver
import com.loosecannon.servicetag.core.fetch.TransportFailure
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.CurrentNetwork
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaListOutcome
import com.loosecannon.servicetag.core.seasonsync.HaEntityCandidate
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome.NoDecision
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome.Observed
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLConnection
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rows 49–54b (#16 C19): the client over a fake Home Assistant behind the `open` seam — a scripted
 * [HttpURLConnection] per open, no socket (the plan's rule) — with a scripted network reader and resolver. Every
 * value is fictional: `192.168.0.10`, `ha.example`, `ExampleHomeWifi`, `fictional-token-1`.
 */
class HomeAssistantStateClientTest {

    @Test
    fun oneGetToTheStatesPathWithExactlyFourHeaders() = runBlocking {
        val h = Harness()

        val outcome = h.client().read(HOME_HTTP, ENTITY, TOKEN)

        assertEquals(Observed(HaSwitchState.ON, LAST_CHANGED), outcome)
        assertEquals(listOf(URL("http://192.168.0.10:8123/api/states/$ENTITY")), h.ha.opened)
        val connection = h.ha.connections.single()
        assertEquals("GET", connection.methodAtHead)
        assertEquals(
            "exactly four request properties, in this order",
            listOf(
                "Authorization" to "Bearer fictional-token-1",
                "Accept" to "application/json",
                "Accept-Encoding" to "identity",
                "User-Agent" to "ServiceTag",
            ),
            connection.properties,
        )
        assertEquals(false, connection.cachesAtHead)
        assertEquals(10_000 to 15_000, connection.connectTimeoutAtHead to connection.readTimeoutAtHead)
    }

    @Test
    fun theBearerIsTheStoredToken() = runBlocking {
        val h = Harness()

        val outcome = h.client().read("http://192.168.0.10:8123", ENTITY, Secret("fictional-token-2"))

        assertEquals(Observed(HaSwitchState.ON, LAST_CHANGED), outcome)
        assertEquals("Bearer fictional-token-2", h.ha.connections.single().header("Authorization"))
    }

    @Test
    fun testConnectionGetsTheApiRootAndMapsItsOwnAnswers() = runBlocking {
        val cases = listOf(
            Script(200, body = """{"message":"API running."}""".toByteArray()) to ConnectionTestOutcome.Ok,
            Script(200, contentType = "text/html", body = "<html/>".toByteArray()) to failure(SyncErrorKind.MALFORMED),
            Script(200, body = "[]".toByteArray()) to failure(SyncErrorKind.MALFORMED),
            Script(401) to failure(SyncErrorKind.AUTH_REFUSED),
            Script(403) to failure(SyncErrorKind.AUTH_REFUSED),
            Script(302, location = "http://192.168.0.11:8123/api/") to failure(SyncErrorKind.REDIRECTED),
            Script(404) to failure(SyncErrorKind.HTTP_ERROR, "404"),
            Script(500) to failure(SyncErrorKind.HTTP_ERROR, "500"),
        )
        for ((script, expected) in cases) {
            val h = Harness().apply { ha.answer = { script } }
            assertEquals("${script.status} ${script.contentType}", expected, h.client().testConnection(HOME_HTTP, TOKEN))
            assertEquals(listOf(URL("http://192.168.0.10:8123/api/")), h.ha.opened)
        }
        val refused = Harness(network = NetworkReading(CurrentNetwork.Other, null))
        assertEquals(
            "the same rules: off the home network nothing is opened",
            failure(SyncErrorKind.NOT_ON_LOCAL_NETWORK),
            refused.client().testConnection(HOME_HTTP, TOKEN),
        )
        assertEquals(emptyList<URL>(), refused.ha.opened)
    }

    @Test
    fun redirectsAreNotFollowedAndNoSecondConnectionOpens() = runBlocking {
        val h = Harness().apply {
            ha.answer = { url ->
                if (url.host == "192.168.0.10") Script(302, location = "http://192.168.0.11:8123/api/states/$ENTITY")
                else Script(200, body = stateJson("on"))
            }
        }

        val outcome = h.client().read(HOME_HTTP, ENTITY, TOKEN)

        assertEquals(NoDecision(SyncErrorKind.REDIRECTED, null), outcome)
        assertEquals("one open, the configured origin only", 1, h.ha.opened.size)
        assertEquals(false, h.ha.connections.single().followAtHead)
    }

    @Test
    fun aRefusedAddressOpensNothing() = runBlocking {
        val h = Harness()
        val documentation = HOME_HTTP.copy(baseUrl = "http://192.0.2.10:8123")

        assertEquals(NoDecision(SyncErrorKind.ENDPOINT_REFUSED, null), h.client().read(documentation, ENTITY, TOKEN))
        assertEquals(emptyList<URL>(), h.ha.opened)
        assertEquals("refused before the network is read", 0, h.networkReads.get())

        val port = h.client(stored = HOME_HTTP)
        assertEquals(
            "the port sends only to the stored address",
            NoDecision(SyncErrorKind.ENDPOINT_REFUSED, null),
            port.read("http://192.168.0.11:8123", ENTITY, TOKEN),
        )
        assertEquals(
            "and to nothing when no connection is stored",
            NoDecision(SyncErrorKind.ENDPOINT_REFUSED, null),
            h.client(stored = null).read("http://192.168.0.10:8123", ENTITY, TOKEN),
        )
        assertEquals(emptyList<URL>(), h.ha.opened)
    }

    @Test
    fun aHostDisagreementIsEndpointRefused() = runBlocking {
        val h = Harness()
        val otherHost = h.client(parse = { URL("http://192.168.0.11:8123/api/states/$ENTITY") })

        assertEquals(NoDecision(SyncErrorKind.ENDPOINT_REFUSED, null), otherHost.read(HOME_HTTP, ENTITY, TOKEN))
        assertEquals(emptyList<URL>(), h.ha.opened)

        val caseOnly = h.client(parse = { URL(it.replace("ha.example", "HA.EXAMPLE")) })
        assertEquals(
            "ASCII case alone is agreement",
            Observed(HaSwitchState.ON, LAST_CHANGED),
            caseOnly.read(ANY_HTTPS_NAME, ENTITY, TOKEN),
        )
    }

    @Test
    fun aBodyOverSixtyFourKibIsTruncatedThenMalformed() = runBlocking {
        val over = Harness().apply { ha.answer = { Script(200, body = paddedStateJson(64 * 1024 + 1)) } }
        assertEquals(NoDecision(SyncErrorKind.MALFORMED, null), over.client().read(HOME_HTTP, ENTITY, TOKEN))

        val exactly = Harness().apply { ha.answer = { Script(200, body = paddedStateJson(64 * 1024)) } }
        assertEquals(
            "exactly 64 KiB is whole",
            Observed(HaSwitchState.ON, LAST_CHANGED),
            exactly.client().read(HOME_HTTP, ENTITY, TOKEN),
        )
    }

    // --- #105 (B2): the setup sheet's one foreground list read ---------------------------------------------------

    /** Row 9: `GET <base>/api/states` with the four headers, no redirect, no cache; the array maps to candidates. */
    @Test
    fun listStatesGetsTheStatesPathWithTheSameHeadersAndMapsTheArray() = runBlocking {
        val h = Harness().apply { ha.answer = { Script(200, body = statesJson()) } }

        val outcome = h.client().listStates(HOME_HTTP, TOKEN)

        assertEquals(
            HaListOutcome.Listed(
                listOf(
                    HaEntityCandidate(ENTITY, "Example Heater In Season"),
                    HaEntityCandidate("input_boolean.example_pump", null),
                ),
            ),
            outcome,
        )
        assertEquals(listOf(URL("http://192.168.0.10:8123/api/states")), h.ha.opened)
        val connection = h.ha.connections.single()
        assertEquals("GET", connection.methodAtHead)
        assertEquals(false, connection.followAtHead)
        assertEquals(false, connection.cachesAtHead)
        assertEquals(
            listOf(
                "Authorization" to "Bearer fictional-token-1",
                "Accept" to "application/json",
                "Accept-Encoding" to "identity",
                "User-Agent" to "ServiceTag",
            ),
            connection.properties,
        )
        assertEquals(10_000 to 15_000, connection.connectTimeoutAtHead to connection.readTimeoutAtHead)
    }

    /** Row 9: the same gate — off the home Wi-Fi nothing opens; a refused address opens nothing; 401 is AUTH_REFUSED. */
    @Test
    fun listStatesIsUnderTheSameGateAndFailureMap() = runBlocking {
        val elsewhere = Harness(network = NetworkReading(CurrentNetwork.Other, null))
        assertEquals(
            HaListOutcome.Failed(SyncErrorKind.NOT_ON_LOCAL_NETWORK, null),
            elsewhere.client().listStates(HOME_HTTP, TOKEN),
        )
        assertEquals(emptyList<URL>(), elsewhere.ha.opened)

        val refused = Harness()
        assertEquals(
            HaListOutcome.Failed(SyncErrorKind.ENDPOINT_REFUSED, null),
            refused.client().listStates(HOME_HTTP.copy(baseUrl = "http://192.0.2.10:8123"), TOKEN),
        )
        assertEquals(emptyList<URL>(), refused.ha.opened)

        val denied = Harness().apply { ha.answer = { Script(401) } }
        assertEquals(HaListOutcome.Failed(SyncErrorKind.AUTH_REFUSED, null), denied.client().listStates(HOME_HTTP, TOKEN))

        val missing = Harness().apply { ha.answer = { Script(404) } }
        assertEquals(
            "a 404 on the list is an HTTP error, not a missing entity",
            HaListOutcome.Failed(SyncErrorKind.HTTP_ERROR, "404"),
            missing.client().listStates(HOME_HTTP, TOKEN),
        )
    }

    /** Row 10: the list reads up to 8 MiB and no further; the poll's 64 KiB cap is untouched. */
    @Test
    fun listStatesReadsUpToEightMebibytesAndThePollStaysAtSixtyFourKib() = runBlocking {
        val cap = 8 * 1024 * 1024
        val over = Harness().apply { ha.answer = { Script(200, body = paddedStatesJson(cap + 1)) } }
        assertEquals(
            HaListOutcome.Failed(SyncErrorKind.MALFORMED, null),
            over.client().listStates(HOME_HTTP, TOKEN),
        )

        val exactly = Harness().apply { ha.answer = { Script(200, body = paddedStatesJson(cap)) } }
        assertEquals(
            "exactly 8 MiB is whole",
            HaListOutcome.Listed(listOf(HaEntityCandidate(ENTITY, null))),
            exactly.client().listStates(HOME_HTTP, TOKEN),
        )

        val poll = Harness().apply { ha.answer = { Script(200, body = paddedStateJson(64 * 1024 + 1)) } }
        assertEquals(
            "the poll's cap did not move",
            NoDecision(SyncErrorKind.MALFORMED, null),
            poll.client().read(HOME_HTTP, ENTITY, TOKEN),
        )
    }

    /** Review NOTE-5: the cap bounds what is read, not only what is kept. */
    @Test
    fun neverReadsPastTheCapFromALargeBody() = runBlocking {
        val consumed = AtomicLong()
        val mebibyte = paddedStateJson(1024 * 1024)
        val h = Harness().apply {
            ha.answer = {
                Script(200, stream = {
                    object : FilterInputStream(ByteArrayInputStream(mebibyte)) {
                        override fun read(): Int = super.read().also { if (it >= 0) consumed.incrementAndGet() }

                        override fun read(b: ByteArray, off: Int, len: Int): Int =
                            super.read(b, off, len).also { if (it > 0) consumed.addAndGet(it.toLong()) }
                    }
                })
            }
        }

        assertEquals(NoDecision(SyncErrorKind.MALFORMED, null), h.client().read(HOME_HTTP, ENTITY, TOKEN))
        assertTrue("read ${consumed.get()} bytes of a 1 MiB body", consumed.get() <= 64 * 1024 + 1)
    }

    /** Ordered, not raced (review MINOR-1): the head is held on an IO thread before virtual time moves. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theWholeCallStopsAtThirtySeconds() = runTest {
        val h = Harness().apply { ha.answer = { Script(200, holdHead = true) } }
        val client = h.client(io = Dispatchers.IO)

        val pending = async { client.read(HOME_HTTP, ENTITY, TOKEN) }
        testScheduler.runCurrent()
        assertTrue("the head is asked for", h.ha.headRequested.await(5, TimeUnit.SECONDS))
        testScheduler.advanceTimeBy(29_999)
        assertTrue("still waiting just before thirty seconds", pending.isActive)
        testScheduler.advanceTimeBy(2)
        val outcome = pending.await()

        assertEquals(NoDecision(SyncErrorKind.TIMED_OUT, null), outcome)
        assertTrue("the deadline disconnects", h.ha.connections.single().disconnects.get() >= 1)
    }

    @Test
    fun aCancelDisconnects() = runBlocking {
        val h = Harness().apply { ha.answer = { Script(200, holdHead = true) } }
        val client = h.client(io = Dispatchers.IO)

        val pending = async(Dispatchers.IO) { client.read(HOME_HTTP, ENTITY, TOKEN) }
        assertTrue(h.ha.headRequested.await(5, TimeUnit.SECONDS))
        pending.cancel()
        assertTrue("the cancel disconnects", h.ha.connections.single().released.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { pending.join() }

        assertTrue("the read ends cancelled, never with an answer", pending.isCancelled)
    }

    @Test
    fun noOutcomeExceptionOrToStringCarriesTheUrlHostEntityOrToken() = runBlocking {
        val spoken = "http://192.168.0.10:8123/api/states/$ENTITY Bearer fictional-token-1 ExampleHomeWifi"
        val failures = listOf<(Harness) -> Unit>(
            { it.ha.openFailure = IOException("connect $spoken") },
            { it.ha.answer = { Script(0, headFailure = IOException("reset by $spoken")) } },
            { it.ha.answer = { Script(0, headFailure = SocketTimeoutException("timeout $spoken")) } },
            { it.ha.answer = { Script(0, headFailure = SSLHandshakeException("bad certificate $spoken")) } },
            { it.ha.answer = { Script(200, bodyFailure = IOException("body $spoken")) } },
            { it.ha.answer = { Script(0, headFailure = IllegalArgumentException("host $spoken")) } },
        )
        val outcomes = failures.map { script ->
            val h = Harness().also(script)
            h.client().read(HOME_HTTP, ENTITY, TOKEN)
        }
        val texts = outcomes.map(HaReadOutcome::toString) +
            NetworkReading(CurrentNetwork.Wifi("ExampleHomeWifi"), null).toString() +
            DefaultNetworkAnswer(DefaultNetworkKind.WIFI, "\"ExampleHomeWifi\"").toString()

        for (text in texts) {
            for (secret in listOf("192.168.0.10", "8123", ENTITY, "fictional-token-1", "ExampleHomeWifi", "Bearer")) {
                assertFalse("'$text' carries '$secret'", text.contains(secret))
            }
        }
        assertTrue("every failure is an outcome, never a throw", outcomes.all { it is NoDecision })
    }

    @Test
    fun anSslExceptionIsTlsFailedNotUnreachable() = runBlocking {
        val direct = Harness().apply { ha.answer = { Script(0, headFailure = SSLHandshakeException("handshake")) } }
        assertEquals(NoDecision(SyncErrorKind.TLS_FAILED, null), direct.client().read(HOME_HTTPS, ENTITY, TOKEN))

        val wrapped = Harness().apply {
            ha.answer = { Script(0, headFailure = IOException(SSLHandshakeException("handshake"))) }
        }
        assertEquals(NoDecision(SyncErrorKind.TLS_FAILED, null), wrapped.client().read(HOME_HTTPS, ENTITY, TOKEN))
    }

    @Test
    fun aDeniedPermissionAnswersDeniedAndOpensNothing() = runBlocking {
        val h = Harness(granted = false)

        assertEquals(NoDecision(SyncErrorKind.DENIED, null), h.client().read(HOME_HTTP, ENTITY, TOKEN))
        assertEquals(emptyList<URL>(), h.ha.opened)
        assertEquals("a quiet status: the network is not even read", 0, h.networkReads.get())
    }

    @Test
    fun homeModeOnTheCapturedWifiProceeds() = runBlocking {
        for (connection in listOf(HOME_HTTP, HOME_HTTPS)) {
            val h = Harness()
            assertEquals(Observed(HaSwitchState.ON, LAST_CHANGED), h.client().read(connection, ENTITY, TOKEN))
            assertEquals("one read of the network per request", 1, h.networkReads.get())
            assertEquals(1, h.ha.opened.size)
        }
    }

    @Test
    fun anotherWifiCellularOrNoneIsNotOnLocalNetworkWithNoDetail() = runBlocking {
        val elsewhere = listOf(
            CurrentNetwork.Wifi("NeighbourWifi"),
            CurrentNetwork.Wifi("examplehomewifi"),
            CurrentNetwork.Other,
            CurrentNetwork.None,
        )
        for (network in elsewhere) {
            val h = Harness(network = NetworkReading(network, null))
            assertEquals(
                "$network",
                NoDecision(SyncErrorKind.NOT_ON_LOCAL_NETWORK, null),
                h.client().read(HOME_HTTP, ENTITY, TOKEN),
            )
            assertEquals(emptyList<URL>(), h.ha.opened)
        }
    }

    @Test
    fun eachHiddenNameCauseIsUnconfirmedWithItsDetail() = runBlocking {
        val causes = listOf(
            UnconfirmedCause.LOCATION_OFF,
            UnconfirmedCause.PERMISSION_MISSING,
            UnconfirmedCause.APPROXIMATE_ONLY,
            UnconfirmedCause.NOT_FOREGROUND,
        )
        for (cause in causes) {
            val h = Harness(network = NetworkReading(CurrentNetwork.WifiUnnamed, cause))
            assertEquals(
                NoDecision(SyncErrorKind.NOT_ON_LOCAL_NETWORK, cause.name),
                h.client().read(HOME_HTTP, ENTITY, TOKEN),
            )
            assertEquals(emptyList<URL>(), h.ha.opened)
        }
    }

    @Test
    fun homeModeOnAWiredNetworkIsNotEligibleWithDetailWired() = runBlocking {
        val h = Harness(network = NetworkReading(CurrentNetwork.Wired, UnconfirmedCause.WIRED))

        assertEquals(NoDecision(SyncErrorKind.NOT_ON_LOCAL_NETWORK, "WIRED"), h.client().read(HOME_HTTP, ENTITY, TOKEN))
        assertEquals(emptyList<URL>(), h.ha.opened)
    }

    @Test
    fun anyNetworkHttpsProceedsOnAnyTransport() = runBlocking {
        val anywhere = listOf(
            NetworkReading(CurrentNetwork.Other, null),
            NetworkReading(CurrentNetwork.None, null),
            NetworkReading(CurrentNetwork.Wired, UnconfirmedCause.WIRED),
            NetworkReading(CurrentNetwork.WifiUnnamed, UnconfirmedCause.LOCATION_OFF),
            NetworkReading(CurrentNetwork.Wifi("NeighbourWifi"), null),
        )
        for (network in anywhere) {
            val h = Harness(network = network)
            assertEquals(Observed(HaSwitchState.ON, LAST_CHANGED), h.client().read(ANY_HTTPS_LITERAL, ENTITY, TOKEN))
            assertEquals("Any network asks nothing of the network", 0, h.networkReads.get())
        }
    }

    @Test
    fun anHttpRowStoredWithAnyNetworkIsStillNetworkChecked() = runBlocking {
        val httpAny = HOME_HTTP.copy(networkEligibility = NetworkEligibility.ANY_NETWORK, homeNetworkSsid = null)
        for (network in listOf(CurrentNetwork.Other, CurrentNetwork.Wifi("ExampleHomeWifi"))) {
            val h = Harness(network = NetworkReading(network, null))
            assertEquals(
                "$network: fail closed",
                NoDecision(SyncErrorKind.NOT_ON_LOCAL_NETWORK, null),
                h.client().read(httpAny, ENTITY, TOKEN),
            )
            assertEquals(emptyList<URL>(), h.ha.opened)
        }
    }

    @Test
    fun aNameResolvingToAPublicAddressIsNameNotLocalAndOpensNothing() = runBlocking {
        val answers = listOf(listOf(PUBLIC), listOf(DOC_V6), listOf(LOOPBACK), emptyList())
        for (answer in answers) {
            val h = Harness(resolver = HostResolver { answer })
            assertEquals(NoDecision(SyncErrorKind.NAME_NOT_LOCAL, null), h.client().read(HOME_HTTPS_NAME, ENTITY, TOKEN))
            assertEquals(emptyList<URL>(), h.ha.opened)
        }
    }

    @Test
    fun aNameResolvingOnlyToPrivateAddressesProceeds() = runBlocking {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val h = Harness(resolver = HostResolver { host -> asked += host; listOf(PRIVATE_V4, OTHER_PRIVATE_V4, ULA) })

        assertEquals(Observed(HaSwitchState.ON, LAST_CHANGED), h.client().read(HOME_HTTPS_NAME, ENTITY, TOKEN))
        assertEquals(listOf("ha.example"), asked)
        assertEquals(listOf(URL("https://ha.example:8123/api/states/$ENTITY")), h.ha.opened)
    }

    @Test
    fun onePublicAnswerAmongPrivateOnesRefuses() = runBlocking {
        val h = Harness(resolver = HostResolver { listOf(PRIVATE_V4, PUBLIC, ULA) })

        assertEquals(NoDecision(SyncErrorKind.NAME_NOT_LOCAL, null), h.client().read(HOME_HTTPS_NAME, ENTITY, TOKEN))
        assertEquals(emptyList<URL>(), h.ha.opened)
    }

    @Test
    fun aResolutionFailureIsUnreachable() = runBlocking {
        val kinds = mapOf(
            TransportFailure.Kind.UNREACHABLE to SyncErrorKind.UNREACHABLE,
            TransportFailure.Kind.INTERRUPTED to SyncErrorKind.UNREACHABLE,
            TransportFailure.Kind.TIMED_OUT to SyncErrorKind.TIMED_OUT,
            TransportFailure.Kind.DENIED to SyncErrorKind.DENIED,
        )
        for ((thrown, expected) in kinds) {
            val h = Harness(resolver = HostResolver { throw TransportFailure(thrown) })
            assertEquals("$thrown", NoDecision(expected, null), h.client().read(HOME_HTTPS_NAME, ENTITY, TOKEN))
            assertEquals(emptyList<URL>(), h.ha.opened)
        }
    }

    @Test
    fun anyNetworkNameResolvingPubliclyProceeds() = runBlocking {
        val resolved = AtomicInteger()
        val h = Harness(resolver = HostResolver { resolved.incrementAndGet(); listOf(PUBLIC) })

        assertEquals(Observed(HaSwitchState.ON, LAST_CHANGED), h.client().read(ANY_HTTPS_NAME, ENTITY, TOKEN))
        assertEquals("no private-answer check in Any network (limit 18)", 0, resolved.get())
    }

    /** One fake Home Assistant, one scripted network, one scripted resolver; a client per call of [client]. */
    private class Harness(
        val network: NetworkReading = NetworkReading(CurrentNetwork.Wifi("ExampleHomeWifi"), null),
        val granted: Boolean = true,
        val resolver: HostResolver = HostResolver { listOf(PRIVATE_V4) },
    ) {
        val ha = FakeHa()
        val networkReads = AtomicInteger()

        fun client(
            stored: HaConnection? = HOME_HTTP,
            io: CoroutineContext = EmptyCoroutineContext,
            parse: (String) -> URL = ::URL,
        ) = HomeAssistantStateClient(
            settings = { stored },
            networkPermissionGranted = { granted },
            currentNetwork = CurrentNetworkReader { networkReads.incrementAndGet(); network },
            resolver = resolver,
            io = io,
            parse = parse,
            open = ha.open,
        )
    }

    private companion object {
        const val ENTITY = "input_boolean.example_heater_in_season"
        const val LAST_CHANGED = "2026-10-01T06:00:00.000000+00:00"
        val TOKEN = Secret("fictional-token-1")
        val HOME_HTTP = HaConnection(
            id = "conn-1",
            baseUrl = "http://192.168.0.10:8123",
            cadence = SyncCadence.DAILY,
            networkEligibility = NetworkEligibility.HOME_NETWORK_ONLY,
            homeNetworkSsid = "ExampleHomeWifi",
            backgroundChecks = BackgroundChecks.OFF,
            createdAt = 1_759_000_000_000L,
            updatedAt = 1_759_000_000_000L,
        )
        val HOME_HTTPS = HOME_HTTP.copy(baseUrl = "https://192.168.0.10:8123")
        val HOME_HTTPS_NAME = HOME_HTTP.copy(baseUrl = "https://ha.example:8123")
        val ANY_HTTPS_NAME = HOME_HTTPS_NAME.copy(
            networkEligibility = NetworkEligibility.ANY_NETWORK,
            homeNetworkSsid = null,
        )
        val ANY_HTTPS_LITERAL = ANY_HTTPS_NAME.copy(baseUrl = "https://192.168.0.10:8123")
        val PRIVATE_V4 = byteArrayOf(192.toByte(), 168.toByte(), 0, 10)
        val OTHER_PRIVATE_V4 = byteArrayOf(10, 0, 0, 5)
        val ULA = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 0x10 }
        val PUBLIC = byteArrayOf(203.toByte(), 0, 113, 10)
        val DOC_V6 = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[2] = 0x0d; it[3] = 0xb8.toByte(); it[15] = 1 }
        val LOOPBACK = byteArrayOf(127, 0, 0, 1)

        fun failure(kind: SyncErrorKind, detail: String? = null) = ConnectionTestOutcome.Failed(kind, detail)

        /** A states list of two fictional helpers, one named, one not (#105). */
        fun statesJson(): ByteArray = (
            """[{"entity_id":"$ENTITY","state":"on","attributes":{"friendly_name":"Example Heater In Season"}},""" +
                """{"entity_id":"input_boolean.example_pump","state":"off","attributes":{}}]"""
            ).toByteArray()

        /** A valid one-entity list of exactly [size] bytes: the padding is an attribute (#105). */
        fun paddedStatesJson(size: Int): ByteArray {
            val head = """[{"entity_id":"$ENTITY","state":"on","attributes":{"pad":""""
            val tail = "\"}}]"
            return (head + "x".repeat(size - head.length - tail.length) + tail).toByteArray().also {
                check(it.size == size)
            }
        }

        fun stateJson(state: String): ByteArray =
            """{"entity_id":"$ENTITY","state":"$state","last_changed":"$LAST_CHANGED","attributes":{}}""".toByteArray()

        /** A valid answer of exactly [size] bytes: the padding is an attribute. */
        fun paddedStateJson(size: Int): ByteArray {
            val head = """{"entity_id":"$ENTITY","state":"on","last_changed":"$LAST_CHANGED","attributes":{"pad":""""
            val tail = "\"}}"
            return (head + "x".repeat(size - head.length - tail.length) + tail).toByteArray().also {
                check(it.size == size)
            }
        }
    }
}

/** One scripted answer: a status, or a failure of the head or the body; [holdHead] waits for a disconnect. */
private class Script(
    val status: Int,
    val contentType: String? = "application/json",
    val body: ByteArray = """{"entity_id":"input_boolean.example_heater_in_season","state":"on",""".toByteArray() +
        """"last_changed":"2026-10-01T06:00:00.000000+00:00","attributes":{}}""".toByteArray(),
    val location: String? = null,
    val headFailure: Throwable? = null,
    val bodyFailure: IOException? = null,
    val holdHead: Boolean = false,
    val stream: (() -> InputStream)? = null,
)

/** The fake Home Assistant behind the `open` seam: every open recorded, a scripted connection answered. */
private class FakeHa {
    val opened: MutableList<URL> = Collections.synchronizedList(mutableListOf())
    val connections: MutableList<FakeConnection> = Collections.synchronizedList(mutableListOf())
    val headRequested = CountDownLatch(1)
    var answer: (URL) -> Script = { Script(200) }
    var openFailure: Throwable? = null

    val open: (URL) -> URLConnection = { url ->
        opened += url
        openFailure?.let { throw it }
        FakeConnection(url, answer(url), this).also { connections += it }
    }
}

/**
 * A scripted connection. It records what the client set, as it stood when the head was asked for; with
 * `instanceFollowRedirects` still true it does what the platform would do with a 3xx — opens the target through the
 * same seam — so a followed redirect shows as a second open. [released] opens on the first disconnect.
 */
private class FakeConnection(url: URL, private val script: Script, private val ha: FakeHa) : HttpURLConnection(url) {
    val properties: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    val disconnects = AtomicInteger()
    val released = CountDownLatch(1)
    var methodAtHead: String? = null
    var followAtHead: Boolean? = null
    var cachesAtHead: Boolean? = null
    var connectTimeoutAtHead = -1
    var readTimeoutAtHead = -1
    private var followed: HttpURLConnection? = null

    fun header(name: String): String? = properties.lastOrNull { it.first == name }?.second

    override fun setRequestProperty(key: String, value: String) {
        properties += key to value
    }

    override fun addRequestProperty(key: String, value: String) {
        properties += key to value
    }

    override fun getResponseCode(): Int {
        methodAtHead = requestMethod
        followAtHead = instanceFollowRedirects
        cachesAtHead = useCaches
        connectTimeoutAtHead = connectTimeout
        readTimeoutAtHead = readTimeout
        ha.headRequested.countDown()
        if (script.holdHead) {
            check(released.await(10, TimeUnit.SECONDS)) { "held head never released" }
            throw IOException("closed")
        }
        script.headFailure?.let { throw it }
        val target = script.location
        if (instanceFollowRedirects && script.status in 300..399 && target != null) {
            val next = ha.open(URL(target)) as HttpURLConnection
            followed = next
            return next.responseCode
        }
        return script.status
    }

    override fun getContentType(): String? = followed?.contentType ?: script.contentType

    override fun getInputStream(): InputStream {
        followed?.let { return it.inputStream }
        script.stream?.let { return it() }
        val failure = script.bodyFailure ?: return ByteArrayInputStream(script.body)
        return object : InputStream() {
            override fun read(): Int = throw failure
        }
    }

    override fun connect() = Unit

    override fun usingProxy() = false

    override fun disconnect() {
        disconnects.incrementAndGet()
        released.countDown()
    }
}
