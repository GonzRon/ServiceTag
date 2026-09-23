package com.loosecannon.servicetag.core.references

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a share's text yields (spec §4.4). The rule the whole class defends is #35's: **raw text is
 * never returned as a URI** — no scheme, no guess, no link.
 */
class ShareTextParserTest {

    @Test
    fun aBareUrlInProseIsFound() {
        val parsed = ShareTextParser.firstUri("see https://example-mower.invalid/xt1 for parts")
        assertEquals("https://example-mower.invalid/xt1", parsed?.uri)
        assertNull(parsed?.label)
    }

    @Test
    fun aMarkdownLinkYieldsTheUriAndItsLabel() {
        val parsed = ShareTextParser.firstUri(
            "[Mower maintenance](joplin://x-callback-url/openNote?id=0f1e2d3c4b5a6978)",
        )
        assertEquals("joplin://x-callback-url/openNote?id=0f1e2d3c4b5a6978", parsed?.uri)
        assertEquals("Mower maintenance", parsed?.label)
    }

    @Test
    fun textHoldingNoUriIsNotALink() {
        assertNull(ShareTextParser.firstUri("just some words"))
        assertNull(ShareTextParser.firstUri(""))
        assertNull(ShareTextParser.firstUri("   \n\t "))
    }

    @Test
    fun sentencePunctuationIsNotPartOfTheUri() {
        val parsed = ShareTextParser.firstUri("Parts are listed at https://example-mower.invalid/xt1.")
        assertEquals("https://example-mower.invalid/xt1", parsed?.uri)
    }

    @Test
    fun aQueryAndAFragmentSurviveVerbatim() {
        val uri = "https://example-mower.invalid/xt1?a=1&b=2#frag"
        assertEquals(uri, ShareTextParser.firstUri("Deck belt: $uri")?.uri)
    }

    @Test
    fun aUriListSkipsItsCommentLines() {
        val body = """
            # the deck belt, on the parts site
            https://example-mower.invalid/xt1/deck-belt
            https://example-mower.invalid/xt1/blades
        """.trimIndent()
        assertEquals("https://example-mower.invalid/xt1/deck-belt", ShareTextParser.firstUri(body)?.uri)
    }
}
