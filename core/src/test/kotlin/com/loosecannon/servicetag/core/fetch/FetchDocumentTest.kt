package com.loosecannon.servicetag.core.fetch

import com.loosecannon.servicetag.core.fetch.FetchOutcome.Fetched
import com.loosecannon.servicetag.core.fetch.FetchOutcome.Refused
import com.loosecannon.servicetag.core.fetch.FetchProblem.Empty
import com.loosecannon.servicetag.core.fetch.FetchProblem.HasCredentials
import com.loosecannon.servicetag.core.fetch.FetchProblem.Interrupted
import com.loosecannon.servicetag.core.fetch.FetchProblem.LocalAddress
import com.loosecannon.servicetag.core.fetch.FetchProblem.NeedsSignIn
import com.loosecannon.servicetag.core.fetch.FetchProblem.NetworkDenied
import com.loosecannon.servicetag.core.fetch.FetchProblem.NotADocument
import com.loosecannon.servicetag.core.fetch.FetchProblem.NotHttps
import com.loosecannon.servicetag.core.fetch.FetchProblem.RedirectRefused
import com.loosecannon.servicetag.core.fetch.FetchProblem.ServerError
import com.loosecannon.servicetag.core.fetch.FetchProblem.TimedOut
import com.loosecannon.servicetag.core.fetch.FetchProblem.TooLarge
import com.loosecannon.servicetag.core.fetch.FetchProblem.Unreachable
import com.loosecannon.servicetag.core.model.MAX_ATTACHMENT_BYTES
import com.loosecannon.servicetag.core.testing.FakeBody
import com.loosecannon.servicetag.core.testing.FakeDocumentTransport
import com.loosecannon.servicetag.core.testing.FakeDocumentTransport.Served
import com.loosecannon.servicetag.core.testing.FakeHostResolver
import com.loosecannon.servicetag.core.testing.FakeStaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Rows 13–16 and 17b (#85 C10; R85-4, R85-5, R85-7, R85-8): the fetch over in-memory fakes. Nothing here
 * touches a network. Under `runTest` the fetch's `io` is the test's own dispatcher (review m1); the two
 * blocking cases run under `runBlocking` on a real `Dispatchers.IO` with a wall-clock bound.
 */
class FetchDocumentTest {

    // ---- fixtures: fictional bytes, example.invalid names, documentation addresses ----

    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun filler(n: Int) = ByteArray(n) { 'x'.code.toByte() }

    private val pdf = ascii("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n")

    /** A fictional login page: what a document link often answers once its session is gone. */
    private val loginPage = ascii(
        "<!doctype html><html><head><title>Sign in</title></head><body><form action=\"/login\">" +
            "Example Manuals: sign in to download this file</form></body></html>\n",
    )

    /** HTML that begins with a PDF header and never ends like one (R85-5's required fixture). */
    private val fakeHeaderHtml = ascii("%PDF-1.7\n<!doctype html><html><body>Please sign in to continue</body></html>\n")

    private val iend = bytes(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82)

    private val pngHead = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        bytes(0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52, 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0, 0x90, 0x77, 0x53, 0xDE)

    private val jpegHead = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 16, 0x4A, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0)

    /** [head], filler, then [marker] starting exactly at byte [markerAt], then [after]. */
    private fun withMarkerAt(head: ByteArray, marker: ByteArray, markerAt: Int, after: ByteArray = ByteArray(0)) =
        head + filler(markerAt - head.size) + marker + after

    /** A PDF of exactly [size] bytes. */
    private fun pdfOf(size: Int) = withMarkerAt(ascii("%PDF-1.5\n"), ascii("%%EOF\n"), size - 6)

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // ---- the rig ----

    private val publicHosts = listOf(
        "manuals.example.invalid", "cdn.example.invalid", "files.example.invalid", "mirror.example.invalid",
    )

    /** Every fixture host answers the public stand-in `203.0.113.10`, unless [extra] says otherwise. */
    private fun resolver(vararg extra: Pair<String, List<String>>) =
        FakeHostResolver.of(*(publicHosts.map { it to listOf("203.0.113.10") } + extra).toTypedArray())

    private fun TestScope.fetcher(
        transport: FakeDocumentTransport,
        resolver: FakeHostResolver = resolver(),
        staging: FakeStaging = FakeStaging(),
        limits: FetchLimits = FetchLimits(),
    ) = FetchDocument(transport, HopPolicy(resolver), staging, limits, StandardTestDispatcher(testScheduler))

    private val doc = "https://manuals.example.invalid/pool-pump/manual.pdf"

    private fun transport(block: FakeDocumentTransport.() -> Unit) = FakeDocumentTransport().apply(block)

    // ---- row 13: redirects ----

    @Test
    fun fiveRedirectsAreFollowedAndTheSixthIsRefused() = runTest {
        val hops = (0..6).map { "https://manuals.example.invalid/hop$it.pdf" }
        val five = transport {
            listOf(301, 302, 303, 307, 308).forEachIndexed { i, status -> redirect(hops[i], hops[i + 1], status) }
            serve(hops[5], pdf)
        }
        assertEquals(hops[5], assertIs<Fetched>(fetcher(five).run(hops[0])).finalUrl)
        assertEquals(hops.take(6), five.requests)

        val six = transport {
            (0 until 6).forEach { redirect(hops[it], hops[it + 1]) }
            serve(hops[6], pdf)
        }
        val staging = FakeStaging()
        assertEquals(Refused(RedirectRefused), fetcher(six, staging = staging).run(hops[0]))
        assertEquals(hops.take(6), six.requests, "the sixth redirect's target is never fetched")
        assertTrue(six.served.all { it.closed })
        assertTrue(staging.files.isEmpty())
    }

    @Test
    fun anHttpsToHttpHopIsRefusedBeforeItsGet() = runTest {
        val cleartext = "http://manuals.example.invalid/pool-pump/manual.pdf"
        val t = transport {
            redirect(doc, cleartext)
            serve(cleartext, pdf)
        }
        assertEquals(Refused(RedirectRefused), fetcher(t).run(doc))
        assertEquals(listOf(doc), t.requests)
        assertTrue(t.served.single().closed)
    }

    @Test
    fun aRelativeLocationIsResolved() = runTest {
        val cases = listOf(
            Triple("https://manuals.example.invalid/docs/pumps/a.pdf", "../b.pdf", "https://manuals.example.invalid/docs/b.pdf"),
            Triple("https://manuals.example.invalid/docs/pumps/a.pdf", "/abs/c.pdf", "https://manuals.example.invalid/abs/c.pdf"),
            Triple("https://manuals.example.invalid/docs/a.pdf", "//cdn.example.invalid/d.pdf", "https://cdn.example.invalid/d.pdf"),
            Triple("https://manuals.example.invalid/docs/a.pdf", "e.pdf", "https://manuals.example.invalid/docs/e.pdf"),
            // review m4: an empty base path is "/" first, never https://manuals.example.invalidx.pdf
            Triple("https://manuals.example.invalid", "x.pdf", "https://manuals.example.invalid/x.pdf"),
            Triple("https://manuals.example.invalid?session=1", "x.pdf", "https://manuals.example.invalid/x.pdf"),
            // review m4: a query-only reference keeps the base's whole path
            Triple("https://manuals.example.invalid/a/b.pdf", "?t=1", "https://manuals.example.invalid/a/b.pdf?t=1"),
            Triple("https://manuals.example.invalid/a/b.pdf?old=2#p3", "?t=1#p2", "https://manuals.example.invalid/a/b.pdf?t=1#p2"),
            // RFC 3986 §5.2.4: dot segments above the root are dropped
            Triple("https://manuals.example.invalid/a.pdf", "../../x.pdf", "https://manuals.example.invalid/x.pdf"),
            Triple("https://manuals.example.invalid:8443/docs/a.pdf", "b.pdf", "https://manuals.example.invalid:8443/docs/b.pdf"),
            Triple("https://[2001:db8::10]/docs/a.pdf", "b.pdf", "https://[2001:db8::10]/docs/b.pdf"),
            Triple("https://manuals.example.invalid/a.pdf", "https://mirror.example.invalid/f.pdf", "https://mirror.example.invalid/f.pdf"),
        )
        for ((base, location, expected) in cases) {
            val t = transport {
                redirect(base, location)
                serve(expected, pdf)
            }
            val r = resolver("2001:db8::10" to listOf("2001:db8::10"))
            val outcome = fetcher(t, r).run(base)
            assertEquals(expected, assertIs<Fetched>(outcome, "$base + $location").finalUrl, "$base + $location")
            assertEquals(listOf(base, expected), t.requests)
        }
    }

    @Test
    fun aMissingLocationIsRefused() = runTest {
        for (location in listOf(null, "", "   ", "https://manuals.example.invalid/bad path.pdf", "https://manuals.example.invalid/%zz.pdf")) {
            val t = transport { redirect(doc, location) }
            assertEquals(Refused(RedirectRefused), fetcher(t).run(doc), "Location <$location>")
            assertEquals(listOf(doc), t.requests)
            assertTrue(t.served.single().closed)
        }
    }

    @Test
    fun everyHopIsResolved() = runTest {
        val r = resolver("cdn.example.invalid." to listOf("203.0.113.11"))
        val t = transport {
            redirect(doc, "https://CDN.Example.Invalid./d.pdf")
            redirect("https://CDN.Example.Invalid./d.pdf", "https://files.example.invalid/e.pdf")
            serve("https://files.example.invalid/e.pdf", pdf)
        }
        assertIs<Fetched>(fetcher(t, r).run(doc))
        // once per hop, the first included, each asked in lowercase with its trailing dot kept (hand-off 3)
        assertEquals(listOf("manuals.example.invalid", "cdn.example.invalid.", "files.example.invalid"), r.asked)
    }

    @Test
    fun aRedirectToAPublicNameResolvingToALanAddressIsLocalAddress() = runTest {
        val lan = "https://intranet.example.invalid/pool-pump/manual.pdf"
        val r = resolver("intranet.example.invalid" to listOf("203.0.113.12", "192.168.1.20"))
        val t = transport {
            redirect(doc, lan)
            serve(lan, pdf)
        }
        assertEquals(Refused(LocalAddress), fetcher(t, r).run(doc))
        assertEquals(listOf(doc), t.requests, "no GET to the LAN name")
    }

    @Test
    fun theFirstHopResolvingLocalNeverReachesTheTransport() = runTest {
        val r = resolver("manuals.example.invalid" to listOf("10.0.0.5"))
        val t = transport { serve(doc, pdf) }
        val staging = FakeStaging()
        assertEquals(Refused(LocalAddress), fetcher(t, r, staging).run(doc))
        assertTrue(t.requests.isEmpty())
        assertTrue(staging.files.isEmpty())
    }

    @Test
    fun theFirstHopsProblemIsReturnedAsIs() = runTest {
        val t = transport { }
        assertEquals(Refused(NotHttps), fetcher(t).run("http://manuals.example.invalid/m.pdf"))
        assertEquals(Refused(HasCredentials), fetcher(t).run("https://owner:pw@manuals.example.invalid/m.pdf"))
        assertEquals(Refused(Unreachable), fetcher(t).run("https://unknown.example.invalid/m.pdf"))
        val denied = resolver().apply { failure = TransportFailure(TransportFailure.Kind.DENIED) }
        assertEquals(Refused(NetworkDenied), fetcher(t, denied).run(doc))
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun aRedirectHopKeepsLocalUnreachableAndDeniedAndIsOtherwiseRefused() = runTest {
        val cases = listOf(
            "https://localhost/m.pdf" to LocalAddress,
            "https://unknown.example.invalid/m.pdf" to Unreachable,
            "https://owner:pw@files.example.invalid/m.pdf" to RedirectRefused,
            "https://10%2e0%2e0%2e5.example.invalid/m.pdf" to RedirectRefused,
            "ftp://files.example.invalid/m.pdf" to RedirectRefused,
        )
        for ((location, expected) in cases) {
            val t = transport { redirect(doc, location) }
            assertEquals(Refused(expected), fetcher(t).run(doc), location)
            assertEquals(listOf(doc), t.requests)
        }
        val r = resolver()
        val t = transport {
            route(doc) {
                r.failure = TransportFailure(TransportFailure.Kind.DENIED)
                Served(302, location = "https://files.example.invalid/m.pdf")
            }
        }
        assertEquals(Refused(NetworkDenied), fetcher(t, r).run(doc))
    }

    @Test
    fun aResolverOrBodyExceptionThatIsNotATransportFailurePropagates() = runTest {
        val broken = resolver().apply { failure = IllegalStateException("resolver bug") }
        val t = transport { serve(doc, pdf) }
        assertFailsWith<IllegalStateException> { fetcher(t, broken).run(doc) }
        assertTrue(t.requests.isEmpty())

        val staging = FakeStaging()
        val odd = transport {
            route(doc) { Served(200, body = FakeBody(pdf, failAt = 10, failure = TransportFailure(TransportFailure.Kind.INTERRUPTED))) }
        }
        assertEquals(Refused(Interrupted), fetcher(odd, staging = staging).run(doc))
        val bug = transport {
            route(doc) { Served(200, body = FakeBody(pdf, forbidden = true)) }
        }
        assertFailsWith<AssertionError> { fetcher(bug, staging = staging).run(doc) }
        assertTrue(bug.served.single().closed)
        assertTrue(staging.files.all { it.discarded })
    }

    // ---- row 14: statuses ----

    @Test
    fun signInStatusesAreNeedsSignIn() = runTest {
        for (status in listOf(401, 403, 407)) {
            val staging = FakeStaging()
            val t = transport { route(doc) { Served(status, body = FakeBody(loginPage, forbidden = true)) } }
            assertEquals(Refused(NeedsSignIn), fetcher(t, staging = staging).run(doc), "$status")
            assertTrue(t.served.single().closed)
            assertTrue(staging.files.isEmpty())
        }
    }

    @Test
    fun onlyTwoHundredAndTwoHundredThreeAreABody() = runTest {
        for (status in listOf(200, 203)) {
            val t = transport { serve(doc, pdf, status = status) }
            assertIs<Fetched>(fetcher(t).run(doc), "$status")
            assertTrue(t.served.single().closed)
        }
        for (status in listOf(204, 206, 300, 304, 404, 500, 503)) {
            val t = transport { route(doc) { Served(status, body = FakeBody(pdf, forbidden = true)) } }
            assertEquals(Refused(ServerError(status)), fetcher(t).run(doc), "$status")
            assertTrue(t.served.single().closed)
        }
    }

    @Test
    fun aTransportFailureAtGetIsMapped() = runTest {
        val expected = mapOf(
            TransportFailure.Kind.UNREACHABLE to Unreachable,
            TransportFailure.Kind.TIMED_OUT to TimedOut,
            TransportFailure.Kind.INTERRUPTED to Interrupted,
            TransportFailure.Kind.DENIED to NetworkDenied,
        )
        for ((kind, problem) in expected) {
            val t = transport { fail(doc, kind) }
            assertEquals(Refused(problem), fetcher(t).run(doc), "$kind")
        }
    }

    // ---- row 15: headers judged before the body ----

    @Test
    fun declaredHtmlXhtmlAndPlainTextFailBeforeTheBody() = runTest {
        val declared = listOf(
            "text/html", "text/html; charset=utf-8", "Application/XHTML+XML", "text/plain;charset=us-ascii", " TEXT/PLAIN ",
        )
        for (type in declared) {
            val staging = FakeStaging()
            val body = FakeBody(pdf, forbidden = true)
            val t = transport { route(doc) { Served(200, contentType = type, contentLength = pdf.size.toLong(), body = body) } }
            assertEquals(Refused(NotADocument), fetcher(t, staging = staging).run(doc), type)
            assertEquals(0, body.reads)
            assertTrue(staging.files.isEmpty())
            assertTrue(t.served.single().closed)
        }
    }

    @Test
    fun contentLengthOverTheLimitFailsBeforeTheBody() = runTest {
        val limits = FetchLimits(maxBytes = 2_000, chunkBytes = 512)
        val staging = FakeStaging()
        val over = transport {
            route(doc) { Served(200, contentType = "application/pdf", contentLength = 2_001, body = FakeBody(pdf, forbidden = true)) }
        }
        assertEquals(Refused(TooLarge), fetcher(over, staging = staging, limits = limits).run(doc))
        assertTrue(staging.files.isEmpty())
        assertTrue(over.served.single().closed)

        val atTheLimit = transport { serve(doc, pdfOf(2_000), "application/pdf") }
        assertEquals(2_000, assertIs<Fetched>(fetcher(atTheLimit, limits = limits).run(doc)).sizeBytes)
    }

    // ---- row 16: streaming ----

    @Test
    fun theCapIsLimitPlusOne() = runTest {
        val limits = FetchLimits(maxBytes = 2_000, chunkBytes = 512)
        val exact = transport { serve(doc, pdfOf(2_000), contentLength = null) }
        assertEquals(2_000, assertIs<Fetched>(fetcher(exact, limits = limits).run(doc)).sizeBytes)

        val staging = FakeStaging()
        val oneOver = transport { serve(doc, pdfOf(2_001), contentLength = null) }
        assertEquals(Refused(TooLarge), fetcher(oneOver, staging = staging, limits = limits).run(doc))
        assertTrue(staging.files.single().discarded)

        // the count is authoritative, never the header
        val lying = transport { serve(doc, pdfOf(2_001), contentLength = 100) }
        assertEquals(Refused(TooLarge), fetcher(lying, limits = limits).run(doc))
    }

    @Test
    fun theDefaultLimitIsMaxAttachmentBytes() = runTest {
        assertEquals(FetchLimits(MAX_ATTACHMENT_BYTES, 5, 600_000, 65_536), FetchLimits())
        val t = transport {
            route(doc) { Served(200, contentLength = MAX_ATTACHMENT_BYTES + 1, body = FakeBody(pdf, forbidden = true)) }
        }
        assertEquals(Refused(TooLarge), fetcher(t).run(doc))
    }

    @Test
    fun theTransportContractCarriesTheOwnersValues() {
        assertEquals("ServiceTag", DocumentTransport.USER_AGENT)
        assertEquals(15_000, DocumentTransport.CONNECT_TIMEOUT_MILLIS)
        assertEquals(30_000, DocumentTransport.IDLE_TIMEOUT_MILLIS)
    }

    @Test
    fun emptyIsEmpty() = runTest {
        for (length in listOf(0L, null)) {
            val staging = FakeStaging()
            val t = transport { serve(doc, ByteArray(0), "application/pdf", contentLength = length) }
            assertEquals(Refused(Empty), fetcher(t, staging = staging).run(doc))
            assertTrue(staging.files.single().discarded)
            assertTrue(t.served.single().closed)
        }
    }

    @Test
    fun aMidBodyFailureIsInterruptedAndDiscards() = runTest {
        val staging = FakeStaging()
        val t = transport { route(doc) { Served(200, body = FakeBody(pdfOf(3_000), failAt = 1_500)) } }
        assertEquals(Refused(Interrupted), fetcher(t, staging = staging, limits = FetchLimits(chunkBytes = 1_000)).run(doc))
        assertTrue(staging.files.single().discarded)
        assertTrue(t.served.single().closed)
    }

    @Test
    fun anIdleBodyIsTimedOut() = runTest {
        val staging = FakeStaging()
        val idle = TransportFailure(TransportFailure.Kind.TIMED_OUT)
        val t = transport { route(doc) { Served(200, body = FakeBody(pdfOf(3_000), failAt = 1_000, failure = idle)) } }
        assertEquals(Refused(TimedOut), fetcher(t, staging = staging).run(doc))
        assertTrue(staging.files.single().discarded)
    }

    @Test
    fun aStagingFailureIsInterrupted() = runTest {
        val full = FakeStaging(failWriteAfter = 100)
        val t = transport { serve(doc, pdfOf(3_000)) }
        assertEquals(Refused(Interrupted), fetcher(t, staging = full, limits = FetchLimits(chunkBytes = 64)).run(doc))
        assertTrue(full.files.single().discarded)
        assertTrue(t.served.single().closed)

        val noRoom = transport { serve(doc, pdf) }
        assertEquals(Refused(Interrupted), fetcher(noRoom, staging = FakeStaging(failCreate = true)).run(doc))
        assertTrue(noRoom.served.single().closed)
    }

    @Test
    fun aStalledBodyIsClosedAtTheDeadline() = runBlocking {
        val body = FakeBody(pdfOf(3_000), stallAt = 500)
        val t = transport { route(doc) { Served(200, contentType = "application/pdf", body = body) } }
        val staging = FakeStaging()
        val fetch = FetchDocument(
            t, HopPolicy(resolver()), staging, FetchLimits(overallMillis = 100, chunkBytes = 256), Dispatchers.IO,
        )
        val started = System.nanoTime()
        val outcome = fetch.run(doc)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertEquals(Refused(TimedOut), outcome)
        assertTrue(t.served.single().closed)
        assertTrue(staging.files.single().discarded)
        assertTrue(elapsed < 2_000, "took $elapsed ms")
    }

    @Test
    fun cancellingMidBodyClosesTheResponseAndDiscards() = runBlocking {
        val body = FakeBody(pdfOf(3_000), stallAt = 500)
        val t = transport { route(doc) { Served(200, contentType = "application/pdf", body = body) } }
        val staging = FakeStaging()
        val fetch = FetchDocument(t, HopPolicy(resolver()), staging, FetchLimits(chunkBytes = 256), Dispatchers.IO)
        var result: Result<FetchOutcome>? = null
        val job = launch { result = runCatching { fetch.run(doc) } }
        assertTrue(withContext(Dispatchers.IO) { body.stalled.await(2, TimeUnit.SECONDS) }, "the body never stalled")
        val started = System.nanoTime()
        job.cancelAndJoin()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertIs<CancellationException>(result?.exceptionOrNull(), "a cancel is never a problem: $result")
        assertTrue(t.served.single().closed)
        assertTrue(staging.files.single().discarded)
        assertTrue(elapsed < 2_000, "took $elapsed ms")
    }

    @Test
    fun aSlowGetTimesOutInVirtualTime() = runTest {
        val t = transport {
            route(doc) {
                delay(11.minutes)
                Served(200, body = FakeBody(pdf))
            }
        }
        assertEquals(Refused(TimedOut), fetcher(t).run(doc))
        assertEquals(600_000, testScheduler.currentTime)
    }

    @Test
    fun progressReportsDoneAndTotal() = runTest {
        val file = pdfOf(2_004)
        val limits = FetchLimits(chunkBytes = 1_000)
        for (declared in listOf(2_004L, null)) {
            val seen = mutableListOf<Pair<Long, Long?>>()
            val t = transport { serve(doc, file, contentLength = declared) }
            assertIs<Fetched>(fetcher(t, limits = limits).run(doc) { done, total -> seen += done to total })
            assertEquals(listOf(1_000L to declared, 2_000L to declared, 2_004L to declared), seen)
        }
    }

    @Test
    fun theDigestIsTheBytesSha256() = runTest {
        val file = pdfOf(5_000)
        val body = FakeBody(file)
        val staging = FakeStaging()
        val t = transport { route(doc) { Served(200, contentType = "application/pdf", body = body) } }
        val fetched = assertIs<Fetched>(fetcher(t, staging = staging, limits = FetchLimits(chunkBytes = 700)).run(doc))
        assertEquals(sha256(file), fetched.sha256)
        assertTrue(Regex("[0-9a-f]{64}").matches(fetched.sha256))
        assertEquals(5_000, fetched.sizeBytes)
        val staged = staging.files.single()
        assertEquals(staged, fetched.staged)
        assertContentEquals(file, staged.bytes)
        assertEquals(0, staged.sourceOpens, "the staged file is never read back")
        assertFalse(staged.discarded)
        assertTrue(staged.outputClosed)
        assertTrue(body.largestRead <= 700, "one chunk at a time")
        assertTrue(t.served.single().closed)
    }

    @Test
    fun anEndMarkerStraddlingAChunkBoundaryIsFound() = runTest {
        // chunks of 1,000: each end marker spans bytes 1,999 and 2,000, and the tail window spans three chunks
        val files = listOf(
            withMarkerAt(ascii("%PDF-1.5\n"), ascii("%%EOF\n"), 1_998) to "application/pdf",
            withMarkerAt(pngHead, iend, 1_995) to "image/png",
            withMarkerAt(jpegHead, bytes(0xFF, 0xD9), 1_999) to "image/jpeg",
        )
        for ((file, mime) in files) {
            val t = transport { serve(doc, file, "application/octet-stream") }
            val fetched = assertIs<Fetched>(fetcher(t, limits = FetchLimits(chunkBytes = 1_000)).run(doc), mime)
            assertEquals(mime, fetched.mimeType)
            assertEquals(file.size.toLong(), fetched.sizeBytes)
        }
    }

    @Test
    fun fetchedToStringPrintsNoUrl() = runTest {
        val secret = "https://files.example.invalid/m.pdf?token=AB12CD34"
        val t = transport { serve(secret, pdf) }
        val fetched = assertIs<Fetched>(fetcher(t).run(secret))
        assertEquals(secret, fetched.finalUrl)
        assertFalse("example.invalid" in fetched.toString() || "token" in fetched.toString(), fetched.toString())
    }

    // ---- row 17b: the "served as" fixtures, end to end through the real sniff (R85-5) ----

    @Test
    fun htmlServedAsPdfIsNotADocument() = runTest {
        val staging = FakeStaging()
        val t = transport { serve(doc, loginPage, "application/pdf") }
        assertEquals(Refused(NotADocument), fetcher(t, staging = staging).run(doc))
        assertTrue(staging.files.single().discarded)
    }

    @Test
    fun aPdfServedAsOctetStreamIsAPdf() = runTest {
        val t = transport { serve(doc, pdf, "application/octet-stream") }
        val fetched = assertIs<Fetched>(fetcher(t).run(doc))
        assertEquals("application/pdf", fetched.mimeType, "recorded as the bytes prove, never as declared")
    }

    @Test
    fun theFakeHeaderHtmlServedAsPdfIsNotADocument() = runTest {
        // declared application/pdf, so the declared-type check (step 5) cannot be what refuses it (review m6)
        val staging = FakeStaging()
        val t = transport { serve(doc, fakeHeaderHtml, "application/pdf") }
        assertEquals(Refused(NotADocument), fetcher(t, staging = staging).run(doc))
        assertTrue(staging.files.single().discarded)
        assertNull(DocumentSniff.classify(fakeHeaderHtml.size.toLong(), fakeHeaderHtml, fakeHeaderHtml))
    }
}
