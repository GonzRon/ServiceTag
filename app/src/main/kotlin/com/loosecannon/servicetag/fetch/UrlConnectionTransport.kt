package com.loosecannon.servicetag.fetch

import com.loosecannon.servicetag.core.fetch.DocumentTransport
import com.loosecannon.servicetag.core.fetch.TransportFailure
import com.loosecannon.servicetag.core.fetch.TransportResponse
import com.loosecannon.servicetag.core.references.ReferenceUris
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * #85 C16 (R85-7, R85-8, R85-13, R85-15): the one GET behind Save as document, and the only class in the app that
 * opens an HTTP connection. Whether a URL may be fetched at all — https, the host and address rule, redirects,
 * statuses, types and sizes — is the core's `FetchDocument` and `HopPolicy`; this class does one hop, as told.
 *
 * **Per call, on this connection only** (no process-wide default is set): redirects are not followed, nothing is
 * cached, the connect and idle deadlines are R85-7's, and exactly three request properties are set — [ACCEPT],
 * `Accept-Encoding: identity` (so a transparent gzip never hides the length) and the fixed
 * [DocumentTransport.USER_AGENT] (R85-13). No cookie, credential or sign-in state is set or sent.
 *
 * **Host agreement (review M1).** Before anything is opened, the host the platform's URL parser reads must equal,
 * ASCII case-insensitively, the host the core checked ([ReferenceUris.hostOf]); otherwise the URL is `UNREACHABLE`,
 * so no odd authority can be checked as one host and connected as another.
 *
 * **Cancellation (review m3).** Cancelling [get] disconnects. A response that still arrives is closed, never
 * returned and never left open. Only a 2xx carries its body; any other status answers an empty body, and the
 * server's error page is never read. Every failure is a [TransportFailure] ([transportFailureOf]), never a platform
 * exception with a message that could hold the URL. Nothing is logged.
 */
class UrlConnectionTransport(
    private val networkPermissionGranted: () -> Boolean,
    private val io: CoroutineContext = Dispatchers.IO,
    private val connectTimeoutMillis: Int = DocumentTransport.CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DocumentTransport.IDLE_TIMEOUT_MILLIS,
    private val open: (URL) -> URLConnection = URL::openConnection,
) : DocumentTransport {

    override suspend fun get(url: String): TransportResponse {
        val arrived = AtomicReference<TransportResponse?>()
        try {
            return withContext(io) {
                val connection = try {
                    connectionFor(url)
                } catch (e: Throwable) {
                    throw failureOf(e, FailurePhase.CONNECT)
                }
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { disconnectQuietly(connection) }
                    val response = try {
                        respond(connection)
                    } catch (e: Throwable) {
                        disconnectQuietly(connection)
                        cont.resumeWithException(failureOf(e, FailurePhase.CONNECT))
                        return@suspendCancellableCoroutine
                    }
                    arrived.set(response)
                    cont.resume(response) // ignored when already cancelled; the catch below closes it
                }
            }
        } catch (e: Throwable) {
            // A response that arrived after the cancel (or whose hand-back the cancel discarded) is closed, never
            // dropped open.
            arrived.get()?.close()
            throw e
        }
    }

    private fun connectionFor(url: String): HttpURLConnection {
        val parsed = URL(url)
        val checked = ReferenceUris.hostOf(url) ?: throw TransportFailure(TransportFailure.Kind.UNREACHABLE)
        val platform = parsed.host.orEmpty().removePrefix("[").removeSuffix("]")
        if (!sameAsciiHost(platform, checked)) throw TransportFailure(TransportFailure.Kind.UNREACHABLE)
        val connection = open(parsed) as? HttpURLConnection
            ?: throw TransportFailure(TransportFailure.Kind.UNREACHABLE)
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.setRequestProperty("Accept", ACCEPT)
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", DocumentTransport.USER_AGENT)
        return connection
    }

    /** Blocks until the response head arrives. Not an HTTP answer at all (no status line) is `UNREACHABLE`. */
    private fun respond(connection: HttpURLConnection): TransportResponse {
        val status = connection.responseCode
        if (status < 0) throw TransportFailure(TransportFailure.Kind.UNREACHABLE)
        val body = if (status in 200..299) GuardedBody(connection.inputStream) else ByteArrayInputStream(ByteArray(0))
        return TransportResponse(
            status = status,
            location = connection.getHeaderField("Location"),
            contentType = connection.contentType,
            contentLength = connection.contentLengthLong.takeIf { it >= 0 },
            body = body,
        ) { disconnectQuietly(connection) }
    }

    private fun failureOf(e: Throwable, phase: FailurePhase) = transportFailureOf(e, phase, networkPermissionGranted)

    /** The body's reads, their failures classified as the body's (`TIMED_OUT` or `INTERRUPTED`). */
    private inner class GuardedBody(private val inner: InputStream) : InputStream() {
        override fun read(): Int = guarded { inner.read() }

        override fun read(b: ByteArray, off: Int, len: Int): Int = guarded { inner.read(b, off, len) }

        override fun skip(n: Long): Long = guarded { inner.skip(n) }

        override fun available(): Int = guarded { inner.available() }

        override fun close() {
            try {
                inner.close()
            } catch (_: Exception) {
                // The response's own close is what frees the connection.
            }
        }

        private inline fun <T> guarded(read: () -> T): T = try {
            read()
        } catch (e: Throwable) {
            throw failureOf(e, FailurePhase.BODY)
        }
    }

    companion object {
        /** The documents Save as document keeps, and anything else at a low weight: the bytes decide, not this. */
        const val ACCEPT = "application/pdf, image/png, image/jpeg;q=0.9, */*;q=0.1"

        /** Equal but for ASCII case: no locale, no Unicode case mapping. */
        private fun sameAsciiHost(a: String, b: String): Boolean {
            fun lower(c: Char) = if (c in 'A'..'Z') c + ('a' - 'A') else c
            return a.length == b.length && a.indices.all { lower(a[it]) == lower(b[it]) }
        }

        /** Idempotent and never throws: it runs on cancel and cleanup paths. */
        private fun disconnectQuietly(connection: HttpURLConnection) {
            try {
                connection.disconnect()
            } catch (_: Exception) {
                // Already gone.
            }
        }
    }
}
