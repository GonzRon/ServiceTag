package com.loosecannon.servicetag.core.model

// #47, the installed component's **shapes** (C4; R47-2, R47-3, R47-8, R47-11, R47-17a): one row per fitted
// instance, its optional direct SupplyItem and its ordered composition. No rule lives here; the use cases that
// write these rows, and the codec and the merge that carry them, live in `usecase`, `backup` and `merge`, and
// the pure tree logic in [InstalledComponentTree].
//
// By construction: no price, interval, range, condition, tag, attachment, health or event field. A row is not an
// Asset and never an NFC target; an Asset's child Assets are a separate thing.

/**
 * One fitted instance inside an Asset, or inside another installed component of the same Asset — an aggregate
 * root, saved and loaded with its [composition]. **Current** while [removedOn] is null; the rows that carry a
 * removal date are the history, and a replacement closes one row and inserts one naming it in [replacesId]
 * (R47-2). A row is never deleted one by one: it leaves only with its Asset. No constructor defaults, so every
 * construction site decides every field.
 *
 * [supplyId] and [composition] are independent (R47-3, R47-17a): [supplyId] says this unit **is** that
 * SupplyItem; [composition] says it is **made of** those entries. Either, both or neither may be set, and an
 * entry may name the same SupplyItem as another entry or as [supplyId].
 *
 * The text fields are free text and `""` means none (the `Journal.kt` convention); [name] is required by the
 * use case, not here. The dates are ISO `YYYY-MM-DD` days.
 */
data class InstalledComponent(
    val id: InstalledComponentId,
    /** The Asset the row is fitted in. Immutable after insert. */
    val assetId: AssetId,
    /** The row it is fitted inside, on the same Asset; null for a top-level row. Immutable after insert. */
    val parentId: InstalledComponentId?,
    /** The label: "Battery tray", "Position 3", "Alternator". */
    val name: String,
    /** Direct identity: this unit is that SupplyItem (R47-3); null for none. */
    val supplyId: SupplyId?,
    /** What this unit is made of, in `(sortOrder, id)` order; empty for none. */
    val composition: List<CompositionEntry>,
    /** The instance's serial number or lot; `""` for none. */
    val serialOrLot: String,
    /** The day it was fitted; null when not recorded (R47-8). */
    val installedOn: String?,
    /** The day it was taken out; null while it is current. Set once (R47-15). */
    val removedOn: String?,
    /** The row this one replaced, set by a replace at insert and immutable (R47-2); null for a first fitting. */
    val replacesId: InstalledComponentId?,
    /** The order among siblings (R47-11); drawn `(sortOrder, name case-insensitively, id)`. */
    val sortOrder: Int,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** True until the row carries a removal date. */
    val isCurrent: Boolean get() = removedOn == null
}

/**
 * One entry of an [InstalledComponent]'s composition, a child row with a durable id of its own — as
 * [SupplySpecification] is of [SupplyItem] — so it survives a backup verbatim. It belongs to one instance row
 * and stays with it when that row closes; a replacement's entries are its own.
 *
 * [quantity] is how many of [supplyId] one unit is made of, in [unit] (`""` for none): `4 × Example 12 V
 * Battery`. Nothing is counted, kept or used up by it, and no event or schedule follows from it.
 */
data class CompositionEntry(
    val id: String,
    val supplyId: SupplyId,
    val quantity: Double,
    val unit: String,
    val sortOrder: Int,
)
