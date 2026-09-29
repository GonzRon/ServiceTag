package com.loosecannon.servicetag.core.fetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Row 17 (C11, R85-5): the sniff judges two byte windows, start AND end, and never a declared type.
 * Every fixture is a fictional byte array built here.
 */
class DocumentSniffTest {

    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** What the fetch will hand over: the first and last min(size, WINDOW) bytes. */
    private fun sniff(file: ByteArray): String? {
        val n = minOf(file.size, DocumentSniff.WINDOW)
        return DocumentSniff.classify(file.copyOfRange(0, n), file.copyOfRange(file.size - n, file.size))
    }

    private val filler = ByteArray(300) { 'x'.code.toByte() }

    private val pdf = ascii("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n")

    private val iend = bytes(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82)

    private val pngHead = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        bytes(0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52, 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0, 0x90, 0x77, 0x53, 0xDE) +
        bytes(0, 0, 0, 4, 0x49, 0x44, 0x41, 0x54, 1, 2, 3, 4, 0, 0, 0, 0)

    private val jpegHead = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 16, 0x4A, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0)

    private val eoi = bytes(0xFF, 0xD9)

    @Test
    fun aMinimalPdfIsAPdf() {
        assertEquals("application/pdf", sniff(pdf + filler.copyOf(0)))
    }

    @Test
    fun aPdfWithJunkBeforeItsHeaderUnder1KiBIsAPdf() {
        assertEquals("application/pdf", sniff(ByteArray(200) { 'j'.code.toByte() } + pdf))
    }

    @Test
    fun aPdfWithoutEofIsNotADocument() {
        assertNull(sniff(ascii("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n") + filler))
    }

    @Test
    fun htmlBeginningWithAFakePdfHeaderIsNotADocument() {
        val html = ascii("%PDF-1.7\n<!doctype html><html><body>Please sign in to continue</body></html>") + filler
        assertNull(sniff(html))
    }

    @Test
    fun aPngEndingInIendIsAPng() {
        assertEquals("image/png", sniff(pngHead + filler + iend))
    }

    @Test
    fun aPngWithBytesAfterIendIsNotADocument() {
        assertNull(sniff(pngHead + filler + iend + bytes(0)))
    }

    @Test
    fun aTruncatedPngIsNotADocument() {
        assertNull(sniff(pngHead + filler))
    }

    @Test
    fun aJpegWithEoiIsAJpeg() {
        assertEquals("image/jpeg", sniff(jpegHead + filler + eoi))
    }

    @Test
    fun aTruncatedJpegIsNotADocument() {
        assertNull(sniff(jpegHead + filler))
    }

    @Test
    fun aJpegWithAShortTrailerAfterEoiIsAJpeg() {
        assertEquals("image/jpeg", sniff(jpegHead + filler + eoi + ByteArray(500)))
    }

    @Test
    fun aJpegWithMoreThan1024BytesAfterEoiIsNotADocument() {
        assertNull(sniff(jpegHead + filler + eoi + ByteArray(1_025)))
    }

    @Test
    fun aZipIsNotADocument() {
        assertNull(sniff(bytes(0x50, 0x4B, 0x03, 0x04) + filler + bytes(0x50, 0x4B, 0x05, 0x06)))
    }

    @Test
    fun aFileSmallerThanOneWindowIsJudgedOnBoth() {
        val small = ascii("%PDF-1.4\nsmall body here\n%%EOF\n") // 30 bytes
        assertEquals("application/pdf", sniff(small))
        val forty = ascii("%PDF-1.4\n") + ByteArray(31) { 'y'.code.toByte() } // 40 bytes, header only
        assertEquals(40, forty.size)
        assertNull(sniff(forty))
        val fortyPng = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(20) + iend // 40 bytes
        assertEquals(40, fortyPng.size)
        assertEquals("image/png", sniff(fortyPng))
    }

    @Test
    fun emptyAndTinyInputsAreNotDocuments() {
        assertNull(DocumentSniff.classify(ByteArray(0), ByteArray(0)))
        assertNull(sniff(bytes(0xFF, 0xD8)))
    }
}
