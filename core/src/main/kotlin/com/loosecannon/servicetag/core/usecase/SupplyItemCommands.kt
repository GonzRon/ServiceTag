package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem

/**
 * #15 (C15; R15-2): what the editor, `POST`/`PATCH /v1/supply-items` and the MCP hand to [SaveSupplyItem] — the whole
 * item, every field named. **No defaults**, here or on [SpecificationInput]: a construction site that forgot a field
 * would clear it, so forgetting one is a compile error instead (the `UpdateReferenceCommand` rule). `""` is "none" for
 * every text but [name]. [specifications] is the whole ordered list: a row left out is gone.
 *
 * There is no `archivedAt`, `createdAt` or `updatedAt`: a save stamps them, and only [ArchiveSupplyItem] archives.
 */
data class SupplyItemCommand(
    val name: String,
    val category: String,
    val manufacturer: String,
    val model: String,
    val partNumber: String,
    val preferredUnit: String,
    val notes: String,
    val specifications: List<SpecificationInput>,
)

/**
 * One specification row of a [SupplyItemCommand]. [id] names a row this item already owns, to **keep** — its id, and
 * its stored key unless [key] types another; any other id (none, another item's, a made-up one, a repeat) is a new
 * row with a fresh id. [key] is `""` on every phone save (the phone never shows a key, R15-11): a new row then takes
 * the definition slug of [label]. [unit] may be `""`.
 */
data class SpecificationInput(
    val id: String?,
    val key: String,
    val label: String,
    val value: String,
    val unit: String,
)

/**
 * One thing wrong with a [SupplyItemCommand], each answered `422` on the wire (C2's code beside each). An index names
 * the row of [SupplyItemCommand.specifications] at fault, so an editor can mark it. No member carries a sentence: the
 * words a person reads are the caller's, from the ratified strings.
 */
sealed interface SupplyItemProblem {
    /** `SUPPLY_ITEM_NAME_REQUIRED`: the name is blank after trimming. */
    data object NameRequired : SupplyItemProblem

    /** `SPECIFICATION_LABEL_REQUIRED`: the row's label is blank after trimming. */
    data class SpecLabelRequired(val index: Int) : SupplyItemProblem

    /** `SPECIFICATION_VALUE_REQUIRED`: the row's value is blank after trimming. */
    data class SpecValueRequired(val index: Int) : SupplyItemProblem

    /** `SPECIFICATION_KEY_INVALID`: a **typed** key outside the definition slug's pattern. */
    data class SpecKeyInvalid(val index: Int) : SupplyItemProblem

    /** `SPECIFICATION_KEY_TAKEN`: a **typed** key another row of this item holds in the same command. */
    data class SpecKeyTaken(val index: Int) : SupplyItemProblem
}

/** Validation failed; every problem found, collected once rather than fail-fast (the `GroupValidation` shape). */
class SupplyItemValidation(val problems: List<SupplyItemProblem>) :
    IllegalArgumentException("invalid supply item: $problems")

/** `NO_SUCH_SUPPLY_ITEM` (404): the SupplyItem an edit or an archive was aimed at is not there. */
class NoSuchSupplyItem(val id: SupplyId) : IllegalArgumentException("no supply item ${id.value}")

/**
 * What [SaveSupplyItem] answers: the item as stored. [unchanged] is true when the command described exactly the
 * stored item, so nothing was written and [item] is the stored row, `updatedAt` held (over the API: 200 with it).
 */
data class SavedSupplyItem(val item: SupplyItem, val unchanged: Boolean)
