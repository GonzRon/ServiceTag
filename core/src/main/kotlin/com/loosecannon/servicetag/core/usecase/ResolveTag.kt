package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.model.openOuts
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.nfc.TagPayload
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.transfer.AssetTransferredOut

/** Every way a scan can end (D3 §9). The UI switches on this and nothing else. */
sealed interface Resolution {
    data class OpenAsset(val tag: TagBinding, val asset: Asset) : Resolution

    /**
     * 2.6 — the tag names a link from before the product split. ServiceTag does not read the link
     * row, let alone launch it: the row is a tombstone and this outcome is the whole answer. It is
     * deliberately not `Unbound`, which offers a bind, and not `NotOurs`, which it is not.
     */
    data class PreSplitLink(val tag: TagBinding) : Resolution

    /**
     * #77 (C20, R77-11) — the tag's asset was transferred out from this phone: [record] is the open OUT that holds
     * it (the latest, if a double mark left two). Never [OpenAsset], so no maintenance sheet opens for it, and a
     * scan does not stamp the tag.
     */
    data class TransferredOut(val tag: TagBinding, val asset: Asset, val record: TransferRecord) : Resolution
    data class Unbound(val tag: TagBinding) : Resolution
    data class Revoked(val tag: TagBinding) : Resolution
    data class UnknownV1(val tagId: TagId) : Resolution
    data class NeedsNewerApp(val version: Int) : Resolution
    data class NotOurs(val payload: TagPayload) : Resolution
}

class ResolveTag(
    private val tags: TagRepository,
    private val assets: AssetRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
    /** #77 (C20): the transfer records, asked inside the same transaction. Required: a missing one would fail open. */
    private val transfers: TransferRecordRepository,
) {
    /**
     * A scan. Lookup is by (format, key) — never by row id (D4 §3). A hit records the scan — except on a tag whose
     * owning asset is transferred out from this phone (#77, C12, R77-11): the guarded tag port refuses the stamp
     * before it writes, by the one ownership rule (a 2.6 link tag through its tombstone link included), and the scan
     * classifies the stored row, unstamped, rather than failing.
     */
    suspend fun run(payload: TagPayload): Resolution = resolve(payload) { format, key ->
        uow.write {
            val row = tags.findByPayload(format, key) ?: return@write null
            // #77 (C20): a held asset's tag answers from the stored row, before any stamp is attempted.
            classify(row).takeIf { it is Resolution.TransferredOut }?.let { return@write it }
            val tag = row.copy(lastScannedAt = clock.nowMillis())
            try {
                tags.upsert(tag)
            } catch (e: AssetTransferredOut) {
                return@write classify(row)
            }
            classify(tag)
        }
    }

    /**
     * A question, not a scan (#70 C1, R70-1): the same classification as [run], read inside one
     * read transaction, with no `lastScannedAt` stamp and no upsert — asking what a tag already
     * identifies before overwriting it leaves the store exactly as it was.
     */
    suspend fun peek(payload: TagPayload): Resolution = resolve(payload) { format, key ->
        uow.read { tags.findByPayload(format, key)?.let { classify(it) } }
    }

    private suspend fun resolve(
        payload: TagPayload,
        known: suspend (format: PayloadFormat, key: String) -> Resolution?,
    ): Resolution = when (payload) {
        is TagPayload.V1 -> known(PayloadFormat.V1, payload.tagId.value) ?: Resolution.UnknownV1(payload.tagId)
        is TagPayload.NewerVersion -> Resolution.NeedsNewerApp(payload.version)
        is TagPayload.Foreign, is TagPayload.Malformed, TagPayload.Empty -> Resolution.NotOurs(payload)
    }

    /** The open OUT that holds [asset] here — the latest by `at`, then id — or null when it is not held. */
    private suspend fun heldBy(asset: AssetId): TransferRecord? =
        openOuts(transfers.forAsset(asset)).maxWithOrNull(compareBy({ it.at }, { it.id }))

    /** The one interpretation of a known row; [run] and [peek] both end here. */
    private suspend fun classify(tag: TagBinding): Resolution = when {
        tag.status == TagStatus.LOST || tag.status == TagStatus.RETIRED -> Resolution.Revoked(tag)
        tag.status == TagStatus.UNBOUND -> Resolution.Unbound(tag)
        else -> when (val t = tag.target) {
            is TagTarget.AssetTarget -> assets.get(t.assetId)?.let { asset ->
                heldBy(asset.id)?.let { Resolution.TransferredOut(tag, asset, it) } ?: Resolution.OpenAsset(tag, asset)
            } ?: Resolution.Unbound(tag)
            is TagTarget.LinkTarget -> Resolution.PreSplitLink(tag)
            TagTarget.None -> Resolution.Unbound(tag)
        }
    }
}
