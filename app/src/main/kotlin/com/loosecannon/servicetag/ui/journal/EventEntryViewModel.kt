package com.loosecannon.servicetag.ui.journal

import com.loosecannon.servicetag.core.transfer.AssetTransferredOut
import com.loosecannon.servicetag.ui.transfer.transferredOutOr
import android.util.Log
import com.loosecannon.servicetag.R
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
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ProfileConsumable
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.usecase.ConditionCommand
import com.loosecannon.servicetag.core.usecase.ConditionProblem
import com.loosecannon.servicetag.core.usecase.ConsumableInput
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.core.usecase.EventOwnership
import com.loosecannon.servicetag.core.usecase.EventValidation
import com.loosecannon.servicetag.core.usecase.FieldProblem
import com.loosecannon.servicetag.core.usecase.IncidentConditionRefused
import com.loosecannon.servicetag.core.usecase.LogEvent
import com.loosecannon.servicetag.core.usecase.NoSuchEvent
import com.loosecannon.servicetag.core.usecase.RecordConditionWithIncident
import com.loosecannon.servicetag.core.usecase.UpdateEvent
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.l10n.localizedDecimal
import com.loosecannon.servicetag.l10n.localizedDecimalSeparator
import com.loosecannon.servicetag.l10n.parseLocalizedDecimal
import com.loosecannon.servicetag.ui.condition.DATE_NOT_LATER_THAN_TODAY
import com.loosecannon.servicetag.ui.condition.EntryOffers
import com.loosecannon.servicetag.ui.condition.EventOffer
import com.loosecannon.servicetag.ui.condition.EventOffers
import com.loosecannon.servicetag.ui.condition.ImpairmentOfferPrompt
import com.loosecannon.servicetag.ui.condition.PendingCondition
import com.loosecannon.servicetag.ui.condition.tapped
import com.loosecannon.servicetag.ui.supplies.SupplyListRow
import com.loosecannon.servicetag.ui.supplies.listRowsOf
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
    /** The badge while typing: a number reads against its target the moment it parses, in the owner's language. */
    val liveState: RangeState?
        get() = if (definition.valueType == ValueType.NUMBER) {
            parseLocalizedDecimal(text)?.let { classify(it, definition.rangeLow, definition.rangeHigh) }
        } else {
            null
        }
}

/**
 * One material line as typed. Quantity stays text until the use case parses it.
 *
 * [supplyId] is the line's SupplyItem link (#15, C19): it arrives with the stored line or with the quick action's
 * chip and stays with its row through every edit of the row's words. It goes to the save with its row — even once
 * all three words are cleared, because a linked row is never taken for an untouched one (`submitted`), so the save
 * names it rather than dropping it and its link unseen. It leaves the row only by the remove action (P15-23,
 * [EventEntryViewModel.unlinkSupply]) or with the row. The form draws it as `SupplyLinkLine` and never makes one
 * (C35, R15-8). It has no default, so a row built without saying what its link is does not compile; nothing here
 * derives it from [name] (C37).
 */
data class ConsumableRow(
    val name: String,
    val quantity: String,
    val unit: String,
    val supplyId: SupplyId?,
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
    /**
     * #15 (C35): every SupplyItem, archived included, by id — what a linked row's line names and marks archived. A
     * link whose item is not here draws no line and is kept.
     */
    val supplies: Map<SupplyId, SupplyListRow> = emptyMap(),
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
    /**
     * #82 (C7): the held DOWN or DEGRADED this entry's Save also records — P82-5 is drawn from it
     * and the asset's name. Null for every entry but the combined flow's.
     */
    val alsoRecords: OperationalCondition? = null,
)

/**
 * The title a preset kind opens with (spec §7). One word, and editable like any other title: the
 * entry is the user's, and the preset is only there so the common case needs no typing. #102: the
 * kind's name in the owner's language ("Season start" for SEASON_START in English); once saved it is
 * the owner's title like any other.
 */
private fun presetTitle(kind: EventKind): String = localized(
    when (kind) {
        EventKind.MAINTENANCE -> R.string.journal_preset_title_maintenance
        EventKind.INSPECTION -> R.string.journal_preset_title_inspection
        EventKind.MEASUREMENT -> R.string.journal_preset_title_measurement
        EventKind.TREATMENT -> R.string.journal_preset_title_treatment
        EventKind.INCIDENT -> R.string.journal_preset_title_incident
        EventKind.REPLACEMENT -> R.string.journal_preset_title_replacement
        EventKind.SEASON_START -> R.string.journal_preset_title_season_start
        EventKind.SEASON_END -> R.string.journal_preset_title_season_end
        EventKind.NOTE -> R.string.journal_preset_title_note
        EventKind.CUSTOM -> R.string.journal_preset_title_custom
    },
)

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
    private val offers: EntryOffers,
    /**
     * #82 (C7, R82-3): Change condition's held DOWN or DEGRADED, when "Log incident details" opened
     * this entry. The entry then opens as a prefilled INCIDENT, and its Save writes the Incident and
     * that row in one transaction through [recordWithIncident] — never [logEvent], never an offer.
     */
    private val pending: PendingCondition? = null,
    private val recordWithIncident: RecordConditionWithIncident? = null,
    /**
     * #15 (C35): the SupplyItem catalog, read only for the names a linked row draws. Production passes the graph's;
     * without one no link line is drawn and every link is still kept and sent.
     */
    private val supplyItems: SupplyItemRepository? = null,
) : ViewModel() {

    init {
        require(pending == null || (recordWithIncident != null && eventId == null)) {
            "a pending condition needs the combined write, and only a new entry carries one" // l10n-ok: exception message
        }
    }

    constructor(
        graph: AppGraph,
        assetId: String,
        profileId: String?,
        eventId: String?,
        kind: String? = null,
        pending: PendingCondition? = null,
    ) : this(
        graph.assets, graph.definitions, graph.profiles, graph.events,
        graph.logEvent, graph.updateEvent, graph.clock,
        AssetId(assetId), profileId?.let(::ProfileId), eventId?.let(::EventId),
        kind?.let { name -> runCatching { EventKind.valueOf(name) }.getOrNull() },
        graph.eventOffers,
        pending,
        graph.recordConditionWithIncident,
        graph.supplyItems,
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
            kind = if (pending != null) EventKind.INCIDENT else existing?.kind ?: profile?.eventKind ?: presetKind ?: EventKind.NOTE
            val draft = pending?.let { incidentDraft(it.reason) }
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
                        ?: draft?.let { it.first ?: presetTitle(EventKind.INCIDENT) }
                        ?: presetKind?.let(::presetTitle).orEmpty(),
                    occurredOn = existing?.occurredOn ?: pending?.occurredOn ?: current.occurredOn,
                    occurredTime = when {
                        existing != null -> existing.occurredTime
                        pending != null -> null
                        else -> current.occurredTime
                    },
                    fields = fields,
                    derivedRows = derivedRows(fields),
                    suggestions = profile?.consumables.orEmpty(),
                    consumables = existing?.consumables.orEmpty().map {
                        ConsumableRow(it.name, formatNumber(it.quantity), it.unit, it.supplyId)
                    },
                    notes = existing?.notes ?: draft?.second.orEmpty(),
                    alsoRecords = pending?.condition,
                    loaded = true,
                )
            }
        }
        supplyItems?.let { catalog ->
            viewModelScope.launch {
                catalog.observeAll().collect { all ->
                    _state.update { it.copy(supplies = listRowsOf(all).associateBy { row -> row.id }) }
                }
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
            val value = parseLocalizedDecimal(row.text)?.takeIf { it.isFinite() }
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

    /**
     * A suggestion is a head start, not an entry: it arrives with its unit, an open quantity and the quick action
     * line's SupplyItem link, if it has one (#15, C19).
     */
    fun addSuggested(suggestion: ProfileConsumable) = _state.update { current ->
        current.copy(
            consumables = current.consumables + ConsumableRow(
                name = suggestion.name,
                quantity = suggestion.defaultQuantity?.let(::formatNumber).orEmpty(),
                unit = suggestion.unit,
                supplyId = suggestion.supplyId,
            ),
            firstProblem = null,
        )
    }

    fun addBlankConsumable() = _state.update { current ->
        current.copy(consumables = current.consumables + ConsumableRow("", "", "", supplyId = null), firstProblem = null)
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

    /**
     * #15 (C35): the remove action (P15-23) — row [index] loses its SupplyItem link and nothing else; its words stay as typed. The
     * form has no way to make a link (R15-8): a row is linked only by the chip it came from or the stored line.
     */
    fun unlinkSupply(index: Int) = _state.update { current ->
        current.copy(
            consumables = current.consumables.mapIndexed { i, row -> if (i == index) row.copy(supplyId = null, problem = false) else row },
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
                // A number goes on as the use case reads it, whatever the owner's decimal separator (#102).
                values = form.fields
                    .filter { it.text.isNotBlank() }
                    .associate {
                        it.definition.id to if (it.definition.valueType == ValueType.NUMBER) neutralNumber(it.text) else it.text
                    },
                consumables = submitted.map { it.second },
            )
            val held = pending
            if (held != null) {
                saveWithCondition(held, cmd, submitted.map { it.first })
                return@launch
            }
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
                markProblems(e.problems, submitted.map { it.first })
            } catch (e: EventOwnership) {
                refuse(e)
            } catch (e: NoSuchEvent) {
                refuse(e)
            } catch (e: AssetTransferredOut) {
                refuse(e)
            }
        }
    }

    /**
     * #82 (C7): the combined flow's Save — the Incident and the held row in one transaction, under
     * the row's pre-allocated id, so a Save repeated after a lost pop writes nothing more (C1). It
     * asks nothing afterwards: the row it wrote already names the Incident. A refused Save writes
     * nothing, and says why in the order the form has always used: a field row's line first, then
     * S25 for a day later than today, then the shipped line for anything no row can explain.
     */
    private suspend fun saveWithCondition(held: PendingCondition, cmd: EventCommand, submittedRows: List<Int>) {
        val writer = checkNotNull(recordWithIncident)
        try {
            val written = writer.run(
                assetId,
                held.id,
                ConditionCommand(
                    condition = held.condition,
                    occurredOn = held.occurredOn,
                    occurredTime = null,
                    tzId = zone.id,
                    reason = held.reason,
                ),
                cmd,
            )
            _state.update { it.copy(saving = false) }
            // The event the screen leaves for: the Incident, or — a Save that found its row stored —
            // the one that row names. The screen leaves either way.
            _saved.tryEmit(written.incident?.id ?: written.condition.eventId ?: NO_EVENT)
        } catch (refused: IncidentConditionRefused) {
            when {
                refused.eventProblems.isNotEmpty() -> markProblems(refused.eventProblems, submittedRows)
                refused.incidentAfterToday || ConditionProblem.DateInFuture in refused.conditionProblems ->
                    _state.update { it.copy(saving = false, firstProblem = DATE_NOT_LATER_THAN_TODAY) }
                else -> {
                    Log.w(TAG, "a combined save the form allowed was refused", refused)
                    refuse(refused)
                }
            }
        } catch (gone: IllegalArgumentException) {
            // After the refusal above, which is one too: the asset gone, a profile that is not this
            // asset's, or the row refused. Nothing the form can fix, and no ratified words for it.
            Log.w(TAG, "the combined save was refused", gone)
            refuse(gone)
        } catch (held: AssetTransferredOut) {
            refuse(held)
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
        // #82: the impairment offer names its answer, so only [acceptImpairment] accepts it.
        if (open.accepting || open is ImpairmentOfferPrompt) return
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

    /**
     * #82, Workflow B's answer: P82-7 ([condition] DOWN) or P82-8 (DEGRADED). The answer and
     * `accepting` are set **before** the write — which disables all three answers — and a second tap
     * is ignored, so one accept reaches the offers. A refused accept (the Incident deleted meanwhile)
     * has no ratified sentence; the entry stands either way, and the screen leaves as it would have.
     */
    fun acceptImpairment(condition: OperationalCondition) {
        val open = _state.value.offer as? ImpairmentOfferPrompt ?: return
        if (open.accepting) return
        val answered = open.copy(accepting = true, chosen = condition)
        _state.update { it.copy(offer = answered) }
        viewModelScope.launch {
            try {
                offers.accept(answered)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: Exception) {
                Log.w(TAG, "an accepted offer was refused", refused)
            }
            nextAfter(answered)
        }
    }

    /**
     * "Not yet", "Not now" or #82's "No change" — and any offer dismissed: nothing is written, and the
     * next offer is asked or the screen leaves.
     */
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
    private fun markProblems(problems: List<FieldProblem>, submittedRows: List<Int>) {
        val byDefinition = problems.mapNotNull { p -> p.definitionId?.let { it to p } }.toMap()
        val badConsumables = problems
            .filterIsInstance<FieldProblem.BadConsumable>()
            .mapNotNull { submittedRows.getOrNull(it.index) }
            .toSet()
        _state.update { current ->
            val fields = current.fields.map { it.copy(problem = byDefinition[it.definition.id]) }
            current.copy(
                saving = false,
                fields = fields,
                consumables = current.consumables.mapIndexed { i, row -> row.copy(problem = i in badConsumables) },
                firstProblem = problems.firstProblemText(fields),
            )
        }
    }

    /**
     * The two failures no amount of retyping fixes: the event moved, or the profile or definition
     * the form names is not this asset's any more. Say so once and leave the form as it was typed.
     */
    private fun refuse(cause: Throwable) {
        val line = if (cause is NoSuchEvent) localized(R.string.journal_entry_gone) else cause.transferredOutOr(CANNOT_SAVE)
        _state.update { it.copy(saving = false, firstProblem = line) }
    }

    /**
     * A row that has not been touched at all is not a material the user forgot to fill in — it is
     * one they added and changed their mind about, so it never reaches validation. The row's own
     * index travels with it, so [FieldProblem.BadConsumable] still marks the right line.
     *
     * A linked row is never untouched (#15, B8b): its link is a choice someone made, drawn under the row, so with
     * its words cleared it still reaches validation and is named, rather than vanishing on Save with its link.
     * Removing the link (P15-23) makes it an untouched row again.
     */
    private fun List<ConsumableRow>.submitted(): List<Pair<Int, ConsumableInput>> = withIndex()
        .filterNot { (_, row) ->
            row.name.isBlank() && row.quantity.isBlank() && row.unit.isBlank() && row.supplyId == null
        }
        .map { (index, row) -> index to ConsumableInput(row.name, neutralNumber(row.quantity), row.unit, row.supplyId) }

    private companion object {
        const val TAG = "EventEntry"
        val NO_EVENT = EventId("")
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd") // l10n-ok: the date field's ISO value, which the owner edits and core reads
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm") // l10n-ok: the time field's HH:mm value, which core reads
    }
}

private fun Long.at(zone: ZoneId) = Instant.ofEpochMilli(this).atZone(zone)

/**
 * #82 (R82-3): the combined flow's Incident from the held reason — its first non-blank line, trimmed,
 * as the title (null when there is none, and the preset "Incident" stands), and every line after
 * it, trimmed as a block, as the notes.
 */
private fun incidentDraft(reason: String): Pair<String?, String> {
    val lines = reason.lines()
    val first = lines.indexOfFirst { it.isNotBlank() }
    if (first < 0) return null to ""
    return lines[first].trim() to lines.drop(first + 1).joinToString("\n").trim()
}

/**
 * The problems that are not about a single row name themselves; anything else is a row, and the
 * first of those is named by its definition's label ("pH is required"). A problem that lands on
 * no visible row still gets a line — a refusal must never be silent.
 */
private fun List<FieldProblem>.firstProblemText(fields: List<FieldRow>): String {
    firstNotNullOfOrNull { problem ->
        when (problem) {
            is FieldProblem.BadDate -> localized(R.string.journal_enter_a_date)
            is FieldProblem.BadTime -> localized(R.string.journal_enter_a_time)
            FieldProblem.TitleRequired -> localized(R.string.journal_title_required)
            is FieldProblem.BadConsumable -> localized(R.string.journal_check_material, problem.index + 1)
            else -> null
        }
    }?.let { return it }

    val row = fields.firstOrNull { it.problem != null } ?: return CANNOT_SAVE
    return when (row.problem) {
        is FieldProblem.Required -> localized(R.string.journal_field_required, row.definition.label)
        is FieldProblem.NotANumber -> localized(R.string.journal_field_not_a_number, row.definition.label)
        else -> CANNOT_SAVE
    }
}

/** The line for a refusal no row can explain. */
internal val CANNOT_SAVE: String get() = localized(R.string.journal_cannot_save)


/**
 * A stored value back as the text that produced it — the entry field holds what was typed, not a
 * formatted reading, so an edit that changes nothing else re-saves the same number. Every digit it
 * has, in the owner's decimal separator (#102), and a whole number keeps its one decimal ("2.0",
 * "2,0" in German) where the definition has decimals.
 */
private fun Measurement?.asText(definition: MeasurementDefinition): String {
    val m = this ?: return ""
    return when (definition.valueType) {
        ValueType.TEXT -> m.valueText.orEmpty()
        ValueType.BOOLEAN -> if (m.valueNum == 1.0) "1" else "0"
        ValueType.NUMBER -> m.valueNum?.let { value ->
            if (definition.decimals == 0) {
                formatNumber(value)
            } else {
                val separator = localizedDecimalSeparator()
                localizedDecimal(value).let { if (separator in it) it else "$it${separator}0" }
            }
        }.orEmpty()
    }
}
