package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.testing.FakeGraph
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
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
        try {
            // A fragment with no terminator: the parser blocks, then the socket times out.
            val stalled = Socket("127.0.0.1", impatient.boundPort)
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
            stalled.close()
        } finally {
            impatient.stop()
        }
    }
}
