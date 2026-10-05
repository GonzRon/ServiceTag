package com.loosecannon.servicetag.ui.setup

import com.loosecannon.servicetag.ui.transfer.transferredOutOr
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.usecase.ArchiveDefinition
import com.loosecannon.servicetag.core.usecase.ArchiveProfile
import com.loosecannon.servicetag.core.usecase.DefinitionReferenced
import com.loosecannon.servicetag.core.usecase.DeleteDefinition
import com.loosecannon.servicetag.core.usecase.DeleteProfile
import com.loosecannon.servicetag.core.usecase.ReorderDefinitions
import com.loosecannon.servicetag.core.usecase.ReorderProfiles
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.l10n.localized
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How long the repository flows stay hot after the last collector leaves (a rotation, typically). */
private const val SUBSCRIPTION_GRACE_MS = 5_000L

/**
 * What one asset measures and what can be logged against it. Both lists are unfiltered and in
 * `sortOrder` — archived rows belong here, because this is the only screen that can bring them
 * back (spec §9). [sourcesById] is every definition of the asset keyed by id, which is how a
 * derived row's formula line names its two sources without the screen doing lookups of its own.
 */
data class AssetSetupState(
    val assetName: String,
    val definitions: List<MeasurementDefinition> = emptyList(),
    val profiles: List<EventProfile> = emptyList(),
    val sourcesById: Map<DefinitionId, MeasurementDefinition> = emptyMap(),
)

/**
 * The Readings & actions screen. Every action is one use case and one `viewModelScope` launch; the
 * screen never reaches past this state and these callbacks.
 *
 * Two refusals are treated differently on purpose. [DefinitionReferenced] is the one a person has
 * to act on — it names readings and quick actions they would have to change first — so it becomes
 * [refusal], a dialog the screen holds open. Everything else is one line on [messages]: a reorder
 * or archive that loses a race with a backup import is worth saying once, not worth a dialog.
 */
class AssetSetupViewModel(
    assets: AssetRepository,
    definitions: DefinitionRepository,
    profiles: ProfileRepository,
    private val archiveDefinition: ArchiveDefinition,
    private val deleteDefinition: DeleteDefinition,
    private val reorderDefinitions: ReorderDefinitions,
    private val archiveProfile: ArchiveProfile,
    private val deleteProfile: DeleteProfile,
    private val reorderProfiles: ReorderProfiles,
    private val assetId: AssetId,
) : ViewModel() {

    constructor(graph: AppGraph, assetId: String) : this(
        graph.assets, graph.definitions, graph.profiles,
        graph.archiveDefinition, graph.deleteDefinition, graph.reorderDefinitions,
        graph.archiveProfile, graph.deleteProfile, graph.reorderProfiles,
        AssetId(assetId),
    )

    private val asset = assets.observeAll().map { rows -> rows.firstOrNull { it.id == assetId } }

    /** Null while the asset is unknown; the repository already orders both lists by `sortOrder`. */
    val state: StateFlow<AssetSetupState?> = combine(
        asset,
        definitions.observeForAsset(assetId),
        profiles.observeForAsset(assetId),
    ) { row, definitionRows, profileRows ->
        row?.let {
            AssetSetupState(
                assetName = it.name,
                definitions = definitionRows,
                profiles = profileRows,
                sourcesById = definitionRows.associateBy(MeasurementDefinition::id),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), null)

    val missing: StateFlow<Boolean> = asset
        .map { it == null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), false)

    private val _refusal = MutableStateFlow<DefinitionReferenced?>(null)

    /** The one refusal the screen renders as a dialog, because it is a list of things to fix. */
    val refusal: StateFlow<DefinitionReferenced?> = _refusal.asStateFlow()

    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun dismissRefusal() { _refusal.value = null }

    /**
     * One row up or down, which is what the overflow offers instead of a drag (recorded ruling).
     * The whole order is sent, so a partial list can never be read as "these first, rest behind".
     * A row already at the end of its list moves nowhere and writes nothing.
     */
    fun moveDefinition(id: DefinitionId, up: Boolean) {
        val ordered = state.value?.definitions?.map(MeasurementDefinition::id) ?: return
        reorderDefinitions(ordered.movedBy(id, up) ?: return)
    }

    fun moveProfile(id: ProfileId, up: Boolean) {
        val ordered = state.value?.profiles?.map(EventProfile::id) ?: return
        reorderProfiles(ordered.movedBy(id, up) ?: return)
    }

    fun reorderDefinitions(orderedIds: List<DefinitionId>) = attempt(R.string.setup_failed_reorder_readings) {
        reorderDefinitions.run(assetId, orderedIds)
    }

    fun reorderProfiles(orderedIds: List<ProfileId>) = attempt(R.string.setup_failed_reorder_actions) {
        reorderProfiles.run(assetId, orderedIds)
    }

    fun archiveDefinition(id: DefinitionId, archived: Boolean) = attempt(R.string.setup_failed_change_reading) {
        archiveDefinition.run(id, archived)
    }

    fun archiveProfile(id: ProfileId, archived: Boolean) = attempt(R.string.setup_failed_change_action) {
        archiveProfile.run(id, archived)
    }

    /**
     * Deletes a reading nothing points at. [DefinitionReferenced] is not an error the user typed
     * their way into, so it is handed back whole on [refusal] rather than flattened to a sentence:
     * the dialog can then say how many entries, which derived readings and which actions.
     */
    fun deleteDefinition(id: DefinitionId) {
        viewModelScope.launch {
            val outcome = runCatching { deleteDefinition.run(id) }
            when (val failure = outcome.exceptionOrNull()) {
                null -> Unit
                is DefinitionReferenced -> _refusal.value = failure
                else -> _messages.tryEmit(failure.transferredOutOr(localized(R.string.setup_failed_delete_that_reading)))
            }
        }
    }

    fun deleteProfile(id: ProfileId) = attempt(R.string.setup_failed_delete_that_action) { deleteProfile.run(id) }

    /**
     * Every action but the delete refusal: do it, and say one line if it could not be done. The line is read
     * when it is said, in the language of that moment (#102).
     */
    private fun attempt(@StringRes onFailure: Int, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.exceptionOrNull()?.let {
                _messages.tryEmit(it.transferredOutOr(localized(onFailure)))
            }
        }
    }
}

/**
 * [id] swapped with the neighbour above (or below) it, or null when there is no such neighbour —
 * which is how the top row's "Move up" becomes a no-op instead of a write that changes nothing.
 */
private fun <T> List<T>.movedBy(id: T, up: Boolean): List<T>? {
    val from = indexOf(id).takeIf { it >= 0 } ?: return null
    val to = if (up) from - 1 else from + 1
    if (to !in indices) return null
    return toMutableList().apply { this[from] = this[to].also { this[to] = id } }
}
