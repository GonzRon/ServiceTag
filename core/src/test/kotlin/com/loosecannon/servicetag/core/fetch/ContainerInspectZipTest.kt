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
}
