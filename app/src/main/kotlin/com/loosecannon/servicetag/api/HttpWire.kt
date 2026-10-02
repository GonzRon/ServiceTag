package com.loosecannon.servicetag.api

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** The four methods this listener speaks. Anything else is refused before a path is looked at. */
private val METHODS = setOf("GET", "POST", "PATCH", "DELETE")

/** The request line's ceiling, in bytes. */
private const val MAX_REQUEST_LINE = 8 * 1024

/** The header block's two ceilings: total bytes, and how many lines. */
private const val MAX_HEADER_BYTES = 8 * 1024
private const val MAX_HEADERS = 64

private const val BEARER_PREFIX = "Bearer "

/**
 * One parsed request. [headers] keys are lower-cased, so a client's capitalisation cannot matter;
 * [body] is empty when there was none. Not a `data class` deliberately — a `ByteArray` member makes
 * the generated `equals` identity-based and misleading, and nothing here needs `copy`.
 *
 * [stream] is set on one shape only, the attachment upload ([isAttachmentUpload], #92 C9): exactly
 * `Content-Length` bytes of the socket, **not yet read**, so the router can check the token before
 * a byte of it moves (C10). [body] is then empty. Every other request's body is read here, as ever.
 */
internal class ApiRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val stream: InputStream? = null,
)

/**
 * #92 (C9): `POST /v1/assets/<one segment>/attachments` on the canonical path — the request whose body
 * is left on the socket, with its own ceiling. #69 (C19, C-3) adds exactly the same upload to the
 * other two owners a route names, `POST /v1/supply-items/<one segment>/attachments` and
 * `POST /v1/installed-components/<one segment>/attachments`, and nothing else. Every other method on
 * those shapes is framed and read like any other request.
 */
internal fun isAttachmentUpload(method: String, path: String): Boolean {
    if (method != "POST") return false
    val segments = path.split('/')
    return segments.size == 5 && segments[0].isEmpty() && segments[1] == "v1" &&
        (segments[2] == "assets" || segments[2] == "supply-items" || segments[2] == "installed-components") &&
        segments[3].isNotEmpty() && segments[4] == "attachments"
}

/**
 * The request's own stream failed under a handler that was reading it (#92 C12): a read timed out,
 * the peer reset, `stop()` closed the socket, or the upload's deadline passed. Not an answer: the
 * router rethrows it and the server closes the connection with nothing written, because a peer that
 * is gone cannot be told anything and a failure of *its* stream is never this phone's 409 or 500.
 */
internal class RequestStreamFailed(why: String, cause: Throwable? = null) : Exception(why, cause)

/** The bearer token this request offered, or null if it offered none in that shape. */
internal fun ApiRequest.bearerToken(): String? {
    val header = headers["authorization"] ?: return null
    if (!header.startsWith(BEARER_PREFIX)) return null
    return header.substring(BEARER_PREFIX.length)
}

/** What this endpoint said it was sending, lower-cased and stripped of any parameters. */
internal fun ApiRequest.mediaType(): String? =
    headers["content-type"]?.substringBefore(';')?.trim()?.lowercase()

internal class ApiResponse(val status: Int, val reason: String, val body: ByteArray) {
    companion object {
        fun json(status: Int, reason: String, body: String): ApiResponse =
            ApiResponse(status, reason, body.toByteArray(Charsets.UTF_8))

        fun empty(status: Int, reason: String): ApiResponse =
            ApiResponse(status, reason, ByteArray(0))
    }
}

/**
 * The request could not be framed. It carries the response to send, so the ceiling that caught it
 * is the thing that decides the status — and an `Exception` rather than an `IOException`, so the
 * server's "the client hung up" handler cannot swallow it.
 *
 * **Every response it carries has an empty body.** A framing refusal happens *before* the token is
 * checked, so it is an answer to an unauthenticated peer, and the status is all it may say: a 413
 * that named the path's ceiling would tell a caller which paths exist and how big each one's window
 * is. The status is enough to act on — 400 framing, 405 method, 413 too large — and every refusal
 * the *router* raises, which is after authentication, keeps its full JSON body.
 */
internal class MalformedRequest(val response: ApiResponse, val why: String) : Exception(why)

/**
 * Reads one HTTP/1.x request off [input], or throws [MalformedRequest] with the answer to send.
 *
 * Deliberately narrow, and the narrowness is the point (see the plan's dependency decision): one
 * request per connection, four methods, a `Content-Length` body or none, hard ceilings on the
 * request line, the header block and the header count. `Transfer-Encoding` in any form is refused
 * outright rather than implemented. A query string is dropped — no endpoint reads one, so nothing
 * may come to depend on one.
 *
 * [bodyCapFor] is consulted with the method and the path *before* a single body byte is read, which
 * is what makes the 4 MiB import ceiling reachable at two paths and the 256 MiB upload ceiling at one
 * method on three shapes, one per owner, and nowhere else. On that shape ([isAttachmentUpload]) the body is **left on
 * the socket** behind [ApiRequest.stream], bounded to exactly `Content-Length` bytes.
 */
internal fun parseRequest(input: InputStream, bodyCapFor: (String, String) -> Int): ApiRequest {
    val requestLine = readLine(input, MAX_REQUEST_LINE)
        ?: throw malformed(400, "Bad Request", "the connection said nothing")
    val parts = requestLine.split(' ')
    if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) {
        throw malformed(400, "Bad Request", "that is not an HTTP request line")
    }
    val method = parts[0]
    if (method !in METHODS) throw malformed(405, "Method Not Allowed")
    // The query string is dropped — no endpoint reads one — and trailing slashes are dropped
    // with it, so `bodyCapFor` below and `ApiRouter.route` (which only strips the one leading slash,
    // not a trailing one — review S6) cannot disagree about which path this is. Canonicalising in
    // one place is what keeps a trailing-slash spelling from reaching a handler with the wrong
    // ceiling.
    val path = parts[1].substringBefore('?').let {
        it.trimEnd('/').ifEmpty { "/" }
    }
    val headers = readHeaders(input)

    if (headers.containsKey("transfer-encoding")) {
        throw malformed(400, "Bad Request", "send a body with a Content-Length, not a chunked one")
    }
    val declared = headers["content-length"]
    val length = when {
        declared == null -> 0
        else -> declared.toIntOrNull()
            ?: throw malformed(400, "Bad Request", "Content-Length is not a number")
    }
    if (length < 0) throw malformed(400, "Bad Request", "Content-Length is negative")
    if (length > 0 && (method == "GET" || method == "DELETE")) {
        throw malformed(400, "Bad Request", "a $method carries no body here")
    }
    if (declared == null && (method == "POST" || method == "PATCH")) {
        throw malformed(400, "Bad Request", "a $method needs a Content-Length")
    }
    // The cap is chosen from the canonical path, before a body byte is read; the refusal names
    // neither the path nor the number, because this is still a pre-authentication answer.
    if (length > bodyCapFor(method, path)) throw malformed(413, "Payload Too Large")
    if (isAttachmentUpload(method, path)) {
        return ApiRequest(method, path, headers, ByteArray(0), BoundedBody(input, length.toLong()))
    }
    return ApiRequest(method, path, headers, readExactly(input, length))
}

/**
 * Exactly [length] bytes of [input] and then an end of stream, whatever follows on the socket. An
 * end of stream before [length] is [EarlyEndOfBody]: the peer sent less than it declared.
 */
private class BoundedBody(private val input: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (remaining == 0L) return -1
        val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n < 0) throw EarlyEndOfBody()
        remaining -= n
        return n
    }
}

/** The peer ended the connection before the `Content-Length` it declared (#92 C12, the shipped 400). */
internal class EarlyEndOfBody : java.io.IOException("the body was shorter than Content-Length")

/** Writes [response] with its own length and closes: one request per connection, always. */
internal fun writeResponse(output: OutputStream, response: ApiResponse) {
    val head = buildString {
        append("HTTP/1.1 ").append(response.status).append(' ').append(response.reason).append("\r\n")
        // A zero-byte body has no type, which is what a 401 here is: an answer that says nothing.
        if (response.body.isNotEmpty()) append("Content-Type: application/json\r\n")
        append("Content-Length: ").append(response.body.size).append("\r\n")
        append("Cache-Control: no-store\r\n")
        append("Connection: close\r\n")
        append("\r\n")
    }
    output.write(head.toByteArray(Charsets.UTF_8))
    output.write(response.body)
}

/**
 * Every framing refusal, with an empty body. [why] is not sent anywhere: it exists so that the
 * throw site reads as an explanation rather than as a bare status, and so a future maintainer who
 * wants these diagnosable has one place to change.
 */
private fun malformed(status: Int, reason: String, why: String = reason) =
    MalformedRequest(ApiResponse.empty(status, reason), why)

/** One CRLF- or LF-terminated line, at most [max] bytes; null only at an immediate end of stream. */
private fun readLine(input: InputStream, max: Int): String? {
    val out = ByteArrayOutputStream()
    while (true) {
        val b = input.read()
        if (b < 0) {
            return if (out.size() == 0) null else String(out.toByteArray(), Charsets.UTF_8)
        }
        if (b == '\n'.code) {
            val bytes = out.toByteArray()
            val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
            return String(bytes, 0, end, Charsets.UTF_8)
        }
        // N5: checked before the write, not after — otherwise the byte that pushes `out` from `max`
        // to `max + 1` is written before the check catches it, admitting one byte more than [max]
        // documents. `out.size()` is bytes, not characters, which is the ceiling this is meant to be.
        if (out.size() >= max) throw malformed(400, "Bad Request", "that line is too long")
        out.write(b)
    }
}

private fun readHeaders(input: InputStream): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    var bytes = 0
    while (true) {
        val line = readLine(input, MAX_HEADER_BYTES)
            ?: throw malformed(400, "Bad Request", "the header block ended early")
        if (line.isEmpty()) return headers
        // Bytes, not characters: a multibyte header value must not undercount against the ceiling.
        bytes += line.toByteArray(Charsets.UTF_8).size + 2
        if (bytes > MAX_HEADER_BYTES) throw malformed(400, "Bad Request", "the header block is too large")
        if (headers.size >= MAX_HEADERS) throw malformed(400, "Bad Request", "too many headers")
        val colon = line.indexOf(':')
        if (colon <= 0) throw malformed(400, "Bad Request", "a header line has no name")
        headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
    }
}

private fun readExactly(input: InputStream, length: Int): ByteArray {
    if (length == 0) return ByteArray(0)
    val body = ByteArray(length)
    var read = 0
    while (read < length) {
        val n = input.read(body, read, length - read)
        if (n < 0) throw malformed(400, "Bad Request", "the body was shorter than Content-Length")
        read += n
    }
    return body
}
