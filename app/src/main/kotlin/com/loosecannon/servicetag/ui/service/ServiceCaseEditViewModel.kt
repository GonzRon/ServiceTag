package com.loosecannon.servicetag.ui.service

import com.loosecannon.servicetag.ui.transfer.transferredOutOr
import com.loosecannon.servicetag.R
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CaseCoverage
import com.loosecannon.servicetag.core.model.CaseType
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.Money
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.usecase.OpenServiceCase
import com.loosecannon.servicetag.core.usecase.ServiceCaseCommand
import com.loosecannon.servicetag.core.usecase.ServiceCaseProblem
import com.loosecannon.servicetag.core.usecase.ServiceCaseValidation
import com.loosecannon.servicetag.core.usecase.UpdateServiceCase
import com.loosecannon.servicetag.core.usecase.suggestCoverage
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.l10n.datePlaceholder
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.ui.asset.BAD_CURRENCY
import com.loosecannon.servicetag.ui.asset.parseAmount
import com.loosecannon.servicetag.ui.asset.priceTextOf
import com.loosecannon.servicetag.ui.condition.DATE_NOT_LATER_THAN_TODAY
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The case editor's fields that can carry a refusal (C21), by the key [ServiceCaseForm.problems] uses. */
object CaseField {
    const val TITLE = "title"
    const val OPENED_ON = "openedOn"
    const val COST = "cost"
    const val CURRENCY = "currency"
}

/**
 * #79 (C21): the case editor as typed. [isNew] is a new case (P79-19 is its title, P79-21 an edit's);
 * [suggested] draws P79-36 under Coverage, on a new case only. [problems] maps a [CaseField] to its
 * one ratified line. [saving] is set before the first suspension and stays set once a save has
 * landed, so a second tap never writes a second case.
 */
data class ServiceCaseForm(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val title: String = "",
    val type: CaseType = CaseType.REPAIR,
    val openedOn: String = "",
    val provider: String = "",
    val contact: String = "",
    val caseRef: String = "",
    val coverage: CaseCoverage = CaseCoverage.UNKNOWN,
    val suggested: Boolean = false,
    val outboundTracking: String = "",
    val outboundCarrier: String = "",
    val returnTracking: String = "",
    val returnCarrier: String = "",
    val cost: String = "",
    val currency: String = "",
    val notes: String = "",
    val problems: Map<String, String> = emptyMap(),
    val saving: Boolean = false,
) {
    /** P79-19 on a new case, P79-21 on an edit. */
    val screenTitle: String get() = if (isNew) NEW_SERVICE_CASE else SERVICE_CASE

    val canSave: Boolean get() = loaded && !saving
}

/**
 * #79 (C21): one service case's header, new or edited.
 *
 * **New** — opened on an Incident ([incidentId]; R79-3): the title is the Incident's, `openedOn` today,
 * the coverage `suggestCoverage` from the warranty date and the Incident's date (C15) with P79-36 under
 * it, the type Warranty service when that is IN_WARRANTY and Repair otherwise, the currency the
 * asset's, else blank. Every one is a **form default** the owner may change; `OpenServiceCase` stores
 * what it is sent. **Edit** — the stored header, whose status, `closedOn`, asset and Incident the
 * update never moves; the loaded resolution link is sent back as it was loaded.
 *
 * The cost is text until [Money] reads it in the currency, as the asset's price is (P79-62 otherwise);
 * every problem lands on its field with its ratified line; anything else is P79-61, logged. Cancel and
 * back write nothing — only [save] writes.
 */
class ServiceCaseEditViewModel(
    private val assets: AssetRepository,
    private val events: EventRepository,
    private val cases: ServiceCaseRepository,
    private val openServiceCase: OpenServiceCase,
    private val updateServiceCase: UpdateServiceCase,
    private val today: Today,
    private val assetId: AssetId,
    private val caseId: ServiceCaseId?,
    private val incidentId: EventId?,
) : ViewModel() {

    constructor(graph: AppGraph, assetId: String, caseId: String?, incidentId: String?) : this(
        graph.assets, graph.events, graph.serviceCases, graph.openServiceCase, graph.updateServiceCase,
        graph.today, AssetId(assetId), caseId?.let(::ServiceCaseId), incidentId?.let(::EventId),
    )

    private val _state = MutableStateFlow(ServiceCaseForm(isNew = caseId == null))
    val state: StateFlow<ServiceCaseForm> = _state.asStateFlow()

    /** True once the asset (a new case) or the case (an edit) is not there: the screen leaves. */
    private val _missing = MutableStateFlow(false)
    val missing: StateFlow<Boolean> = _missing.asStateFlow()

    /** The written case's id, once: the screen leaves for it. */
    private val _saved = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val saved: SharedFlow<String> = _saved.asSharedFlow()

    /** P79-61, once per unexpected failure. */
    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** An edit's resolution link as loaded — sent back unchanged, dangling or not (C21). */
    private var loadedResolution: EventId? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        if (caseId != null) {
            val case = cases.get(caseId) ?: return run { _missing.value = true }
            loadedResolution = case.resolutionEventId
            _state.update { it.filledFrom(case).copy(loaded = true) }
            return
        }
        val asset = assets.get(assetId) ?: return run { _missing.value = true }
        val incident = incidentId?.let { events.get(it) }
        val openedOn = today.localDate().toString()
        val coverage = suggestCoverage(asset.warrantyExpiresOn, incident?.occurredOn, openedOn)
        _state.update {
            it.copy(
                loaded = true,
                title = incident?.title.orEmpty(),
                openedOn = openedOn,
                coverage = coverage,
                suggested = true,
                type = if (coverage == CaseCoverage.IN_WARRANTY) CaseType.WARRANTY_SERVICE else CaseType.REPAIR,
                currency = asset.currency.orEmpty(),
            )
        }
    }

    fun onTitle(value: String) = edit(CaseField.TITLE) { it.copy(title = value) }
    fun onType(value: CaseType) = edit { it.copy(type = value) }
    fun onOpenedOn(value: String) = edit(CaseField.OPENED_ON) { it.copy(openedOn = value) }
    fun onProvider(value: String) = edit { it.copy(provider = value) }
    fun onContact(value: String) = edit { it.copy(contact = value) }
    fun onCaseRef(value: String) = edit { it.copy(caseRef = value) }
    fun onCoverage(value: CaseCoverage) = edit { it.copy(coverage = value) }
    fun onOutboundTracking(value: String) = edit { it.copy(outboundTracking = value) }
    fun onOutboundCarrier(value: String) = edit { it.copy(outboundCarrier = value) }
    fun onReturnTracking(value: String) = edit { it.copy(returnTracking = value) }
    fun onReturnCarrier(value: String) = edit { it.copy(returnCarrier = value) }
    fun onCost(value: String) = edit(CaseField.COST, CaseField.CURRENCY) { it.copy(cost = value) }
    fun onCurrency(value: String) = edit(CaseField.CURRENCY, CaseField.COST) { it.copy(currency = value) }
    fun onNotes(value: String) = edit { it.copy(notes = value) }

    /** An edit clears the lines of the fields it touches, and nothing while a save is out. */
    private fun edit(vararg clears: String, change: (ServiceCaseForm) -> ServiceCaseForm) = _state.update { form ->
        if (form.saving) form else change(form).copy(problems = form.problems - clears.toSet())
    }

    /**
     * The one write: `OpenServiceCase` for a new case (on its Incident), `UpdateServiceCase` for an
     * edit. `saving` is set before the first suspension, so a double tap writes one case; after a
     * write it stays set and the screen leaves on [saved].
     */
    fun save() {
        val form = _state.value
        if (!form.canSave) return
        _state.update { it.copy(saving = true, problems = emptyMap()) }
        viewModelScope.launch {
            val cost = costOf(form.cost, form.currency)
            if (cost is Costed.Bad) {
                _state.update { it.copy(saving = false, problems = cost.problems) }
                return@launch
            }
            val command = form.command((cost as Costed.Ok).minor, loadedResolution)
            try {
                val written = if (caseId == null) {
                    openServiceCase.run(assetId, command, incidentId)
                } else {
                    updateServiceCase.run(caseId, command)
                }
                _saved.tryEmit(written.id.value)
            } catch (refused: ServiceCaseValidation) {
                val marks = refused.problems.mapNotNull(::markFor).toMap()
                _state.update { it.copy(saving = false, problems = marks) }
                if (refused.problems.any { markFor(it) == null }) {
                    Log.w(TAG, "a case save the form could not mark was refused", refused)
                    _messages.tryEmit(COULD_NOT_SAVE_THIS_CASE)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                Log.w(TAG, "a case save failed", failed)
                _state.update { it.copy(saving = false) }
                _messages.tryEmit(failed.transferredOutOr(COULD_NOT_SAVE_THIS_CASE))
            }
        }
    }

    private companion object {
        const val TAG = "ServiceCaseEdit"
    }
}

/** The stored header as the form shows it; the cost without its code, which the Currency field names. */
private fun ServiceCaseForm.filledFrom(case: ServiceCase): ServiceCaseForm = copy(
    title = case.title,
    type = case.type,
    openedOn = case.openedOn,
    provider = case.provider,
    contact = case.contact,
    caseRef = case.caseRef,
    coverage = case.coverage,
    outboundTracking = case.outboundTracking,
    outboundCarrier = case.outboundCarrier,
    returnTracking = case.returnTracking,
    returnCarrier = case.returnCarrier,
    cost = priceTextOf(case.costMinor, case.currency),
    currency = case.currency.orEmpty(),
    notes = case.notes,
)

private fun ServiceCaseForm.command(costMinor: Long?, resolution: EventId?) = ServiceCaseCommand(
    title = title,
    type = type,
    openedOn = openedOn.trim(),
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
    currency = currency.trim().ifBlank { null },
    notes = notes,
)

private sealed interface Costed {
    data class Ok(val minor: Long?) : Costed
    data class Bad(val problems: Map<String, String>) : Costed
}

/**
 * The form's cost as minor units, read as the asset's price is: blank is no cost (always allowed);
 * a cost needs a currency [Money] resolves (P79-48, the shipped currency line) before its digits mean
 * anything; digits it cannot read are P79-62 with this currency's example.
 */
private fun costOf(cost: String, currency: String): Costed {
    val text = cost.trim()
    val code = currency.trim()
    if (text.isEmpty()) return Costed.Ok(null)
    if (code.isEmpty()) return Costed.Bad(mapOf(CaseField.CURRENCY to A_COST_NEEDS_A_CURRENCY))
    val digits = Money.fractionDigits(code) ?: return Costed.Bad(mapOf(CaseField.CURRENCY to BAD_CURRENCY))
    val minor = parseAmount(text, code) ?: return Costed.Bad(mapOf(CaseField.COST to costExample(digits)))
    return Costed.Ok(minor)
}

/** One refusal on its field with its ratified line, or null for one no field of this form can explain. */
private fun markFor(problem: ServiceCaseProblem): Pair<String, String>? = when (problem) {
    ServiceCaseProblem.TitleRequired -> CaseField.TITLE to GIVE_THE_CASE_A_TITLE
    is ServiceCaseProblem.BadDate -> CaseField.OPENED_ON to localized(R.string.service_enter_a_date, datePlaceholder())
    ServiceCaseProblem.OpenedAfterToday -> CaseField.OPENED_ON to DATE_NOT_LATER_THAN_TODAY
    ServiceCaseProblem.BadCurrency -> CaseField.CURRENCY to BAD_CURRENCY
    ServiceCaseProblem.CostWithoutCurrency -> CaseField.CURRENCY to A_COST_NEEDS_A_CURRENCY
    ServiceCaseProblem.NegativeCost -> CaseField.COST to COST_CANNOT_BE_NEGATIVE
    else -> null
}
