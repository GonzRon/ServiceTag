package com.loosecannon.servicetag.fetch

import com.loosecannon.servicetag.core.fetch.HostResolver
import com.loosecannon.servicetag.core.fetch.TransportFailure
import java.net.InetAddress
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #85 C18 (R85-7): the host rule's resolver, the platform's own lookup on [io]. It answers **every** address the
 * name resolves to, so the core can refuse a name when any one of them is local. An unknown host or an empty answer
 * is `UNREACHABLE`, and every other failure is classified by [transportFailureOf] (a denial only when the permission
 * reads as not granted): the core's hop rule catches nothing but a [TransportFailure]. The host arrives as the core
 * asks it — ASCII-lowercased, an IPv6 literal unbracketed, a trailing dot kept — and is looked up exactly so.
 */
class InetHostResolver(
    private val networkPermissionGranted: () -> Boolean,
    private val io: CoroutineContext = Dispatchers.IO,
    private val lookup: (String) -> Array<InetAddress> = InetAddress::getAllByName,
) : HostResolver {
    override suspend fun resolve(host: String): List<ByteArray> = withContext(io) {
        val answer = try {
            lookup(host)
        } catch (e: Throwable) {
            throw transportFailureOf(e, FailurePhase.CONNECT, networkPermissionGranted)
        }
        if (answer.isEmpty()) throw TransportFailure(TransportFailure.Kind.UNREACHABLE)
        answer.map { it.address }
    }
}
