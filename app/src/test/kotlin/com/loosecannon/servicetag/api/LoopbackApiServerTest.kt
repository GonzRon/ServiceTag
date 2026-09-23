package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.testing.FakeGraph
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private const val TOKEN = "ABCD2345"

/**
 * 1.1.0 (#46) — the listener itself, over a real loopback socket, on the JVM.
 *
 * **Port 0, not 17337.** The production port is fixed by the design and by what the workstation
 * forwards to; a test that bound it would fight the emulator, another test run and an actual app on
 * the same machine. `LoopbackApiServer` therefore takes its port, defaulted to
 * [DEVELOPER_API_PORT], and these cases ask the kernel for a free one and read [boundPort] back —
 * which is also what proves `boundPort` reports the truth.
 *
 * No Android and no Robolectric: `java.net` on the JVM is the same `java.net` on the device, and
 * what is genuinely Android about this feature — the permission, the lifecycle, the screen — is
 * `DeveloperApiListenerTest`'s on `emulator-5554`.
 */
class LoopbackApiServerTest {

    private val graph = FakeGraph()
    private lateinit var server: LoopbackApiServer

    private fun router(): ApiRouter = ApiRouter(
        ApiHandlers(
            graph.assets, graph.tags, graph.links, graph.definitions, graph.profiles,
            graph.events, graph.attachments,
            graph.createAsset, graph.updateAsset, graph.retireAsset, graph.archiveAsset,
            graph.saveDefinition, graph.archiveDefinition, graph.saveProfile,
            graph.archiveProfile, graph.logEvent, graph.updateEvent, graph.deleteEvent,
            graph.importBackupMerge,
            maintenanceHandlersFor(graph),
            referenceHandlersFor(graph),
            appVersion = "1.1.0",
            schemaVersion = 5,
        ),
        TOKEN,
    )

    @Before fun startOnAFreePort() {
        server = LoopbackApiServer(router(), port = 0)
        assertTrue("the listener did not bind", server.start())
    }

    @After fun stopAndClose() {
        server.stop()
        graph.close()
    }

    /** Sends [raw] verbatim and reads the whole answer back, as a workstation's client would. */
    private fun speak(raw: String): String = Socket("127.0.0.1", server.boundPort).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().apply {
            write(raw.toByteArray())
            flush()
        }
        socket.getInputStream().readBytes().decodeToString()
    }

    @Test fun itAnswersStatusOverARealSocket() {
        val answer = speak("GET /v1/status HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $TOKEN\r\n\r\n")
        assertTrue(answer, answer.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(answer, answer.contains("Content-Type: application/json\r\n"))
        assertTrue(answer, answer.contains("\"apiVersion\":$API_VERSION"))
        assertTrue(answer, answer.contains("\"assets\":0"))
    }

    /**
     * Review S2, over a real socket: a 413 fires before the body is read, so the declared body may
     * still be arriving on the wire when the response goes out. Without draining it before close,
     * the caller risks an `ECONNRESET` instead of the 413 it was sent — this is the case the drain
     * exists to make observable, so it is pinned with real bytes over a real socket, not the
     * `ByteArrayInputStream`-backed `HttpWireTest.aBodyOverTheCapIs413`.
     */
    @Test fun aBodyOverTheCapReachesTheCallerAsA413NotAReset() {
        val big = MAX_BODY_BYTES + 1
        val answer = Socket("127.0.0.1", server.boundPort).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(
                    "POST /v1/assets HTTP/1.1\r\nAuthorization: Bearer $TOKEN\r\nContent-Length: $big\r\n\r\n"
                        .toByteArray(),
                )
                write(ByteArray(big))
                flush()
            }
            socket.getInputStream().readBytes().decodeToString()
        }
        assertTrue(answer, answer.startsWith("HTTP/1.1 413"))
    }

    /** The first security minimum: the socket is on this phone's own address, and nowhere else. */
    @Test fun itBindsTheLoopbackAddressAndNothingElse() {
        assertEquals("127.0.0.1", server.boundAddress)
        assertNotEquals(0, server.boundPort)
        assertEquals(17337, DEVELOPER_API_PORT)
    }

    @Test fun anUnauthorisedRequestGetsAnEmptyBodyOverTheWire() {
        val noToken = speak("GET /v1/status HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        assertEquals(
            "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
            noToken,
        )
        val wrongToken = speak("GET /v1/status HTTP/1.1\r\nAuthorization: Bearer NOPENOPE\r\n\r\n")
        assertEquals(noToken, wrongToken)
    }

    /** Leaving the screen is what this models: after `stop`, there is nothing on the port. */
    @Test fun stopRefusesFurtherConnections() {
        val port = server.boundPort
        assertTrue(speak("GET /v1/status HTTP/1.1\r\nAuthorization: Bearer $TOKEN\r\n\r\n").isNotEmpty())

        server.stop()
        assertEquals(0, server.boundPort)
        try {
            Socket("127.0.0.1", port).use { it.getInputStream().read() }
            fail("the port is still answering after stop()")
        } catch (e: IOException) {
            // Refused, which is the whole point of the lifecycle binding.
        }
    }

    /** What the screen's third line counts: answers given, refusals included. */
    @Test fun theRequestCountMovesForEveryAnswerIncludingRefusals() {
        assertEquals(0, server.requests.value)
        speak("GET /v1/status HTTP/1.1\r\nAuthorization: Bearer $TOKEN\r\n\r\n")
        speak("GET /v1/status HTTP/1.1\r\n\r\n")
        speak("PUT /v1/status HTTP/1.1\r\n\r\n")
        assertEquals(3, server.requests.value)
    }

    /**
     * Security minimum 1's second half. It is a predicate and not a socket case on purpose: a
     * listener bound to `127.0.0.1` cannot be *reached* by a non-loopback peer, which is exactly why
     * the check inside `answer` is defence in depth rather than the boundary. Asserting the bind
     * (above) and asserting the predicate (here) together are what the minimum claims.
     */
    @Test fun onlyALoopbackPeerIsAnswered() {
        assertTrue(isAcceptablePeer(InetAddress.getByName("127.0.0.1")))
        assertTrue(isAcceptablePeer(InetAddress.getByName("127.0.0.53")))
        assertTrue(isAcceptablePeer(InetAddress.getByName("::1")))
        assertFalse(isAcceptablePeer(InetAddress.getByName("192.168.1.10")))
        assertFalse(isAcceptablePeer(InetAddress.getByName("10.0.2.2")))
        assertFalse(isAcceptablePeer(InetAddress.getByName("8.8.8.8")))
    }

    /**
     * The dependency decision claims a half-open socket cannot hold the only worker. This is that
     * claim: a client that connects, sends a fragment and stops is dropped after the read timeout,
     * and the **next** connection is answered normally. Built with a 200 ms timeout so the case
     * costs 200 ms rather than five seconds; the production default is [SOCKET_TIMEOUT_MILLIS].
     */
    @Test fun aHalfOpenClientDoesNotHoldTheWorker() {
        val impatient = LoopbackApiServer(router(), port = 0, readTimeoutMillis = 200)
        assertTrue(impatient.start())
        // N13: declared here, not inside the `try`, so the `finally` below can always close it —
        // previously a failed assertion inside the `try` would leak this socket for the rest of the
        // JVM's life.
        var stalled: Socket? = null
        try {
            // A fragment with no terminator: the parser blocks, then the socket times out.
            stalled = Socket("127.0.0.1", impatient.boundPort)
            stalled.getOutputStream().apply {
                write("GET /v1/sta".toByteArray())
                flush()
            }

            val answer = Socket("127.0.0.1", impatient.boundPort).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().apply {
                    write("GET /v1/status HTTP/1.1\r\nAuthorization: Bearer $TOKEN\r\n\r\n".toByteArray())
                    flush()
                }
                socket.getInputStream().readBytes().decodeToString()
            }

            assertTrue(answer, answer.startsWith("HTTP/1.1 200 OK\r\n"))
            // The stalled connection was dropped without ever being answered, so it counts as one
            // refusal at most and never blocked the one that mattered.
            assertEquals("requests", 1, impatient.requests.value)
        } finally {
            stalled?.close()
            impatient.stop()
        }
    }

    /**
     * Review re-review New-1: `drainBeforeClose`'s wall-clock deadline. A peer that never stops
     * sending, but paces itself just under [Socket.soTimeout] so no single `read()` call ever times
     * out on its own, must not be able to hold the drain — and so the listener's one worker — open
     * for as long as it keeps trickling. A `413` fires before the declared body is read, which is
     * exactly the drain's own case (review S2); this test's peer then trickles that body one byte at
     * a time, spaced under the 200 ms read timeout, for up to two full seconds — comfortably more
     * than the roughly one `readTimeoutMillis` window the deadline should cut it off within.
     */
    @Test fun aTricklingClientCannotHoldTheDrainOpenIndefinitely() {
        val impatient = LoopbackApiServer(router(), port = 0, readTimeoutMillis = 200)
        assertTrue(impatient.start())
        var trickler: Socket? = null
        try {
            val big = MAX_BODY_BYTES + 1
            trickler = Socket("127.0.0.1", impatient.boundPort)
            trickler.getOutputStream().apply {
                write(
                    "POST /v1/assets HTTP/1.1\r\nAuthorization: Bearer $TOKEN\r\nContent-Length: $big\r\n\r\n"
                        .toByteArray(),
                )
                flush()
            }

            val started = System.nanoTime()
            val stopTrickling = started + TimeUnit.SECONDS.toNanos(2)
            var cutOff = false
            try {
                while (System.nanoTime() < stopTrickling) {
                    trickler.getOutputStream().write(0)
                    trickler.getOutputStream().flush()
                    Thread.sleep(150) // just under the 200 ms read timeout, so no one read times out
                }
            } catch (e: IOException) {
                // The server closed the connection out from under the trickle — the point of the fix.
                cutOff = true
            }
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertTrue("the trickle was never cut off in two seconds of trying", cutOff)
            // Comfortably under the two-second trickle budget, and the right order of magnitude for
            // one 200 ms deadline window rather than the full duration the peer was willing to send.
            assertTrue("cut off too slowly: ${elapsedMillis}ms", elapsedMillis < 1_000)

            // The worker is free again: a fresh connection is answered normally and promptly.
            val answer = Socket("127.0.0.1", impatient.boundPort).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().apply {
                    write("GET /v1/status HTTP/1.1\r\nAuthorization: Bearer $TOKEN\r\n\r\n".toByteArray())
                    flush()
                }
                socket.getInputStream().readBytes().decodeToString()
            }
            assertTrue(answer, answer.startsWith("HTTP/1.1 200 OK\r\n"))
        } finally {
            trickler?.close()
            impatient.stop()
        }
    }

    /**
     * Review S7. The plan's rule for `stop()` mid-request — close the in-flight socket, do not join,
     * let a domain call already running finish as its own transaction — was asserted nowhere. A
     * blocking stub repository, the same seam `ApiRouterTest`'s S4 case uses, stands in for a slow
     * `uow.write`: this proves both halves of the rule in one case, and it is exactly the scenario
     * S1 was a race in.
     */
    private class BlockingAssetRepository(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
        private val completed: CountDownLatch,
    ) : AssetRepository {
        override suspend fun upsert(asset: Asset): Unit = error("not used by this test")
        override suspend fun get(id: AssetId): Asset? = error("not used by this test")
        override suspend fun all(): List<Asset> {
            entered.countDown()
            release.await()
            completed.countDown()
            return emptyList()
        }
        override suspend fun delete(id: AssetId): Unit = error("not used by this test")
        override suspend fun deleteAll(): Unit = error("not used by this test")
        override fun observeAll(): Flow<List<Asset>> = error("not used by this test")
    }

    @Test fun stopWhileARequestIsInFlightEndsTheConnectionWithNoResponseAndLetsTheHandlerFinish() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val blockingRouter = ApiRouter(
            ApiHandlers(
                BlockingAssetRepository(entered, release, completed), graph.tags, graph.links,
                graph.definitions, graph.profiles, graph.events, graph.attachments,
                graph.createAsset, graph.updateAsset, graph.retireAsset, graph.archiveAsset,
                graph.saveDefinition, graph.archiveDefinition, graph.saveProfile, graph.archiveProfile,
                graph.logEvent, graph.updateEvent, graph.deleteEvent, graph.importBackupMerge,
                maintenanceHandlersFor(graph),
                referenceHandlersFor(graph),
                appVersion = "1.1.0",
                schemaVersion = 5,
            ),
            TOKEN,
        )
        val blockingServer = LoopbackApiServer(blockingRouter, port = 0)
        assertTrue(blockingServer.start())
        var client: Socket? = null
        try {
            client = Socket("127.0.0.1", blockingServer.boundPort)
            client.getOutputStream().apply {
                write("GET /v1/status HTTP/1.1\r\nAuthorization: Bearer $TOKEN\r\n\r\n".toByteArray())
                flush()
            }
            assertTrue("the handler never entered", entered.await(5, TimeUnit.SECONDS))

            blockingServer.stop()

            client.soTimeout = 5_000
            assertEquals("expected EOF, not a response", -1, client.getInputStream().read())

            release.countDown()
            assertTrue("the handler never finished", completed.await(5, TimeUnit.SECONDS))
        } finally {
            client?.close()
            blockingServer.stop()
        }
    }
}
