package com.loosecannon.servicetag.seasonsync

import com.loosecannon.servicetag.core.fetch.HostResolver
import com.loosecannon.servicetag.core.fetch.TransportFailure
import com.loosecannon.servicetag.core.hapublish.HaServiceAction
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.RemoteRead
import com.loosecannon.servicetag.core.hapublish.publishKindOf
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.CurrentNetwork
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome.NoDecision
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.hapublish.CallOutcome
import com.loosecannon.servicetag.hapublish.HomeAssistantPublishClient
import java.net.URL
import java.net.URLConnection
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Row 51 (#113/#114 C21): the request gate #16's client made inline, now one class both clients call. Each refusal is
 * the gate's answer and the same answer from the state client's read, the publish client's read and its service call,
 * with nothing opened by any of them; and the gate itself carries no request timeout (F-7), so each client's own
 * whole-call bound is the only one. Every value is fictional: `192.168.0.10`, `ha.example`, `ExampleHomeWifi`,
 * `fictional-token-1`.
 */
class HaRequestGateTest {

    @Test
    fun theGateAllowsTheCanonicalUrlAndItsAnswerNamesNoUrl() = runBlocking {
        val h = Harness()

        val answer = h.gate().check(HOME_HTTP, STATES_PATH)

        assertTrue(answer is HaRequestGate.Answer.Allowed)
        assertEquals(URL("http://192.168.0.10:8123$STATES_PATH"), (answer as HaRequestGate.Answer.Allowed).url)
        assertFalse("the answer's text names no address", answer.toString().contains("192.168.0.10"))
        assertEquals("the gate opens nothing itself", emptyList<URL>(), h.opened)
    }

    @Test
    fun anEndpointRefusalOpensNothingForBothClients() = runBlocking {
        val documentation = HOME_HTTP.copy(baseUrl = "http://192.0.2.10:8123")
        val h = Harness()

        h.assertRefusedEverywhere(documentation, SyncErrorKind.ENDPOINT_REFUSED, null)
        assertEquals("refused before the network is read", 0, h.networkReads.get())
    }

    @Test
    fun aDeniedPermissionOpensNothingForBothClients() = runBlocking {
        val h = Harness(granted = false)

        h.assertRefusedEverywhere(HOME_HTTP, SyncErrorKind.DENIED, null)
        assertEquals("a quiet status: the network is not even read", 0, h.networkReads.get())
    }

    @Test
    fun offTheHomeNetworkEachCauseOpensNothingForBothClients() = runBlocking {
        val readings = listOf(
            NetworkReading(CurrentNetwork.Other, null) to null,
            NetworkReading(CurrentNetwork.Wifi("OtherWifi"), null) to null,
            NetworkReading(CurrentNetwork.WifiUnnamed, UnconfirmedCause.LOCATION_OFF) to "LOCATION_OFF",
            NetworkReading(CurrentNetwork.WifiUnnamed, UnconfirmedCause.PERMISSION_MISSING) to "PERMISSION_MISSING",
            NetworkReading(CurrentNetwork.WifiUnnamed, UnconfirmedCause.APPROXIMATE_ONLY) to "APPROXIMATE_ONLY",
            NetworkReading(CurrentNetwork.WifiUnnamed, UnconfirmedCause.NOT_FOREGROUND) to "NOT_FOREGROUND",
            NetworkReading(CurrentNetwork.Wired, UnconfirmedCause.WIRED) to "WIRED",
        )
        for ((reading, detail) in readings) {
            Harness(network = reading).assertRefusedEverywhere(HOME_HTTP, SyncErrorKind.NOT_ON_LOCAL_NETWORK, detail)
        }
    }

    @Test
    fun aNameThatIsNotPrivateOrDoesNotResolveOpensNothingForBothClients() = runBlocking {
        val cases = listOf(
            HostResolver { listOf(PUBLIC) } to SyncErrorKind.NAME_NOT_LOCAL,
            HostResolver { listOf(PRIVATE_V4, PUBLIC) } to SyncErrorKind.NAME_NOT_LOCAL,
            HostResolver { emptyList() } to SyncErrorKind.NAME_NOT_LOCAL,
            HostResolver { throw TransportFailure(TransportFailure.Kind.UNREACHABLE) } to SyncErrorKind.UNREACHABLE,
            HostResolver { throw TransportFailure(TransportFailure.Kind.TIMED_OUT) } to SyncErrorKind.TIMED_OUT,
        )
        for ((resolver, kind) in cases) {
            Harness(resolver = resolver).assertRefusedEverywhere(HOME_HTTPS_NAME, kind, null)
        }
    }

    @Test
    fun aHostDisagreementOpensNothingForBothClients() = runBlocking {
        val h = Harness(parse = { URL("http://192.168.0.11:8123/api/") })

        h.assertRefusedEverywhere(HOME_HTTP, SyncErrorKind.ENDPOINT_REFUSED, null)
    }

    @Test
    fun anUnparsableUrlOpensNothingForBothClients() = runBlocking {
        val h = Harness(parse = { throw java.net.MalformedURLException("no") })

        h.assertRefusedEverywhere(HOME_HTTP, SyncErrorKind.ENDPOINT_REFUSED, null)
    }

    /**
     * F-7: a network reading that takes a minute of virtual time still passes the gate — no deadline inside it — while
     * each client's own thirty-second bound, wrapped around gate and request, ends the call with nothing opened.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theGateCarriesNoRequestTimeout() = runTest {
        val h = Harness(slowNetworkMillis = 60_000)

        val answer = h.gate().check(HOME_HTTP, STATES_PATH)

        assertTrue("the gate waited the whole minute and allowed", answer is HaRequestGate.Answer.Allowed)
        assertEquals(60_000L, testScheduler.currentTime)

        val start = testScheduler.currentTime
        assertEquals(
            NoDecision(SyncErrorKind.TIMED_OUT, null),
            h.stateClient().read(HOME_HTTP.baseUrl, ENTITY, TOKEN),
        )
        assertEquals("the state client's own bound", 30_000L, testScheduler.currentTime - start)
        assertEquals(
            CallOutcome.Refused(PublishErrorKind.TIMED_OUT, null),
            h.publishClient().call(HOME_HTTP, HaServiceAction.SELECT_OPTION, HELPER, "UP", TOKEN),
        )
        assertEquals("the publish client's own bound", 60_000L, testScheduler.currentTime - start)
        assertEquals(emptyList<URL>(), h.opened)
    }

    /** One scripted network, resolver and parser; a gate or a client of either kind per call, sharing one `open`. */
    private class Harness(
        val network: NetworkReading = NetworkReading(CurrentNetwork.Wifi("ExampleHomeWifi"), null),
        val granted: Boolean = true,
        val resolver: HostResolver = HostResolver { listOf(PRIVATE_V4) },
        val parse: (String) -> URL = ::URL,
        val slowNetworkMillis: Long = 0,
    ) {
        val opened: MutableList<URL> = Collections.synchronizedList(mutableListOf())
        val networkReads = AtomicInteger()
        private val reader = CurrentNetworkReader {
            networkReads.incrementAndGet()
            if (slowNetworkMillis > 0) delay(slowNetworkMillis)
            network
        }
        private val open: (URL) -> URLConnection = { url ->
            opened += url
            throw AssertionError("nothing may be opened")
        }

        fun gate() = HaRequestGate({ granted }, reader, resolver, parse)

        fun stateClient(stored: HaConnection? = null) = HomeAssistantStateClient(
            settings = { stored ?: HOME_HTTP },
            networkPermissionGranted = { granted },
            currentNetwork = reader,
            resolver = resolver,
            io = EmptyCoroutineContext,
            parse = parse,
            open = open,
        )

        fun publishClient(stored: HaConnection? = null) = HomeAssistantPublishClient(
            settings = { stored ?: HOME_HTTP },
            networkPermissionGranted = { granted },
            currentNetwork = reader,
            resolver = resolver,
            io = EmptyCoroutineContext,
            parse = parse,
            open = open,
        )

        suspend fun assertRefusedEverywhere(connection: HaConnection, kind: SyncErrorKind, detail: String?) {
            val label = "$kind/$detail"
            assertEquals(label, HaRequestGate.Answer.Refused(kind, detail), gate().check(connection, STATES_PATH))
            assertEquals(label, NoDecision(kind, detail), stateClient(connection).read(connection, ENTITY, TOKEN))
            assertEquals(
                label,
                RemoteRead.Failed(publishKindOf(kind), detail),
                publishClient(connection).read(connection, HELPER, TOKEN),
            )
            assertEquals(
                label,
                CallOutcome.Refused(publishKindOf(kind), detail),
                publishClient(connection).call(connection, HaServiceAction.SELECT_OPTION, HELPER, "UP", TOKEN),
            )
            assertEquals("$label: nothing opened", emptyList<URL>(), opened)
        }
    }

    private companion object {
        const val ENTITY = "input_boolean.example_heater_in_season"
        const val HELPER = "input_select.example_heater_equipment_status"
        const val STATES_PATH = "/api/states/input_select.example_heater_equipment_status"
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
        val HOME_HTTPS_NAME = HOME_HTTP.copy(baseUrl = "https://ha.example:8123")
        val PRIVATE_V4 = byteArrayOf(192.toByte(), 168.toByte(), 0, 10)
        val PUBLIC = byteArrayOf(203.toByte(), 0, 113, 10)
    }
}
