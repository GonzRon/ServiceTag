package com.loosecannon.servicetag.testing

import com.loosecannon.servicetag.core.fetch.DocumentTransport
import com.loosecannon.servicetag.core.fetch.TransportFailure
import com.loosecannon.servicetag.core.fetch.TransportResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app side's [DocumentTransport] (#85 C19), behind `FakeGraph.materializeReference`, so a view-model test never
 * reaches a network. Each URL answers what [serve] or [fail] gave it; a URL with none is `UNREACHABLE`, as a host
 * that cannot be reached is. [requests] records every GET in order and [closes] every response closed, so a test can
 * prove the fetch let go of each one.
 */
class FakeDocumentTransport : DocumentTransport {
    val requests: MutableList<String> = CopyOnWriteArrayList()
    val closes = AtomicInteger()
    private val routes = ConcurrentHashMap<String, () -> TransportResponse>()

    /** [url] answers [status] with [bytes], declared as [contentType] and (unless null) [contentLength]. */
    fun serve(
        url: String,
        bytes: ByteArray,
        contentType: String? = null,
        status: Int = 200,
        contentLength: Long? = bytes.size.toLong(),
    ) {
        routes[url] = {
            TransportResponse(status, null, contentType, contentLength, ByteArrayInputStream(bytes)) {
                closes.incrementAndGet()
            }
        }
    }

    /** [url] fails as [kind] does. */
    fun fail(url: String, kind: TransportFailure.Kind) {
        routes[url] = { throw TransportFailure(kind) }
    }

    override suspend fun get(url: String): TransportResponse {
        requests += url
        val answer = routes[url] ?: throw TransportFailure(TransportFailure.Kind.UNREACHABLE)
        return answer()
    }
}
