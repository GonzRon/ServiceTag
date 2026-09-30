package com.loosecannon.servicetag.core.testing

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ZIP packages for the container rows (#85 rows 35–37), written here with `java.util.zip` (tests only).
 * Every part is one fictional line; no real document, name or host.
 */
object ZipFixtures {
    const val PART = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><p>Example Pool Pump manual</p>"

    class Entry(val name: String, val content: ByteArray, val stored: Boolean, val comment: String?)

    fun entry(name: String, content: String = PART, stored: Boolean = false, comment: String? = null) =
        Entry(name, content.toByteArray(Charsets.UTF_8), stored, comment)

    fun stored(name: String, content: ByteArray) = Entry(name, content, stored = true, comment = null)

    /** [entries] in order, DEFLATED unless stored (a STORED entry carries its size and CRC); [comment] is the archive's. */
    fun zip(vararg entries: Entry, comment: String? = null): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            comment?.let { zip.setComment(it) }
            for (e in entries) {
                val z = ZipEntry(e.name)
                e.comment?.let { z.comment = it }
                if (e.stored) {
                    z.method = ZipEntry.STORED
                    z.size = e.content.size.toLong()
                    z.compressedSize = z.size
                    z.crc = CRC32().apply { update(e.content) }.value
                }
                zip.putNextEntry(z)
                zip.write(e.content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /** An ODF package: `mimetype` STORED first with its CRC (ODF 1.2 Part 3 §3.3), the manifest, the content, then [more]. */
    fun odf(mimetype: String, vararg more: Entry, comment: String? = null) =
        zip(entry("mimetype", mimetype, stored = true), entry("META-INF/manifest.xml"), entry("content.xml"), *more, comment = comment)

    /** A minimal OOXML package: the content types, the package relationships, then [parts]. */
    fun ooxml(vararg parts: String) = zip(entry("[Content_Types].xml"), entry("_rels/.rels"), *parts.map { entry(it) }.toTypedArray())

    /** Where [zip]'s end record starts (no fixture comment holds its signature). */
    fun endRecord(zip: ByteArray): Int = (zip.size - 22 downTo 0).first {
        zip[it] == 0x50.toByte() && zip[it + 1] == 0x4B.toByte() && zip[it + 2] == 5.toByte() && zip[it + 3] == 6.toByte()
    }

    /** A copy of [zip] whose end-record field at [offset] (from the record's start) is [value], little-endian over [width] bytes. */
    fun withEndField(zip: ByteArray, offset: Int, width: Int, value: Long): ByteArray {
        val at = endRecord(zip) + offset
        return zip.copyOf().also { b -> repeat(width) { b[at + it] = (value shr (8 * it)).toByte() } }
    }

    /** A copy of [zip] with [value] written at [at]. */
    fun withByte(zip: ByteArray, at: Int, value: Int): ByteArray = zip.copyOf().also { it[at] = value.toByte() }
}
