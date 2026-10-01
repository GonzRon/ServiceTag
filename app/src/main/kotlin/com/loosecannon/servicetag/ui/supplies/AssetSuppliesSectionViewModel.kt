package com.loosecannon.servicetag.ui.supplies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.journal.CategoryKey
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetSupply
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.ports.AssetSupplyRepository
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.usecase.AddAssetSupply
import com.loosecannon.servicetag.core.usecase.AddAssetSupplyCommand
import com.loosecannon.servicetag.core.usecase.AssetSupplyProblem
import com.loosecannon.servicetag.core.usecase.AssetSupplyResult
import com.loosecannon.servicetag.core.usecase.AssetSupplyRoles
import com.loosecannon.servicetag.core.usecase.RemoveAssetSupply
import com.loosecannon.servicetag.core.usecase.UpdateAssetSupply
import com.loosecannon.servicetag.core.usecase.UpdateAssetSupplyCommand
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.transfer.transferredOutOr
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How long the repository flows stay hot after the last collector leaves (a rotation, typically). */
private const val SUBSCRIPTION_GRACE_MS = 5_000L

/**
 * One applicability row as the asset's Supplies section draws it (C33): the SupplyItem's [name], the [role] as a
 * quiet line, and whether the item is [archived] (R15-6: it stays on the asset, marked). [supplyId] is what a tap opens.
 */
data class AssetSupplyRowState(
    val id: String,
    val supplyId: SupplyId,
    val name: String,
    val role: String,
    val archived: Boolean,
)

/**
 * The role sheet (C33): adding a picked SupplyItem ([rowId] null, titled P15-13) or re-roling a row (titled P15-17).
 * [role] is what was typed, free text; [problem] the one sentence under the field, null when there is none.
 */
data class RoleSheetState(
    val rowId: String?,
    val supplyId: SupplyId,
    val supplyName: String,
    val role: String,
    val problem: String? = null,
    val saving: Boolean = false,
) {
    val title: String get() = if (rowId == null) ADD_SUPPLY else EDIT_ROLE

    /** Save is offered while the role, cleaned by the one role cleaner, says something (P15-19 is reserved). */
    val canSave: Boolean get() = !saving && CategoryKey.display(role).isNotEmpty()

    /** The same sheet a later answer belongs to: a save answered after the sheet changed lands nowhere. */
    internal fun sameAs(other: RoleSheetState): Boolean = rowId == other.rowId && supplyId == other.supplyId
}

data class AssetSuppliesState(
    /** This asset's rows, in `(role casefolded, SupplyItem name casefolded, id)` order. */
    val rows: List<AssetSupplyRowState> = emptyList(),
    /** What the picker offers: the catalog's **unarchived** items, in the Supplies list's order (C32, R15-6). */
    val choices: List<SupplyListRow> = emptyList(),
    /** The role sheet's chips: the cleaned roles in use on any asset, in the category picker's order (C17). */
    val suggestions: List<String> = emptyList(),
    /** False on a held asset: the rows draw, and no glyph, overflow, picker or sheet is offered. */
    val offersWrites: Boolean = true,
    val picking: Boolean = false,
    val sheet: RoleSheetState? = null,
)

/**
 * #15 (C32, C33) — the one ViewModel behind the asset's Supplies section, in `ReferencesSectionViewModel`'s shape: the
 * repositories' flows for what is drawn, one use-case call per write (`AddAssetSupply`, `UpdateAssetSupply`,
 * `RemoveAssetSupply`), and one exhaustive `when` over [AssetSupplyProblem] for the sentence each refusal draws.
 * Nothing here re-checks a rule the use cases own: the role is cleaned, and its uniqueness decided, by them.
 */
class AssetSuppliesSectionViewModel(
    private val assetId: AssetId,
    private val assetSupplies: AssetSupplyRepository,
    items: SupplyItemRepository,
    private val addAssetSupply: AddAssetSupply,
    private val updateAssetSupply: UpdateAssetSupply,
    private val removeAssetSupply: RemoveAssetSupply,
    /** Where the three writes run: `Dispatchers.IO` in the app, the test's own scheduler in a JVM test. */
    private val io: CoroutineContext = Dispatchers.IO,
) : ViewModel() {

    constructor(graph: AppGraph, assetId: AssetId) : this(
        assetId,
        graph.assetSupplies,
        graph.supplyItems,
        graph.addAssetSupply,
        graph.updateAssetSupply,
        graph.removeAssetSupply,
    )

    private data class Catalog(
        val rows: List<AssetSupplyRowState>,
        val choices: List<SupplyListRow>,
        val suggestions: List<String>,
    )

    private data class Ui(val offersWrites: Boolean = true, val picking: Boolean = false, val sheet: RoleSheetState? = null)

    private val ui = MutableStateFlow(Ui())

    /** Kept apart from [ui], so a keystroke in the sheet re-reads nothing from the store. */
    private val catalog = combine(assetSupplies.observeForAsset(assetId), items.observeAll()) { rows, all ->
        Catalog(
            rows = rowsOf(rows, all),
            choices = listRowsOf(all.filter { it.archivedAt == null }),
            // Every asset's roles, re-read whenever this asset's rows or the catalog move.
            suggestions = AssetSupplyRoles.suggestions(assetSupplies.all()),
        )
    }

    val state: StateFlow<AssetSuppliesState> =
        combine(catalog, ui) { c, u ->
            AssetSuppliesState(c.rows, c.choices, c.suggestions, u.offersWrites, u.picking, u.sheet)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), AssetSuppliesState())

    /** A refusal with no sheet to draw it under — a remove refused because the asset was transferred out. No replay. */
    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** The screen's `!offersWrites` (#77): read-only closes whatever is open and offers nothing until it lifts. */
    fun setReadOnly(readOnly: Boolean) {
        ui.update { if (readOnly) Ui(offersWrites = false) else it.copy(offersWrites = true) }
    }

    /** The header's add glyph (P15-13): the picker first. */
    fun startAdd() {
        ui.update { if (it.offersWrites) it.copy(picking = true, sheet = null) else it }
    }

    fun dismissPicker() {
        ui.update { it.copy(picking = false) }
    }

    /** A picked SupplyItem opens the add sheet with no role: a role is only ever typed or tapped (R15-3, C37). */
    fun pick(row: SupplyListRow) {
        ui.update {
            if (!it.offersWrites) it
            else it.copy(picking = false, sheet = RoleSheetState(rowId = null, supplyId = row.id, supplyName = row.name, role = ""))
        }
    }

    /** "Edit role" (P15-17): the same sheet, prefilled with the stored role. */
    fun startEdit(row: AssetSupplyRowState) {
        ui.update {
            if (!it.offersWrites) it
            else it.copy(picking = false, sheet = RoleSheetState(row.id, row.supplyId, row.name, row.role))
        }
    }

    /** Typed or tapped from a suggestion: the field's text, as is; a sentence under it is cleared by the edit. */
    fun onRole(text: String) {
        ui.update { u -> u.copy(sheet = u.sheet?.copy(role = text, problem = null)) }
    }

    /** Cancel, or the sheet dismissed: nothing is written. */
    fun dismissSheet() {
        ui.update { it.copy(sheet = null) }
    }

    fun save() {
        val sheet = ui.value.sheet ?: return
        if (!ui.value.offersWrites || sheet.saving) return
        ui.update { u -> u.copy(sheet = u.sheet?.takeIf { it.sameAs(sheet) }?.copy(saving = true, problem = null) ?: u.sheet) }
        viewModelScope.launch(io) {
            val outcome = try {
                val rowId = sheet.rowId
                if (rowId == null) {
                    addAssetSupply.run(AddAssetSupplyCommand(assetId, sheet.supplyId, sheet.role))
                } else {
                    updateAssetSupply.run(rowId, UpdateAssetSupplyCommand(sheet.role))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // P77-35 when the asset was transferred out meanwhile; any other failure ratifies no sentence, so the
                // sheet stays open holding what was typed (the references section's ruling).
                answer(sheet, t.transferredOutOr("").takeIf { it.isNotEmpty() })
                return@launch
            }
            when (outcome) {
                is AssetSupplyResult.Ok -> close(sheet)
                is AssetSupplyResult.Refused -> say(sheet, outcome.problem)
            }
        }
    }

    /** At once, with no dialog: an applicability row is configuration, re-addable (C33, R15-5). */
    fun remove(id: String) {
        if (!ui.value.offersWrites) return
        viewModelScope.launch(io) {
            try {
                // Its one refusal, `NoSuchAssetSupply`, means the row is already gone — what was asked — so it says nothing.
                removeAssetSupply.run(id)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                t.transferredOutOr("").takeIf { it.isNotEmpty() }?.let { _messages.tryEmit(it) }
            }
        }
    }

    /** One ratified sentence per refusal (§5); `Unchanged` closes the sheet as saved, writing nothing. */
    private fun say(sheet: RoleSheetState, problem: AssetSupplyProblem) {
        val line = when (problem) {
            AssetSupplyProblem.Unchanged -> return close(sheet)
            AssetSupplyProblem.Taken -> SUPPLY_ROLE_TAKEN
            AssetSupplyProblem.OwnerMissing -> SUPPLY_ITEM_GONE
            AssetSupplyProblem.SupplyItemMissing -> SUPPLY_ITEM_GONE
            AssetSupplyProblem.SupplyItemArchived -> SUPPLY_ITEM_GONE
            AssetSupplyProblem.NoSuchAssetSupply -> SUPPLY_ITEM_GONE
            // Unreachable from the sheet: Save is disabled while the cleaned role is blank (P15-19, reserved).
            AssetSupplyProblem.RoleRequired -> null
        }
        answer(sheet, line)
    }

    private fun answer(sheet: RoleSheetState, line: String?) {
        ui.update { u -> u.copy(sheet = u.sheet?.let { if (it.sameAs(sheet)) it.copy(saving = false, problem = line) else it }) }
    }

    private fun close(sheet: RoleSheetState) {
        ui.update { u -> u.copy(sheet = u.sheet?.takeUnless { it.sameAs(sheet) }) }
    }

    private fun rowsOf(rows: List<AssetSupply>, all: List<SupplyItem>): List<AssetSupplyRowState> {
        val byId = all.associateBy { it.id }
        // A row whose item the catalog flow has not caught up with yet waits for it rather than drawing a blank name.
        return rows.mapNotNull { row ->
            byId[row.supplyId]?.let { item ->
                AssetSupplyRowState(row.id, row.supplyId, item.name, row.role, archived = item.archivedAt != null)
            }
        }.sortedWith(compareBy({ it.role.lowercase() }, { it.name.lowercase() }, { it.id }))
    }
}
