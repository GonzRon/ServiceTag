package com.loosecannon.servicetag.core.model

// #15, the SupplyItem MVP's **shapes** (C4; R15-1, R15-2, R15-3, R15-5): canonical identity, generic
// specifications and Asset applicability — nothing more. No rule lives here; the use cases that write these
// rows, and the codec and the merge that carry them, are later briefs'.
//
// The fence, by construction: no quantity, threshold, price, URL, position, fitting date, fitted serial,
// parent SupplyItem or owner column on any of these types (#95, #47 and #69 own those). A pack and a single
// item inside it are two SupplyItems with no relation between them (AC2).

/**
 * One canonical product, replacement item, consumable, module or pack — an aggregate root, saved and loaded
 * with its [specifications], keyed by [id] and **archived, never deleted** ([archivedAt]; R15-5), the
 * [MaintenanceGroup] shape. No constructor defaults, so every construction site decides every field.
 *
 * The identity fields are free text and `""` means none (the `Journal.kt` convention): [category] is **not**
 * the Asset category catalog; [partNumber] is the one SKU field, labelled "Part number" (R15-2). [name] is
 * required by the use case, not here.
 */
data class SupplyItem(
    val id: SupplyId,
    val name: String,
    val category: String,
    val manufacturer: String,
    val model: String,
    val partNumber: String,
    val preferredUnit: String,
    val notes: String,
    val archivedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    /** In `(sortOrder, id)` order. */
    val specifications: List<SupplySpecification>,
)

/**
 * One generic specification, a child row of [SupplyItem] with a durable id of its own — as [GroupMember] is
 * of [MaintenanceGroup] — so it survives a backup verbatim. [key] is the definition slug, unique within its
 * SupplyItem and kept across label edits (R15-11, the use case's). [value] is plain text: no type, range,
 * condition or interval (AC12). [unit] may be `""`.
 */
data class SupplySpecification(
    val id: String,
    val key: String,
    val label: String,
    val value: String,
    val unit: String,
    val sortOrder: Int,
)

/**
 * Applicability: this Asset (any Asset, a child Asset included) takes this SupplyItem in this [role] — free
 * text cleaned by the use case, `(assetId, supplyId, role)` unique (R15-3). It says what an Asset *takes*,
 * never what is fitted in it now (#47). No notes on the row (R15-3).
 */
data class AssetSupply(
    val id: String,
    val assetId: AssetId,
    val supplyId: SupplyId,
    val role: String,
    val createdAt: Long,
    val updatedAt: Long,
)
