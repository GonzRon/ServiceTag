package com.loosecannon.servicetag.ui.transfer

import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.model.shortPackId
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.l10n.localizedDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * #77 (B4; plan §6, R77-24) — **every word the sender's phone draws for a transfer, verbatim from the ratified table**,
 * one home per literal and each literal on one line. Nothing is paraphrased or composed from fragments beyond the
 * placeholders the table names. P77-6…11 (the count lines) and P77-35 live in B3's
 * [com.loosecannon.servicetag.ui.transfer.import.TransferImportStrings] and are reused from there; the eight reused
 * strings stay in their shipped homes.
 *
 * P77-69 (amended) and P77-70 were ratified by the owner on 2026-09-29, after the B4 hand-offs proposed them.
 *
 * #102: the words live in `res/values/strings_transfer.xml` (`transfer_*`, each with its P77 id); every entry here is a
 * getter or a function, so it is read in the current language when it is drawn. The pack's file name (P77-54) is not
 * words and stays here, language-neutral.
 */
internal object TransferStrings {
    /** P77-1 — the Assets overflow, the detail overflow, the selection's top bar. */
    val TRANSFER_ASSETS: String get() = localized(R.string.transfer_assets)

    /** P77-2 — the selection's heading. */
    val SELECT_WHAT_LEAVES: String get() = localized(R.string.transfer_select_what_leaves)

    /** P77-3 — the selection's QuietLine. */
    val COMPONENTS_GO_WITH: String get() = localized(R.string.transfer_components_go_with)

    /** P77-4 — the selection's button. */
    val REVIEW: String get() = localized(R.string.transfer_review)

    /** P77-5 — the review's SectionHeader. */
    val TRANSFER_PACK: String get() = localized(R.string.transfer_pack)

    /** P77-12 — the review's body line. */
    val MAY_CONTAIN: String get() = localized(R.string.transfer_may_contain)

    /** P77-13 — the note field's label. */
    val NOTE_LABEL: String get() = localized(R.string.transfer_note_label)

    /** P77-14 — the review's button. */
    val CREATE: String get() = localized(R.string.transfer_create)

    /** P77-15 — a group naming an asset that stays. */
    fun mixedGroup(group: String, staying: String): String = localized(R.string.transfer_mixed_group, group, staying)

    /** P77-16 — a component without its parent. */
    fun parentNotSelected(child: String, parent: String): String = localized(R.string.transfer_parent_not_selected, child, parent)

    /** P77-17 — an open loan (also the marking refusal). */
    fun lentOut(asset: String): String = localized(R.string.transfer_lent_out, asset)

    /** P77-18 — a record naming something outside the pack. */
    fun outsideReference(asset: String): String = localized(R.string.transfer_outside_reference, asset)

    /** P77-19 — creation's progress. */
    val CREATING: String get() = localized(R.string.transfer_creating)

    /** P77-20 — creation failed; [reason] is the shipped wording for why (a missing document's). */
    fun notCreated(reason: String): String = localized(R.string.transfer_not_created, reason)

    /** P77-20's entangled reason (R77-CREATE-SAFETY): the mark would leave records here pointing into a held graph. */
    val NOT_CREATED_ENTANGLED: String get() = localized(R.string.transfer_not_created_entangled)

    /** P77-20's generic reason: any other failure while the pack was being made. */
    val NOT_CREATED: String get() = localized(R.string.transfer_not_created_generic)

    /** P77-21 — the ready screen's heading. */
    val READY: String get() = localized(R.string.transfer_ready)

    /** P77-22. */
    val SHARE: String get() = localized(R.string.transfer_share)

    /** P77-23. */
    val SAVE_A_COPY: String get() = localized(R.string.transfer_save_a_copy)

    /** P77-24 — [size] is the shipped B / KB / MB helper's answer. */
    fun size(size: String): String = localized(R.string.transfer_size, size)

    /** P77-25 — the chooser's title. */
    val SHARE_TITLE: String get() = localized(R.string.transfer_share_title)

    /** P77-26 — the copy was saved. */
    val SAVED: String get() = localized(R.string.transfer_saved)

    /** P77-27 — the mark question. */
    val MARK_QUESTION: String get() = localized(R.string.transfer_mark_question)

    /** P77-28. */
    val MARK: String get() = localized(R.string.transfer_mark)

    /** P77-30 — under the mark question. */
    val MARK_CONSEQUENCE: String get() = localized(R.string.transfer_mark_consequence)

    /** P77-31 — the plate and list badge (drawn upper-case by `StatusBadge`, read as written). */
    val TRANSFERRED: String get() = localized(R.string.transfer_transferred)

    /** P77-32 — the detail block's SectionHeader. */
    val TRANSFERRED_OUT: String get() = localized(R.string.transfer_transferred_out)

    /** P77-33 — [date] as `d MMM uuuu` ([day]). */
    fun transferredOn(date: String): String = localized(R.string.transfer_transferred_on, date)

    /** P77-34 — [short] is the pack id's first eight characters. */
    fun packLine(short: String): String = localized(R.string.transfer_pack_line, short)

    /** P77-36 — the scan sheet's title. */
    val SCAN_TITLE: String get() = localized(R.string.transfer_scan_title)

    /** P77-37 — the scan sheet's body. */
    fun handedOver(asset: String, date: String): String = localized(R.string.transfer_handed_over, asset, date)

    /** P77-51 — marking refused: the pack is stale (R77-16). */
    fun changedSince(asset: String): String = localized(R.string.transfer_changed_since, asset)

    /** P77-53 — marking failed. */
    val COULD_NOT_MARK: String get() = localized(R.string.transfer_could_not_mark)

    /**
     * P77-54 — the pack's file name, for the chooser and the saved copy. #102: a file other devices and tools read, so
     * it stays language-neutral here and never comes from the resources.
     */
    fun fileName(date: String, short: String): String = "servicetag-transfer-$date-$short.zip"

    /** P77-55. */
    val COULD_NOT_SAVE: String get() = localized(R.string.transfer_could_not_save)

    /** P77-56 — nothing to offer. */
    val NOTHING_TO_OFFER: String get() = localized(R.string.transfer_nothing_to_offer)

    /** P77-57 — already held. */
    val ALREADY_TRANSFERRED: String get() = localized(R.string.transfer_already_transferred)

    /** P77-59 — over the pack's data cap. */
    val TOO_LARGE: String get() = localized(R.string.transfer_too_large)

    /** P77-60 — a restored ready screen whose file is gone (R77-18). */
    val PACK_GONE: String get() = localized(R.string.transfer_pack_gone)

    /** P77-61 — a forced component's state description (accessibility only). */
    fun includedWith(parent: String): String = localized(R.string.transfer_included_with, parent)

    /** P77-62 — the detail block's TextButton. */
    val WITHDRAW_RECORD: String get() = localized(R.string.transfer_withdraw_record)

    /** P77-63 — the withdrawal dialog's title (rm-8: the short id the file name shows). */
    fun withdrawTitle(short: String): String = localized(R.string.transfer_withdraw_title, short)

    /** P77-64 (amended, R77-WITHDRAW) — the withdrawal dialog's body: the withdrawal is the whole pack's. */
    val WITHDRAW_BODY: String get() = localized(R.string.transfer_withdraw_body)

    /** P77-65 — the withdrawal dialog's confirm. */
    val WITHDRAW: String get() = localized(R.string.transfer_withdraw)

    /** P77-71 — the withdrawal failed. */
    val COULD_NOT_WITHDRAW: String get() = localized(R.string.transfer_could_not_withdraw)

    /** P77-72 — the withdrawal refused: the estate it would leave is entangled (R77-WITHDRAW). */
    val WITHDRAW_ENTANGLED: String get() = localized(R.string.transfer_withdraw_entangled)

    /** P77-69 (R77-25; ratified as amended) — the ready screen's QuietLine under P77-24: the pack is a bearer file. */
    val BEARER: String get() = localized(R.string.transfer_bearer)

    /** P77-70 (R77-B2a-MARK; ratified) — marking refused: the estate left behind would be entangled. */
    val MARK_ENTANGLED: String get() = localized(R.string.transfer_mark_entangled)

    private val FILE_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)

    /** P77-33's and P77-37's `<date>`: the display date in [zone] (English `d MMM uuuu`), through [localizedDate]. */
    fun day(at: Long, zone: ZoneId): String = localizedDate(Instant.ofEpochMilli(at).atZone(zone).toLocalDate())

    /**
     * P77-54 for a pack created at [createdAt] in [zone], named by its id's first eight characters.
     * The day is taken the way [day] takes it: `LocalDate.ofInstant` is API 34, and minSdk is 26.
     */
    fun packFileName(createdAt: Long, zone: ZoneId, packId: String): String =
        fileName(FILE_DAY.format(Instant.ofEpochMilli(createdAt).atZone(zone).toLocalDate()), shortPackId(packId))
}
