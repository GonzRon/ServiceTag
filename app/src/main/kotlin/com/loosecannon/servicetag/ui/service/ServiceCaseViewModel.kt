package com.loosecannon.servicetag.ui.service

import android.util.Log
import com.loosecannon.servicetag.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.Money
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseEntry
import com.loosecannon.servicetag.core.model.ServiceCaseId
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseEntryRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.usecase.AddServiceCaseEntry
import com.loosecannon.servicetag.core.usecase.CaseEntryCommand
import com.loosecannon.servicetag.core.usecase.ServiceCaseCommand
import com.loosecannon.servicetag.core.usecase.ServiceCaseProblem
import com.loosecannon.servicetag.core.usecase.ServiceCaseValidation
import com.loosecannon.servicetag.core.usecase.UpdateServiceCase
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.transfer.AssetTransferredOut
import com.loosecannon.servicetag.ui.transfer.`import`.TransferImportStrings
import com.loosecannon.servicetag.ui.asset.LINKED_RECORD_REMOVED
import com.loosecannon.servicetag.ui.asset.amountLine
import com.loosecannon.servicetag.ui.condition.DATE_NOT_LATER_THAN_TODAY
import com.loosecannon.servicetag.ui.condition.displayDate
import com.loosecannon.servicetag.ui.journal.CANNOT_SAVE
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How long the repository flows stay hot after the last collector leaves (a rotation, typically). */
private const val SUBSCRIPTION_GRACE_MS = 5_000L

/** One header fact: a `FieldLabel` and its value, as the case screen draws it. */
data class CaseFact(val label: String, val value: String)

/**
 * A link row (C22): the Incident (P79-57) or the Repair record (P79-58). [exists] false is a link
 * whose event has since been deleted — readable, never an error (R79-4), and drawn as S24. Only the
 * Repair record is [removable] ("Remove"); the Incident a case was opened on is not.
 */
data class CaseLink(
    val label: String,
    val eventId: String,
    val title: String?,
    val date: String?,
    val exists: Boolean,
    val removable: Boolean,
) {
    /** S24 where the linked record is gone, else null. */
    val removedLine: String? get() = if (exists) null else LINKED_RECORD_REMOVED
}

/** One timeline entry, as recorded: its day (and time), the status it set if any, and its note. */
data class TimelineRow(val id: String, val date: String, val time: String?, val status: String?, val note: String)

/** One event P79-59's picker offers: a MAINTENANCE or REPLACEMENT of this case's asset. */
data class RepairCandidate(val eventId: String, val title: String, val date: String)

/** The update sheet's field keys for its refusals. */
object UpdateField {
    const val DATE = "date"
    const val TIME = "time"
}

/**
 * #79 (C22): P79-54's sheet as typed — Date (today), Time, P79-55, a status chip or none (no change).
 * [problems] maps an [UpdateField] to its line; [failure] is "Could not save this entry.".
 */
data class UpdateSheet(
    val date: String,
    val time: String = "",
    val note: String = "",
    val status: CaseStatus? = null,
    val problems: Map<String, String> = emptyMap(),
    val failure: String? = null,
    val saving: Boolean = false,
) {
    /** P79-56 is enabled only with a note or a status (C22). */
    val canSave: Boolean get() = !saving && (note.isNotBlank() || status != null)
}

/**
 * Everything the case screen draws (C22): the header's [facts], the two link rows, P79-59's
 * [candidates] and the append-only [timeline] in its order.
 */
data class ServiceCaseState(
    val case: ServiceCase,
    val facts: List<CaseFact>,
    val incident: CaseLink?,
    val repair: CaseLink?,
    val candidates: List<RepairCandidate>,
    val timeline: List<TimelineRow>,
    /**
     * #77 (C19, R77-4): false when the case's asset is transferred out from this phone — its timeline and links are
     * read, and nothing is updated, linked, removed or edited. Keyed on the held records, never on ARCHIVED.
     */
    val editable: Boolean = true,
) {
    /** P79-59 while no repair record is linked and this asset has one to link. */
    val offersLinkRepair: Boolean get() = editable && repair == null && candidates.isNotEmpty()
}

/**
 * #79 (C22): one service case, read-only but for three writes, each asked for by a tap and none
 * asking anything afterwards (R79-9, R79-10; AC 11): an update (`AddServiceCaseEntry` — a status
 * entry moves the status, CLOSED or CANCELLED set Closed on, a reopening clears it), and linking or
 * removing the repair record (`UpdateServiceCase` with every other field as loaded). No entry is
 * ever edited or deleted (R79-8). Nothing here writes a condition, an event or a schedule.
 *
 * It reads the case, its entries and its asset's events: one case, one timeline, one journal — no
 * read per row. A case that disappears (its asset deleted, a replace) sends the screen back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceCaseViewModel(
    private val cases: ServiceCaseRepository,
    entries: ServiceCaseEntryRepository,
    events: EventRepository,
    private val updateServiceCase: UpdateServiceCase,
    private val addServiceCaseEntry: AddServiceCaseEntry,
    private val today: Today,
    private val id: ServiceCaseId,
    /** The zone an update is recorded in: the device's (B1's zone rule). */
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** #77 (C19): the held set. Null holds nothing — a test that is not about transfers. */
    transfers: TransferRecordRepository? = null,
) : ViewModel() {

    constructor(graph: AppGraph, caseId: String) : this(
        graph.serviceCases, graph.serviceCaseEntries, graph.events,
        graph.updateServiceCase, graph.addServiceCaseEntry, graph.today, ServiceCaseId(caseId),
        transfers = graph.transferRecords,
    )

    /** The case as stored, followed through its asset's cases; null once it is gone. */
    private val page: Flow<ServiceCaseState?> = flow { emit(cases.get(id)?.assetId) }
        .flatMapLatest { assetId ->
            if (assetId == null) {
                flowOf(null)
            } else {
                combine(
                    cases.observeForAsset(assetId),
                    entries.observeForCase(id),
                    events.observeForAsset(assetId),
                    transfers?.observeHeldIds() ?: flowOf(emptySet()),
                ) { rows, timeline, journal, held ->
                    rows.firstOrNull { it.id == id }
                        ?.let { serviceCaseStateOf(it, timeline, journal).copy(editable = assetId !in held) }
                }
            }
        }

    val state: StateFlow<ServiceCaseState?> =
        page.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), null)

    val missing: StateFlow<Boolean> = page
        .map { it == null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), false)

    private val _sheet = MutableStateFlow<UpdateSheet?>(null)
    val sheet: StateFlow<UpdateSheet?> = _sheet.asStateFlow()

    private val _picking = MutableStateFlow(false)
    val picking: StateFlow<Boolean> = _picking.asStateFlow()

    /** Set while a link or a removal is being written; a second tap meanwhile is ignored. */
    private val _linking = MutableStateFlow(false)
    val linking: StateFlow<Boolean> = _linking.asStateFlow()

    /** P79-61, once, when a link or a removal fails. */
    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // ---------------------------------------------------------------- the update sheet (P79-54)

    /** Opens the sheet on today, no time, no note and no status. Opening writes nothing. */
    fun openUpdate() {
        _sheet.value = UpdateSheet(date = today.localDate().toString())
    }

    fun onUpdateDate(value: String) = editSheet(UpdateField.DATE) { it.copy(date = value) }
    fun onUpdateTime(value: String) = editSheet(UpdateField.TIME) { it.copy(time = value) }
    fun onUpdateNote(value: String) = editSheet { it.copy(note = value) }

    /** A status chip: tapping the chosen one again chooses none, which is no change of status. */
    fun onUpdateStatus(value: CaseStatus) = editSheet { it.copy(status = if (it.status == value) null else value) }

    private fun editSheet(vararg clears: String, change: (UpdateSheet) -> UpdateSheet) = _sheet.update { sheet ->
        if (sheet == null || sheet.saving) sheet else change(sheet).copy(problems = sheet.problems - clears.toSet(), failure = null)
    }

    /** "Cancel" and any dismissal: nothing is written. */
    fun cancelUpdate() = _sheet.update { if (it?.saving == true) it else null }

    /**
     * P79-56: one entry through `AddServiceCaseEntry`, in the device's zone. A status entry dated
     * before an earlier one is accepted and applied in write order (C14; the plan's erratum): the
     * timeline shows it at its date. A date later than today is S25; the sheet's other refusals are
     * the shipped date and time lines; anything else is "Could not save this entry.", logged.
     */
    fun saveUpdate() {
        val sheet = _sheet.value ?: return
        if (!sheet.canSave) return
        _sheet.value = sheet.copy(saving = true, problems = emptyMap(), failure = null)
        viewModelScope.launch {
            val command = CaseEntryCommand(
                occurredOn = sheet.date.trim(),
                occurredTime = sheet.time.trim().ifBlank { null },
                tzId = zone().id,
                note = sheet.note,
                status = sheet.status,
            )
            // The problems a refusal named, or null for a failure that is not a refusal at all.
            val refused: List<ServiceCaseProblem>? = try {
                addServiceCaseEntry.run(id, command)
                _sheet.value = null
                return@launch
            } catch (validation: ServiceCaseValidation) {
                validation.problems
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (held: AssetTransferredOut) {
                // #77 (B4 hand-off 1): the asset left while the sheet was open — P77-35, nothing written.
                _sheet.update { it?.copy(saving = false, failure = TransferImportStrings.ASSET_TRANSFERRED_OUT) }
                return@launch
            } catch (failed: Exception) {
                Log.w(TAG, "an update failed", failed)
                null
            }
            val marks = refused.orEmpty().mapNotNull(::entryMarkFor).toMap()
            val failed = refused == null || refused.any { entryMarkFor(it) == null }
            if (refused != null && failed) Log.w(TAG, "an update the sheet could not mark was refused: $refused")
            _sheet.update {
                it?.copy(saving = false, problems = marks, failure = if (failed) CANNOT_SAVE else null)
            }
        }
    }

    // ---------------------------------------------------------------- the repair record (P79-58, P79-59)

    /** P79-59: the picker, when there is something to pick. Opening writes nothing. */
    fun openPicker() {
        if (state.value?.offersLinkRepair == true) _picking.value = true
    }

    /** The picker's "Cancel", and any dismissal. */
    fun closePicker() {
        _picking.value = false
    }

    /** A pick: the header alone, through `UpdateServiceCase`, and nothing is asked afterwards. */
    fun linkRepair(eventId: String) = writeResolution(EventId(eventId))

    /** "Remove", on the Repair record row only: the link alone goes; the event stays. */
    fun removeRepair() = writeResolution(null)

    private fun writeResolution(resolution: EventId?) {
        val case = state.value?.case ?: return
        if (_linking.value) return
        _linking.value = true
        _picking.value = false
        viewModelScope.launch {
            try {
                updateServiceCase.run(case.id, case.asCommand(resolution))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (held: AssetTransferredOut) {
                _messages.tryEmit(TransferImportStrings.ASSET_TRANSFERRED_OUT)
            } catch (failed: Exception) {
                Log.w(TAG, "a repair link failed", failed)
                _messages.tryEmit(COULD_NOT_SAVE_THIS_CASE)
            } finally {
                _linking.value = false
            }
        }
    }

    private companion object {
        const val TAG = "ServiceCase"
    }
}

/** The header [case] as a command with every field as loaded but the resolution. */
private fun ServiceCase.asCommand(resolution: EventId?) = ServiceCaseCommand(
    title = title,
    type = type,
    openedOn = openedOn,
    coverage = coverage,
    resolutionEventId = resolution,
    provider = provider,
    contact = contact,
    caseRef = caseRef,
    outboundTracking = outboundTracking,
    outboundCarrier = outboundCarrier,
    returnTracking = returnTracking,
    returnCarrier = returnCarrier,
    costMinor = costMinor,
    currency = currency,
    notes = notes,
)

/** An update's refusal on its field, or null for one the sheet has no field for. */
private fun entryMarkFor(problem: ServiceCaseProblem): Pair<String, String>? = when (problem) {
    is ServiceCaseProblem.BadDate -> UpdateField.DATE to localized(R.string.service_enter_a_date)
    ServiceCaseProblem.EntryAfterToday -> UpdateField.DATE to DATE_NOT_LATER_THAN_TODAY
    is ServiceCaseProblem.BadTime -> UpdateField.TIME to localized(R.string.service_enter_a_time)
    else -> null
}

/** The case screen's state from one case, its entries and its asset's journal. */
internal fun serviceCaseStateOf(case: ServiceCase, entries: List<ServiceCaseEntry>, journal: List<AssetEvent>): ServiceCaseState {
    val byId = journal.associateBy { it.id }
    return ServiceCaseState(
        case = case,
        facts = factsOf(case),
        incident = case.incidentEventId?.let { linkOf(INCIDENT_LINK, it, byId[it], removable = false) },
        repair = case.resolutionEventId?.let { linkOf(REPAIR_RECORD, it, byId[it], removable = true) },
        candidates = journal
            .filter { it.kind == EventKind.MAINTENANCE || it.kind == EventKind.REPLACEMENT }
            .sortedWith(NEWEST_FIRST)
            .map { RepairCandidate(it.id.value, it.title, dayOf(it.occurredOn)) },
        timeline = entries.sortedWith(TIMELINE_ORDER).map { entry ->
            TimelineRow(
                id = entry.id.value,
                date = dayOf(entry.occurredOn),
                time = entry.occurredTime,
                status = entry.status?.let(::caseStatusWord),
                note = entry.note,
            )
        },
    )
}

/**
 * The header's facts in the order drawn: Type, Opened on, Status, Closed on when set, Coverage, then
 * only the facts that are set — provider, contact, the case or RMA number, each leg's tracking with
 * its carrier, the cost through [Money] and the notes.
 */
private fun factsOf(case: ServiceCase): List<CaseFact> = buildList {
    add(CaseFact(CASE_TYPE, caseTypeWord(case.type)))
    add(CaseFact(OPENED_ON, dayOf(case.openedOn)))
    add(CaseFact(CASE_STATUS, caseStatusWord(case.status)))
    case.closedOn?.let { add(CaseFact(CLOSED_ON, dayOf(it))) }
    add(CaseFact(COVERAGE, coverageWord(case.coverage)))
    case.provider.takeIf { it.isNotBlank() }?.let { add(CaseFact(SERVICE_PROVIDER, it)) }
    case.contact.takeIf { it.isNotBlank() }?.let { add(CaseFact(PHONE_OR_CONTACT, it)) }
    case.caseRef.takeIf { it.isNotBlank() }?.let { add(CaseFact(CASE_OR_RMA_NUMBER, it)) }
    legOf(case.outboundTracking, case.outboundCarrier)?.let { add(CaseFact(OUTBOUND_TRACKING, it)) }
    legOf(case.returnTracking, case.returnCarrier)?.let { add(CaseFact(RETURN_TRACKING, it)) }
    costOf(case)?.let { add(CaseFact(COST, it)) }
    case.notes.takeIf { it.isNotBlank() }?.let { add(CaseFact(localized(R.string.service_field_notes), it)) }
}

/** One leg's tracking number and carrier, as one value; null when neither is recorded. */
private fun legOf(tracking: String, carrier: String): String? =
    listOf(tracking, carrier).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { null }

/**
 * The cost through [Money] — "0.00 USD" is no charge — in the language's decimal separator ([amountLine], #102);
 * null when none is recorded or it cannot be read.
 */
private fun costOf(case: ServiceCase): String? {
    val minor = case.costMinor ?: return null
    val code = case.currency ?: return null
    return runCatching { amountLine(minor, code) }.getOrNull()
}

private fun linkOf(label: String, id: EventId, event: AssetEvent?, removable: Boolean) = CaseLink(
    label = label,
    eventId = id.value,
    title = event?.title,
    date = event?.let { dayOf(it.occurredOn) },
    exists = event != null,
    removable = removable,
)

/** `d MMM yyyy`, or the stored text when it is not a date. */
private fun dayOf(iso: String): String = runCatching { displayDate(LocalDate.parse(iso)) }.getOrDefault(iso)

/** C22's timeline order, oldest first: `(occurredOn, occurredTime nulls first, createdAt, id)`. */
private val TIMELINE_ORDER: Comparator<ServiceCaseEntry> =
    compareBy({ it.occurredOn }, { it.occurredTime }, { it.createdAt }, { it.id.value })

/** The picker's order: this asset's journal order, newest first. */
private val NEWEST_FIRST: Comparator<AssetEvent> =
    compareByDescending<AssetEvent> { it.occurredOn }
        .thenByDescending { it.occurredTime }
        .thenByDescending { it.createdAt }
        .thenByDescending { it.id.value }
