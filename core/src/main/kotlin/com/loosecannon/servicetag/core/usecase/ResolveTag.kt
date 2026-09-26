package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
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

/** Every way a scan can end (D3 §9). The UI switches on this and nothing else. */
sealed interface Resolution {
    data class OpenAsset(val tag: TagBinding, val asset: Asset) : Resolution

    /**
     * 2.6 — the tag names a link from before the product split. ServiceTag does not read the link
     * row, let alone launch it: the row is a tombstone and this outcome is the whole answer. It is
     * deliberately not `Unbound`, which offers a bind, and not `NotOurs`, which it is not.
     */
    data class PreSplitLink(val tag: TagBinding) : Resolution
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
) {
    /** A scan. Lookup is by (format, key) — never by row id (D4 §3). A hit records the scan. */
    suspend fun run(payload: TagPayload): Resolution = resolve(payload) { format, key ->
        uow.write {
            val row = tags.findByPayload(format, key) ?: return@write null
            val tag = row.copy(lastScannedAt = clock.nowMillis())
            tags.upsert(tag)
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

    /** The one interpretation of a known row; [run] and [peek] both end here. */
    private suspend fun classify(tag: TagBinding): Resolution = when {
        tag.status == TagStatus.LOST || tag.status == TagStatus.RETIRED -> Resolution.Revoked(tag)
        tag.status == TagStatus.UNBOUND -> Resolution.Unbound(tag)
        else -> when (val t = tag.target) {
            is TagTarget.AssetTarget -> assets.get(t.assetId)?.let { Resolution.OpenAsset(tag, it) } ?: Resolution.Unbound(tag)
            is TagTarget.LinkTarget -> Resolution.PreSplitLink(tag)
            TagTarget.None -> Resolution.Unbound(tag)
        }
    }
}
