package com.loosecannon.servicetag.api

import android.system.ErrnoException
import android.system.OsConstants
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ServerSocketFactory
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
 * What [LoopbackApiServer.start] did (#66). Before this it said only `true` or `false`, so a taken
 * port and a network permission the owner's hardened Android build had revoked drew the same
 * sentence, whose remedy cannot fix the second.
 */
internal sealed interface StartOutcome {
    data object Bound : StartOutcome
    data object PortInUse : StartOutcome
    data object NetworkPermissionDenied : StartOutcome
    data object Other : StartOutcome
}

/** The listener as the screen should describe it: one value, published by [LoopbackApiServer]. */
internal sealed interface ListenerState {
    /** Never started, or [LoopbackApiServer.stop] ran. */
    data object Stopped : ListenerState

    data object Listening : ListenerState

    /** [outcome] is never [StartOutcome.Bound]. */
    data class CouldNotStart(val outcome: StartOutcome) : ListenerState

    /** `accept()` failed on a socket `stop()` had not closed (#51). Terminal: nothing restarts it. */
    data object Died : ListenerState
}

/** The errno a bind failure carried, reduced to what [classifyBindFailure] can use. */
internal enum class BindErrno { ADDRESS_IN_USE, ACCESS_DENIED, OTHER, UNKNOWN }

/**
 * Why a listener could not start, **conservatively** (#66). The rules apply in order:
 * 1. `EADDRINUSE` is a taken port, whatever the permission says.
 * 2. A permission that reads as not granted, with `EACCES`/`EPERM` or no errno at all, is the denial.
 * 3. No errno but a [BindException] is a taken port: that is the JVM's shape, where no
 *    [ErrnoException] is ever in the chain; Android always carries the errno.
 * 4. Anything else is [StartOutcome.Other].
 *
 * A permission that reads as granted never produces [StartOutcome.NetworkPermissionDenied], so on
 * stock Android, where INTERNET is install-time and always granted, the denial is never shown.
 */
internal fun classifyBindFailure(errno: BindErrno, isBindException: Boolean, permissionGranted: Boolean): StartOutcome =
    when {
        errno == BindErrno.ADDRESS_IN_USE -> StartOutcome.PortInUse
        !permissionGranted && (errno == BindErrno.ACCESS_DENIED || errno == BindErrno.UNKNOWN) ->
            StartOutcome.NetworkPermissionDenied
        errno == BindErrno.UNKNOWN && isBindException -> StartOutcome.PortInUse
        else -> StartOutcome.Other
    }

/**
 * The errno adapter: the first [ErrnoException] in [failure]'s cause chain, reduced to a
 * [BindErrno]. Thin on purpose — on the JVM `ErrnoException` and `OsConstants` are stubs (every
 * errno reads 0), so this is proved only on a device, by `DeveloperApiListenerTest`.
 */
internal fun bindErrnoOf(failure: Throwable): BindErrno {
    var cause: Throwable? = failure
    repeat(MAX_CAUSE_DEPTH) {
        val link = cause ?: return BindErrno.UNKNOWN
        if (link is ErrnoException) {
            return when (link.errno) {
                OsConstants.EADDRINUSE -> BindErrno.ADDRESS_IN_USE
                OsConstants.EACCES, OsConstants.EPERM -> BindErrno.ACCESS_DENIED
                else -> BindErrno.OTHER
            }
        }
        cause = link.cause
    }
    return BindErrno.UNKNOWN
}

/** A cause chain longer than this is a cycle, not a history. */
private const val MAX_CAUSE_DEPTH = 16

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
 * **[start] returns a [StartOutcome]**, rather than throwing: the caller is a Compose lifecycle
 * effect, a `SocketException` there would be a crash on a screen opening, and a port already in use
 * is a thing that happens. The one log line this feature produces is the caller's, on anything but
 * [StartOutcome.Bound]. [state] carries the same answer, and also the one thing [start] cannot: a
 * listener that stopped answering on its own ([ListenerState.Died], #51).
 */
internal class LoopbackApiServer(
    private val router: ApiRouter,
    /**
     * Whether INTERNET reads as granted. Consulted only after a bind has failed, to corroborate a
     * denial ([classifyBindFailure]); no default, so every caller decides where the answer comes from.
     */
    private val networkPermissionGranted: () -> Boolean,
    private val port: Int = DEVELOPER_API_PORT,
    /** Parameterised only so a test can prove the drop without waiting five seconds for it. */
    private val readTimeoutMillis: Int = SOCKET_TIMEOUT_MILLIS,
    /** The bind seam: a test injects a failed bind or a failing `accept()` through it. */
    private val serverSocketFactory: ServerSocketFactory = ServerSocketFactory.getDefault(),
) {
    private val _requests = MutableStateFlow(0)

    /** Answers given **this session**, refusals included — never reset by a restart within it. */
    val requests: StateFlow<Int> = _requests.asStateFlow()

    @Volatile private var socket: ServerSocket? = null

    /** The connection currently being answered, so [stop] can close it rather than wait for it. */
    @Volatile private var inFlight: Socket? = null

    private val _state = MutableStateFlow<ListenerState>(ListenerState.Stopped)

    /** What the screen shows: listening, stopped, could not start (and why), or died. */
    val state: StateFlow<ListenerState> = _state.asStateFlow()

    /** The port actually bound, or 0 while stopped or after the listener died. */
    val boundPort: Int get() = socket?.localPort ?: 0

    /** The address actually bound, or null while stopped. Always [LOOPBACK_ADDRESS] when running. */
    val boundAddress: String? get() = socket?.inetAddress?.hostAddress

    /**
     * Binds and starts the worker, or says why it could not. Idempotent: while listening it binds
     * nothing new and answers [StartOutcome.Bound].
     *
     * Catches [IOException] **and [SecurityException]**, and nothing broader: a denied socket is
     * expected as a `SocketException` (the one recorded denial logged the bind line rather than
     * crashing), and a build that throws `SecurityException` instead must not turn the screen
     * opening into a crash.
     */
    @Synchronized
    fun start(): StartOutcome {
        if (socket != null) return StartOutcome.Bound
        val bound = try {
            serverSocketFactory.createServerSocket(port, ACCEPT_BACKLOG, InetAddress.getByName(LOOPBACK_ADDRESS))
        } catch (e: IOException) {
            return couldNotStart(e)
        } catch (e: SecurityException) {
            return couldNotStart(e)
        }
        socket = bound
        _state.value = ListenerState.Listening
        // N1: not held in a field. Nothing ever read it — it was assigned in `start()`, nulled in
        // `stop()`, and never consulted — so it was write-only dead state.
        Thread({ acceptLoop(bound) }, "servicetag-developer-api").apply {
            isDaemon = true
            start()
        }
        return StartOutcome.Bound
    }

    private fun couldNotStart(failure: Exception): StartOutcome {
        val outcome = classifyBindFailure(
            errno = bindErrnoOf(failure),
            isBindException = failure is BindException,
            permissionGranted = networkPermissionGranted(),
        )
        _state.value = ListenerState.CouldNotStart(outcome)
        return outcome
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
     *
     * It always publishes [ListenerState.Stopped], even when the socket is already gone (a failed
     * start, or a listener that died): the message belongs to a visit, and a pause must clear it.
     */
    @Synchronized
    fun stop() {
        _state.value = ListenerState.Stopped
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
                retire(bound) // `stop()` closed it, or the OS failed the accept. Either way, finished.
                return
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

    /**
     * #51: an `accept()` that failed on a socket `stop()` did not close is terminal. The socket is
     * closed and forgotten, so [boundPort] reads 0 and the kernel stops completing handshakes nobody
     * will read, and [ListenerState.Died] is published so the screen stops showing a healthy port.
     *
     * Compare-and-clear under the lock, as review S1 did for `inFlight`: if [socket] is no longer
     * [bound], then `stop()` closed it (and published [ListenerState.Stopped] itself), or a later
     * generation owns the field — and a stale worker must never close, null or re-label that one.
     * Nothing restarts the listener here: S6's leave-and-return is the remedy.
     */
    @Synchronized
    private fun retire(bound: ServerSocket) {
        if (socket !== bound) return
        socket = null
        closeQuietly(bound)
        _state.value = ListenerState.Died
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
     * graceful instead of a reset. Bounded three ways: by the byte budget; by [readTimeoutMillis] on
     * each individual read, so a peer that goes silent mid-drain does not block forever on one call;
     * and by a wall-clock deadline of [readTimeoutMillis] over the *whole loop* — [Socket.soTimeout]
     * only bounds one `read()` call, not how many of them the loop may make, so without the deadline
     * a peer trickling a byte every `readTimeoutMillis − ε` could hold this loop, and the listener's
     * one worker, open for as long as it kept sending (review re-review New-1).
     */
    private fun drainBeforeClose(client: Socket) {
        try {
            client.shutdownOutput()
            val input = client.getInputStream()
            val scratch = ByteArray(8 * 1024)
            var drained = 0
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeoutMillis.toLong())
            while (drained < DRAIN_BUDGET_BYTES && System.nanoTime() < deadline) {
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
