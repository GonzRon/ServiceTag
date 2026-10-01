package com.loosecannon.servicetag.ui.supplies

/*
 * #15 (§5, ratified 2026-10-01) — the Supplies words the phone draws, one home per literal, imported and never
 * copied. B4a declares the first, P15-20; B7 and B8 add theirs beside it.
 */

/**
 * P15-20 — a row whose SupplyItem is no longer there: the role sheet and the SupplyItem editor (C31, C33), and the
 * quick-action editor's line refused as `UnknownSupplyItem` (C20, C-2: the same meaning, reused verbatim).
 */
const val SUPPLY_ITEM_GONE = "That supply item is no longer available."

/**
 * P15-1 — the Maintenance shell's fifth row (C29), the Supplies list's title (C30), and the asset detail's section
 * header (C33, B8).
 */
const val SUPPLIES_SECTION = "Supplies"

/** P15-2 — the Supplies list's add button (C30) and the editor's title (C31; the `MAINTENANCE_GROUP` precedent). */
const val SUPPLY_ITEM = "Supply item"

/** P15-3 — the Supplies list, empty (C30). */
const val NO_SUPPLIES_YET = "No supplies yet."

/** P15-4 — the editor's field and the detail's fact (C30, C31): the one label allowed that word (R15-2). */
const val PART_NUMBER_FIELD = "Part number"

/** P15-5 — the editor's field and the detail's fact (C30, C31). */
const val PREFERRED_UNIT_FIELD = "Preferred unit"

/** P15-6 — the detail's and the editor's section header (C30, C31). */
const val SPECIFICATIONS_SECTION = "Specifications"

/** P15-9 — the detail, no specification (C30). */
const val NO_SPECIFICATIONS = "No specifications"

/** P15-10 — the detail's applicability section header (C30). */
const val USED_BY_SECTION = "Used by"

/** P15-11 — the detail, no applicability row (C30). */
const val NOT_USED_BY_ANY_ASSET = "Not used by any asset"

/** P15-7 — the editor's row button under the specification rows (C31; the "Add material" precedent). */
const val ADD_SPECIFICATION = "Add specification"

/** P15-8 — a specification row's close glyph, its accessibility label (C31; the "Remove material" precedent). */
const val REMOVE_SPECIFICATION = "Remove specification"

/** P15-12 — the editor, under the specification rows, after a save refused a row's label or value (C31). */
const val SPECIFICATION_NEEDS_LABEL_AND_VALUE = "Each specification needs a label and a value."
