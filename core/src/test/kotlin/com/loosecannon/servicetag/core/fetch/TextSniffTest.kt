package com.loosecannon.servicetag.core.fetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Row 41 (C30, C31; R85-5 widened): TXT, Markdown, CSV and TSV under the owner's weaker model — text-like
 * bytes, plus a text declared type or URL extension, plus explicit web-page rejection. Every fixture is a
 * fictional string or byte array built here.
 */
class TextSniffTest {

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun filler(n: Int) = ByteArray(n) { 'x'.code.toByte() }

    /** What the fetch hands over: the first and last min(size, WINDOW) bytes, then the two labels. */
    private fun sniff(file: ByteArray, declared: String?, extension: String? = null): String? {
        val n = minOf(file.size, DocumentSniff.WINDOW)
        val head = file.copyOfRange(0, n)
        return TextSniff.classify(file.size.toLong(), head, file.copyOfRange(file.size - n, file.size), declared, extension)
    }

    private val txt = utf8("Example Pool Pump manual\nClean the strainer basket every week.\n")
    private val md = utf8("# Example Pool Pump manual\n\n- Clean the strainer basket\n- Check the lid seal: `ok`\n")
    private val csv = utf8("part,interval\nstrainer basket,1 week\nlid seal,1 year\n")
    private val tsv = utf8("part\tinterval\nstrainer basket\t1 week\nlid seal\t1 year\n")

    /** A fictional login page: what a document link often answers once its session is gone. */
    private val loginPage = utf8(
        "<!doctype html><html><head><title>Sign in</title></head><body><form action=\"/login\">" +
            "Example Manuals: sign in to download this file</form></body></html>\n",
    )

    /** A 4-byte character, `F0 9F 94 A7`. */
    private val wrench = utf8("🔧")

    // ---- pass ----

    @Test
    fun eachTextFlavourIsClassifiedByItsDeclaredType() {
        assertEquals("text/plain", sniff(txt, "text/plain"))
        assertEquals("text/markdown", sniff(md, "text/markdown"))
        assertEquals("text/csv", sniff(csv, "text/csv"))
        assertEquals("text/tab-separated-values", sniff(tsv, "text/tab-separated-values"))
    }

    @Test
    fun eachTextFlavourIsClassifiedByItsExtensionWhenServedAsOctetStream() {
        assertEquals("text/plain", sniff(txt, "application/octet-stream", "txt"))
        assertEquals("text/markdown", sniff(md, "application/octet-stream", "md"))
        assertEquals("text/csv", sniff(csv, "application/octet-stream", "csv"))
        assertEquals("text/tab-separated-values", sniff(tsv, "application/octet-stream", "tsv"))
        assertEquals("text/csv", sniff(csv, null, "csv"), "no declared type at all")
    }

    @Test
    fun aDeclaredFlavourWinsThenTheExtensionThenPlainText() {
        assertEquals("text/markdown", sniff(md, "text/plain", "md"))
        assertEquals("text/csv", sniff(csv, "text/csv", "txt"))
        assertEquals("text/tab-separated-values", sniff(tsv, "text/tab-separated-values", "csv"))
        assertEquals("text/markdown", sniff(md, "text/markdown", "tsv"))
        assertEquals("text/plain", sniff(txt, "text/plain", "pdf"))
        assertEquals("text/plain", sniff(txt, "text/plain", null))
        assertEquals("text/csv", sniff(csv, "Text/CSV; charset=utf-8", null), "the declared type is normalised")
    }

    @Test
    fun aUtf8BomIsAllowed() {
        val bom = bytes(0xEF, 0xBB, 0xBF)
        assertEquals("text/plain", sniff(bom + txt, "text/plain"))
        assertEquals("text/csv", sniff(bom + csv, null, "csv"))
        assertEquals("text/plain", sniff(bom + filler(3_000) + txt, "text/plain"))
    }

    @Test
    fun multiByteTextIsText() {
        assertEquals("text/plain", sniff(utf8("Pumpe für das Becken: 25 € — ") + wrench + utf8("\n"), "text/plain"))
    }

    @Test
    fun aCharacterCutAtEitherWindowEdgeOfALargeFileIsTolerated() {
        for (cut in 1..3) {
            // the head keeps the first [cut] bytes of the character; the rest are in the unread middle
            assertEquals("text/plain", sniff(filler(1_024 - cut) + wrench + filler(2_000), "text/plain"), "head $cut")
            // the tail starts with the last [cut] bytes of a character that began before it
            assertEquals("text/plain", sniff(filler(2_000) + wrench + filler(1_024 - cut), "text/plain"), "tail $cut")
        }
    }

    @Test
    fun aCharacterAcrossTheWindowsOfAFileUnderTwoWindowsDecodesWhole() {
        val file = filler(1_022) + wrench + filler(500)
        assertEquals("text/plain", sniff(file, "text/plain"))
        assertEquals("text/plain", sniff(filler(1_022) + wrench + filler(1_022), "text/plain"), "exactly two windows")
    }

    @Test
    fun anXmlDeclarationWithoutTheXhtmlNamespaceIsNotAWebPage() {
        assertEquals("text/plain", sniff(utf8("<?xml version=\"1.0\"?>\n<pump model=\"Example\"/>\n"), "text/plain"))
    }

    // ---- fail: the owner's must-fail fixtures ----

    @Test
    fun htmlServedAsPlainTextIsRefused() {
        assertNull(sniff(loginPage, "text/plain"))
    }

    @Test
    fun htmlAtATxtUrlIsRefused() {
        assertNull(sniff(loginPage, "application/octet-stream", "txt"))
    }

    @Test
    fun htmlServedAsCsvIsRefused() {
        assertNull(sniff(loginPage, "text/csv"))
    }

    @Test
    fun eachWebPagePatternInAnyCaseIsRefused() {
        val patterns = listOf(
            "<!doctype html>", "<!DOCTYPE HTML PUBLIC>", "<!DocType Html>",
            "<html>", "<HTML lang=\"en\">", "<Html>",
            "<head>", "<HEAD>", "<hEaD>",
            "<script>", "<SCRIPT src=\"x.js\">", "<ScRiPt>",
            "<body>", "<BODY>", "<Body onload=\"x\">",
            "<meta charset=\"utf-8\">", "<META>", "<Meta>",
            "<form action=\"/login\">", "<FORM>", "<Form>",
            "<iframe>", "<IFRAME>", "<iFrame>",
        )
        for (pattern in patterns) {
            val page = utf8("Example Pool Pump manual\n$pattern\nsign in\n")
            assertNull(sniff(page, "text/plain"), pattern)
            assertNull(sniff(page, "application/octet-stream", "md"), pattern)
        }
        // in the head window of a large file too
        assertNull(sniff(filler(900) + utf8("<Script>") + filler(3_000), "text/plain"))
    }

    /**
     * Pre-review fix (controller ruling on concern 1): the doctype allows any run of space, tab, CR, LF or FF
     * before `html`, so a bare page (no html, head, body, meta, script, form or iframe tag) is still refused.
     */
    @Test
    fun aBarePageWithADoctypeSpacedAnyWayIsRefused() {
        val page = "\n<title>Sign in</title>\n<p>Example Manuals: sign in to download this file</p>\n"
        val doctypes = listOf(
            "two spaces" to "<!DOCTYPE  html>",
            "LF" to "<!doctype\nhtml>",
            "tab" to "<!doctype\thtml>",
            "CRLF and a space" to "<!DocType\r\n HTML>",
            "FF" to "<!doctype\u000Chtml>",
        )
        val classified = doctypes.filter { (_, doctype) -> sniff(utf8(doctype + page), "text/plain", "txt") != null }
        assertEquals(emptyList(), classified.map { it.first }, "classified as text")
    }

    @Test
    fun aDoctypeThatIsNotHtmlIsNotAWebPage() {
        assertEquals("text/plain", sniff(utf8("<!doctype pump-notes>\nExample Pool Pump manual\n"), "text/plain", "txt"))
    }

    @Test
    fun anXmlDeclarationFollowedByTheXhtmlNamespaceIsRefused() {
        val xhtml = "<?xml version=\"1.0\"?>\n<page xmlns=\"http://www.w3.org/1999/xhtml\">Sign in</page>\n"
        assertNull(sniff(utf8(xhtml), "text/plain"))
        assertNull(sniff(utf8(xhtml.uppercase()), "application/octet-stream", "txt"), "any case")
    }

    @Test
    fun aTextFileWithANulIsRefused() {
        assertNull(sniff(utf8("Example Pool Pump manual\u0000\n"), "text/plain"))
        assertNull(sniff(txt + filler(3_000) + bytes(0) + txt, "text/plain", "txt"), "a NUL in the tail window")
        assertNull(sniff(bytes(0xFF, 0xFE) + "Example".toByteArray(Charsets.UTF_16LE), "text/plain"), "UTF-16 (a recorded limit)")
    }

    @Test
    fun invalidUtf8IsRefused() {
        val invalid = listOf(
            bytes(0x80), // a lone continuation byte
            bytes(0xC3, 0x20), // a lead byte without its continuation
            bytes(0xC0, 0xAF), // overlong '/'
            bytes(0xE0, 0x80, 0xAF), // overlong, three bytes
            bytes(0xED, 0xA0, 0x80), // a UTF-16 surrogate
            bytes(0xF4, 0x90, 0x80, 0x80), // above U+10FFFF
            bytes(0xF8, 0x88, 0x80, 0x80, 0x80), // a five-byte form
            bytes(0xE9), // ISO-8859-1 'e' with an acute accent
        )
        for (bad in invalid) {
            val label = bad.joinToString(" ") { "%02X".format(it) }
            assertNull(sniff(utf8("Example ") + bad + utf8(" manual\n"), "text/plain"), label)
        }
    }

    @Test
    fun aCutCharacterIsToleratedOnlyAtAWindowsInnerEdge() {
        assertNull(sniff(utf8("Example Pool Pump manual") + wrench.copyOf(2), "text/plain"), "the end of a small file")
        assertNull(sniff(filler(1_500) + wrench.copyOf(3), "text/plain"), "the end of a file under two windows")
        assertNull(sniff(filler(1_022) + wrench.copyOf(3) + filler(1_023), "text/plain"), "a cut character inside two windows")
        assertNull(sniff(filler(3_000) + wrench.copyOf(3), "text/plain"), "the end of a large file")
        assertNull(sniff(filler(2_000) + bytes(0x80, 0x80, 0x80, 0x80) + filler(1_020), "text/plain"), "four leading continuations")
        assertNull(sniff(filler(1_022) + bytes(0xE0, 0x80) + filler(2_000), "text/plain"), "an invalid cut prefix")
    }

    @Test
    fun plainTextWithNoTextDeclarationOrExtensionIsNeverClassified() {
        assertNull(sniff(txt, "application/octet-stream", null))
        assertNull(sniff(txt, null, null))
        assertNull(sniff(txt, "application/pdf", "pdf"))
        assertNull(sniff(txt, "text/html", "html"))
        assertNull(sniff(txt, "text/xml", "xml"))
        assertNull(sniff(txt, "application/json", "json"))
    }

    @Test
    fun windowsThatAreNotTheExpectedLengthAreRefused() {
        val n = txt.size.toLong()
        assertEquals("text/plain", TextSniff.classify(n, txt, txt, "text/plain", null))
        assertNull(TextSniff.classify(n + 1, txt, txt, "text/plain", null))
        assertNull(TextSniff.classify(n, txt, txt.copyOfRange(1, txt.size), "text/plain", null))
        assertNull(TextSniff.classify(0, ByteArray(0), ByteArray(0), "text/plain", "txt"))
    }
}
