package com.loosecannon.servicetag.core.fetch

import com.loosecannon.servicetag.core.model.MimeTypes
import com.loosecannon.servicetag.core.references.ReferenceUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * C10 (#85; R85-4, R85-5, R85-7, R85-8): one document link downloaded into app-private staging, hop by
 * hop, the whole run inside [FetchLimits.overallMillis] (then [FetchProblem.TimedOut]).
 *
 * Every hop, the first included, passes [HopPolicy.check] immediately before its GET, so https and the
 * address rule hold on each one. At most [FetchLimits.maxRedirects] redirects are followed. A 200 or 203
 * is a body; its declared type and length are judged before a byte is read, and the declared type is
 * never more than that: the bytes decide the kind, by their first and last windows. The body is
 * streamed once, counted (the count, never `Content-Length`, is authoritative), digested and windowed on
 * the way in. The staged file is read back only by at most one bounded, read-only container inspection,
 * after the stream is done and only for a ZIP head the windows left undecided (C31).
 *
 * **Cleanup.** Every outcome but [FetchOutcome.Fetched], every exception and every cancellation discards
 * the staging file, and every response is closed. A kept file is either handed to the caller or
 * discarded, even when a cancel or the deadline lands after the body is done but before [run] returns. **Cancellation reaches a blocked read:** the body is
 * read in a child on [io], and when this call is cancelled (or the deadline passes) the response is
 * closed at once, which unblocks the read; a failure raised after that rethrows the cancellation. A
 * cancel is never a problem.
 *
 * It sends nothing but the URL (the transport owns its three request properties, R85-13), logs nothing,
 * and keeps the URL nowhere but the returned [FetchOutcome.Fetched.finalUrl]. The progress callback runs
 * on [io], at most once per chunk, with the running count and the declared length (null when undeclared).
 */
class FetchDocument(
    private val transport: DocumentTransport,
    private val hops: HopPolicy,
    private val staging: StagingArea,
    private val limits: FetchLimits = FetchLimits(),
    private val io: CoroutineContext = Dispatchers.IO,
) {
    /**
     * The block never answers null, so only this deadline reads as [FetchProblem.TimedOut]; an outer cancel
     * propagates. The deadline and a cancel are asynchronous: either can land after `download` kept the file
     * but before this returns, which drops the finished outcome (review m1). The hand-over remembers the kept
     * file, and the `finally` discards it unless it is the answer.
     */
    suspend fun run(url: String, onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> }): FetchOutcome {
        val handOver = HandOver()
        var answer: FetchOutcome? = null
        try {
            answer = kotlinx.coroutines.withTimeoutOrNull(limits.overallMillis) { follow(url, onProgress, handOver) }
                ?: FetchOutcome.Refused(FetchProblem.TimedOut)
            return answer
        } finally {
            handOver.kept?.let { if (it !== answer) it.staged.discard() }
        }
    }

    /** The one [FetchOutcome.Fetched] this run kept, once `download` has decided to keep it. */
    private class HandOver {
        var kept: FetchOutcome.Fetched? = null
    }

    private suspend fun follow(first: String, onProgress: (Long, Long?) -> Unit, handOver: HandOver): FetchOutcome {
        var url = first
        var redirects = 0
        while (true) {
            hops.check(url)?.let { return refused(if (redirects == 0) it else onRedirectHop(it)) }
            val response = try {
                transport.get(url)
            } catch (e: TransportFailure) {
                currentCoroutineContext().ensureActive()
                return refused(problemOf(e))
            }
            try {
                when (response.status) {
                    in REDIRECTS -> {
                        if (redirects == limits.maxRedirects) return refused(FetchProblem.RedirectRefused)
                        url = response.location?.let { resolve(url, it) } ?: return refused(FetchProblem.RedirectRefused)
                        redirects++
                    }
                    401, 403, 407 -> return refused(FetchProblem.NeedsSignIn)
                    200, 203 -> return download(url, response, onProgress, handOver)
                    else -> return refused(FetchProblem.ServerError(response.status))
                }
            } finally {
                response.close()
            }
        }
    }

    /** Steps 5–9: the headers, then one streamed pass, then the sniff and, for a container, the inspection. */
    private suspend fun download(
        url: String,
        response: TransportResponse,
        onProgress: (Long, Long?) -> Unit,
        handOver: HandOver,
    ): FetchOutcome {
        val declared = response.contentType?.let(MimeTypes::normalise)
        if (declared != null && declared in NOT_DOCUMENTS) return refused(FetchProblem.NotADocument)
        if ((response.contentLength ?: 0L) > limits.maxBytes) return refused(FetchProblem.TooLarge)
        val staged = try {
            staging.create()
        } catch (e: IOException) {
            return refused(FetchProblem.Interrupted)
        }
        var keep = false
        try {
            val streamed = coroutineScope {
                val reader = async(io) { stream(staged, response, onProgress) }
                try {
                    reader.await()
                } catch (e: CancellationException) {
                    response.close()
                    throw e
                }
            }
            val outcome = when (streamed) {
                is Streamed.Failed -> refused(streamed.problem)
                is Streamed.Done -> judged(url, declared, staged, streamed)
            }
            if (outcome is FetchOutcome.Fetched) handOver.kept = outcome
            keep = outcome is FetchOutcome.Fetched
            return outcome
        } finally {
            if (!keep) staged.discard()
        }
    }

    /** The blocking chunk loop, on [io]. Holds one chunk and the two windows, nothing more. */
    private suspend fun stream(staged: StagingFile, response: TransportResponse, onProgress: (Long, Long?) -> Unit): Streamed {
        val windows = Windows()
        val digest = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(limits.chunkBytes)
        var count = 0L
        try {
            staged.output().use { out ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = response.body.read(chunk, 0, chunk.size)
                    if (n < 0) break
                    if (count + n > limits.maxBytes) return Streamed.Failed(FetchProblem.TooLarge)
                    out.write(chunk, 0, n)
                    digest.update(chunk, 0, n)
                    windows.take(chunk, n)
                    count += n
                    onProgress(count, response.contentLength)
                }
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            return Streamed.Failed(if (e is TransportFailure) problemOf(e) else FetchProblem.Interrupted)
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        return Streamed.Done(count, sha256, windows.head(), windows.tail())
    }

    /** [declared] is the normalised declared type: for a ZIP head it can only refuse (C28 (7)), never accept. */
    private suspend fun judged(url: String, declared: String?, staged: StagingFile, done: Streamed.Done): FetchOutcome {
        if (done.size == 0L) return refused(FetchProblem.Empty)
        if (DocumentSniff.isZip(done.head) && OoxmlExclusions.refuses(declared, extensionOf(url))) {
            return refused(FetchProblem.NotADocument)
        }
        val proven = try {
            DocumentSniff.classify(done.size, done.head, done.tail) ?: inspected(staged, done)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            return refused(FetchProblem.Interrupted)
        } catch (e: InspectionOverBudget) {
            return refused(FetchProblem.NotADocument) // C31; ContainerInspect already answers null for it
        }
        val mimeType = proven ?: return refused(FetchProblem.NotADocument)
        return FetchOutcome.Fetched(staged, url, mimeType, done.size, done.sha256)
    }

    /**
     * C31: the one bounded, read-only inspection of the staged file, once the stream is done and only for a
     * head the windows left undecided that [ContainerInspect] is for. It reads a file, so it runs on [io].
     */
    private suspend fun inspected(staged: StagingFile, done: Streamed.Done): String? {
        if (!ContainerInspect.inspects(done.head)) return null
        return withContext(io) { ContainerInspect.classify(done.size, done.head, done.tail, BoundedInspection(staged.reader())) }
    }

    private sealed interface Streamed {
        class Done(val size: Long, val sha256: String, val head: ByteArray, val tail: ByteArray) : Streamed
        class Failed(val problem: FetchProblem) : Streamed
    }

    /** The first and the last `DocumentSniff.WINDOW` bytes, kept while streaming: no second pass. */
    private class Windows {
        private val size = DocumentSniff.WINDOW
        private val head = ByteArray(size)
        private var headSize = 0
        private val tail = ByteArray(size)
        private var tailSize = 0

        fun take(chunk: ByteArray, n: Int) {
            if (headSize < size) {
                val k = minOf(size - headSize, n)
                chunk.copyInto(head, headSize, 0, k)
                headSize += k
            }
            if (n >= size) {
                chunk.copyInto(tail, 0, n - size, n)
                tailSize = size
            } else {
                val kept = minOf(tailSize, size - n)
                tail.copyInto(tail, 0, tailSize - kept, tailSize)
                chunk.copyInto(tail, kept, 0, n)
                tailSize = kept + n
            }
        }

        fun head() = head.copyOf(headSize)

        fun tail() = tail.copyOf(tailSize)
    }

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)

        /** Declared types that fail at once (R85-5): a page, never a document. */
        val NOT_DOCUMENTS = setOf("text/html", "application/xhtml+xml", "text/plain")

        fun refused(problem: FetchProblem) = FetchOutcome.Refused(problem)

        /** The final URL's last path segment's extension, lowercased, after C8's stripping; null when it has none. */
        fun extensionOf(url: String): String? =
            ReferenceUris.destinationOf(url)?.substringAfterLast('/')?.takeIf { '.' in it }?.substringAfterLast('.')?.lowercase()

        /** C10 step 3: a redirect hop keeps these three meanings; any other problem is a refused redirect. */
        fun onRedirectHop(problem: FetchProblem): FetchProblem = when (problem) {
            FetchProblem.LocalAddress, FetchProblem.Unreachable, FetchProblem.NetworkDenied -> problem
            else -> FetchProblem.RedirectRefused
        }

        fun problemOf(failure: TransportFailure): FetchProblem = when (failure.kind) {
            TransportFailure.Kind.UNREACHABLE -> FetchProblem.Unreachable
            TransportFailure.Kind.TIMED_OUT -> FetchProblem.TimedOut
            TransportFailure.Kind.INTERRUPTED -> FetchProblem.Interrupted
            TransportFailure.Kind.DENIED -> FetchProblem.NetworkDenied
        }

        /**
         * [location] resolved against [base] by RFC 3986 §5.2, or null when it is blank or does not parse.
         * The JDK's URI parser follows RFC 2396, so its readings are corrected where they differ: an empty
         * base path is `/` first (else `https://h.example` + `x.pdf` reads `https://h.examplex.pdf`); a
         * query-only reference keeps the base's whole path (review m4); and `..` segments above the root
         * are dropped. It parses a Location only: every host decision is the next hop's check.
         */
        fun resolve(base: String, location: String): String? {
            if (location.isBlank()) return null
            return try {
                val reference = URI(location)
                val rooted = withRootPath(base)
                when {
                    reference.isAbsolute -> location
                    reference.rawAuthority == null && reference.rawPath.isNullOrEmpty() && reference.rawQuery != null ->
                        rooted.substringBefore('#').substringBefore('?') + location
                    else -> withoutDotsAboveRoot(URI(rooted).resolve(reference).toString())
                }
            } catch (e: Exception) {
                null // an unparseable Location or base; nothing here suspends, so no cancellation is swallowed
            }
        }

        /** The index where [url]'s path starts: the end of its authority. */
        private fun pathStart(url: String): Int {
            val authority = url.indexOf("//").let { if (it < 0) return 0 else it + 2 }
            return url.indexOfAny(charArrayOf('/', '?', '#'), authority).let { if (it < 0) url.length else it }
        }

        private fun withRootPath(url: String): String {
            val at = pathStart(url)
            return if (at < url.length && url[at] == '/') url else url.substring(0, at) + "/" + url.substring(at)
        }

        private fun withoutDotsAboveRoot(url: String): String {
            val at = pathStart(url)
            val end = url.indexOfAny(charArrayOf('?', '#'), at).let { if (it < 0) url.length else it }
            var path = url.substring(at, end)
            while (path == "/.." || path.startsWith("/../")) path = if (path == "/..") "/" else path.substring(3)
            return url.substring(0, at) + path + url.substring(end)
        }
    }
}
