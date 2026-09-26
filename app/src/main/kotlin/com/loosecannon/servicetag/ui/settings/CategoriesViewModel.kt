package com.loosecannon.servicetag.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.journal.CategoryCatalog
import com.loosecannon.servicetag.core.journal.CategoryKey
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.CategoryRepository
import com.loosecannon.servicetag.core.usecase.CategoryExists
import com.loosecannon.servicetag.core.usecase.CategoryInUse
import com.loosecannon.servicetag.core.usecase.CategoryIsBuiltIn
import com.loosecannon.servicetag.core.usecase.CategoryUsage
import com.loosecannon.servicetag.core.usecase.DeleteCategory
import com.loosecannon.servicetag.core.usecase.NoSuchCategory
import com.loosecannon.servicetag.core.usecase.RenameCategory
import com.loosecannon.servicetag.di.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val SUBSCRIPTION_GRACE_MS = 5_000L

// #74's Categories sentences this model composes (plan §6, RATIFIED 2026-09-26), each by its P74-number
// and verbatim; `<x>` is a category's display, `<n>` a count. The screen's fixed words are beside it.

/** P74-6a/b/c, a category's usage line: every asset counted, archived and retired included (C8). */
internal fun usageLine(count: Int): String = when (count) {
    0 -> "Not used"
    1 -> "Used by 1 asset"
    else -> "Used by $count assets"
}

/** P74-11, under the rename field: the name is another category's; [existing] is that row's display. */
internal fun nameTaken(existing: String): String = "A category named $existing already exists."

/** P74-12, under the rename field: the name is a built-in's; [label] is the built-in's own. */
internal fun builtInName(label: String): String = "$label is a built-in category."

/** P74-16, the delete dialog's body. */
internal fun notUsedByAnyAsset(display: String): String = "$display is not used by any asset."

/** P74-17a/b, the snackbar for a delete of a category [count] assets still use. */
internal fun stillInUse(display: String, count: Int): String = if (count == 1) {
    "$display is used by 1 asset. Change its category first."
} else {
    "$display is used by $count assets. Change their category first."
}

/** One of the owner's categories as the screen lists it: [usage] assets use it (C8). */
data class OwnCategory(val key: String, val display: String, val usage: Int)

/**
 * The screen's two lists: the owner's categories in the picker's order (R74-10) with their usage, and
 * the built-in labels in compiled order, which are never rows and have nothing to count or change.
 */
data class CategoriesState(val own: List<OwnCategory>, val builtIns: List<String>)

/**
 * The open rename dialog (P74-8) for the row [key], which read [current] when it opened. [refusal] is
 * P74-11 or P74-12 under the field; typing takes it down.
 */
data class RenameDraft(
    val key: String,
    val current: String,
    val text: String,
    val refusal: String? = null,
    val renaming: Boolean = false,
) {
    /**
     * P74-10 is held while [text] is blank or would change nothing — C6's no-op rule, said by the
     * button rather than by a refusal — and while one rename is already on its way.
     */
    val canRename: Boolean
        get() = !renaming && CategoryKey.of(text) != null && CategoryKey.display(text) != current
}

/** The open delete confirmation (P74-15, P74-16) for an unused category. */
data class DeleteAsk(val key: String, val display: String)

/**
 * Settings → Categories (#74, C17). The owner's own categories, each with how many assets use it, and
 * the built-ins beside them; the two writes are [RenameCategory] and [DeleteCategory], and nothing here
 * adds a category — a successful asset save is the one way in (P74-5 says so).
 *
 * Where each answer is said:
 * - a refused rename (a name another category or a built-in holds) is drawn under the rename field and
 *   the dialog stays, as a problem under a field always is — a snackbar would sit under the dialog;
 * - a delete of a category in use asks nothing and says why on the snackbar (P74-17), both when the
 *   row's own count already shows the use and when the use arrived after the confirmation opened;
 * - a rename or delete aimed at a row that is gone ([NoSuchCategory]) has no words: the dialog closes
 *   and the list, which follows the store, already shows the truth.
 */
class CategoriesViewModel(
    categories: CategoryRepository,
    assets: AssetRepository,
    private val renameCategory: RenameCategory,
    private val deleteCategory: DeleteCategory,
) : ViewModel() {

    constructor(graph: AppGraph) :
        this(graph.categories, graph.assets, graph.renameCategory, graph.deleteCategory)

    /** Null until the store has answered once. Both lists follow it, and so do the counts. */
    val state: StateFlow<CategoriesState?> = combine(categories.observeAll(), assets.observeAll()) { rows, all ->
        CategoriesState(
            // The catalog's own order and its dedupe: a row under a built-in's key is the built-in's.
            own = CategoryCatalog.choices(rows)
                .filterNot { it.builtIn }
                .map { OwnCategory(it.key, it.display, CategoryUsage.count(all, it.key)) },
            builtIns = CategoryCatalog.builtIns.map { it.display },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), null)

    private val _rename = MutableStateFlow<RenameDraft?>(null)
    val rename: StateFlow<RenameDraft?> = _rename.asStateFlow()

    private val _deleting = MutableStateFlow<DeleteAsk?>(null)
    val deleting: StateFlow<DeleteAsk?> = _deleting.asStateFlow()

    /** The snackbar's lines: only P74-17. One line, once. */
    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** P74-13: the dialog opens on the row's own display (P74-9 pre-filled). */
    fun startRename(row: OwnCategory) {
        _rename.value = RenameDraft(key = row.key, current = row.display, text = row.display)
    }

    fun onRenameText(text: String) = _rename.update { it?.copy(text = text, refusal = null) }

    fun dismissRename() {
        _rename.value = null
    }

    /**
     * P74-10. Success closes the dialog and the row changes in place, with no line (§6). The guard is
     * set before the first suspension, so two taps inside one frame rename once.
     */
    fun confirmRename() {
        val draft = _rename.value?.takeIf { it.canRename } ?: return
        _rename.value = draft.copy(renaming = true)
        viewModelScope.launch {
            val failure = runCatching { renameCategory.run(draft.key, draft.text) }.exceptionOrNull()
            if (failure is CancellationException) throw failure
            _rename.update { open ->
                // A dialog closed while the rename ran stays closed.
                if (open?.key != draft.key) {
                    open
                } else {
                    when (failure) {
                        null, is NoSuchCategory -> null
                        is CategoryExists -> open.copy(renaming = false, refusal = nameTaken(failure.existingDisplay))
                        is CategoryIsBuiltIn -> open.copy(renaming = false, refusal = builtInName(failure.label))
                        // CategoryValidation is what the held button already prevents, and nothing
                        // else has ratified words: the dialog stays as typed and can be tried again.
                        else -> open.copy(renaming = false)
                    }
                }
            }
        }
    }

    /**
     * P74-14. An unused category asks first (P74-15, P74-16); one in use asks nothing and says so with
     * the count its row already shows (P74-17), and nothing is written.
     */
    fun requestDelete(row: OwnCategory) {
        if (row.usage > 0) {
            _messages.tryEmit(stillInUse(row.display, row.usage))
        } else {
            _deleting.value = DeleteAsk(row.key, row.display)
        }
    }

    fun dismissDelete() {
        _deleting.value = null
    }

    /**
     * The confirmation's Delete. The use case counts again in its own transaction, so an asset that
     * took the category after the dialog opened refuses the delete with P74-17 and the row stays.
     */
    fun confirmDelete() {
        val ask = _deleting.getAndUpdate { null } ?: return
        viewModelScope.launch {
            val failure = runCatching { deleteCategory.run(ask.key) }.exceptionOrNull()
            if (failure is CancellationException) throw failure
            if (failure is CategoryInUse) _messages.tryEmit(stillInUse(failure.display, failure.count))
        }
    }
}
