package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.core.fetch.DocumentTransport
import com.loosecannon.servicetag.core.fetch.HostResolver
import com.loosecannon.servicetag.core.fetch.TransportFailure
import com.loosecannon.servicetag.core.hapublish.HaServiceAction
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.RemoteRead
import com.loosecannon.servicetag.core.hapublish.STATUS_OPTIONS
import com.loosecannon.servicetag.core.hapublish.mapRemoteEntity
import com.loosecannon.servicetag.core.hapublish.publishKindOf
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaEndpointPolicy
import com.loosecannon.servicetag.core.seasonsync.HaHttpAnswer
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.isValidEntityId
import com.loosecannon.servicetag.fetch.FailurePhase
import com.loosecannon.servicetag.fetch.transportFailureOf
import com.loosecannon.servicetag.seasonsync.CurrentNetworkReader
import com.loosecannon.servicetag.seasonsync.HaRequestGate
import com.loosecannon.servicetag.seasonsync.syncKindOf
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * #113/#114 (C22, C23) — what one service call came to. [Accepted] is HA's 200: the call was taken, never "verified"
 * (only a read-back verifies). [Refused] is known not to have acted: it never reached HA, or HA refused it explicitly
 * before acting. [Unknown] may have reached HA and may have acted, so the next attempt reads back before it sends.
 * No member carries a URL, host, entity, body or token; a [Refused.detail] is an HTTP status, the refused value of a
 * 400 or #16's network cause, and an [Unknown.detail] an HTTP status.
 */
sealed interface CallOutcome {
    data object Accepted : CallOutcome

    data class Refused(val kind: PublishErrorKind, val detail: String?) : CallOutcome

    data class Unknown(val detail: String?) : CallOutcome
}

/**
 * #113/#114 (C22, C23) — the outbound Home Assistant client: one GET of a helper's state, or one POST of one of the
 * two allowlisted service actions ([HaServiceAction]; the path and the body's keys come from the enum alone), at the
 * stored connection's origin and nowhere else. Per call, on its own connection:
 * 1. the connection must be the stored one (its id and address), else `ENDPOINT_REFUSED` with nothing opened (#16's
 *    port rule, I10); the stored row's network setting then governs, and a caller's older copy cannot widen it;
 * 2. [HaRequestGate], #16's checks (C21); nothing is opened before it allows;
 * 3. redirects not followed, nothing cached, no proxy; the bearer, JSON, identity encoding, the fixed user agent and,
 *    for the POST alone, the JSON content type with a fixed-length body; no cookie;
 * 4. connect [CONNECT_MILLIS], idle [IDLE_MILLIS], the whole call — gate and request — [CALL_MILLIS]; at most
 *    [MAX_BODY_BYTES] of a 200's body are read (a POST's are discarded); a cancel disconnects and propagates.
 *
 * A POST is split at the start of transmission, the moment the output stream is handed over (C23, A10): a failure
 * before it is `Refused` (TLS first, then #85's classification); from it on — writing, closing, waiting for the status
 * line, reading the body, a timeout or the whole-call bound — it is `Unknown`. A status line maps by C23's table; every
 * 5xx is `Unknown`. Every outcome is a value, never a throw; nothing is logged. The token is a parameter only, held for
 * one request.
 */
class HomeAssistantPublishClient(
    /** The stored connection, read at each call: the one origin the token may go to, and whose setting governs. */
    private val settings: suspend () -> HaConnection?,
    private val networkPermissionGranted: () -> Boolean,
    currentNetwork: CurrentNetworkReader,
    resolver: HostResolver,
    private val io: CoroutineContext = Dispatchers.IO,
    parse: (String) -> URL = ::URL,
    /** Direct, never through a system proxy: the bearer goes to the configured origin and nowhere else. */
    private val open: (URL) -> URLConnection = { it.openConnection(Proxy.NO_PROXY) },
) {
    private val gate = HaRequestGate(networkPermissionGranted, currentNetwork, resolver, parse)

    /**
     * One `GET <base>/api/states/<entityId>`, through C16's mapper. An [entityId] outside #16's shape is `MALFORMED`
     * before anything is opened, so the path is that one helper's and no other endpoint's.
     */
    suspend fun read(connection: HaConnection, entityId: String, token: Secret): RemoteRead {
        if (!isValidEntityId(entityId)) return RemoteRead.Failed(PublishErrorKind.MALFORMED, null)
        val stored = storedAs(connection) ?: return RemoteRead.Failed(PublishErrorKind.ENDPOINT_REFUSED, null)
        return withTimeoutOrNull(CALL_MILLIS) {
            when (val answer = gate.check(stored, STATES_PATH + entityId)) {
                is HaRequestGate.Answer.Allowed -> withContext(io) { get(answer.url, token, entityId) }
                is HaRequestGate.Answer.Refused -> RemoteRead.Failed(publishKindOf(answer.kind), answer.detail)
            }
        } ?: RemoteRead.Failed(PublishErrorKind.TIMED_OUT, null)
    }

    /**
     * One `POST <base>/api/services/<domain>/<service>` of [action], with the body `{"entity_id": entityId,
     * <action's key>: value}`. The caller has validated both (C16): [entityId] is a helper of [action]'s domain and
     * [value] one of the three options or a `YYYY-MM-DD` day; anything else is a caller's bug, thrown before anything
     * is opened, and the message names neither.
     */
    suspend fun call(
        connection: HaConnection,
        action: HaServiceAction,
        entityId: String,
        value: String,
        token: Secret,
    ): CallOutcome {
        require(admissible(action, entityId, value)) { "a service call outside its action's shape" }
        val body = bodyOf(action, entityId, value)
        val stored = storedAs(connection) ?: return CallOutcome.Refused(PublishErrorKind.ENDPOINT_REFUSED, null)
        val transmission = AtomicBoolean(false)
        return withTimeoutOrNull(CALL_MILLIS) {
            when (val answer = gate.check(stored, SERVICES_PATH + action.domain + "/" + action.service)) {
                is HaRequestGate.Answer.Allowed ->
                    withContext(io) { post(answer.url, token, body, value, transmission) }
                is HaRequestGate.Answer.Refused -> CallOutcome.Refused(publishKindOf(answer.kind), answer.detail)
            }
        } ?: if (transmission.get()) {
            CallOutcome.Unknown(null)
        } else {
            CallOutcome.Refused(PublishErrorKind.TIMED_OUT, null)
        }
    }

    /** The stored connection when [connection] is it — the same id at the same address — else null. */
    private suspend fun storedAs(connection: HaConnection): HaConnection? {
        val stored = settings() ?: return null
        val canonical = HaEndpointPolicy.canonical(connection.baseUrl) ?: return null
        val same = stored.id == connection.id && HaEndpointPolicy.canonical(stored.baseUrl) == canonical
        return if (same) stored else null
    }

    private suspend fun get(url: URL, token: Secret, entityId: String): RemoteRead {
        val connection = try {
            getConnectionFor(url, token)
        } catch (e: Throwable) {
            return RemoteRead.Failed(kindBefore(e, FailurePhase.CONNECT), null)
        }
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { disconnectQuietly(connection) }
            if (!cont.isActive) return@suspendCancellableCoroutine
            val read = try {
                readOf(connection, entityId)
            } catch (e: Throwable) {
                RemoteRead.Failed(kindBefore(e, FailurePhase.CONNECT), null)
            } finally {
                disconnectQuietly(connection)
            }
            cont.resume(read)
        }
    }

    /** Blocks for the head, then reads a 200's body under the cap; any other status answers no body. */
    private fun readOf(connection: HttpURLConnection, entityId: String): RemoteRead {
        val status = connection.responseCode
        if (status < 0) return RemoteRead.Failed(PublishErrorKind.UNREACHABLE, null)
        if (status != OK) {
            return mapRemoteEntity(HaHttpAnswer(status, connection.contentType, ByteArray(0), false), entityId)
        }
        val contentType = connection.contentType
        val (body, truncated) = try {
            capped(connection.inputStream)
        } catch (e: Throwable) {
            return RemoteRead.Failed(kindBefore(e, FailurePhase.BODY), null)
        }
        return mapRemoteEntity(HaHttpAnswer(status, contentType, body, truncated), entityId)
    }

    private suspend fun post(
        url: URL,
        token: Secret,
        body: ByteArray,
        value: String,
        transmission: AtomicBoolean,
    ): CallOutcome {
        val connection = try {
            postConnectionFor(url, token, body.size)
        } catch (e: Throwable) {
            return refusedBy(e)
        }
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { disconnectQuietly(connection) }
            if (!cont.isActive) return@suspendCancellableCoroutine
            val outcome = try {
                exchangeOf(connection, body, value, transmission)
            } catch (e: Throwable) {
                if (transmission.get()) unknownBy(e) else refusedBy(e)
            } finally {
                disconnectQuietly(connection)
            }
            cont.resume(outcome)
        }
    }

    /**
     * C23: the output stream connects (name, socket, TLS) and sends the head; once it is handed over, HA may have the
     * request, so [transmission] is set before a byte of the body is written. The head alone cannot act: a fixed-length
     * body is not complete until written, which is why a timeout before [transmission] is set reads `Refused(TIMED_OUT)`.
     */
    private fun exchangeOf(
        connection: HttpURLConnection,
        body: ByteArray,
        value: String,
        transmission: AtomicBoolean,
    ): CallOutcome {
        val output = connection.outputStream
        transmission.set(true)
        output.use { it.write(body) }
        val status = connection.responseCode
        if (status == OK) {
            discardCapped(connection.inputStream)
            return CallOutcome.Accepted
        }
        return outcomeOf(status, value)
    }

    private fun getConnectionFor(url: URL, token: Secret): HttpURLConnection {
        val connection = opened(url)
        connection.requestMethod = "GET"
        return headed(connection, token, contentType = null)
    }

    private fun postConnectionFor(url: URL, token: Secret, bodySize: Int): HttpURLConnection {
        val connection = opened(url)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(bodySize)
        return headed(connection, token, contentType = JSON)
    }

    private fun opened(url: URL): HttpURLConnection =
        open(url) as? HttpURLConnection ?: throw TransportFailure(TransportFailure.Kind.UNREACHABLE)

    /** The request properties, exactly and in this order: four for a GET, five for a POST. */
    private fun headed(connection: HttpURLConnection, token: Secret, contentType: String?): HttpURLConnection {
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = CONNECT_MILLIS
        connection.readTimeout = IDLE_MILLIS
        connection.setRequestProperty("Authorization", "Bearer " + token.value) // l10n-ok: HTTP header
        connection.setRequestProperty("Accept", JSON) // l10n-ok: HTTP header
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (contentType != null) connection.setRequestProperty("Content-Type", contentType) // l10n-ok: HTTP header
        connection.setRequestProperty("User-Agent", DocumentTransport.USER_AGENT)
        return connection
    }

    /** Before transmission: TLS first, since #85's rule would fold it into `UNREACHABLE`; then #85's classification. */
    private fun refusedBy(e: Throwable): CallOutcome = CallOutcome.Refused(kindBefore(e, FailurePhase.CONNECT), null)

    /** From transmission on, whatever failed: HA may have acted (A10). A cancellation or an Error propagates. */
    private fun unknownBy(e: Throwable): CallOutcome {
        if (e is CancellationException || e is Error) throw e
        return CallOutcome.Unknown(null)
    }

    private fun kindBefore(e: Throwable, phase: FailurePhase): PublishErrorKind {
        if (e is CancellationException || e is Error) throw e
        if (generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is SSLException }) {
            return PublishErrorKind.TLS_FAILED
        }
        val classified = transportFailureOf(e, phase, networkPermissionGranted)
        if (classified !is TransportFailure) throw classified
        return publishKindOf(syncKindOf(classified.kind))
    }

    private companion object {
        const val STATES_PATH = "/api/states/"
        const val SERVICES_PATH = "/api/services/"
        const val JSON = "application/json"
        const val OK = 200
        const val CONNECT_MILLIS = 10_000
        const val IDLE_MILLIS = 15_000
        const val CALL_MILLIS = 30_000L
        const val MAX_BODY_BYTES = 64 * 1024
        const val BUFFER_BYTES = 8 * 1024
        const val MAX_CAUSE_DEPTH = 16

        /** Two keys, in this order, encoded by kotlinx-serialization: never built by concatenation. */
        fun bodyOf(action: HaServiceAction, entityId: String, value: String): ByteArray {
            val call = buildJsonObject {
                put("entity_id", entityId)
                put(action.valueKey, value)
            }
            return Json.encodeToString(JsonObject.serializer(), call).toByteArray(Charsets.UTF_8)
        }

        /**
         * C23's table for every status but 200, which is answered before this: an explicit refusal HA gives before
         * acting stays `Refused`; every 5xx, and any other status a service call does not answer with, is `Unknown`.
         */
        fun outcomeOf(status: Int, value: String): CallOutcome {
            if (status in 300..399) return CallOutcome.Refused(PublishErrorKind.REDIRECTED, null)
            if (status == 400) return CallOutcome.Refused(PublishErrorKind.SERVICE_REFUSED, value)
            if (status == 401) return CallOutcome.Refused(PublishErrorKind.AUTH_REFUSED, null)
            if (status == 403) return CallOutcome.Refused(PublishErrorKind.WRITE_FORBIDDEN, null)
            if (status in 400..499) return CallOutcome.Refused(PublishErrorKind.HTTP_ERROR, status.toString())
            return CallOutcome.Unknown(if (status < 0) null else status.toString())
        }

        /** At most [MAX_BODY_BYTES], and whether more followed; never a byte past the cap is read. */
        fun capped(input: InputStream): Pair<ByteArray, Boolean> = input.use {
            val kept = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            while (kept.size() <= MAX_BODY_BYTES) {
                val read = it.read(buffer, 0, minOf(buffer.size, MAX_BODY_BYTES + 1 - kept.size()))
                if (read < 0) break
                kept.write(buffer, 0, read)
            }
            val bytes = kept.toByteArray()
            if (bytes.size > MAX_BODY_BYTES) bytes.copyOf(MAX_BODY_BYTES) to true else bytes to false
        }

        /** A 200's body is read up to [MAX_BODY_BYTES] and dropped; the rest is left to the disconnect. */
        fun discardCapped(input: InputStream) = input.use {
            val buffer = ByteArray(BUFFER_BYTES)
            var left = MAX_BODY_BYTES
            while (left > 0) {
                val read = it.read(buffer, 0, minOf(buffer.size, left))
                if (read < 0) break
                left -= read
            }
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

private val ISO_DAY = Regex("^\\d{4}-\\d{2}-\\d{2}$")

/**
 * C16's shapes at the wire, the one rule: a helper of the action's own domain and a value it takes. The client
 * refuses a call outside it as a bug; the runner checks it first and records `MALFORMED` without sending (C30).
 */
internal fun admissible(action: HaServiceAction, entityId: String, value: String): Boolean {
    if (!isValidEntityId(entityId) || entityId.substringBefore('.') != action.domain) return false
    return when (action) {
        HaServiceAction.SELECT_OPTION -> value in STATUS_OPTIONS
        HaServiceAction.SET_DATE -> ISO_DAY.matches(value)
    }
}
