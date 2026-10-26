package com.loosecannon.servicetag.seasonsync

import com.loosecannon.servicetag.core.fetch.HostResolver
import com.loosecannon.servicetag.core.fetch.TransportFailure
import com.loosecannon.servicetag.core.seasonsync.EndpointCheck
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaEndpointPolicy
import com.loosecannon.servicetag.core.seasonsync.HaHostKind
import com.loosecannon.servicetag.core.seasonsync.HaScheme
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.core.seasonsync.eligibleNow
import com.loosecannon.servicetag.core.seasonsync.isPrivateLanAddress
import java.net.URL

/**
 * #113/#114 (C21) — the checks #16's client made before any request (C19 steps 1–2), moved here unchanged so that the
 * state client and the publish client make them alike. In order, for one request at [HaConnection.baseUrl] + `path`:
 * 1. the address rule again ([HaEndpointPolicy]; refused → `ENDPOINT_REFUSED`) and the network permission (not
 *    granted → `DENIED`, a quiet status); then, in the home-network mode **or for any `http` address whatever the
 *    stored setting** (fail closed, C-5), the current network must be the captured Wi-Fi ([eligibleNow]), else
 *    `NOT_ON_LOCAL_NETWORK` with the reader's [UnconfirmedCause] as `detail` (null: another network); and in that mode
 *    an https name must resolve only to private addresses, else `NAME_NOT_LOCAL` (`UNREACHABLE` or `TIMED_OUT` when it
 *    does not resolve);
 * 2. the platform's parsed host must equal the policy's, ASCII case-insensitively, else `ENDPOINT_REFUSED`.
 *
 * It opens nothing and **carries no request timeout** (F-7): each client bounds gate and request together with its own
 * whole-call limit. Neither answer carries the URL, host, Wi-Fi name or token, and nothing is logged.
 */
internal class HaRequestGate(
    private val networkPermissionGranted: () -> Boolean,
    private val currentNetwork: CurrentNetworkReader,
    private val resolver: HostResolver,
    private val parse: (String) -> URL,
) {

    /** The gate's answer: the one URL a client may open, or why none. */
    sealed interface Answer {
        /** The checked URL. Its `toString` names no address. */
        class Allowed(val url: URL) : Answer {
            override fun toString(): String = "Allowed" // l10n-ok: debug text, never shown
        }

        /** Nothing may be opened: [kind], and #16's cause as [detail] for `NOT_ON_LOCAL_NETWORK`. */
        data class Refused(val kind: SyncErrorKind, val detail: String?) : Answer
    }

    suspend fun check(connection: HaConnection, path: String): Answer {
        val endpoint = when (val check = HaEndpointPolicy.classify(connection.baseUrl)) {
            is EndpointCheck.Allowed -> check.endpoint
            is EndpointCheck.Refused -> return refused(SyncErrorKind.ENDPOINT_REFUSED)
        }
        if (!networkPermissionGranted()) return refused(SyncErrorKind.DENIED)
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
                return refused(SyncErrorKind.NOT_ON_LOCAL_NETWORK, reading.cause?.name)
            }
            when (endpoint.hostKind) {
                HaHostKind.NAME -> resolvesOnlyPrivately(endpoint.host)?.let { return it }
                HaHostKind.PRIVATE_IPV4 -> Unit
            }
        }
        val url = try {
            parse(endpoint.canonical + path)
        } catch (_: Exception) {
            return refused(SyncErrorKind.ENDPOINT_REFUSED)
        }
        if (!sameAsciiHost(url.host.orEmpty(), endpoint.host)) return refused(SyncErrorKind.ENDPOINT_REFUSED)
        return Answer.Allowed(url)
    }

    /** C19 step 1b: null when every answer is private (C8 rule 5); else the refusal, nothing opened. */
    private suspend fun resolvesOnlyPrivately(host: String): Answer.Refused? {
        val addresses = try {
            resolver.resolve(host)
        } catch (e: TransportFailure) {
            return refused(syncKindOf(e.kind))
        }
        val local = addresses.isNotEmpty() && addresses.all(::isPrivateLanAddress)
        return if (local) null else refused(SyncErrorKind.NAME_NOT_LOCAL)
    }

    private companion object {
        fun refused(kind: SyncErrorKind, detail: String? = null) = Answer.Refused(kind, detail)

        /** Equal but for ASCII case: no locale, no Unicode case mapping (#85 review M1). */
        fun sameAsciiHost(a: String, b: String): Boolean {
            fun lower(c: Char) = if (c in 'A'..'Z') c + ('a' - 'A') else c
            return a.length == b.length && a.indices.all { lower(a[it]) == lower(b[it]) }
        }
    }
}

/** #85's transport kinds under #16's names; shared by the gate and both clients. */
internal fun syncKindOf(kind: TransportFailure.Kind): SyncErrorKind = when (kind) {
    TransportFailure.Kind.UNREACHABLE, TransportFailure.Kind.INTERRUPTED -> SyncErrorKind.UNREACHABLE
    TransportFailure.Kind.TIMED_OUT -> SyncErrorKind.TIMED_OUT
    TransportFailure.Kind.DENIED -> SyncErrorKind.DENIED
}
