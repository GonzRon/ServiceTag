package com.loosecannon.servicetag.ui.journal

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.journal.Derived
import com.loosecannon.servicetag.core.journal.RangeState
import com.loosecannon.servicetag.core.journal.Reading
import com.loosecannon.servicetag.core.journal.classify
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.Measurement
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ProfileConsumable
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.usecase.ConsumableInput
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.core.usecase.EventOwnership
import com.loosecannon.servicetag.core.usecase.EventValidation
import com.loosecannon.servicetag.core.usecase.FieldProblem
import com.loosecannon.servicetag.core.usecase.LogEvent
import com.loosecannon.servicetag.core.usecase.NoSuchEvent
import com.loosecannon.servicetag.core.usecase.UpdateEvent
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.condition.EventOffer
import com.loosecannon.servicetag.ui.condition.EventOffers
import com.loosecannon.servicetag.ui.condition.tapped
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException

/**
 * The entry route's state: the field test sheet of G1 §1.3, one row per profile field, plus the
 * materials that went in and one free note. The form is driven entirely by the profile's fields
 * and each definition's [ValueType] — nothing here knows what a hot tub is.
 */

/** One value row. [problem] is only ever set by a refused save, so nothing is red before a try. */
data class FieldRow(
    val definition: MeasurementDefinition,
    val required: Boolean,
    val text: String,
    val problem: FieldProblem?,
) {
    /** The badge while typing: a number reads against its target the moment it parses. */
    val liveState: RangeState?
        get() = if (definition.valueType == ValueType.NUMBER) {
            text.trim().toDoubleOrNull()?.let { classify(it, definition.rangeLow, definition.rangeHigh) }
        } else {
            null
        }
}

/** One material line as typed. Quantity stays text until the use case parses it. */
data class ConsumableRow(
    val name: String,
    val quantity: String,
    val unit: String,
    val problem: Boolean = false,
)

data class EventEntryState(
    val assetName: String = "",
    val profileName: String = "",
    val title: String = "",
    val occurredOn: String,
    val occurredTime: String?,
    val fields: List<FieldRow> = emptyList(),
    /**
     * The asset's DERIVED readings, recomputed from [fields] on every keystroke (spec §5). Read-only
     * rows under the inputs: never entered, never stored, and "—" until this entry's own values can
     * produce them.
     */
    val derivedRows: List<Reading> = emptyList(),
    val suggestions: List<ProfileConsumable> = emptyList(),
    val consumables: List<ConsumableRow> = emptyList(),
    val notes: String = "",
    val editing: Boolean = false,
    val saving: Boolean = false,
    /** The one line the screen says out loud when a save is refused; null while nothing is wrong. */
    val firstProblem: String? = null,
    val loaded: Boolean = false,
    /**
     * 1.4: the question a just-logged event asks before the screen leaves — "Mark operational?" or
     * the season offer (spec §3.3, §5.4). Null while there is none. The entry is already saved; this
     * only asks, and only its accept writes.
     */
    val offer: EventOffer? = null,
)

/**
 * The title a preset kind opens with (spec §7). One word, and editable like any other title: the
 * entry is the user's, and the preset is only there so the common case needs no typing.
 */
private fun presetTitle(kind: EventKind): String =
    kind.name.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' ')

/**
 * New entry ([eventId] null) or edit of a stored one. A new entry takes its rows from the profile;
 * an edit takes them from the event's own profile, falling back to every unarchived definition of
 * the asset as optional rows when the event was logged without one.
 *
 * Validation belongs to the use cases: [save] sends what was typed and maps the [FieldProblem]s
 * that come back onto the rows they belong to. The form never second-guesses the domain.
 */
class EventEntryViewModel(
    private val assets: AssetRepository,
    private val definitions: DefinitionRepository,
    private val profiles: ProfileRepository,
    private val events: EventRepository,
    private val logEvent: LogEvent,
    private val updateEvent: UpdateEvent,
    private val clock: Clock,
    private val assetId: AssetId,
    private val profileId: ProfileId?,
    private val eventId: EventId?,
    /**
     * The kind a *new, profile-less* entry opens with — the retirement follow-on of spec §7 asks
     * for a REPLACEMENT or a NOTE. A profile always wins, because its kind is the one the action
     * was set up to log; an edit always keeps the kind it was logged with.
     */
    private val presetKind: EventKind? = null,
    /**
     * 1.4: the offers a **newly logged** event may make (spec §3.3, §5.4) — the graph's one
     * [EventOffers]. An edit never offers: S19 and S53 say "You logged", and an edit logged nothing
     * new.
     */
    private val offers: EventOffers,
) : ViewModel() {

    constructor(
        graph: AppGraph,
        assetId: String,
        profileId: String?,
        eventId: String?,
        kind: String? = null,
    ) : this(
        graph.assets, graph.definitions, graph.profiles, graph.events,
        graph.logEvent, graph.updateEvent, graph.clock,
        AssetId(assetId), profileId?.let(::ProfileId), eventId?.let(::EventId),
        kind?.let { name -> runCatching { EventKind.valueOf(name) }.getOrNull() },
        graph.eventOffers,
    )

    /** The zone the entry is being made in; stored on the event as `tzId` for the audit trail. */
    private val zone: ZoneId = ZoneId.systemDefault()

    /** The offers a just-logged event still has to ask after the open one, in order. */
    private var laterOffers: List<EventOffer> = emptyList()

    /** Set once the event is loaded, so an edit's save carries the kind it was logged with. */
    private var kind: EventKind = EventKind.NOTE

    /** The profile the save names, which in edit mode is the event's own, not the route's. */
    private var commandProfileId: ProfileId? = profileId

    /**
     * Every definition of the asset, keyed by id: the `sources` argument [Derived.compute] resolves
     * A and B against. Loaded once, because a definition edit does not run behind an open form.
     */
    private var sources: Map<DefinitionId, MeasurementDefinition> = emptyMap()

    /** The asset's unarchived DERIVED definitions, in `sortOrder` — the read-only rows of §5. */
    private var derivedDefinitions: List<MeasurementDefinition> = emptyList()

    private val _state = MutableStateFlow(
        EventEntryState(
            occurredOn = clock.nowMillis().at(zone).toLocalDate().format(DATE),
            occurredTime = clock.nowMillis().at(zone).toLocalTime().format(TIME),
            editing = eventId != null,
        ),
    )
    val state: StateFlow<EventEntryState> = _state.asStateFlow()

    /** One shot per successful save: the screen that started it pops, a later visitor is not told. */
    private val _saved = MutableSharedFlow<EventId>(replay = 0, extraBufferCapacity = 1)
    val saved: SharedFlow<EventId> = _saved.asSharedFlow()

    init {
        viewModelScope.launch {
            val assetName = assets.get(assetId)?.name.orEmpty()
            val existing = eventId?.let { events.get(it) }
            val profile = (existing?.profileId ?: profileId)?.let { profiles.get(it) }
            commandProfileId = profile?.id
            kind = existing?.kind ?: profile?.eventKind ?: presetKind ?: EventKind.NOTE
            val all = definitions.forAsset(assetId)
            sources = all.associateBy(MeasurementDefinition::id)
            derivedDefinitions = all
                .filter { it.kind == DefinitionKind.DERIVED && it.archivedAt == null }
                .sortedBy { it.sortOrder }
            val fields = rows(profile, existing)
            _state.update { current ->
                current.copy(
                    assetName = assetName,
                    profileName = profile?.name.orEmpty(),
                    title = existing?.title
                        ?: profile?.defaultTitle
                        ?: presetKind?.let(::presetTitle).orEmpty(),
                    occurredOn = existing?.occurredOn ?: current.occurredOn,
                    occurredTime = if (existing != null) existing.occurredTime else current.occurredTime,
                    fields = fields,
                    derivedRows = derivedRows(fields),
                    suggestions = profile?.consumables.orEmpty(),
                    consumables = existing?.consumables.orEmpty().map {
                        ConsumableRow(it.name, formatNumber(it.quantity), it.unit)
                    },
                    notes = existing?.notes.orEmpty(),
                    loaded = true,
                )
            }
        }
    }

    /**
     * The profile's fields in `sortOrder`, or — for an event logged without a profile — every
     * unarchived definition of the asset as an optional row.
     *
     * An edit then adds a row for anything the stored event measured that neither list covers: an
     * imported event whose profile has since changed, or a definition archived after the event was
     * logged. Without that row the value would be invisible on the form and, because [save] submits
     * the rows and nothing else, silently dropped by the edit. An edit must not delete a reading
     * the user never saw.
     *
     * Two definitions never become rows at all (spec §9): a DERIVED one, which is computed and not
     * entered, and an archived one the edited event does not already carry a value for — a profile
     * that still names a retired reading must not go on asking for it.
     */
    private suspend fun rows(profile: EventProfile?, existing: AssetEvent?): List<FieldRow> {
        val fields: List<Pair<DefinitionId, Boolean>> = profile
            ?.fields
            ?.sortedBy { it.sortOrder }
            ?.map { it.definitionId to it.required }
            ?: definitions.forAsset(assetId)
                .filter { it.archivedAt == null }
                .sortedBy { it.sortOrder }
                .map { it.id to false }

        val covered = fields.map { it.first }.toSet()
        val carried = existing?.measurements.orEmpty()
            .map { it.definitionId }
            .filterNot { it in covered }
            .distinct()
            .mapNotNull { id -> definitions.get(id)?.let { id to it.sortOrder } }
            .sortedBy { it.second }
            .map { it.first to false }

        return (fields + carried).mapNotNull { (id, required) ->
            val definition = definitions.get(id) ?: return@mapNotNull null
            if (definition.kind == DefinitionKind.DERIVED) return@mapNotNull null
            val measurement = existing?.measurements?.firstOrNull { it.definitionId == id }
            if (definition.archivedAt != null && measurement == null) return@mapNotNull null
            FieldRow(definition, required, measurement.asText(definition), problem = null)
        }
    }

    /**
     * The derived readings of this entry as it stands, from the values typed so far. The rows go to
     * [Derived.compute] as a throwaway [AssetEvent] carrying one measurement per filled NUMBER row,
     * because same-event semantics are the whole rule (spec §5): the form must never mix a number
     * being typed with a stored one from another entry.
     *
     * Nothing here is written anywhere and nothing but `measurements` is read off the event, so the
     * rest of it is filled in as what it is — an entry that does not exist.
     */
    private fun derivedRows(fields: List<FieldRow>): List<Reading> {
        if (derivedDefinitions.isEmpty()) return emptyList()
        val typed = fields.mapIndexedNotNull { index, row ->
            if (row.definition.valueType != ValueType.NUMBER) return@mapIndexedNotNull null
            val value = row.text.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: return@mapIndexedNotNull null
            Measurement(
                id = "",
                definitionId = row.definition.id,
                valueNum = value,
                valueText = null,
                unit = row.definition.unit,
                sortOrder = index,
            )
        }
        val unsaved = AssetEvent(
            id = EventId(""),
            assetId = assetId,
            kind = kind,
            title = "",
            profileId = commandProfileId,
            occurredOn = "",
            occurredTime = null,
            tzId = zone.id,
            notes = "",
            source = EventSource.MANUAL,
            sourceRef = null,
            createdAt = 0L,
            updatedAt = 0L,
            measurements = typed,
            consumables = emptyList(),
        )
        return derivedDefinitions.map { definition ->
            val value = Derived.compute(definition, unsaved, sources)
            Reading(
                definition = definition,
                measurement = null,
                occurredOn = null,
                occurredTime = null,
                state = value?.let { classify(it, definition.rangeLow, definition.rangeHigh) },
                derivedValue = value,
            )
        }
    }

    fun onTitle(value: String) = _state.update { it.copy(title = value, firstProblem = null) }

    fun onDate(value: String) = _state.update { it.copy(occurredOn = value, firstProblem = null) }

    fun onTime(value: String?) = _state.update { it.copy(occurredTime = value, firstProblem = null) }

    fun onNotes(value: String) = _state.update { it.copy(notes = value, firstProblem = null) }

    /**
     * Typing in a row clears that row's mark and the line under the app bar, as 1C's name field
     * does, and recomputes the derived rows — they are a view of what is typed, so they follow the
     * keystroke rather than the save.
     */
    fun onValue(definitionId: DefinitionId, value: String) = _state.update { current ->
        val fields = current.fields.map { row ->
            if (row.definition.id == definitionId) row.copy(text = value, problem = null) else row
        }
        current.copy(fields = fields, derivedRows = derivedRows(fields), firstProblem = null)
    }

    /** A suggestion is a head start, not an entry: it arrives with its unit and an open quantity. */
    fun addSuggested(suggestion: ProfileConsumable) = _state.update { current ->
        current.copy(
            consumables = current.consumables + ConsumableRow(
                name = suggestion.name,
                quantity = suggestion.defaultQuantity?.let(::formatNumber).orEmpty(),
                unit = suggestion.unit,
            ),
            firstProblem = null,
        )
    }

    fun addBlankConsumable() = _state.update { current ->
        current.copy(consumables = current.consumables + ConsumableRow("", "", ""), firstProblem = null)
    }

    fun onConsumable(index: Int, name: String? = null, quantity: String? = null, unit: String? = null) =
        _state.update { current ->
            current.copy(
                consumables = current.consumables.mapIndexed { i, row ->
                    if (i != index) {
                        row
                    } else {
                        row.copy(
                            name = name ?: row.name,
                            quantity = quantity ?: row.quantity,
                            unit = unit ?: row.unit,
                            problem = false,
                        )
                    }
                },
                firstProblem = null,
            )
        }

    fun removeConsumable(index: Int) = _state.update { current ->
        current.copy(
            consumables = current.consumables.filterIndexed { i, _ -> i != index },
            firstProblem = null,
        )
    }

    /**
     * Logs or edits, then names the event the screen should leave for, once, on [saved]. The write
     * runs in `viewModelScope` so a rotation halfway through cannot abandon it with `saving` stuck
     * true, and the guard is set before the first suspension, so two taps in one frame log one
     * event rather than two.
     */
    fun save() {
        val state = _state.value
        if (state.saving || !state.loaded) return
        _state.update { it.copy(saving = true, firstProblem = null) }
        viewModelScope.launch {
            val form = _state.value
            val submitted = form.consumables.submitted()
            val cmd = EventCommand(
                assetId = assetId,
                profileId = commandProfileId,
                kind = kind,
                title = form.title,
                occurredOn = form.occurredOn,
                occurredTime = form.occurredTime?.takeIf { it.isNotBlank() },
                tzId = zone.id,
                notes = form.notes,
                values = form.fields
                    .filter { it.text.isNotBlank() }
                    .associate { it.definition.id to it.text },
                consumables = submitted.map { it.second },
            )
            // Caught by name, not by runCatching: a cancelled `viewModelScope` must stay cancelled
            // rather than be reported to the user as a refused save.
            try {
                val event = if (eventId == null) logEvent.run(cmd) else updateEvent.run(eventId, cmd)
                val asked = if (eventId == null) offers.offersAfter(event) else emptyList()
                if (asked.isEmpty()) {
                    _state.update { it.copy(saving = false) }
                    _saved.tryEmit(event.id)
                } else {
                    // Saved, and its questions before the screen leaves, one at a time. `saving`
                    // stays set, so the form cannot log the entry again while a question is open.
                    laterOffers = asked.drop(1)
                    _state.update { it.copy(offer = asked.first()) }
                }
            } catch (e: EventValidation) {
                markProblems(e, submitted.map { it.first })
            } catch (e: EventOwnership) {
                refuse(e)
            } catch (e: NoSuchEvent) {
                refuse(e)
            }
        }
    }

    /**
     * The offer's accept: marked as tapped **before** the write — which disables its buttons — and
     * refused on a second tap, so a double tap writes one row. Then the next offer is asked, or the
     * screen leaves, as it would have without the offer. A refused accept has no ratified sentence;
     * the entry stands either way.
     */
    fun acceptOffer() {
        val open = _state.value.offer ?: return
        if (open.accepting) return
        _state.update { it.copy(offer = open.tapped()) }
        viewModelScope.launch {
            try {
                offers.accept(open)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: Exception) {
                Log.w(TAG, "an accepted offer was refused", refused)
            }
            nextAfter(open)
        }
    }

    /** "Not yet" or "Not now": nothing is written, and the next offer is asked or the screen leaves. */
    fun declineOffer() {
        val open = _state.value.offer ?: return
        if (open.accepting) return
        nextAfter(open)
    }

    private fun nextAfter(offer: EventOffer) {
        val next = laterOffers.firstOrNull()
        laterOffers = laterOffers.drop(1)
        if (next != null) {
            _state.update { it.copy(offer = next) }
        } else {
            _state.update { it.copy(offer = null, saving = false) }
            _saved.tryEmit(offer.event.id)
        }
    }

    /** Puts every [FieldProblem] back on the row it belongs to and names the first one out loud. */
    private fun markProblems(failure: EventValidation, submittedRows: List<Int>) {
        val byDefinition = failure.problems.mapNotNull { p -> p.definitionId?.let { it to p } }.toMap()
        val badConsumables = failure.problems
            .filterIsInstance<FieldProblem.BadConsumable>()
            .mapNotNull { submittedRows.getOrNull(it.index) }
            .toSet()
        _state.update { current ->
            val fields = current.fields.map { it.copy(problem = byDefinition[it.definition.id]) }
            current.copy(
                saving = false,
                fields = fields,
                consumables = current.consumables.mapIndexed { i, row -> row.copy(problem = i in badConsumables) },
                firstProblem = failure.problems.firstProblemText(fields),
            )
        }
    }

    /**
     * The two failures no amount of retyping fixes: the event moved, or the profile or definition
     * the form names is not this asset's any more. Say so once and leave the form as it was typed.
     */
    private fun refuse(cause: Throwable) {
        val line = if (cause is NoSuchEvent) "This entry is no longer there." else CANNOT_SAVE
        _state.update { it.copy(saving = false, firstProblem = line) }
    }

    /**
     * A row that has not been touched at all is not a material the user forgot to fill in — it is
     * one they added and changed their mind about, so it never reaches validation. The row's own
     * index travels with it, so [FieldProblem.BadConsumable] still marks the right line.
     */
    private fun List<ConsumableRow>.submitted(): List<Pair<Int, ConsumableInput>> = withIndex()
        .filterNot { (_, row) -> row.name.isBlank() && row.quantity.isBlank() && row.unit.isBlank() }
        .map { (index, row) -> index to ConsumableInput(row.name, row.quantity, row.unit) }

    private companion object {
        const val TAG = "EventEntry"
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

private fun Long.at(zone: ZoneId) = Instant.ofEpochMilli(this).atZone(zone)

/**
 * The problems that are not about a single row name themselves; anything else is a row, and the
 * first of those is named by its definition's label ("pH is required"). A problem that lands on
 * no visible row still gets a line — a refusal must never be silent.
 */
private fun List<FieldProblem>.firstProblemText(fields: List<FieldRow>): String {
    firstNotNullOfOrNull { problem ->
        when (problem) {
            is FieldProblem.BadDate -> "Enter a date as YYYY-MM-DD"
            is FieldProblem.BadTime -> "Enter a time as HH:MM"
            FieldProblem.TitleRequired -> "Give the entry a title"
            is FieldProblem.BadConsumable -> "Check material ${problem.index + 1}"
            else -> null
        }
    }?.let { return it }

    val row = fields.firstOrNull { it.problem != null } ?: return CANNOT_SAVE
    return when (row.problem) {
        is FieldProblem.Required -> "${row.definition.label} is required"
        is FieldProblem.NotANumber -> "${row.definition.label} is not a number"
        else -> CANNOT_SAVE
    }
}

/** The line for a refusal no row can explain. */
private const val CANNOT_SAVE = "Could not save this entry."


/**
 * A stored value back as the text that produced it — the entry field holds what was typed, not a
 * formatted reading, so an edit that changes nothing else re-saves the same number.
 */
private fun Measurement?.asText(definition: MeasurementDefinition): String {
    val m = this ?: return ""
    return when (definition.valueType) {
        ValueType.TEXT -> m.valueText.orEmpty()
        ValueType.BOOLEAN -> if (m.valueNum == 1.0) "1" else "0"
        ValueType.NUMBER -> m.valueNum?.let { value ->
            if (definition.decimals == 0) formatNumber(value) else value.toString()
        }.orEmpty()
    }
}
