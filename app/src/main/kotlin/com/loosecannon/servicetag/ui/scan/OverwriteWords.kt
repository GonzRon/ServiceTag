package com.loosecannon.servicetag.ui.scan

import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.usecase.OverwriteLine
import com.loosecannon.servicetag.core.usecase.OverwriteSubject
import com.loosecannon.servicetag.l10n.localized

/**
 * What the overwrite sheet draws (#70), in the owner's language (#102): [line] is the one sentence
 * in the warning surface, [identifier] the quiet mono line under it — the shortened id and any
 * placement — or null when no ServiceTag id applies.
 */
data class OverwriteWords(val line: String, val identifier: String?)

/** [OverwriteSubject] worded. The identifier is an id and the owner's own label, so it passes through unchanged. */
internal fun OverwriteSubject.words(): OverwriteWords = OverwriteWords(overwriteSentence(line), identifier)

/**
 * The sheet's sentence for [line] (plan §5, ratified 2026-09-26). P70-4 and P70-5 are the inspect
 * sheet's own sentences, read from the very same resources, so the write flow and the scan flow
 * cannot drift apart. The owner's asset name and the library's evidence are arguments, shown as is.
 */
internal fun overwriteSentence(line: OverwriteLine): String = when (line) {
    is OverwriteLine.Identifies -> localized(R.string.tag_overwrite_identifies, line.assetName)
    OverwriteLine.NotAssigned -> localized(R.string.tag_overwrite_not_assigned)
    OverwriteLine.MarkedLost -> localized(R.string.tag_overwrite_marked_lost)
    OverwriteLine.Retired -> localized(R.string.tag_overwrite_retired)
    OverwriteLine.NotInRecords -> localized(R.string.tag_not_in_records)
    OverwriteLine.PreSplitLink -> PRE_SPLIT_LINK_SENTENCE
    OverwriteLine.CouldNotCheck -> localized(R.string.tag_overwrite_could_not_check)
    is OverwriteLine.NewerFormat -> localized(R.string.tag_overwrite_holds_newer, line.format)
    is OverwriteLine.Foreign -> localized(R.string.tag_overwrite_holds_foreign, line.detail)
    is OverwriteLine.Unreadable -> localized(R.string.tag_overwrite_holds_unreadable, line.detail)
}
