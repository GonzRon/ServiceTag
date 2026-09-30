package com.loosecannon.servicetag.fetch

import android.system.ErrnoException
import android.system.OsConstants
import com.loosecannon.servicetag.core.fetch.TransportFailure
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException

/** Where a failure happened: before the response head arrived, or while its body was being read. */
internal enum class FailurePhase { CONNECT, BODY }

/**
 * #85 C17 (the #66 M21 shape; review m7): what a platform failure means, the rules in order.
 * 1. A cancellation or an [Error] is not a transport failure: it is answered as itself, to propagate.
 * 2. An already classified [TransportFailure] keeps its kind.
 * 3. A timeout is `TIMED_OUT`, in either phase.
 * 4. A [SecurityException], or `EACCES`/`EPERM` in the chain ([errnoDenied]), is `DENIED` **only** when the
 *    permission reads as not granted; otherwise it falls through to rule 5. Conservative on purpose: on stock
 *    Android, where the permission is install-time, the denial is never shown.
 * 5. Anything else — an unknown host, a refused or unroutable connect, a TLS failure, any other I/O failure, and any
 *    other throwable the platform raises for a host it rejects — is `UNREACHABLE` before the response and
 *    `INTERRUPTED` in the body.
 *
 * The answer is a fresh [TransportFailure], which carries its kind and nothing else: the platform's message and
 * cause, which may name the URL or the host, go nowhere.
 */
internal fun transportFailureOf(
    failure: Throwable,
    phase: FailurePhase,
    permissionGranted: () -> Boolean,
    errnoDenied: (Throwable) -> Boolean = ::errnoDeniedIn,
): Throwable = when {
    failure is CancellationException || failure is Error -> failure
    failure is TransportFailure -> failure
    failure is SocketTimeoutException -> TransportFailure(TransportFailure.Kind.TIMED_OUT)
    (failure is SecurityException || errnoDenied(failure)) && !permissionGranted() ->
        TransportFailure(TransportFailure.Kind.DENIED)
    phase == FailurePhase.CONNECT -> TransportFailure(TransportFailure.Kind.UNREACHABLE)
    else -> TransportFailure(TransportFailure.Kind.INTERRUPTED)
}

/**
 * `EACCES` or `EPERM` anywhere in [failure]'s cause chain (the `bindErrnoOf` precedent in `LoopbackApiServer`).
 * Thin on purpose: on the JVM `ErrnoException` and `OsConstants` are stubs, so only a device can show it true.
 */
internal fun errnoDeniedIn(failure: Throwable): Boolean {
    var link: Throwable? = failure
    repeat(MAX_CAUSE_DEPTH) {
        val current = link ?: return false
        if (current is ErrnoException) return current.errno == OsConstants.EACCES || current.errno == OsConstants.EPERM
        link = current.cause
    }
    return false
}

/** A cause chain longer than this is a cycle, not a history. */
private const val MAX_CAUSE_DEPTH = 16
