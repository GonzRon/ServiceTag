package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.ArtifactsCodec
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.heldIds
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.transfer.EntangledRef
import com.loosecannon.servicetag.core.transfer.TransferGraph
import com.loosecannon.servicetag.core.transfer.TransferPack
import com.loosecannon.servicetag.core.transfer.TransferPackCodec
import com.loosecannon.servicetag.core.transfer.TransferPackDraft
import com.loosecannon.servicetag.core.transfer.TransferRefusal
import com.loosecannon.servicetag.core.transfer.TransferRetention
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

    /** P77-57: [assetIds] of the selection — a root or a forced component — are already held here (R77-CREATE-SAFETY). */
    data class AlreadyTransferred(val assetIds: List<AssetId>) : CreateTransferPackResult

    /**
     * P77-20's entangled reason (R77-CREATE-SAFETY): marking this pack now would leave an estate that does not retain
     * cleanly — [refs] — so it could never be marked, and is not made to be shared.
     */
    data class Entangled(val refs: List<EntangledRef>) : CreateTransferPackResult

    data class Created(val draft: TransferPackDraft) : CreateTransferPackResult
}

/**
 * #77 (C5; AC 1, 2, 15) — the pack's contents, and nothing more: one read transaction over every
 * canonical table ([readSnapshot], the export's own), the selection (C2), the data archive encoded by the
 * unchanged codec with the pack id as its set id, and the plan for the pack's own MANAGED rows. It
 * **writes nothing**: no store, no record, no file — the app streams the plan and seals the pack (B4),
 * and marking is a separate, later write (C8).
 *
 * R77-CREATE-SAFETY: inside the same read, and before any pack id or byte exists, it proves the mark could be
 * committed now, by marking's own rules: a selected asset already held here is refused
 * ([CreateTransferPackResult.AlreadyTransferred], P77-57), and so is a pack whose mark would leave the complete estate
 * entangled — `TransferGraph.retain(snapshot, heldIds ∪ selected)` ([CreateTransferPackResult.Entangled], P77-20).
 * Marking re-proves both inside its own write (C8).
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
        val read: Reading = uow.read {
            val snapshot = readSnapshot(repos)
            when (val selection = TransferGraph.select(snapshot, rootIds)) {
                is TransferSelection.Refused -> Reading.Refuse(CreateTransferPackResult.Refused(selection.refusals))
                is TransferSelection.Selected -> safety(snapshot, selection)?.let(Reading::Refuse)
                    ?: Reading.Selected(selection, selection.assetIds.associate { it.value to lineageOf(it) }.toSortedMap())
            }
        }
        val (selected, lineage) = when (read) {
            is Reading.Refuse -> return read.result
            is Reading.Selected -> read
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
            lineage = lineage,
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

    /** What the one read came to: a refusal, or a selection that may leave with each asset's lineage. */
    private sealed interface Reading {
        data class Refuse(val result: CreateTransferPackResult) : Reading
        data class Selected(val selection: TransferSelection.Selected, val lineage: Map<String, List<String>>) : Reading
    }

    /** R77-CREATE-SAFETY, inside the read: null when the mark could be committed now, else the refusal. */
    private suspend fun safety(snapshot: BackupData, selection: TransferSelection.Selected): CreateTransferPackResult? {
        val held = heldIds(repos.transfers.all())
        selection.assetIds.filter { it in held }.takeIf { it.isNotEmpty() }?.let {
            return CreateTransferPackResult.AlreadyTransferred(it)
        }
        return when (val retention = TransferGraph.retain(snapshot, held + selection.assetIds)) {
            is TransferRetention.Retained -> null
            is TransferRetention.Entangled -> CreateTransferPackResult.Entangled(retention.refs)
        }
    }
}
