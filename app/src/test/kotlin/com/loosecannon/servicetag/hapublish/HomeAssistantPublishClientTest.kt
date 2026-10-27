package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.core.fetch.HostResolver
import com.loosecannon.servicetag.core.hapublish.HaServiceAction
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.RemoteEntity
import com.loosecannon.servicetag.core.hapublish.RemoteRead
import com.loosecannon.servicetag.core.hapublish.RetryClass
import com.loosecannon.servicetag.core.hapublish.retryClassOf
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.CurrentNetwork
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.seasonsync.CurrentNetworkReader
import com.loosecannon.servicetag.seasonsync.NetworkReading
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Rows 52–56 and row 54a's client half (#113/#114 C22, C23): the publish client over a fake Home Assistant behind
 * the `open` seam — a scripted [HttpURLConnection] per open, no socket — with a scripted network reader and resolver.
 * A service call is `Refused` only before transmission starts (the moment the output stream is handed over) or for
 * an explicit refusal status; everything after is `Unknown`. Every value is fictional: `192.168.0.10`, `ha.example`,
 * `ExampleHomeWifi`, `fictional-token-1`.
 */
class HomeAssistantPublishClientTest {

    // ---- Row 52: the request, exactly ----

    @Test
    fun postsExactlyThePathBodyAndFiveHeaders() = runBlocking {
        val h = Harness()

        val outcome = h.client().call(HOME_HTTP, HaServiceAction.SELECT_OPTION, STATUS_HELPER, "UP", TOKEN)

        assertEquals(CallOutcome.Accepted, outcome)
        assertEquals(listOf(URL("http://192.168.0.10:8123/api/services/input_select/select_option")), h.ha.opened)
        val connection = h.ha.connections.single()
        assertEquals("POST", connection.methodAtHead)
        assertEquals(
            "exactly five request properties, in this order",
            listOf(
                "Authorization" to "Bearer fictional-token-1",
                "Accept" to "application/json",
                "Accept-Encoding" to "identity",
                "Content-Type" to "application/json",
                "User-Agent" to "ServiceTag",
            ),
            connection.properties,
        )
        val body = """{"entity_id":"input_select.example_heater_equipment_status","option":"UP"}"""
        assertEquals(body, connection.sent.toString(Charsets.UTF_8.name()))
        assertEquals("fixed-length streaming of exactly the body", body.length, connection.fixedLengthAtHead)
        assertEquals(true, connection.outputAtHead)
        assertEquals(10_000 to 15_000, connection.connectTimeoutAtHead to connection.readTimeoutAtHead)
        assertEquals("the body is closed before the status line is asked for", true, connection.closedAtHead)
        assertTrue("the connection is released", connection.disconnects.get() >= 1)
    }

    @Test
    fun theDateActionPostsADateBody() = runBlocking {
        for (date in listOf("2026-09-30", "1970-01-01")) {
            val h = Harness()

            val outcome = h.client().call(HOME_HTTP, HaServiceAction.SET_DATE, DATE_HELPER, date, TOKEN)

            assertEquals(CallOutcome.Accepted, outcome)
            assertEquals(
                listOf(URL("http://192.168.0.10:8123/api/services/input_datetime/set_datetime")),
                h.ha.opened,
            )
            val connection = h.ha.connections.single()
            assertEquals("POST", connection.methodAtHead)
            assertEquals(
                """{"entity_id":"input_datetime.example_heater_service_done","date":"$date"}""",
                connection.sent.toString(Charsets.UTF_8.name()),
            )
        }
    }

    @Test
    fun noRedirectNoCacheNoProxy() = runBlocking {
        val h = Harness().apply {
            ha.answer = { url ->
                if (url.host == "192.168.0.10") Script(302, location = "http://192.168.0.11:8123/api/services/x/y")
                else Script(200)
            }
        }

        val outcome = h.client().call(HOME_HTTP, HaServiceAction.SELECT_OPTION, STATUS_HELPER, "DOWN", TOKEN)

        assertEquals(CallOutcome.Refused(PublishErrorKind.REDIRECTED, null), outcome)
        assertEquals("one open, the configured origin only", 1, h.ha.opened.size)
        val connection = h.ha.connections.single()
        assertEquals(false, connection.followAtHead)
        assertEquals(false, connection.cachesAtHead)

        // The shipped default `open`: the URL's own handler is asked for a connection with no proxy, never the
        // system's selection.
        val proxies = Collections.synchronizedList(mutableListOf<Proxy?>())
        val handler = object : URLStreamHandler() {
            override fun openConnection(u: URL): URLConnection {
                proxies += null
                return FakeConnection(u, Script(200), h.ha)
            }

            override fun openConnection(u: URL, p: Proxy): URLConnection {
                proxies += p
                return FakeConnection(u, Script(200), h.ha)
            }
        }
        val direct = HomeAssistantPublishClient(
            settings = { HOME_HTTP },
            networkPermissionGranted = { true },
            currentNetwork = CurrentNetworkReader { HOME_WIFI },
            resolver = HostResolver { listOf(PRIVATE_V4) },
            io = EmptyCoroutineContext,
            parse = { spec -> @Suppress("DEPRECATION") URL(null, spec, handler) },
        )
        assertEquals(
            CallOutcome.Accepted,
            direct.call(HOME_HTTP, HaServiceAction.SELECT_OPTION, STATUS_HELPER, "UP", TOKEN),
        )
        assertTrue(direct.read(HOME_HTTP, STATUS_HELPER, TOKEN) is RemoteRead.Failed)
        assertEquals("both requests asked for a direct connection", listOf(Proxy.NO_PROXY, Proxy.NO_PROXY), proxies)
    }

    // ---- Row 53: each status of C23's table ----

    @Test
    fun eachStatusOfTheTableMapsToItsOutcome() = runBlocking {
        val cases = listOf(
            Script(200) to CallOutcome.Accepted,
            Script(400) to CallOutcome.Refused(PublishErrorKind.SERVICE_REFUSED, "DEGRADED"),
            Script(401) to CallOutcome.Refused(PublishErrorKind.AUTH_REFUSED, null),
            Script(403) to CallOutcome.Refused(PublishErrorKind.WRITE_FORBIDDEN, null),
            Script(404) to CallOutcome.Refused(PublishErrorKind.HTTP_ERROR, "404"),
            Script(302, location = "http://192.168.0.11:8123/") to
                CallOutcome.Refused(PublishErrorKind.REDIRECTED, null),
            Script(301) to CallOutcome.Refused(PublishErrorKind.REDIRECTED, null),
            Script(405) to CallOutcome.Refused(PublishErrorKind.HTTP_ERROR, "405"),
            Script(409) to CallOutcome.Refused(PublishErrorKind.HTTP_ERROR, "409"),
            Script(429) to CallOutcome.Refused(PublishErrorKind.HTTP_ERROR, "429"),
            Script(500) to CallOutcome.Unknown("500"),
        )
        for ((script, expected) in cases) {
            val h = Harness().apply { ha.answer = { script } }
            assertEquals(
                "${script.status}",
                expected,
                h.client().call(HOME_HTTP, HaServiceAction.SELECT_OPTION, STATUS_HELPER, "DEGRADED", TOKEN),
            )
            assertEquals("${script.status}: one open", 1, h.ha.opened.size)
        }
        assertEquals(
            "a 404's code reaches the retry class: a permanent refusal",
            RetryClass.NEEDS_OWNER,
            retryClassOf(PublishErrorKind.HTTP_ERROR, "404"),
        )
    }

    // ---- Row 54: the two phases ----

    @Test
    fun aConnectFailureBeforeTransmissionIsRefused() = runBlocking {
        val cases = listOf<Pair<(Harness) -> Unit, CallOutcome>>(
            { h: Harness -> h.ha.answer = { Script(0, connectFailure = ConnectException("refused")) } } to
                CallOutcome.Refused(PublishErrorKind.UNREACHABLE, null),
            { h: Harness -> h.ha.answer = { Script(0, connectFailure = UnknownHostException("gone")) } } to
                CallOutcome.Refused(PublishErrorKind.UNREACHABLE, null),
            { h: Harness -> h.ha.answer = { Script(0, connectFailure = SocketTimeoutException("connect")) } } to
                CallOutcome.Refused(PublishErrorKind.TIMED_OUT, null),
            { h: Harness -> h.ha.openFailure = IOException("open") } to
                CallOutcome.Refused(PublishErrorKind.UNREACHABLE, null),
        )
        for ((script, expected) in cases) {
            val h = Harness().also(script)
            assertEquals(
                expected,
                h.client().call(HOME_HTTP, HaServiceAction.SELECT_OPTION, STATUS_HELPER, "UP", TOKEN),
            )
            assertTrue("not a byte was sent", h.ha.connections.all { it.sent.size() == 0 })
        }
    }

    @Test
    fun aReadTimeoutAfterTheWriteIsUnknown() = runBlocking {
        val h = Harness().apply { ha.answer = { Script(0, headFailure = SocketTimeoutException("read timed out")) } }

        val outcome = h.client().call(HOME_HTTP, HaServiceAction.SELECT_OPTION, STATUS_HELPER, "UP", TOKEN)

        assertEquals(CallOutcome.Unknown(null), outcome)
        assertTrue("the whole body was sent", h.ha.connections.single().closedAtHead == true)
    }

    /** Ordered, not raced: the head is held on an IO thread before virtual time moves (the #16 test's shape). */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theWholeCallBoundAfterTransmissionIsUnknown() = runTest {
        val h = Harness().apply { ha.answer = { Script(200, holdHead = true) } }
        val client = h.client(io = Dispatchers.IO)

        val pending = async { client.call(HOME_HTTP, HaServiceAction.SET_DATE, DATE_HELPER, "2026-09-30", TOKEN) }
        testScheduler.runCurrent()
        assertTrue("the head is asked for", h.ha.headRequested.await(5, TimeUnit.SECONDS))
        testScheduler.advanceTimeBy(29_999)
        assertTrue("still waiting just before thirty seconds", pending.isActive)
        testScheduler.advanceTimeBy(2)

        assertEquals(CallOutcome.Unknown(null), pending.await())
        assertTrue("the deadline disconnects", h.ha.connections.single().disconnects.get() >= 1)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theWholeCallBoundBeforeTransmissionIsTimedOut() = runTest {
        val h = Harness().apply { ha.answer = { Script(200, holdConnect = true) } }
        val client = h.client(io = Dispatchers.IO)

        val pending = async { client.call(HOME_HTTP, HaServiceAction.SET_DATE, DATE_HELPER, "2026-09-30", TOKEN) }
        testScheduler.runCurrent()
        assertTrue("the connection is asked for", h.ha.connectRequested.await(5, TimeUnit.SECONDS))
        testScheduler.advanceTimeBy(30_001)

        assertEquals(CallOutcome.Refused(PublishErrorKind.TIMED_OUT, null), pending.await())
        assertEquals("not a byte was sent", 0, h.ha.connections.single().sent.size())
    }

    @Test
    fun every5xxIsUnknown() = runBlocking {
        for (status in listOf(500, 501, 502, 503, 504, 599)) {
            val h = Harness().apply { ha.answer = { Script(status) } }
            assertEquals(
                "$status",
                CallOutcome.Unknown(status.toString()),
                h.client().call(HOME_HTTP, HaServiceAction.SELECT_OPTION, STATUS_HELPER, "UP", TOKEN),
            )
        }
    }

    // ---- Row 54a: scripted failures after transmission started (A10) ----

    @Test
    fun afterTheLastByteBeforeCloseReturns() = runBlocking {
        val h = Harness().apply { ha.answer = { Script(200, closeFailure = IOException("reset")) } }

        val outcome = h.client().call(HOME_HTTP, HaServiceAction.SELECT_OPTION, STATUS_HELPER, "UP", TOKEN)

        assertEquals(CallOutcome.Unknown(null), outcome)
        val connection = h.ha.connections.single()
        assertEquals("every byte was written before close failed", STATUS_UP_BODY.length, connection.sent.size())
        assertEquals("the status line was never asked for", null, connection.closedAtHead)
    }

    @Test
    fun afterAPartialWrite() = runBlocking {
        val h = Harness().apply { ha.answer = { Script(200, writeFailureAfter = 10) } }

        val outcome = h.client().call(HOME_HTTP, HaServiceAction.SELECT_OPTION, STATUS_HELPER, "UP", TOKEN)

        assertEquals(CallOutcome.Unknown(null), outcome)
        assertEquals(10, h.ha.connections.single().sent.size())
    }

    @Test
    fun a500AfterTheServerChangedTheHelper() = runBlocking {
        val h = Harness().apply {
            ha.helpers[STATUS_HELPER] = "DOWN"
            ha.acts = true
            ha.answer = { url ->
                if (url.path.startsWith("/api/services/")) Script(500)
                else Script(200, body = selectJson(STATUS_HELPER, ha.helpers.getValue(STATUS_HELPER)))
            }
        }
        val client = h.client()

        val outcome = client.call(HOME_HTTP, HaServiceAction.SELECT_OPTION, STATUS_HELPER, "UP", TOKEN)

        assertEquals("HA acted, then answered 500: unknown, never refused", CallOutcome.Unknown("500"), outcome)
        val readBack = client.read(HOME_HTTP, STATUS_HELPER, TOKEN)
        assertEquals("the read-back sees what the call did", "UP", (readBack as RemoteRead.Answered).remote.state)
    }

    /**
     * C23: cancellation propagates and disconnects; the client never claims an outcome for a cancelled call. Row 54a's
     * `Unknown` is B4a's half: the client propagates the cancel (ruling 4), and the runner records the outcome.
     */
    @Test
    fun cancellationAroundCompletion() = runBlocking {
        val h = Harness().apply {
            ha.acts = true
            ha.answer = { Script(200, holdHead = true) }
        }
        val client = h.client(io = Dispatchers.IO)

        val pending = async(Dispatchers.IO) {
            client.call(HOME_HTTP, HaServiceAction.SELECT_OPTION, STATUS_HELPER, "UP", TOKEN)
        }
        assertTrue(h.ha.headRequested.await(5, TimeUnit.SECONDS))
        pending.cancel()
        assertTrue("the cancel disconnects", h.ha.connections.single().released.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { pending.join() }

        assertTrue("the call ends cancelled, never with an outcome", pending.isCancelled)
        assertEquals("HA had the whole body: the outcome is not known", "UP", h.ha.helpers[STATUS_HELPER])
    }

    @Test
    fun aFailureReadingTheAcceptedBodyIsUnknown() = runBlocking {
        val h = Harness().apply { ha.answer = { Script(200, bodyFailure = IOException("reset")) } }

        val outcome = h.client().call(HOME_HTTP, HaServiceAction.SELECT_OPTION, STATUS_HELPER, "UP", TOKEN)

        assertEquals(CallOutcome.Unknown(null), outcome)
    }

    // ---- Row 55: TLS and the cap ----

    @Test
    fun tlsFailureIsTlsFailed() = runBlocking {
        for (failure in listOf(SSLHandshakeException("handshake"), IOException(SSLHandshakeException("handshake")))) {
            val h = Harness().apply { ha.answer = { Script(0, connectFailure = failure) } }
            assertEquals(
                CallOutcome.Refused(PublishErrorKind.TLS_FAILED, null),
                h.client(stored = HOME_HTTPS)
                    .call(HOME_HTTPS, HaServiceAction.SELECT_OPTION, STATUS_HELPER, "UP", TOKEN),
            )
            val read = Harness().apply { ha.answer = { Script(0, headFailure = failure) } }
            assertEquals(
                RemoteRead.Failed(PublishErrorKind.TLS_FAILED, null),
                read.client(stored = HOME_HTTPS).read(HOME_HTTPS, STATUS_HELPER, TOKEN),
            )
        }
    }

    @Test
    fun a200BodyIsCappedAndDiscarded() = runBlocking {
        for (size in listOf(65 * 1024, 1024 * 1024)) {
            val consumed = AtomicInteger()
            val h = Harness().apply {
                val body = ByteArray(size) { 'x'.code.toByte() }
                ha.answer = { Script(200, stream = { countingStream(body, consumed) }) }
            }

            val outcome = h.client().call(HOME_HTTP, HaServiceAction.SELECT_OPTION, STATUS_HELPER, "UP", TOKEN)

            assertEquals(CallOutcome.Accepted, outcome)
            assertTrue("$size: at most 64 KiB read, was ${consumed.get()}", consumed.get() <= 64 * 1024)
        }
    }

    // ---- Row 56: nothing carries the URL, entity, body or token ----

    @Test
    fun noLogNoMessageNoToStringCarriesUrlEntityBodyOrToken() = runBlocking {
        val spoken = "http://192.168.0.10:8123/api/services/input_select/select_option $STATUS_HELPER " +
            "$STATUS_UP_BODY Bearer fictional-token-1 ExampleHomeWifi"
        val failures = listOf<(Harness) -> Unit>(
            { it.ha.openFailure = IOException("open $spoken") },
            { it.ha.answer = { Script(0, connectFailure = ConnectException("connect $spoken")) } },
            { it.ha.answer = { Script(0, connectFailure = SSLHandshakeException("tls $spoken")) } },
            { it.ha.answer = { Script(0, connectFailure = IllegalArgumentException("host $spoken")) } },
            { it.ha.answer = { Script(200, writeFailureAfter = 3) } },
            { it.ha.answer = { Script(200, closeFailure = IOException("close $spoken")) } },
            { it.ha.answer = { Script(0, headFailure = IOException("reset by $spoken")) } },
            { it.ha.answer = { Script(0, headFailure = SocketTimeoutException("timeout $spoken")) } },
            { it.ha.answer = { Script(200, bodyFailure = IOException("body $spoken")) } },
            { it.ha.answer = { Script(400) } },
            { it.ha.answer = { Script(403) } },
            { it.ha.answer = { Script(503) } },
        )
        val texts = mutableListOf<String>()
        for (script in failures) {
            texts += Harness().also(script).client()
                .call(HOME_HTTP, HaServiceAction.SELECT_OPTION, STATUS_HELPER, "UP", TOKEN).toString()
            texts += Harness().also(script).client().read(HOME_HTTP, STATUS_HELPER, TOKEN).toString()
        }
        // The one throw: a value outside the action's shape is a caller's bug, and its message names neither.
        try {
            Harness().client().call(HOME_HTTP, HaServiceAction.SELECT_OPTION, STATUS_HELPER, "SECRET_VALUE", TOKEN)
            fail("a value outside the three options is never sent")
        } catch (e: IllegalArgumentException) {
            texts += e.message.orEmpty()
            assertFalse(e.message.orEmpty().contains("SECRET_VALUE"))
        }

        val secrets = listOf(
            "192.168.0.10", "8123", "example_heater", "entity_id", "fictional-token-1", "Bearer", "ExampleHomeWifi",
            "/api/",
        )
        for (text in texts) {
            for (secret in secrets) {
                assertFalse("'$text' carries '$secret'", text.contains(secret))
            }
        }

        // No log seam exists to capture: the client and the gate write no line at all (G-list: none of theirs).
        for (file in clientSources()) {
            val source = file.readText()
            for (call in listOf("Log.", "println(", "System.out", "System.err", "printStackTrace")) {
                assertFalse("${file.name} must not call $call", source.contains(call))
            }
        }
    }

    // ---- C22's read: one GET of a helper through the same gate ----

    @Test
    fun readGetsTheStatesPathWithFourHeadersAndMapsTheHelper() = runBlocking {
        val h = Harness().apply {
            ha.answer = { Script(200, body = selectJson(STATUS_HELPER, "DEGRADED")) }
        }

        val outcome = h.client().read(HOME_HTTP, STATUS_HELPER, TOKEN)

        assertEquals(
            RemoteRead.Answered(RemoteEntity(STATUS_HELPER, "DEGRADED", listOf("UP", "DEGRADED", "DOWN"), null, null)),
            outcome,
        )
        assertEquals(listOf(URL("http://192.168.0.10:8123/api/states/$STATUS_HELPER")), h.ha.opened)
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
        assertEquals(false, connection.followAtHead)
        assertEquals(false, connection.cachesAtHead)
        assertEquals(false, connection.outputAtHead)
        assertEquals(10_000 to 15_000, connection.connectTimeoutAtHead to connection.readTimeoutAtHead)
    }

    @Test
    fun aReadMapsEachAnswerAndCarriesTheStatusForTheRetryClass() = runBlocking {
        val cases = listOf(
            Script(404) to RemoteRead.Failed(PublishErrorKind.ENTITY_NOT_FOUND, null),
            Script(401) to RemoteRead.Failed(PublishErrorKind.AUTH_REFUSED, null),
            Script(302, location = "http://192.168.0.11:8123/") to RemoteRead.Failed(PublishErrorKind.REDIRECTED, null),
            Script(503) to RemoteRead.Failed(PublishErrorKind.HTTP_ERROR, "503"),
            Script(422) to RemoteRead.Failed(PublishErrorKind.HTTP_ERROR, "422"),
            Script(200, body = paddedSelectJson(64 * 1024 + 1)) to RemoteRead.Failed(PublishErrorKind.MALFORMED, null),
            Script(0, headFailure = SocketTimeoutException("idle")) to
                RemoteRead.Failed(PublishErrorKind.TIMED_OUT, null),
            Script(0, headFailure = ConnectException("refused")) to
                RemoteRead.Failed(PublishErrorKind.UNREACHABLE, null),
            Script(200, bodyFailure = IOException("reset")) to RemoteRead.Failed(PublishErrorKind.UNREACHABLE, null),
        )
        for ((script, expected) in cases) {
            val h = Harness().apply { ha.answer = { script } }
            assertEquals("${script.status}", expected, h.client().read(HOME_HTTP, STATUS_HELPER, TOKEN))
            assertEquals("${script.status}: one open", 1, h.ha.opened.size)
        }
        val exact = Harness().apply { ha.answer = { Script(200, body = paddedSelectJson(64 * 1024)) } }
        assertTrue(
            "exactly 64 KiB is read whole",
            exact.client().read(HOME_HTTP, STATUS_HELPER, TOKEN) is RemoteRead.Answered,
        )

        assertEquals("a GET's 5xx is transient", RetryClass.TRANSIENT, retryClassOf(PublishErrorKind.HTTP_ERROR, "503"))
        assertEquals("a GET's 4xx is not", RetryClass.NEEDS_OWNER, retryClassOf(PublishErrorKind.HTTP_ERROR, "422"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theReadStopsAtThirtySeconds() = runTest {
        val h = Harness().apply { ha.answer = { Script(200, holdHead = true) } }
        val client = h.client(io = Dispatchers.IO)

        val pending = async { client.read(HOME_HTTP, STATUS_HELPER, TOKEN) }
        testScheduler.runCurrent()
        assertTrue(h.ha.headRequested.await(5, TimeUnit.SECONDS))
        testScheduler.advanceTimeBy(30_001)

        assertEquals(RemoteRead.Failed(PublishErrorKind.TIMED_OUT, null), pending.await())
        assertTrue("the deadline disconnects", h.ha.connections.single().disconnects.get() >= 1)
    }

    // ---- The stored connection, as #16's port: the bearer goes to the configured origin and nowhere else ----

    @Test
    fun onlyTheStoredConnectionIsServedAndItsSettingGoverns() = runBlocking {
        val others = listOf<HaConnection?>(
            null,
            HOME_HTTP.copy(baseUrl = "http://192.168.0.11:8123"),
            HOME_HTTP.copy(id = "conn-2"),
        )
        for (stored in others) {
            val h = Harness()
            assertEquals(
                "$stored",
                CallOutcome.Refused(PublishErrorKind.ENDPOINT_REFUSED, null),
                h.client(stored = stored).call(HOME_HTTP, HaServiceAction.SELECT_OPTION, STATUS_HELPER, "UP", TOKEN),
            )
            assertEquals(
                RemoteRead.Failed(PublishErrorKind.ENDPOINT_REFUSED, null),
                h.client(stored = stored).read(HOME_HTTP, STATUS_HELPER, TOKEN),
            )
            assertEquals(emptyList<URL>(), h.ha.opened)
        }

        // A caller holding an older copy that allowed any network: the stored row's home-network rule still applies.
        val stale = ANY_HTTPS_LITERAL
        val stored = ANY_HTTPS_LITERAL.copy(
            networkEligibility = NetworkEligibility.HOME_NETWORK_ONLY,
            homeNetworkSsid = "ExampleHomeWifi",
        )
        val away = Harness(network = NetworkReading(CurrentNetwork.Other, null))
        assertEquals(
            CallOutcome.Refused(PublishErrorKind.NOT_ON_LOCAL_NETWORK, null),
            away.client(stored = stored).call(stale, HaServiceAction.SELECT_OPTION, STATUS_HELPER, "UP", TOKEN),
        )
        assertEquals(emptyList<URL>(), away.ha.opened)
    }

    @Test
    fun aCallTakesOnlyItsActionsShapeAndOpensNothingOtherwise() = runBlocking {
        val refused = listOf(
            Triple(HaServiceAction.SELECT_OPTION, STATUS_HELPER, "MAYBE"),
            Triple(HaServiceAction.SELECT_OPTION, STATUS_HELPER, "up"),
            Triple(HaServiceAction.SELECT_OPTION, DATE_HELPER, "UP"),
            Triple(HaServiceAction.SELECT_OPTION, "input_select.Example Heater", "UP"),
            Triple(HaServiceAction.SET_DATE, DATE_HELPER, "2026-9-30"),
            Triple(HaServiceAction.SET_DATE, DATE_HELPER, "2026-09-30T06:00:00"),
            Triple(HaServiceAction.SET_DATE, STATUS_HELPER, "2026-09-30"),
        )
        for ((action, entity, value) in refused) {
            val h = Harness()
            try {
                h.client().call(HOME_HTTP, action, entity, value, TOKEN)
                fail("$action $entity $value is never sent")
            } catch (expected: IllegalArgumentException) {
                assertNotNull(expected.message)
            }
            assertEquals(emptyList<URL>(), h.ha.opened)
        }
    }

    /** I10: a read reaches `/api/states/<helper>` and no other path; an id outside #16's shape opens nothing. */
    @Test
    fun aReadOfAMalformedEntityIdOpensNothing() = runBlocking {
        for (entity in listOf("../services", "input_select.a/b")) {
            val h = Harness()
            assertEquals(entity, RemoteRead.Failed(PublishErrorKind.MALFORMED, null), h.client().read(HOME_HTTP, entity, TOKEN))
            assertEquals(entity, emptyList<URL>(), h.ha.opened)
        }
    }

    /** One fake Home Assistant, one scripted network, one scripted resolver; a client per call of [client]. */
    private class Harness(
        val network: NetworkReading = HOME_WIFI,
        val granted: Boolean = true,
        val resolver: HostResolver = HostResolver { listOf(PRIVATE_V4) },
    ) {
        val ha = FakeHa()

        /** [stored] is what the connection repository holds; the calls pass the same row unless a test says not. */
        fun client(
            stored: HaConnection? = HOME_HTTP,
            io: CoroutineContext = EmptyCoroutineContext,
        ) = HomeAssistantPublishClient(
            settings = { stored },
            networkPermissionGranted = { granted },
            currentNetwork = CurrentNetworkReader { network },
            resolver = resolver,
            io = io,
            open = ha.open,
        )
    }

    private companion object {
        const val STATUS_HELPER = "input_select.example_heater_equipment_status"
        const val DATE_HELPER = "input_datetime.example_heater_service_done"
        const val STATUS_UP_BODY = """{"entity_id":"$STATUS_HELPER","option":"UP"}"""
        val TOKEN = Secret("fictional-token-1")
        val HOME_WIFI = NetworkReading(CurrentNetwork.Wifi("ExampleHomeWifi"), null)
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
        val ANY_HTTPS_LITERAL = HOME_HTTP.copy(
            baseUrl = "https://192.168.0.10:8123",
            networkEligibility = NetworkEligibility.ANY_NETWORK,
            homeNetworkSsid = null,
        )
        val PRIVATE_V4 = byteArrayOf(192.toByte(), 168.toByte(), 0, 10)

        fun selectJson(entity: String, state: String): ByteArray =
            (
                """{"entity_id":"$entity","state":"$state",""" +
                    """"attributes":{"options":["UP","DEGRADED","DOWN"]}}"""
                ).toByteArray()

        /** A valid helper answer of exactly [size] bytes: the padding is an attribute. */
        fun paddedSelectJson(size: Int): ByteArray {
            val head = """{"entity_id":"$STATUS_HELPER","state":"UP","attributes":{"pad":""""
            val tail = "\"}}"
            return (head + "x".repeat(size - head.length - tail.length) + tail).toByteArray().also {
                check(it.size == size)
            }
        }

        fun countingStream(bytes: ByteArray, consumed: AtomicInteger): InputStream =
            object : FilterInputStream(ByteArrayInputStream(bytes)) {
                override fun read(): Int = super.read().also { if (it >= 0) consumed.incrementAndGet() }

                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it > 0) consumed.addAndGet(it) }
            }

        fun clientSources(): List<File> {
            val relative = "src/main/kotlin/com/loosecannon/servicetag"
            val root = listOf(File(relative), File("app/$relative")).firstOrNull { it.isDirectory }
                ?: error("cannot find $relative from ${File(".").absolutePath}")
            return listOf(
                File(root, "hapublish/HomeAssistantPublishClient.kt"),
                File(root, "seasonsync/HaRequestGate.kt"),
            ).onEach { check(it.isFile) { "missing ${it.path}" } }
        }
    }
}

/**
 * One scripted answer. Before transmission: [connectFailure] or [holdConnect] when the output stream is asked for.
 * After it: [writeFailureAfter] bytes accepted, a [closeFailure], a [headFailure] or [holdHead] at the status line, a
 * [bodyFailure] or a [stream] for the body.
 */
private class Script(
    val status: Int,
    val contentType: String? = "application/json",
    val body: ByteArray = "{}".toByteArray(),
    val location: String? = null,
    val connectFailure: Throwable? = null,
    val holdConnect: Boolean = false,
    val writeFailureAfter: Int? = null,
    val closeFailure: IOException? = null,
    val headFailure: Throwable? = null,
    val holdHead: Boolean = false,
    val bodyFailure: IOException? = null,
    val stream: (() -> InputStream)? = null,
)

/**
 * The fake Home Assistant behind the `open` seam: every open recorded, a scripted connection answered. With [acts],
 * a service call's body sets the named helper in [helpers] the moment the body is complete, as HA would act on it.
 */
private class FakeHa {
    val opened: MutableList<URL> = Collections.synchronizedList(mutableListOf())
    val connections: MutableList<FakeConnection> = Collections.synchronizedList(mutableListOf())
    val headRequested = CountDownLatch(1)
    val connectRequested = CountDownLatch(1)
    val helpers: MutableMap<String, String> = ConcurrentHashMap()
    var acts = false
    var answer: (URL) -> Script = { Script(200) }
    var openFailure: Throwable? = null

    val open: (URL) -> URLConnection = { url ->
        opened += url
        openFailure?.let { throw it }
        FakeConnection(url, answer(url), this).also { connections += it }
    }

    fun received(body: ByteArray) {
        if (!acts) return
        val call = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
        val entity = call.getValue("entity_id").jsonPrimitive.content
        val value = (call["option"] ?: call.getValue("date")).jsonPrimitive.content
        helpers[entity] = value
    }
}

/**
 * A scripted connection. It records what the client set as it stood when the request went out: at the output
 * stream for a POST, at the status line for a GET. With `instanceFollowRedirects` still true it does what the
 * platform would do with a 3xx — opens the target through the same seam — so a followed redirect shows as a second
 * open. [released] opens on the first disconnect.
 */
private class FakeConnection(url: URL, private val script: Script, private val ha: FakeHa) : HttpURLConnection(url) {
    val properties: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    val disconnects = AtomicInteger()
    val released = CountDownLatch(1)
    val sent = ByteArrayOutputStream()
    var methodAtHead: String? = null
    var followAtHead: Boolean? = null
    var cachesAtHead: Boolean? = null
    var outputAtHead: Boolean? = null
    var fixedLengthAtHead = -1
    var connectTimeoutAtHead = -1
    var readTimeoutAtHead = -1

    /** Whether the body's stream was closed when the status line was asked for; null when it never was. */
    var closedAtHead: Boolean? = null
    private var outputHanded = false
    private var closed = false
    private var followed: HttpURLConnection? = null

    private fun recordRequest() {
        methodAtHead = requestMethod
        followAtHead = instanceFollowRedirects
        cachesAtHead = useCaches
        outputAtHead = doOutput
        fixedLengthAtHead = fixedContentLength
        connectTimeoutAtHead = connectTimeout
        readTimeoutAtHead = readTimeout
    }

    override fun setRequestProperty(key: String, value: String) {
        properties += key to value
    }

    override fun addRequestProperty(key: String, value: String) {
        properties += key to value
    }

    override fun getOutputStream(): OutputStream {
        recordRequest()
        ha.connectRequested.countDown()
        if (script.holdConnect) {
            check(released.await(10, TimeUnit.SECONDS)) { "held connect never released" }
            throw IOException("closed")
        }
        script.connectFailure?.let { throw it }
        outputHanded = true
        return object : OutputStream() {
            override fun write(b: Int) {
                val limit = script.writeFailureAfter
                if (limit != null && sent.size() >= limit) throw IOException("broken pipe")
                sent.write(b)
            }

            override fun close() {
                closed = true
                script.closeFailure?.let { throw it }
                ha.received(sent.toByteArray())
            }
        }
    }

    override fun getResponseCode(): Int {
        if (!outputHanded) recordRequest()
        closedAtHead = closed
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
