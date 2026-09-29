package com.loosecannon.servicetag.transfer

import com.loosecannon.servicetag.core.backup.ArtifactsCodec
import com.loosecannon.servicetag.core.backup.BackupSetIncomplete
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.transfer.TransferPackCodec
import com.loosecannon.servicetag.core.transfer.TransferPackDraft
import com.loosecannon.servicetag.ui.backup.NoAttachmentFolder
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A sealed pack on disk: its file in `cache/transfer/`, the whole file's sha256 and its size. */
data class WrittenPack(val file: File, val packSha256: String, val bytes: Long)

/**
 * #77 (C5, C18, C22; R77-18) — the sender's Transfer Pack files, all under the app's cache:
 * - `cache/transfer-work/` — the artifacts archive while a pack is sealed; deleted as soon as the pack is written.
 * - `cache/transfer/` — the sealed packs, the **one** directory the `${applicationId}.files` provider exposes
 *   (`res/xml/file_paths.xml`), so a share hands out exactly one pack and nothing else.
 * - `cache/transfer-in/` — B3's inbox of picked and shared packs; never exposed, swept here at start-up.
 *
 * [write] streams the draft's MANAGED files from the attachment folder into the work file, seals the pack around it
 * with the unchanged codecs and deletes the work file; any failure or cancellation leaves no file in either place.
 * [sweepAtStart] is R77-18's start-up cleaning.
 */
class TransferPackWriter(private val cacheDir: File, private val storage: AttachmentStorage) {
    private val packs: File get() = File(cacheDir, PACKS)
    private val work: File get() = File(cacheDir, WORK)
    private val inbox: File get() = File(cacheDir, INBOX)

    /**
     * Seals [draft] as `cache/transfer/<[name]>`.
     *
     * @throws NoAttachmentFolder when the pack carries documents and there is no attachment folder to read them from.
     * @throws BackupSetIncomplete when a document is missing or has changed since it was added (the shipped wording).
     */
    suspend fun write(draft: TransferPackDraft, name: String): WrittenPack {
        require(name == File(name).name && !name.startsWith(".")) { "a pack's file name is a bare name" }
        val store = storage.store()
        if (store == null && draft.plan.entries.isNotEmpty()) throw NoAttachmentFolder()
        work.mkdirs()
        packs.mkdirs()
        val artifacts = File(work, "${draft.packId}.zip")
        val pack = File(packs, name)
        try {
            val written = artifacts.outputStream().use { out ->
                // With no documents this is a manifest-only archive, and `store` may be null.
                ArtifactsCodec.write(out, draft.plan) { locator -> store?.open(locator) }
            }
            if (!written.covers(draft.plan)) throw BackupSetIncomplete(written.missing, written.mismatched)
            currentCoroutineContext().ensureActive()
            val sealed = TransferPackCodec.write(pack.outputStream(), draft) { artifacts.inputStream() }
            currentCoroutineContext().ensureActive()
            return WrittenPack(pack, sealed.packSha256, sealed.packBytes)
        } catch (t: Throwable) {
            pack.delete()
            throw t
        } finally {
            artifacts.delete()
        }
    }

    /** The pack named [name] — a bare name in `cache/transfer/` — or null when it is gone (or not one of ours). */
    fun find(name: String): File? {
        if (name.isEmpty() || name != File(name).name || name.startsWith(".")) return null
        return File(packs, name).takeIf { it.isFile }
    }

    /** Best effort; an absent file is not an error. */
    fun delete(name: String) {
        find(name)?.delete()
    }

    /**
     * R77-18, at app start: every intake copy and work file made **before** this process started ([startedAt]) — a
     * copy made since belongs to a share this process is already handling — and every pack older than a day
     * ([now] − [PACK_LIFETIME_MS]). A fresh pack stays, so a ready screen restored after a process death still finds
     * its file.
     */
    fun sweepAtStart(startedAt: Long, now: Long) {
        listOf(inbox, work).forEach { dir ->
            dir.listFiles()?.filter { it.isFile && it.lastModified() < startedAt }?.forEach { it.delete() }
        }
        packs.listFiles()?.filter { it.isFile && it.lastModified() < now - PACK_LIFETIME_MS }?.forEach { it.delete() }
    }

    companion object {
        /** The provider-exposed directory (`<cache-path name="transfer" path="transfer/" />`). */
        const val PACKS = "transfer"

        /** The artifacts archive while a pack is sealed. */
        const val WORK = "transfer-work"

        /** B3's inbox ([com.loosecannon.servicetag.ui.transfer.import.CacheTransferPackInbox]). */
        const val INBOX = "transfer-in"

        /** A pack older than this is swept at start (R77-18). */
        const val PACK_LIFETIME_MS = 24L * 60 * 60 * 1000
    }
}
