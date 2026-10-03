package com.loosecannon.servicetag.core.seasonsync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * #16 (C8; rows 10–13) — the owner's address rule (R16-Q-B): http only to an RFC 1918 IPv4 literal, https to a name
 * or an RFC 1918 IPv4 literal, an origin and nothing more. Every address is fictional: the accepted fixtures
 * `http://192.168.0.10:8123` and `https://ha.example:8123`, the refused `http://192.0.2.10:8123`, and range edges.
 */
class HaEndpointPolicyTest {

    /** Every [urls] answers [expected] (null: allowed); all mismatches are listed in one failure. */
    private fun assertEach(expected: EndpointProblem?, urls: List<String>) {
        val wrong = urls.mapNotNull { url ->
            val actual = HaEndpointPolicy.check(url)
            if (actual == expected) null else "<$url> → ${actual ?: "allowed"}"
        }
        if (wrong.isNotEmpty()) fail("expected $expected, but ${wrong.joinToString("; ")}")
    }

    private fun allowed(url: String): HaEndpoint =
        when (val result = HaEndpointPolicy.classify(url)) {
            is EndpointCheck.Allowed -> result.endpoint
            is EndpointCheck.Refused -> fail("<$url> refused: ${result.problem}")
        }

    private fun v4(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    /** Eight 16-bit groups, written out: no parser, no platform type. */
    private fun v6(vararg groups: Int): ByteArray {
        require(groups.size == 8)
        return ByteArray(16) { (groups[it / 2] shr (if (it % 2 == 0) 8 else 0)).toByte() }
    }

    private val privateEdges = listOf(
        "10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.254", "192.168.0.1", "192.168.255.254",
        "10.0.0.0", "10.255.255.255", "172.16.0.0", "172.31.255.255", "192.168.0.0", "192.168.255.255",
    )

    /** Each field on its own: a whole endpoint prints redacted, so a mismatch would read as two equal texts. */
    private fun assertEndpoint(scheme: HaScheme, host: String, kind: HaHostKind, canonical: String, actual: HaEndpoint) {
        assertEquals(scheme, actual.scheme, "scheme")
        assertEquals(host, actual.host, "host")
        assertEquals(kind, actual.hostKind, "host kind")
        assertEquals(canonical, actual.canonical, "canonical")
    }

    // Row 10 — http to an RFC 1918 literal.

    @Test
    fun theCanonicalHttpFixtureIsAllowed() {
        val endpoint = allowed("http://192.168.0.10:8123")
        assertEndpoint(HaScheme.HTTP, "192.168.0.10", HaHostKind.PRIVATE_IPV4, "http://192.168.0.10:8123", endpoint)
        assertEquals("http://192.168.0.10:8123", HaEndpointPolicy.canonical("http://192.168.0.10:8123"))
        assertFalse("192.168" in endpoint.toString(), "an endpoint's text names no address: $endpoint")
    }

    @Test
    fun httpToEachPrivateRangesEdgesIsAllowed() =
        assertEach(null, privateEdges.flatMap { listOf("http://$it", "http://$it:8123", "http://$it/") })

    // Row 11 — http refused everywhere else.

    @Test
    fun httpToANameIsRefused() = assertEach(
        EndpointProblem.HTTP_TO_A_NAME,
        listOf("http://ha.example:8123", "http://ha.example", "HTTP://HA.Example:8123/", "http://ha_box.example"),
    )

    @Test
    fun httpToEveryOtherIpv4IsRefused() = assertEach(
        EndpointProblem.NOT_A_PRIVATE_IPV4,
        listOf(
            "192.0.2.10", "172.32.0.1", "172.15.255.254", "192.169.0.1", "11.0.0.1", "127.0.0.1", "169.254.1.1",
            "100.64.0.1", "0.0.0.0", "255.255.255.255", "173.16.0.1", "171.31.0.1", "193.168.0.1", "191.168.0.1",
            "192.167.255.254", "9.255.255.255",
        ).map { "http://$it:8123" },
    )

    @Test
    fun httpToAnIpv6LiteralIsRefused() = assertEach(
        EndpointProblem.IPV6_LITERAL,
        listOf("http://[fd00::1]:8123", "http://[::1]", "http://[fe80::1]", "http://[::ffff:192.168.0.10]:8123"),
    )

    // Row 12 — https.

    @Test
    fun httpsToANameOrAPrivateIpv4LiteralIsAllowed() {
        assertEndpoint(HaScheme.HTTPS, "ha.example", HaHostKind.NAME, "https://ha.example:8123", allowed("https://ha.example:8123"))
        assertEndpoint(
            HaScheme.HTTPS, "192.168.0.10", HaHostKind.PRIVATE_IPV4, "https://192.168.0.10:8123",
            allowed("https://192.168.0.10:8123"),
        )
        assertEach(
            null,
            listOf(
                "https://ha.example", "https://ha.example./", "https://ha_box.example:443", "https://xn--bcher-kva.example",
                "https://localhost.example", "https://notlocalhost.example",
            ) + privateEdges.map { "https://$it:8123" },
        )
    }

    @Test
    fun httpsToAPublicLiteralIsRefused() {
        assertEach(
            EndpointProblem.NOT_A_PRIVATE_IPV4,
            listOf("192.0.2.10", "198.51.100.7", "203.0.113.10:8123", "172.32.0.1", "127.0.0.1", "169.254.1.1", "100.64.0.1")
                .map { "https://$it" },
        )
        assertEach(EndpointProblem.IPV6_LITERAL, listOf("https://[fd00::1]:8123", "https://[2001:db8::10]"))
    }

    @Test
    fun isPrivateLanAddressAcceptsRfc1918AndFc00Only() {
        val inside = listOf(
            v4(10, 0, 0, 1), v4(10, 255, 255, 254), v4(172, 16, 0, 1), v4(172, 31, 255, 254), v4(192, 168, 0, 1),
            v4(192, 168, 255, 254), v6(0xfd00, 0, 0, 0, 0, 0, 0, 1), v6(0xfc00, 0, 0, 0, 0, 0, 0, 1),
            v6(0xfdff, 0xffff, 0xffff, 0xffff, 0xffff, 0xffff, 0xffff, 0xffff), v4(10, 0, 0, 0), v4(10, 255, 255, 255),
            v4(172, 16, 0, 0), v4(172, 31, 255, 255), v4(192, 168, 0, 0), v4(192, 168, 255, 255),
        )
        val outside = listOf(
            v6(0xfe80, 0, 0, 0, 0, 0, 0, 1), v6(0x2001, 0x0db8, 0, 0, 0, 0, 0, 1), v4(192, 0, 2, 10),
            v4(127, 0, 0, 1), v4(169, 254, 1, 1), v4(100, 64, 0, 1), v4(172, 32, 0, 1), v4(172, 15, 255, 254),
            v4(192, 169, 0, 1), v4(0, 0, 0, 0), v4(255, 255, 255, 255), v6(0, 0, 0, 0, 0, 0, 0, 1),
            v6(0, 0, 0, 0, 0, 0xffff, 0xc0a8, 0x000a), v6(0xfbff, 0, 0, 0, 0, 0, 0, 1), v6(0xfe00, 0, 0, 0, 0, 0, 0, 1),
            ByteArray(0), byteArrayOf(10), byteArrayOf(10, 0, 0, 1, 0), v4(173, 16, 0, 1), v4(171, 31, 0, 1),
            v4(193, 168, 0, 1), v4(191, 168, 0, 1), v4(192, 167, 255, 254), v4(9, 255, 255, 255),
        )
        for (address in inside) assertTrue(isPrivateLanAddress(address), "in: ${address.map { it.toInt() and 0xff }}")
        for (address in outside) assertFalse(isPrivateLanAddress(address), "out: ${address.map { it.toInt() and 0xff }}")
    }

    @Test
    fun localhostAndDotLocalhostAreRefusedUnderBothSchemes() = assertEach(
        EndpointProblem.LOCALHOST,
        listOf("localhost", "localhost:8123", "LOCALHOST", "localhost.", "ha.localhost", "ha.localhost:8123")
            .flatMap { listOf("http://$it", "https://$it") },
    )

    // Row 13 — the rest of the URL.

    @Test
    fun onlyHttpAndHttpsAreSchemes() = assertEach(
        EndpointProblem.NOT_HTTP_OR_HTTPS,
        listOf(
            "ftp://192.168.0.10", "ws://192.168.0.10:8123", "wss://ha.example", "httpx://ha.example", "192.168.0.10:8123",
            "ha.example:8123", "http:192.168.0.10", "https:/ha.example", "http:///192.168.0.10", " http://192.168.0.10",
            "", "https://",
        ),
    )

    @Test
    fun userinfoPathQueryAndFragmentAreRefused() {
        assertEach(
            EndpointProblem.USERINFO,
            listOf(
                "http://owner@192.168.0.10:8123", "https://owner:fictional-token-1@ha.example:8123", "https://@ha.example",
                "http://ha.example@192.168.0.10",
            ),
        )
        assertEach(
            EndpointProblem.NOT_AN_ORIGIN,
            listOf(
                "http://192.168.0.10:8123/api", "http://192.168.0.10:8123/api/", "https://ha.example:8123//",
                "https://ha.example/ha/", "http://192.168.0.10:8123?x=1", "http://192.168.0.10:8123/?",
                "https://ha.example#top", "https://ha.example:8123/#", "https://ha.example?",
            ),
        )
    }

    @Test
    fun oddAuthoritiesAreRefusedByTheSharedAllowlist() = assertEach(
        EndpointProblem.AUTHORITY,
        listOf(
            "https://ha%2eexample", "http://192.168.0%2e10", "https://hä.example", "http://192.168.0.10:0",
            "http://192.168.0.10:65536", "http://192.168.0.10:08123", "https://ha.example:", "https://ha.example:8123:1",
            "http://192.168.0.010", "http://3232235530", "http://0xc0.168.0.10", "http://192.168.0.10.",
            "http://１９２.168.0.10", "https://ha example", "https://ha\\example", "https://[fd00::01]",
            "http://192.168.0.10 ",
        ),
    )

    @Test
    fun canonicalLowercasesAndDropsTheSlash() {
        val cases = mapOf(
            "HTTP://192.168.0.10:8123/" to "http://192.168.0.10:8123",
            "HTTPS://HA.Example:8123" to "https://ha.example:8123",
            "https://Ha.Example/" to "https://ha.example",
            "hTtPs://ha.example.:8123/" to "https://ha.example.:8123",
        )
        for ((url, expected) in cases) {
            assertEquals(expected, HaEndpointPolicy.canonical(url), "for <$url>")
            assertEquals(expected, HaEndpointPolicy.canonical(expected), "canonical is its own canonical")
        }
        assertEquals("ha.example", allowed("HTTPS://HA.Example:8123").host)
        for (url in listOf("http://192.0.2.10:8123", "http://ha.example:8123", "https://ha.example/api", "https://localhost")) {
            assertNull(HaEndpointPolicy.canonical(url), "a refused <$url> has no canonical form")
        }
    }
}
