package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.ArtifactsCodec
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.transfer.TransferGraph
import com.loosecannon.servicetag.core.transfer.TransferPack
import com.loosecannon.servicetag.core.transfer.TransferPackCodec
import com.loosecannon.servicetag.core.transfer.TransferPackDraft
import com.loosecannon.servicetag.core.transfer.TransferRefusal
import com.loosecannon.servicetag.core.transfer.TransferSelection

sealed interface CreateTransferPackResult {
    /** P77-15 to P77-18: the selection cannot leave as it stands; every reason, together. */
    data class Refused(val refusals: List<TransferRefusal>) : CreateTransferPackResult

    /**
     * P77-59: [part] of the pack would be at least [bytes] bytes, over [limit] — refused here rather than built
     * for every recipient's reader to call damaged (P77-49).
     */
    data class TooLarge(val part: Part, val bytes: Long, val limit: Long) : CreateTransferPackResult {
        /** `data.zip` as stored; the pack's manifest; `data.zip`'s entries inflated together. */
        enum class Part { DATA, MANIFEST, INFLATED_DATA }
    }

    data class Created(val draft: TransferPackDraft) : CreateTransferPackResult
}

/**
 * #77 (C5; AC 1, 2, 15) — the pack's contents, and nothing more: one read transaction over every
 * canonical table ([readSnapshot], the export's own), the selection (C2), the data archive encoded by the
 * unchanged codec with the pack id as its set id, and the plan for the pack's own MANAGED rows. It
 * **writes nothing**: no store, no record, no file — the app streams the plan and seals the pack (B4),
 * and marking is a separate, later write (C8).
 *
 * [lineageOf] answers, inside the read, the pack ids an asset travelled in before this one, oldest first
 * (B2a wires the transfer records' `lineageFor`; until then every asset starts here). [maxDataBytes] is
 * [TransferPack.MAX_PACK_DATA_BYTES], and [maxManifestBytes] and [maxJsonBytes] the reader's own manifest and
 * inflated caps (mn-1); only a test passes others.
 */
class CreateTransferPack(
    private val repos: BackupRepositories,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val appVersion: String,
    private val schemaVersion: Int,
    private val lineageOf: suspend (AssetId) -> List<String>,
    private val maxDataBytes: Long = TransferPack.MAX_PACK_DATA_BYTES,
    private val maxManifestBytes: Long = TransferPack.MAX_MANIFEST_BYTES,
    private val maxJsonBytes: Long = TransferPack.MAX_PACK_JSON_BYTES,
) {
    /**
     * @throws IllegalArgumentException when [rootIds] is empty or names no asset, or [note] breaks the
     *   note rule ([TransferPack.noteAccepted]) — the selection screen offers neither.
     */
    suspend fun run(rootIds: Collection<AssetId>, note: String = ""): CreateTransferPackResult {
        require(TransferPack.noteAccepted(note)) { "a pack's note is one line of at most ${TransferPack.MAX_NOTE_CHARS} characters" }
        val (selection, lineage) = uow.read {
            val selection = TransferGraph.select(readSnapshot(repos), rootIds)
            val lineage = (selection as? TransferSelection.Selected)?.assetIds
                ?.associate { it.value to lineageOf(it) }
                ?.toSortedMap()
            selection to lineage
        }
        val selected = when (selection) {
            is TransferSelection.Refused -> return CreateTransferPackResult.Refused(selection.refusals)
            is TransferSelection.Selected -> selection
        }
        val packId = ids.newId()
        val createdAt = clock.nowMillis()
        val data = BackupCodec.encode(selected.data, appVersion, schemaVersion, createdAt, packId)
        if (data.size > maxDataBytes) {
            return CreateTransferPackResult.TooLarge(CreateTransferPackResult.TooLarge.Part.DATA, data.size.toLong(), maxDataBytes)
        }
        // mn-1: never build what every recipient's reader would call damaged — the same caps, the same helper.
        val inflated = TransferPack.inflatedSize(data, maxJsonBytes)
        if (inflated > maxJsonBytes) {
            return CreateTransferPackResult.TooLarge(CreateTransferPackResult.TooLarge.Part.INFLATED_DATA, inflated, maxJsonBytes)
        }
        // The inner manifest is the one home of the counts and the content hash: read them back from it.
        val inner = BackupCodec.decode(data).manifest
        val draft = TransferPackDraft(
            packId = packId,
            createdAt = createdAt,
            appVersion = appVersion,
            schemaVersion = schemaVersion,
            dataFormatVersion = inner.formatVersion,
            artifactFormatVersion = ArtifactsCodec.ARTIFACT_FORMAT_VERSION,
            rootAssetIds = selected.rootIds.map { it.value },
            assetIds = selected.assetIds.map { it.value },
            lineage = lineage!!,
            counts = inner.counts,
            attachments = inner.artifactCount,
            attachmentBytes = inner.artifactBytes,
            contentSha256 = inner.dataSha256,
            note = note,
            data = data,
            plan = artifactsPlanOf(selected.data, packId, createdAt),
        )
        // The artifacts' hash is not known yet; a placeholder of its exact length measures the manifest as sealed.
        val manifestBytes = TransferPackCodec.encodeManifest(draft.manifest(artifactsSha256 = "0".repeat(64))).size.toLong()
        if (manifestBytes > maxManifestBytes) {
            return CreateTransferPackResult.TooLarge(CreateTransferPackResult.TooLarge.Part.MANIFEST, manifestBytes, maxManifestBytes)
        }
        return CreateTransferPackResult.Created(draft)
    }
}
