package com.loosecannon.servicetag.api

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The only address this listener will ever bind. A literal, not [InetAddress.getLoopbackAddress],
 * because that is free to answer `::1` and because a literal is a thing a reviewer and a grep can
 * both see.
 */
private const val LOOPBACK_ADDRESS = "127.0.0.1"

/** The port the app listens on and the workstation forwards to. Fixed, so nothing has to discover it. */
internal const val DEVELOPER_API_PORT: Int = 17337

/** How long a connection may go quiet before it is dropped, so it cannot hold the one worker. */
internal const val SOCKET_TIMEOUT_MILLIS = 5_000

/**
 * Whether a peer that reached this listener may be answered: loopback only.
 *
 * Extracted so it can be tested, because it cannot be *driven* through a socket bound to
 * `127.0.0.1` — which is the point of it being defence in depth rather than the boundary. The bind
 * is the boundary; this is the second check on it.
 */
internal fun isAcceptablePeer(address: InetAddress): Boolean = address.isLoopbackAddress

/** How many connections may queue while one is being answered. */
private const val ACCEPT_BACKLOG = 4

/**
 * Review S2's drain ceiling: never more than the largest body any route accepts, so a peer that
 * declared a legitimate (if over-cap) length is fully drained, and a peer sending something far
 * larger than any real route allows is simply not waited on past this budget.
 */
private const val DRAIN_BUDGET_BYTES = MAX_IMPORT_BYTES

/**
 * A loopback HTTP/1.1 listener, one connection at a time, on one daemon thread.
 *
 * **Deliberately narrow, deliberately hand-rolled** — the plan's dependency decision argues it in
 * full. What matters here: there is no chunked decoding, no multipart, no body spooled to disk, no
 * session, no thread pool and no keep-alive. One client (the workstation's MCP server) makes one
 * call at a time, so serialising connections means no shared mutable state between requests and no
 * concurrency to reason about; a second caller waits in the backlog or is refused.
 *
 * **Two independent checks on who is talking.** The socket is bound to [LOOPBACK_ADDRESS], so the
 * kernel refuses anything from off this phone; and every accepted connection's peer is checked
 * again with [Socket.getInetAddress] before a byte of it is read, so a misconfiguration cannot
 * quietly widen the first check.
 *
 * **[start] returns whether it bound**, rather than throwing: the caller is a Compose lifecycle
 * effect, a `SocketException` there would be a crash on a screen opening, and a port already in use
 * is a thing that happens. The one log line this feature produces is the caller's, on a `false`.
 */
internal class LoopbackApiServer(
    private val router: ApiRouter,
    private val port: Int = DEVELOPER_API_PORT,
    /** Parameterised only so a test can prove the drop without waiting five seconds for it. */
    private val readTimeoutMillis: Int = SOCKET_TIMEOUT_MILLIS,
) {
    private val _requests = MutableStateFlow(0)

    /** Answers given **this session**, refusals included — never reset by a restart within it. */
    val requests: StateFlow<Int> = _requests.asStateFlow()

    @Volatile private var socket: ServerSocket? = null

    /** The connection currently being answered, so [stop] can close it rather than wait for it. */
    @Volatile private var inFlight: Socket? = null

    /** The port actually bound, or 0 while stopped. */
    val boundPort: Int get() = socket?.localPort ?: 0

    /** The address actually bound, or null while stopped. Always [LOOPBACK_ADDRESS] when running. */
    val boundAddress: String? get() = socket?.inetAddress?.hostAddress

    @Synchronized
    fun start(): Boolean {
        if (socket != null) return true
        val bound = try {
            ServerSocket(port, ACCEPT_BACKLOG, InetAddress.getByName(LOOPBACK_ADDRESS))
        } catch (e: IOException) {
            return false
        }
        socket = bound
        // N1: not held in a field. Nothing ever read it — it was assigned in `start()`, nulled in
        // `stop()`, and never consulted — so it was write-only dead state.
        Thread({ acceptLoop(bound) }, "servicetag-developer-api").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /**
     * Stops accepting, and closes the connection currently being answered.
     *
     * What this guarantees: after it returns, nothing new is accepted, and no response is delivered
     * to a connection this listener had recorded as in flight — closing its socket makes the
     * in-flight `writeResponse` throw, which the per-connection handler swallows. A connection
     * accepted in the instant before `stop()` ran, and not yet recorded, may still be answered in
     * full; its peer connected while the screen was resumed and still needs the token. What it deliberately does **not** do is
     * join the worker: this is called from a Compose lifecycle effect on the main thread, and a
     * bounded join there is an ANR waiting for a slow query. So a domain call already inside
     * `uow.write` runs to completion — as **one transaction**, which is the right outcome, because
     * abandoning a half-applied merge would be strictly worse than finishing it. The write lands or
     * rolls back atomically; the answer is simply never sent.
     */
    @Synchronized
    fun stop() {
        val bound = socket ?: return
        socket = null
        // Closing the server socket is what unblocks `accept()`; the loop then sees `isClosed`.
        closeQuietly(bound)
        inFlight?.let { closeQuietly(it) }
        inFlight = null
    }

    private fun acceptLoop(bound: ServerSocket) {
        while (!bound.isClosed) {
            val client = try {
                bound.accept()
            } catch (e: IOException) {
                return // `stop()` closed it, or the OS did. Either way this listener is finished.
            }
            inFlight = client
            try {
                client.use { answer(it) }
            } catch (t: Throwable) {
                // `Throwable`, not `IOException`, and per connection: a client that hung up, a
                // `RuntimeException` from a handler, an `OutOfMemoryError` from a 4 MiB body that
                // never arrives — none of them may take the listener down, because a dead worker
                // leaves the screen still showing a port that answers nothing.
            } finally {
                // Review S1: compare-and-clear, not an unconditional null. `inFlight` is instance
                // state shared across listener generations — `stop()` does not join, so `start()`
                // is free to spin up a second generation while this `finally` is still on its way
                // to running. An unconditional clear would let this (stale) worker discard the
                // *new* generation's in-flight socket, defeating `stop()`'s promise that no answer
                // reaches a connection it had recorded. Retracting only the socket this worker
                // itself recorded keeps that promise across a pause-then-quick-resume.
                if (inFlight === client) inFlight = null
            }
        }
    }

    private fun closeQuietly(closeable: java.io.Closeable) {
        try {
            closeable.close()
        } catch (e: IOException) {
            // Already closed, or the OS refused; either way there is nothing left to close.
        }
    }

    private fun answer(client: Socket) {
        // The second check. The bind already refuses anything off this phone; this refuses anything
        // that reached us some other way, before a byte of it is parsed.
        if (!isAcceptablePeer(client.inetAddress)) return
        client.soTimeout = readTimeoutMillis
        val response = try {
            val request = parseRequest(client.getInputStream(), router::bodyCapFor)
            _requests.update { it + 1 }
            router.handle(request)
        } catch (e: MalformedRequest) {
            _requests.update { it + 1 }
            e.response
        }
        val output = client.getOutputStream()
        writeResponse(output, response)
        output.flush()
        drainBeforeClose(client)
    }

    /**
     * Review S2. `client.use { answer(it) }` (in [acceptLoop]) closes [client] the instant this
     * returns; on Linux, closing a socket that still has unread received data queued sends an RST
     * and can discard outbound data still in flight, so a caller can see `ECONNRESET` instead of the
     * response it was just sent. That is most likely exactly for the case the cap exists to serve: a
     * 413 fires *before* the body is read, so the whole declared body may still be arriving when the
     * response goes out.
     *
     * Half-closing the write side tells the peer no more is coming — which is also what lets its own
     * read of the response return — and then draining whatever it still has queued, up to
     * [DRAIN_BUDGET_BYTES] or EOF, empties the receive buffer before `close()` runs so that close is
     * graceful instead of a reset. Bounded twice over: by the byte budget, and by [readTimeoutMillis]
     * already set on this socket, so a peer that never sends its declared body and never closes
     * cannot hold the worker here either.
     */
    private fun drainBeforeClose(client: Socket) {
        try {
            client.shutdownOutput()
            val input = client.getInputStream()
            val scratch = ByteArray(8 * 1024)
            var drained = 0
            while (drained < DRAIN_BUDGET_BYTES) {
                val n = input.read(scratch)
                if (n < 0) break
                drained += n
            }
        } catch (e: IOException) {
            // Half-closed already, closed by the peer, or the read timed out — nothing left to
            // drain, and the caller either got its answer or gave up waiting for one.
        }
    }
}
