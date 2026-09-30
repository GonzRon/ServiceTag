package com.loosecannon.servicetag.core.fetch

import com.loosecannon.servicetag.core.model.MimeTypes
import com.loosecannon.servicetag.core.references.ReferenceUris
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * C30 (#85, R85-5 widened): TXT, Markdown, CSV and TSV under the owner's weaker model, asked only when no
 * binary family matched (C31). All three must hold: both windows are text-like (strict UTF-8 after an
 * optional BOM, no NUL); the declared type or the final URL's extension is a text one, so text-looking
 * bytes are never classified on their own; and the head window does not look like a web page. The
 * flavour is C31's: a declared Markdown, CSV or TSV type wins, then the extension's, then plain text.
 * Pure: no I/O.
 */
object TextSniff {
    const val PLAIN = "text/plain"
    const val MARKDOWN = "text/markdown"
    const val CSV = "text/csv"
    const val TSV = "text/tab-separated-values"

    private val DECLARED = setOf(PLAIN, MARKDOWN, CSV, TSV)
    private val BY_EXTENSION = mapOf("txt" to PLAIN, "md" to MARKDOWN, "csv" to CSV, "tsv" to TSV)

    /** Any one of these in the head window, ignoring ASCII case, is a web page. */
    private val WEB_PAGE = listOf("<!doctype html", "<html", "<head", "<script", "<body", "<meta", "<form", "<iframe")

    /** An XML declaration followed, later in the head window, by the XHTML namespace is a web page too. */
    private const val XML_DECLARATION = "<?xml"
    private const val XHTML_NAMESPACE = "http://www.w3.org/1999/xhtml"

    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private const val NUL: Byte = 0

    /** At most this many bytes of one multi-byte sequence cut by a window's inner edge. */
    private const val MAX_CUT = 3

    /**
     * The text MIME, or null. [size], [head] and [tail] are as for [DocumentSniff.classify] (other window
     * lengths are refused); [declared] is the declared type or null (normalised again here); [extension] is
     * `FetchDocument.extensionOf`'s, or null.
     */
    fun classify(size: Long, head: ByteArray, tail: ByteArray, declared: String?, extension: String?): String? {
        val expected = minOf(size, DocumentSniff.WINDOW.toLong())
        if (size <= 0 || head.size.toLong() != expected || tail.size.toLong() != expected) return null
        val flavour = flavourOf(declared?.let(MimeTypes::normalise), extension?.lowercase()) ?: return null
        return flavour.takeIf { isTextLike(size, head, tail) && !isWebPage(head) }
    }

    /** C31; null when neither label is a text one. */
    private fun flavourOf(declared: String?, extension: String?): String? =
        declared?.takeIf { it in DECLARED && it != PLAIN }
            ?: extension?.let(BY_EXTENSION::get)
            ?: declared?.takeIf { it == PLAIN }

    /**
     * No NUL, and strict UTF-8 after an optional BOM. When the windows overlap or meet, the whole file is on
     * hand and is decoded once, with no cut allowed; otherwise the head may end inside one character and the
     * tail may start inside one.
     */
    private fun isTextLike(size: Long, head: ByteArray, tail: ByteArray): Boolean {
        if (NUL in head || NUL in tail) return false
        val bom = if (DocumentSniff.regionMatches(head, 0, BOM)) BOM.size else 0
        if (size <= 2L * DocumentSniff.WINDOW) {
            val tailStart = size - tail.size
            return decodes(head + tail.copyOfRange((head.size - tailStart).toInt(), tail.size), bom, cut = 0)
        }
        val continuations = tail.take(MAX_CUT).takeWhile { it.toInt() and 0xC0 == 0x80 }.size
        return decodes(head, bom, cut = MAX_CUT) && decodes(tail, continuations, cut = 0)
    }

    /**
     * Strict UTF-8 from [from] (overlong forms, surrogates and code points past U+10FFFF are malformed),
     * leaving at most [cut] bytes of one incomplete sequence at the end.
     */
    private fun decodes(bytes: ByteArray, from: Int, cut: Int): Boolean {
        val input = ByteBuffer.wrap(bytes, from, bytes.size - from)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val result = decoder.decode(input, CharBuffer.allocate(bytes.size), cut == 0)
        return result.isUnderflow && input.remaining() <= cut
    }

    /** The head byte for byte (ISO-8859-1, so no decoder hides a pattern), ASCII-lowercased. */
    private fun isWebPage(head: ByteArray): Boolean {
        val text = ReferenceUris.asciiLowercase(String(head, Charsets.ISO_8859_1))
        if (WEB_PAGE.any { it in text }) return true
        val declaration = text.indexOf(XML_DECLARATION)
        return declaration >= 0 && text.indexOf(XHTML_NAMESPACE, declaration + XML_DECLARATION.length) >= 0
    }
}
