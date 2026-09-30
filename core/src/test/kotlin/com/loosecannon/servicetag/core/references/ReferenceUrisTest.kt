package com.loosecannon.servicetag.core.references

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Row 12 (C8; R85-2 as amended by R85-14): the destination a redirect's provenance may keep is scheme +
 * authority + the ordinary path — no query, no fragment, no per-segment `;` parameter, no userinfo — and
 * the extracted `hostOf` answers what `AddReference`'s private one always answered.
 */
class ReferenceUrisTest {

    @Test
    fun destinationDropsQueryAndFragment() {
        val cases = mapOf(
            "https://cdn.example.invalid/files/pump.pdf?token=AB12&exp=9" to "https://cdn.example.invalid/files/pump.pdf",
            "https://cdn.example.invalid/files/pump.pdf#page=2" to "https://cdn.example.invalid/files/pump.pdf",
            "https://cdn.example.invalid/files/pump.pdf?token=AB12#page=2" to "https://cdn.example.invalid/files/pump.pdf",
            "https://cdn.example.invalid/files/pump.pdf#page=2?token=AB12" to "https://cdn.example.invalid/files/pump.pdf",
            "https://cdn.example.invalid/files/pump.pdf?" to "https://cdn.example.invalid/files/pump.pdf",
            "https://cdn.example.invalid/files/pump.pdf#" to "https://cdn.example.invalid/files/pump.pdf",
            "https://cdn.example.invalid/files/pump.pdf?next=/a;b/c" to "https://cdn.example.invalid/files/pump.pdf",
        )
        for ((uri, expected) in cases) assertEquals(expected, ReferenceUris.destinationOf(uri), "for <$uri>")
    }

    @Test
    fun destinationDropsPathParametersPerSegment() {
        val cases = mapOf(
            "https://cdn.example.invalid/a;x=1/b;jsessionid=AB12/m.pdf" to "https://cdn.example.invalid/a/b/m.pdf",
            "https://cdn.example.invalid/files/pump.pdf;jsessionid=AB12" to "https://cdn.example.invalid/files/pump.pdf",
            "https://cdn.example.invalid/a;x=1;y=2/m.pdf;v=3?token=AB12" to "https://cdn.example.invalid/a/m.pdf",
            "https://cdn.example.invalid/;x=1/m.pdf" to "https://cdn.example.invalid//m.pdf",
            "https://cdn.example.invalid/;x=1" to "https://cdn.example.invalid/",
        )
        for ((uri, expected) in cases) assertEquals(expected, ReferenceUris.destinationOf(uri), "for <$uri>")
    }

    @Test
    fun destinationKeepsTheOrdinaryPath() {
        for (uri in listOf(
            "https://cdn.example.invalid/Docs/Pool%20Pump/Manual-Rev_2.pdf",
            "https://cdn.example.invalid/a/b/c/",
            "https://cdn.example.invalid:8443/files/pump.pdf",
            "https://[2001:db8::10]:8443/files/pump.pdf",
            "https://203.0.113.10/files/pump.pdf",
        )) {
            assertEquals(uri, ReferenceUris.destinationOf(uri), "for <$uri>")
        }
    }

    @Test
    fun destinationDropsUserinfoAndLowercasesSchemeAndHost() {
        val cases = mapOf(
            "HTTPS://owner:secret@CDN.Example.Invalid:8443/Docs/Pump.PDF" to "https://cdn.example.invalid:8443/Docs/Pump.PDF",
            "Https://owner@Cdn.Example.Invalid/Docs/Pump.PDF" to "https://cdn.example.invalid/Docs/Pump.PDF",
            "https://[2001:DB8::10]/Pump.PDF" to "https://[2001:db8::10]/Pump.PDF",
        )
        for ((uri, expected) in cases) assertEquals(expected, ReferenceUris.destinationOf(uri), "for <$uri>")
    }

    @Test
    fun anEmptyPathIsSlash() {
        for (uri in listOf(
            "https://cdn.example.invalid",
            "https://cdn.example.invalid?token=AB12",
            "https://cdn.example.invalid#page=2",
        )) {
            assertEquals("https://cdn.example.invalid/", ReferenceUris.destinationOf(uri), "for <$uri>")
        }
    }

    @Test
    fun noAuthorityIsNoDestination() {
        for (uri in listOf("https://", "https:///pump.pdf", "https:pump.pdf", "mailto:parts@example.invalid", "", "//cdn.example.invalid/x")) {
            assertNull(ReferenceUris.destinationOf(uri), "for <$uri>")
        }
    }

    @Test
    fun hasUserInfoReadsOnlyTheAuthority() {
        assertTrue(ReferenceUris.hasUserInfo("https://owner:secret@cdn.example.invalid/x"))
        assertTrue(ReferenceUris.hasUserInfo("https://owner@cdn.example.invalid"))
        assertFalse(ReferenceUris.hasUserInfo("https://cdn.example.invalid/owner@x"))
        assertFalse(ReferenceUris.hasUserInfo("https://cdn.example.invalid?by=owner@x"))
        assertFalse(ReferenceUris.hasUserInfo("https://cdn.example.invalid#owner@x"))
        assertFalse(ReferenceUris.hasUserInfo("mailto:parts@example.invalid"))
    }

    /** The shipped cases the private `AddReference.hostOf` answered, byte for byte (IPv6, port, userinfo, underscore). */
    @Test
    fun hostOfIsByteIdenticalForTheShippedCases() {
        val cases = mapOf(
            "https://[2001:db8::10]:8443/x" to "2001:db8::10",
            "https://[2001:db8::10]/x" to "2001:db8::10",
            "https://manuals.example.invalid:8443/x" to "manuals.example.invalid",
            "https://owner:secret@manuals.example.invalid/x" to "manuals.example.invalid",
            "https://a@b@manuals.example.invalid:1/x" to "manuals.example.invalid",
            "https://dl_cdn.example.invalid/x" to "dl_cdn.example.invalid",
            "https://Manuals.Example.Invalid?q=1" to "Manuals.Example.Invalid",
            "https://manuals.example.invalid#f" to "manuals.example.invalid",
            "file://localhost/etc/passwd" to "localhost",
            "https://10.0.0.5\\.x.example.invalid/x" to "10.0.0.5\\.x.example.invalid",
        )
        for ((uri, expected) in cases) assertEquals(expected, ReferenceUris.hostOf(uri), "for <$uri>")
        for (uri in listOf("https://", "https:///x", "http:///path", "https://:8443/x", "https://owner@/x",
            "https://[]/x", "joplin:x-callback-url/openNote?id=1", "notaurl", "")) {
            assertNull(ReferenceUris.hostOf(uri), "for <$uri>")
        }
        // The shipped private function, verbatim as it stood at 05dc90b0 over the text after the scheme's
        // colon, is the oracle: the extraction answers what it answered for every case above.
        for (uri in cases.keys + listOf("https://", "https:///x", "https://:8443/x", "https://owner@/x", "https://[]/x")) {
            assertEquals(shippedHostOf(uri.substringAfter(':')), ReferenceUris.hostOf(uri), "for <$uri>")
        }
    }

    private fun shippedHostOf(rest: String): String? {
        if (!rest.startsWith("//")) return null
        val afterSlashes = rest.substring(2)
        val end = afterSlashes.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (end < 0) afterSlashes else afterSlashes.substring(0, end)
        if (authority.isEmpty()) return null
        val hostAndPort = authority.substringAfterLast('@')
        val host = if (hostAndPort.startsWith("[")) {
            hostAndPort.substringBefore(']').removePrefix("[")
        } else {
            hostAndPort.substringBefore(':')
        }
        return host.ifEmpty { null }
    }
}
