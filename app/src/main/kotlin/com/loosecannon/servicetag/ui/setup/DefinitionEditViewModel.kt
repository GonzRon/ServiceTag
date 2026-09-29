package com.loosecannon.servicetag.ui.setup

import com.loosecannon.servicetag.ui.transfer.transferredOutOr
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.journal.DerivedProblem
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.DerivedFormula
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.usecase.ArchiveDefinition
import com.loosecannon.servicetag.core.usecase.DefinitionCommand
import com.loosecannon.servicetag.core.usecase.DefinitionInUse
import com.loosecannon.servicetag.core.usecase.DefinitionProblem
import com.loosecannon.servicetag.core.usecase.DefinitionReferenced
import com.loosecannon.servicetag.core.usecase.DefinitionValidation
import com.loosecannon.servicetag.core.usecase.DefinitionWouldBreakDerived
import com.loosecannon.servicetag.core.usecase.DefinitionWouldBreakProfiles
import com.loosecannon.servicetag.core.usecase.DeleteDefinition
import com.loosecannon.servicetag.core.usecase.SaveDefinition
import com.loosecannon.servicetag.core.usecase.slugify
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
 * The names [DefinitionEditState.problems] is keyed by — one per field of the form, so a refused
 * save marks the control the problem is actually about (the 2A pattern, one table wider).
 */
object DefinitionField {
    const val LABEL = "label"
    const val KEY = "key"
    const val KIND = "kind"
    const val TYPE = "type"
    const val DECIMALS = "decimals"
    const val RANGE_LOW = "rangeLow"
    const val RANGE_HIGH = "rangeHigh"
    const val METER = "meter"
    const val SOURCES = "sources"
}

/**
 * The definition form, every numeric field held as text until a save parses it — a half-typed
 * "0." or an empty target is a state the form has to be able to sit in.
 *
 * [keyEdited] is what makes the key follow the label: until someone types in the key field the
 * form shows what [slugify] would produce and sends a blank key, so `SaveDefinition` mints and
 * de-duplicates it. After that the typed key is sent verbatim and the label stops driving it.
 *
 * [inUse] is how many stored measurements name this definition. Above zero, `key`, `kind` and
 * `valueType` are how those measurements are read back (spec §6), so the form disables them and
 * says why rather than letting a save be refused.
 */
data class DefinitionEditState(
    val label: String = "",
    val key: String = "",
    val keyEdited: Boolean = false,
    val unit: String = "",
    val kind: DefinitionKind = DefinitionKind.ENTERED,
    val valueType: ValueType = ValueType.NUMBER,
    val decimals: String = DEFAULT_DECIMALS,
    val rangeLow: String = "",
    val rangeHigh: String = "",
    val isMeter: Boolean = false,
    val formula: DerivedFormula = DerivedFormula.PERCENT_DROP,
    val sourceA: DefinitionId? = null,
    val sourceB: DefinitionId? = null,
    val problems: Map<String, String> = emptyMap(),
    val inUse: Int = 0,
    /** The asset's ENTERED, NUMBER, non-meter, unarchived definitions bar this one (spec §4). */
    val sources: List<MeasurementDefinition> = emptyList(),
    /**
     * Every reading and action of the asset by name. The form needs them because the refusals it
     * can hit — a delete something points at, a source change a derived reading depends on — are
     * only useful when they say which reading and which action (spec §9).
     */
    val definitionLabels: Map<DefinitionId, String> = emptyMap(),
    val profileNames: Map<ProfileId, String> = emptyMap(),
    val editing: Boolean = false,
    val archived: Boolean = false,
    val saving: Boolean = false,
    val loaded: Boolean = false,
)

/** One decimal reads right for a pH, a percentage and a temperature; 0–4 is the legal range. */
private const val DEFAULT_DECIMALS = "1"

/** A percentage is the only thing [DerivedFormula.PERCENT_DROP] can produce, so the unit is free. */
private const val PERCENT = "%"

/**
 * Create ([definitionId] null) or edit one reading of an asset. Validation belongs to
 * `SaveDefinition`: [save] sends what was typed and maps whatever comes back onto the field it
 * belongs to. The three refusals that are not about one field — the frozen columns of a definition
 * with data, and the two prospective checks — are said out loud on [messages] instead, because
 * what they name is another row, not this form.
 */
class DefinitionEditViewModel(
    private val definitions: DefinitionRepository,
    private val profiles: ProfileRepository,
    private val events: EventRepository,
    private val saveDefinition: SaveDefinition,
    private val archiveDefinition: ArchiveDefinition,
    private val deleteDefinition: DeleteDefinition,
    private val assetId: AssetId,
    private val definitionId: DefinitionId?,
) : ViewModel() {

    constructor(graph: AppGraph, assetId: String, definitionId: String?) : this(
        graph.definitions, graph.profiles, graph.events,
        graph.saveDefinition, graph.archiveDefinition, graph.deleteDefinition,
        AssetId(assetId), definitionId?.let(::DefinitionId),
    )

    private val _state = MutableStateFlow(DefinitionEditState(editing = definitionId != null))
    val state: StateFlow<DefinitionEditState> = _state.asStateFlow()

    /** One shot per successful save; the screen that started it is told to leave, once. */
    private val _saved = MutableSharedFlow<DefinitionId>(replay = 0, extraBufferCapacity = 1)
    val saved: SharedFlow<DefinitionId> = _saved.asSharedFlow()

    private val _deleted = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val deleted: SharedFlow<Unit> = _deleted.asSharedFlow()

    private val _refusal = MutableStateFlow<DefinitionReferenced?>(null)

    /** A refused delete, rendered as the same dialog the setup screen shows (spec §9). */
    val refusal: StateFlow<DefinitionReferenced?> = _refusal.asStateFlow()

    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            val siblings = definitions.forAsset(assetId)
            val profileRows = profiles.forAsset(assetId)
            val existing = definitionId?.let { definitions.get(it) }
            val used = definitionId?.let { events.countMeasurementsFor(it) } ?: 0
            _state.update { form ->
                val loaded = existing?.let { form.filledFrom(it) } ?: form
                loaded.copy(
                    inUse = used,
                    sources = siblings.filter { it.canBeASource() },
                    definitionLabels = siblings.associate { it.id to it.label },
                    profileNames = profileRows.associate { it.id to it.name },
                    loaded = true,
                )
            }
        }
    }

    /** A definition of this asset that a DERIVED one may read: entered, numeric, live, not this one. */
    private fun MeasurementDefinition.canBeASource(): Boolean =
        id != definitionId &&
            kind == DefinitionKind.ENTERED &&
            valueType == ValueType.NUMBER &&
            !isMeter &&
            archivedAt == null

    private fun DefinitionEditState.filledFrom(row: MeasurementDefinition) = copy(
        label = row.label,
        key = row.key,
        unit = row.unit,
        kind = row.kind,
        valueType = row.valueType,
        decimals = row.decimals.toString(),
        rangeLow = row.rangeLow?.let(::formatNumber).orEmpty(),
        rangeHigh = row.rangeHigh?.let(::formatNumber).orEmpty(),
        isMeter = row.isMeter,
        formula = row.derived?.formula ?: DerivedFormula.PERCENT_DROP,
        sourceA = row.derived?.sourceA,
        sourceB = row.derived?.sourceB,
        archived = row.archivedAt != null,
        editing = true,
    )

    /** Typing in a field clears that field's mark and nothing else — the rest is still wrong. */
    private fun clearing(vararg fields: String, block: (DefinitionEditState) -> DefinitionEditState) =
        _state.update { block(it).copy(problems = it.problems - fields.toSet()) }

    fun onLabel(value: String) = clearing(DefinitionField.LABEL, DefinitionField.KEY) { form ->
        form.copy(label = value, key = if (form.keyEdited) form.key else slugify(value))
    }

    fun onKey(value: String) = clearing(DefinitionField.KEY) {
        it.copy(key = value, keyEdited = true)
    }

    fun onUnit(value: String) = _state.update { it.copy(unit = value) }

    fun onDecimals(value: String) = clearing(DefinitionField.DECIMALS) { it.copy(decimals = value) }

    fun onRangeLow(value: String) =
        clearing(DefinitionField.RANGE_LOW, DefinitionField.RANGE_HIGH) { it.copy(rangeLow = value) }

    fun onRangeHigh(value: String) =
        clearing(DefinitionField.RANGE_LOW, DefinitionField.RANGE_HIGH) { it.copy(rangeHigh = value) }

    /**
     * DERIVED is a NUMBER that is never a meter and is measured in something — a percentage, for
     * the one formula there is — so choosing it fills those in rather than leaving a form that can
     * only be refused.
     */
    fun onKind(value: DefinitionKind) = clearing(DefinitionField.KIND, DefinitionField.TYPE) { form ->
        if (value != DefinitionKind.DERIVED) {
            form.copy(kind = value)
        } else {
            form.copy(
                kind = value,
                valueType = ValueType.NUMBER,
                isMeter = false,
                unit = form.unit.ifBlank { PERCENT },
            )
        }
    }

    /**
     * Decimals, a target and a meter flag only mean something for a NUMBER (spec §6), so they go
     * with it — decimals back to the default rather than blank, because the command still needs a
     * number and the control that would show a bad one is no longer on screen.
     */
    fun onValueType(value: ValueType) =
        clearing(DefinitionField.TYPE, DefinitionField.DECIMALS) { form ->
            if (value == ValueType.NUMBER) {
                form.copy(valueType = value)
            } else {
                form.copy(
                    valueType = value,
                    decimals = DEFAULT_DECIMALS,
                    rangeLow = "",
                    rangeHigh = "",
                    isMeter = false,
                )
            }
        }

    fun onMeter(value: Boolean) = clearing(DefinitionField.METER) { it.copy(isMeter = value) }

    fun onSourceA(value: DefinitionId) = clearing(DefinitionField.SOURCES) { it.copy(sourceA = value) }

    fun onSourceB(value: DefinitionId) = clearing(DefinitionField.SOURCES) { it.copy(sourceB = value) }

    fun dismissRefusal() { _refusal.value = null }

    /**
     * Saves, then names the definition on [saved]. The guard is set before the first suspension, so
     * two taps in one frame write one row. The two numeric text fields are parsed here because the
     * command takes typed values and so cannot report "that is not a number" back to the form.
     */
    fun save() {
        val form = _state.value
        if (form.saving) return

        val local = mutableMapOf<String, String>()
        val decimals = form.decimals.trim().toIntOrNull()
        if (decimals == null) local[DefinitionField.DECIMALS] = DECIMALS_COPY
        val low = form.rangeLow.bound(DefinitionField.RANGE_LOW, local)
        val high = form.rangeHigh.bound(DefinitionField.RANGE_HIGH, local)
        if (decimals == null || local.isNotEmpty()) {
            _state.update { it.copy(problems = it.problems + local) }
            return
        }

        _state.update { it.copy(saving = true, problems = emptyMap()) }
        viewModelScope.launch {
            val outcome = runCatching {
                saveDefinition.run(definitionId, form.command(assetId, decimals, low, high))
            }
            val failure = outcome.exceptionOrNull()
            // What happened is announced before the form is unlocked, so nothing can observe a
            // settled form that has not yet said how the save went.
            when (failure) {
                null -> _saved.tryEmit(outcome.getOrThrow().id)
                is DefinitionValidation -> Unit                  // named under their own fields
                is DefinitionInUse -> _messages.tryEmit(
                    "${failure.measurements} entries already use this reading, " +
                        "so its key, kind and type are fixed.",
                )
                is DefinitionWouldBreakDerived -> _messages.tryEmit(
                    "Used as a source by ${failure.dependentDerivedIds.named(form.definitionLabels)} — " +
                        "change that derived reading first.",
                )
                is DefinitionWouldBreakProfiles -> _messages.tryEmit(
                    "Offered as a field by ${failure.profileIds.named(form.profileNames)} — " +
                        "take it off that action first.",
                )
                else -> _messages.tryEmit(failure.transferredOutOr("Could not save this reading."))
            }
            _state.update { it.copy(saving = false, problems = failure.asProblems()) }
        }
    }

    fun archive(archived: Boolean) {
        viewModelScope.launch {
            val id = definitionId ?: return@launch
            val archiving = runCatching { archiveDefinition.run(id, archived) }
            if (archiving.isSuccess) {
                _state.update { it.copy(archived = archived) }
            } else {
                _messages.tryEmit(archiving.exceptionOrNull()!!.transferredOutOr("Could not change that reading."))
            }
        }
    }

    /** Delete is for a row nothing points at yet; anything else comes back as [refusal]. */
    fun delete() {
        viewModelScope.launch {
            val id = definitionId ?: return@launch
            val outcome = runCatching { deleteDefinition.run(id) }
            when (val failure = outcome.exceptionOrNull()) {
                null -> _deleted.tryEmit(Unit)
                is DefinitionReferenced -> _refusal.value = failure
                else -> _messages.tryEmit(failure.transferredOutOr("Could not delete this reading."))
            }
        }
    }
}

/** A blank target bound is no bound; anything else has to parse, and says so where it does not. */
private fun String.bound(field: String, into: MutableMap<String, String>): Double? {
    val text = trim()
    if (text.isEmpty()) return null
    val value = text.toDoubleOrNull()
    if (value == null) into[field] = "Not a number"
    return value
}

private fun DefinitionEditState.command(
    assetId: AssetId,
    decimals: Int,
    low: Double?,
    high: Double?,
): DefinitionCommand {
    val derived = kind == DefinitionKind.DERIVED
    val numeric = derived || valueType == ValueType.NUMBER
    return DefinitionCommand(
        assetId = assetId,
        // Blank means "generate it" on a create and "leave it alone" on an update (spec §6), which
        // is exactly what a key the user has not touched should do.
        key = if (keyEdited) key.trim() else "",
        label = label,
        unit = unit,
        kind = kind,
        valueType = if (derived) ValueType.NUMBER else valueType,
        decimals = decimals,
        rangeLow = low.takeIf { numeric },
        rangeHigh = high.takeIf { numeric },
        isMeter = isMeter && !derived,
        formula = formula.takeIf { derived },
        sourceA = sourceA.takeIf { derived },
        sourceB = sourceB.takeIf { derived },
    )
}

/** The wording under a field. Only [DefinitionValidation] lands here; the rest is said out loud. */
private fun Throwable?.asProblems(): Map<String, String> {
    val validation = this as? DefinitionValidation ?: return emptyMap()
    // Later problems overwrite earlier ones on the same field, which is fine: the collected list
    // never carries two problems about one field that a person would fix differently.
    return validation.problems.associate { it.fieldName() to it.message() }
}

private const val DECIMALS_COPY = "Decimals must be 0–4"

private fun DefinitionProblem.fieldName(): String = when (this) {
    DefinitionProblem.LabelRequired -> DefinitionField.LABEL
    DefinitionProblem.BadKey, DefinitionProblem.KeyTaken -> DefinitionField.KEY
    DefinitionProblem.BadDecimals -> DefinitionField.DECIMALS
    DefinitionProblem.RangeOrder, DefinitionProblem.RangeOnNonNumber -> DefinitionField.RANGE_LOW
    DefinitionProblem.MeterOnNonNumber -> DefinitionField.METER
    is DefinitionProblem.Derived -> when (p) {
        DerivedProblem.NotNumber -> DefinitionField.TYPE
        DerivedProblem.IsMeter -> DefinitionField.METER
        DerivedProblem.SpecOnEntered -> DefinitionField.KIND
        else -> DefinitionField.SOURCES
    }
}

private fun DefinitionProblem.message(): String = when (this) {
    DefinitionProblem.LabelRequired -> "Give the reading a label"
    DefinitionProblem.BadKey -> "Key: lowercase letters, digits and _ only"
    DefinitionProblem.KeyTaken -> "Another reading already uses this key"
    DefinitionProblem.BadDecimals -> DECIMALS_COPY
    DefinitionProblem.RangeOrder -> "Low must not exceed high"
    DefinitionProblem.RangeOnNonNumber -> "Only a number reading has a target"
    DefinitionProblem.MeterOnNonNumber -> "Only a number reading can be a meter"
    is DefinitionProblem.Derived -> when (p) {
        DerivedProblem.SameSource -> "Choose two different number readings"
        DerivedProblem.MissingSpec -> "Choose two different number readings"
        is DerivedProblem.UnknownSource -> "That reading is no longer there"
        is DerivedProblem.SourceOtherAsset -> "A source must belong to this asset"
        is DerivedProblem.SourceNotEntered -> "A source must be a reading someone enters"
        is DerivedProblem.SourceNotNumber -> "A source must be a number reading"
        is DerivedProblem.SourceIsMeter -> "A meter cannot be a source"
        DerivedProblem.NotNumber -> "A derived reading is a number"
        DerivedProblem.IsMeter -> "A derived reading is not a meter"
        DerivedProblem.SpecOnEntered -> "An entered reading has no formula"
    }
}

/** "Rejection", or "Rejection and Recovery" — what the refusal is actually about, by name. */
private fun <T> List<T>.named(names: Map<T, String>): String {
    val words = map { names[it] ?: "another reading" }
    return when (words.size) {
        0 -> "another reading"
        1 -> words.single()
        else -> words.dropLast(1).joinToString(", ") + " and " + words.last()
    }
}
