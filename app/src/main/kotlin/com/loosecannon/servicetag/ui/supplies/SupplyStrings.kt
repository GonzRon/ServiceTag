package com.loosecannon.servicetag.ui.supplies

import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.l10n.localized

/*
 * #15 (§5, ratified 2026-10-01) — the Supplies words the phone draws, one home per literal, imported and never
 * copied. B4a declares the first, P15-20; B7 and B8 add theirs beside it.
 *
 * #102: each word's text lives in `res/values/strings_supplies_reminders.xml` and is read when it is drawn, in the
 * owner's language; the names here are the call sites' one home for it.
 */

/**
 * P15-20 — a row whose SupplyItem is no longer there: the role sheet and the SupplyItem editor (C31, C33), and the
 * quick-action editor's line refused as `UnknownSupplyItem` (C20, C-2: the same meaning, reused verbatim).
 */
val SUPPLY_ITEM_GONE: String get() = localized(R.string.supplies_item_gone)

/**
 * P15-1 — the Maintenance shell's fifth row (C29), the Supplies list's title (C30), and the asset detail's section
 * header (C33, B8).
 */
val SUPPLIES_SECTION: String get() = localized(R.string.supplies_section)

/** P15-2 — the Supplies list's add button (C30) and the editor's title (C31; the `MAINTENANCE_GROUP` precedent). */
val SUPPLY_ITEM: String get() = localized(R.string.supplies_supply_item)

/** P15-3 — the Supplies list, empty (C30). */
val NO_SUPPLIES_YET: String get() = localized(R.string.supplies_no_supplies_yet)

/** P15-4 — the editor's field and the detail's fact (C30, C31): the one label allowed that word (R15-2). */
val PART_NUMBER_FIELD: String get() = localized(R.string.supplies_part_number_field)

/** P15-5 — the editor's field and the detail's fact (C30, C31). */
val PREFERRED_UNIT_FIELD: String get() = localized(R.string.supplies_preferred_unit_field)

/** P15-6 — the detail's and the editor's section header (C30, C31). */
val SPECIFICATIONS_SECTION: String get() = localized(R.string.supplies_specifications_section)

/** P15-9 — the detail, no specification (C30). */
val NO_SPECIFICATIONS: String get() = localized(R.string.supplies_no_specifications)

/** P15-10 — the detail's applicability section header (C30). */
val USED_BY_SECTION: String get() = localized(R.string.supplies_used_by_section)

/** P15-11 — the detail, no applicability row (C30). */
val NOT_USED_BY_ANY_ASSET: String get() = localized(R.string.supplies_not_used_by_any_asset)

/** P15-7 — the editor's row button under the specification rows (C31; the "Add material" precedent). */
val ADD_SPECIFICATION: String get() = localized(R.string.supplies_add_specification)

/** P15-8 — a specification row's close glyph, its accessibility label (C31; the "Remove material" precedent). */
val REMOVE_SPECIFICATION: String get() = localized(R.string.supplies_remove_specification)

/** P15-12 — the editor, under the specification rows, after a save refused a row's label or value (C31). */
val SPECIFICATION_NEEDS_LABEL_AND_VALUE: String get() = localized(R.string.supplies_specification_needs_label_and_value)

/** P15-13 — the asset section's add glyph, its accessibility label, and the add sheet's title (C33). */
val ADD_SUPPLY: String get() = localized(R.string.supplies_add_supply)

/** P15-14 — the asset section, empty (C33; the shape of the child-asset section's empty line). */
val NO_SUPPLIES: String get() = localized(R.string.supplies_no_supplies)

/** P15-15 — the SupplyItem picker's title (C32). */
val CHOOSE_A_SUPPLY: String get() = localized(R.string.supplies_choose_a_supply)

/** P15-16 — the picker, when no unarchived SupplyItem exists: it points to the catalog rather than adding one (C32). */
val NO_SUPPLY_ITEMS_YET: String get() = localized(R.string.supplies_no_supply_items_yet)

/** P15-17 — an asset row's overflow item and the role sheet's title when it re-roles a row (C33). */
val EDIT_ROLE: String get() = localized(R.string.supplies_edit_role)

/** P15-18 — the role sheet, under the field, when the use case answers `Taken` (C33). */
val SUPPLY_ROLE_TAKEN: String get() = localized(R.string.supplies_role_taken)

/** P15-21 — an unlinked Materials row on the quick-action editor: its link control, which opens the picker (C34). */
val LINK_SUPPLY: String get() = localized(R.string.supplies_link_supply)

/**
 * P15-22 — a linked Materials row on either editor (C34, C35), and a component's direct link (#47); [name] is the
 * SupplyItem's name, filled where the line is drawn (`SupplyLinkLine`, the component sheet and its quiet line).
 */
fun linkedTo(name: String): String = localized(R.string.supplies_linked_to, name)

/** P15-23 — a linked row's action on either editor: it clears the row's link and nothing else (C34, C35). */
val REMOVE_LINK: String get() = localized(R.string.supplies_remove_link)
