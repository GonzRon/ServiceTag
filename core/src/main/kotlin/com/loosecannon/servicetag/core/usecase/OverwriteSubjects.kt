package com.loosecannon.servicetag.core.usecase

import com.loosecannon.nfc.tagcore.OverwriteDecision
import com.loosecannon.nfc.tagcore.OverwriteReason
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagStatus

/**
 * What the overwrite sheet says the tag already holds (#70): [line] names the one sentence in the
 * warning surface, [identifier] the quiet mono line under it — the shortened id, plus the tag's
 * placement for a known labelled row — or null when no ServiceTag id applies.
 *
 * #102: [line] says *which* sentence and carries what fills it; the words themselves are the app's,
 * in the owner's language (`ui/scan/OverwriteWords.kt`). [identifier] is an id and the owner's own
 * label, never words, so it is built here.
 */
data class OverwriteSubject(val line: OverwriteLine, val identifier: String?)

/**
 * The overwrite sheet's sentences, one per state (plan §5, ratified 2026-09-26), language-neutral
 * (#102): each names a ratified sentence and holds only what fills it — the owner's asset name, or
 * the library's evidence — never a word of its own.
 */
sealed interface OverwriteLine {
    /** P70-1: the tag is bound to an asset this phone knows; [assetName] is the owner's own name for it. */
    data class Identifies(val assetName: String) : OverwriteLine

    /** P70-2: a row this phone holds that is not assigned to anything. */
    data object NotAssigned : OverwriteLine

    /** P70-3a: a row marked lost. */
    data object MarkedLost : OverwriteLine

    /** P70-3b: a retired row. */
    data object Retired : OverwriteLine

    /** P70-4: the inspect sheet's own sentence for a v1 id this phone has no row for. */
    data object NotInRecords : OverwriteLine

    /** P70-5: the inspect sheet's `PRE_SPLIT_LINK_SENTENCE`. */
    data object PreSplitLink : OverwriteLine

    /** P70-6: the lookup failed or ran out of time: unknown right now, never "not in the records" (R70-5). */
    data object CouldNotCheck : OverwriteLine

    /** A ServiceTag payload in a newer format; [format] is the version the tag carries. */
    data class NewerFormat(val format: String) : OverwriteLine

    /** Another product's NDEF content; [detail] is the codec's description of it. */
    data class Foreign(val detail: String) : OverwriteLine

    /** NDEF that could not be parsed; [detail] is the reader's reason. */
    data class Unreadable(val detail: String) : OverwriteLine
}

/**
 * Which of the sheet's sentences applies, from the library's question and — for a different v1
 * identity only — what [ResolveTag.peek] made of the id. It lives here, beside [Resolution], because
 * `core/nfc` does not know what an id means on this phone. Presentation only: nothing here decides
 * whether to ask or whether to write (plan §5, ratified 2026-09-26).
 */
object OverwriteSubjects {
    /** [resolution] matters only for a different v1 identity; `null` means the lookup did not answer. */
    fun of(c: OverwriteDecision.Confirm, resolution: Resolution?): OverwriteSubject = when (c.reason) {
        OverwriteReason.OTHER_TAG_SAME_PRODUCT -> OverwriteSubject(lineFor(resolution), identifierFor(c.detail, resolution))
        // The three non-v1 lines: the token picks, the detail fills (target §4.2 invariant 13).
        OverwriteReason.SAME_PRODUCT_UNSUPPORTED -> OverwriteSubject(OverwriteLine.NewerFormat(c.detail), identifier = null)
        OverwriteReason.FOREIGN -> OverwriteSubject(OverwriteLine.Foreign(c.detail), identifier = null)
        OverwriteReason.UNREADABLE -> OverwriteSubject(OverwriteLine.Unreadable(c.detail), identifier = null)
        OverwriteReason.EMPTY_TAG, OverwriteReason.SAME_TAG -> error("${c.reason} never asks a question")
    }

    /** No asset name reaches any line but the bound one: `Unbound` and `Revoked` carry no asset. */
    private fun lineFor(resolution: Resolution?): OverwriteLine = when (resolution) {
        is Resolution.OpenAsset -> OverwriteLine.Identifies(resolution.asset.name)
        // #77 (C20): never asked — the write screen offers no overwrite of a transferred-out asset's tag.
        is Resolution.TransferredOut -> OverwriteLine.Identifies(resolution.asset.name)
        is Resolution.Unbound -> OverwriteLine.NotAssigned
        is Resolution.Revoked -> if (resolution.tag.status == TagStatus.LOST) OverwriteLine.MarkedLost else OverwriteLine.Retired
        is Resolution.PreSplitLink -> OverwriteLine.PreSplitLink
        is Resolution.UnknownV1 -> OverwriteLine.NotInRecords
        // A v1 payload never resolves to the last two; if one ever did, the honest line stands.
        null, is Resolution.NeedsNewerApp, is Resolution.NotOurs -> OverwriteLine.CouldNotCheck
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
        is Resolution.TransferredOut -> tag
        is Resolution.Unbound -> tag
        is Resolution.Revoked -> tag
        is Resolution.PreSplitLink -> tag
        is Resolution.UnknownV1, is Resolution.NeedsNewerApp, is Resolution.NotOurs -> null
    }
}
