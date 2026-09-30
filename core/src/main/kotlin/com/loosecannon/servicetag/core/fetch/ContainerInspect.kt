package com.loosecannon.servicetag.core.fetch

import com.loosecannon.servicetag.core.model.MimeTypes
import java.io.ByteArrayOutputStream

/**
 * C28 (#85, R85-5 widened): the one bounded, read-only look inside a staged container whose head the two
 * windows left undecided. Every byte comes through one [BoundedInspection], and nothing is decompressed.
 * The ZIP arm proves OOXML from the central directory's names alone; the flavour is the one main part's,
 * never a declared type or a URL extension, so an arbitrary ZIP is refused whatever it was called. The OLE2
 * arm (C29) proves DOC, XLS or PPT from the compound file's directory names alone, never from its signature.
 */
object ContainerInspect {
    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    const val DOC = "application/msword"
    const val XLS = "application/vnd.ms-excel"
    const val PPT = "application/vnd.ms-powerpoint"

    /** The end record's farthest reach: its 22 bytes and a 65,535-byte comment. */
    private const val END_REACH = 65_557
    private const val MAX_DIRECTORY_BYTES = 262_144
    private const val MAX_ENTRIES = 4_096
    private val central = byteArrayOf(0x50, 0x4B, 0x01, 0x02)
    private val mainParts = mapOf("word/document.xml" to DOCX, "xl/workbook.xml" to XLSX, "ppt/presentation.xml" to PPTX)
    private val cfbSignature = listOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1).map { it.toByte() }.toByteArray()
    private val contentStreams = mapOf("WordDocument" to DOC, "Workbook" to XLS, "Book" to XLS, "PowerPoint Document" to PPT)
    private const val NO_ENTRY = 0xFFFF_FFFFL

    /** Whether [head] is one this inspection is for: a ZIP local header or the compound-file signature. */
    fun inspects(head: ByteArray): Boolean = DocumentSniff.isZip(head) || isCfb(head)

    /**
     * The MIME the container proves, or null. [size], [head] and [tail] are as for [DocumentSniff.classify].
     * A read past [inspection]'s budget is null: refused, never a partial answer. An IOException from the
     * staged file propagates (the fetch's `Interrupted`).
     */
    fun classify(size: Long, head: ByteArray, tail: ByteArray, inspection: BoundedInspection): String? = try {
        when {
            DocumentSniff.isZip(head) -> ooxml(size, inspection)
            isCfb(head) -> legacy(size, head, inspection)
            else -> null
        }
    } catch (e: InspectionOverBudget) {
        null
    }

    private fun isCfb(head: ByteArray) = DocumentSniff.regionMatches(head, 0, cfbSignature)

    /**
     * C29 (3)–(4): among the root storage's own children, exactly one family's content stream: `WordDocument`
     * (DOC), `Workbook` or `Book` (XLS), or `PowerPoint Document` (PPT), whole and NUL-terminated, never a
     * prefix. None (a bare signature, an installer, a message) or two families is refused, and families are
     * counted case-insensitively up to the first NUL, as MS-CFB §2.6.4 compares names, so `WORKBOOK` beside
     * `WordDocument` is a second family (review m1); a case variant alone is no proof. The walk follows only
     * sibling ids from the root's child, never a storage's own child, so an embedded object (a document's
     * `ObjectPool/…/Workbook`, a message's attached `WordDocument`) never counts. No stream's contents are read.
     */
    private fun legacy(size: Long, head: ByteArray, inspection: BoundedInspection): String? {
        val sectors = CompoundFile.open(size, head, inspection)?.directory() ?: return null
        val directory = ByteArrayOutputStream().apply { sectors.forEach { write(it) } }.toByteArray()
        for (at in directory.indices step 128) {
            val length = directory.u16(at + 64)
            if (directory[at + 66].toInt() != 0 && length != 0 && (length > 64 || length % 2 != 0)) return null
        }
        if (directory[66].toInt() != 5) return null // entry 0 must be the root storage
        val proofs = HashSet<String>()
        val families = HashSet<String>()
        val seen = HashSet<Long>()
        val pending = ArrayDeque(listOf(directory.u32(76)))
        while (pending.isNotEmpty()) {
            val id = pending.removeLast()
            if (id == NO_ENTRY) continue
            if (id >= directory.size / 128 || !seen.add(id)) return null // outside the entries read, or a cycle
            val at = id.toInt() * 128
            val type = directory[at + 66].toInt()
            if (type != 1 && type != 2) return null // a sibling must be a storage or a stream
            val name = String(directory, at, directory.u16(at + 64), Charsets.UTF_16LE)
            if (type == 2) {
                val base = name.substringBefore('\u0000')
                contentStreams.entries.firstOrNull { it.key.equals(base, ignoreCase = true) }?.let { families += it.value }
                if (name.lastOrNull() == '\u0000') contentStreams[name.dropLast(1)]?.let { proofs += it }
            }
            pending += directory.u32(at + 68)
            pending += directory.u32(at + 72) // left and right siblings only: a storage's child (at + 76) is never followed
        }
        return families.singleOrNull()?.takeIf { it in proofs }
    }

    /**
     * C29 (1)–(2): an OLE2 compound file (MS-CFB) read through one [BoundedInspection]: the header from the head
     * window, then only the directory chain, followed through the FAT sectors the header's 109 slots and at most
     * [MAX_DIFAT] DIFAT sectors list. Every sector must lie wholly inside the file and is read at most once, so
     * a cycle is refused, never followed; a FAT sector, once read, is kept.
     */
    private class CompoundFile(
        private val size: Long,
        private val header: ByteArray,
        private val shift: Int,
        private val inspection: BoundedInspection,
    ) {
        private val sectorSize = 1 shl shift
        private val perSector = sectorSize / 4
        private val read = HashSet<Long>()
        private val fats = HashMap<Long, ByteArray>()
        private val difats = ArrayList<ByteArray>()

        /** The directory's sectors in chain order, at most [MAX_DIRECTORY]; null for an empty or broken chain. */
        fun directory(): List<ByteArray>? {
            val sectors = ArrayList<ByteArray>()
            var id = header.u32(48)
            while (id != END_OF_CHAIN) {
                if (sectors.size == MAX_DIRECTORY) return null
                sectors += sector(id) ?: return null
                id = next(id) ?: return null
            }
            return sectors.ifEmpty { null }
        }

        private fun next(id: Long): Long? {
            val fat = fatSector(id / perSector) ?: return null
            val entries = fats[fat] ?: (sector(fat) ?: return null).also { fats[fat] = it }
            return entries.u32(4 * (id % perSector).toInt())
        }

        /** The [k]th FAT sector's id: a header slot, then a DIFAT sector's (whose last slot links the next). */
        private fun fatSector(k: Long): Long? {
            if (k < HEADER_SLOTS) return header.u32(76 + 4 * k.toInt())
            val j = (k - HEADER_SLOTS) / (perSector - 1)
            if (j >= MAX_DIFAT) return null
            while (difats.size <= j) {
                val id = if (difats.isEmpty()) header.u32(68) else difats.last().u32(sectorSize - 4)
                difats += sector(id) ?: return null
            }
            return difats[j.toInt()].u32(4 * ((k - HEADER_SLOTS) % (perSector - 1)).toInt())
        }

        /** Sector [id], whole and read once; a sentinel id, a sector not wholly inside the file or a second read is null. */
        private fun sector(id: Long): ByteArray? {
            val position = (id + 1) shl shift
            if (id > MAX_REGULAR || position + sectorSize > size || !read.add(id)) return null
            return inspection.readAt(position, sectorSize).takeIf { it.size == sectorSize }
        }

        companion object {
            private const val END_OF_CHAIN = 0xFFFF_FFFEL
            private const val MAX_REGULAR = 0xFFFF_FFFAL
            private const val HEADER_SLOTS = 109
            private const val MAX_DIFAT = 8
            private const val MAX_DIRECTORY = 64

            /** C29 (1): byte order `FE FF`; version 3 with 512-byte or 4 with 4,096-byte sectors; 64-byte mini sectors. */
            fun open(size: Long, head: ByteArray, inspection: BoundedInspection): CompoundFile? {
                if (head.size < 512) return null
                val major = head.u16(26)
                val shift = head.u16(30)
                val valid = head.u16(28) == 0xFFFE && head.u16(32) == 6 && (major == 3 && shift == 9 || major == 4 && shift == 12)
                return if (valid) CompoundFile(size, head, shift, inspection) else null
            }
        }
    }

    /** Steps 5–7: both package parts, exactly one main part, and no macro part name (`vbaProject.bin`, `vbaData.xml`). */
    private fun ooxml(size: Long, inspection: BoundedInspection): String? {
        val names = centralNames(size, inspection) ?: return null
        if (names.any { it.endsWith("vbaProject.bin", ignoreCase = true) || it.endsWith("vbaData.xml", ignoreCase = true) }) return null
        if ("[Content_Types].xml" !in names || "_rels/.rels" !in names) return null
        return names.mapNotNull { mainParts[it] }.singleOrNull()
    }

    /**
     * Steps 1–4: every central-directory name (UTF-8), or null for anything but a plain, bounded ZIP. The
     * directory is the one every reader finds: it ends exactly at the end record, both entry counts agree,
     * and its records fill its declared size exactly, so no record is hidden past the count (review MJ1, MJ2).
     */
    private fun centralNames(size: Long, inspection: BoundedInspection): List<String>? {
        val reach = minOf(size, END_REACH.toLong()).toInt()
        val end = inspection.readAt(size - reach, reach)
        if (end.size != reach) return null
        val at = DocumentSniff.zipEndRecord(end)
        if (at < 0) return null
        val entries = end.u16(at + 10)
        val length = end.u32(at + 12)
        val offset = end.u32(at + 16)
        if (end.u16(at + 8) != entries || entries == 0xFFFF || length == 0xFFFF_FFFFL || offset == 0xFFFF_FFFFL) return null // ZIP64, or counts that disagree
        if (entries > MAX_ENTRIES || length > MAX_DIRECTORY_BYTES || offset + length != size - reach + at) return null
        val directory = inspection.readAt(offset, length.toInt())
        if (directory.size.toLong() != length) return null
        val names = ArrayList<String>(entries)
        var p = 0
        repeat(entries) {
            if (p + 46 > directory.size || !DocumentSniff.regionMatches(directory, p, central)) return null
            val name = directory.u16(p + 28)
            val next = p + 46 + name + directory.u16(p + 30) + directory.u16(p + 32)
            if (next > directory.size) return null
            names += String(directory, p + 46, name, Charsets.UTF_8)
            p = next
        }
        return if (p == directory.size) names else null
    }
}

/**
 * C28 (7) (owner): the macro-enabled, template, slideshow, add-in and slide OOXML types, as a ZIP-headed
 * file's served label names them. A label only ever refuses; it never proves a package. #85 validates
 * document families and is not an active-content sanitizer, so an accepted package is not thereby
 * macro-free: it is only not one of the forms the fetch can identify.
 */
object OoxmlExclusions {
    val EXTENSIONS: Set<String> = setOf(
        "docm", "dotx", "dotm", "xlsm", "xltx", "xltm", "xlam", "pptm", "potx", "potm", "ppsx", "ppsm", "ppam", "sldx", "sldm",
    )

    val MIME_TYPES: Set<String> = listOf(
        "application/vnd.ms-word.document.macroEnabled.12",
        "application/vnd.ms-word.template.macroEnabled.12",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
        "application/vnd.ms-excel.sheet.macroEnabled.12",
        "application/vnd.ms-excel.template.macroEnabled.12",
        "application/vnd.ms-excel.addin.macroEnabled.12",
        "application/vnd.ms-excel.sheet.binary.macroEnabled.12",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
        "application/vnd.ms-powerpoint.presentation.macroEnabled.12",
        "application/vnd.ms-powerpoint.template.macroEnabled.12",
        "application/vnd.ms-powerpoint.slideshow.macroEnabled.12",
        "application/vnd.ms-powerpoint.addin.macroEnabled.12",
        "application/vnd.ms-powerpoint.slide.macroEnabled.12",
        "application/vnd.openxmlformats-officedocument.presentationml.template",
        "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
        "application/vnd.openxmlformats-officedocument.presentationml.slide",
    ).map { it.lowercase() }.toSet()

    /** Whether the declared type (normalised) or the final URL's extension (lowercased) names an excluded type. */
    fun refuses(declared: String?, extension: String?): Boolean =
        (declared != null && MimeTypes.normalise(declared) in MIME_TYPES) ||
            (extension != null && extension.lowercase() in EXTENSIONS)
}
