package com.loosecannon.servicetag.core.fetch

import com.loosecannon.servicetag.core.testing.ZipFixtures
import com.loosecannon.servicetag.core.testing.ZipFixtures.entry
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
        return DocumentSniff.classify(file.size.toLong(), file.copyOfRange(0, n), file.copyOfRange(file.size - n, file.size))
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
        assertNull(DocumentSniff.classify(0, ByteArray(0), ByteArray(0)))
        assertNull(sniff(bytes(0xFF, 0xD8)))
    }

    private val big = ByteArray(3_000) { 'x'.code.toByte() }

    @Test
    fun aLargePdfWithEofOnlyInTheTailIsAPdf() {
        assertEquals("application/pdf", sniff(ascii("%PDF-1.4\n") + big + ascii("\n%%EOF\n")))
    }

    @Test
    fun aLargePdfWithHeaderOnlyInTheHeadIsAPdf() {
        val file = ascii("%PDF-1.5\n") + big + ascii("%%EOF")
        assertEquals("application/pdf", sniff(file))
        assertNull(sniff(big + ascii("%PDF-1.5\n%%EOF")))
    }

    @Test
    fun aLargePngWithIendOnlyInTheTailIsAPng() {
        assertEquals("image/png", sniff(pngHead + big + iend))
    }

    @Test
    fun aJpegTrailerOf1022PassesAnd1023IsRefused() {
        assertEquals("image/jpeg", sniff(jpegHead + big + eoi + ByteArray(1_022)))
        assertNull(sniff(jpegHead + big + eoi + ByteArray(1_023)))
    }

    @Test
    fun windowsThatAreNotTheExpectedLengthAreRefused() {
        val file = pdf
        val n = file.size
        assertEquals("application/pdf", DocumentSniff.classify(n.toLong(), file, file))
        assertNull(DocumentSniff.classify(n.toLong() + 1, file, file))
        assertNull(DocumentSniff.classify(n.toLong(), file, file.copyOfRange(1, n)))
        assertNull(DocumentSniff.classify(n.toLong(), file.copyOfRange(0, n - 1), file))
        assertNull(DocumentSniff.classify(-1, file, file))
    }

    // ---- row 35 (C27): ODF in the two windows — `mimetype` stored first, and the end record ends the tail ----

    @Test
    fun aMinimalOdtOdsAndOdpAreProvenByTheirMimetype() {
        for (mime in listOf(DocumentSniff.ODT, DocumentSniff.ODS, DocumentSniff.ODP)) {
            val file = ZipFixtures.odf(mime)
            assertEquals(mime, sniff(file), mime)
        }
    }

    @Test
    fun anOdtLongerThanTheWindowsWithAShortCommentIsAnOdt() {
        val media = ZipFixtures.stored("Pictures/pump.png", ByteArray(3_000) { (it * 31).toByte() })
        val file = ZipFixtures.odf(DocumentSniff.ODT, media, comment = "Example Pool Pump manual")
        assertEquals(true, file.size > 2 * DocumentSniff.WINDOW)
        assertEquals(DocumentSniff.ODT, sniff(file))
    }

    @Test
    fun aZipWithoutAMimetypeIsNotOdf() {
        assertNull(sniff(ZipFixtures.zip(entry("META-INF/manifest.xml"), entry("content.xml"))))
    }

    @Test
    fun aWrongMimetypeIsNotOdf() {
        assertNull(sniff(ZipFixtures.odf("application/zip")))
    }

    @Test
    fun aCompressedMimetypeIsNotOdf() {
        val file = ZipFixtures.zip(entry("mimetype", DocumentSniff.ODT), entry("content.xml"))
        assertNull(sniff(file))
    }

    @Test
    fun aMimetypeThatIsNotFirstIsNotOdf() {
        val file = ZipFixtures.zip(entry("content.xml"), entry("mimetype", DocumentSniff.ODT, stored = true))
        assertNull(sniff(file))
    }

    @Test
    fun aTemplateOrADrawingIsNotOdf() {
        assertNull(sniff(ZipFixtures.odf("application/vnd.oasis.opendocument.text-template")))
        assertNull(sniff(ZipFixtures.odf("application/vnd.oasis.opendocument.graphics")))
    }

    @Test
    fun anOdfWithoutItsEndRecordInTheTailIsNotOdf() {
        // bytes after the end record: its comment length no longer ends the file
        assertNull(sniff(ZipFixtures.odf(DocumentSniff.ODT) + bytes(0)))
        // a comment of about 1,000 bytes pushes the end record out of the tail window (a recorded limit)
        assertNull(sniff(ZipFixtures.odf(DocumentSniff.ODT, comment = "x".repeat(1_100))))
    }

    // ---- fix round 1 (review m3, m5, m6) ----

    @Test
    fun anEndSignatureInsideTheCommentIsNotOdf() {
        // the end record must be the last end signature in the tail at all (a recorded safe-side limit)
        assertNull(sniff(ZipFixtures.odf(DocumentSniff.ODT, comment = "PK\u0005\u0006 Example Pool Pump manual")))
    }

    @Test
    fun aLocalHeaderAndABareEndRecordIn26BytesIsNotOdf() {
        assertNull(sniff(bytes(0x50, 0x4B, 0x03, 0x04) + bytes(0x50, 0x4B, 0x05, 0x06) + ByteArray(18)))
    }

    @Test
    fun aMimetypeClaimingDeflateIsNotOdfEvenWithItsSizesRight() {
        assertNull(sniff(ZipFixtures.withByte(ZipFixtures.odf(DocumentSniff.ODT), 8, 8)))
    }

    // ---- row 40 (C30): GIF, WebP and RTF in the two windows — the header, and the end or the size, right ----

    /** A fictional 1×1 GIF: header, screen descriptor, a two-colour table, one image, then the trailer `3B`. */
    private fun gif(version: String) = ascii(version) +
        bytes(1, 0, 1, 0, 0x80, 0, 0, 0, 0, 0, 0xFF, 0xFF, 0xFF) +
        bytes(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0) + bytes(0x3B)

    private fun le32(n: Long) = ByteArray(4) { ((n ushr (8 * it)) and 0xFF).toByte() }

    /** A fictional WebP: `RIFF`, the size of what follows (or [riffSize]), `WEBP`, then one [chunk] of [payload]. */
    private fun webp(chunk: String, payload: ByteArray = bytes(0x2F, 0, 0, 0, 0x10, 7, 0x10, 0x11, 0x11, 0x88), riffSize: Long? = null): ByteArray {
        val form = ascii("WEBP") + ascii(chunk) + le32(payload.size.toLong()) + payload
        return ascii("RIFF") + le32(riffSize ?: form.size.toLong()) + form
    }

    private val rtf = ascii("{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Example Sans;}}\\f0 Example Pool Pump manual\\par}")

    @Test
    fun aMinimalGif87aAndGif89aAreGifs() {
        assertEquals("image/gif", sniff(gif("GIF87a")))
        assertEquals("image/gif", sniff(gif("GIF89a")))
    }

    @Test
    fun aLargeGifWithItsTrailerOnlyInTheTailIsAGif() {
        val small = gif("GIF89a")
        assertEquals("image/gif", sniff(small.copyOf(small.size - 1) + big + bytes(0x3B)))
    }

    @Test
    fun aGifCutBeforeItsTrailerIsNotADocument() {
        val small = gif("GIF89a")
        assertNull(sniff(small.copyOf(small.size - 1)))
        assertNull(sniff(small.copyOf(small.size - 1) + big))
        assertNull(sniff(small + bytes(0)), "a byte after the trailer (a recorded limit)")
    }

    @Test
    fun aGifHeaderOfAnotherVersionIsNotAGif() {
        assertNull(sniff(gif("GIF88a")))
        assertNull(sniff(gif("gif89a")))
    }

    @Test
    fun aWebpOfEachChunkKindIsAWebp() {
        for (chunk in listOf("VP8 ", "VP8L", "VP8X")) assertEquals("image/webp", sniff(webp(chunk)), chunk)
    }

    @Test
    fun aLargeWebpIsJudgedByItsRiffSizeAgainstTheFileSize() {
        assertEquals("image/webp", sniff(webp("VP8 ", big)))
    }

    @Test
    fun aWebpWhoseRiffSizeIsNotTheFileSizeIsNotADocument() {
        val file = webp("VP8L")
        val riffSize = file.size - 8L
        assertNull(sniff(webp("VP8L", riffSize = riffSize + 1)), "a byte promised that never came")
        assertNull(sniff(webp("VP8L", riffSize = riffSize - 1)), "a byte after the RIFF")
        assertNull(sniff(file + bytes(0)))
        assertNull(sniff(webp("VP8 ", big, riffSize = 0xFFFF_FFF8L)), "the size is unsigned")
    }

    @Test
    fun aRiffThatIsNotAWebpOrAnUnknownChunkIsNotAWebp() {
        assertNull(sniff(webp("VP8A")))
        assertNull(sniff(webp("VP8\u0000")), "VP8 needs its space")
        assertNull(sniff(webp("VP8 ").also { ascii("WAVE").copyInto(it, 8) }))
    }

    @Test
    fun anRtfIsAnRtfWithOrWithoutTrailingPadding() {
        assertEquals("application/rtf", sniff(rtf))
        assertEquals("application/rtf", sniff(rtf + ascii("\r\n")))
        assertEquals("application/rtf", sniff(rtf + ascii(" \t\r\n") + bytes(0, 0)))
    }

    @Test
    fun aLargeRtfWithItsBraceOnlyInTheTailIsAnRtf() {
        assertEquals("application/rtf", sniff(ascii("{\\rtf1\\ansi ") + big + ascii("}\r\n")))
        val padded = ascii("{\\rtf1\\ansi ") + big + ascii("}") + ByteArray(1_100) { ' '.code.toByte() }
        assertNull(sniff(padded), "a brace behind more than a window of padding (a recorded limit)")
    }

    @Test
    fun anRtfWithoutItsClosingBraceIsNotADocument() {
        assertNull(sniff(rtf.copyOf(rtf.size - 1) + ascii("\r\n")))
        assertNull(sniff(ascii("{\\rtf1\\ansi ") + big + ascii("\r\n")))
        assertNull(sniff(rtf + ascii("x\r\n")))
    }

    @Test
    fun rtfWithoutItsVersionOneIsNotAnRtf() {
        assertNull(sniff(ascii("{\\rtf\\ansi Example Pool Pump manual\\par}")))
        assertNull(sniff(ascii("{\\rtf0\\ansi Example Pool Pump manual\\par}")))
        assertNull(sniff(ascii(" {\\rtf1\\ansi Example Pool Pump manual\\par}")), "the head must start with it")
    }
}
