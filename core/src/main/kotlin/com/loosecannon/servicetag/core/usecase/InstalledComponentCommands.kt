package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompositionEntry
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.InstalledComponentTree
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import java.time.LocalDate

/**
 * One thing wrong with an installed component command (#47, C2, C5, C15), C2's code and status beside each. No member
 * carries a sentence. The first five are the shape problems [installedComponentProblems] and [compositionProblems]
 * find; the rest are about other rows, which only the use cases read.
 */
sealed interface InstalledComponentProblem {
    /** `INSTALLED_COMPONENT_NAME_REQUIRED` (422, `name`): the name is blank once trimmed. */
    data object NameRequired : InstalledComponentProblem

    /** `INSTALLED_COMPONENT_DATE_INVALID` (422, [field]): not an ISO `YYYY-MM-DD` date. */
    data class BadDate(val field: String) : InstalledComponentProblem

    /** `INSTALLED_COMPONENT_DATE_AFTER_TODAY` (422, [field]): later than the phone's today. */
    data class AfterToday(val field: String) : InstalledComponentProblem

    /** `INSTALLED_COMPONENT_REMOVED_BEFORE_INSTALLED` (422, [field]): a closing date before the install date. */
    data class RemovedBeforeInstalled(val field: String) : InstalledComponentProblem

    /** `COMPOSITION_QUANTITY_INVALID` (422, `composition`): entry [index]'s quantity is not a finite number above zero. */
    data class QuantityInvalid(val index: Int) : InstalledComponentProblem

    /** `no_such_asset` (404): the command's asset is not there. */
    data object OwnerMissing : InstalledComponentProblem

    /** `NO_SUCH_INSTALLED_COMPONENT` (404, `parentId`): the command's parent names no row. */
    data object ParentMissing : InstalledComponentProblem

    /** `INSTALLED_COMPONENT_PARENT_ON_ANOTHER_ASSET` (422, `parentId`): the parent is fitted in another Asset. */
    data object ParentOnAnotherAsset : InstalledComponentProblem

    /** `INSTALLED_COMPONENT_PARENT_REMOVED` (409, `parentId`): the parent is a removed row, which takes nothing new. */
    data object ParentRemoved : InstalledComponentProblem

    /** `NO_SUCH_SUPPLY_ITEM` (404, `supplyId`): the direct link names no SupplyItem. */
    data object SupplyItemMissing : InstalledComponentProblem

    /**
     * `SUPPLY_ITEM_ARCHIVED` (409, `supplyId`): the direct link names an archived SupplyItem, which enters no new link
     * (R47-3); only an edit keeping the stored link may keep it.
     */
    data object SupplyItemArchived : InstalledComponentProblem

    /** `NO_SUCH_SUPPLY_ITEM` (404, `composition`): entry [index] names no SupplyItem. */
    data class EntrySupplyItemMissing(val index: Int) : InstalledComponentProblem

    /**
     * `SUPPLY_ITEM_ARCHIVED` (409, `composition`): entry [index] names an archived SupplyItem, which enters no new
     * composition (R47-3); only an edit whose stored composition already names it may keep it.
     */
    data class EntrySupplyItemArchived(val index: Int) : InstalledComponentProblem

    /** `NO_SUCH_INSTALLED_COMPONENT` (404): the row a remove, a replace or an edit was aimed at is not there. */
    data object NoSuchInstalledComponent : InstalledComponentProblem

    /** `INSTALLED_COMPONENT_REMOVED` (409): removing or replacing a row that is already removed. */
    data object AlreadyRemoved : InstalledComponentProblem

    /**
     * No code: an edit equal to the stored row after trimming, its composition compared with its kept ids, writes
     * nothing and answers 200 with the stored row (E-13's precedent).
     */
    data object Unchanged : InstalledComponentProblem
}

/**
 * The two outcomes of an installed component use case (#47, C15), the [AssetSupplyResult] shape: every
 * [InstalledComponentProblem] is something a caller draws or maps to a code. A held asset's write is the one
 * exception that still escapes: the guarded port throws `AssetTransferredOut` at the write, after every check here.
 */
sealed interface InstalledComponentResult {
    /**
     * [row] as written. [replaced] is the predecessor a replace closed, null otherwise; [closed] is every current
     * descendant the write closed with it (R47-6), by id, empty for an install or an edit.
     */
    data class Ok(
        val row: InstalledComponent,
        val replaced: InstalledComponent?,
        val closed: List<InstalledComponent>,
    ) : InstalledComponentResult

    /**
     * Nothing was written. A shape refusal opened no transaction; a refusal about another row was found inside the
     * write before its first write.
     */
    data class Refused(val problems: List<InstalledComponentProblem>) : InstalledComponentResult
}

/**
 * One composition entry as a caller sent it (#47, C15). [quantity] is the text as typed, parsed by the use case with
 * [ConsumableInput]'s convention and refused by index unless it is a number above zero. [id] is the entry an edit
 * means to keep: kept only when the stored row owns it, and only at its first occurrence in the list (C-7); any
 * other id, and every id on an install or a replace, is minted fresh.
 */
data class CompositionInput(val id: String?, val supplyId: SupplyId, val quantity: String, val unit: String)

/**
 * What the phone's install sheet, `POST /v1/installed-components` and the MCP hand to [InstallComponent] (#47, C15,
 * C16): fit [name] in [assetId], at the top or inside [parentId] on the same Asset. No defaults, so every caller
 * decides every field. Text is trimmed by the use case and `""` is none; a null [installedOn] is a date not recorded
 * (R47-8); a null [sortOrder] comes after this parent's current children (R47-11).
 */
data class InstallComponentCommand(
    val assetId: AssetId,
    val parentId: InstalledComponentId?,
    val name: String,
    val supplyId: SupplyId?,
    val composition: List<CompositionInput>,
    val serialOrLot: String,
    val installedOn: String?,
    val notes: String,
    val sortOrder: Int?,
)

/**
 * The successor a replace fits on [replacedOn] in place of the row it closes (#47, C15, C18). What the command
 * carries is what the successor gets: a null [supplyId] or an empty [composition] is none, never the predecessor's
 * (R47-17b), and every entry's id is minted fresh. Only the API fills [name] from the predecessor when its body
 * leaves it out (a label, not a link); the phone's prefill is a draft the person saves.
 */
data class ReplaceComponentCommand(
    val replacedOn: String,
    val name: String,
    val supplyId: SupplyId?,
    val composition: List<CompositionInput>,
    val serialOrLot: String,
    val notes: String,
)

/**
 * The whole editable set of one row, current or removed (#47, C15, C19; R47-15). It has no asset, parent, removal
 * date or `replacesId`, so an edit never moves a row, reopens it or rewrites what it replaced. [composition] is the
 * whole ordered list and replaces the stored one.
 */
data class UpdateInstalledComponentCommand(
    val name: String,
    val supplyId: SupplyId?,
    val composition: List<CompositionInput>,
    val serialOrLot: String,
    val installedOn: String?,
    val notes: String,
    val sortOrder: Int,
)

/**
 * The shape of an installed component, whatever wrote it, and nothing about other rows (#47, C5) —
 * [loanProblems]' contract. The use cases ask it of the command they were sent, with their [today]; the backup
 * content check asks it of every restored row with none, so a restore refuses what a command refuses but never
 * judges a row by the importing phone's date. A null [installedOn] is a date not recorded (R47-8) and a null
 * [removedOn] a current row; neither is a problem. Every problem is collected, in field order.
 */
internal fun installedComponentProblems(
    name: String,
    installedOn: String?,
    removedOn: String?,
    today: LocalDate? = null,
): List<InstalledComponentProblem> {
    val problems = mutableListOf<InstalledComponentProblem>()
    if (name.isBlank()) problems += InstalledComponentProblem.NameRequired
    val installed = installedOn?.let { value ->
        val parsed = parseDate(value)
        if (parsed == null) {
            problems += InstalledComponentProblem.BadDate("installedOn")
        } else if (today != null && parsed > today) {
            problems += InstalledComponentProblem.AfterToday("installedOn")
        }
        parsed
    }
    if (removedOn != null) {
        val removed = parseDate(removedOn)
        if (removed == null) {
            problems += InstalledComponentProblem.BadDate("removedOn")
        } else {
            if (installed != null && removed < installed) problems += InstalledComponentProblem.RemovedBeforeInstalled("removedOn")
            if (today != null && removed > today) problems += InstalledComponentProblem.AfterToday("removedOn")
        }
    }
    return problems
}

/**
 * The shape of a composition (#47, C5): each entry's quantity is a finite number above zero, reported by the
 * entry's index in [entries]. Every problem is collected. Which SupplyItems the entries name is about other rows,
 * so it is the use cases' and the graph check's, not this.
 */
internal fun compositionProblems(entries: List<CompositionEntry>): List<InstalledComponentProblem> =
    entries.mapIndexedNotNull { index, entry ->
        InstalledComponentProblem.QuantityInvalid(index).takeIf { !entry.quantity.isFinite() || entry.quantity <= 0.0 }
    }

/**
 * One quantity as typed (#47, C15), [ConsumableInput]'s convention: trimmed, then a finite number. Anything else is
 * NaN, which [compositionProblems] refuses by its entry's index, as it refuses zero and below.
 */
internal fun compositionQuantity(typed: String): Double = typed.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: Double.NaN

/**
 * The command-only shape of [inputs] (#47, C15): [compositionProblems] over the parsed quantities, before any id is
 * minted or any row read.
 */
internal fun compositionInputProblems(inputs: List<CompositionInput>): List<InstalledComponentProblem> =
    compositionProblems(
        inputs.mapIndexed { index, input ->
            CompositionEntry(id = "", supplyId = input.supplyId, quantity = compositionQuantity(input.quantity), unit = input.unit, sortOrder = index)
        },
    )

/**
 * [inputs] as the entries a write stores (#47, C15): each quantity parsed, each unit trimmed, `sortOrder` the list
 * position, and each id kept only when it is in [owned] and no earlier entry claimed it (C-7, [SaveSupplyItem]'s
 * rule); every other id is minted by [ids], in list order. An install and a replace own nothing, so every entry they
 * write is new. Called after [compositionInputProblems] passed.
 */
internal fun compositionOf(inputs: List<CompositionInput>, owned: Set<String>, ids: IdGenerator): List<CompositionEntry> {
    val claimed = HashSet<String>()
    return inputs.mapIndexed { index, input ->
        CompositionEntry(
            id = input.id?.takeIf { it in owned && claimed.add(it) } ?: ids.newId(),
            supplyId = input.supplyId,
            quantity = compositionQuantity(input.quantity),
            unit = input.unit.trim(),
            sortOrder = index,
        )
    }
}

/** The shape of a closing date (#47, C17, C18): an ISO `YYYY-MM-DD` day no later than [today], reported under [field]. */
internal fun closingDateProblems(field: String, value: String, today: LocalDate): List<InstalledComponentProblem> {
    val parsed = parseDate(value) ?: return listOf(InstalledComponentProblem.BadDate(field))
    return if (parsed > today) listOf(InstalledComponentProblem.AfterToday(field)) else emptyList()
}

/**
 * The SupplyItems a write's direct link and composition name (#47, C15, C16): the direct link first, one refusal —
 * [InstalledComponentProblem.SupplyItemMissing], then [InstalledComponentProblem.SupplyItemArchived] unless it is
 * [keptDirect] — and only then every entry's, collected by index, an archived one refused unless its SupplyItem is in
 * [keptInComposition] (R47-3). An install and a replace keep nothing: no archived SupplyItem enters a new link or
 * composition.
 */
internal suspend fun supplyProblems(
    items: SupplyItemRepository,
    supplyId: SupplyId?,
    composition: List<CompositionInput>,
    keptDirect: SupplyId?,
    keptInComposition: Set<SupplyId>,
): List<InstalledComponentProblem> {
    if (supplyId != null) {
        val item = items.get(supplyId) ?: return listOf(InstalledComponentProblem.SupplyItemMissing)
        if (item.archivedAt != null && supplyId != keptDirect) return listOf(InstalledComponentProblem.SupplyItemArchived)
    }
    return composition.mapIndexedNotNull { index, input ->
        val item = items.get(input.supplyId)
        when {
            item == null -> InstalledComponentProblem.EntrySupplyItemMissing(index)
            item.archivedAt != null && input.supplyId !in keptInComposition -> InstalledComponentProblem.EntrySupplyItemArchived(index)
            else -> null
        }
    }
}

/**
 * Every current row fitted inside [id] at any depth (#47, C17, C18; R47-6): what closing [id] closes with it, by id.
 * The walk passes through removed rows, so a current row below one, which no writer leaves, is closed too.
 */
internal fun currentDescendants(rows: Collection<InstalledComponent>, id: InstalledComponentId): List<InstalledComponent> {
    val inside = InstalledComponentTree.descendants(rows, id)
    return rows.filter { it.id in inside && it.isCurrent }.sortedBy { it.id.value }
}

/**
 * True when [closedOn] falls before the install date of any of [rows] (#47, C17, C18): a row and its current subtree
 * cannot leave before they were fitted. A date not recorded refuses nothing.
 */
internal fun closesBeforeInstalled(closedOn: LocalDate, rows: Collection<InstalledComponent>): Boolean =
    rows.any { row -> row.installedOn?.let(::parseDate)?.let { it > closedOn } == true }
