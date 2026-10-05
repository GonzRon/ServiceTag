package com.loosecannon.servicetag.seasonsync

import com.loosecannon.servicetag.core.fetch.DocumentTransport
import com.loosecannon.servicetag.core.fetch.HostResolver
import com.loosecannon.servicetag.core.fetch.TransportFailure
import com.loosecannon.servicetag.core.seasonsync.EndpointCheck
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaEndpoint
import com.loosecannon.servicetag.core.seasonsync.HaEndpointPolicy
import com.loosecannon.servicetag.core.seasonsync.HaHostKind
import com.loosecannon.servicetag.core.seasonsync.HaHttpAnswer
import com.loosecannon.servicetag.core.seasonsync.HaListOutcome
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome
import com.loosecannon.servicetag.core.seasonsync.HaScheme
import com.loosecannon.servicetag.core.seasonsync.HaStateReader
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.core.seasonsync.eligibleNow
import com.loosecannon.servicetag.core.seasonsync.isJsonMediaType
import com.loosecannon.servicetag.core.seasonsync.isPrivateLanAddress
import com.loosecannon.servicetag.core.seasonsync.mapHaAnswer
import com.loosecannon.servicetag.core.seasonsync.mapHaStatesAnswer
import com.loosecannon.servicetag.core.seasonsync.parseObject
import com.loosecannon.servicetag.fetch.FailurePhase
import com.loosecannon.servicetag.fetch.transportFailureOf
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import javax.net.ssl.SSLException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** #16 (R16-13, N-3): Test connection's answer, mapped on its own; a failure carries the poll's kinds. */
sealed interface ConnectionTestOutcome {
    data object Ok : ConnectionTestOutcome

    data class Failed(val kind: SyncErrorKind, val detail: String?) : ConnectionTestOutcome
}

/**
 * #16 C19 (R16-6, R16-13, R16-Q-B, R16-Q-D as amended) — the Home Assistant client: one authenticated GET of one
 * entity's state, or of the API root for Test connection, at the configured origin and nowhere else. Per call, on its
 * own connection, in order:
 * 1. the address rule again ([HaEndpointPolicy]; refused → `ENDPOINT_REFUSED`) and the network permission (not
 *    granted → `DENIED`, a quiet status); then, in the home-network mode **or for any `http` address whatever the
 *    stored setting** (fail closed, C-5), the current network must be the captured Wi-Fi ([eligibleNow]), else
 *    `NOT_ON_LOCAL_NETWORK` with the reader's [UnconfirmedCause] as `detail` (null: another network); and in that mode
 *    an https name must resolve only to private addresses, else `NAME_NOT_LOCAL` (`UNREACHABLE` when it does not
 *    resolve). Nothing is opened before all of this passes;
 * 2. the platform's parsed host must equal the policy's, ASCII case-insensitively, else `ENDPOINT_REFUSED`;
 * 3. GET with redirects not followed, nothing cached and exactly four request properties: the bearer, JSON,
 *    identity encoding and the fixed user agent; no cookie;
 * 4. connect [CONNECT_MILLIS], idle [IDLE_MILLIS], the whole call [CALL_MILLIS]; only a 200's body is read, at most
 *    [MAX_BODY_BYTES] (`truncated` beyond); a cancel disconnects;
 * 5. a TLS failure is `TLS_FAILED` (system trust anchors only, C20); every other failure is #85's classification.
 *
 * Every outcome is a value, never a throw, and none carries the URL, host, entity, Wi-Fi name or token; nothing is
 * logged. The token is a parameter only, held for one request.
 */
class HomeAssistantStateClient(
    /** The stored connection, read at each port request for its network setting (C4a). */
    private val settings: suspend () -> HaConnection?,
    private val networkPermissionGranted: () -> Boolean,
    private val currentNetwork: CurrentNetworkReader,
    private val resolver: HostResolver,
    private val io: CoroutineContext = Dispatchers.IO,
    private val parse: (String) -> URL = ::URL,
    /** Direct, never through a system proxy: the bearer goes to the configured origin and nowhere else (I10). */
    private val open: (URL) -> URLConnection = { it.openConnection(Proxy.NO_PROXY) },
) : HaStateReader {

    /**
     * The port (C7): [baseUrl] must be the stored connection's address, whose setting then governs; any other
     * address, or none stored, is `ENDPOINT_REFUSED` with nothing opened (I10).
     */
    override suspend fun read(baseUrl: String, entityId: String, token: Secret): HaReadOutcome {
        val stored = settings()
        val canonical = HaEndpointPolicy.canonical(baseUrl)
        if (stored == null || canonical == null || HaEndpointPolicy.canonical(stored.baseUrl) != canonical) {
            return refused(SyncErrorKind.ENDPOINT_REFUSED)
        }
        return read(stored, entityId, token)
    }

    /**
     * One `GET <base>/api/states/<entityId>`, through C5's mapper. `internal` (review MINOR-2): the port above is the
     * one poll entry, so the stored-connection check cannot be skipped.
     */
    internal suspend fun read(connection: HaConnection, entityId: String, token: Secret): HaReadOutcome =
        when (val exchange = exchange(connection, STATES_PATH + entityId, token)) {
            is Exchange.Answered -> mapHaAnswer(exchange.answer, entityId)
            is Exchange.Failed -> exchange.outcome
        }

    /** Test connection (R16-13): `GET <base>/api/` under the same rules; a 200 JSON object is `Ok`. */
    suspend fun testConnection(connection: HaConnection, token: Secret): ConnectionTestOutcome =
        when (val exchange = exchange(connection, API_ROOT_PATH, token)) {
            is Exchange.Answered -> testOutcomeOf(exchange.answer)
            is Exchange.Failed -> ConnectionTestOutcome.Failed(exchange.outcome.kind, exchange.outcome.detail)
        }

    /**
     * #105 (B2; owner ruling Q2): the setup sheet's one foreground `GET <base>/api/states`, under exactly the rules
     * above — the same gate, headers, timeouts and failure map — with its own body cap, [LIST_MAX_BODY_BYTES], because
     * a whole installation's states run to megabytes where one entity's run to bytes. The poll's cap is untouched.
     * Called only when the owner taps Choose entity or Refresh; never by the runner or the worker. The answer is mapped
     * on [io] too: up to 8 MiB of JSON is not parsed on the caller's thread, which is the sheet's, the main one.
     */
    suspend fun listStates(connection: HaConnection, token: Secret): HaListOutcome =
        when (val exchange = exchange(connection, STATES_LIST_PATH, token, LIST_MAX_BODY_BYTES)) {
            is Exchange.Answered -> withContext(io) { mapHaStatesAnswer(exchange.answer) }
            is Exchange.Failed -> HaListOutcome.Failed(exchange.outcome.kind, exchange.outcome.detail)
        }

    private sealed interface Exchange {
        class Answered(val answer: HaHttpAnswer) : Exchange

        class Failed(val outcome: HaReadOutcome.NoDecision) : Exchange
    }

    private suspend fun exchange(
        connection: HaConnection,
        path: String,
        token: Secret,
        maxBodyBytes: Int = MAX_BODY_BYTES,
    ): Exchange {
        val endpoint = when (val check = HaEndpointPolicy.classify(connection.baseUrl)) {
            is EndpointCheck.Allowed -> check.endpoint
            is EndpointCheck.Refused -> return failed(SyncErrorKind.ENDPOINT_REFUSED)
        }
        if (!networkPermissionGranted()) return failed(SyncErrorKind.DENIED)
        return withTimeoutOrNull(CALL_MILLIS) { checkedExchange(connection, endpoint, path, token, maxBodyBytes) }
            ?: failed(SyncErrorKind.TIMED_OUT)
    }

    private suspend fun checkedExchange(
        connection: HaConnection,
        endpoint: HaEndpoint,
        path: String,
        token: Secret,
        maxBodyBytes: Int,
    ): Exchange {
        val homeOnly = when (endpoint.scheme) {
            HaScheme.HTTP -> true
            HaScheme.HTTPS -> when (connection.networkEligibility) {
                NetworkEligibility.HOME_NETWORK_ONLY -> true
                NetworkEligibility.ANY_NETWORK -> false
            }
        }
        if (homeOnly) {
            val reading = currentNetwork.read()
            val asHome = connection.copy(networkEligibility = NetworkEligibility.HOME_NETWORK_ONLY)
            if (!eligibleNow(asHome, reading.network)) {
                return failed(SyncErrorKind.NOT_ON_LOCAL_NETWORK, reading.cause?.name)
            }
            when (endpoint.hostKind) {
                HaHostKind.NAME -> resolvesOnlyPrivately(endpoint.host)?.let { return it }
                HaHostKind.PRIVATE_IPV4 -> Unit
            }
        }
        val url = try {
            parse(endpoint.canonical + path)
        } catch (_: Exception) {
            return failed(SyncErrorKind.ENDPOINT_REFUSED)
        }
        if (!sameAsciiHost(url.host.orEmpty(), endpoint.host)) return failed(SyncErrorKind.ENDPOINT_REFUSED)
        return withContext(io) { get(url, token, maxBodyBytes) }
    }

    /** C19 step 1b: null when every answer is private (C8 rule 5); else the outcome, nothing opened. */
    private suspend fun resolvesOnlyPrivately(host: String): Exchange.Failed? {
        val addresses = try {
            resolver.resolve(host)
        } catch (e: TransportFailure) {
            return failed(kindOf(e.kind))
        }
        val local = addresses.isNotEmpty() && addresses.all(::isPrivateLanAddress)
        return if (local) null else failed(SyncErrorKind.NAME_NOT_LOCAL)
    }

    private suspend fun get(url: URL, token: Secret, maxBodyBytes: Int): Exchange {
        val connection = try {
            connectionFor(url, token)
        } catch (e: Throwable) {
            return failedBy(e, FailurePhase.CONNECT)
        }
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { disconnectQuietly(connection) }
            if (!cont.isActive) return@suspendCancellableCoroutine
            val exchange = try {
                answerOf(connection, maxBodyBytes)
            } catch (e: Throwable) {
                failedBy(e, FailurePhase.CONNECT)
            } finally {
                disconnectQuietly(connection)
            }
            cont.resume(exchange)
        }
    }

    private fun connectionFor(url: URL, token: Secret): HttpURLConnection {
        val connection = open(url) as? HttpURLConnection ?: throw TransportFailure(TransportFailure.Kind.UNREACHABLE)
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = CONNECT_MILLIS
        connection.readTimeout = IDLE_MILLIS
        connection.setRequestProperty("Authorization", "Bearer " + token.value) // l10n-ok: HTTP header
        connection.setRequestProperty("Accept", "application/json") // l10n-ok: HTTP header
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", DocumentTransport.USER_AGENT)
        return connection
    }

    /** Blocks for the head, then reads a 200's body under [maxBodyBytes]; any other status answers no body. */
    private fun answerOf(connection: HttpURLConnection, maxBodyBytes: Int): Exchange {
        val status = connection.responseCode
        if (status < 0) return failed(SyncErrorKind.UNREACHABLE)
        if (status != OK) return Exchange.Answered(HaHttpAnswer(status, connection.contentType, ByteArray(0), false))
        val contentType = connection.contentType
        return try {
            val (body, truncated) = capped(connection.inputStream, maxBodyBytes)
            Exchange.Answered(HaHttpAnswer(status, contentType, body, truncated))
        } catch (e: Throwable) {
            failedBy(e, FailurePhase.BODY)
        }
    }

    /** At most [maxBodyBytes], and whether more followed; never a byte past the cap is read (review NOTE-5). */
    private fun capped(input: InputStream, maxBodyBytes: Int): Pair<ByteArray, Boolean> = input.use {
        val kept = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (kept.size() <= maxBodyBytes) {
            val read = it.read(buffer, 0, minOf(buffer.size, maxBodyBytes + 1 - kept.size()))
            if (read < 0) break
            kept.write(buffer, 0, read)
        }
        val bytes = kept.toByteArray()
        if (bytes.size > maxBodyBytes) bytes.copyOf(maxBodyBytes) to true else bytes to false
    }

    /** N-4: TLS first, since #85's rule would fold it into `UNREACHABLE`; a cancellation or an Error propagates. */
    private fun failedBy(e: Throwable, phase: FailurePhase): Exchange.Failed {
        if (e is CancellationException || e is Error) throw e
        if (generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is SSLException }) {
            return failed(SyncErrorKind.TLS_FAILED)
        }
        val classified = transportFailureOf(e, phase, networkPermissionGranted)
        if (classified !is TransportFailure) throw classified
        return failed(kindOf(classified.kind))
    }

    private companion object {
        const val STATES_PATH = "/api/states/"
        const val API_ROOT_PATH = "/api/"

        /** #105: the whole states list, read only on Choose entity and Refresh. */
        const val STATES_LIST_PATH = "/api/states"
        const val OK = 200
        const val CONNECT_MILLIS = 10_000
        const val IDLE_MILLIS = 15_000
        const val CALL_MILLIS = 30_000L
        const val MAX_BODY_BYTES = 64 * 1024

        /** #105 (owner ruling Q2): the list call's own cap; the poll keeps [MAX_BODY_BYTES]. */
        const val LIST_MAX_BODY_BYTES = 8 * 1024 * 1024
        const val BUFFER_BYTES = 8 * 1024
        const val MAX_CAUSE_DEPTH = 16

        fun failed(kind: SyncErrorKind, detail: String? = null) =
            Exchange.Failed(HaReadOutcome.NoDecision(kind, detail))

        fun refused(kind: SyncErrorKind): HaReadOutcome = HaReadOutcome.NoDecision(kind, null)

        fun kindOf(kind: TransportFailure.Kind): SyncErrorKind = when (kind) {
            TransportFailure.Kind.UNREACHABLE, TransportFailure.Kind.INTERRUPTED -> SyncErrorKind.UNREACHABLE
            TransportFailure.Kind.TIMED_OUT -> SyncErrorKind.TIMED_OUT
            TransportFailure.Kind.DENIED -> SyncErrorKind.DENIED
        }

        /** N-3: Test connection's own map; the status is `detail` only for `HTTP_ERROR`. */
        fun testOutcomeOf(answer: HaHttpAnswer): ConnectionTestOutcome {
            val status = answer.status
            if (status == OK) {
                val jsonObject =
                    !answer.truncated && isJsonMediaType(answer.contentType) && parseObject(answer.body) != null
                if (jsonObject) return ConnectionTestOutcome.Ok
                return ConnectionTestOutcome.Failed(SyncErrorKind.MALFORMED, null)
            }
            if (status == 401 || status == 403) return ConnectionTestOutcome.Failed(SyncErrorKind.AUTH_REFUSED, null)
            if (status in 300..399) return ConnectionTestOutcome.Failed(SyncErrorKind.REDIRECTED, null)
            return ConnectionTestOutcome.Failed(SyncErrorKind.HTTP_ERROR, status.toString())
        }

        /** Equal but for ASCII case: no locale, no Unicode case mapping (#85 review M1). */
        fun sameAsciiHost(a: String, b: String): Boolean {
            fun lower(c: Char) = if (c in 'A'..'Z') c + ('a' - 'A') else c
            return a.length == b.length && a.indices.all { lower(a[it]) == lower(b[it]) }
        }

        /** Idempotent and never throws: it runs on cancel and cleanup paths. */
        fun disconnectQuietly(connection: HttpURLConnection) {
            try {
                connection.disconnect()
            } catch (_: Exception) {
                // Already gone.
            }
        }
    }
}
