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
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.usecase.ArchiveProfile
import com.loosecannon.servicetag.core.usecase.BadFieldCause
import com.loosecannon.servicetag.core.usecase.DeleteProfile
import com.loosecannon.servicetag.core.usecase.ProfileCommand
import com.loosecannon.servicetag.core.usecase.ProfileConsumableInput
import com.loosecannon.servicetag.core.usecase.ProfileFieldInput
import com.loosecannon.servicetag.core.usecase.ProfileProblem
import com.loosecannon.servicetag.core.usecase.ProfileValidation
import com.loosecannon.servicetag.core.usecase.SaveProfile
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.l10n.parseLocalizedDecimal
import com.loosecannon.servicetag.ui.supplies.SUPPLY_ITEM_GONE
import com.loosecannon.servicetag.ui.supplies.SupplyListRow
import com.loosecannon.servicetag.ui.supplies.listRowsOf
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
 *
 * [supplyId] is the line's SupplyItem link (#15, C19): loaded with the stored row, kept through every edit of the
 * row's words, and sent back on the save, so a re-save never clears it. A row someone adds starts unlinked; a pick
 * from the picker links it and the remove action (P15-23) unlinks it (C34: [ProfileEditViewModel.linkSupply],
 * [ProfileEditViewModel.unlinkSupply]), and the editor draws it under the row as `SupplyLinkLine`. It has no default,
 * so a row built without saying what its link is does not compile; nothing here derives it from [name] (C37).
 */
data class ConsumableEdit(
    val id: String?,
    val name: String = "",
    val quantity: String = "",
    val unit: String = "",
    val supplyId: SupplyId?,
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
 *
 * #15 (C34): [supplyChoices] is what the SupplyItem picker may offer — the unarchived SupplyItems only, in the
 * Supplies list's order (R15-6; the filter is this host's). [supplies] is every SupplyItem, archived included, by
 * id: what a linked row's line names and marks archived. Both follow the catalog live.
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
    val supplyChoices: List<SupplyListRow> = emptyList(),
    val supplies: Map<SupplyId, SupplyListRow> = emptyMap(),
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
    private val supplyItems: SupplyItemRepository,
    private val saveProfile: SaveProfile,
    private val archiveProfile: ArchiveProfile,
    private val deleteProfile: DeleteProfile,
    private val assetId: AssetId,
    private val profileId: ProfileId?,
) : ViewModel() {

    constructor(graph: AppGraph, assetId: String, profileId: String?) : this(
        graph.profiles, graph.definitions, graph.assets, graph.supplyItems,
        graph.saveProfile, graph.archiveProfile, graph.deleteProfile,
        AssetId(assetId), profileId?.let(::ProfileId),
    )

    /** Every definition of the asset, so a chosen field can name itself even once archived. */
    private var known: List<MeasurementDefinition> = emptyList()

    /** #15 (C34): the catalog as last read, by id — where a pick looks its item up for the unit it may fill. */
    private var catalog: Map<SupplyId, SupplyItem> = emptyMap()

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
        viewModelScope.launch {
            supplyItems.observeAll().collect { all ->
                catalog = all.associateBy { it.id }
                _state.update { form ->
                    form.copy(
                        supplyChoices = listRowsOf(all.filter { it.archivedAt == null }),
                        supplies = listRowsOf(all).associateBy { it.id },
                    )
                }
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
                        supplyId = it.supplyId,
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
        form.copy(consumables = form.consumables + ConsumableEdit(id = null, supplyId = null))
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
     * #15 (C34): links row [index] to the SupplyItem the picker reported, looked up by [id] in this editor's own
     * catalog. Besides the link, a pick fills the row's name only while it is blank and its unit — from the item's
     * preferred unit — only while it is blank: nothing typed is overwritten and the quantity is never touched. An id
     * the catalog does not hold (the picker never offers one) links nothing. Like typing, it clears the row's mark.
     */
    fun linkSupply(index: Int, id: SupplyId) = clearing(ProfileForm.consumable(index)) { form ->
        val item = catalog[id] ?: return@clearing form
        form.copy(
            consumables = form.consumables.mapIndexed { i, row ->
                if (i != index) {
                    row
                } else {
                    row.copy(
                        name = row.name.ifBlank { item.name },
                        unit = row.unit.ifBlank { item.preferredUnit },
                        supplyId = item.id,
                    )
                }
            },
        )
    }

    /**
     * #15 (C34): the remove action (P15-23) — row [index] loses its link and nothing else; its words stay as they are. Like any
     * edit of the row, it clears the row's mark.
     */
    fun unlinkSupply(index: Int) = clearing(ProfileForm.consumable(index)) { form ->
        form.copy(
            consumables = form.consumables.mapIndexed { i, row -> if (i == index) row.copy(supplyId = null) else row },
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
            // In the owner's decimal separator, as the field drew it (#102).
            val value = parseLocalizedDecimal(text)
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
                else -> _messages.tryEmit(failure.transferredOutOr(localized(R.string.setup_failed_save_action)))
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
                _messages.tryEmit(
                    archiving.exceptionOrNull()!!.transferredOutOr(localized(R.string.setup_failed_change_action)),
                )
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
                _messages.tryEmit(
                    deleting.exceptionOrNull()!!.transferredOutOr(localized(R.string.setup_failed_delete_this_action)),
                )
            }
        }
    }
}

private const val CONSUMABLE_PREFIX = "consumable-"

/** Under a material row whose quantity does not parse; read when it is said (#102). */
private val QUANTITY_COPY: String get() = localized(R.string.setup_problem_quantity_not_number)

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
            supplyId = row.supplyId,
        )
    },
)

/** The wording under a control. Only [ProfileValidation] lands here; the rest is said out loud. */
private fun Throwable?.asProblems(): Map<String, String> {
    val validation = this as? ProfileValidation ?: return emptyMap()
    return validation.problems.associate { problem ->
        when (problem) {
            ProfileProblem.NameRequired -> ProfileForm.NAME to localized(R.string.setup_problem_action_name_required)
            ProfileProblem.NameTaken -> ProfileForm.NAME to localized(R.string.setup_problem_action_name_taken)
            // What is wrong with the field it names; the list is one control, so it is said once under the
            // header rather than per row. The cause is a code: its words are the owner's language (#102).
            is ProfileProblem.BadField -> ProfileForm.FIELDS to badFieldWords(problem.cause)
            is ProfileProblem.BadConsumable ->
                ProfileForm.consumable(problem.index) to localized(R.string.setup_problem_bad_material)
            // #15 (C20, C-2): the line's SupplyItem is gone — P15-20, reused verbatim.
            is ProfileProblem.UnknownSupplyItem -> ProfileForm.consumable(problem.index) to SUPPLY_ITEM_GONE
        }
    }
}

/** #102: a field the use case refused, in the owner's language. English keeps the use case's original wording. */
private fun badFieldWords(cause: BadFieldCause): String = when (cause) {
    BadFieldCause.NOT_THIS_ASSET -> localized(R.string.setup_problem_field_not_this_asset)
    BadFieldCause.DERIVED -> localized(R.string.setup_problem_field_derived)
    BadFieldCause.ARCHIVED -> localized(R.string.setup_problem_field_archived)
    BadFieldCause.LISTED_TWICE -> localized(R.string.setup_problem_field_listed_twice)
}
