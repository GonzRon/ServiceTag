package com.loosecannon.servicetag.ui.transfer.`import`

/**
 * #77 (B3; plan §6, R77-24) — **every word the Transfer Pack import draws, verbatim from the ratified table**, one home
 * per literal. Nothing is paraphrased or composed from fragments beyond the placeholders the table names.
 *
 * [ASSET_TRANSFERRED_OUT] (P77-35) lands here for the share intake's refused write; B4's screens reuse it from here.
 * [countLines] are P77-6…11, which the review screen (B4) and P77-50 share.
 */
internal object TransferImportStrings {
    /** P77-35 — a write refused because the asset was transferred out. */
    const val ASSET_TRANSFERRED_OUT = "This asset was transferred out."

    /** P77-38 — the Backup screen's button and the import screen's title. */
    const val TITLE = "Import Transfer Pack"

    /** P77-39. */
    fun note(note: String): String = "Note: $note"

    /** P77-40. */
    fun created(date: String): String = "Created $date"

    /** P77-41. */
    const val CONTAINS = "Contains"

    /** P77-42. */
    const val IMPORT = "Import"

    /** P77-43. */
    const val ALREADY_HERE = "This Transfer Pack is already on this phone."

    /** P77-44. */
    const val CONFLICTS = "This Transfer Pack conflicts with records on this phone, so nothing was imported."

    /** P77-45. */
    fun tagUsedHere(asset: String): String = "An NFC tag in this pack is already used for $asset here."

    /** P77-46. */
    fun duplicate(incoming: String, local: String): String = "$incoming may already be here as $local."

    /** P77-47. */
    const val NOT_A_PACK = "This file is not a Transfer Pack."

    /** P77-48. */
    const val NEWER = "This Transfer Pack needs a newer version of ServiceTag."

    /** P77-49. */
    const val DAMAGED = "This Transfer Pack is damaged and cannot be imported."

    /** P77-50 — anchored apart from the shipped `Imported: …`. */
    fun imported(counts: String): String = "Transfer Pack imported: $counts"

    /** P77-52. */
    const val COULD_NOT_IMPORT = "Could not import this Transfer Pack. Nothing was changed."

    /** P77-66 (R77-25). */
    fun comingBack(asset: String): String = "Coming back: $asset"

    /** P77-67. */
    fun notBroughtBack(asset: String): String =
        "$asset was transferred out from this phone, and this Transfer Pack does not bring it back."

    /**
     * P77-6…11, in the table's order: `%d assets` · `%d NFC tags` · `%d records` · `%d schedules` ·
     * `%d documents and photos` · `%d service cases`, each with its `1 …` singular; a zero line is hidden, except
     * the assets'.
     */
    fun countLines(assets: Int, tags: Int, records: Int, schedules: Int, documents: Int, cases: Int): List<String> =
        listOfNotNull(
            if (assets == 1) "1 asset" else "$assets assets",
            count(tags, "1 NFC tag", "NFC tags"),
            count(records, "1 record", "records"),
            count(schedules, "1 schedule", "schedules"),
            count(documents, "1 document or photo", "documents and photos"),
            count(cases, "1 service case", "service cases"),
        )

    private fun count(n: Int, one: String, many: String): String? = when (n) {
        0 -> null
        1 -> one
        else -> "$n $many"
    }
}
