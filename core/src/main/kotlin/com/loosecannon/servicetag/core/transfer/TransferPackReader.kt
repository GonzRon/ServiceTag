package com.loosecannon.servicetag.core.transfer

import com.loosecannon.servicetag.core.backup.ArtifactEntry
import com.loosecannon.servicetag.core.backup.ArtifactsCodec
import com.loosecannon.servicetag.core.backup.ArtifactsManifest
import com.loosecannon.servicetag.core.backup.ArtifactsNewerFormat
import com.loosecannon.servicetag.core.backup.ArtifactsReadReport
import com.loosecannon.servicetag.core.backup.Backup
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupException
import com.loosecannon.servicetag.core.backup.BackupNewerFormat
import com.loosecannon.servicetag.core.model.AttachmentMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** What reading a file as a Transfer Pack found (C4). */
sealed interface TransferPackRead {
    /** P77-47: not a ZIP, or a ZIP whose first entry is not `transfer-manifest.json`. */
    data object NotAPack : TransferPackRead

    /** P77-48: [what] ("pack", "data" or "artifacts") is format [found]; this build reads up to [supported]. */
    data class NewerPack(val what: String, val found: Int, val supported: Int) : TransferPackRead

    /** P77-49: a pack, but not one this build can trust; [reason] is for the log, never the owner. */
    data class Damaged(val reason: String) : TransferPackRead

    /**
     * A whole, consistent pack: [backup] is [data] decoded by the unchanged codec (graph and content
     * checks included), and [artifacts] is the inner artifacts manifest, every entry's bytes verified.
     */
    class Pack(
        val manifest: TransferPackManifest,
        val backup: Backup,
        val data: ByteArray,
        val artifacts: ArtifactsManifest,
    ) : TransferPackRead
}

/**
 * #77 (C4) — reads a Transfer Pack in one streaming pass: the manifest (bounded), `data.zip` (bounded as
 * stored, and bounded again inflated **before** the backup codec reads it — that codec has no cap of its
 * own), and `artifacts.zip`, whose every entry is hashed on the way past and whose whole is hashed too.
 * Nothing is written anywhere. Anything that is not exactly the three entries in order, or any hash, set
 * id, format, count or id list that disagrees with another, is [TransferPackRead.Damaged].
 *
 * One known limit, harmless by construction: a file cut off exactly after its last entry, before the ZIP
 * central directory, reads as whole — every byte of every entry has been hashed and matched by then.
 */
object TransferPackReader {
    private const val BUFFER = 64 * 1024

    suspend fun read(source: InputStream): TransferPackRead = try {
        readOrRefuse(source)
    } catch (refusal: Refusal) {
        refusal.answer
    }

    private class Refusal(val answer: TransferPackRead) : Exception(null, null, false, false)

    private fun damaged(reason: String): Nothing = throw Refusal(TransferPackRead.Damaged(reason))

    /**
     * The JDK's ZIP reader fails in two families: `IOException` (truncation, a bad header), and
     * `IllegalArgumentException` for an entry name that is not UTF-8 (MJ-1) — an older zipper's legacy code
     * page. Both are damage here; neither may escape [read].
     */
    private inline fun <T> zipRead(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        damaged("the pack is not a readable zip: ${e.message}")
    } catch (e: IllegalArgumentException) {
        damaged("the pack is not a readable zip: ${e.message}")
    }

    private class ArtifactsSeen(
        val manifest: ArtifactsManifest,
        val report: ArtifactsReadReport,
        val wrongBytes: List<String>,
        val sha256: String,
    )

    private suspend fun readOrRefuse(source: InputStream): TransferPackRead {
        val zin = ZipInputStream(source)
        val first = try {
            zin.nextEntry
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null // a first name that is not UTF-8: some other ZIP, never a pack (MJ-1)
        }
        if (first == null || first.isDirectory || first.name != TransferPack.MANIFEST_ENTRY) {
            return TransferPackRead.NotAPack
        }
        val manifestBytes = zipRead { bounded(zin, TransferPack.MAX_MANIFEST_BYTES) }
            ?: damaged("${TransferPack.MANIFEST_ENTRY} is over ${TransferPack.MAX_MANIFEST_BYTES} bytes")
        val manifest = parseManifest(manifestBytes)
        checkManifest(manifest)

        val seen = mutableListOf(TransferPack.MANIFEST_ENTRY)
        var data: ByteArray? = null
        var artifacts: ArtifactsSeen? = null
        while (true) {
            val entry = zipRead { zin.nextEntry } ?: break
            if (entry.isDirectory) damaged("the pack holds a directory entry ${entry.name}")
            seen += entry.name
            when {
                entry.name == TransferPack.DATA_ENTRY && data == null -> data = zipRead {
                    bounded(zin, TransferPack.MAX_PACK_DATA_BYTES)
                } ?: damaged("${TransferPack.DATA_ENTRY} is over ${TransferPack.MAX_PACK_DATA_BYTES} bytes")
                entry.name == TransferPack.ARTIFACTS_ENTRY && artifacts == null -> artifacts = readArtifacts(zin)
            }
        }
        if (seen != TransferPack.ENTRIES) damaged("the pack's entries are $seen, not ${TransferPack.ENTRIES}")

        val dataBytes = data!!
        if (TransferPack.sha256Hex(dataBytes) != manifest.dataSha256) damaged("${TransferPack.DATA_ENTRY} does not match its sha256")
        if (zipRead { TransferPack.inflatedSize(dataBytes, TransferPack.MAX_PACK_JSON_BYTES) } > TransferPack.MAX_PACK_JSON_BYTES) {
            damaged("${TransferPack.DATA_ENTRY} inflates past ${TransferPack.MAX_PACK_JSON_BYTES} bytes")
        }
        val backup = try {
            BackupCodec.decode(dataBytes)
        } catch (e: BackupNewerFormat) {
            return TransferPackRead.NewerPack("data", e.found, e.supported)
        } catch (e: BackupException) {
            damaged("${TransferPack.DATA_ENTRY}: ${e.message}")
        }
        checkData(manifest, backup)

        val seenArtifacts = artifacts!!
        checkArtifacts(manifest, backup, seenArtifacts)
        return TransferPackRead.Pack(manifest, backup, dataBytes, seenArtifacts.manifest)
    }

    private fun parseManifest(bytes: ByteArray): TransferPackManifest = try {
        val tree = TransferPackCodec.json.parseToJsonElement(String(bytes, Charsets.UTF_8))
        val version = (tree as? JsonObject)?.get("packFormatVersion")?.jsonPrimitive?.intOrNull
            ?: damaged("${TransferPack.MANIFEST_ENTRY} has no packFormatVersion")
        if (version > TransferPack.PACK_FORMAT_VERSION) {
            throw Refusal(TransferPackRead.NewerPack("pack", version, TransferPack.PACK_FORMAT_VERSION))
        }
        if (version != TransferPack.PACK_FORMAT_VERSION) damaged("packFormatVersion $version is not a pack format")
        TransferPackCodec.json.decodeFromJsonElement(TransferPackManifest.serializer(), tree)
    } catch (e: IllegalArgumentException) {
        damaged("${TransferPack.MANIFEST_ENTRY} is not readable: ${e.message}")
    }

    private fun checkManifest(manifest: TransferPackManifest) {
        if (manifest.packId.isBlank()) damaged("the pack has no packId")
        if (!TransferPack.noteAccepted(manifest.note)) damaged("the pack's note is over ${TransferPack.MAX_NOTE_CHARS} characters or not one line")
        if (manifest.lineage.keys != manifest.assetIds.toSet()) damaged("the lineage keys are not the pack's assets")
        if (manifest.rootAssetIds.isEmpty() || !manifest.assetIds.containsAll(manifest.rootAssetIds)) {
            damaged("the roots are not among the pack's assets")
        }
    }

    private fun checkData(manifest: TransferPackManifest, backup: Backup) {
        val inner = backup.manifest
        if (inner.backupSetId != manifest.packId) damaged("${TransferPack.DATA_ENTRY} belongs to set ${inner.backupSetId}")
        if (inner.formatVersion != manifest.dataFormatVersion) damaged("${TransferPack.DATA_ENTRY} is format ${inner.formatVersion}")
        if (inner.schemaVersion != manifest.schemaVersion) damaged("${TransferPack.DATA_ENTRY} is schema ${inner.schemaVersion}")
        if (inner.dataSha256 != manifest.contentSha256) damaged("the content hash does not match ${TransferPack.DATA_ENTRY}")
        if (inner.counts != manifest.counts) damaged("the counts do not match ${TransferPack.DATA_ENTRY}")
        if (inner.artifactCount != manifest.attachments || inner.artifactBytes != manifest.attachmentBytes) {
            damaged("the attachment tallies do not match ${TransferPack.DATA_ENTRY}")
        }
        if (backup.data.assets.map { it.id }.sorted() != manifest.assetIds) damaged("assetIds are not the pack's assets")
        // #77 (B2a): the records are the sender's own facts (C1's SENDER_ONLY); creation never puts one in a pack.
        if (backup.data.transferRecords.isNotEmpty()) damaged("${TransferPack.DATA_ENTRY} carries transfer records")
    }

    private fun checkArtifacts(manifest: TransferPackManifest, backup: Backup, seen: ArtifactsSeen) {
        val inner = seen.manifest
        if (seen.sha256 != manifest.artifactsSha256) damaged("${TransferPack.ARTIFACTS_ENTRY} does not match its sha256")
        if (inner.backupSetId != manifest.packId) damaged("${TransferPack.ARTIFACTS_ENTRY} belongs to set ${inner.backupSetId}")
        if (inner.artifactFormatVersion != manifest.artifactFormatVersion || inner.dataFormatVersion != backup.manifest.formatVersion) {
            damaged("${TransferPack.ARTIFACTS_ENTRY}'s formats disagree with the pack's")
        }
        if (seen.report.missingEntries.isNotEmpty() || seen.report.unexpectedEntries.isNotEmpty() || seen.wrongBytes.isNotEmpty()) {
            damaged("${TransferPack.ARTIFACTS_ENTRY} does not hold what its manifest names")
        }
        // Either way (mn-3): a MANAGED row with no bytes here, or bytes here for no MANAGED row.
        val managed = backup.data.attachments.filter { it.mode == AttachmentMode.MANAGED.name }
            .associate { it.id to (it.sha256 to it.sizeBytes) }
        val carried = inner.entries.associate { it.attachmentId to (it.sha256 to it.sizeBytes) }
        if (carried.size != inner.entries.size || carried != managed) {
            damaged("${TransferPack.ARTIFACTS_ENTRY} disagrees with ${TransferPack.DATA_ENTRY}'s documents")
        }
    }

    /**
     * Hashes the whole inner archive as it streams past while the unchanged artifacts reader walks it,
     * checking each entry's bytes against its manifest line; then drains the rest (its central directory)
     * through the same hash.
     */
    private suspend fun readArtifacts(zin: ZipInputStream): ArtifactsSeen {
        val digest = MessageDigest.getInstance("SHA-256")
        val tee = KeepOpen(DigestInputStream(zin, digest))
        var manifest: ArtifactsManifest? = null
        val wrong = mutableListOf<String>()
        val report = try {
            zipRead {
                ArtifactsCodec.read(
                    tee,
                    onManifest = { manifest = it },
                    onEntry = { entry, input -> if (!bytesMatch(entry, input)) wrong += entry.attachmentId },
                )
            }
        } catch (e: ArtifactsNewerFormat) {
            throw Refusal(TransferPackRead.NewerPack("artifacts", e.found, e.supported))
        } catch (e: BackupException) {
            damaged("${TransferPack.ARTIFACTS_ENTRY}: ${e.message}")
        }
        zipRead { tee.drain() }
        return ArtifactsSeen(manifest!!, report, wrong, TransferPack.hex(digest.digest()))
    }

    private fun bytesMatch(entry: ArtifactEntry, input: InputStream): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            size += read
        }
        return size == entry.sizeBytes && TransferPack.hex(digest.digest()) == entry.sha256
    }

    /** At most [cap] bytes, or null the moment there is one more — never the whole of an oversized entry. */
    private fun bounded(input: InputStream, cap: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            total += read
            if (total > cap) return null
            out.write(buffer, 0, read)
        }
    }

    /** The artifacts reader closes what it is handed; the pack's own stream must outlive it. */
    private class KeepOpen(private val delegate: InputStream) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun close() { /* the pack's ZipInputStream owns its own lifetime */ }

        fun drain() {
            val buffer = ByteArray(BUFFER)
            var read = delegate.read(buffer)
            while (read >= 0) read = delegate.read(buffer)
        }
    }
}
