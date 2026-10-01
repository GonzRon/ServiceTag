package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.fetch.FetchDocument
import com.loosecannon.servicetag.core.fetch.HopPolicy
import com.loosecannon.servicetag.core.fetch.HostResolver
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.AttachmentStore
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.ports.StoredBytes
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.usecase.AddAttachment
import com.loosecannon.servicetag.core.usecase.MaterializeReference
import com.loosecannon.servicetag.testing.InMemoryAttachmentStore
import com.loosecannon.servicetag.testing.FakeGraph
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ServerSocketFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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

    private fun router(attachmentRoutes: AttachmentHandlers = attachmentHandlersFor(graph)): ApiRouter = ApiRouter(
        ApiHandlers(
            graph.assets, graph.tags, graph.links, graph.definitions, graph.profiles,
            graph.events, graph.attachments, graph.categories, graph.transferRecords, graph.assetSuccessions,
            graph.createAsset, graph.updateAsset, graph.retireAsset, graph.archiveAsset,
            graph.saveDefinition, graph.archiveDefinition, graph.saveProfile,
            graph.archiveProfile, graph.logEvent, graph.updateEvent, graph.deleteEvent,
            graph.importBackupMerge,
            maintenanceHandlersFor(graph),
            referenceHandlersFor(graph),
            seasonHealthHandlersFor(graph),
            warrantyHandlersFor(graph),
            serviceCaseHandlersFor(graph),
            loanHandlersFor(graph),
            attachmentRoutes,
            replaceHandlersFor(graph),
            supplyHandlersFor(graph),
            appVersion = "1.1.0",
            schemaVersion = 5,
        ),
        TOKEN,
    )

    @Before fun startOnAFreePort() {
        server = LoopbackApiServer(router(), networkPermissionGranted = { true }, port = 0)
        assertEquals("the listener did not bind", StartOutcome.Bound, server.start())
    }

    @After fun stopAndClose() {
        server.stop()
        graph.close()
    }

    /** Sends [raw] verbatim and reads the whole answer back, as a workstation's client would. */
    private fun speak(raw: String, port: Int = server.boundPort): String = Socket("127.0.0.1", port).use { socket ->
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
        val impatient = LoopbackApiServer(router(), networkPermissionGranted = { true }, port = 0, readTimeoutMillis = 200)
        assertEquals(StartOutcome.Bound, impatient.start())
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
        val impatient = LoopbackApiServer(router(), networkPermissionGranted = { true }, port = 0, readTimeoutMillis = 200)
        assertEquals(StartOutcome.Bound, impatient.start())
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
                graph.definitions, graph.profiles, graph.events, graph.attachments, graph.categories, graph.transferRecords, graph.assetSuccessions,
                graph.createAsset, graph.updateAsset, graph.retireAsset, graph.archiveAsset,
                graph.saveDefinition, graph.archiveDefinition, graph.saveProfile, graph.archiveProfile,
                graph.logEvent, graph.updateEvent, graph.deleteEvent, graph.importBackupMerge,
                maintenanceHandlersFor(graph),
                referenceHandlersFor(graph),
                seasonHealthHandlersFor(graph),
                warrantyHandlersFor(graph),
                serviceCaseHandlersFor(graph),
                loanHandlersFor(graph),
                attachmentHandlersFor(graph),
                replaceHandlersFor(graph),
                supplyHandlersFor(graph),
                appVersion = "1.1.0",
                schemaVersion = 5,
            ),
            TOKEN,
        )
        val blockingServer = LoopbackApiServer(blockingRouter, networkPermissionGranted = { true }, port = 0)
        assertEquals(StartOutcome.Bound, blockingServer.start())
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

    // ---- #66: the typed start outcome ------------------------------------------------------------

    /** The bind seam: hands out whatever [make] builds, counting the binds it was asked for. */
    private class ScriptedFactory(
        private val make: (port: Int, backlog: Int, address: InetAddress?) -> ServerSocket,
    ) : ServerSocketFactory() {
        val binds = AtomicInteger()
        override fun createServerSocket(port: Int): ServerSocket = createServerSocket(port, 50, null)
        override fun createServerSocket(port: Int, backlog: Int): ServerSocket = createServerSocket(port, backlog, null)
        override fun createServerSocket(port: Int, backlog: Int, ifAddress: InetAddress?): ServerSocket {
            binds.incrementAndGet()
            return make(port, backlog, ifAddress)
        }
    }

    /**
     * A real, bound socket that records the worker parked in its `accept()`. With [failWhenReleased]
     * it fails that `accept()` — on a socket nobody closed — once the latch opens, the way `EMFILE`
     * or `ECONNABORTED` would; without it, it accepts normally.
     */
    private class WatchedServerSocket(
        port: Int,
        backlog: Int,
        address: InetAddress?,
        private val failWhenReleased: CountDownLatch? = null,
    ) : ServerSocket(port, backlog, address) {
        @Volatile var worker: Thread? = null
        val entered = CountDownLatch(1)
        override fun accept(): Socket {
            worker = Thread.currentThread()
            entered.countDown()
            val release = failWhenReleased ?: return super.accept()
            release.await()
            throw IOException("accept failed on an open socket")
        }
    }

    /** A bounded wait on the flow — never a lone sleep — then the value, so a miss names what it saw. */
    private fun LoopbackApiServer.awaitState(expected: ListenerState) {
        runBlocking { withTimeoutOrNull(5_000) { state.first { it == expected } } }
        assertEquals(expected, state.value)
    }

    private fun Thread.joinWithin(millis: Long) {
        join(millis)
        assertFalse("the worker never finished", isAlive)
    }

    /**
     * Every combination of the three inputs, and the outcome each one must give. The shape to
     * notice: no row where the permission reads as granted gives the denial, so on stock Android —
     * where INTERNET is install-time — the denial sentence can never be drawn.
     */
    @Test fun theBindFailureTableIsConservative() {
        val table = listOf(
            // errno, is a BindException, permission granted -> outcome
            Triple(BindErrno.ADDRESS_IN_USE, false, false) to StartOutcome.PortInUse,
            Triple(BindErrno.ADDRESS_IN_USE, false, true) to StartOutcome.PortInUse,
            Triple(BindErrno.ADDRESS_IN_USE, true, false) to StartOutcome.PortInUse,
            Triple(BindErrno.ADDRESS_IN_USE, true, true) to StartOutcome.PortInUse,
            Triple(BindErrno.ACCESS_DENIED, false, false) to StartOutcome.NetworkPermissionDenied,
            Triple(BindErrno.ACCESS_DENIED, false, true) to StartOutcome.Other,
            Triple(BindErrno.ACCESS_DENIED, true, false) to StartOutcome.NetworkPermissionDenied,
            Triple(BindErrno.ACCESS_DENIED, true, true) to StartOutcome.Other,
            Triple(BindErrno.OTHER, false, false) to StartOutcome.Other,
            Triple(BindErrno.OTHER, false, true) to StartOutcome.Other,
            Triple(BindErrno.OTHER, true, false) to StartOutcome.Other,
            Triple(BindErrno.OTHER, true, true) to StartOutcome.Other,
            Triple(BindErrno.UNKNOWN, false, false) to StartOutcome.NetworkPermissionDenied,
            Triple(BindErrno.UNKNOWN, false, true) to StartOutcome.Other,
            Triple(BindErrno.UNKNOWN, true, false) to StartOutcome.NetworkPermissionDenied,
            Triple(BindErrno.UNKNOWN, true, true) to StartOutcome.PortInUse,
        )
        assertEquals("every combination, each once", BindErrno.entries.size * 2 * 2, table.map { it.first }.toSet().size)
        assertEquals(table.size, table.map { it.first }.toSet().size)
        table.forEach { (input, outcome) ->
            val (errno, isBind, granted) = input
            assertEquals("$errno, bind=$isBind, granted=$granted", outcome, classifyBindFailure(errno, isBind, granted))
        }
    }

    /** A real squatter on the JVM: a `BindException` with no errno in its chain, rule 3. */
    @Test fun aTakenPortIsPortInUse() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { squatter ->
            val late = LoopbackApiServer(router(), networkPermissionGranted = { true }, port = squatter.localPort)
            assertEquals(StartOutcome.PortInUse, late.start())
            assertEquals(ListenerState.CouldNotStart(StartOutcome.PortInUse), late.state.value)
            assertEquals(0, late.boundPort)
            late.stop()
            assertEquals(ListenerState.Stopped, late.state.value)
        }
    }

    /**
     * The failure the owner's hardened build produces, injected through the seam: a
     * `SocketException` with no errno the JVM can show. Only a permission that reads as denied turns
     * it into the denial; the same exception with the permission granted is merely "other".
     */
    @Test fun aDeniedBindIsDeniedOnlyWhenThePermissionSaysSo() {
        val refusing = ScriptedFactory { _, _, _ -> throw SocketException("socket failed: EACCES (Permission denied)") }

        val denied = LoopbackApiServer(router(), { false }, port = 0, serverSocketFactory = refusing)
        assertEquals(StartOutcome.NetworkPermissionDenied, denied.start())
        assertEquals(ListenerState.CouldNotStart(StartOutcome.NetworkPermissionDenied), denied.state.value)
        assertEquals(0, denied.boundPort)

        val granted = LoopbackApiServer(router(), { true }, port = 0, serverSocketFactory = refusing)
        assertEquals(StartOutcome.Other, granted.start())
        assertEquals(ListenerState.CouldNotStart(StartOutcome.Other), granted.state.value)
    }

    /** A build that refuses the socket with a `SecurityException` gets a notice, not a crash. */
    @Test fun aSecurityExceptionFromTheBindIsClassified() {
        val forbidding = ScriptedFactory { _, _, _ -> throw SecurityException("no network for this app") }

        val denied = LoopbackApiServer(router(), { false }, port = 0, serverSocketFactory = forbidding)
        assertEquals(StartOutcome.NetworkPermissionDenied, denied.start())
        assertEquals(ListenerState.CouldNotStart(StartOutcome.NetworkPermissionDenied), denied.state.value)

        val granted = LoopbackApiServer(router(), { true }, port = 0, serverSocketFactory = forbidding)
        assertEquals(StartOutcome.Other, granted.start())
        assertEquals(ListenerState.CouldNotStart(StartOutcome.Other), granted.state.value)
    }

    @Test fun startWhileListeningBindsNothingNew() {
        val factory = ScriptedFactory { port, backlog, address -> ServerSocket(port, backlog, address) }
        val twice = LoopbackApiServer(router(), { true }, port = 0, serverSocketFactory = factory)
        try {
            assertEquals(StartOutcome.Bound, twice.start())
            val port = twice.boundPort
            assertEquals(StartOutcome.Bound, twice.start())
            assertEquals("binds", 1, factory.binds.get())
            assertEquals(port, twice.boundPort)
            assertEquals(ListenerState.Listening, twice.state.value)
        } finally {
            twice.stop()
        }
    }

    // ---- #51: a listener that dies ---------------------------------------------------------------

    /**
     * `accept()` fails on a socket `stop()` never closed. Before #51 the worker returned and left the
     * socket bound: `boundPort` still read a port, the kernel kept completing handshakes nobody read,
     * and the screen kept showing a healthy listener. Now it is closed, forgotten and reported.
     */
    @Test fun anAcceptFailureOnAnOpenSocketIsTerminal() {
        val release = CountDownLatch(1)
        val sockets = mutableListOf<WatchedServerSocket>()
        val factory = ScriptedFactory { port, backlog, address ->
            WatchedServerSocket(port, backlog, address, failWhenReleased = release).also { sockets += it }
        }
        val dying = LoopbackApiServer(router(), { true }, port = 0, serverSocketFactory = factory)
        try {
            assertEquals(StartOutcome.Bound, dying.start())
            assertEquals(ListenerState.Listening, dying.state.value)
            val port = dying.boundPort
            val watched = sockets.single()
            assertTrue("the worker never reached accept()", watched.entered.await(5, TimeUnit.SECONDS))

            release.countDown()

            dying.awaitState(ListenerState.Died)
            assertEquals(0, dying.boundPort)
            assertTrue("the dead listener's socket was left open", watched.isClosed)
            try {
                Socket("127.0.0.1", port).use { it.getInputStream().read() }
                fail("the dead listener's port still accepts connections")
            } catch (e: IOException) {
                // Refused: nothing is left on the port.
            }
        } finally {
            release.countDown()
            dying.stop()
        }
    }

    /** `stop()` closing the socket is the intended exit, and says "stopped", never "died". */
    @Test fun anAcceptFailureAfterStopIsSilent() {
        val sockets = mutableListOf<WatchedServerSocket>()
        val factory = ScriptedFactory { port, backlog, address ->
            WatchedServerSocket(port, backlog, address).also { sockets += it }
        }
        val stopping = LoopbackApiServer(router(), { true }, port = 0, serverSocketFactory = factory)
        try {
            assertEquals(StartOutcome.Bound, stopping.start())
            val watched = sockets.single()
            assertTrue("the worker never reached accept()", watched.entered.await(5, TimeUnit.SECONDS))

            stopping.stop() // closes the socket: the parked accept() throws, and the worker runs its catch

            watched.worker!!.joinWithin(5_000)
            assertEquals(ListenerState.Stopped, stopping.state.value)
            assertEquals(0, stopping.boundPort)
        } finally {
            stopping.stop() // N13: never leave a bound listener behind a failed assertion; a second stop is harmless
        }
    }

    /**
     * Review S1's compare-and-clear, applied to the socket. Generation 1's worker is still parked in
     * `accept()` when `stop()` and a quick `start()` bring up generation 2; its `accept()` failing
     * *then* must not close, null or re-label generation 2.
     */
    @Test fun aStaleWorkerCannotTouchTheNextGeneration() {
        val release = CountDownLatch(1)
        val sockets = mutableListOf<WatchedServerSocket>()
        val factory = ScriptedFactory { port, backlog, address ->
            // Generation 1 fails its accept() when released; generation 2 is an ordinary listener.
            val gate = if (sockets.isEmpty()) release else null
            WatchedServerSocket(port, backlog, address, failWhenReleased = gate).also { sockets += it }
        }
        val restarted = LoopbackApiServer(router(), { true }, port = 0, serverSocketFactory = factory)
        try {
            assertEquals(StartOutcome.Bound, restarted.start())
            val first = sockets[0]
            assertTrue("generation 1 never reached accept()", first.entered.await(5, TimeUnit.SECONDS))

            restarted.stop()
            assertEquals(StartOutcome.Bound, restarted.start())
            val second = sockets[1]
            val secondPort = restarted.boundPort
            assertNotEquals(0, secondPort)

            release.countDown() // generation 1's accept() fails now, with generation 2 listening
            first.worker!!.joinWithin(5_000)

            assertEquals(ListenerState.Listening, restarted.state.value)
            assertEquals(secondPort, restarted.boundPort)
            assertFalse("generation 2's socket was closed by a stale worker", second.isClosed)
            val answer = speak(
                "GET /v1/status HTTP/1.1\r\nAuthorization: Bearer $TOKEN\r\n\r\n",
                port = secondPort,
            )
            assertTrue(answer, answer.startsWith("HTTP/1.1 200 OK\r\n"))
        } finally {
            release.countDown()
            restarted.stop()
        }
    }

    // ---- #92 (B1b): the upload over a real socket, and C33's lock across generations -------------------------------

    private fun metadataFor(key: String, bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        """{"operationKey":"$key","displayName":"Example Water Heater manual","sha256":"${InMemoryAttachmentStore.sha256Hex(bytes)}"}"""
            .toByteArray(),
    )

    private fun uploadHead(assetId: String, length: Long, metadata: String, token: String = TOKEN): ByteArray =
        ("POST /v1/assets/$assetId/attachments HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $token\r\n" +
            "Content-Type: application/pdf\r\n$UPLOAD_METADATA_HEADER: $metadata\r\nContent-Length: $length\r\n\r\n")
            .toByteArray()

    /** One whole upload over a fresh connection, and the whole answer back. */
    private fun sendUpload(port: Int, assetId: String, bytes: ByteArray, metadata: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 30_000
            socket.getOutputStream().apply {
                write(uploadHead(assetId, bytes.size.toLong(), metadata))
                write(bytes)
                flush()
            }
            socket.getInputStream().readBytes().decodeToString()
        }

    private fun stagingIsEmpty(): Boolean = graph.materializeStagingDir.listFiles().orEmpty().isEmpty()

    /** Row 11: past the 4 MiB import ceiling, over a real socket, into one row with every byte. */
    @Test fun aFiveMiBUploadCrossesARealSocket() {
        val heater = V1Client(graph, TOKEN).asset("Example Water Heater")
        val bytes = ByteArray(5 * 1024 * 1024) { (it % 253).toByte() }
        val answer = sendUpload(server.boundPort, heater, bytes, metadataFor("op-5mib", bytes))
        assertTrue(answer.take(300), answer.startsWith("HTTP/1.1 201 Created\r\n"))
        val row = runBlocking { graph.attachments.forAsset(AssetId(heater)) }.single()
        assertEquals(bytes.size.toLong(), row.sizeBytes)
        assertTrue(graph.attachmentStorage.store.files.getValue(row.storageLocator).contentEquals(bytes))
        assertTrue(stagingIsEmpty())
    }

    /**
     * Row 9b (C10), observed client-side: a peer without the token declares 200 MiB. It reads its 401, then **cannot
     * deliver the body** — the server drains at most 4 MiB within 5 s and closes, so the peer's writes fail after
     * that plus the socket buffers — and the next request is answered.
     */
    @Test fun anUnauthenticatedUploadIsClosedWithinTheDrainBound() {
        val declared = 200L * 1024 * 1024
        var delivered = 0L
        var failure: IOException? = null
        val head = Socket("127.0.0.1", server.boundPort).use { socket ->
            socket.soTimeout = 10_000
            val out = socket.getOutputStream()
            out.write(uploadHead("a1", declared, metadataFor("op-1", ByteArray(1)), token = "NOPENOPE"))
            out.flush()
            val answer = socket.getInputStream().readBytes().decodeToString()
            val chunk = ByteArray(64 * 1024)
            try {
                while (delivered < declared) {
                    out.write(chunk)
                    delivered += chunk.size
                }
                out.flush()
            } catch (e: IOException) {
                failure = e
            }
            answer
        }
        assertTrue(head, head.startsWith("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n"))
        assertTrue("the whole unauthenticated body was delivered ($delivered bytes)", failure != null)
        assertTrue(delivered < declared)
        val next = speak("GET /v1/status HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $TOKEN\r\n\r\n")
        assertTrue(next, next.startsWith("HTTP/1.1 200 OK\r\n"))
    }

    /** A store whose `put` writes its bytes and then parks until the test releases it; it counts every `put`. */
    private class GatedStore(private val inner: InMemoryAttachmentStore) : AttachmentStore {
        val puts = AtomicInteger()
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)

        override suspend fun put(locator: String, source: ByteSource): StoredBytes {
            puts.incrementAndGet()
            val stored = inner.put(locator, source)
            parked.countDown()
            release.await(30, TimeUnit.SECONDS)
            return stored
        }

        override suspend fun open(locator: String) = inner.open(locator)
        override suspend fun exists(locator: String) = inner.exists(locator)
        override suspend fun delete(locator: String) = inner.delete(locator)
    }

    /**
     * Row 43 (C33): an upload parked in `put`, the listener stopped and started, and the MCP's retry of its key on the
     * new generation. The retry waits for the process-wide lock and then answers 200 with the first's row: one row,
     * the first request's bytes intact, and the store's `put` called exactly once. On the graph's real dispatcher.
     */
    @Test fun aRetriedUploadAcrossAStopAndStartLandsOnceWithTheFirstBytesIntact() {
        val heater = V1Client(graph, TOKEN).asset("Example Water Heater")
        val inner = InMemoryAttachmentStore()
        val gated = GatedStore(inner)
        val folder = object : AttachmentStorage {
            override fun state(): StoreState = StoreState.Ready("Attachments", "com.example.provider")
            override fun store(): AttachmentStore = gated
        }
        val add = AddAttachment(graph.attachments, graph.assets, graph.events, folder, graph.uow, graph.ids, graph.clock)
        val routes = AttachmentHandlers(
            attachments = graph.attachments, assets = graph.assets, storage = folder,
            updateAttachment = graph.updateAttachment, installation = graph.installationIdentity,
            transfers = graph.transferRecords, addAttachment = add, staging = graph.materializeStaging,
            apiLongWrites = graph.apiLongWrites,
        )
        server.stop()
        server = LoopbackApiServer(router(routes), networkPermissionGranted = { true }, port = 0)
        assertEquals(StartOutcome.Bound, server.start())

        val bytes = "Example Water Heater, the first request's bytes".toByteArray()
        val metadata = metadataFor("op-retry", bytes)
        val firstPort = server.boundPort
        val first = Thread { runCatching { sendUpload(firstPort, heater, bytes, metadata) } }.apply {
            isDaemon = true
            start()
        }
        assertTrue("the first upload never reached put", gated.parked.await(10, TimeUnit.SECONDS))

        server.stop()
        assertEquals(StartOutcome.Bound, server.start())
        val secondPort = server.boundPort
        var replay = ""
        val second = Thread { replay = sendUpload(secondPort, heater, bytes, metadata) }.apply {
            isDaemon = true
            start()
        }
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (server.requests.value < 2 && System.nanoTime() < until) Thread.sleep(10)
        assertEquals("the retry was never read", 2, server.requests.value)
        Thread.sleep(300) // time for the retry to reach the lock — or, without one, the store
        gated.release.countDown()
        second.join(15_000)
        first.join(15_000)

        assertTrue(replay.take(300), replay.startsWith("HTTP/1.1 200 OK\r\n"))
        val rows = runBlocking { graph.attachments.forAsset(AssetId(heater)) }
        assertEquals(1, rows.size)
        assertTrue(replay, replay.contains(rows.single().id.value))
        assertTrue(inner.files.getValue(rows.single().storageLocator).contentEquals(bytes))
        assertEquals("the store's put ran more than once", 1, gated.puts.get())
        assertTrue(stagingIsEmpty())
    }

    // ---- #92 (B2): save as document across generations — C33's materialize half and B2-pre's BC5 --------------------

    /**
     * One asset, a gated folder, a scripted transport and the production attachment routes with save as document wired
     * over them, on the graph's real dispatcher. [references] is what the handler reads the reference through.
     */
    private inner class MaterializeRig(references: ReferenceRepository? = null) {
        val transport = ScriptedTransport()
        val store = InMemoryAttachmentStore()
        val gated = GatedStore(store)
        private val folder = object : AttachmentStorage {
            override fun state(): StoreState = StoreState.Ready("Attachments", "com.example.provider")
            override fun store(): AttachmentStore = gated
        }
        private val add = AddAttachment(graph.attachments, graph.assets, graph.events, folder, graph.uow, graph.ids, graph.clock)
        private val hops = HopPolicy(HostResolver { listOf(byteArrayOf(203.toByte(), 0, 113, 10)) })
        val routes = AttachmentHandlers(
            attachments = graph.attachments, assets = graph.assets, storage = folder,
            updateAttachment = graph.updateAttachment, installation = graph.installationIdentity,
            transfers = graph.transferRecords, addAttachment = add, staging = graph.materializeStaging,
            apiLongWrites = graph.apiLongWrites, references = references ?: graph.references,
            materializeReference = MaterializeReference(
                graph.references, graph.attachments, folder, LinkLaunchPolicy(), hops,
                FetchDocument(transport, hops, graph.materializeStaging), add, { true }, graph.clock,
            ),
        )
        val asset: String = V1Client(graph, TOKEN).asset("Example Water Heater")

        fun reference(uri: String): String = V1Client(graph, TOKEN).ok(
            ReferenceResponse.serializer(), "POST", "/v1/references",
            """{"assetId":"$asset","uri":"$uri","displayName":"Example Water Heater manual","description":""}""",
            status = 201,
        ).reference.id
    }

    private val manualUri = "https://manuals.example.invalid/water-heater/manual.pdf"
    private val manual = "%PDF-1.7 Example Water Heater manual %%EOF".toByteArray()

    /** One materialize over a fresh connection and the whole answer back, or "" for a connection closed without one. */
    private fun sendMaterialize(port: Int, referenceId: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 30_000
        socket.getOutputStream().apply {
            write(
                ("POST /v1/references/$referenceId/materialize HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Authorization: Bearer $TOKEN\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}")
                    .toByteArray(),
            )
            flush()
        }
        try {
            socket.getInputStream().readBytes().decodeToString()
        } catch (e: IOException) {
            ""
        }
    }

    private fun inBackground(block: () -> Unit): Thread = Thread { runCatching(block) }.apply {
        isDaemon = true
        start()
    }

    private fun awaitRequests(count: Int) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (server.requests.value < count && System.nanoTime() < until) Thread.sleep(10)
        assertEquals("request $count was never read", count, server.requests.value)
    }

    private fun awaitLockFree() {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (graph.apiLongWrites.isLocked && System.nanoTime() < until) Thread.sleep(10)
        assertFalse("the long-write lock was never released", graph.apiLongWrites.isLocked)
    }

    private fun restartOver(router: ApiRouter) {
        server.stop()
        server = LoopbackApiServer(router, networkPermissionGranted = { true }, port = 0)
        assertEquals(StartOutcome.Bound, server.start())
    }

    /**
     * Row 44 (C33): a materialize parked in its commit, the listener stopped and started, and the same reference
     * materialized on the new generation. The second waits for the process-wide lock, then R85-6 answers 409
     * `ATTACHMENT_ALREADY_HELD` naming the first row: one row, and the store's `put` called once.
     */
    @Test fun aSecondMaterializeOfOneReferenceAcrossGenerationsWritesOnce() {
        val rig = MaterializeRig()
        val reference = rig.reference(manualUri)
        rig.transport.serve(manualUri, manual)
        restartOver(router(rig.routes))
        val firstPort = server.boundPort
        val first = inBackground { sendMaterialize(firstPort, reference) }
        assertTrue("the first commit never reached put", rig.gated.parked.await(10, TimeUnit.SECONDS))

        server.stop()
        assertEquals(StartOutcome.Bound, server.start())
        val secondPort = server.boundPort
        var second = ""
        val retry = inBackground { second = sendMaterialize(secondPort, reference) }
        awaitRequests(2)
        Thread.sleep(300) // time for the retry to reach the lock — or, without one, the store
        rig.gated.release.countDown()
        retry.join(15_000)
        first.join(15_000)

        val rows = runBlocking { graph.attachments.forAsset(AssetId(rig.asset)) }
        assertEquals("two writes landed", 1, rows.size)
        assertTrue(second.take(400), second.startsWith("HTTP/1.1 409 Conflict\r\n"))
        assertTrue(second, second.contains("ATTACHMENT_ALREADY_HELD"))
        assertTrue(second, second.contains("AlreadyHave(attachmentId=${rows.single().id.value})"))
        assertEquals("the store's put ran more than once", 1, rig.gated.puts.get())
        assertTrue(manual.contentEquals(rig.store.files.getValue(rows.single().storageLocator)))
        assertTrue(stagingIsEmpty())
    }

    /**
     * Row 44 (C33): an upload on the next generation, arriving while a materialize parked in its commit holds the lock,
     * waits for it (one generation serves one connection at a time, so only a stop and start lets the two overlap).
     * The materialize's commit is not cut by the stop; both rows land, each with its own bytes.
     */
    @Test fun anUploadWaitsForAMaterializeHoldingTheLock() {
        val rig = MaterializeRig()
        val reference = rig.reference(manualUri)
        rig.transport.serve(manualUri, manual)
        restartOver(router(rig.routes))
        val firstPort = server.boundPort
        val first = inBackground { sendMaterialize(firstPort, reference) }
        assertTrue("the materialize never reached put", rig.gated.parked.await(10, TimeUnit.SECONDS))

        server.stop()
        assertEquals(StartOutcome.Bound, server.start())
        val port = server.boundPort
        val scan = "Example Water Heater, a scanned label".toByteArray()
        var uploaded = ""
        val upload = inBackground { uploaded = sendUpload(port, rig.asset, scan, metadataFor("op-after", scan)) }
        awaitRequests(2)
        Thread.sleep(300) // time for the upload to reach the lock — or, without one, the store
        assertEquals("the upload did not wait for the materialize's lock", 1, rig.gated.puts.get())
        rig.gated.release.countDown()
        first.join(15_000)
        upload.join(15_000)

        assertTrue(uploaded.take(400), uploaded.startsWith("HTTP/1.1 201 Created\r\n"))
        val rows = runBlocking { graph.attachments.forAsset(AssetId(rig.asset)) }.sortedBy { it.createdAt }
        assertEquals(2, rows.size)
        assertEquals(manualUri, rows.first { it.source != null }.source?.uri)
        assertTrue(manual.contentEquals(rig.store.files.getValue(rows.first { it.source != null }.storageLocator)))
        assertTrue(scan.contentEquals(rig.store.files.getValue(rows.first { it.source == null }.storageLocator)))
        assertTrue(stagingIsEmpty())
    }

    /**
     * Row 45 (C33): a stale generation's materialize finishing after `start()` leaves the new generation's download
     * registered — the slot is compare-and-clear — and a later `stop()` still cancels it: staging empty, no row.
     */
    @Test fun aStaleGenerationsFinallyLeavesTheNewDownloadRegistered() {
        val rig = MaterializeRig()
        val stale = rig.reference(manualUri)
        rig.transport.serve(manualUri, manual)
        val slowUri = "https://slow.example.invalid/water-heater/wiring.pdf"
        val fresh = rig.reference(slowUri)
        val body = rig.transport.hang(slowUri)
        val shared = router(rig.routes)
        restartOver(shared)
        val firstPort = server.boundPort
        val first = inBackground { sendMaterialize(firstPort, stale) }
        assertTrue("the stale commit never reached put", rig.gated.parked.await(10, TimeUnit.SECONDS))

        server.stop()
        assertEquals(StartOutcome.Bound, server.start())
        val secondPort = server.boundPort
        val second = inBackground { sendMaterialize(secondPort, fresh) }
        awaitRequests(2)
        Thread.sleep(200) // the new download registers, then waits for the lock
        rig.gated.release.countDown()
        first.join(15_000)
        assertTrue("the new download never started", body.blocked.await(10, TimeUnit.SECONDS))
        Thread.sleep(300) // the stale generation's `finally` has run by now

        val registered = shared.downloadInFlight()
        assertTrue("the stale generation unregistered the new download", registered != null && registered.isActive)
        server.stop()
        awaitLockFree()
        second.join(15_000)

        assertTrue("the new download was not cancelled", body.closed)
        assertTrue(stagingIsEmpty())
        val rows = runBlocking { graph.attachments.forAsset(AssetId(rig.asset)) }
        assertEquals("only the stale generation's row", listOf(manualUri), rows.map { it.source?.uri })
    }

    /**
     * A reference read whose **first** call parks until the test releases it: the gap between a request's decode and
     * its register. Every later read passes straight through.
     */
    private class GatedReferences(private val inner: ReferenceRepository) : ReferenceRepository by inner {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val first = java.util.concurrent.atomic.AtomicBoolean(true)

        override suspend fun get(id: ReferenceId) = inner.get(id).also {
            if (first.getAndSet(false)) {
                entered.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
    }

    /**
     * B2-pre BC5: a materialize read before `stop()` that registers its download after it starts cancelled — its
     * `Job` is a child of the stopped generation's — so the transport is never called, nothing is staged or written,
     * and the connection closes with no answer (no code: `stop()` closed it, and the router sends nothing).
     */
    @Test fun aMaterializeThatRegistersAfterStopFetchesNothing() {
        val gatedReferences = GatedReferences(graph.references)
        val rig = MaterializeRig(references = gatedReferences)
        rig.gated.release.countDown()
        val reference = rig.reference(manualUri)
        rig.transport.serve(manualUri, manual)
        restartOver(router(rig.routes))
        val port = server.boundPort
        var answer: String? = null
        val flight = inBackground { answer = sendMaterialize(port, reference) }
        assertTrue("the request never reached its reference read", gatedReferences.entered.await(10, TimeUnit.SECONDS))

        server.stop()
        gatedReferences.release.countDown()
        flight.join(15_000)
        Thread.sleep(500) // time for the handler to register and, were it live, to fetch
        awaitLockFree()

        assertEquals("a download that registered after stop() reached the transport", emptyList<String>(), rig.transport.requests)
        assertTrue(stagingIsEmpty())
        assertTrue(runBlocking { graph.attachments.forAsset(AssetId(rig.asset)) }.isEmpty())
        assertEquals("", answer)
    }

    /**
     * Fix round 1 (review m1): a stale generation's request that reaches its register after `stop()` — while the next
     * generation's download is live — registers nothing, so it neither overwrites the live download in the slot nor
     * clears it on the way out. The live one is still what the slot tracks, and `stop()` still ends it.
     */
    @Test fun aStaleRegistrantAfterStopLeavesTheLiveDownloadRegistered() {
        val gatedReferences = GatedReferences(graph.references)
        val rig = MaterializeRig(references = gatedReferences)
        val stale = rig.reference(manualUri)
        rig.transport.serve(manualUri, manual)
        val slowUri = "https://slow.example.invalid/water-heater/wiring.pdf"
        val live = rig.reference(slowUri)
        val body = rig.transport.hang(slowUri)
        val shared = router(rig.routes)
        restartOver(shared)
        val firstPort = server.boundPort
        val first = inBackground { sendMaterialize(firstPort, stale) }
        assertTrue("the stale request never reached its reference read", gatedReferences.entered.await(10, TimeUnit.SECONDS))

        server.stop()
        assertEquals(StartOutcome.Bound, server.start())
        val secondPort = server.boundPort
        val second = inBackground { sendMaterialize(secondPort, live) }
        assertTrue("the live download never started", body.blocked.await(10, TimeUnit.SECONDS))
        val registered = shared.downloadInFlight()
        assertTrue("the live download was not registered", registered != null && registered.isActive)

        gatedReferences.release.countDown()
        first.join(15_000)
        Thread.sleep(300) // the stale request registers (or not) and unwinds

        assertTrue("the stale registrant displaced the live download", shared.downloadInFlight() === registered)
        assertEquals("the stale request fetched", listOf(slowUri), rig.transport.requests)
        server.stop()
        awaitLockFree()
        second.join(15_000)
        assertTrue("the live download was not cancelled", body.closed)
        assertTrue(stagingIsEmpty())
        assertTrue(runBlocking { graph.attachments.forAsset(AssetId(rig.asset)) }.isEmpty())
    }
}
