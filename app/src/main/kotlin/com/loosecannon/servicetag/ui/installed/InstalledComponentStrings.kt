package com.loosecannon.servicetag.ui.installed

import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.ui.replace.ReplaceStrings

/*
 * #47 (§5, ratified 2026-10-01) — the Installed components words the phone draws, one home per literal, imported and
 * never copied. Every new string of the ratified block is declared here once: the section draws some, and the sheets
 * and the composition editor draw the rest. A format is a one-line function, so its argument is named where it is
 * filled.
 * The reused words (§5's list) stay at their own homes and are imported from there.
 *
 * #102: each word's text lives in `res/values/strings_supplies_reminders.xml` and is read when it is drawn, in the
 * owner's language; a format's arguments — names, days, counts — are filled in by position.
 */

/** P47-1 — the asset detail's section header (C27). */
val INSTALLED_COMPONENTS_SECTION: String get() = localized(R.string.installed_components_section)

/** P47-2 — the section, empty (C25; the "No supplies" shape). */
val NO_INSTALLED_COMPONENTS: String get() = localized(R.string.installed_no_installed_components)

/** P47-3 — the header's add glyph, its accessibility label, and the install sheet's title (C26, C27). */
val INSTALL_COMPONENT: String get() = localized(R.string.installed_install_component)

/** P47-4 — a current row's sheet action: install a component inside this one (C26). */
val INSTALL_INSIDE: String get() = localized(R.string.installed_install_inside)

/**
 * P47-5 — "Inside %s", [parent] the parent row's name: the install sheet's quiet line (C26), a removed row whose parent
 * is not current, and a nested row's content description, since TalkBack cannot hear an indent (C25).
 */
fun insideOf(parent: String): String = localized(R.string.installed_inside_of, parent)

/** P47-6 — the sheet field and the row fact (C26; ratified over the shipped "Serial number"). */
val SERIAL_OR_LOT: String get() = localized(R.string.installed_serial_or_lot)

/** P47-7 — the date field and the fact; on replace, the replacement date (C26). */
val INSTALLED_ON: String get() = localized(R.string.installed_installed_on)

/** P47-8 — the remove sheet's date field and the fact (C26). */
val REMOVED_ON: String get() = localized(R.string.installed_removed_on)

/** P47-9 — a current row's sheet action and the replace sheet's button (C26). */
val REPLACE_COMPONENT: String get() = localized(R.string.installed_replace)

/** P47-10 — "Replace %s", [name] the row's name: the replace sheet's title (C26). */
fun replaceTitle(name: String): String = localized(R.string.installed_replace_title, name)

/** P47-11 — "Remove %s", [name] the row's name: the remove sheet's title (C26). */
fun removeTitle(name: String): String = localized(R.string.installed_remove_title, name)

/** P47-12 — the remove and replace sheets, when the row has current children (C26; R47-6). */
val SUBTREE_REMOVED_TOO: String get() = localized(R.string.installed_subtree_removed_too)

/** P47-13 — the edit sheet's title (C26). */
val EDIT_COMPONENT: String get() = localized(R.string.installed_edit_component)

/** P47-14 — the row sheet's section (C26). */
val COMPONENT_HISTORY: String get() = localized(R.string.installed_history)

/** P47-15 — "Installed %s", [iso] the install day drawn by `ReplaceStrings.day`: the quiet line and history (C25, C26). */
fun installedOnDay(iso: String): String = localized(R.string.installed_installed_on_day, ReplaceStrings.day(iso))

/** P47-16 — the fact and history when the install date is not recorded (C26; R47-8). */
val INSTALL_DATE_NOT_RECORDED: String get() = localized(R.string.installed_install_date_not_recorded)

/** P47-17 — "Removed %s", [iso] the removal day drawn by `ReplaceStrings.day`: the removed rows and history (C25, C26). */
fun removedOnDay(iso: String): String = localized(R.string.installed_removed_on_day, ReplaceStrings.day(iso))

/** P47-18 — "Removed (%d)", [count] the removed rows nothing replaced: the toggle under the tree (C25). */
fun removedCount(count: Int): String = localized(R.string.installed_removed_count, count)

/** P47-19 — any sheet, when its row or parent is gone or closed (C26). */
val COMPONENT_CHANGED: String get() = localized(R.string.installed_component_changed)

/** P47-20 — under the date field, when a closing date is before an install date (C26; `LoanWords.kt`'s shape). */
val REMOVAL_BEFORE_INSTALL: String get() = localized(R.string.installed_removal_before_install)

/**
 * P47-21 — "%1$s × %2$s": [amount] the quantity with its unit ("4", "2 L"), then [name] the SupplyItem's name — one
 * composition entry, on the quiet line and the composition's lines (C25, C26).
 */
fun compositionLine(amount: String, name: String): String = localized(R.string.installed_composition_line, amount, name)

/** P47-22 — "+%d more", [count] the entries past the first two: the quiet line (C25). */
fun moreEntries(count: Int): String = localized(R.string.installed_more_entries, count)

/** P47-23 — the row sheet's and the editor's section (C26). */
val COMPOSITION_SECTION: String get() = localized(R.string.installed_composition_section)

/** P47-24 — an entry row's close glyph, its accessibility label (C26). */
val REMOVE_FROM_COMPOSITION: String get() = localized(R.string.installed_remove_from_composition)

/** P47-25 — under the composition rows after a refused save (C26; P15-12's shape). */
val COMPOSITION_QUANTITY_REQUIRED: String get() = localized(R.string.installed_composition_quantity_required)

/*
 * #69 (§5, ratified 2026-10-02) — the installed-component screen's words (C27). The sections' own words stay at their
 * homes (`DocumentsSection.kt`, `ReferencesSection.kt`) and "Back" at the top bars'.
 */

/** P69-1 — the row sheet's action opening the component's own screen, on every row (C27). */
val DOCUMENTS_AND_REFERENCES: String get() = localized(R.string.installed_documents_and_references)

/** P69-2 — the heading over the component's own Documents and References (C27). */
val THIS_INSTALLED_COMPONENT: String get() = localized(R.string.installed_this_installed_component)

/** P69-3 — "From %s", [name] the SupplyItem's name: each open-only group's heading; a tap opens it (C27). */
fun fromSupply(name: String): String = localized(R.string.installed_from_supply, name)

/** P69-4 — the quiet line under each group's heading (C27). */
val OPEN_THE_SUPPLY_TO_CHANGE: String get() = localized(R.string.installed_open_the_supply_to_change)
