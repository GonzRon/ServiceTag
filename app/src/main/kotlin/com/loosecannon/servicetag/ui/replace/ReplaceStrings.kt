package com.loosecannon.servicetag.ui.replace

import com.loosecannon.servicetag.ui.condition.displayDate
import java.time.LocalDate

/**
 * #86 (C18; plan §6, R86-23 RATIFIED) — **every word Replace asset adds, verbatim from the ratified table**, one home
 * per literal and each literal on a line of its own. The detail menu (P86-1) and the detail lines (P86-26, P86-27)
 * reference it from here. The 24 reused strings stay in their shipped homes and are never quoted in this package.
 *
 * `<old>` and `<new>` are asset names; every `<date>` is an ISO day, drawn `d MMM uuuu` through the shipped
 * [displayDate] (P77-33's precedent).
 */
internal object ReplaceStrings {
    /** P86-1 — the detail overflow, the Replace top bar, and the review's confirm. */
    const val REPLACE_ASSET = "Replace asset"

    /** P86-2 — the form's SectionHeader. */
    const val OLD_ASSET = "Old asset"

    /** P86-3 — the form, not yet retired: the body above the reused `Retired on` field. */
    fun willBeRetired(old: String): String =
        "$old will be retired with its history, documents and service record kept as they are."

    /** P86-4 — the form, already retired: the body, with no field. */
    fun wasRetiredOn(old: String, on: String): String =
        "$old was retired on ${day(on)}. Its history, documents and service record are kept as they are."

    /** P86-5 — the form's SectionHeader; the one home of these words, read by the editor's create title too. */
    const val NEW_ASSET = "New asset"

    /** P86-6 — the form's QuietLine under P86-5. */
    const val SEPARATE_ASSET = "A separate asset with its own identity. None of the old asset's history is copied."

    /** P86-7 — the form's SectionHeader; the review's FieldLabel. */
    const val CARRY_FORWARD = "Carry forward"

    /** P86-8 — the form's QuietLine under P86-7. */
    const val ONLY_WHAT_YOU_TICK = "Only what you tick is copied, as new records with no history."

    /** P86-9 (R86-13) — the season checkbox; a review line. */
    const val SEASON_AND_BREAK = "Operating season and maintenance break"

    /** P86-10 — the notes checkbox; a review line. */
    const val DESCRIPTION_AND_NOTES = "Description and notes"

    /** P86-11 (R86-12) — one checkbox per group; a review line. */
    fun addTo(group: String): String = "Add to $group"

    /** P86-12 — the date field's label, shown iff a ticked schedule has a time rule. */
    const val SCHEDULES_START_ON = "Copied schedules start on"

    /** P86-13 — the supporting line under a schedule that needs `Readings & actions`. */
    const val NEEDS_SETUP = "Needs Readings & actions."

    /** P86-14 (amended) — the supporting line under a PRE_SERVICE schedule that needs the season. */
    const val NEEDS_SEASON = "Needs Operating season and maintenance break."

    /** P86-15 — a tag's radio; the review's FieldLabel over the moved tags. */
    const val MOVE_TO_NEW = "Move to the new asset"

    /** P86-16 — a tag's radio, the default. */
    const val LEAVE_WITH_OLD = "Leave with the old asset"

    /** P86-17 — the QuietLine under the tags. */
    const val MOVED_TAG_NOT_REWRITTEN = "A moved tag is not rewritten. Scanning it opens the new asset."

    /**
     * P86-18 (MN-8) — the children named, never moved: the one-child form or the several-children form, the names
     * sorted and joined with `, `; null when there is none.
     */
    fun childrenStay(names: List<String>, old: String): String? = when (names.size) {
        0 -> null
        1 -> oneChildStays(names.single(), old)
        else -> childrenStayPartOf(names.sortedWith(compareBy({ it.lowercase() }, { it })).joinToString(", "), old)
    }

    /** P86-18, one child. */
    private fun oneChildStays(name: String, old: String): String = "$name stays part of $old."

    /** P86-18, several children. */
    private fun childrenStayPartOf(names: String, old: String): String = "$names stay part of $old."

    /** P86-19 (R86-6) — an open loan, named, never moved. */
    fun lentOut(old: String): String = "$old is lent out. The loan stays with it."

    /** P86-20 — the review's retirement line. */
    fun retireOn(old: String, on: String): String = "Retire $old on ${day(on)}"

    /** P86-21 — the review's line for an asset already retired. */
    fun staysRetiredFrom(old: String, on: String): String = "$old stays retired from ${day(on)}"

    /** P86-22 — the review's creation line. */
    fun create(new: String): String = "Create $new"

    /** P86-23 — the review's line under P86-7 when nothing is ticked. */
    const val NOTHING_CARRIED = "Nothing carried forward"

    /** P86-24 — any failure but the two named refusals. */
    fun couldNotReplace(old: String): String = "Could not replace $old. Nothing was changed."

    /** P86-25 (amended) — the stale refusal; the form re-reads. */
    const val CHANGED_WHILE_REVIEWING = "The asset or related records changed while you were reviewing. Nothing was changed."

    /** P86-26 — the old asset's detail line, tappable. */
    fun replacedBy(new: String, on: String): String = "Replaced by $new on ${day(on)}"

    /** P86-27 — the new asset's detail line, tappable. */
    fun replaces(old: String): String = "Replaces $old"

    /** P86-28 (R86-13 amended) — above the S36/S37 options on a MANUAL old asset, with no default; [on] is the replacement date. */
    fun wasInSeasonOn(on: String): String = "Was the new asset in season on ${day(on)}?"

    /** Every `<date>`: an ISO day as `d MMM uuuu`; a value that is not one is shown as stored. */
    fun day(iso: String): String = try {
        displayDate(LocalDate.parse(iso))
    } catch (e: java.time.format.DateTimeParseException) {
        iso
    }
}
