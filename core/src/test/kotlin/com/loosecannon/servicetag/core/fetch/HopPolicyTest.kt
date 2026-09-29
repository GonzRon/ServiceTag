package com.loosecannon.servicetag.core.fetch

import com.loosecannon.servicetag.core.testing.FakeHostResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Row 9 (C8, C9; R85-4, R85-15): the static half of the hop rule. The invariant it guards is that the
 * host ServiceTag checks is unambiguously the host the network is asked for, so every spelling a
 * browser-compatible parser could read two ways is refused before anything resolves it.
 */
class HopPolicyTest {

    private val resolver = FakeHostResolver()
    private val policy = HopPolicy(resolver)

    /** A name of exactly [length] characters, labels at most 63, ending `.example.invalid`. */
    private fun nameOfLength(length: Int): String {
        val suffix = ".example.invalid"
        val labels = mutableListOf<String>()
        var left = length - suffix.length
        while (left > 0) {
            val label = minOf(63, if (labels.isEmpty()) left else left - 1)
            labels += "n".repeat(label)
            left -= label + if (labels.size > 1) 1 else 0
        }
        return (labels.joinToString(".") + suffix).also { check(it.length == length) { "built ${it.length}" } }
    }

    private fun assertEach(expected: FetchProblem?, urls: List<String>) {
        for (url in urls) assertEquals(expected, policy.staticProblem(url), "for <$url>")
    }

    @Test
    fun httpIsNotHttps() = assertEach(
        FetchProblem.NotHttps,
        listOf(
            "http://manuals.example.invalid/pump.pdf",
            "HTTP://manuals.example.invalid/pump.pdf",
            "ftp://manuals.example.invalid/pump.pdf",
            "manuals.example.invalid/pump.pdf",
            "joplin://x-callback-url/openNote?id=1",
        ),
    )

    @Test
    fun userinfoIsHasCredentials() = assertEach(
        FetchProblem.HasCredentials,
        listOf(
            "https://owner:secret@manuals.example.invalid/pump.pdf",
            "https://owner@manuals.example.invalid/pump.pdf",
            "https://@manuals.example.invalid/pump.pdf",
            "https://manuals.example.invalid@203.0.113.10/pump.pdf",
        ),
    )

    @Test
    fun localhostAndDotLocalhostAreLocal() = assertEach(
        FetchProblem.LocalAddress,
        listOf(
            "https://localhost/pump.pdf",
            "https://LocalHost/pump.pdf",
            "https://localhost./pump.pdf",
            "https://localhost:8443/pump.pdf",
            "https://printer.localhost/pump.pdf",
            "https://Printer.LOCALHOST./pump.pdf",
        ),
    )

    @Test
    fun aNameThatOnlyContainsLocalhostIsNotLocal() = assertEach(
        null,
        listOf(
            "https://localhost.example.invalid/pump.pdf",
            "https://notlocalhost/pump.pdf",
            "https://my-localhost.example.invalid/pump.pdf",
        ),
    )

    @Test
    fun noHostIsRefused() = assertEach(
        FetchProblem.NotHttps,
        listOf(
            "https://",
            "https:///pump.pdf",
            "https://?q=1",
            "https://#frag",
            "https:pump.pdf",
            "https:/manuals.example.invalid/pump.pdf",
            "https://:443/pump.pdf",
            "https://[]/pump.pdf",
        ),
    )

    @Test
    fun theStaticRuleNeverResolves() {
        for (url in listOf(
            "https://manuals.example.invalid/pump.pdf",
            "https://203.0.113.10/pump.pdf",
            "https://[2001:db8::10]/pump.pdf",
            "https://localhost/pump.pdf",
            "http://manuals.example.invalid/pump.pdf",
        )) {
            policy.staticProblem(url)
        }
        assertTrue(resolver.asked.isEmpty(), "the static rule asked the resolver for ${resolver.asked}")
    }

    @Test
    fun aHostOutsideTheAllowlistIsRefused() = assertEach(
        FetchProblem.NotHttps,
        listOf(
            // a backslash: a platform parser ends the authority there and connects to 10.0.0.5 (review M1)
            "https://10.0.0.5\\.x.example.invalid/pump.pdf",
            "https://manuals.example.invalid\\pump.pdf",
            // a percent escape: decoded by the platform, never by this rule
            "https://10%2e0%2e0%2e5.example.invalid/pump.pdf",
            "https://manuals%2Eexample.invalid/pump.pdf",
            // fullwidth digits and dots, and an ideographic full stop, which IDNA folds to ASCII
            "https://１０．０．０．５/pump.pdf",
            "https://manuals。example.invalid/pump.pdf",
            // a Unicode IDN host that was not stored as punycode (R85-15)
            "https://bücher.example.invalid/pump.pdf",
            // whitespace and control characters inside the authority
            "https://manuals.example.invalid\t/pump.pdf",
            "https://manuals .example.invalid/pump.pdf",
            "https://manuals.example.invalid\u0000/pump.pdf",
            "https://manuals.example.invalid\r\n/pump.pdf",
            "https://manuals.example.invalid\n/pump.pdf",
            "https://manuals\r.example.invalid/pump.pdf",
            // empty labels
            "https://manuals..example.invalid/pump.pdf",
            "https://.example.invalid/pump.pdf",
            "https://manuals.example.invalid../pump.pdf",
            "https://./pump.pdf",
            // label and name lengths
            "https://${"a".repeat(64)}.example.invalid/pump.pdf",
            "https://${List(64) { "abc" }.joinToString(".")}.invalid/pump.pdf",
            "https://${nameOfLength(254)}/pump.pdf",
            "https://${nameOfLength(254)}./pump.pdf",
            // hyphens at a label's edge
            "https://-manuals.example.invalid/pump.pdf",
            "https://manuals-.example.invalid/pump.pdf",
            // other characters a hostname never carries
            "https://manuals!.example.invalid/pump.pdf",
            "https://manuals;x.example.invalid/pump.pdf",
            // malformed ports
            "https://manuals.example.invalid:/pump.pdf",
            "https://manuals.example.invalid:0/pump.pdf",
            "https://manuals.example.invalid:00/pump.pdf",
            "https://manuals.example.invalid:0443/pump.pdf",
            "https://manuals.example.invalid:00443/pump.pdf",
            "https://[2001:db8::10]:08443/pump.pdf",
            "https://manuals.example.invalid:65536/pump.pdf",
            "https://manuals.example.invalid:123456/pump.pdf",
            "https://manuals.example.invalid:44a/pump.pdf",
            "https://manuals.example.invalid:+443/pump.pdf",
            "https://manuals.example.invalid:443:443/pump.pdf",
            "https://manuals.example.invalid:４４３/pump.pdf",
            // brackets that do not hold exactly an IPv6 literal
            "https://[2001:db8::10/pump.pdf",
            "https://[2001:db8::10]x/pump.pdf",
            "https://[manuals.example.invalid]/pump.pdf",
            "https://[203.0.113.10]/pump.pdf",
            // an unbracketed IPv6 literal
            "https://2001:db8::10/pump.pdf",
            // multiple slashes some parsers skip
            "https:////manuals.example.invalid/pump.pdf",
            // leading whitespace some parsers trim
            " https://manuals.example.invalid/pump.pdf",
        ),
    )

    @Test
    fun nonCanonicalIpv4IsRefused() = assertEach(
        FetchProblem.NotHttps,
        listOf(
            "https://127.1/pump.pdf",
            "https://2130706433/pump.pdf",
            "https://0177.0.0.1/pump.pdf",
            "https://0x7f.0.0.1/pump.pdf",
            "https://0X7F.0.0.1/pump.pdf",
            "https://0x7f000001/pump.pdf",
            "https://203.0.113.010/pump.pdf",
            "https://203.0.113.256/pump.pdf",
            "https://203.0.113.10.5/pump.pdf",
            "https://203.0.113.10./pump.pdf",
            "https://manuals.example.123/pump.pdf",
            "https://0xcafe.example.invalid/pump.pdf",
        ),
    )

    @Test
    fun nonCanonicalIpv6IsRefused() = assertEach(
        FetchProblem.NotHttps,
        listOf(
            "https://[2001:DB8::10]/pump.pdf",                  // uppercase
            "https://[2001:db8:0:0:0:0:0:10]/pump.pdf",         // uncompressed
            "https://[2001:0db8::10]/pump.pdf",                 // a leading zero
            "https://[fe80::1%25eth0]/pump.pdf",                // a zone id
            "https://[fe80::1%eth0]/pump.pdf",
            "https://[2001:db8::1:0:0:0:1]/pump.pdf",           // not the longest zero run
            "https://[2001:db8:0:0:1::1]/pump.pdf",             // a tie compressed at the second run
            "https://[2001:db8:1:2:3:4:5::]/pump.pdf",          // one zero group compressed
            "https://[2001:db8::10::1]/pump.pdf",               // two ::
            "https://[2001:db8:1:2:3:4:5:6:7]/pump.pdf",        // nine groups
            "https://[2001:db8:1:2:3:4:5]/pump.pdf",            // seven groups, no ::
            "https://[2001:db8::10000]/pump.pdf",               // a five-digit group
            "https://[::ffff:203.0.113.010]/pump.pdf",          // a non-canonical embedded quad
            "https://[::ffff:203.0.113]/pump.pdf",
            "https://[203.0.113.10::]/pump.pdf",
            "https://[:2001:db8::10]/pump.pdf",
            "https://[2001:db8::g]/pump.pdf",
            "https://[1.2::203.0.113.10]/pump.pdf",             // a dot in a group that is not the last
            "https://[::1.2:203.0.113.10]/pump.pdf",
        ),
    )

    /** Review MAJOR 1: the rule is total. Every malformed bracketed literal is refused, and none throws. */
    @Test
    fun aMalformedBracketedLiteralNeverThrows() {
        val literals = listOf(
            "a.b::1.2.3.4", "::1.2:1.2.3.4", "1.2:0:0:0:0:0:1.2.3.4", "1.2::203.0.113.10", "::1.2:203.0.113.10",
            "1.2.3.4:1.2.3.4", "::ffff:1.2.3.4.5", "::1.2.3.", "::.1.2.3", "1::2::3", ":", ":::", ".", "..", "::.",
            "fe80::1%25eth0", "fe80::1%eth0", "203.0.113.10", "manuals.example.invalid", "", "12345::", "::1:2:3:4:5:6:7:8",
            "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "::-1", "::+1", "::0x1", "::1 ", "::\uFF11",
        )
        for (literal in literals) {
            for (url in listOf("https://[$literal]/pump.pdf", "https://[$literal", "https://[$literal]:443/pump.pdf")) {
                val answer = runCatching { policy.staticProblem(url) }
                assertEquals(Result.success(FetchProblem.NotHttps), answer, "for <$url>")
            }
        }
        assertTrue(resolver.asked.isEmpty())
    }

    @Test
    fun punycodeUnderscoreCanonicalLiteralsAndAPortPass() = assertEach(
        null,
        listOf(
            "https://xn--bcher-kva.example.invalid/pump.pdf",
            "https://dl_cdn.example.invalid/pump.pdf",
            "https://203.0.113.10/pump.pdf",
            "https://[2001:db8::10]:8443/pump.pdf",
            "https://[2001:db8::10]/pump.pdf",
            "https://[::ffff:203.0.113.10]/pump.pdf",
            "https://[::ffff:cb00:710a]/pump.pdf",
            "https://[2001:db8:0:1::1]/pump.pdf",
            "https://[2001:db8::1:0:0:1]/pump.pdf",
            "https://[::]/pump.pdf",
            "https://manuals.example.invalid./pump.pdf",
            "https://manuals.example.invalid:443/pump.pdf",
            "https://manuals.example.invalid:1/pump.pdf",
            "https://manuals.example.invalid:65535",
            "HTTPS://Manuals.Example.Invalid/Pump.pdf",
            "https://manuals.example.invalid",
            "https://manuals.example.invalid?x=1",
            "https://manuals.example.invalid#p2",
            "https://${"a".repeat(63)}.example.invalid/pump.pdf",
            "https://${nameOfLength(253)}/pump.pdf",
            "https://${nameOfLength(253)}./pump.pdf",
            "https://m.example.invalid/a\\b%20c/pump.pdf?q=%2e#ü",
        ),
    )
}
