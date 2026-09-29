package com.loosecannon.servicetag.ui.transfer

import com.loosecannon.servicetag.core.model.shortPackId
import com.loosecannon.servicetag.ui.condition.displayDate
import java.time.Instant
import java.time.LocalDate
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
 * P77-69 and P77-70 are the controller's PROPOSED strings (the B4 hand-offs, items 3 and 4), used as written until the
 * owner rules; the controller patches the literal if amended.
 */
internal object TransferStrings {
    /** P77-1 — the Assets overflow, the detail overflow, the selection's top bar. */
    const val TRANSFER_ASSETS = "Transfer assets"

    /** P77-2 — the selection's heading. */
    const val SELECT_WHAT_LEAVES = "Select what is leaving this ServiceTag"

    /** P77-3 — the selection's QuietLine. */
    const val COMPONENTS_GO_WITH = "Components go with the asset they belong to."

    /** P77-4 — the selection's button. */
    const val REVIEW = "Review"

    /** P77-5 — the review's SectionHeader. */
    const val TRANSFER_PACK = "Transfer Pack"

    /** P77-12 — the review's body line. */
    const val MAY_CONTAIN = "These records may contain serial numbers, locations, purchase information, receipts and service history."

    /** P77-13 — the note field's label. */
    const val NOTE_LABEL = "Note for the new owner (optional)"

    /** P77-14 — the review's button. */
    const val CREATE = "Create Transfer Pack"

    /** P77-15 — a group naming an asset that stays. */
    fun mixedGroup(group: String, staying: String): String = "$group also covers $staying, which stay here. A group transfers only with every asset it has ever covered."

    /** P77-16 — a component without its parent. */
    fun parentNotSelected(child: String, parent: String): String = "$child is a component of $parent. Select $parent too."

    /** P77-17 — an open loan (also the marking refusal). */
    fun lentOut(asset: String): String = "$asset is lent out. Mark it returned first."

    /** P77-18 — a record naming something outside the pack. */
    fun outsideReference(asset: String): String = "$asset has a record that points outside this transfer."

    /** P77-19 — creation's progress. */
    const val CREATING = "Creating Transfer Pack…"

    /** P77-20 — creation failed; [reason] is the shipped wording for why (a missing document's). */
    fun notCreated(reason: String): String = "Transfer Pack not created: $reason. Nothing was changed."

    /** P77-20's entangled reason (R77-CREATE-SAFETY): the mark would leave records here pointing into a held graph. */
    const val NOT_CREATED_ENTANGLED = "Transfer Pack not created: records on this phone still point to a transferred asset. Nothing was changed."

    /** P77-20's generic reason: any other failure while the pack was being made. */
    const val NOT_CREATED = "Transfer Pack not created: the file could not be written. Nothing was changed."

    /** P77-21 — the ready screen's heading. */
    const val READY = "Transfer Pack ready"

    /** P77-22. */
    const val SHARE = "Share"

    /** P77-23. */
    const val SAVE_A_COPY = "Save a copy"

    /** P77-24 — [size] is the shipped B / KB / MB helper's answer. */
    fun size(size: String): String = "Size: $size"

    /** P77-25 — the chooser's title. */
    const val SHARE_TITLE = "Share Transfer Pack"

    /** P77-26 — the copy was saved. */
    const val SAVED = "Saved"

    /** P77-27 — the mark question. */
    const val MARK_QUESTION = "Mark these assets transferred out on this phone?"

    /** P77-28. */
    const val MARK = "Mark transferred"

    /** P77-30 — under the mark question. */
    const val MARK_CONSEQUENCE = "Their reminders stop and they leave your lists. You can still open them under Archived."

    /** P77-31 — the plate and list badge (drawn upper-case by `StatusBadge`, read as written). */
    const val TRANSFERRED = "Transferred"

    /** P77-32 — the detail block's SectionHeader. */
    const val TRANSFERRED_OUT = "Transferred out"

    /** P77-33 — [date] as `d MMM uuuu` ([day]). */
    fun transferredOn(date: String): String = "Transferred on $date"

    /** P77-34 — [short] is the pack id's first eight characters. */
    fun packLine(short: String): String = "Transfer Pack $short"

    /** P77-36 — the scan sheet's title. */
    const val SCAN_TITLE = "Asset transferred out"

    /** P77-37 — the scan sheet's body. */
    fun handedOver(asset: String, date: String): String = "$asset was handed over on $date. This phone no longer maintains it."

    /** P77-51 — marking refused: the pack is stale (R77-16). */
    fun changedSince(asset: String): String = "$asset changed after this Transfer Pack was made. Create it again."

    /** P77-53 — marking failed. */
    const val COULD_NOT_MARK = "Could not mark these assets. Nothing was changed."

    /** P77-54 — the pack's file name, for the chooser and the saved copy. */
    fun fileName(date: String, short: String): String = "servicetag-transfer-$date-$short.zip"

    /** P77-55. */
    const val COULD_NOT_SAVE = "Could not save a copy."

    /** P77-56 — nothing to offer. */
    const val NOTHING_TO_OFFER = "No assets can be transferred."

    /** P77-57 — already held. */
    const val ALREADY_TRANSFERRED = "These assets are already marked transferred out."

    /** P77-59 — over the pack's data cap. */
    const val TOO_LARGE = "These assets have too many records for one Transfer Pack. Transfer fewer at a time."

    /** P77-60 — a restored ready screen whose file is gone (R77-18). */
    const val PACK_GONE = "This Transfer Pack is no longer on this phone. Create it again."

    /** P77-61 — a forced component's state description (accessibility only). */
    fun includedWith(parent: String): String = "Included with $parent"

    /** P77-62 — the detail block's TextButton. */
    const val WITHDRAW_RECORD = "Withdraw transfer record"

    /** P77-63 — the withdrawal dialog's title (rm-8: the short id the file name shows). */
    fun withdrawTitle(short: String): String = "Withdraw the record for Transfer Pack $short?"

    /** P77-64 (amended, R77-WITHDRAW) — the withdrawal dialog's body: the withdrawal is the whole pack's. */
    const val WITHDRAW_BODY = "Use this only if this Transfer Pack did not leave, or another phone's record should stand. Its assets stay archived."

    /** P77-65 — the withdrawal dialog's confirm. */
    const val WITHDRAW = "Withdraw"

    /** P77-71 — the withdrawal failed. */
    const val COULD_NOT_WITHDRAW = "Could not withdraw this record. Nothing was changed."

    /** P77-72 — the withdrawal refused: the estate it would leave is entangled (R77-WITHDRAW). */
    const val WITHDRAW_ENTANGLED = "Could not withdraw this record: records on this phone would still point to a transferred asset. Nothing was changed."

    /** P77-69 (PROPOSED, hand-off 4; R77-25) — the ready screen's QuietLine under P77-24. */
    const val BEARER = "Anyone with this file can import these assets. Share it only with the phone that should have them."

    /** P77-70 (PROPOSED, hand-off 3) — marking refused: the estate left behind would be entangled. */
    const val MARK_ENTANGLED = "Could not mark these assets: records on this phone still point to a transferred asset. Nothing was changed."

    private val FILE_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)

    /** P77-33's and P77-37's `<date>`: `d MMM uuuu` in [zone], through the shipped [displayDate]. */
    fun day(at: Long, zone: ZoneId): String = displayDate(Instant.ofEpochMilli(at).atZone(zone).toLocalDate())

    /** P77-54 for a pack created at [createdAt] in [zone], named by its id's first eight characters. */
    fun packFileName(createdAt: Long, zone: ZoneId, packId: String): String =
        fileName(FILE_DAY.format(LocalDate.ofInstant(Instant.ofEpochMilli(createdAt), zone)), shortPackId(packId))
}
