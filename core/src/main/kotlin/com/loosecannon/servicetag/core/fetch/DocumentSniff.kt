package com.loosecannon.servicetag.core.fetch

/**
 * C11 (#85, R85-5), widened by C27 and C30: the byte sniff. A pure function over the two windows of a staged
 * file, deciding PDF, PNG, JPEG, GIF, WebP, RTF, ODT, ODS, ODP or nothing. It takes no declared type and reads
 * nothing but the windows; it checks signatures and end markers and parses nothing between them. A header
 * alone is never enough: each kind must also end right. A ZIP it does not decide is `ContainerInspect`'s (C28).
 */
object DocumentSniff {
    const val WINDOW = 1_024

    const val PDF = "application/pdf"
    const val PNG = "image/png"
    const val JPEG = "image/jpeg"
    const val ODT = "application/vnd.oasis.opendocument.text"
    const val ODS = "application/vnd.oasis.opendocument.spreadsheet"
    const val ODP = "application/vnd.oasis.opendocument.presentation"
    const val GIF = "image/gif"
    const val WEBP = "image/webp"
    const val RTF = "application/rtf"

    private val pdfHeader = "%PDF-".toByteArray(Charsets.ISO_8859_1)
    private val pdfEof = "%%EOF".toByteArray(Charsets.ISO_8859_1)
    private val pngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private val pngIend = byteArrayOf(
        0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )
    private val jpegSoi = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val jpegEoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    private val zipLocal = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val zipEnd = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    private val odfEntry = "mimetype".toByteArray(Charsets.ISO_8859_1)
    private val odfTypes = listOf(ODT, ODS, ODP)
    private val gifHeaders = listOf("GIF87a", "GIF89a").map { it.toByteArray(Charsets.ISO_8859_1) }
    private const val GIF_TRAILER: Byte = 0x3B
    private val riff = "RIFF".toByteArray(Charsets.ISO_8859_1)
    private val webpForm = "WEBP".toByteArray(Charsets.ISO_8859_1)
    private val webpChunks = listOf("VP8 ", "VP8L", "VP8X").map { it.toByteArray(Charsets.ISO_8859_1) }
    private val rtfHeader = "{\\rtf1".toByteArray(Charsets.ISO_8859_1)
    private val rtfPadding = " \t\r\n\u0000".toByteArray(Charsets.ISO_8859_1)

    /**
     * The MIME the bytes prove, or null. [size] is the file's length; [head] must be its first and [tail] its
     * last min([size], [WINDOW]) bytes (for a small file they overlap). Windows of any other length are
     * refused as not a document: a caller bug must never read as a document. A JPEG's EOI must lie within the
     * last [WINDOW] bytes, so a trailer after EOI of up to 1,022 bytes passes and 1,023 or more is refused
     * (a recorded safe-side limit).
     */
    fun classify(size: Long, head: ByteArray, tail: ByteArray): String? {
        val expected = minOf(size, WINDOW.toLong())
        if (size < 0 || head.size.toLong() != expected || tail.size.toLong() != expected) return null
        return when {
            isPdf(head, tail) -> PDF
            isPng(head, tail) -> PNG
            isJpeg(head, tail) -> JPEG
            isGif(head, tail) -> GIF
            isWebp(size, head) -> WEBP
            isRtf(head, tail) -> RTF
            else -> odf(head, tail)
        }
    }

    private fun isPdf(head: ByteArray, tail: ByteArray): Boolean {
        val at = indexOf(head, pdfHeader)
        if (at < 0) return false
        val v = at + pdfHeader.size
        val versioned = v + 2 < head.size && head[v].isDigit() && head[v + 1] == '.'.code.toByte() && head[v + 2].isDigit()
        return versioned && indexOf(tail, pdfEof) >= 0
    }

    private fun isPng(head: ByteArray, tail: ByteArray): Boolean =
        startsWith(head, pngSignature) && endsWith(tail, pngIend)

    private fun isJpeg(head: ByteArray, tail: ByteArray): Boolean =
        startsWith(head, jpegSoi) && indexOf(tail, jpegEoi) >= 0

    /** C30: `GIF87a` or `GIF89a`, and the file's last byte is the trailer. */
    private fun isGif(head: ByteArray, tail: ByteArray): Boolean =
        gifHeaders.any { startsWith(head, it) } && tail.lastOrNull() == GIF_TRAILER

    /** C30: `RIFF`, a little-endian size, `WEBP`, then a `VP8 `, `VP8L` or `VP8X` chunk; the RIFF ends the file. */
    private fun isWebp(size: Long, head: ByteArray): Boolean =
        head.size >= 16 && startsWith(head, riff) && regionMatches(head, 8, webpForm) &&
            webpChunks.any { regionMatches(head, 12, it) } && head.u32(4) + 8 == size

    /** C30: `{\rtf1`, and the last byte that is not a space, tab, CR, LF or NUL closes the group. */
    private fun isRtf(head: ByteArray, tail: ByteArray): Boolean =
        startsWith(head, rtfHeader) && tail.lastOrNull { it !in rtfPadding } == '}'.code.toByte()

    /**
     * C27: a local header at 0 naming exactly `mimetype`, stored (method 0) with equal sizes, its data exactly
     * one of the three types; and a ZIP end record ending the tail. Nothing else in the package is parsed.
     */
    private fun odf(head: ByteArray, tail: ByteArray): String? {
        if (!isZip(head) || head.size < 30 || zipEndRecord(tail) < 0) return null
        val name = head.u16(26)
        val data = 30 + name + head.u16(28)
        val length = head.u32(18)
        if (head.u16(8) != 0 || length != head.u32(22) || name != odfEntry.size || !regionMatches(head, 30, odfEntry)) return null
        return odfTypes.firstOrNull { type ->
            val value = type.toByteArray(Charsets.ISO_8859_1)
            length == value.size.toLong() && regionMatches(head, data, value)
        }
    }

    /** A ZIP local file header at 0 (C27, C28). */
    internal fun isZip(head: ByteArray): Boolean = startsWith(head, zipLocal)

    /**
     * Where the ZIP end record starts in [window] (the file's last bytes), or -1. It is the last end signature
     * in the window at all, and its comment length must equal the bytes after its 22-byte record, so the ZIP
     * ends where the file does. An end signature after it (inside the comment) refuses (review m3).
     */
    internal fun zipEndRecord(window: ByteArray): Int {
        var at = window.size - zipEnd.size
        while (at >= 0 && !regionMatches(window, at, zipEnd)) at--
        return if (at in 0..window.size - 22 && at + 22 + window.u16(at + 20) == window.size) at else -1
    }

    internal fun regionMatches(a: ByteArray, at: Int, p: ByteArray): Boolean =
        at >= 0 && at + p.size <= a.size && p.indices.all { a[at + it] == p[it] }

    private fun Byte.isDigit() = this in '0'.code.toByte()..'9'.code.toByte()

    private fun startsWith(a: ByteArray, p: ByteArray): Boolean =
        a.size >= p.size && p.indices.all { a[it] == p[it] }

    private fun endsWith(a: ByteArray, p: ByteArray): Boolean =
        a.size >= p.size && p.indices.all { a[a.size - p.size + it] == p[it] }

    private fun indexOf(a: ByteArray, p: ByteArray): Int {
        var i = 0
        while (i + p.size <= a.size) {
            if (p.indices.all { a[i + it] == p[it] }) return i
            i++
        }
        return -1
    }
}

/** Little-endian fields, as ZIP stores them. */
internal fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
