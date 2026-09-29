package com.loosecannon.servicetag.ui.setup

import com.loosecannon.servicetag.ui.transfer.transferredOutOr
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.usecase.ArchiveProfile
import com.loosecannon.servicetag.core.usecase.DeleteProfile
import com.loosecannon.servicetag.core.usecase.ProfileCommand
import com.loosecannon.servicetag.core.usecase.ProfileConsumableInput
import com.loosecannon.servicetag.core.usecase.ProfileFieldInput
import com.loosecannon.servicetag.core.usecase.ProfileProblem
import com.loosecannon.servicetag.core.usecase.ProfileValidation
import com.loosecannon.servicetag.core.usecase.SaveProfile
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.journal.formatNumber
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The names [ProfileEditState.problems] is keyed by. The fields are one list rather than one
 * control, so a refused field is named once under the FIELDS header (the use case's reason says
 * which definition it is about); a consumable, which is a row a person can see, is named under
 * itself by index.
 */
object ProfileForm {
    const val NAME = "name"
    const val FIELDS = "fields"

    fun consumable(index: Int): String = "consumable-$index"
}

/** One chosen field: the definition it offers and whether the entry form may be saved without it. */
data class FieldPick(val definition: MeasurementDefinition, val required: Boolean)

/**
 * One consumable suggestion as the form holds it. [id] is the stored row's id when this came out of
 * the database and null for a row someone just added — that is what tells `SaveProfile` a rename is
 * a rename and not a delete plus an insert. The UI never mints one.
 */
data class ConsumableEdit(
    val id: String?,
    val name: String = "",
    val quantity: String = "",
    val unit: String = "",
)

/**
 * The profile form. Quantities are held as text until a save parses them, for the same reason the
 * definition editor holds its targets that way: a half-typed "0." is a state the form must sit in.
 *
 * [titleEdited] is what makes the default title follow the name. Until someone types in the title
 * field the form shows the name and sends a blank title, so `SaveProfile` defaults it; after that
 * the typed title is sent verbatim and the name stops driving it.
 *
 * [available] is what "+ Add field" may offer: the asset's unarchived ENTERED definitions that are
 * not already chosen. A field the profile already carries stays in [fields] even once its
 * definition has been archived (spec §6) — it is shown with a badge rather than dropped.
 */
data class ProfileEditState(
    val assetName: String = "",
    val name: String = "",
    val eventKind: EventKind = EventKind.MAINTENANCE,
    val defaultTitle: String = "",
    val titleEdited: Boolean = false,
    val fields: List<FieldPick> = emptyList(),
    val available: List<MeasurementDefinition> = emptyList(),
    val consumables: List<ConsumableEdit> = emptyList(),
    val problems: Map<String, String> = emptyMap(),
    val editing: Boolean = false,
    val archived: Boolean = false,
    val saving: Boolean = false,
    val loaded: Boolean = false,
)

/**
 * Create ([profileId] null) or edit one quick action of an asset (spec §9). Validation belongs to
 * `SaveProfile`: [save] sends the rows in the order the list shows them and maps whatever comes
 * back onto the control it is about. A delete is always allowed by the domain, so there is no
 * refusal state here — only the confirm the screen puts in front of it.
 */
class ProfileEditViewModel(
    private val profiles: ProfileRepository,
    private val definitions: DefinitionRepository,
    private val assets: AssetRepository,
    private val saveProfile: SaveProfile,
    private val archiveProfile: ArchiveProfile,
    private val deleteProfile: DeleteProfile,
    private val assetId: AssetId,
    private val profileId: ProfileId?,
) : ViewModel() {

    constructor(graph: AppGraph, assetId: String, profileId: String?) : this(
        graph.profiles, graph.definitions, graph.assets,
        graph.saveProfile, graph.archiveProfile, graph.deleteProfile,
        AssetId(assetId), profileId?.let(::ProfileId),
    )

    /** Every definition of the asset, so a chosen field can name itself even once archived. */
    private var known: List<MeasurementDefinition> = emptyList()

    private val _state = MutableStateFlow(ProfileEditState(editing = profileId != null))
    val state: StateFlow<ProfileEditState> = _state.asStateFlow()

    /** One shot per successful save; the screen that started it is told to leave, once. */
    private val _saved = MutableSharedFlow<ProfileId>(replay = 0, extraBufferCapacity = 1)
    val saved: SharedFlow<ProfileId> = _saved.asSharedFlow()

    private val _deleted = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val deleted: SharedFlow<Unit> = _deleted.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            known = definitions.forAsset(assetId)
            val asset = assets.get(assetId)
            val existing = profileId?.let { profiles.get(it) }
            _state.update { form ->
                val loaded = existing?.let { form.filledFrom(it) } ?: form
                loaded.withAvailable().copy(assetName = asset?.name.orEmpty(), loaded = true)
            }
        }
    }

    private fun ProfileEditState.filledFrom(row: EventProfile): ProfileEditState {
        val byId = known.associateBy { it.id }
        return copy(
            name = row.name,
            eventKind = row.eventKind,
            defaultTitle = row.defaultTitle,
            // A title that still reads like the name keeps following it; one that was written by
            // hand does not, so renaming the action does not quietly rewrite its entries' titles.
            titleEdited = row.defaultTitle != row.name,
            fields = row.fields
                .sortedBy { it.sortOrder }
                .mapNotNull { field -> byId[field.definitionId]?.let { FieldPick(it, field.required) } },
            consumables = row.consumables
                .sortedBy { it.sortOrder }
                .map {
                    ConsumableEdit(
                        id = it.id,
                        name = it.name,
                        quantity = it.defaultQuantity?.let(::formatNumber).orEmpty(),
                        unit = it.unit,
                    )
                },
            archived = row.archivedAt != null,
            editing = true,
        )
    }

    /** The picker's contents, recomputed from whatever is chosen right now (spec §9). */
    private fun ProfileEditState.withAvailable(): ProfileEditState {
        val chosen = fields.map { it.definition.id }.toSet()
        return copy(
            available = known.filter {
                it.id !in chosen && it.kind == DefinitionKind.ENTERED && it.archivedAt == null
            },
        )
    }

    /** Typing in a control clears that control's mark and nothing else — the rest is still wrong. */
    private fun clearing(vararg fields: String, block: (ProfileEditState) -> ProfileEditState) =
        _state.update { block(it).copy(problems = it.problems - fields.toSet()) }

    fun onName(value: String) = clearing(ProfileForm.NAME) { form ->
        form.copy(name = value, defaultTitle = if (form.titleEdited) form.defaultTitle else value)
    }

    fun onKind(value: EventKind) = _state.update { it.copy(eventKind = value) }

    fun onTitle(value: String) = _state.update { it.copy(defaultTitle = value, titleEdited = true) }

    fun addField(definitionId: DefinitionId) = clearing(ProfileForm.FIELDS) { form ->
        val definition = known.firstOrNull { it.id == definitionId }
        if (definition == null || form.fields.any { it.definition.id == definitionId }) {
            form
        } else {
            form.copy(fields = form.fields + FieldPick(definition, required = false)).withAvailable()
        }
    }

    fun removeField(definitionId: DefinitionId) = clearing(ProfileForm.FIELDS) { form ->
        form.copy(fields = form.fields.filterNot { it.definition.id == definitionId }).withAvailable()
    }

    /** Position is sort order (spec §6), so moving a row one place is the whole reorder gesture. */
    fun moveField(index: Int, delta: Int) = _state.update { form ->
        val target = index + delta
        if (index !in form.fields.indices || target !in form.fields.indices) {
            form
        } else {
            val reordered = form.fields.toMutableList()
            reordered[index] = form.fields[target]
            reordered[target] = form.fields[index]
            form.copy(fields = reordered)
        }
    }

    fun setRequired(definitionId: DefinitionId, required: Boolean) = _state.update { form ->
        form.copy(
            fields = form.fields.map {
                if (it.definition.id == definitionId) it.copy(required = required) else it
            },
        )
    }

    fun addConsumable() = _state.update { form ->
        form.copy(consumables = form.consumables + ConsumableEdit(id = null))
    }

    fun onConsumable(
        index: Int,
        name: String? = null,
        quantity: String? = null,
        unit: String? = null,
    ) = clearing(ProfileForm.consumable(index)) { form ->
        form.copy(
            consumables = form.consumables.mapIndexed { i, row ->
                if (i != index) {
                    row
                } else {
                    row.copy(
                        name = name ?: row.name,
                        quantity = quantity ?: row.quantity,
                        unit = unit ?: row.unit,
                    )
                }
            },
        )
    }

    /**
     * Removing a row renumbers the ones after it, so every consumable mark is dropped rather than
     * left pointing at whatever moved up into that index.
     */
    fun removeConsumable(index: Int) = _state.update { form ->
        if (index !in form.consumables.indices) {
            form
        } else {
            form.copy(
                consumables = form.consumables.filterIndexed { i, _ -> i != index },
                problems = form.problems.filterKeys { !it.startsWith(CONSUMABLE_PREFIX) },
            )
        }
    }

    /**
     * Saves, then names the profile on [saved]. The guard is set before the first suspension, so
     * two taps in one frame write one row. Quantities are parsed here because the command takes
     * typed values and so cannot report "that is not a number" back to the row it came from.
     */
    fun save() {
        val form = _state.value
        if (form.saving) return

        val local = mutableMapOf<String, String>()
        val quantities = form.consumables.mapIndexed { index, row ->
            val text = row.quantity.trim()
            if (text.isEmpty()) return@mapIndexed null
            val value = text.toDoubleOrNull()
            if (value == null) local[ProfileForm.consumable(index)] = QUANTITY_COPY
            value
        }
        if (local.isNotEmpty()) {
            _state.update { it.copy(problems = it.problems + local) }
            return
        }

        _state.update { it.copy(saving = true, problems = emptyMap()) }
        viewModelScope.launch {
            val outcome = runCatching { saveProfile.run(profileId, form.command(assetId, quantities)) }
            val failure = outcome.exceptionOrNull()
            // What happened is announced before the form is unlocked, so nothing can observe a
            // settled form that has not yet said how the save went.
            when (failure) {
                null -> _saved.tryEmit(outcome.getOrThrow().id)
                is ProfileValidation -> Unit                     // named under their own controls
                else -> _messages.tryEmit(failure.transferredOutOr("Could not save this action."))
            }
            _state.update { it.copy(saving = false, problems = failure.asProblems()) }
        }
    }

    fun archive(archived: Boolean) {
        viewModelScope.launch {
            val id = profileId ?: return@launch
            val archiving = runCatching { archiveProfile.run(id, archived) }
            if (archiving.isSuccess) {
                _state.update { it.copy(archived = archived) }
            } else {
                _messages.tryEmit(archiving.exceptionOrNull()!!.transferredOutOr("Could not change that action."))
            }
        }
    }

    /** A profile is a shortcut, not data (spec §6), so a delete is never refused — only confirmed. */
    fun delete() {
        viewModelScope.launch {
            val id = profileId ?: return@launch
            val deleting = runCatching { deleteProfile.run(id) }
            if (deleting.isSuccess) {
                _deleted.tryEmit(Unit)
            } else {
                _messages.tryEmit(deleting.exceptionOrNull()!!.transferredOutOr("Could not delete this action."))
            }
        }
    }
}

private const val CONSUMABLE_PREFIX = "consumable-"

private const val QUANTITY_COPY = "Quantity must be a number, or empty"

private fun ProfileEditState.command(assetId: AssetId, quantities: List<Double?>) = ProfileCommand(
    assetId = assetId,
    name = name,
    eventKind = eventKind,
    // Blank means "use the name" (spec §6), which is exactly what a title nobody has touched wants.
    defaultTitle = if (titleEdited) defaultTitle else "",
    fields = fields.map { ProfileFieldInput(it.definition.id, it.required) },
    consumables = consumables.mapIndexed { index, row ->
        ProfileConsumableInput(
            id = row.id,
            name = row.name,
            defaultQuantity = quantities[index],
            unit = row.unit,
        )
    },
)

/** The wording under a control. Only [ProfileValidation] lands here; the rest is said out loud. */
private fun Throwable?.asProblems(): Map<String, String> {
    val validation = this as? ProfileValidation ?: return emptyMap()
    return validation.problems.associate { problem ->
        when (problem) {
            ProfileProblem.NameRequired -> ProfileForm.NAME to "Give the action a name"
            ProfileProblem.NameTaken -> ProfileForm.NAME to "Another action already uses this name"
            // The use case's reason already says what is wrong with the field it names; the list is
            // one control, so it is said once under the header rather than per row.
            is ProfileProblem.BadField -> ProfileForm.FIELDS to problem.reason
            is ProfileProblem.BadConsumable ->
                ProfileForm.consumable(problem.index) to
                    "Needs a name, and a quantity of 0 or more"
        }
    }
}
