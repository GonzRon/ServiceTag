package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.fetch.DocumentTransport
import com.loosecannon.servicetag.core.fetch.TransportFailure
import com.loosecannon.servicetag.core.fetch.TransportResponse
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A [DocumentTransport] that never touches a network: each URL answers from its route, in memory. A GET of
 * a URL with no route fails the test (an [AssertionError]), so a hop the fetch should have refused shows.
 * [requests] records every GET in order; [served] every response handed out, so a test can prove each was
 * closed.
 */
class FakeDocumentTransport : DocumentTransport {
    val requests = mutableListOf<String>()
    val served = mutableListOf<Served>()
    private val routes = mutableMapOf<String, suspend () -> Served>()

    /** One answer and its body; [closes] counts `close()` calls on the response. */
    class Served(
        val status: Int,
        val location: String? = null,
        val contentType: String? = null,
        val contentLength: Long? = null,
        val body: FakeBody = FakeBody(ByteArray(0)),
    ) {
        @Volatile var closes = 0
        val closed get() = closes > 0

        fun response() = TransportResponse(status, location, contentType, contentLength, body) {
            closes++
            body.abort()
        }
    }

    fun serve(
        url: String,
        bytes: ByteArray,
        contentType: String? = null,
        status: Int = 200,
        contentLength: Long? = bytes.size.toLong(),
    ) = route(url) { Served(status, null, contentType, contentLength, FakeBody(bytes)) }

    fun redirect(from: String, to: String?, status: Int = 302) = route(from) { Served(status, location = to) }

    fun status(url: String, status: Int) = route(url) { Served(status) }

    fun fail(url: String, kind: TransportFailure.Kind) = route(url) { throw TransportFailure(kind) }

    fun route(url: String, respond: suspend () -> Served) {
        routes[url] = respond
    }

    override suspend fun get(url: String): TransportResponse {
        requests += url
        val respond = routes[url] ?: throw AssertionError("an unexpected GET")
        val answer = respond()
        served += answer
        return answer.response()
    }
}

/**
 * An in-memory body. It hands out at most [len] bytes per read, like a socket. Optionally it fails with
 * [failure] once [failAt] bytes have been read; stalls once [stallAt] bytes have been read, signalling
 * [stalled] and blocking until the response is closed (then failing as a closed socket does); or, when
 * [forbidden], fails the test on any read. The stall waits at most five seconds, so a broken fetch fails
 * the test instead of hanging it.
 */
class FakeBody(
    private val bytes: ByteArray,
    private val failAt: Int? = null,
    private val failure: IOException = TransportFailure(TransportFailure.Kind.INTERRUPTED),
    private val stallAt: Int? = null,
    private val forbidden: Boolean = false,
) : InputStream() {
    @Volatile var position = 0
    @Volatile var reads = 0
    @Volatile var largestRead = 0
    val stalled = CountDownLatch(1)
    private val aborted = CountDownLatch(1)

    fun abort() = aborted.countDown()

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (forbidden) throw AssertionError("the body was read")
        reads++
        largestRead = maxOf(largestRead, len)
        if (aborted.count == 0L) throw TransportFailure(TransportFailure.Kind.INTERRUPTED)
        if (failAt != null && position >= failAt) throw failure
        if (stallAt != null && position >= stallAt) {
            stalled.countDown()
            aborted.await(5, TimeUnit.SECONDS)
            throw TransportFailure(TransportFailure.Kind.INTERRUPTED)
        }
        if (position >= bytes.size) return -1
        val limit = listOfNotNull(bytes.size, failAt, stallAt).min()
        val n = minOf(len, limit - position)
        bytes.copyInto(b, off, position, position + n)
        position += n
        return n
    }
}
