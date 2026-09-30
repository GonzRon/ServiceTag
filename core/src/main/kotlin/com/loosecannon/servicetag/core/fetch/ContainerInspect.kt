package com.loosecannon.servicetag.core.fetch

import com.loosecannon.servicetag.core.model.MimeTypes

/**
 * C28 (#85, R85-5 widened): the one bounded, read-only look inside a staged container whose head the two
 * windows left undecided. Every byte comes through one [BoundedInspection], and nothing is decompressed.
 * The ZIP arm proves OOXML from the central directory's names alone; the flavour is the one main part's,
 * never a declared type or a URL extension, so an arbitrary ZIP is refused whatever it was called.
 */
object ContainerInspect {
    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

    /** The end record's farthest reach: its 22 bytes and a 65,535-byte comment. */
    private const val END_REACH = 65_557
    private const val MAX_DIRECTORY_BYTES = 262_144
    private const val MAX_ENTRIES = 4_096
    private val central = byteArrayOf(0x50, 0x4B, 0x01, 0x02)
    private val mainParts = mapOf("word/document.xml" to DOCX, "xl/workbook.xml" to XLSX, "ppt/presentation.xml" to PPTX)

    /** Whether [head] is one this inspection is for: a ZIP local header. */
    fun inspects(head: ByteArray): Boolean = DocumentSniff.isZip(head)

    /**
     * The MIME the container proves, or null. [size], [head] and [tail] are as for [DocumentSniff.classify].
     * A read past [inspection]'s budget is null: refused, never a partial answer. An IOException from the
     * staged file propagates (the fetch's `Interrupted`).
     */
    fun classify(size: Long, head: ByteArray, tail: ByteArray, inspection: BoundedInspection): String? = try {
        if (DocumentSniff.isZip(head)) ooxml(size, inspection) else null
    } catch (e: InspectionOverBudget) {
        null
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
