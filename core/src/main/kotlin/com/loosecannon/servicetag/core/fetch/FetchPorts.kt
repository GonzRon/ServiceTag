package com.loosecannon.servicetag.core.fetch

import com.loosecannon.servicetag.core.model.MAX_ATTACHMENT_BYTES
import com.loosecannon.servicetag.core.ports.ByteSource
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * One https GET, and nothing else (#85 C10, C16). The adapter never follows a redirect and never sends a
 * cookie, a credential or an `Authorization` header; it sends exactly three request properties, the
 * last of them the fixed [USER_AGENT] (R85-13). It connects within [CONNECT_TIMEOUT_MILLIS] and gives up
 * on a read idle for [IDLE_TIMEOUT_MILLIS] (R85-7), both surfacing as `TransportFailure(TIMED_OUT)`.
 * Every failure is thrown as a [TransportFailure], already classified. Cancelling [get] disconnects, and
 * a response that arrives after the cancel is closed by the transport and never returned (review m3).
 */
interface DocumentTransport {
    /** One GET of [url]. Never follows a redirect, never sends a cookie or credential. Throws [TransportFailure]. */
    suspend fun get(url: String): TransportResponse

    companion object {
        /** R85-13: the whole identifying header. No app or Android version, device model or build id. */
        const val USER_AGENT = "ServiceTag"

        /** R85-7: the connect deadline. */
        const val CONNECT_TIMEOUT_MILLIS = 15_000

        /** R85-7: the idle deadline, per read. The overall one is [FetchLimits.overallMillis]. */
        const val IDLE_TIMEOUT_MILLIS = 30_000
    }
}

/**
 * One answer. [body] is read only for a 200 or 203, and only after the declared type and length have
 * been judged; its read failures surface as `TransportFailure(INTERRUPTED | TIMED_OUT)`. [close] closes
 * the connection: it is idempotent, never throws, and unblocks a read pending on another thread, which is
 * how a cancel or the overall deadline reaches a stalled body. It runs in `finally` blocks and cancel
 * paths, where a throw would replace the cancellation.
 */
class TransportResponse(
    val status: Int,
    val location: String?,
    val contentType: String?,
    val contentLength: Long?,
    val body: InputStream,
    private val abort: () -> Unit,
) : Closeable {
    override fun close() = abort()

    /** No [location]: a redirect target may carry a token. */
    override fun toString() = "TransportResponse(status=$status)"
}

/** App-private staging for one download (C18). */
interface StagingArea {
    fun create(): StagingFile
}

/** One staged download. [discard] is idempotent and never throws: it runs on cleanup and cancel paths. */
interface StagingFile {
    fun output(): OutputStream
    fun source(): ByteSource

    /** Read-only random access for the one container inspection (C26), once the stream is done. */
    fun reader(): StagedReader
    fun discard()
}

/** C26: random, read-only access to a staged file. There is no write method. */
fun interface StagedReader {
    /** Exactly min([length], size − [position]) bytes from [position] (empty at or past the end). Throws IOException. */
    fun readAt(position: Long, length: Int): ByteArray
}

/**
 * A read past an inspection's budget, or at a negative position or length: the file is refused. Not an
 * IOException on purpose: a refused file is `NotADocument`, a failed read is `Interrupted`.
 */
class InspectionOverBudget : RuntimeException("past the inspection budget")

/**
 * C26: the one budget of one container inspection; every validator reads through it, never through a raw
 * [StagedReader]. A read that would pass [maxReads] reads or [maxBytes] requested bytes throws
 * [InspectionOverBudget] before the file is touched: a file that needs more is refused, never half-read
 * into an answer.
 */
class BoundedInspection(private val reader: StagedReader, val maxReads: Int = 160, val maxBytes: Int = 524_288) {
    var reads = 0
        private set
    var bytes = 0
        private set

    fun readAt(position: Long, length: Int): ByteArray {
        reads++
        bytes += length
        return reader.readAt(position, length)
    }
}

/**
 * R85-7's bounds. The window size is not here: `DocumentSniff.WINDOW` is its only home.
 * [chunkBytes] is the most the fetch holds of the body at once, beside the two windows.
 */
data class FetchLimits(
    val maxBytes: Long = MAX_ATTACHMENT_BYTES,
    val maxRedirects: Int = 5,
    val overallMillis: Long = 600_000,
    val chunkBytes: Int = 65_536,
) {
    init {
        require(maxBytes >= 0 && maxRedirects >= 0 && overallMillis > 0 && chunkBytes > 0)
    }
}

sealed interface FetchOutcome {
    /**
     * The bytes are staged, proven by [mimeType] (sniffed, never declared) and measured by the fetch's own
     * count ([sizeBytes]) and digest ([sha256], lowercase hex). [finalUrl] is the last hop's URL and lives
     * only in memory; [toString] never prints it, nor the staging file.
     */
    data class Fetched(
        val staged: StagingFile,
        val finalUrl: String,
        val mimeType: String,
        val sizeBytes: Long,
        val sha256: String,
    ) : FetchOutcome {
        override fun toString() = "Fetched(mimeType=$mimeType, sizeBytes=$sizeBytes, sha256=$sha256)"
    }

    data class Refused(val problem: FetchProblem) : FetchOutcome
}
