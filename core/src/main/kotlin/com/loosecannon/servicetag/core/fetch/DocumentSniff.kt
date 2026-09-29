package com.loosecannon.servicetag.core.fetch

/**
 * C11 (#85, R85-5): the byte sniff. A pure function over the two windows of a staged file, deciding PDF, PNG,
 * JPEG or nothing. It takes no declared type and reads nothing but the windows; it checks signatures and end
 * markers and parses nothing between them. A header alone is never enough: each kind must also end right.
 */
object DocumentSniff {
    const val WINDOW = 1_024

    const val PDF = "application/pdf"
    const val PNG = "image/png"
    const val JPEG = "image/jpeg"

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
            else -> null
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
