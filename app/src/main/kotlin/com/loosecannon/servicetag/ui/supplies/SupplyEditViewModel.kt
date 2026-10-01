package com.loosecannon.servicetag.ui.supplies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplySpecification
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.usecase.NoSuchSupplyItem
import com.loosecannon.servicetag.core.usecase.SaveSupplyItem
import com.loosecannon.servicetag.core.usecase.SpecificationInput
import com.loosecannon.servicetag.core.usecase.SupplyItemCommand
import com.loosecannon.servicetag.core.usecase.SupplyItemProblem
import com.loosecannon.servicetag.core.usecase.SupplyItemValidation
import com.loosecannon.servicetag.di.AppGraph
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One specification row on the form (C31). [id] is the stored row's, carried so the save keeps it (B3's rule: a
 * row sent with an id this item owns is kept, its key with it); a row added here has none and is minted on save.
 * **There is no key**: the phone never shows or sends one (R15-11), so a kept row keeps its stored key and a new
 * row takes its label's slug. [marked] is the use case naming this row in a refused save.
 */
data class SpecificationEdit(
    val id: String?,
    val label: String = "",
    val value: String = "",
    val unit: String = "",
    val marked: Boolean = false,
)

/**
 * The SupplyItem form (C31): the identity fields, the ordered specification rows, and what a refused or a gone save
 * left on it. Each text is held as typed; the use case trims.
 */
data class SupplyEditState(
    val editing: Boolean = false,
    val loaded: Boolean = false,
    val name: String = "",
    val category: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val partNumber: String = "",
    val preferredUnit: String = "",
    val notes: String = "",
    val specifications: List<SpecificationEdit> = emptyList(),
    val saving: Boolean = false,
    val gone: Boolean = false,
) {
    /** A name of spaces is no name, and `SaveSupplyItem` refuses one: Save waits for a trimmed name (no sentence). */
    val canSave: Boolean get() = name.trim().isNotEmpty() && !saving

    /** P15-12, drawn under the rows while a row the refused save named is still marked. */
    val specificationsProblem: String?
        get() = SPECIFICATION_NEEDS_LABEL_AND_VALUE.takeIf { specifications.any { it.marked } }

    /** P15-20: the item this form edits is not there (gone before the load, or between the load and the save). */
    val goneProblem: String? get() = SUPPLY_ITEM_GONE.takeIf { gone }
}

/**
 * The SupplyItem editor (#15, C31), on `GroupEditViewModel`'s shape.
 *
 * It **owns no rule**. Every save is one [SaveSupplyItem] call with the whole form — the fields as typed and the rows
 * in screen order, each loaded row with its id and every row with `key = ""` — and the use case decides what is
 * trimmed, kept, minted, keyed or refused. Nothing here archives (the detail does), deletes (nothing does, R15-5),
 * or moves a row (there is no control to move one, R15-8). Until Save nothing is written, so Cancel and Back are
 * just leaving.
 */
class SupplyEditViewModel(
    private val items: SupplyItemRepository,
    private val saveSupplyItem: SaveSupplyItem,
    private val id: SupplyId?,
) : ViewModel() {

    constructor(graph: AppGraph, id: String?) : this(graph.supplyItems, graph.saveSupplyItem, id?.let(::SupplyId))

    private val _state = MutableStateFlow(SupplyEditState(editing = id != null))
    val state: StateFlow<SupplyEditState> = _state.asStateFlow()

    /** The saved item's id, once per save. The screen leaves on it; nothing else listens. */
    private val _saved = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val saved: SharedFlow<String> = _saved.asSharedFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun onName(value: String) = _state.update { it.copy(name = value) }
    fun onCategory(value: String) = _state.update { it.copy(category = value) }
    fun onManufacturer(value: String) = _state.update { it.copy(manufacturer = value) }
    fun onModel(value: String) = _state.update { it.copy(model = value) }
    fun onPartNumber(value: String) = _state.update { it.copy(partNumber = value) }
    fun onPreferredUnit(value: String) = _state.update { it.copy(preferredUnit = value) }
    fun onNotes(value: String) = _state.update { it.copy(notes = value) }

    /** A new, empty row at the end: it has no id, so the save mints one. */
    fun addSpecification() =
        _state.update { it.copy(specifications = it.specifications + SpecificationEdit(id = null)) }

    /** Edits row [index]; a null leaves that part as it is. An edited row loses its mark (the profile editor's). */
    fun onSpecification(index: Int, label: String? = null, value: String? = null, unit: String? = null) =
        _state.update { form ->
            form.copy(
                specifications = form.specifications.mapIndexed { i, row ->
                    if (i != index) {
                        row
                    } else {
                        row.copy(
                            label = label ?: row.label,
                            value = value ?: row.value,
                            unit = unit ?: row.unit,
                            marked = false,
                        )
                    }
                },
            )
        }

    /** Row [index] leaves the form, its mark with it; a kept row left out of the save is gone from the item. */
    fun removeSpecification(index: Int) = _state.update { form ->
        form.copy(specifications = form.specifications.filterIndexed { i, _ -> i != index })
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        _state.update { form ->
            form.copy(saving = true, gone = false, specifications = form.specifications.map { it.copy(marked = false) })
        }
        viewModelScope.launch {
            val command = SupplyItemCommand(
                name = current.name,
                category = current.category,
                manufacturer = current.manufacturer,
                model = current.model,
                partNumber = current.partNumber,
                preferredUnit = current.preferredUnit,
                notes = current.notes,
                specifications = current.specifications.map {
                    SpecificationInput(id = it.id, key = "", label = it.label, value = it.value, unit = it.unit)
                },
            )
            runCatching { saveSupplyItem.run(id, command) }.fold(
                // An unchanged edit wrote nothing, and leaves all the same: the stored item is what the form says.
                onSuccess = { saved ->
                    _state.update { it.copy(saving = false) }
                    _saved.tryEmit(saved.item.id.value)
                },
                onFailure = { failure ->
                    val problems = (failure as? SupplyItemValidation)?.problems.orEmpty()
                    val marks = problems.mapNotNullTo(mutableSetOf(), ::rowNamedBy)
                    _state.update { form ->
                        form.copy(
                            saving = false,
                            gone = failure is NoSuchSupplyItem,
                            specifications = form.specifications.mapIndexed { i, row -> row.copy(marked = i in marks) },
                        )
                    }
                },
            )
        }
    }

    private suspend fun load() {
        val item = id?.let { items.get(it) }
        _state.update { form ->
            if (item == null) {
                // A restored back stack or a replacing import can name an item that is not there any more.
                form.copy(loaded = true, gone = id != null)
            } else {
                form.copy(
                    loaded = true,
                    name = item.name,
                    category = item.category,
                    manufacturer = item.manufacturer,
                    model = item.model,
                    partNumber = item.partNumber,
                    preferredUnit = item.preferredUnit,
                    notes = item.notes,
                    specifications = item.specifications.map(::editOf),
                )
            }
        }
    }
}

private fun editOf(spec: SupplySpecification) =
    SpecificationEdit(id = spec.id, label = spec.label, value = spec.value, unit = spec.unit)

/**
 * The row a problem names, when it is one the form marks. A blank label or value is the only row problem the phone
 * can meet: Save waits for a name, and the phone sends no key, so the other three arms are unreachable from here.
 */
private fun rowNamedBy(problem: SupplyItemProblem): Int? = when (problem) {
    is SupplyItemProblem.SpecLabelRequired -> problem.index
    is SupplyItemProblem.SpecValueRequired -> problem.index
    SupplyItemProblem.NameRequired, is SupplyItemProblem.SpecKeyInvalid, is SupplyItemProblem.SpecKeyTaken -> null
}
