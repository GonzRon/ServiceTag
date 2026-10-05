package com.loosecannon.servicetag.core.nfc

import com.loosecannon.nfc.tagcore.ExistingContent
import com.loosecannon.nfc.tagcore.OverwriteDecision
import com.loosecannon.nfc.tagcore.OverwritePolicy
import com.loosecannon.servicetag.core.model.TagId

/**
 * ServiceTag's side of the read-before-write rule: the library decides (`OverwritePolicy`, tokens
 * and evidence); this product classifies what its codec read. Turning a token into this product's
 * sentence is `OverwriteSubjects`' (core/usecase), which names the sentence, and the app's, which
 * words it in the owner's language (#102) — the library builds no sentence (target §4.2 invariant 13).
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
}
