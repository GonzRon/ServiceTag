package com.loosecannon.servicetag.core.fetch

import java.io.IOException

/**
 * Why a link did not become a document (#85 C8): a closed set, each answered by one ratified line in
 * the sheet (§6), so a new refusal is a compile error there rather than a silent fallback.
 *
 * Cancellation is never one of these. It propagates as a `CancellationException`, as
 * `AttachmentSweep` requires of everything below a cancelled caller: a Cancel tap that came back as
 * "the download could not be completed" would be a lie.
 */
sealed interface FetchProblem {
    /** Not `https`, on the first hop or a later one, or a host outside the ASCII allowlist (R85-4, R85-15). */
    data object NotHttps : FetchProblem

    /** Userinfo in the authority: a credential is never sent (C9). */
    data object HasCredentials : FetchProblem

    /** `localhost`, or a host that resolves to any address in C9's set (R85-7, P85-19). */
    data object LocalAddress : FetchProblem

    data object Unreachable : FetchProblem
    data object Interrupted : FetchProblem
    data object TimedOut : FetchProblem
    data object TooLarge : FetchProblem
    data object Empty : FetchProblem
    data object NotADocument : FetchProblem
    data object NeedsSignIn : FetchProblem
    data class ServerError(val code: Int) : FetchProblem
    data object RedirectRefused : FetchProblem

    /** The INTERNET permission is not granted (the production phone's state, #66). */
    data object NetworkDenied : FetchProblem
}

/**
 * What a transport or resolver adapter throws, already classified. It carries its [kind] and nothing
 * else — no message, no URI, no cause — so nothing a server or a name said can reach a log or a screen
 * through it.
 */
class TransportFailure(val kind: Kind) : IOException() {
    enum class Kind { UNREACHABLE, TIMED_OUT, INTERRUPTED, DENIED }
}
