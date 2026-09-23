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
    fun aUriListPrefersALineThatIsNotAComment() {
        val body = """
            # the deck belt, on the parts site
            https://example-mower.invalid/xt1/deck-belt
            https://example-mower.invalid/xt1/blades
        """.trimIndent()
        assertEquals("https://example-mower.invalid/xt1/deck-belt", ShareTextParser.firstUri(body)?.uri)
    }

    /**
     * Spec §4.4 scans `text/plain` whole, and a Markdown heading is `text/plain` beginning with a
     * `#`. So the comment rule is a **preference** and not a skip: a commented-out URI never wins
     * over a real one, and a heading holding the only URI in the share is still found.
     */
    @Test
    fun aHeadingIsScannedWhenNoOtherLineHoldsAUri() {
        val heading = "## [Mower service](joplin://x-callback-url/openNote?id=0f1e2d3c4b5a6978)"
        val parsed = ShareTextParser.firstUri(heading)
        assertEquals("joplin://x-callback-url/openNote?id=0f1e2d3c4b5a6978", parsed?.uri)
        assertEquals("Mower service", parsed?.label)
    }

    /**
     * A colon does not make a scheme. Several apps share `Title:URL` with no space, and a Markdown
     * label may hold a colon of its own; in both the real link has to win, because `uri` is
     * immutable once written (I-1) and the only repair for a stored `belt:https://…` is delete and
     * re-add. A candidate qualifies when it is followed by `//` or its scheme is one the tier
     * tables name; anything else is prose, whatever the colon suggests.
     */
    @Test
    fun proseGluedToAUrlDoesNotBecomeItsScheme() {
        assertEquals(
            "https://example-mower.invalid/xt1",
            ShareTextParser.firstUri("Deck belt:https://example-mower.invalid/xt1")?.uri,
        )
        val markdown = ShareTextParser.firstUri("[Note:mower](https://example-mower.invalid/xt1)")
        assertEquals("https://example-mower.invalid/xt1", markdown?.uri)
        assertEquals("Note:mower", markdown?.label)
        assertNull(ShareTextParser.firstUri("Deck belt:replaced in spring"))
    }

    /**
     * The restart rule, made observable. A disqualified candidate's colon is **spent**: the scan
     * resumes after it rather than one character in, so each colon is examined once and a
     * colon-dense share cannot go quadratic. The behaviour that pins it is that a word which merely
     * *ends* in a scheme is not that scheme — rescanning from one character in would take the
     * `mailto:` out of `xmailto:` and store a URI nobody shared.
     */
    @Test
    fun aWordThatMerelyEndsInASchemeIsNotThatScheme() {
        assertNull(ShareTextParser.firstUri("xmailto:parts@example-mower.invalid"))
        assertNull(ShareTextParser.firstUri("Xjoplin:x-callback-url/openNote?id=0f1e2d3c4b5a6978"))
        assertEquals(
            "mailto:parts@example-mower.invalid",
            ShareTextParser.firstUri("order from mailto:parts@example-mower.invalid")?.uri,
        )
    }

    /** The control for the rule above: an opaque allow-listed scheme and a blocked one still count. */
    @Test
    fun anOpaqueAllowListedSchemeAndAMailtoAreStillFound() {
        assertEquals(
            "joplin:x-callback-url/openNote?id=0f1e2d3c4b5a6978",
            ShareTextParser.firstUri("note joplin:x-callback-url/openNote?id=0f1e2d3c4b5a6978")?.uri,
        )
        assertEquals(
            "mailto:parts@example-mower.invalid",
            ShareTextParser.firstUri("order from mailto:parts@example-mower.invalid")?.uri,
        )
        assertEquals(
            "zotero://select/items/0",
            ShareTextParser.firstUri("cite zotero://select/items/0")?.uri,
        )
    }
}
