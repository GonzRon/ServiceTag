package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.fetch.isAllowedAuthority
import com.loosecannon.servicetag.core.references.ReferenceUris

/**
 * #16 (C8) — why a Home Assistant address is refused: a code, never a sentence. Every member draws the one
 * `ENDPOINT_REFUSED` sentence (P16-12) on the phone; the code says which rule refused it.
 */
enum class EndpointProblem {
    /** Not `http://` or `https://` followed by an authority (rule 1). */
    NOT_HTTP_OR_HTTPS,

    /** A userinfo (`name@`) before the host (rule 1). */
    USERINFO,

    /** A path other than nothing or `/`, a query, or a fragment: the address is an origin only (rule 1). */
    NOT_AN_ORIGIN,

    /** The host or the port fails #85's shared authority allowlist (rule 2). */
    AUTHORITY,

    /** `localhost` or a name under `.localhost`, under either scheme (rule 3). */
    LOCALHOST,

    /** `http` to a DNS name: cleartext goes to a private IPv4 literal only (rule 4). */
    HTTP_TO_A_NAME,

    /** An IPv6 literal, under either scheme (rules 4 and 5). */
    IPV6_LITERAL,

    /** An IPv4 literal outside 10.0.0.0/8, 172.16.0.0/12 and 192.168.0.0/16, under either scheme (rules 4, 5). */
    NOT_A_PRIVATE_IPV4,
}

enum class HaScheme(val text: String) { HTTP("http"), HTTPS("https") }

/** [NAME] is only ever https, and still needs C19 step 1b: every address it resolves to is [isPrivateLanAddress]. */
enum class HaHostKind { NAME, PRIVATE_IPV4 }

/**
 * An accepted address: [host] ASCII-lowercased, [canonical] `scheme://authority` with no slash (C19 step 2). The
 * constructor is `internal` so only [HaEndpointPolicy.classify] makes one: an endpoint nobody checked is not one.
 */
@ConsistentCopyVisibility
data class HaEndpoint internal constructor(
    val scheme: HaScheme,
    val host: String,
    val hostKind: HaHostKind,
    val canonical: String,
) {
    /** No host and no address: an endpoint is not a log line (C19). */
    override fun toString(): String = "HaEndpoint(scheme=$scheme, hostKind=$hostKind)"
}

sealed interface EndpointCheck {
    data class Allowed(val endpoint: HaEndpoint) : EndpointCheck
    data class Refused(val problem: EndpointProblem) : EndpointCheck
}

/**
 * #16 (C8; R16-6, R16-Q-B (a) and (c)) — the owner's address rule, pure: no resolution, no network, no platform
 * type. The rules: rule 1, the scheme is `http` or `https` (ASCII case-insensitive), no userinfo, nothing after the
 * authority but one `/`; rule 2, the authority passes [isAllowedAuthority], #85's allowlist, called as shipped;
 * rule 3, `localhost` and `*.localhost` are refused; rule 4, `http` only to an IPv4 literal in RFC 1918; rule 5,
 * `https` to a DNS name or an RFC 1918 IPv4 literal. Rules 1 and 2 run first; after them the code checks the IPv6
 * and IPv4 literals before rule 3, which is safe because those steps are disjoint (a literal is never `localhost`).
 * Nothing is trimmed, decoded or IDN-mapped. #85's hop rule is
 * neither called nor changed: the downloader stays https-only. The saved address (C17) and every request (C19)
 * ask this again.
 */
object HaEndpointPolicy {

    fun classify(url: String): EndpointCheck {
        val authority = ReferenceUris.authorityOf(url) ?: return EndpointCheck.Refused(EndpointProblem.NOT_HTTP_OR_HTTPS)
        val schemeText = url.substringBefore(':')
        val scheme = HaScheme.entries.firstOrNull { it.text == ReferenceUris.asciiLowercase(schemeText) }
            ?: return EndpointCheck.Refused(EndpointProblem.NOT_HTTP_OR_HTTPS)
        if (ReferenceUris.hasUserInfo(url)) return EndpointCheck.Refused(EndpointProblem.USERINFO)
        val rest = url.substring(schemeText.length + "://".length + authority.length)
        if (rest.isNotEmpty() && rest != "/") return EndpointCheck.Refused(EndpointProblem.NOT_AN_ORIGIN)
        if (!isAllowedAuthority(authority)) return EndpointCheck.Refused(EndpointProblem.AUTHORITY)
        if (authority.startsWith("[")) return EndpointCheck.Refused(EndpointProblem.IPV6_LITERAL)
        val host = ReferenceUris.asciiLowercase(
            ReferenceUris.hostOf(url) ?: return EndpointCheck.Refused(EndpointProblem.AUTHORITY),
        )
        val canonical = scheme.text + "://" + ReferenceUris.asciiLowercase(authority)
        val quad = ipv4LiteralBytes(host)
        if (quad != null) {
            if (!isPrivateLanAddress(quad)) return EndpointCheck.Refused(EndpointProblem.NOT_A_PRIVATE_IPV4)
            return EndpointCheck.Allowed(HaEndpoint(scheme, host, HaHostKind.PRIVATE_IPV4, canonical))
        }
        val name = host.removeSuffix(".")
        if (name == "localhost" || name.endsWith(".localhost")) return EndpointCheck.Refused(EndpointProblem.LOCALHOST)
        return when (scheme) {
            HaScheme.HTTP -> EndpointCheck.Refused(EndpointProblem.HTTP_TO_A_NAME)
            HaScheme.HTTPS -> EndpointCheck.Allowed(HaEndpoint(scheme, host, HaHostKind.NAME, canonical))
        }
    }

    /** Null when [url] is allowed; else the rule that refused it. */
    fun check(url: String): EndpointProblem? = when (val result = classify(url)) {
        is EndpointCheck.Allowed -> null
        is EndpointCheck.Refused -> result.problem
    }

    /** The scheme and host ASCII-lowercased, a lone `/` dropped; null when [check] refuses [url]. */
    fun canonical(url: String): String? = when (val result = classify(url)) {
        is EndpointCheck.Allowed -> result.endpoint.canonical
        is EndpointCheck.Refused -> null
    }
}

/**
 * #16 (C8 rule 5; C-9) — a private network address: 4 bytes in 10.0.0.0/8, 172.16.0.0/12 or 192.168.0.0/16, or
 * 16 bytes in `fc00::/7`. Nothing else: no loopback, link-local, shared (100.64.0.0/10), documentation or
 * IPv4-mapped address, and any other length. C8 asks it of an IPv4 literal; C19 step 1b of every resolved address.
 */
fun isPrivateLanAddress(address: ByteArray): Boolean {
    if (address.size == 16) return (address[0].toInt() and 0xfe) == 0xfc
    if (address.size != 4) return false
    val a = address[0].toInt() and 0xff
    val b = address[1].toInt() and 0xff
    return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
}

/**
 * Called only after [isAllowedAuthority] passed: under that precondition a host of four 1–3-digit parts is a
 * canonical dotted quad (the allowlist sends every all-digit last label through its quad rule), and this returns
 * its four bytes; any other host is a name, and this returns null.
 */
private fun ipv4LiteralBytes(host: String): ByteArray? {
    val parts = host.split('.')
    if (parts.size != 4 || parts.any { part -> part.length !in 1..3 || part.any { it !in '0'..'9' } }) return null
    val values = parts.map { it.toInt() }
    return if (values.all { it <= 255 }) ByteArray(4) { values[it].toByte() } else null
}
