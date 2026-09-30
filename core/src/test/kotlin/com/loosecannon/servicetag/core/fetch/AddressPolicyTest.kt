package com.loosecannon.servicetag.core.fetch

import com.loosecannon.servicetag.core.testing.FakeHostResolver
import com.loosecannon.servicetag.core.testing.address
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rows 10 and 11 (C9, R85-7 amended): C9's address set exactly — no broader — and the resolution half of
 * the hop rule, which refuses a host when ANY of its addresses is in that set and never reads a
 * resolver failure as "allowed".
 */
class AddressPolicyTest {

    /** Row 10, forbidden: each IPv4 range's first and last address, then the IPv6 and embedded-IPv4 set. */
    private val forbidden = listOf(
        "0.0.0.0", "0.255.255.255",                    // 0.0.0.0/8
        "10.0.0.0", "10.255.255.255",                  // 10.0.0.0/8
        "100.64.0.0", "100.127.255.255",               // 100.64.0.0/10
        "127.0.0.0", "127.255.255.255",                // 127.0.0.0/8
        "169.254.0.0", "169.254.255.255",              // 169.254.0.0/16
        "172.16.0.0", "172.31.255.255",                // 172.16.0.0/12
        "192.168.0.0", "192.168.255.255",              // 192.168.0.0/16
        "224.0.0.0", "239.255.255.255",                // 224.0.0.0/4
        "240.0.0.0", "255.255.255.255",                // 240.0.0.0/4
        "::",                                          // ::/128
        "::1",                                         // ::1/128
        "fe80::1", "febf:ffff::1",                     // fe80::/10
        "fec0::1", "feff:ffff::1",                     // fec0::/10
        "fc00::1", "fd12::1",                          // fc00::/7
        "ff02::1", "ff00::",                           // ff00::/8
        "::ffff:192.168.1.5", "::ffff:127.0.0.1",      // ::ffff:0:0/96, embedded
        "::a00:1",                                     // ::/96 (IPv4-compatible), embedded 10.0.0.1
        "64:ff9b::a00:1",                              // 64:ff9b::/96, embedded 10.0.0.1
        "64:ff9b:1::a00:1",                            // 64:ff9b:1::/48, embedded 10.0.0.1
        "2002:a00:1::1",                               // 2002::/16, embedded 10.0.0.1 in bytes 2-5
    )

    /**
     * Row 10, allowed: the four documentation ranges (owner, 2026-09-29), each IPv4 range's neighbours,
     * and every embedding prefix unwrapped to a public address — so the unwrap is proved both ways.
     */
    private val allowed = listOf(
        "203.0.113.10", "192.0.2.1", "198.51.100.7", "2001:db8::10",
        "::ffff:203.0.113.10",
        "100.63.255.255", "100.128.0.0",
        "172.15.255.255", "172.32.0.0",
        "1.0.0.0", "9.255.255.255", "11.0.0.0", "126.255.255.255", "128.0.0.0",
        "169.253.255.255", "169.255.0.0", "192.167.255.255", "192.169.0.0", "223.255.255.255",
        "::cb00:710a", "64:ff9b::cb00:710a", "64:ff9b:1::cb00:710a", "2002:cb00:710a::1",
        "fe7f:ffff::1", "fbff:ffff::1", "fe00::1",
    )

    @Test
    fun theForbiddenSetIsExactlyC9s() {
        for (text in forbidden) assertTrue(isForbiddenAddress(address(text)), "$text must be forbidden")
        for (text in allowed) assertFalse(isForbiddenAddress(address(text)), "$text must be allowed")
    }

    @Test
    fun anAddressOfAnyOtherLengthIsForbidden() {
        for (size in listOf(0, 1, 3, 5, 15, 17)) {
            assertTrue(isForbiddenAddress(ByteArray(size) { 0x40 }), "a $size-byte address must be forbidden")
        }
    }

    private val url = "https://manuals.example.invalid/pump.pdf"

    @Test
    fun anyForbiddenAddressAmongManyIsLocal() = runTest {
        val resolver = FakeHostResolver.of(
            "manuals.example.invalid" to listOf("203.0.113.10", "2001:db8::10", "198.51.100.7", "192.168.1.5"),
        )
        assertEquals(FetchProblem.LocalAddress, HopPolicy(resolver).check(url))
        assertEquals(listOf("manuals.example.invalid"), resolver.asked)
    }

    @Test
    fun onlyPublicAddressesPass() = runTest {
        val resolver = FakeHostResolver.of(
            "manuals.example.invalid" to listOf("203.0.113.10", "2001:db8::10", "::ffff:198.51.100.7"),
        )
        assertNull(HopPolicy(resolver).check(url))
    }

    @Test
    fun aResolverFailureIsUnreachable() = runTest {
        for (kind in listOf(
            TransportFailure.Kind.UNREACHABLE,
            TransportFailure.Kind.TIMED_OUT,
            TransportFailure.Kind.INTERRUPTED,
        )) {
            val resolver = FakeHostResolver(failure = TransportFailure(kind))
            assertEquals(FetchProblem.Unreachable, HopPolicy(resolver).check(url), "for $kind")
        }
        assertEquals(FetchProblem.Unreachable, HopPolicy(FakeHostResolver()).check(url), "an unknown name")
    }

    @Test
    fun anEmptyAnswerIsUnreachable() = runTest {
        val resolver = FakeHostResolver(mapOf("manuals.example.invalid" to emptyList()))
        assertEquals(FetchProblem.Unreachable, HopPolicy(resolver).check(url))
    }

    @Test
    fun deniedIsNetworkDenied() = runTest {
        val resolver = FakeHostResolver(failure = TransportFailure(TransportFailure.Kind.DENIED))
        assertEquals(FetchProblem.NetworkDenied, HopPolicy(resolver).check(url))
    }

    @Test
    fun aStaticProblemIsAnsweredWithoutResolving() = runTest {
        val resolver = FakeHostResolver.of("manuals.example.invalid" to listOf("203.0.113.10"))
        val policy = HopPolicy(resolver)
        assertEquals(FetchProblem.NotHttps, policy.check("http://manuals.example.invalid/pump.pdf"))
        assertEquals(FetchProblem.HasCredentials, policy.check("https://o:p@manuals.example.invalid/pump.pdf"))
        assertEquals(FetchProblem.NotHttps, policy.check("https://manuals.example.invalid\\x/pump.pdf"))
        assertEquals(FetchProblem.LocalAddress, policy.check("https://localhost/pump.pdf"))
        assertTrue(resolver.asked.isEmpty(), "resolved ${resolver.asked}")
    }

    @Test
    fun aLiteralIsResolvedLikeAName() = runTest {
        val resolver = FakeHostResolver.of(
            "127.0.0.1" to listOf("127.0.0.1"),
            "::1" to listOf("::1"),
            "203.0.113.10" to listOf("203.0.113.10"),
        )
        val policy = HopPolicy(resolver)
        assertEquals(FetchProblem.LocalAddress, policy.check("https://127.0.0.1/pump.pdf"))
        assertEquals(FetchProblem.LocalAddress, policy.check("https://[::1]:8443/pump.pdf"))
        assertNull(policy.check("https://203.0.113.10/pump.pdf"))
        assertEquals(listOf("127.0.0.1", "::1", "203.0.113.10"), resolver.asked)
    }

    @Test
    fun theResolverIsAskedForTheHostAsWrittenInLowercase() = runTest {
        val resolver = FakeHostResolver.of(
            "manuals.example.invalid" to listOf("203.0.113.10"),
            "manuals.example.invalid." to listOf("203.0.113.10"),
        )
        val policy = HopPolicy(resolver)
        assertNull(policy.check("https://Manuals.EXAMPLE.invalid:8443/pump.pdf"))
        assertNull(policy.check("https://manuals.example.invalid./pump.pdf"))
        assertEquals(listOf("manuals.example.invalid", "manuals.example.invalid."), resolver.asked)
    }

    @Test
    fun cancellationIsNeverAProblem() = runTest {
        val resolver = FakeHostResolver(failure = CancellationException("cancelled"))
        assertFailsWith<CancellationException> { HopPolicy(resolver).check(url) }
    }
}
