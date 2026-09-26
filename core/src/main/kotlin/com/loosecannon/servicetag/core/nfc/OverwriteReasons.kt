package com.loosecannon.servicetag.core.nfc

import com.loosecannon.nfc.tagcore.ExistingContent
import com.loosecannon.nfc.tagcore.OverwriteDecision
import com.loosecannon.nfc.tagcore.OverwritePolicy
import com.loosecannon.nfc.tagcore.OverwriteReason
import com.loosecannon.servicetag.core.model.TagId

/**
 * ServiceTag's side of the read-before-write rule: the library decides (`OverwritePolicy`, tokens
 * and evidence); this product classifies what its codec read and turns a token into its own sentence
 * (target §4.2 invariant 13 — the library builds no sentence).
 */
object OverwriteReasons {
    fun existing(p: TagPayload): ExistingContent = when (p) {
        TagPayload.Empty -> ExistingContent.Empty
        is TagPayload.V1 -> ExistingContent.Ours(p.tagId.value)
        is TagPayload.NewerVersion -> ExistingContent.OursUnsupported(p.version.toString())
        is TagPayload.Foreign -> ExistingContent.Foreign(p.description)
        is TagPayload.Malformed -> ExistingContent.Unreadable(p.reason)
    }

    fun decide(existing: TagPayload, intended: TagId): OverwriteDecision =
        OverwritePolicy.decide(existing(existing), isSameIdentity = existing is TagPayload.V1 && existing.tagId == intended)

    /**
     * The three non-v1 sentences the confirmation sheet has always shown; the token picks, the
     * detail fills. A different v1 identity is worded by `OverwriteSubjects` (core/usecase), which
     * knows what that id means on this phone (#70, R70-4) — so it has no sentence here.
     */
    fun sentence(c: OverwriteDecision.Confirm): String = when (c.reason) {
        OverwriteReason.SAME_PRODUCT_UNSUPPORTED -> "a ServiceTag tag written by a newer app (format ${c.detail})"
        OverwriteReason.FOREIGN -> "foreign NDEF content (${c.detail})"
        OverwriteReason.UNREADABLE -> "unreadable NDEF content (${c.detail})"
        OverwriteReason.OTHER_TAG_SAME_PRODUCT -> error("${c.reason} is worded by OverwriteSubjects, which resolves the id")
        OverwriteReason.EMPTY_TAG, OverwriteReason.SAME_TAG -> error("${c.reason} never asks a question")
    }
}
