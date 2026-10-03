package com.loosecannon.servicetag.core.fetch

import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.references.ReferenceUris

/**
 * The hop rule (#85 C9; R85-4, R85-7 amended, R85-15), asked of every hop — the first included —
 * immediately before that hop's GET.
 *
 * **The invariant:** the host ServiceTag validates is unambiguously the host the networking layer
 * connects to. No browser-compatible reading of an odd authority is attempted: a platform HTTP stack
 * also ends an authority at `\`, percent-decodes a host and IDN-maps it (fullwidth digits and dots fold
 * to ASCII), so any host that could be read two ways is refused here, before resolution or any request.
 * Nothing is decoded, IDN-mapped or normalised beyond ASCII lowercasing and, for the `localhost` test
 * only, one trailing dot.
 */
class HopPolicy(private val resolver: HostResolver) {

    private val links = LinkLaunchPolicy()

    /**
     * The rule without resolution, in order: not `https` → [FetchProblem.NotHttps]; userinfo →
     * [FetchProblem.HasCredentials]; an authority outside the ASCII allowlist → [FetchProblem.NotHttps];
     * `localhost` or `*.localhost` → [FetchProblem.LocalAddress]. The row state asks this (C20), so it
     * never resolves.
     */
    fun staticProblem(url: String): FetchProblem? {
        if (links.schemeOf(url) != "https") return FetchProblem.NotHttps
        if (ReferenceUris.hasUserInfo(url)) return FetchProblem.HasCredentials
        val authority = ReferenceUris.authorityOf(url) ?: return FetchProblem.NotHttps
        if (!isAllowedAuthority(authority)) return FetchProblem.NotHttps
        val name = ReferenceUris.asciiLowercase(ReferenceUris.hostOf(url) ?: return FetchProblem.NotHttps)
            .removeSuffix(".")
        if (name == "localhost" || name.endsWith(".localhost")) return FetchProblem.LocalAddress
        return null
    }

    /**
     * [staticProblem], then the host resolved: a [TransportFailure] or an empty answer →
     * [FetchProblem.Unreachable] (`DENIED` → [FetchProblem.NetworkDenied]), then
     * [FetchProblem.LocalAddress] when **any** address is in C9's set ([isForbiddenAddress]). A resolver
     * failure is never "allowed"; a cancellation propagates.
     *
     * The resolver is asked for the host as the platform will look it up: ASCII-lowercased, an IPv6
     * literal unbracketed, a trailing dot kept (an absolute name stays absolute).
     */
    suspend fun check(url: String): FetchProblem? {
        staticProblem(url)?.let { return it }
        val host = ReferenceUris.asciiLowercase(ReferenceUris.hostOf(url) ?: return FetchProblem.NotHttps)
        val addresses = try {
            resolver.resolve(host)
        } catch (e: TransportFailure) {
            return if (e.kind == TransportFailure.Kind.DENIED) FetchProblem.NetworkDenied else FetchProblem.Unreachable
        }
        if (addresses.isEmpty()) return FetchProblem.Unreachable
        return if (addresses.any(::isForbiddenAddress)) FetchProblem.LocalAddress else null
    }
}

/**
 * The authority allowlist (R85-15, review M1): a host plus an optional port (`:` and a canonical decimal
 * 1–65535, no leading zero). The host is exactly one of a DNS name in ASCII label syntax, a canonical dotted quad, or a
 * bracketed IPv6 literal equal to its RFC 5952 text. Anything else — a Unicode character, a `%` escape, a
 * `\`, whitespace or a control character, an empty label or host, a malformed port — is refused.
 */
internal fun isAllowedAuthority(authority: String): Boolean {
    val hostEnd = if (authority.startsWith("[")) authority.indexOf(']') + 1 else authority.indexOf(':')
    if (authority.startsWith("[") && hostEnd == 0) return false
    val host = if (hostEnd < 0) authority else authority.substring(0, hostEnd)
    val port = if (hostEnd < 0) "" else authority.substring(hostEnd)
    if (port.isNotEmpty() && !isPort(port)) return false
    return if (host.startsWith("[")) isCanonicalIpv6(host.substring(1, host.length - 1)) else isAllowedName(host)
}

/** `:` then a canonical decimal 1–65535: 1–5 ASCII digits, no leading zero (controller ruling, fix round 1). */
private fun isPort(port: String): Boolean {
    val digits = port.removePrefix(":")
    return port.startsWith(":") && digits.length in 1..5 && digits.all(::isAsciiDigit) && digits[0] != '0' &&
        digits.toInt() in 1..65_535
}

/**
 * A DNS name: dot-separated labels of ASCII letters, digits, `-` and `_` (the shipped underscore reason,
 * `AddReference`), each 1–63 characters, not starting or ending with `-`, the whole at most 253, one
 * trailing dot tolerated; `xn--` punycode labels are ordinary labels. A host whose last label is all
 * digits, or with any label starting `0x`, is an IPv4 candidate and must be a canonical dotted quad, so
 * `127.1`, `2130706433`, `0177.0.0.1` and `0x7f.0.0.1` are refused.
 */
private fun isAllowedName(host: String): Boolean {
    if (host.isEmpty() || !host.all { isAsciiLetterOrDigit(it) || it == '-' || it == '_' || it == '.' }) return false
    val name = host.removeSuffix(".")
    val labels = name.split('.')
    val lastIsNumeric = labels.last().let { it.isNotEmpty() && it.all(::isAsciiDigit) }
    if (lastIsNumeric || labels.any { it.startsWith("0x", ignoreCase = true) }) return canonicalIpv4(host) != null
    return name.length <= 253 && labels.all { it.length in 1..63 && !it.startsWith('-') && !it.endsWith('-') }
}

/** A canonical dotted quad's four bytes: four decimal parts, each 0–255, no leading zero; else null. */
private fun canonicalIpv4(text: String): ByteArray? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    if (parts.any { it.isEmpty() || it.length > 3 || !it.all(::isAsciiDigit) || (it.length > 1 && it[0] == '0') }) {
        return null
    }
    val values = parts.map { it.toInt() }
    return if (values.all { it <= 255 }) ByteArray(4) { values[it].toByte() } else null
}

/**
 * RFC 4291 text — hex groups, at most one `::`, an optional canonical trailing dotted quad, no zone id —
 * that equals its own RFC 5952 text: lowercase, no leading zeros, the longest run of two or more zero
 * groups compressed (the first on a tie). A trailing quad is compared in the same mixed notation.
 */
private fun isCanonicalIpv6(text: String): Boolean {
    if (text.isEmpty() || !text.all { isHexDigit(it) || it == ':' || it == '.' }) return false
    val lastColon = text.lastIndexOf(':')
    val quad = if ('.' in text) text.substring(lastColon + 1) else null
    if (quad != null && canonicalIpv4(quad) == null) return false
    val hex = if (quad == null) text else text.substring(0, lastColon + 1) + "0:0"   // the quad's two groups
    val halves = hex.split("::")
    if (halves.size > 2) return false
    val head = if (halves[0].isEmpty()) emptyList() else halves[0].split(':')
    val tail = if (halves.size < 2 || halves[1].isEmpty()) emptyList() else halves[1].split(':')
    if ((head + tail).any { it.length !in 1..4 || !it.all(::isHexDigit) }) return false   // a '.' only in the quad
    val count = head.size + tail.size
    if (if (halves.size == 2) count > 7 else count != 8) return false
    val groups = head.map { it.toInt(16) } + List(8 - count) { 0 } + tail.map { it.toInt(16) }
    if (quad == null) return text == rfc5952(groups)
    val prefix = rfc5952(groups.take(6))
    return text == prefix + (if (prefix.endsWith("::")) "" else ":") + quad
}

private fun rfc5952(groups: List<Int>): String {
    var bestStart = -1
    var bestLength = 1
    var i = 0
    while (i < groups.size) {
        val start = i
        while (i < groups.size && groups[i] == 0) i++
        if (i - start > bestLength) { bestStart = start; bestLength = i - start }
        if (i == start) i++
    }
    val hex = groups.map { it.toString(16) }
    if (bestStart < 0) return hex.joinToString(":")
    return hex.subList(0, bestStart).joinToString(":") + "::" +
        hex.subList(bestStart + bestLength, hex.size).joinToString(":")
}

private fun isAsciiDigit(c: Char) = c in '0'..'9'

private fun isAsciiLetterOrDigit(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || isAsciiDigit(c)

private fun isHexDigit(c: Char) = isAsciiDigit(c) || c in 'a'..'f' || c in 'A'..'F'
