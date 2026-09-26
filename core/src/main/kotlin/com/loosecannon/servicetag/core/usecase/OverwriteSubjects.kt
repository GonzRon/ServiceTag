package com.loosecannon.servicetag.core.usecase

import com.loosecannon.nfc.tagcore.OverwriteDecision
import com.loosecannon.nfc.tagcore.OverwriteReason
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.nfc.OverwriteReasons

/**
 * What the overwrite sheet says the tag already holds (#70): [line] is the one sentence in the
 * warning surface, [identifier] the quiet mono line under it — the shortened id, plus the tag's
 * placement for a known labelled row — or null when no ServiceTag id applies.
 */
data class OverwriteSubject(val line: String, val identifier: String?)

/**
 * The sheet's words, from the library's question and — for a different v1 identity only — what
 * [ResolveTag.peek] made of the id. It lives here, beside [Resolution], because `core/nfc` does
 * not know what an id means on this phone. Presentation only: nothing here decides whether to ask
 * or whether to write (plan §5, ratified 2026-09-26).
 */
object OverwriteSubjects {
    const val NOT_ASSIGNED = "This tag is in this phone's records but is not assigned to anything yet."
    const val MARKED_LOST = "This tag was marked lost and taken out of service."
    const val RETIRED = "This tag was retired and taken out of service."

    /** The inspect sheet's own sentence for the same state, verbatim. */
    const val NOT_IN_RECORDS = "This ServiceTag tag is not in this phone's records."

    /** The inspect sheet's `PRE_SPLIT_LINK_SENTENCE`, verbatim. */
    const val PRE_SPLIT_LINK = "This tag points at a note link from before the product split. ServiceTag no longer opens links; NoteTag does."

    /** The lookup failed or ran out of time: unknown right now, never "not in the records" (R70-5). */
    const val COULD_NOT_CHECK = "This is a ServiceTag tag, but its record could not be checked just now."

    /** The tag is bound to an asset this phone knows. */
    fun identifies(assetName: String): String = "This tag currently identifies " + assetName + "."

    /** [resolution] matters only for a different v1 identity; `null` means the lookup did not answer. */
    fun of(c: OverwriteDecision.Confirm, resolution: Resolution?): OverwriteSubject = when (c.reason) {
        OverwriteReason.OTHER_TAG_SAME_PRODUCT -> OverwriteSubject(lineFor(resolution), identifierFor(c.detail, resolution))
        OverwriteReason.SAME_PRODUCT_UNSUPPORTED, OverwriteReason.FOREIGN, OverwriteReason.UNREADABLE ->
            OverwriteSubject("The tag already holds " + OverwriteReasons.sentence(c) + ".", identifier = null)
        OverwriteReason.EMPTY_TAG, OverwriteReason.SAME_TAG -> error("${c.reason} never asks a question")
    }

    /** No asset name reaches any line but the bound one: `Unbound` and `Revoked` carry no asset. */
    private fun lineFor(resolution: Resolution?): String = when (resolution) {
        is Resolution.OpenAsset -> identifies(resolution.asset.name)
        is Resolution.Unbound -> NOT_ASSIGNED
        is Resolution.Revoked -> if (resolution.tag.status == TagStatus.LOST) MARKED_LOST else RETIRED
        is Resolution.PreSplitLink -> PRE_SPLIT_LINK
        is Resolution.UnknownV1 -> NOT_IN_RECORDS
        // A v1 payload never resolves to the last two; if one ever did, the honest line stands.
        null, is Resolution.NeedsNewerApp, is Resolution.NotOurs -> COULD_NOT_CHECK
    }

    /**
     * `identityLine(key)`'s rule — the first eight characters and the format — with the placement
     * of a known row appended as the Written state appends "locked" (R70-3). [key] is the payload
     * key, which is the row id of every row `ProvisionTag` and `BindTag` create.
     */
    private fun identifierFor(key: String, resolution: Resolution?): String {
        val short = "${key.take(8)} · ${PayloadFormat.V1.name.lowercase()}"
        val placement = resolution?.knownRow()?.label?.takeIf { it.isNotBlank() }
        return if (placement == null) short else "$short · $placement"
    }

    private fun Resolution.knownRow(): TagBinding? = when (this) {
        is Resolution.OpenAsset -> tag
        is Resolution.Unbound -> tag
        is Resolution.Revoked -> tag
        is Resolution.PreSplitLink -> tag
        is Resolution.UnknownV1, is Resolution.NeedsNewerApp, is Resolution.NotOurs -> null
    }
}
