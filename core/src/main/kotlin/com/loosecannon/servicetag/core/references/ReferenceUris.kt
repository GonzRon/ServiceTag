package com.loosecannon.servicetag.core.references

/**
 * The shared, text-only readings of a reference URI (#85 C8): one home, so the host `AddReference`
 * checks for structural validity and the host the downloader's hop rule checks are read by the same
 * code.
 *
 * Deliberately not the JDK's `URI` parser: it answers "no host" for an authority it cannot read as
 * a server name — an underscore in a hostname is enough — which would refuse URIs the phone opens
 * perfectly well. The authority is read the way `AddReference` always read it: from `//` to the first
 * `/`, `?` or `#`. Nothing here decodes, IDN-maps or otherwise rewrites a host; whether a host is one
 * the network may be asked for is `HopPolicy`'s question, never this object's.
 */
object ReferenceUris {

    /** The authority's host: userinfo and port dropped, an IPv6 literal unbracketed; null when there is none. */
    fun hostOf(uri: String): String? {
        val authority = authorityOf(uri) ?: return null
        val hostAndPort = authority.substringAfterLast('@')          // userinfo is not the host
        val host = if (hostAndPort.startsWith("[")) {
            hostAndPort.substringBefore(']').removePrefix("[")       // an IPv6 literal
        } else {
            hostAndPort.substringBefore(':')                         // the port is not the host
        }
        return host.ifEmpty { null }
    }

    /** An `@` inside the authority: userinfo, which a fetch never sends (C9). */
    fun hasUserInfo(uri: String): Boolean = authorityOf(uri)?.contains('@') == true

    /**
     * `scheme://authority/path` and nothing else (R85-2, R85-14): the scheme and the host ASCII-lowercased,
     * the userinfo dropped, the query and the fragment dropped, and in **each** path segment everything from
     * its first `;` to the segment's end dropped — never a cut at the first `;` of the whole path, so
     * `/a;x=1/b;jsessionid=AB12/m.pdf` keeps `/a/b/m.pdf`. The ordinary path is kept as sent (no decoding,
     * no case change) and an empty one is `/`. Null when there is no authority or no host.
     */
    fun destinationOf(uri: String): String? {
        val scheme = SCHEME.find(uri) ?: return null
        val authority = authorityOf(uri) ?: return null
        val hostAndPort = authority.substringAfterLast('@')
        if (hostAndPort.isEmpty()) return null
        val path = uri.substring(scheme.value.length + 2 + authority.length)
            .takeWhile { it != '?' && it != '#' }
            .ifEmpty { "/" }
            .split('/')
            .joinToString("/") { segment -> segment.substringBefore(';') }
        return asciiLowercase(scheme.value) + "//" + asciiLowercase(hostAndPort) + path
    }

    /**
     * The authority: from the `//` after the scheme to the first `/`, `?` or `#`. Null without a scheme,
     * without a `//`, or when it is empty.
     */
    internal fun authorityOf(uri: String): String? {
        val scheme = SCHEME.find(uri) ?: return null
        val rest = uri.substring(scheme.value.length)
        if (!rest.startsWith("//")) return null
        val afterSlashes = rest.substring(2)
        val end = afterSlashes.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (end < 0) afterSlashes else afterSlashes.substring(0, end)
        return authority.ifEmpty { null }
    }

    /** A-Z to a-z and nothing else: no locale, no Unicode case mapping, no length change. */
    internal fun asciiLowercase(text: String): String =
        buildString(text.length) { for (c in text) append(if (c in 'A'..'Z') c + ('a' - 'A') else c) }

    /** RFC 3986's scheme production and its colon, as `LinkLaunchPolicy.schemeOf` reads it, but untrimmed. */
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")
}
