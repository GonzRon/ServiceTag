package com.loosecannon.servicetag.ui.maintenance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.isRetired
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.usecase.GroupCommand
import com.loosecannon.servicetag.core.usecase.GroupMemberInput
import com.loosecannon.servicetag.core.usecase.GroupProblem
import com.loosecannon.servicetag.core.usecase.GroupValidation
import com.loosecannon.servicetag.core.usecase.SaveGroup
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
 * One asset the group may hold, and whether it does.
 *
 * The form is a **selection over assets**, not a list of membership rows, and that is what makes
 * invariant 80 unreachable from here: there is exactly one entry per asset, so no sequence of taps
 * can ask for a second open window on one `(group, asset)` pair. The membership row behind a
 * selected entry is [membershipId] — carried so the save can say "keep this one" rather than
 * "add another".
 */
data class GroupEditCandidate(
    val assetId: AssetId,
    val name: String,
    val selected: Boolean,
    val membershipId: String?,
)

/**
 * The group form: a name, a description and the member set.
 *
 * D-26: the aggregate carries a **description** and no third context field, so the form has no
 * state for one either.
 *
 * [problems] is what `SaveGroup` refused, kept so a refusal is a visible outcome rather than a tap
 * that did nothing. **Nothing draws it as a sentence**: §17 ratifies no wording for a group refusal
 * and this brief drafts none, so the screen's defence is that it cannot ask for the two states the
 * use case refuses — a blank name disables the save, and a duplicate open member is unconstructible.
 */
data class GroupEditState(
    val editing: Boolean = false,
    val name: String = "",
    val description: String = "",
    val candidates: List<GroupEditCandidate> = emptyList(),
    val saving: Boolean = false,
    val loaded: Boolean = false,
    val problems: List<GroupProblem> = emptyList(),
) {
    /** A group with no name is not identifiable by a human, and `SaveGroup` refuses one. */
    val canSave: Boolean get() = name.isNotBlank() && !saving
}

/**
 * The group editor (#55, spec §2.3).
 *
 * It owns **no membership rule**. Every one of them is [SaveGroup]'s, called once with the member
 * set as it now stands, and the use case decides what that means for the stored windows:
 *
 * - a selected asset that already holds an open window is sent **with its membership id** — a keep,
 *   which copies `added_at` and `removed_at` across untouched;
 * - a selected asset that holds none is sent **without one** — an insert, with a new id and a new
 *   `added_at`, which is why re-adding a removed member produces a **second** window and never
 *   reopens the first (invariants 33, 79);
 * - a deselected open member is simply **omitted**, which stamps `removed_at` and deletes nothing.
 *
 * Nothing here clears a `removed_at`, and there is no code path that could: the form sends a set of
 * assets, and the only temporal fact it can express is which ones are in the group now.
 */
class GroupEditViewModel(
    private val groups: GroupRepository,
    private val assets: AssetRepository,
    private val saveGroup: SaveGroup,
    private val id: GroupId?,
) : ViewModel() {

    constructor(graph: AppGraph, id: String?) : this(
        graph.groups,
        graph.assets,
        graph.saveGroup,
        id?.let(::GroupId),
    )

    private val _state = MutableStateFlow(GroupEditState(editing = id != null))
    val state: StateFlow<GroupEditState> = _state.asStateFlow()

    /** The saved group's id, once. The screen leaves on it; nothing else listens. */
    private val _saved = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val saved: SharedFlow<String> = _saved.asSharedFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun onName(value: String) = _state.update { it.copy(name = value) }

    fun onDescription(value: String) = _state.update { it.copy(description = value) }

    /** In or out. One entry per asset, so this is the whole vocabulary of membership editing. */
    fun toggle(assetId: AssetId) = _state.update { current ->
        current.copy(
            candidates = current.candidates.map { candidate ->
                if (candidate.assetId == assetId) candidate.copy(selected = !candidate.selected) else candidate
            },
        )
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        _state.update { it.copy(saving = true, problems = emptyList()) }
        viewModelScope.launch {
            val command = GroupCommand(
                name = current.name.trim(),
                description = current.description.trim(),
                members = current.candidates
                    .filter { it.selected }
                    .mapIndexed { index, candidate ->
                        GroupMemberInput(
                            assetId = candidate.assetId,
                            // An open window is kept by id; an asset with none is an add. The
                            // use case is what turns those two into rows.
                            id = candidate.membershipId,
                            sortOrder = index,
                        )
                    },
            )
            runCatching { saveGroup.run(id, command) }.fold(
                onSuccess = { group ->
                    // Re-read the windows the save just wrote, so a second save from the same open
                    // form keeps them by id rather than asking for a duplicate of each.
                    refreshCandidates(group)
                    _state.update { it.copy(saving = false) }
                    _saved.tryEmit(group.id.value)
                },
                onFailure = { failure ->
                    _state.update {
                        it.copy(
                            saving = false,
                            problems = (failure as? GroupValidation)?.problems.orEmpty(),
                        )
                    }
                },
            )
        }
    }

    private suspend fun load() {
        val group = id?.let { groups.get(it) }
        _state.update {
            it.copy(
                editing = id != null,
                name = group?.name.orEmpty(),
                description = group?.description.orEmpty(),
            )
        }
        refreshCandidates(group)
    }

    /**
     * The candidate set: everything in service, plus every asset [group] currently holds an open
     * window on. The second half matters because archiving or retiring a member **Asset** leaves its
     * membership untouched (D-16) — dropping it from the form would silently remove it on the next
     * save, which is exactly the tidy-looking edit the append-only rule exists to prevent.
     *
     * One entry per asset, ordered by name. A name is a label here too: the order is a reading
     * convenience and nothing is ever looked up by it (invariant 7).
     */
    private suspend fun refreshCandidates(group: MaintenanceGroup?) {
        val open = group?.members.orEmpty()
            .filter { it.removedAt == null }
            .associate { it.assetId to it.id }
        val candidates = assets.all()
            .filter { (it.status == AssetStatus.ACTIVE && !it.isRetired) || it.id in open }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id.value }))
            .map {
                GroupEditCandidate(
                    assetId = it.id,
                    name = it.name,
                    selected = it.id in open,
                    membershipId = open[it.id],
                )
            }
        _state.update { it.copy(candidates = candidates, loaded = true) }
    }
}
