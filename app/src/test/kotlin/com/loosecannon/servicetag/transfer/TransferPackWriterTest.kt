package com.loosecannon.servicetag.transfer

import com.loosecannon.servicetag.core.backup.BackupSetIncomplete
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.transfer.TransferPackDraft
import com.loosecannon.servicetag.core.transfer.TransferPackRead
import com.loosecannon.servicetag.core.transfer.TransferPackReader
import com.loosecannon.servicetag.core.usecase.CreateTransferPackResult
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.InMemoryAttachmentStore
import com.loosecannon.servicetag.ui.backup.NoAttachmentFolder
import com.loosecannon.servicetag.ui.transfer.`import`.TransferPackAppFixtures
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #77 (C5, C22; rows 30, 31) — the sender's pack files on the JVM: the sealed pack lands in `cache/transfer/` under the
 * name it is given, its sha256 is the file's, and the artifacts work file is gone; a missing document or no folder
 * leaves no file anywhere; start-up sweeps every older intake copy and work file and only the day-old packs.
 */
class TransferPackWriterTest {

    private val graph = FakeGraph()
    private val cache: File = Files.createTempDirectory("transfer-cache").toFile()
    private val writer = TransferPackWriter(cache, graph.attachmentStorage)

    @After fun tearDown() {
        graph.close()
        cache.deleteRecursively()
    }

    private suspend fun heaterDraft(): TransferPackDraft {
        TransferPackAppFixtures.seedHeater(graph)
        val created = graph.createTransferPack.run(listOf(AssetId(TransferPackAppFixtures.HEATER)), "")
        return (created as CreateTransferPackResult.Created).draft
    }

    private fun filesIn(dir: String): List<String> = File(cache, dir).listFiles()?.map { it.name }.orEmpty()

    @Test fun thePackLandsInTheSharedDirectoryWithItsShaAndTheWorkFileGone() = runBlocking {
        val draft = heaterDraft()

        val written = writer.write(draft, "servicetag-transfer-2026-09-28-abcdef01.zip")

        assertEquals(File(File(cache, "transfer"), "servicetag-transfer-2026-09-28-abcdef01.zip"), written.file)
        val bytes = written.file.readBytes()
        assertEquals(InMemoryAttachmentStore.sha256Hex(bytes), written.packSha256)
        assertEquals(bytes.size.toLong(), written.bytes)
        assertEquals(emptyList<String>(), filesIn("transfer-work"))
        val read = TransferPackReader.read(bytes.inputStream())
        assertTrue("the sealed file reads back as a pack: $read", read is TransferPackRead.Pack)
        assertEquals(1, (read as TransferPackRead.Pack).artifacts.entries.size)
    }

    @Test fun aMissingDocumentLeavesNoFileAnywhere() = runBlocking {
        val draft = heaterDraft()
        graph.attachmentStorage.store.files.remove(TransferPackAppFixtures.MANUAL_AT)

        try {
            writer.write(draft, "p.zip")
            fail("a missing document refuses the pack")
        } catch (e: BackupSetIncomplete) {
            assertEquals(listOf(AttachmentId("at1")), e.missing)
        }
        assertEquals(emptyList<String>(), filesIn("transfer"))
        assertEquals(emptyList<String>(), filesIn("transfer-work"))
    }

    @Test fun documentsWithoutAFolderAreRefusedBeforeAnyFile() = runBlocking {
        val draft = heaterDraft()
        graph.attachmentStorage.state = StoreState.NotConfigured

        try {
            writer.write(draft, "p.zip")
            fail("no folder refuses the pack")
        } catch (e: NoAttachmentFolder) {
            assertEquals("Choose an attachment folder in Settings first", e.message)
        }
        assertEquals(emptyList<String>(), filesIn("transfer"))
        assertEquals(emptyList<String>(), filesIn("transfer-work"))
    }

    @Test fun startSweepsCopiesWorkFilesAndDayOldPacksOnly() {
        val startedAt = 10_000_000_000L
        val now = startedAt + 1_000L
        fun file(dir: String, name: String, modified: Long) = File(File(cache, dir).also { it.mkdirs() }, name).also {
            it.writeText(name)
            it.setLastModified(modified)
        }
        file("transfer-in", "old-copy.zip", startedAt - 5_000L)
        val freshCopy = file("transfer-in", "this-process.zip", startedAt + 500L)
        file("transfer-work", "old-work.zip", startedAt - 5_000L)
        file("transfer", "day-old.zip", now - TransferPackWriter.PACK_LIFETIME_MS - 1_000L)
        val freshPack = file("transfer", "fresh.zip", now - 60_000L)

        writer.sweepAtStart(startedAt, now)

        assertEquals(listOf(freshCopy.name), filesIn("transfer-in"))
        assertEquals(emptyList<String>(), filesIn("transfer-work"))
        assertEquals(listOf(freshPack.name), filesIn("transfer"))
        assertFalse(File(File(cache, "transfer"), "day-old.zip").exists())
    }
}
