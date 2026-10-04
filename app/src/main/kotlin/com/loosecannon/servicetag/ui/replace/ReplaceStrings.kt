package com.loosecannon.servicetag.ui.replace

import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.l10n.localizedDate
import com.loosecannon.servicetag.l10n.localizedList
import com.loosecannon.servicetag.l10n.localizedPlural
import java.time.LocalDate

/**
 * #86 (C18; plan §6, R86-23 RATIFIED) — **every word Replace asset adds, verbatim from the ratified table**, one home
 * per literal and each literal on a line of its own. The detail menu (P86-1) and the detail lines (P86-26, P86-27)
 * reference it from here. The 24 reused strings stay in their shipped homes and are never quoted in this package.
 *
 * #102: the words live in `res/values/strings_transfer.xml` (`replace_*`, each with its P86 id); every entry here is a
 * getter or a function, so it is read in the current language when it is drawn.
 *
 * `<old>` and `<new>` are asset names; every `<date>` is an ISO day, drawn as the display date through [localizedDate]
 * (P77-33's precedent; English `d MMM uuuu`).
 */
internal object ReplaceStrings {
    /** P86-1 — the detail overflow, the Replace top bar, and the review's confirm. */
    val REPLACE_ASSET: String get() = localized(R.string.replace_asset)

    /** P86-2 — the form's SectionHeader. */
    val OLD_ASSET: String get() = localized(R.string.replace_old_asset)

    /** P86-3 — the form, not yet retired: the body above the reused `Retired on` field. */
    fun willBeRetired(old: String): String = localized(R.string.replace_will_be_retired, old)

    /** P86-4 — the form, already retired: the body, with no field. */
    fun wasRetiredOn(old: String, on: String): String = localized(R.string.replace_was_retired_on, old, day(on))

    /** P86-5 — the form's SectionHeader; the one home of these words, read by the editor's create title too. */
    val NEW_ASSET: String get() = localized(R.string.replace_new_asset)

    /** P86-6 — the form's QuietLine under P86-5. */
    val SEPARATE_ASSET: String get() = localized(R.string.replace_separate_asset)

    /** P86-7 — the form's SectionHeader; the review's FieldLabel. */
    val CARRY_FORWARD: String get() = localized(R.string.replace_carry_forward)

    /** P86-8 — the form's QuietLine under P86-7. */
    val ONLY_WHAT_YOU_TICK: String get() = localized(R.string.replace_only_what_you_tick)

    /** P86-9 (R86-13) — the season checkbox; a review line. */
    val SEASON_AND_BREAK: String get() = localized(R.string.replace_season_and_break)

    /** P86-10 — the notes checkbox; a review line. */
    val DESCRIPTION_AND_NOTES: String get() = localized(R.string.replace_description_and_notes)

    /** P86-11 (R86-12) — one checkbox per group; a review line. */
    fun addTo(group: String): String = localized(R.string.replace_add_to, group)

    /** P86-12 — the date field's label, shown iff a ticked schedule has a time rule. */
    val SCHEDULES_START_ON: String get() = localized(R.string.replace_schedules_start_on)

    /** P86-13 — the supporting line under a schedule that needs `Readings & actions`. */
    val NEEDS_SETUP: String get() = localized(R.string.replace_needs_setup)

    /** P86-14 (amended) — the supporting line under a PRE_SERVICE schedule that needs the season. */
    val NEEDS_SEASON: String get() = localized(R.string.replace_needs_season)

    /** P86-15 — a tag's radio; the review's FieldLabel over the moved tags. */
    val MOVE_TO_NEW: String get() = localized(R.string.replace_move_to_new)

    /** P86-16 — a tag's radio, the default. */
    val LEAVE_WITH_OLD: String get() = localized(R.string.replace_leave_with_old)

    /** P86-17 — the QuietLine under the tags. */
    val MOVED_TAG_NOT_REWRITTEN: String get() = localized(R.string.replace_moved_tag_not_rewritten)

    /**
     * P86-18 (MN-8) — the children named, never moved: one plural over the number of children (the one-child form or
     * the several-children form), the names sorted and joined as the language's list; null when there is none.
     */
    fun childrenStay(names: List<String>, old: String): String? =
        if (names.isEmpty()) {
            null
        } else {
            val sorted = names.sortedWith(compareBy({ it.lowercase() }, { it }))
            localizedPlural(R.plurals.replace_children_stay, names.size, localizedList(sorted), old)
        }

    /** P86-19 (R86-6) — an open loan, named, never moved. */
    fun lentOut(old: String): String = localized(R.string.replace_lent_out, old)

    /** P86-20 — the review's retirement line. */
    fun retireOn(old: String, on: String): String = localized(R.string.replace_retire_on, old, day(on))

    /** P86-21 — the review's line for an asset already retired. */
    fun staysRetiredFrom(old: String, on: String): String = localized(R.string.replace_stays_retired_from, old, day(on))

    /** P86-22 — the review's creation line. */
    fun create(new: String): String = localized(R.string.replace_create, new)

    /** P86-23 — the review's line under P86-7 when nothing is ticked. */
    val NOTHING_CARRIED: String get() = localized(R.string.replace_nothing_carried)

    /** P86-24 — any failure but the two named refusals. */
    fun couldNotReplace(old: String): String = localized(R.string.replace_could_not_replace, old)

    /** P86-25 (amended) — the stale refusal; the form re-reads. */
    val CHANGED_WHILE_REVIEWING: String get() = localized(R.string.replace_changed_while_reviewing)

    /** P86-26 — the old asset's detail line, tappable. */
    fun replacedBy(new: String, on: String): String = localized(R.string.replace_replaced_by, new, day(on))

    /** P86-27 — the new asset's detail line, tappable. */
    fun replaces(old: String): String = localized(R.string.replace_replaces, old)

    /** P86-28 (R86-13 amended) — above the S36/S37 options on a MANUAL old asset, with no default; [on] is the replacement date. */
    fun wasInSeasonOn(on: String): String = localized(R.string.replace_was_in_season_on, day(on))

    /** Every `<date>`: an ISO day as the display date (English `d MMM uuuu`); a value that is not one is shown as stored. */
    fun day(iso: String): String = try {
        localizedDate(LocalDate.parse(iso))
    } catch (e: java.time.format.DateTimeParseException) {
        iso
    }
}
