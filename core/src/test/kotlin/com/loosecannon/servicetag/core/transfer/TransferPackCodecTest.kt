package com.loosecannon.servicetag.core.transfer

import com.loosecannon.servicetag.core.backup.ArtifactsPlanEntry
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.transfer.TransferFixtures.ANODE
import com.loosecannon.servicetag.core.transfer.TransferFixtures.HEATER
import com.loosecannon.servicetag.core.transfer.TransferPackTesting.Raw
import com.loosecannon.servicetag.core.transfer.TransferPackTesting.artifactsOf
import com.loosecannon.servicetag.core.transfer.TransferPackTesting.entriesOf
import com.loosecannon.servicetag.core.transfer.TransferPackTesting.heaterDraft
import com.loosecannon.servicetag.core.transfer.TransferPackTesting.read
import com.loosecannon.servicetag.core.transfer.TransferPackTesting.redraft
import com.loosecannon.servicetag.core.transfer.TransferPackTesting.seal
import com.loosecannon.servicetag.core.transfer.TransferPackTesting.zipOf
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * #77 (C4; AC 1, 14; R77-1) — the container: three entries in order around the unchanged inner archives,
 * and a reader that trusts nothing it has not checked. Every damaged case is refused as a whole.
 */
class TransferPackCodecTest {

    private val draft = heaterDraft()
    private val sealed = seal(draft)
    private val pack = sealed.first
    private val written = sealed.second

    private fun damaged(bytes: ByteArray, because: String) {
        val read = assertIs<TransferPackRead.Damaged>(read(bytes))
        assertTrue(because in read.reason, "expected \"$because\" in \"${read.reason}\"")
    }

    /** The pack as written, re-zipped with [edit] applied to its manifest. */
    private fun withManifest(edit: (TransferPackManifest) -> TransferPackManifest): ByteArray {
        val entries = entriesOf(pack)
        val manifest = TransferPackCodec.json.decodeFromString(TransferPackManifest.serializer(), String(entries[0].bytes))
        return zipOf(listOf(Raw(TransferPack.MANIFEST_ENTRY, TransferPackCodec.encodeManifest(edit(manifest)))) + entries.drop(1))
    }

    // --- the shape ----------------------------------------------------------------------------------

    @Test
    fun aPackRoundTrips() {
        val read = assertIs<TransferPackRead.Pack>(read(pack))

        assertEquals(written.manifest, read.manifest)
        assertContentEquals(draft.data, read.data)
        assertEquals(BackupCodec.decode(draft.data).data, read.backup.data)
        assertEquals(listOf("at1", "at2"), read.artifacts.entries.map { it.attachmentId })
        assertEquals(TransferPack.sha256Hex(pack) to pack.size.toLong(), written.packSha256 to written.packBytes)
        assertEquals("Example handover note", read.manifest.note)
        assertEquals(listOf(HEATER, ANODE).sorted(), read.manifest.lineage.keys.toList())
    }

    @Test
    fun theManifestIsFirstAndTheInnerArchivesAreStored() {
        val entries = entriesOf(pack)

        assertEquals(TransferPack.ENTRIES, entries.map { it.name })
        assertEquals(listOf(true, true), entries.drop(1).map { it.stored })
        assertContentEquals(draft.data, entries[1].bytes)
        val manifest = TransferPackCodec.json.decodeFromString(TransferPackManifest.serializer(), String(entries[0].bytes))
        assertEquals(written.manifest, manifest)
        assertEquals(1, manifest.packFormatVersion)
        assertEquals(TransferPack.sha256Hex(entries[1].bytes) to TransferPack.sha256Hex(entries[2].bytes), manifest.dataSha256 to manifest.artifactsSha256)
    }

    // --- structure ----------------------------------------------------------------------------------

    @Test
    fun anExtraEntryIsDamaged() = damaged(zipOf(entriesOf(pack) + Raw("extra.txt", "Example".toByteArray())), "entries")

    @Test
    fun aMissingEntryIsDamaged() = damaged(zipOf(entriesOf(pack).dropLast(1)), "entries")

    @Test
    fun aReorderedPackIsDamaged() {
        val (manifest, data, artifacts) = entriesOf(pack)
        damaged(zipOf(listOf(manifest, artifacts, data)), "entries")
    }

    @Test
    fun aDirectoryEntryIsDamaged() = damaged(zipOf(entriesOf(pack) + Raw("docs/", ByteArray(0))), "directory")

    /** Not a ZIP, an empty file, and an ordinary backup (its first entry is `manifest.json`) are not packs. */
    @Test
    fun anythingElseIsNotAPack() {
        assertEquals(TransferPackRead.NotAPack, read(Random(7).nextBytes(4_096)))
        assertEquals(TransferPackRead.NotAPack, read(ByteArray(0)))
        assertEquals(TransferPackRead.NotAPack, read(draft.data))
    }

    @Test
    fun aNewerPackIsRefusedAsNewer() {
        val entries = entriesOf(pack)
        val newer = String(entries[0].bytes).replaceFirst("\"packFormatVersion\": 1", "\"packFormatVersion\": 2, \"somethingNew\": true")

        assertEquals(
            TransferPackRead.NewerPack("pack", 2, 1),
            read(zipOf(listOf(Raw(TransferPack.MANIFEST_ENTRY, newer.toByteArray())) + entries.drop(1))),
        )
    }

    // --- hashes, set ids, lineage, assets -----------------------------------------------------------

    @Test
    fun eitherShaDisagreeingIsDamaged() {
        damaged(withManifest { it.copy(dataSha256 = "0".repeat(64)) }, "data.zip does not match its sha256")
        damaged(withManifest { it.copy(artifactsSha256 = "0".repeat(64)) }, "artifacts.zip does not match its sha256")
        damaged(withManifest { it.copy(contentSha256 = "0".repeat(64)) }, "content hash")
    }

    @Test
    fun setIdsMustBeThePackId() {
        val otherData = BackupCodec.encode(BackupCodec.decode(draft.data).data, "1.4.1", 13, draft.createdAt, "set-other")
        damaged(seal(redraft(draft, data = otherData)).first, "data.zip belongs to set set-other")

        val otherPlan = draft.plan.copy(backupSetId = "set-other")
        damaged(seal(draft, artifactsOf(otherPlan)).first, "artifacts.zip belongs to set set-other")
    }

    @Test
    fun lineageKeysMustBeThePacksAssets() {
        damaged(seal(redraft(draft, lineage = mapOf(HEATER to emptyList()))).first, "lineage")
        damaged(seal(redraft(draft, lineage = draft.lineage + ("x1" to emptyList()))).first, "lineage")
    }

    @Test
    fun assetIdsMustBeTheDecodedAssets() {
        damaged(seal(redraft(draft, assetIds = listOf(HEATER), lineage = mapOf(HEATER to emptyList()))).first, "assetIds")
    }

    /** mn-3, either way: a document the data names and the artifacts lack, or bytes for a row the data does not have. */
    @Test
    fun artifactsMustMatchTheManagedRowsBothWays() {
        val lacking = draft.plan.copy(entries = draft.plan.entries.filter { it.attachmentId.value != "at2" })
        damaged(seal(draft, artifactsOf(lacking)).first, "disagrees with data.zip's documents")

        val strayBytes = "Example stray bytes".toByteArray()
        val stray = ArtifactsPlanEntry(
            attachmentId = AttachmentId("at9"), entryName = "artifacts/at9.pdf", locator = "assets/h1/at9.pdf",
            sha256 = TransferPack.sha256Hex(strayBytes), sizeBytes = strayBytes.size.toLong(), mimeType = "application/pdf",
        )
        val extra = draft.plan.copy(entries = draft.plan.entries + stray)
        damaged(seal(draft, artifactsOf(extra, mapOf("assets/h1/at9.pdf" to strayBytes))).first, "disagrees with data.zip's documents")
    }

    // --- caps, the note, truncation, a broken inner archive -----------------------------------------

    @Test
    fun anOverCapDataArchiveIsDamaged() {
        val entries = entriesOf(pack)
        val huge = Random(16).nextBytes((TransferPack.MAX_PACK_DATA_BYTES + 1).toInt())

        damaged(zipOf(listOf(entries[0], Raw(TransferPack.DATA_ENTRY, huge), entries[2])), "data.zip is over")
    }

    /** The inner `data.json` inflates past 64 MiB: refused before the backup codec would hold it whole. */
    @Test
    fun anOverCapInflatedDataArchiveIsDamaged() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry(BackupCodec.MANIFEST_ENTRY))
            zos.write("{}".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry(BackupCodec.DATA_ENTRY))
            val spaces = ByteArray(1024 * 1024) { ' '.code.toByte() }
            repeat((TransferPack.MAX_PACK_JSON_BYTES / spaces.size).toInt()) { zos.write(spaces) }
            zos.write(' '.code)
            zos.closeEntry()
        }
        val bomb = out.toByteArray()
        check(bomb.size < TransferPack.MAX_PACK_DATA_BYTES)
        val entries = entriesOf(withManifest { it.copy(dataSha256 = TransferPack.sha256Hex(bomb)) })

        damaged(zipOf(listOf(entries[0], Raw(TransferPack.DATA_ENTRY, bomb), entries[2])), "inflates past")
    }

    @Test
    fun aBadNoteIsDamaged() {
        damaged(seal(redraft(draft, note = "x".repeat(TransferPack.MAX_NOTE_CHARS + 1))).first, "note")
        damaged(seal(redraft(draft, note = "Example\nsecond line")).first, "note")
        assertIs<TransferPackRead.Pack>(read(seal(redraft(draft, note = "x".repeat(TransferPack.MAX_NOTE_CHARS))).first))
    }

    @Test
    fun aTruncatedPackIsDamaged() {
        listOf(pack.size / 2, pack.size * 9 / 10, pack.size / 5).forEach { at ->
            assertIs<TransferPackRead.Damaged>(read(pack.copyOf(at)), "cut at $at of ${pack.size}")
        }
    }

    @Test
    fun aBrokenInnerArchiveIsDamaged() {
        damaged(seal(redraft(draft, data = "not an archive".toByteArray())).first, "data.zip:")
    }
}
