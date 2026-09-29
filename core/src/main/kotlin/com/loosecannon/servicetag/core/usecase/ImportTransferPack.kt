package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.ArtifactsCodec
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.merge.MergePlan
import com.loosecannon.servicetag.core.merge.MergeReason
import com.loosecannon.servicetag.core.merge.MergeReport
import com.loosecannon.servicetag.core.merge.MergeTable
import com.loosecannon.servicetag.core.merge.MergeVerdict
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.model.heldIds
import com.loosecannon.servicetag.core.model.returnsHere
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.AttachmentStore
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.StoredBytes
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.transfer.TransferPack
import com.loosecannon.servicetag.core.transfer.TransferPackManifest
import com.loosecannon.servicetag.core.transfer.TransferPackRead
import com.loosecannon.servicetag.core.transfer.TransferPackReader
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** One asset of a Transfer Pack, by id and by the name the pack gives it. */
data class PackAsset(val id: AssetId, val name: String)

/** P77-46: [incoming] — a pack asset's name — may already be here as [local]. Reported; never acted on. */
data class PackDuplicate(val incoming: String, val local: String)

/** What a Transfer Pack's preview shows (C14, C16), before anything is written. */
enum class TransferImportOutcome {
    /** Every row, the IN records included, is already here (P77-43). */
    ALREADY_HERE,

    /** A pack asset held here that this pack does not bring back (P77-67, R77-B3-RETURN). */
    NOT_BROUGHT_BACK,

    /** The plan holds a conflict (P77-44, and P77-45 per NFC tag bound here to another asset). */
    CONFLICTS,

    /** Import is offered (P77-42). */
    READY,
}

/** C14 (1)–(2): reading and planning a Transfer Pack. Nothing here has written anything. */
sealed interface TransferImportPreview {
    /** P77-47. */
    data object NotAPack : TransferImportPreview

    /** P77-48. */
    data object NewerPack : TransferImportPreview

    /** P77-49; [reason] is for the log, never the owner. */
    data class Damaged(val reason: String) : TransferImportPreview

    /** R77-14: the pack carries documents and this phone has no attachment folder — refused before the plan. */
    data object NoAttachmentFolder : TransferImportPreview

    /**
     * A whole pack, planned against this phone. [returning] are the assets it brings back (P77-66); [refused]
     * the held assets it does not (P77-67); [tagsUsedHere] the local assets whose NFC tag a pack tag's payload
     * already names (P77-45); [duplicates] the review hints (P77-46). Each list is sorted by name.
     */
    class Ready internal constructor(
        val manifest: TransferPackManifest,
        val packSha256: String,
        val plan: MergePlan,
        val returning: List<PackAsset>,
        val refused: List<PackAsset>,
        val tagsUsedHere: List<String>,
        val duplicates: List<PackDuplicate>,
        internal val pack: TransferPackRead.Pack,
    ) : TransferImportPreview {
        /** One answer, in the ruled precedence: P77-67, then P77-44, then P77-43, else Import. */
        val outcome: TransferImportOutcome = when {
            refused.isNotEmpty() -> TransferImportOutcome.NOT_BROUGHT_BACK
            !plan.applicable -> TransferImportOutcome.CONFLICTS
            plan.decisions.none { it.verdict == MergeVerdict.INSERT } -> TransferImportOutcome.ALREADY_HERE
            else -> TransferImportOutcome.READY
        }

        /** True exactly when Import is offered. */
        val importable: Boolean get() = outcome == TransferImportOutcome.READY
    }
}

/** C14 (3)–(5): what Import did. */
sealed interface TransferImportResult {
    /** P77-50, with the pack's own counts. */
    data class Imported(val manifest: TransferPackManifest, val report: MergeReport) : TransferImportResult

    /** P77-44: the destination moved into a conflict between the preview and the apply; nothing was written. */
    data class Conflicted(val report: MergeReport) : TransferImportResult

    /** R77-14 at Import: the folder went away since the preview. Nothing was written. */
    data object NoAttachmentFolder : TransferImportResult

    /** P77-52 (logged): nothing was changed — every byte this import staged was swept. */
    data class Failed(val cause: Throwable) : TransferImportResult
}

/** C14 (3): a document's bytes in the pack are not the bytes its row names. Everything staged is swept. */
class TransferStagingMismatch(val locator: String) : IOException("the staged bytes at $locator are not the pack's")

/**
 * #77 (C14, C15; R77-12, R77-14, R77-25, R77-B3-RETURN) — imports a Transfer Pack **additively**, through the shipped
 * merge core: one backup format, one merge engine, one attachment store.
 *
 * **[preview]** reads the pack (C4's reader; the file is hashed on the way past), refuses documents without an
 * attachment folder before anything is planned (R77-14), and plans the pack's archive — plus **one IN record per pack
 * asset**, its `lineage` the pack's — with [BuildBackupMergePlan], the pack's own documents overlaid only on locators
 * absent from the folder, so what the folder already holds is never masked. It writes nothing.
 *
 * **Returning assets (C15, R77-B3-RETURN).** A pack asset returns here when [returnsHere] finds this phone's OUT in its
 * lineage (an open OUT, or one withdrawn here — the rm-8 recovery) **and**, once its IN is appended, no OUT of the
 * asset stays open: `returnsHere` establishes ancestry and is necessary, not sufficient, so a pack never erases or
 * ignores a different outstanding transfer. Any other pack asset held here is refused (P77-67) — a stale or foreign
 * pack, or a return while another OUT stays open — and nothing is offered; the recovery is explicit (withdraw the
 * other record, then import again). A pack whose valid lineage returns the asset is a bearer instrument by design
 * (R77-25). The IN of a returning asset is appended **first**, and its stale local graph replaced in the same write
 * ([ApplyBackupMergePlan]).
 *
 * **[import]** stages each document whose locator is absent from the folder, never overwriting, each verified by
 * size and sha256 against the pack's manifest and row, remembering every locator it wrote; then applies through
 * [ApplyBackupMergePlan], which re-plans inside its one write. On any refusal or failure **before the write commits**
 * it sweeps exactly what it staged; once it has committed — or a cancellation arrives after Room committed, which this
 * pack's IN records being here shows — the staged bytes are the committed rows' and stay (MJ-1). The removed
 * documents of a return are swept after the commit, not cancellably. Known limit (C14): a crash between staging and
 * the apply leaves unnamed files for the sweep to find.
 *
 * An IN record's id is derived from the pack and the asset, and a re-import reuses the IN already here, so importing
 * one pack twice plans every row IDENTICAL (P77-43).
 */
class ImportTransferPack(
    private val build: BuildBackupMergePlan,
    private val apply: ApplyBackupMergePlan,
    private val transfers: TransferRecordRepository,
    private val assets: AssetRepository,
    private val tags: TagRepository,
    private val storage: AttachmentStorage,
    private val uow: UnitOfWork,
    private val clock: Clock,
) {
    suspend fun preview(open: () -> InputStream): TransferImportPreview {
        val digest = MessageDigest.getInstance("SHA-256")
        val read = open().use { raw ->
            val hashed = DigestInputStream(raw, digest)
            val answer = TransferPackReader.read(hashed)
            // The reader stops at the ZIP's central directory; the file's sha256 covers every byte.
            val buffer = ByteArray(BUFFER)
            while (hashed.read(buffer) >= 0) Unit
            answer
        }
        val pack = when (read) {
            TransferPackRead.NotAPack -> return TransferImportPreview.NotAPack
            is TransferPackRead.NewerPack -> return TransferImportPreview.NewerPack
            is TransferPackRead.Damaged -> return TransferImportPreview.Damaged(read.reason)
            is TransferPackRead.Pack -> read
        }
        // R77-14: documents need a folder on this side too — refused before anything is planned.
        if (pack.artifacts.entries.isNotEmpty() && storage.store() == null) {
            return TransferImportPreview.NoAttachmentFolder
        }
        val packSha256 = TransferPack.hex(digest.digest())
        val (local, localAssets, localTags) = uow.read { Triple(transfers.all(), assets.all(), tags.all()) }
        val data = pack.backup.data
        val names = data.assets.associate { it.id to it.name }
        val localById = local.associateBy { it.id }
        val ins = pack.manifest.assetIds.map { id ->
            val asset = AssetId(id)
            localById[inRecordId(pack.manifest.packId, asset)] ?: TransferRecord(
                id = inRecordId(pack.manifest.packId, asset),
                assetId = asset,
                kind = TransferKind.IN,
                packId = pack.manifest.packId,
                lineage = pack.manifest.lineage.getValue(id),
                at = clock.nowMillis(),
                packSha256 = packSha256,
                nameSnapshot = names.getValue(id),
                note = pack.manifest.note,
            )
        }
        val (returning, refused) = returningOf(local, ins)

        val locatorOf = data.attachments.associate { it.id to it.storageLocator }
        val incoming = pack.artifacts.entries.associate { entry ->
            locatorOf.getValue(entry.attachmentId) to StoredBytes(entry.sha256, entry.sizeBytes)
        }
        val backup = pack.backup.copy(data = data.copy(transferRecords = ins.map { it.toDto() }))
        // A pack that leaves any held asset behind is never imported (P77-67), so its plan is not a return's: the
        // replaced graph is computed — and mn-1's refusal checked — only for a pack that can come back whole.
        val plan = build.run(backup, incoming, if (refused.isEmpty()) returning else emptySet())

        val assetNames = localAssets.associate { it.id.value to it.name }
        val tagsById = localTags.associateBy { it.id.value }
        val tagsUsedHere = plan.conflicts
            .filter { it.table == MergeTable.TAGS && it.reason == MergeReason.PAYLOAD_BOUND_TO_ANOTHER_ASSET }
            .mapNotNull { conflict -> (tagsById[conflict.detail]?.target as? TagTarget.AssetTarget)?.assetId }
            .mapNotNull { assetNames[it.value] }
            .distinct()
            .sorted()
        val duplicates = plan.duplicateCandidates.mapNotNull { candidate ->
            val incomingName = names[candidate.incomingAssetId] ?: return@mapNotNull null
            val localName = assetNames[candidate.localAssetId] ?: return@mapNotNull null
            PackDuplicate(incomingName, localName)
        }
        fun named(ids: Set<AssetId>) = ids.map { PackAsset(it, names.getValue(it.value)) }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id.value }))
        return TransferImportPreview.Ready(
            manifest = pack.manifest,
            packSha256 = packSha256,
            plan = plan,
            returning = named(returning),
            refused = named(refused),
            tagsUsedHere = tagsUsedHere,
            duplicates = duplicates,
            pack = pack,
        )
    }

    /**
     * Stages, applies, and sweeps on any refusal or failure (C14 (3)–(5)). [open] reads the same file the preview
     * read. Only a preview that offers Import may be imported.
     */
    suspend fun import(ready: TransferImportPreview.Ready, open: () -> InputStream): TransferImportResult {
        require(ready.importable) { "this Transfer Pack's preview does not offer Import" }
        val entries = ready.pack.artifacts.entries
        val store = storage.store()
        if (entries.isNotEmpty() && store == null) return TransferImportResult.NoAttachmentFolder
        val written = mutableListOf<String>()
        // MJ-1: once the write has committed, the staged bytes belong to its rows and are never swept.
        var committed = false
        return try {
            if (store != null && entries.isNotEmpty()) stage(ready.pack, store, open, written)
            val applied = apply.runReturning(ready.plan, ready.returning.mapTo(LinkedHashSet()) { it.id })
            committed = true
            // C15: the removed documents' bytes the pack does not name — after the commit, best effort, and not
            // cancellable, so a back press here can neither undo nor half-do anything.
            withContext(NonCancellable) {
                try {
                    storage.sweepBytes(applied.removedLocators)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // An orphaned file, never a failed import: the rows are committed.
                }
            }
            TransferImportResult.Imported(ready.manifest, applied.report)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                // Room can commit and then deliver the cancellation on resume: this pack's INs being here says so.
                val planned = ready.plan.writes.transfers.mapTo(HashSet()) { it.id }
                val landed = committed || (planned.isNotEmpty() && transfers.all().any { it.id in planned })
                if (!landed) storage.sweepBytes(written)
            }
            throw e
        } catch (e: MergeRefused) {
            if (!committed) storage.sweepBytes(written)
            TransferImportResult.Conflicted(e.report)
        } catch (e: Exception) {
            if (!committed) storage.sweepBytes(written)
            TransferImportResult.Failed(e)
        }
    }

    /**
     * C14 (3): each document of [pack] whose locator is absent from [store] is written from `artifacts.zip` and
     * verified against its manifest line and its row; a locator that holds anything is left alone. Every locator
     * written is in [written] before its bytes are, so a failure mid-copy is swept too.
     */
    private suspend fun stage(
        pack: TransferPackRead.Pack,
        store: AttachmentStore,
        open: () -> InputStream,
        written: MutableList<String>,
    ) {
        val rows = pack.backup.data.attachments.associateBy { it.id }
        open().use { raw ->
            val outer = ZipInputStream(raw)
            while (true) {
                val entry = outer.nextEntry ?: throw IOException("the pack has no ${TransferPack.ARTIFACTS_ENTRY}")
                if (entry.name == TransferPack.ARTIFACTS_ENTRY) break
            }
            ArtifactsCodec.read(
                source = outer,
                onManifest = { manifest ->
                    if (manifest.backupSetId != pack.manifest.packId) {
                        throw IOException("${TransferPack.ARTIFACTS_ENTRY} belongs to another pack")
                    }
                },
                onEntry = stage@{ entry, input ->
                    val row = rows[entry.attachmentId] ?: return@stage
                    val locator = row.storageLocator
                    // Never overwrite: a locator that holds anything is the folder's, not this import's.
                    if (store.exists(locator)) return@stage
                    written += locator
                    val stored = store.put(locator, ByteSource { input })
                    if (stored.sha256 != entry.sha256 || stored.sizeBytes != entry.sizeBytes ||
                        stored.sha256 != row.sha256 || stored.sizeBytes != row.sizeBytes
                    ) {
                        throw TransferStagingMismatch(locator)
                    }
                },
            )
        }
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}

/**
 * C15 and R77-B3-RETURN, per asset: which pack assets come back here, and which held ones the pack does not bring
 * back. [ins] are the pack's IN records, one per asset.
 */
internal fun returningOf(local: List<TransferRecord>, ins: List<TransferRecord>): Pair<Set<AssetId>, Set<AssetId>> {
    val held = heldIds(local)
    val returning = LinkedHashSet<AssetId>()
    val refused = LinkedHashSet<AssetId>()
    for (record in ins) {
        val asset = record.assetId
        val back = returnsHere(local, asset, record.lineage) && asset !in heldIds(local + record)
        when {
            back -> returning += asset
            asset in held -> refused += asset
        }
    }
    return returning to refused
}

/** An IN record's id: one per pack and asset, so a re-import finds the one already here. */
internal fun inRecordId(packId: String, asset: AssetId): String =
    UUID.nameUUIDFromBytes("transfer-in\u0000$packId\u0000${asset.value}".toByteArray(Charsets.UTF_8)).toString()
