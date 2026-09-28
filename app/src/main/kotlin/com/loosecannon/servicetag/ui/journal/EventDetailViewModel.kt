package com.loosecannon.servicetag.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.journal.Derived
import com.loosecannon.servicetag.core.journal.Reading
import com.loosecannon.servicetag.core.journal.classify
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseRepository
import com.loosecannon.servicetag.core.usecase.DeleteEvent
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.health.inService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How long the repository flow stays hot after the last collector leaves (a rotation, typically). */
private const val SUBSCRIPTION_GRACE_MS = 5_000L

/**
 * One stored event, read-only. The definitions travel with it because a measurement only means
 * something next to the definition that names and bounds it, and they are looked up by the id the
 * measurement carries rather than by position.
 */
data class EventDetailState(
    val event: AssetEvent,
    val definitions: Map<DefinitionId, MeasurementDefinition>,
    val assetName: String,
    /**
     * What the asset derives from this entry's own readings (spec §5), computed on every emission
     * and stored nowhere. Empty when the asset has no unarchived DERIVED definition, or when the
     * entry carried no readings at all; a row that cannot be computed is present and reads "—".
     */
    val derived: List<Reading> = emptyList(),
    /**
     * #79 (C23, R79-3): P79-20 "Start service case" — a non-completion INCIDENT of an asset **in
     * service**, the one rule with the asset detail's P79-19.
     */
    val startsServiceCase: Boolean = false,
)

/** #79 (C23, R79-4): the delete confirm as asked — with P79-60 when a service case names the entry. */
data class DeleteConfirm(val linkedByCase: Boolean)

/**
 * #79 (C23): whether a service case names an event — as its Incident or its repair record. Read-only,
 * built in `AppGraph`; the Incident's delete confirm asks it (R79-4: the link is left dangling).
 */
fun interface CaseLinks {
    suspend fun linking(eventId: EventId): Boolean
}

/**
 * The graph's [CaseLinks]: the event's asset's cases — one read of each, never a read per case — any of
 * which names it as its Incident or its repair record. An event that is gone links nothing.
 */
fun caseLinksOf(events: EventRepository, cases: ServiceCaseRepository): CaseLinks = CaseLinks { id ->
    val event = events.get(id) ?: return@CaseLinks false
    cases.forAsset(event.assetId).any { it.incidentEventId == id || it.resolutionEventId == id }
}

/**
 * The read side of the journal. [missing] is separate from [state] because "not loaded yet" and
 * "gone" both read as a null state, and only the second sends the user back — a restored back
 * stack or a replacing import can name an event that is no longer there (the 1C pattern).
 */
class EventDetailViewModel(
    events: EventRepository,
    definitions: DefinitionRepository,
    assets: AssetRepository,
    private val deleteEvent: DeleteEvent,
    private val id: EventId,
    /** #79 (C23): whether a case links this entry. The default links none — a test that is not about cases. */
    private val caseLinks: CaseLinks = CaseLinks { false },
) : ViewModel() {

    constructor(graph: AppGraph, eventId: String) :
        this(graph.events, graph.definitions, graph.assets, graph.deleteEvent, EventId(eventId), graph.caseLinks)

    private val row = events.observe(id)

    val state: StateFlow<EventDetailState?> = row
        .map { event ->
            event?.let {
                val byId = definitions.forAsset(it.assetId).associateBy(MeasurementDefinition::id)
                val asset = assets.get(it.assetId)
                EventDetailState(
                    event = it,
                    definitions = byId,
                    assetName = asset?.name.orEmpty(),
                    derived = derivedFor(it, byId),
                    startsServiceCase = it.kind == EventKind.INCIDENT && it.scheduleId == null && asset?.inService == true,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), null)

    val missing: StateFlow<Boolean> = row
        .map { it == null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), false)

    /**
     * #79 (C23, R79-4): the delete confirm, or null while none is asked. Whether a case links the entry
     * is read when Delete is tapped, not when the page opened: a case started from this very entry
     * (P79-20) and back again is counted.
     */
    private val _deleteConfirm = MutableStateFlow<DeleteConfirm?>(null)
    val deleteConfirm: StateFlow<DeleteConfirm?> = _deleteConfirm.asStateFlow()

    /** Delete, tapped: the one read the confirm needs, then the confirm. Nothing is written. */
    fun askDelete() {
        viewModelScope.launch { _deleteConfirm.value = DeleteConfirm(linkedByCase = caseLinks.linking(id)) }
    }

    /** "Cancel", any dismissal, and the confirm itself: the dialog goes. */
    fun dismissDelete() {
        _deleteConfirm.value = null
    }

    /** One shot, so the screen pops on the delete it asked for rather than on the row vanishing. */
    private val _deleted = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val deleted: SharedFlow<Unit> = _deleted.asSharedFlow()

    /**
     * An event is a record of something that happened, so this is the one destructive action in
     * the journal and the screen asks first. Its children go with it (CASCADE).
     */
    fun delete() {
        viewModelScope.launch {
            deleteEvent.run(id)
            _deleted.tryEmit(Unit)
        }
    }
}

/**
 * The asset's unarchived DERIVED definitions against this one event, same-event semantics and all
 * (spec §5). An entry that recorded no readings gets none of them: a row that could never have a
 * value on an entry with nothing to derive from is noise, not information.
 */
private fun derivedFor(
    event: AssetEvent,
    definitions: Map<DefinitionId, MeasurementDefinition>,
): List<Reading> {
    if (event.measurements.isEmpty()) return emptyList()
    return definitions.values
        .filter { it.kind == DefinitionKind.DERIVED && it.archivedAt == null }
        .sortedBy { it.sortOrder }
        .map { definition ->
            val value = Derived.compute(definition, event, definitions)
            Reading(
                definition = definition,
                measurement = null,
                occurredOn = event.occurredOn,
                occurredTime = event.occurredTime,
                state = value?.let { classify(it, definition.rangeLow, definition.rangeHigh) },
                derivedValue = value,
            )
        }
}
