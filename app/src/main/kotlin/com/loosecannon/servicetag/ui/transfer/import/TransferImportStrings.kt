package com.loosecannon.servicetag.ui.transfer.`import`

import androidx.annotation.PluralsRes
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.l10n.localizedPlural

/**
 * #77 (B3; plan §6, R77-24) — **every word the Transfer Pack import draws, verbatim from the ratified table**, one home
 * per literal. Nothing is paraphrased or composed from fragments beyond the placeholders the table names.
 *
 * [ASSET_TRANSFERRED_OUT] (P77-35) lands here for the share intake's refused write; B4's screens reuse it from here.
 * [countLines] are P77-6…11, which the review screen (B4) and P77-50 share.
 *
 * #102: the words live in `res/values/strings_transfer.xml` (`transfer_import_*` and the `transfer_count_*` plurals,
 * each with its P77 id); every entry here is a getter or a function, so it is read in the current language when drawn.
 */
internal object TransferImportStrings {
    /** P77-35 — a write refused because the asset was transferred out. */
    val ASSET_TRANSFERRED_OUT: String get() = localized(R.string.transfer_import_asset_transferred_out)

    /** P77-38 — the Backup screen's button and the import screen's title. */
    val TITLE: String get() = localized(R.string.transfer_import_title)

    /** P77-39. */
    fun note(note: String): String = localized(R.string.transfer_import_note, note)

    /** P77-40. */
    fun created(date: String): String = localized(R.string.transfer_import_created, date)

    /** P77-41. */
    val CONTAINS: String get() = localized(R.string.transfer_import_contains)

    /** P77-42. */
    val IMPORT: String get() = localized(R.string.transfer_import_import)

    /** P77-43. */
    val ALREADY_HERE: String get() = localized(R.string.transfer_import_already_here)

    /** P77-44. */
    val CONFLICTS: String get() = localized(R.string.transfer_import_conflicts)

    /** P77-45. */
    fun tagUsedHere(asset: String): String = localized(R.string.transfer_import_tag_used_here, asset)

    /** P77-46. */
    fun duplicate(incoming: String, local: String): String = localized(R.string.transfer_import_duplicate, incoming, local)

    /** P77-47. */
    val NOT_A_PACK: String get() = localized(R.string.transfer_import_not_a_pack)

    /** P77-48. */
    val NEWER: String get() = localized(R.string.transfer_import_newer)

    /** P77-49. */
    val DAMAGED: String get() = localized(R.string.transfer_import_damaged)

    /** P77-50 — anchored apart from the shipped `Imported: …`. */
    fun imported(counts: String): String = localized(R.string.transfer_import_imported, counts)

    /** P77-52. */
    val COULD_NOT_IMPORT: String get() = localized(R.string.transfer_import_could_not_import)

    /** P77-66 (R77-25). */
    fun comingBack(asset: String): String = localized(R.string.transfer_import_coming_back, asset)

    /** P77-67. */
    fun notBroughtBack(asset: String): String = localized(R.string.transfer_import_not_brought_back, asset)

    /**
     * P77-6…11, in the table's order: `%d assets` · `%d NFC tags` · `%d records` · `%d schedules` ·
     * `%d documents and photos` · `%d service cases`, each a plural (`1 …` its singular in English); a zero line is
     * hidden, except the assets'.
     */
    fun countLines(assets: Int, tags: Int, records: Int, schedules: Int, documents: Int, cases: Int): List<String> =
        listOfNotNull(
            localizedPlural(R.plurals.transfer_count_assets, assets, assets),
            count(R.plurals.transfer_count_nfc_tags, tags),
            count(R.plurals.transfer_count_records, records),
            count(R.plurals.transfer_count_schedules, schedules),
            count(R.plurals.transfer_count_documents, documents),
            count(R.plurals.transfer_count_service_cases, cases),
        )

    private fun count(@PluralsRes line: Int, n: Int): String? = if (n == 0) null else localizedPlural(line, n, n)
}
