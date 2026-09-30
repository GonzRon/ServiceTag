package com.loosecannon.servicetag.fetch

import com.loosecannon.servicetag.core.fetch.DocumentTransport
import com.loosecannon.servicetag.core.fetch.TransportFailure
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Row 24 (#85 C16; R85-7, R85-13, R85-15): the transport against a JDK `HttpServer` on the loopback address, over
 * plain http — the https rule is the core policy's, so no TLS is needed here — and, where the server must watch the
 * socket itself, a hand-rolled loopback [RawServer]. Nothing leaves the machine and no name is looked up.
 */
class UrlConnectionTransportTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val handlers = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress(loopback, 0), 0).apply {
        executor = handlers
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    /** Lets a handler that stalls on purpose finish once the test is done. */
    private val release = CountDownLatch(1)
    private val transport = UrlConnectionTransport(networkPermissionGranted = { true })

    @After
    fun stop() {
        release.countDown()
        server.stop(0)
        handlers.shutdownNow()
    }

    private fun respond(path: String, handler: (HttpExchange) -> Unit) {
        server.createContext(path) { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
    }

    private suspend fun failureOf(get: suspend () -> Unit): TransportFailure =
        runCatching { get() }.exceptionOrNull() as? TransportFailure ?: throw AssertionError("no TransportFailure")

    @Test
    fun answersTheStatusLocationTypeAndLength() = runBlocking<Unit> {
        val bytes = "%PDF-1.7 Example Pool Pump manual".toByteArray()
        respond("/manual.pdf") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/pdf")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }
        respond("/streamed") { exchange ->
            exchange.sendResponseHeaders(203, 0) // chunked: no declared length
            exchange.responseBody.write(bytes)
        }

        transport.get("$base/manual.pdf").use { response ->
            assertEquals(200, response.status)
            assertNull(response.location)
            assertEquals("application/pdf", response.contentType)
            assertEquals(bytes.size.toLong(), response.contentLength)
            assertArrayEquals(bytes, response.body.readBytes())
        }
        transport.get("$base/streamed").use { response ->
            assertEquals(203, response.status)
            assertNull("-1 is no length", response.contentLength)
            assertArrayEquals(bytes, response.body.readBytes())
        }
    }

    @Test
    fun aRedirectIsNotFollowed() = runBlocking<Unit> {
        val followed = AtomicInteger()
        respond("/moved") { exchange ->
            exchange.responseHeaders.add("Location", "/manual.pdf")
            exchange.sendResponseHeaders(302, -1)
        }
        respond("/manual.pdf") { exchange ->
            followed.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
        }

        transport.get("$base/moved").use { response ->
            assertEquals(302, response.status)
            assertEquals("/manual.pdf", response.location)
            assertEquals(-1, response.body.read())
        }
        assertEquals("the core follows a redirect, never the adapter", 0, followed.get())
    }

    @Test
    fun sendsExactlyAcceptIdentityEncodingAndTheFixedUserAgent() = runBlocking<Unit> {
        val seen = Collections.synchronizedMap(mutableMapOf<String, List<String>>())
        respond("/manual.pdf") { exchange ->
            exchange.requestHeaders.forEach { (name, values) -> seen[name.lowercase()] = values.toList() }
            exchange.sendResponseHeaders(200, -1)
        }

        transport.get("$base/manual.pdf").close()

        assertEquals(listOf("application/pdf, image/png, image/jpeg;q=0.9, */*;q=0.1"), seen["accept"])
        assertEquals(listOf("identity"), seen["accept-encoding"])
        assertEquals("ServiceTag", DocumentTransport.USER_AGENT)
        assertEquals("R85-13: no platform, version, model or build", listOf("ServiceTag"), seen["user-agent"])
        // The three, plus only what the platform's HTTP/1.1 adds by itself — the host, the connection and, for
        // `useCaches = false`, its no-cache directives: no cookie, no credential, nothing that identifies the phone.
        val platform = setOf("host", "connection", "pragma", "cache-control")
        assertEquals(setOf("accept", "accept-encoding", "user-agent"), seen.keys - platform)
        listOf("pragma", "cache-control").forEach { name -> seen[name]?.let { assertEquals(listOf("no-cache"), it) } }
    }

    @Test
    fun aNon2xxBodyIsEmpty() = runBlocking<Unit> {
        val page = "<html>Sign in to Example Files</html>".toByteArray()
        for (code in listOf(401, 404, 500)) {
            respond("/status$code") { exchange ->
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.sendResponseHeaders(code, page.size.toLong())
                exchange.responseBody.write(page)
            }
            transport.get("$base/status$code").use { response ->
                assertEquals(code, response.status)
                assertEquals("the error page is never read", -1, response.body.read())
            }
        }
    }

    @Test
    fun aReadTimeoutIsTimedOut() = runBlocking<Unit> {
        val quick = UrlConnectionTransport(networkPermissionGranted = { true }, readTimeoutMillis = 200)
        respond("/stalls") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write(byteArrayOf(0x25, 0x50, 0x44, 0x46))
            exchange.responseBody.flush()
            release.await(5, TimeUnit.SECONDS)
        }
        respond("/silent") { release.await(5, TimeUnit.SECONDS) }

        quick.get("$base/stalls").use { response ->
            val inBody = failureOf { response.body.readBytes() }
            assertEquals(TransportFailure.Kind.TIMED_OUT, inBody.kind)
        }
        assertEquals(TransportFailure.Kind.TIMED_OUT, failureOf { quick.get("$base/silent") }.kind)
    }

    @Test
    fun closeDisconnects() = runBlocking<Unit> {
        RawServer("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\nConnection: close\r\n\r\n%PDF-").use { raw ->
            val response = transport.get(raw.url)
            assertEquals(200, response.status)

            response.close()

            assertTrue("the server sees the socket close", raw.sawEof.await(1, TimeUnit.SECONDS))
            response.close() // idempotent, and it never throws
        }
    }

    @Test
    fun cancellingAPendingGetDisconnects() = runBlocking<Unit> {
        val bounded = UrlConnectionTransport(networkPermissionGranted = { true }, readTimeoutMillis = 5_000)
        RawServer(response = null).use { raw ->
            val pending = async(Dispatchers.IO) { bounded.get(raw.url) }
            assertTrue(raw.gotRequest.await(5, TimeUnit.SECONDS))

            pending.cancel()

            withTimeout(1_000) { pending.join() } // get returns within 1 s of real time, not at the idle deadline
            assertTrue(pending.isCancelled)
            assertTrue("the server sees EOF", raw.sawEof.await(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun aHostThePlatformReadsDifferentlyIsRefused() = runBlocking<Unit> {
        val opened = AtomicInteger()
        val watched = UrlConnectionTransport(networkPermissionGranted = { true }, open = { url ->
            opened.incrementAndGet()
            url.openConnection()
        })
        // The core reads the last `@` as the end of the userinfo and checks `example.invalid`; the platform's URL
        // parser reads two `@`s as no server at all — an empty host, which it would connect as this machine.
        val failure = failureOf { watched.get("http://a@b@example.invalid:${server.address.port}/m.pdf") }

        assertEquals(TransportFailure.Kind.UNREACHABLE, failure.kind)
        assertNull("no platform message, which could carry the URL", failure.message)
        assertEquals("refused before anything is opened", 0, opened.get())
    }

    /**
     * Review m3's case, the JDK's shape during a TCP connect: `disconnect()` cannot stop the head read, so the
     * response still arrives after the cancel. A stub connection (through the `open` seam; no socket) holds its head
     * until the test has cancelled, then answers 200. The late response must be closed, and never returned.
     */
    @Test
    fun aResponseThatArrivesAfterTheCancelIsClosed() = runBlocking<Unit> {
        val headRead = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val answered = AtomicInteger()
        val disconnects = AtomicInteger()
        val closesAfterTheAnswer = AtomicInteger()
        val late = UrlConnectionTransport(networkPermissionGranted = { true }, open = { url ->
            object : HttpURLConnection(url) {
                override fun getResponseCode(): Int {
                    headRead.countDown()
                    check(answer.await(5, TimeUnit.SECONDS))
                    answered.set(1)
                    return 200
                }

                override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
                override fun connect() = Unit
                override fun usingProxy() = false
                override fun disconnect() {
                    disconnects.incrementAndGet() // a no-op, as the JDK's is before a connection exists
                    if (answered.get() == 1) closesAfterTheAnswer.incrementAndGet()
                }
            }
        })
        val pending = async(Dispatchers.IO) { late.get("http://127.0.0.1:1/manual.pdf") }
        assertTrue(headRead.await(5, TimeUnit.SECONDS))

        pending.cancel()
        assertEquals("the cancel disconnects at once", 1, disconnects.get())
        answer.countDown()
        withTimeout(1_000) { pending.join() }

        assertTrue("get completes as cancelled, never with the response", pending.isCancelled)
        assertEquals("the late response is closed", 1, closesAfterTheAnswer.get())
    }
}

/**
 * One loopback connection, by hand, so the test sees the socket itself: it reads the request head, writes [response]
 * (or nothing, a server that never answers), then waits for the client to close ([sawEof]).
 */
private class RawServer(private val response: String?) : AutoCloseable {
    private val socket = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val url = "http://127.0.0.1:${socket.localPort}/manual.pdf"
    val gotRequest = CountDownLatch(1)
    val sawEof = CountDownLatch(1)

    init {
        thread(isDaemon = true) {
            runCatching {
                socket.accept().use { peer ->
                    peer.soTimeout = 5_000
                    val input = peer.getInputStream()
                    readHead(input)
                    gotRequest.countDown()
                    response?.let { peer.getOutputStream().apply { write(it.toByteArray()); flush() } }
                    while (input.read() != -1) continue
                    sawEof.countDown()
                }
            }
        }
    }

    private fun readHead(input: InputStream) {
        var tail = 0
        while (tail != 0x0D0A0D0A) {
            val b = input.read()
            if (b < 0) return
            tail = (tail shl 8) or b
        }
    }

    override fun close() = socket.close()
}
