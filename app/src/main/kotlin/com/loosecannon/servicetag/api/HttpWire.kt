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
 */
internal class ApiRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

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
 * [bodyCapFor] is consulted with the path *before* a single body byte is read, which is what makes
 * the 4 MiB import ceiling reachable at one path and nowhere else.
 */
internal fun parseRequest(input: InputStream, bodyCapFor: (String) -> Int): ApiRequest {
    val requestLine = readLine(input, MAX_REQUEST_LINE)
        ?: throw malformed(400, "Bad Request", "the connection said nothing")
    val parts = requestLine.split(' ')
    if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) {
        throw malformed(400, "Bad Request", "that is not an HTTP request line")
    }
    val method = parts[0]
    if (method !in METHODS) throw malformed(405, "Method Not Allowed")
    // The query string is dropped — no endpoint reads one — and trailing slashes are dropped
    // with it, so `bodyCapFor` below and `ApiRouter.route` (which trims slashes of its own) cannot
    // disagree about which path this is. Canonicalising in one place is what keeps a trailing-slash
    // spelling from reaching a handler with the wrong ceiling.
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
    if (length > bodyCapFor(path)) throw malformed(413, "Payload Too Large")
    return ApiRequest(method, path, headers, readExactly(input, length))
}

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
        out.write(b)
        if (out.size() > max) throw malformed(400, "Bad Request", "that line is too long")
        // `out.size()` is bytes, not characters, which is the ceiling this is meant to be.
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
