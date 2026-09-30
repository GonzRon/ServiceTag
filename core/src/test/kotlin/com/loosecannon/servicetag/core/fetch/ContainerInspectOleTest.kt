package com.loosecannon.servicetag.core.fetch

import com.loosecannon.servicetag.core.fetch.ContainerInspect.DOC
import com.loosecannon.servicetag.core.fetch.ContainerInspect.PPT
import com.loosecannon.servicetag.core.fetch.ContainerInspect.XLS
import com.loosecannon.servicetag.core.testing.BytesStagedReader
import com.loosecannon.servicetag.core.testing.CfbFixtures.BYTE_ORDER
import com.loosecannon.servicetag.core.testing.CfbFixtures.DIFAT_COUNT
import com.loosecannon.servicetag.core.testing.CfbFixtures.DIFAT_SLOTS
import com.loosecannon.servicetag.core.testing.CfbFixtures.END
import com.loosecannon.servicetag.core.testing.CfbFixtures.FIRST_DIFAT
import com.loosecannon.servicetag.core.testing.CfbFixtures.FIRST_DIRECTORY
import com.loosecannon.servicetag.core.testing.CfbFixtures.FREE
import com.loosecannon.servicetag.core.testing.CfbFixtures.MAJOR
import com.loosecannon.servicetag.core.testing.CfbFixtures.MINI_SHIFT
import com.loosecannon.servicetag.core.testing.CfbFixtures.SECTOR_SHIFT
import com.loosecannon.servicetag.core.testing.CfbFixtures.cfb
import com.loosecannon.servicetag.core.testing.CfbFixtures.directoryEntry
import com.loosecannon.servicetag.core.testing.CfbFixtures.fatEntry
import com.loosecannon.servicetag.core.testing.CfbFixtures.patched
import com.loosecannon.servicetag.core.testing.CfbFixtures.storage
import com.loosecannon.servicetag.core.testing.CfbFixtures.stream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Row 38 (C29): an OLE2 compound file is DOC, XLS or PPT only when its directory, walked through the FAT
 * inside one bounded inspection, holds exactly one family's content stream by its exact name. The
 * compound-file signature and a valid header are never enough, and no stream is ever read.
 */
class ContainerInspectOleTest {

    private fun head(file: ByteArray) = file.copyOfRange(0, minOf(file.size, DocumentSniff.WINDOW))

    private fun tail(file: ByteArray) = file.copyOfRange(file.size - minOf(file.size, DocumentSniff.WINDOW), file.size)

    private fun inspect(file: ByteArray, inspection: BoundedInspection = BoundedInspection(BytesStagedReader.of(file))) =
        ContainerInspect.classify(file.size.toLong(), head(file), tail(file), inspection)

    private val summary = stream("\u0005SummaryInformation")

    private val doc = cfb(stream("WordDocument"), stream("1Table"), summary)

    /** The root and four entries: two 512-byte directory sectors, the content stream in the second. */
    private val twoSectors = cfb(summary, stream("\u0005DocumentSummaryInformation"), stream("1Table"), stream("WordDocument"))

    // ---- pass ----

    @Test
    fun aMinimalDocXlsAndPptAreProvenByTheirContentStream() {
        assertEquals(DOC, inspect(doc))
        assertEquals(XLS, inspect(cfb(stream("Workbook"), summary)))
        assertEquals(XLS, inspect(cfb(stream("Book"), summary)), "the BIFF5 name")
        assertEquals(PPT, inspect(cfb(stream("PowerPoint Document"), stream("Current User"), summary)))
    }

    @Test
    fun aFileOf4096ByteSectorsIsProvenTheSameWay() {
        for ((name, mime) in listOf("WordDocument" to DOC, "Workbook" to XLS, "PowerPoint Document" to PPT)) {
            assertEquals(mime, inspect(cfb(stream(name), summary, shift = 12)), name)
        }
    }

    @Test
    fun aDirectoryChainAcrossTwoSectorsIsFollowedThroughTheFat() {
        val reader = BytesStagedReader.of(twoSectors)
        assertEquals(DOC, inspect(twoSectors, BoundedInspection(reader)))
        assertEquals(3, reader.reads, "two directory sectors, and the FAT sector once")
    }

    @Test
    fun theInspectionReadsTheDirectoryAndTheFatAndNoStream() {
        val reader = BytesStagedReader.of(doc)
        val inspection = BoundedInspection(reader)
        assertEquals(DOC, inspect(doc, inspection))
        assertEquals(2, reader.reads)
        assertEquals(1_024, inspection.bytes, "one directory sector and one FAT sector; the header is the head window's")
    }

    @Test
    fun storagesAndOtherStreamsBesideTheContentStreamAreIgnored() {
        assertEquals(DOC, inspect(cfb(stream("\u0001CompObj"), storage("ObjectPool"), stream("WordDocument"), stream("Data"))))
        assertEquals(XLS, inspect(cfb(stream("Workbook"), stream("Book"))), "both Excel names are one family's proof")
    }

    // ---- fail ----

    @Test
    fun noWordExcelOrPowerPointStreamIsRefused() {
        assertNull(inspect(cfb(summary)), "a valid header and directory alone")
        assertNull(inspect(cfb()), "a root entry alone")
        assertNull(inspect(cfb(stream("__properties_version1.0"), stream("__substg1.0_0037001F"))), "a message's streams")
    }

    @Test
    fun twoFamiliesContentStreamsAreRefused() {
        assertNull(inspect(cfb(stream("WordDocument"), stream("Workbook"))))
        assertNull(inspect(cfb(stream("PowerPoint Document"), stream("Book"))))
    }

    @Test
    fun aContentStreamIsMatchedByItsExactNameNeverAPrefix() {
        assertNull(inspect(cfb(stream("WordDocumentX"), summary)))
        assertNull(inspect(cfb(stream("WorkbookX"))))
        assertNull(inspect(cfb(stream("PowerPoint DocumentX"))))
        assertNull(inspect(cfb(stream("Word"))), "nor a prefix of one")
        val lengthCut = patched(cfb(stream("WordDocumentX")), directoryEntry(1) + 64, 26, width = 2)
        assertNull(inspect(lengthCut), "a length ending at \"WordDocument\" whose terminator is X, not NUL")
    }

    @Test
    fun aContentNameOnAStorageOrPastTheNameLimitIsRefused() {
        assertNull(inspect(cfb(storage("WordDocument"), summary)))
        assertNull(inspect(patched(doc, directoryEntry(1) + 64, 66, width = 2)), "a 66-byte name length")
    }

    @Test
    fun theSignatureAndJunkAreRefused() {
        val signature = doc.copyOf(8)
        assertNull(inspect(signature))
        assertNull(inspect(signature + ByteArray(1_000) { 'x'.code.toByte() }))
        assertNull(inspect(doc.copyOf(512)), "a valid header and nothing after it")
    }

    @Test
    fun aHeaderOutsideTheSpecIsRefused() {
        val bad = listOf(
            patched(doc, SECTOR_SHIFT, 10, width = 2) to "sector shift 10",
            patched(doc, SECTOR_SHIFT, 12, width = 2) to "version 3 with 4,096-byte sectors",
            patched(doc, MAJOR, 4, width = 2) to "version 4 with 512-byte sectors",
            patched(doc, MINI_SHIFT, 7, width = 2) to "mini-sector shift 7",
            patched(doc, BYTE_ORDER, 0xFEFF, width = 2) to "the byte order reversed",
        )
        for ((file, why) in bad) assertNull(inspect(file), why)
    }

    @Test
    fun aDirectorySectorPastTheEndIsRefused() {
        assertNull(inspect(patched(doc, FIRST_DIRECTORY, 40)), "the first directory sector")
        assertNull(inspect(patched(twoSectors, fatEntry(1), 40)), "the chain's second sector")
        assertNull(inspect(twoSectors.copyOf(3 * 512 + 100)), "a directory sector cut short by the file's end")
    }

    @Test
    fun aChainEndingBeforeAnyDirectorySectorOrOnASentinelIsRefused() {
        assertNull(inspect(patched(doc, FIRST_DIRECTORY, END)))
        assertNull(inspect(patched(doc, FIRST_DIRECTORY, FREE)))
        assertNull(inspect(patched(twoSectors, fatEntry(1), FREE)), "a free sector in the chain")
        assertNull(inspect(patched(doc, DIFAT_SLOTS, FREE)), "no FAT sector")
    }

    @Test
    fun aFatCycleIsRefusedWithoutLooping() {
        assertNull(inspect(patched(doc, fatEntry(1), 1)), "a directory sector chained to itself")
        val looped = patched(twoSectors, fatEntry(2), 1)
        val reader = BytesStagedReader.of(looped)
        assertNull(inspect(looped, BoundedInspection(reader)), "the second directory sector chained back to the first")
        assertEquals(3, reader.reads, "each sector once, then refused")
        assertNull(inspect(patched(doc, DIFAT_SLOTS, 1)), "the directory sector read again as the FAT")
    }

    @Test
    fun sixtyFourDirectorySectorsAreFollowedAndASixtyFifthIsNeverRead() {
        // the root, filler streams and the content stream last: four entries to a 512-byte sector
        fun withEntries(n: Int) = cfb(*Array(n - 2) { stream("Data$it") }, stream("WordDocument"))
        val sixtyFour = withEntries(64 * 4)
        val all = BytesStagedReader.of(sixtyFour)
        assertEquals(DOC, inspect(sixtyFour, BoundedInspection(all)), "the content stream in the 64th sector")
        assertEquals(65, all.reads, "64 directory sectors and the FAT sector once")
        val sixtyFive = withEntries(64 * 4 + 1)
        val capped = BytesStagedReader.of(sixtyFive)
        assertNull(inspect(sixtyFive, BoundedInspection(capped)))
        assertEquals(65, capped.reads, "the 65th directory sector is never read")
    }

    @Test
    fun aReadPastTheBudgetIsRefusedNeverAnswered() {
        assertNull(inspect(doc, BoundedInspection(BytesStagedReader.of(doc), maxReads = 1)))
        assertNull(inspect(doc, BoundedInspection(BytesStagedReader.of(doc), maxBytes = 1_000)))
    }

    @Test
    fun aZipOrCompoundFileHeadIsInspected() {
        assertTrue(ContainerInspect.inspects(doc))
        assertFalse(ContainerInspect.inspects(patched(doc, 7, 0, width = 1)))
    }

    // ---- pre-review fix: only the root storage's own children prove the file (an embedded object never does) ----

    @Test
    fun aDocWithAnEmbeddedWorkbookIsADoc() {
        assertEquals(DOC, inspect(cfb(stream("WordDocument"), stream("1Table"), storage("ObjectPool", stream("Workbook")))))
        val nested = cfb(stream("WordDocument"), storage("ObjectPool", storage("_1234567890", stream("\u0001CompObj"), stream("Workbook"))))
        assertEquals(DOC, inspect(nested), "ObjectPool/_1234567890/Workbook")
        assertEquals(XLS, inspect(cfb(stream("Workbook"), storage("MBD0001", stream("WordDocument")))), "a Word object in a workbook")
    }

    @Test
    fun aMessageWithAnEmbeddedWordObjectIsRefused() {
        val message = arrayOf(stream("__substg1.0_0037001F"), stream("__substg1.0_1000001F"), stream("__properties_version1.0"))
        assertNull(inspect(cfb(*message, storage("__attach_version1.0_#00000000", stream("WordDocument")))))
        val nested = storage("__attach_version1.0_#00000000", storage("__substg1.0_3701000D", stream("WordDocument"), stream("1Table")))
        assertNull(inspect(cfb(*message, nested)), "the object two storages down")
    }

    @Test
    fun aRootTreeThatCyclesOrLeavesTheDirectoryIsRefused() {
        // doc's root children: WordDocument (1) -> 1Table (2) -> SummaryInformation (3), linked by right siblings
        assertNull(inspect(patched(doc, directoryEntry(3) + 72, 1)), "a right-sibling cycle")
        assertNull(inspect(patched(doc, directoryEntry(2) + 68, 2)), "a node that is its own left sibling")
        assertNull(inspect(patched(doc, directoryEntry(1) + 72, 40)), "a sibling past the entries read")
        assertNull(inspect(patched(doc, directoryEntry(0) + 76, 40)), "a root child past the entries read")
        assertNull(inspect(patched(twoSectors, directoryEntry(4) + 72, 6)), "a sibling that is an unused entry")
        assertNull(inspect(patched(doc, directoryEntry(0) + 76, FREE)), "a root with no children")
    }

    @Test
    fun theFirstEntryMustBeTheRootStorage() {
        assertNull(inspect(patched(doc, directoryEntry(0) + 66, 1, width = 1)))
    }

    // ---- the DIFAT (MS-CFB §2.5): FAT sectors past the header's 109 slots ----

    /** A [size]-byte file of zeros but for [parts] (position to bytes): room for far sectors without their bytes. */
    private fun sparse(size: Long, parts: Map<Long, ByteArray>) = StagedReader { position, length ->
        val out = ByteArray(minOf(length.toLong(), size - position).coerceAtLeast(0).toInt())
        for ((at, bytes) in parts) for (i in bytes.indices) {
            val p = at + i - position
            if (p >= 0 && p < out.size) out[p.toInt()] = bytes[i]
        }
        out
    }

    private fun sector(fill: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(128) { putInt(4 * it, FREE.toInt()) }; fill() }.array()

    /**
     * [doc]'s directory moved to sector [far], whose FAT sector is listed in the DIFAT chain: DIFAT sector j
     * lists FAT sectors 109 + 127·j onward and ends with the next one's id. Returns the class's answer and budget.
     */
    private fun inspectFar(far: Long): Pair<String?, BoundedInspection> {
        val k = far / 128
        val j = ((k - 109) / 127).toInt()
        val fat = far + j + 2
        val header = patched(patched(patched(doc, FIRST_DIRECTORY, far), FIRST_DIFAT, far + 1), DIFAT_COUNT, j + 1L)
        val parts = mutableMapOf(0L to header, (far + 1) * 512 to doc.copyOfRange(1_024, 1_536))
        for (m in 0..j) parts[(far + 2 + m) * 512] = sector {
            putInt(508, if (m < j) (far + 2 + m).toInt() else END.toInt())
            if (m == j) putInt(4 * ((k - 109) % 127).toInt(), fat.toInt())
        }
        parts[(fat + 1) * 512] = sector { putInt(4 * (far % 128).toInt(), END.toInt()) }
        val size = (fat + 2) * 512
        val reader = sparse(size, parts)
        val inspection = BoundedInspection(reader)
        return ContainerInspect.classify(size, reader.readAt(0, 1_024), reader.readAt(size - 1_024, 1_024), inspection) to inspection
    }

    @Test
    fun aFatSectorListedInTheDifatIsFollowed() {
        val (mime, inspection) = inspectFar(109L * 128)
        assertEquals(DOC, mime)
        assertEquals(3, inspection.reads, "the directory, one DIFAT sector, one FAT sector")
    }

    @Test
    fun eightDifatSectorsAreFollowedAndANinthIsNeverRead() {
        val (eighth, followed) = inspectFar((109L + 7 * 127) * 128 + 5)
        assertEquals(DOC, eighth)
        assertEquals(10, followed.reads, "the directory, eight DIFAT sectors, one FAT sector")
        val (ninth, refused) = inspectFar((109L + 8 * 127) * 128)
        assertNull(ninth)
        assertEquals(1, refused.reads, "the directory only: the ninth DIFAT sector is past the cap")
    }
}
