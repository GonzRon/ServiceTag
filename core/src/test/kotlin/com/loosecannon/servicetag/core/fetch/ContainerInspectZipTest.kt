package com.loosecannon.servicetag.core.fetch

import com.loosecannon.servicetag.core.fetch.ContainerInspect.DOCX
import com.loosecannon.servicetag.core.fetch.ContainerInspect.PPTX
import com.loosecannon.servicetag.core.fetch.ContainerInspect.XLSX
import com.loosecannon.servicetag.core.testing.BytesStagedReader
import com.loosecannon.servicetag.core.testing.ZipFixtures
import com.loosecannon.servicetag.core.testing.ZipFixtures.entry
import com.loosecannon.servicetag.core.testing.ZipFixtures.ooxml
import com.loosecannon.servicetag.core.testing.ZipFixtures.withEndField
import com.loosecannon.servicetag.core.testing.ZipFixtures.zip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Row 36 (C28): a ZIP is OOXML only when its central directory names `[Content_Types].xml`, `_rels/.rels`
 * and exactly one main part, and no macro project. Nothing is decompressed; an arbitrary ZIP is refused.
 */
class ContainerInspectZipTest {

    private fun head(file: ByteArray) = file.copyOfRange(0, minOf(file.size, DocumentSniff.WINDOW))

    private fun tail(file: ByteArray) = file.copyOfRange(file.size - minOf(file.size, DocumentSniff.WINDOW), file.size)

    private fun inspect(file: ByteArray, inspection: BoundedInspection = BoundedInspection(BytesStagedReader.of(file))) =
        ContainerInspect.classify(file.size.toLong(), head(file), tail(file), inspection)

    private val docx = ooxml("word/document.xml")

    // ---- pass ----

    @Test
    fun aMinimalDocxXlsxAndPptxAreProvenByTheirMainPart() {
        assertEquals(DOCX, inspect(docx))
        assertEquals(XLSX, inspect(ooxml("xl/workbook.xml")))
        assertEquals(PPTX, inspect(ooxml("ppt/presentation.xml")))
    }

    @Test
    fun theFlavourIsTheOneMainPartNotAFolderOrTheOrder() {
        val file = ooxml("word/styles.xml", "docProps/core.xml", "ppt/slides/slide1.xml", "xl/workbook.xml")
        assertEquals(XLSX, inspect(file))
    }

    @Test
    fun aPackageLargerThanTheWindowsWithACommentIsADocx() {
        val media = ZipFixtures.stored("word/media/pump.png", ByteArray(5_000) { (it * 31).toByte() })
        val file = zip(
            entry("[Content_Types].xml"), entry("_rels/.rels"), entry("word/document.xml"), media,
            comment = "Example Pool Pump manual",
        )
        assertEquals(DOCX, inspect(file))
    }

    @Test
    fun theInspectionReadsTheEndThenTheDirectoryAndNothingElse() {
        val reader = BytesStagedReader.of(docx)
        val inspection = BoundedInspection(reader)
        assertEquals(DOCX, inspect(docx, inspection))
        assertEquals(2, reader.reads)
        val end = ZipFixtures.endRecord(docx)
        val directory = docx[end + 12].toInt() and 0xFF or ((docx[end + 13].toInt() and 0xFF) shl 8)
        assertEquals(docx.size + directory, inspection.bytes, "the whole small file, then the directory once")
    }

    // ---- fail ----

    @Test
    fun anArbitraryZipIsRefused() {
        assertNull(inspect(zip(entry("readme.txt", "Example Pool Pump manual"))))
        assertNull(inspect(zip(entry("word/document.xml"))), "a main part alone is not a package")
    }

    @Test
    fun withoutContentTypesItIsRefused() {
        assertNull(inspect(zip(entry("_rels/.rels"), entry("word/document.xml"))))
    }

    @Test
    fun withoutThePackageRelationshipsItIsRefused() {
        assertNull(inspect(zip(entry("[Content_Types].xml"), entry("word/document.xml"))))
    }

    @Test
    fun twoMainPartsAreRefused() {
        assertNull(inspect(ooxml("word/document.xml", "xl/workbook.xml")))
        assertNull(inspect(ooxml("xl/workbook.xml", "ppt/presentation.xml")))
    }

    @Test
    fun aMacroEnabledPackageIsRefused() {
        assertNull(inspect(ooxml("word/document.xml", "word/vbaProject.bin")))
        assertNull(inspect(ooxml("xl/workbook.xml", "xl/vbaProject.bin")))
        assertNull(inspect(ooxml("ppt/presentation.xml", "ppt/vbaProject.bin")))
    }

    @Test
    fun zip64MarkersAreRefused() {
        assertNull(inspect(withEndField(docx, 10, 2, 0xFFFF)), "entries")
        assertNull(inspect(withEndField(docx, 8, 2, 0xFFFF)), "entries on this disk")
        assertNull(inspect(withEndField(docx, 12, 4, 0xFFFF_FFFFL)), "directory size")
        assertNull(inspect(withEndField(docx, 16, 4, 0xFFFF_FFFFL)), "directory offset")
    }

    @Test
    fun aCentralDirectoryOutsideTheFileIsRefused() {
        val end = ZipFixtures.endRecord(docx).toLong()
        assertNull(inspect(withEndField(docx, 16, 4, docx.size.toLong())), "starting past the end")
        assertNull(inspect(withEndField(docx, 16, 4, end - 10)), "running into the end record")
    }

    @Test
    fun aCentralDirectoryOver256KiBIsRefused() {
        fun withComments(n: Int) = zip(
            entry("[Content_Types].xml"), entry("_rels/.rels"), entry("word/document.xml"),
            *Array(n) { entry("docProps/custom$it.xml", comment = "Example Pool Pump manual ".repeat(2_400)) },
        )
        val four = withComments(4) // a directory of about 240 KiB
        assertEquals(DOCX, inspect(four))
        assertNull(inspect(withComments(5))) // about 300 KiB
    }

    @Test
    fun moreThan4096EntriesAreRefused() {
        fun withParts(n: Int) = zip(
            entry("[Content_Types].xml"), entry("_rels/.rels"), entry("word/document.xml"),
            *Array(n) { entry("c/$it", "") },
        )
        assertEquals(DOCX, inspect(withParts(4_093)))
        assertNull(inspect(withParts(4_094)))
    }

    @Test
    fun anEndRecordWhoseCommentDoesNotEndTheFileIsRefused() {
        assertNull(inspect(docx + byteArrayOf(0)))
        assertNull(inspect(withEndField(docx, 20, 2, 1)))
    }

    @Test
    fun aBrokenCentralRecordIsRefused() {
        val end = ZipFixtures.endRecord(docx)
        val directory = (docx[end + 16].toInt() and 0xFF) or ((docx[end + 17].toInt() and 0xFF) shl 8)
        assertNull(inspect(ZipFixtures.withByte(docx, directory + 2, 0x03)))
    }

    @Test
    fun aReadPastTheBudgetIsRefusedNeverAnswered() {
        assertNull(inspect(docx, BoundedInspection(BytesStagedReader.of(docx), maxBytes = docx.size)))
        assertNull(inspect(docx, BoundedInspection(BytesStagedReader.of(docx), maxReads = 1)))
    }

    @Test
    fun onlyAZipHeadIsInspected() {
        val pdf = "%PDF-1.4\n%%EOF\n".toByteArray(Charsets.ISO_8859_1)
        val reader = BytesStagedReader.of(pdf)
        assertNull(inspect(pdf, BoundedInspection(reader)))
        assertEquals(0, reader.reads)
        assertFalse(ContainerInspect.inspects(pdf))
        assertTrue(ContainerInspect.inspects(docx))
    }

    // ---- fix round 1: the parser differentials (review MJ1, MJ2, m3) and the guard pins (m5) ----

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int) = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)

    private fun le(value: Long, width: Int) = ByteArray(width) { (value shr (8 * it)).toByte() }

    /** An arbitrary archive whose three names are exactly as long as the three OOXML package names. */
    private val arbitrary = zip(
        entry("notes/readme-01.txt", "Example Pool Pump arbitrary"), entry("payload.bin", "arbitrary bytes"),
        entry("other/zzzzzzzz.tx", "arbitrary"),
    )

    /** [directory] with its three names overwritten, in place, by the OOXML package names. */
    private fun renamed(directory: ByteArray): ByteArray {
        val fake = directory.copyOf()
        var p = 0
        for (name in listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml")) {
            val bytes = name.toByteArray(Charsets.UTF_8)
            assertEquals(bytes.size, u16(fake, p + 28), name)
            bytes.copyInto(fake, p + 46)
            p += 46 + bytes.size + u16(fake, p + 30) + u16(fake, p + 32)
        }
        return fake
    }

    @Test
    fun recordsPastTheEntryCountAreNeverHidden() {
        // review MJ1: both entry counts patched from 4 to 3; every ZIP reader still lists the fourth record
        val macros = ooxml("word/document.xml", "word/vbaProject.bin")
        assertNull(inspect(withEndField(withEndField(macros, 8, 2, 3), 10, 2, 3)))
        val twoMain = ooxml("word/document.xml", "xl/workbook.xml")
        assertNull(inspect(withEndField(withEndField(twoMain, 8, 2, 3), 10, 2, 3)))
        // and the two counts must agree
        assertNull(inspect(withEndField(docx, 8, 2, 2)))
    }

    @Test
    fun aDirectoryThatDoesNotEndAtTheEndRecordIsRefused() {
        // review MJ2: [a ZIP-headed prefix][the arbitrary archive's entries][a renamed copy of its directory]
        // [its real directory][the end record, its offset at the copy]; readers take end record - size: the real one
        val end = ZipFixtures.endRecord(arbitrary)
        val length = u32(arbitrary, end + 12).toInt()
        val offset = u32(arbitrary, end + 16).toInt()
        val real = arbitrary.copyOfRange(offset, offset + length)
        val prefix = ByteArray(length).also { byteArrayOf(0x50, 0x4B, 0x03, 0x04).copyInto(it) }
        val endRecord = arbitrary.copyOfRange(end, arbitrary.size).also { le(prefix.size + offset.toLong(), 4).copyInto(it, 16) }
        assertNull(inspect(prefix + arbitrary.copyOfRange(0, offset) + renamed(real) + real + endRecord))
        // the plain case: bytes between a real package's directory and its end record
        val at = ZipFixtures.endRecord(docx)
        assertNull(inspect(docx.copyOfRange(0, at) + ByteArray(8) + docx.copyOfRange(at, docx.size)))
    }

    @Test
    fun anEndSignatureAfterTheChosenEndRecordIsRefused() {
        // review m3: [entries][renamed directory][an end record whose comment covers the rest]
        // [the real directory][an end record with comment length 5 and nothing after it]
        val end = ZipFixtures.endRecord(arbitrary)
        val length = u32(arbitrary, end + 12).toInt()
        val offset = u32(arbitrary, end + 16).toInt()
        val real = arbitrary.copyOfRange(offset, offset + length)
        val record = arbitrary.copyOfRange(end, arbitrary.size)
        val fakeEnd = record.copyOf().also { le(offset.toLong(), 4).copyInto(it, 16); le(length + 22L, 2).copyInto(it, 20) }
        val realEnd = record.copyOf().also { le(offset + length + 22L, 4).copyInto(it, 16); le(5, 2).copyInto(it, 20) }
        assertNull(inspect(arbitrary.copyOfRange(0, offset) + renamed(real) + fakeEnd + real + realEnd))
        // an end signature inside a real package's comment (a recorded safe-side limit)
        val commented = zip(
            entry("[Content_Types].xml"), entry("_rels/.rels"), entry("word/document.xml"),
            comment = "PK\u0005\u0006 Example Pool Pump manual",
        )
        assertNull(inspect(commented))
    }

    @Test
    fun recordsTheDirectoryCannotHoldAreRefused() {
        // review m5: a name length of 0xFFFF, and an entry count of 5 over three records
        val three = zip(entry("[Content_Types].xml"), entry("_rels/.rels"), entry("word/document.xml"))
        assertEquals(DOCX, inspect(three))
        val directory = u32(three, ZipFixtures.endRecord(three) + 16).toInt()
        val longName = ZipFixtures.withByte(ZipFixtures.withByte(three, directory + 28, 0xFF), directory + 29, 0xFF)
        assertNull(inspect(longName))
        assertNull(inspect(withEndField(withEndField(three, 8, 2, 5), 10, 2, 5)))
        // a record signature with fewer than 46 bytes after it, inside a directory that ends at its end record
        val at = ZipFixtures.endRecord(three)
        val cut = three.copyOfRange(0, at) + byteArrayOf(0x50, 0x4B, 0x01, 0x02) + ByteArray(10) + three.copyOfRange(at, three.size)
        val size = u32(three, at + 12)
        assertNull(inspect(withEndField(withEndField(withEndField(cut, 12, 4, size + 14), 8, 2, 4), 10, 2, 4)))
    }

    // ---- C28 (7) (owner): the OOXML types outside the list, as far as the fetch can identify them ----

    @Test
    fun aVbaDataPartIsRefused() {
        assertNull(inspect(ooxml("word/document.xml", "word/vbaData.xml")))
        assertNull(inspect(ooxml("word/document.xml", "word/VBADATA.XML")))
    }

    @Test
    fun theExclusionsAreClosedSetsThatLeaveTheListedAndLegacyTypesAlone() {
        assertEquals(15, OoxmlExclusions.EXTENSIONS.size)
        assertEquals(16, OoxmlExclusions.MIME_TYPES.size)
        for (extension in OoxmlExclusions.EXTENSIONS) assertTrue(OoxmlExclusions.refuses(null, extension), extension)
        for (mime in OoxmlExclusions.MIME_TYPES) assertTrue(OoxmlExclusions.refuses(mime, null), mime)
        assertTrue(OoxmlExclusions.refuses("Application/vnd.ms-word.document.macroEnabled.12; charset=binary", null))
        assertTrue(OoxmlExclusions.refuses(null, "DOTX"))
        // the exact legacy types are XLS and PPT (B2e), never an exclusion
        assertFalse(OoxmlExclusions.refuses("application/vnd.ms-excel", "xls"))
        assertFalse(OoxmlExclusions.refuses("application/vnd.ms-powerpoint", "ppt"))
        assertFalse(OoxmlExclusions.refuses("application/msword", "doc"))
        assertFalse(OoxmlExclusions.refuses(DOCX, "docx"))
        assertFalse(OoxmlExclusions.refuses(XLSX, "xlsx"))
        assertFalse(OoxmlExclusions.refuses(PPTX, "pptx"))
        assertFalse(OoxmlExclusions.refuses("application/octet-stream", null))
        assertFalse(OoxmlExclusions.refuses(null, null))
    }
}
