package com.loosecannon.servicetag.api

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.0 (#46) — the framing, and every way a request can be refused before a route is looked up.
 *
 * This is the whole justification for a hand-rolled server rather than a library: the ceilings the
 * owner made the condition of one are asserted here, on the real parser, in the JVM gate CI already
 * runs. Nothing in this file needs a socket, an emulator or an Android class.
 */
class HttpWireTest {

    /** The production caps, as the router publishes them. */
    private val caps: (String) -> Int = { path ->
        if (path == IMPORT_MERGE_PLAN_PATH || path == IMPORT_MERGE_APPLY_PATH) {
            MAX_IMPORT_BYTES
        } else {
            MAX_BODY_BYTES
        }
    }

    private fun parse(raw: String) = parseRequest(ByteArrayInputStream(raw.toByteArray()), caps)

    private fun refusal(raw: String): ApiResponse =
        try {
            parse(raw)
            error("expected a refusal")
        } catch (e: MalformedRequest) {
            e.response
        }

    @Test fun aWellFormedGetParses() {
        val request = parse("GET /v1/status?verbose=1 HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer ABCD2345\r\n\r\n")
        assertEquals("GET", request.method)
        // The query string is dropped: no endpoint reads one, so nothing may depend on it.
        assertEquals("/v1/status", request.path)
        assertEquals("ABCD2345", request.bearerToken())
        assertEquals(0, request.body.size)
    }

    @Test fun aWellFormedPostReadsExactlyContentLengthBytes() {
        val request = parse("POST /v1/assets HTTP/1.1\r\nContent-Length: 16\r\n\r\n{\"name\":\"Hot tub\"}extra")
        assertEquals("POST", request.method)
        assertEquals("{\"name\":\"Hot tu", request.body.decodeToString().take(15))
        assertEquals(16, request.body.size)
    }

    @Test fun anUnknownMethodIs405() {
        assertEquals(405, refusal("PUT /v1/assets HTTP/1.1\r\n\r\n").status)
        assertEquals(405, refusal("OPTIONS /v1/assets HTTP/1.1\r\n\r\n").status)
        assertEquals(405, refusal("CONNECT /v1/assets HTTP/1.1\r\n\r\n").status)
    }

    @Test fun aChunkedBodyIs400() {
        val response = refusal("POST /v1/assets HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n")
        assertEquals(400, response.status)
        assertEquals(0, response.body.size)
    }

    /**
     * Security minimum 6: a framing refusal is an answer to a peer that has not authenticated, so
     * the status is all it may say. In particular a 413 must not name the path's ceiling — that
     * would tell a caller which paths exist and how large each one's window is.
     */
    @Test fun everyFramingRefusalHasAnEmptyBody() {
        val shapes = listOf(
            "",
            "PUT /v1/assets HTTP/1.1\r\n\r\n",
            "POST /v1/assets HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n",
            "POST /v1/assets HTTP/1.1\r\n\r\n",
            "GET /v1/assets HTTP/1.1\r\nContent-Length: 2\r\n\r\n{}",
            "POST /v1/assets HTTP/1.1\r\nContent-Length: ${MAX_BODY_BYTES + 1}\r\n\r\n",
            "POST $IMPORT_MERGE_PLAN_PATH HTTP/1.1\r\nContent-Length: ${MAX_IMPORT_BYTES + 1}\r\n\r\n",
            "GET /v1/status HTTP/1.1\r\nno-colon-here\r\n\r\n",
        )
        for (raw in shapes) {
            val response = refusal(raw)
            assertEquals("body for ${raw.take(24)}", 0, response.body.size)
            assertTrue("status for ${raw.take(24)}", response.status in setOf(400, 405, 413))
        }
        // And the cap number is nowhere in any of them.
        assertTrue(
            shapes.none { refusal(it).body.decodeToString().contains(MAX_IMPORT_BYTES.toString()) },
        )
    }

    /**
     * One canonical spelling, so the ceiling and the route cannot disagree. `ApiRouter.route` trims
     * slashes of its own, so without this a `POST /v1/import-merge/plan/` would reach the plan
     * handler with the 64 KiB cap instead of 4 MiB.
     */
    @Test fun aTrailingSlashIsDroppedSoTheCapComesFromTheCanonicalPath() {
        assertEquals("/v1/assets", parse("GET /v1/assets/ HTTP/1.1\r\n\r\n").path)
        assertEquals(
            IMPORT_MERGE_PLAN_PATH,
            parse("POST $IMPORT_MERGE_PLAN_PATH/ HTTP/1.1\r\nContent-Length: 0\r\n\r\n").path,
        )
        // A body that only the import ceiling allows, sent to the trailing-slash spelling: the
        // canonical path is what chose the cap, so it is accepted rather than refused.
        val big = MAX_BODY_BYTES + 1
        assertEquals(
            400,
            refusal("POST $IMPORT_MERGE_PLAN_PATH/ HTTP/1.1\r\nContent-Length: $big\r\n\r\n").status,
        )
        // The root is left alone: it is one character and dropping it would make it empty.
        assertEquals("/", parse("GET / HTTP/1.1\r\n\r\n").path)
    }

    @Test fun aPostWithNoContentLengthIs400() {
        assertEquals(400, refusal("POST /v1/assets HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").status)
        assertEquals(400, refusal("PATCH /v1/assets/a1 HTTP/1.1\r\n\r\n").status)
    }

    @Test fun aGetOrDeleteWithABodyIs400() {
        assertEquals(400, refusal("GET /v1/assets HTTP/1.1\r\nContent-Length: 2\r\n\r\n{}").status)
        assertEquals(400, refusal("DELETE /v1/events/e1 HTTP/1.1\r\nContent-Length: 2\r\n\r\n{}").status)
    }

    @Test fun aBodyOverTheCapIs413() {
        val response = refusal("POST /v1/assets HTTP/1.1\r\nContent-Length: ${MAX_BODY_BYTES + 1}\r\n\r\n")
        assertEquals(413, response.status)
    }

    /** The two paths with a bigger ceiling, and the proof that they are the only ones. */
    @Test fun theImportPathsHaveTheirOwnCap() {
        val big = MAX_BODY_BYTES + 1
        assertEquals(413, refusal("POST /v1/assets HTTP/1.1\r\nContent-Length: $big\r\n\r\n").status)
        // Bare `/v1/import-merge` is not a route, and gets no favours from the parser either.
        assertEquals(413, refusal("POST /v1/import-merge HTTP/1.1\r\nContent-Length: $big\r\n\r\n").status)
        for (path in listOf(IMPORT_MERGE_PLAN_PATH, IMPORT_MERGE_APPLY_PATH)) {
            // Same length, the import paths: accepted by the parser, which then waits for bytes
            // that never come — a short body is its own 400, which is what makes this assertion
            // about the cap and not about the body.
            assertEquals(400, refusal("POST $path HTTP/1.1\r\nContent-Length: $big\r\n\r\n").status)
            assertEquals(
                413,
                refusal("POST $path HTTP/1.1\r\nContent-Length: ${MAX_IMPORT_BYTES + 1}\r\n\r\n").status,
            )
        }
    }

    @Test fun anOversizedRequestLineOrHeaderBlockIs400() {
        assertEquals(400, refusal("GET /v1/" + "a".repeat(9_000) + " HTTP/1.1\r\n\r\n").status)
        val fatHeaders = (1..40).joinToString("") { "X-Pad-$it: ${"p".repeat(300)}\r\n" }
        assertEquals(400, refusal("GET /v1/status HTTP/1.1\r\n$fatHeaders\r\n").status)
    }

    @Test fun tooManyHeadersIs400() {
        val many = (1..70).joinToString("") { "X-Pad-$it: p\r\n" }
        assertEquals(400, refusal("GET /v1/status HTTP/1.1\r\n$many\r\n").status)
    }

    @Test fun garbageIsAlways400AndNeverACrash() {
        assertEquals(400, refusal("").status)
        assertEquals(400, refusal("\r\n\r\n").status)
        assertEquals(400, refusal("not a request line at all\r\n\r\n").status)
        assertEquals(400, refusal("GET /v1/status\r\n\r\n").status)
        assertEquals(400, refusal("GET /v1/status HTTP/1.1\r\nno-colon-here\r\n\r\n").status)
        assertEquals(400, refusal("GET /v1/status HTTP/1.1\r\nContent-Length: nine\r\n\r\n").status)
        assertEquals(400, refusal("POST /v1/assets HTTP/1.1\r\nContent-Length: 40\r\n\r\nshort").status)
    }

    @Test fun aResponseIsFramedWithItsLengthAndClosesTheConnection() {
        val out = ByteArrayOutputStream()
        writeResponse(out, ApiResponse.json(200, "OK", """{"ok":true}"""))
        val text = out.toByteArray().decodeToString()
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Type: application/json\r\n"))
        assertTrue(text.contains("Content-Length: 11\r\n"))
        assertTrue(text.contains("Connection: close\r\n"))
        assertTrue(text.endsWith("\r\n\r\n{\"ok\":true}"))
    }

    /** A 401 is a zero-byte body, and says nothing at all — not even what kind of thing it is. */
    @Test fun anEmptyResponseCarriesNoContentTypeAndNoBody() {
        val out = ByteArrayOutputStream()
        writeResponse(out, ApiResponse.empty(401, "Unauthorized"))
        val text = out.toByteArray().decodeToString()
        assertEquals("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n", text)
    }
}
