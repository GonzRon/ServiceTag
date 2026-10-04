package com.loosecannon.servicetag.ui.condition

import com.loosecannon.servicetag.core.transfer.AssetTransferredOut
import com.loosecannon.servicetag.ui.transfer.`import`.TransferImportStrings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.usecase.ConditionCommand
import com.loosecannon.servicetag.core.usecase.ConditionProblem
import com.loosecannon.servicetag.core.usecase.ConditionValidation
import com.loosecannon.servicetag.core.usecase.MAX_CONDITION_REASON
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.RecordCondition
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.ui.health.inService
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** S25: the one refusal Change condition draws — a date later than today. */
val DATE_NOT_LATER_THAN_TODAY: String get() = localized(R.string.condition_date_not_later_than_today)

/**
 * #82 (R82-2): a DOWN or DEGRADED answer **held** while P82-1 is asked — nothing is written yet. [id]
 * is the row's id, allocated when the answer is held, so whichever answer commits it — P82-4 alone,
 * or the Incident entry's Save with it — writes that one row at most once (C2). It is also what
 * P82-3 hands to the host for the Incident entry, which carries it on its route.
 */
data class PendingCondition(
    val id: String,
    val condition: OperationalCondition,
    val occurredOn: String,
    val reason: String,
)

/**
 * The Change condition form as typed. [choice] starts **unanswered** — none of the three is
 * preselected (spec §10.1) — and [occurredOn] starts on today in the device zone, past dates
 * allowed. [refusal] is S25 or nothing: it is set only by a Save that named a later date.
 *
 * #82: [held] is the answer P82-1 is asked about, named by [assetName]; [handingOver] is set while
 * P82-3's draft is with the Incident entry, and [checking] while the one read that decides what a
 * return to the sheet means is out. The question is drawn only when none of them is in the way.
 */
data class ChangeConditionState(
    val choice: OperationalCondition? = null,
    val reason: String = "",
    val occurredOn: String,
    val refusal: String? = null,
    val saving: Boolean = false,
    val held: PendingCondition? = null,
    val assetName: String = "",
    val checking: Boolean = false,
    val handingOver: Boolean = false,
) {
    /** P82-1 is on screen: a held answer, not with the Incident entry, and not being read back. */
    val asking: Boolean get() = held != null && !handingOver && !checking

    /**
     * Save needs an answer and a real ISO date. A malformed date has no ratified sentence, so it is
     * made unreachable by the held button rather than answered with invented words (plan decision
     * 46's rule); the calendar picker only ever writes a real one. While an answer is held, S16 is
     * already answered and waits on the question.
     */
    val canSave: Boolean get() = choice != null && !saving && held == null && isIsoDate(occurredOn)
}

/**
 * **Change condition** (spec §5.4, §10.1): one of three conditions, an optional reason, the day it
 * changed — and one [RecordCondition] call, which is the only write here (inv. 81).
 *
 * **#82, hold and commit (R82-2).** S16 with OPERATIONAL, or for an asset out of service, writes at
 * once, exactly as shipped. S16 with DOWN or DEGRADED for an asset in service writes **nothing**: it
 * holds the answer and asks P82-1. **P82-4** writes the held row alone; **P82-3** hands the draft to
 * the host ([handOver]) for the Incident entry, whose Save writes the Incident and this row in one
 * transaction; **dismissing** the question drops the hold and returns to the form as typed. The hold
 * lives only here, in memory: after process death the sheet reopens empty with nothing written
 * (R82-2 (b)).
 *
 * Cancel writes nothing: it is a way out, not a call. Every way out — a save, a Cancel, the sheet
 * dismissed — resets the form, so the next time the sheet opens nothing is preselected again.
 * `occurredTime` is left null; `tzId` is the device zone.
 */
class ChangeConditionViewModel(
    private val record: RecordCondition,
    private val today: Today,
    private val assetId: AssetId,
    /** The asset, read at Save: whether it is in service decides the hold, and P82-2 names it. */
    private val assetOf: suspend (AssetId) -> Asset?,
    /** The asset's condition rows, read only — [onShown] looks for the held id among them. */
    private val rowsOf: suspend (AssetId) -> List<AssetCondition>,
    private val ids: IdGenerator,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {

    constructor(graph: AppGraph, assetId: String) : this(
        graph.recordCondition, graph.today, AssetId(assetId),
        assetOf = { graph.assets.get(it) },
        rowsOf = { graph.conditions.forAsset(it) },
        ids = graph.ids,
    )

    private val _state = MutableStateFlow(fresh())
    val state: StateFlow<ChangeConditionState> = _state.asStateFlow()

    /** One shot per finished form — saved or cancelled — so the host can close the sheet. */
    private val _finished = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val finished: SharedFlow<Unit> = _finished.asSharedFlow()

    /** One shot per P82-3: the held draft, for the host to open the Incident entry with. */
    private val _handOver = MutableSharedFlow<PendingCondition>(replay = 0, extraBufferCapacity = 1)
    val handOver: SharedFlow<PendingCondition> = _handOver.asSharedFlow()

    private fun fresh() = ChangeConditionState(occurredOn = today.localDate().toString())

    fun choose(condition: OperationalCondition) = _state.update { it.copy(choice = condition) }

    /** The reason is capped at the command's 500 characters as it is typed, so no refusal is needed. */
    fun onReason(value: String) = _state.update { it.copy(reason = value.take(MAX_CONDITION_REASON)) }

    fun onDate(value: String) = _state.update { it.copy(occurredOn = value, refusal = null) }

    /**
     * **"Save condition"**. A date later than today is answered by S25 and writes nothing. Then the
     * asset is read: DOWN or DEGRADED for an asset in service is **held** and P82-1 asked, with
     * nothing written; anything else is one [RecordCondition] call, as shipped. The guard is set
     * before the first suspension, so two taps in one frame hold or record once; while an answer is
     * held, S16 is ignored.
     */
    fun save() {
        val form = _state.value
        val choice = form.choice ?: return
        if (!form.canSave) return
        val on = LocalDate.parse(form.occurredOn.trim())
        if (on > today.localDate()) {
            _state.update { it.copy(refusal = DATE_NOT_LATER_THAN_TODAY) }
            return
        }
        _state.update { it.copy(saving = true, refusal = null) }
        viewModelScope.launch {
            val asset = assetOf(assetId)
            if (choice != OperationalCondition.OPERATIONAL && asset != null && asset.inService) {
                val held = PendingCondition(ids.newId(), choice, on.toString(), form.reason)
                _state.update { it.copy(saving = false, held = held, assetName = asset.name) }
            } else {
                commit(choice, on, form.reason, heldId = null)
            }
        }
    }

    /**
     * **P82-4, "Save condition only"**: the held answer written alone, unlinked, under its
     * pre-allocated id — so a combined Save that already landed that id makes this write nothing
     * (C2). `saving` is set before the first suspension: a double tap records one row, and Cancel is
     * ignored while it lands.
     */
    fun saveConditionOnly() {
        val form = _state.value
        val held = form.held ?: return
        if (form.saving || form.checking) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            commit(held.condition, LocalDate.parse(held.occurredOn), held.reason, heldId = held.id)
        }
    }

    /**
     * **P82-3, "Log incident details"**: the held draft goes to the host, once, and nothing is
     * written. The hold is kept — back from the entry, the question is asked again ([onShown]).
     */
    fun logIncidentDetails() {
        val form = _state.value
        val held = form.held ?: return
        if (form.saving || form.handingOver || form.checking) return
        _state.update { it.copy(handingOver = true) }
        _handOver.tryEmit(held)
    }

    /** The question dismissed: the hold is dropped and the form is back as typed. Nothing is written. */
    fun dismissQuestion() {
        val form = _state.value
        if (form.held == null || form.saving) return
        _state.update { it.copy(held = null, handingOver = false, checking = false) }
    }

    /**
     * The sheet is on screen again — composed, or resumed. With an answer held, the question stays
     * hidden until one read of the asset's rows says what the return means: the held id stored (the
     * Incident entry's Save landed) finishes the form with no write; absent (back from the entry),
     * the question is asked again. Nothing held, or a P82-4 landing, and there is nothing to decide.
     */
    fun onShown() {
        val form = _state.value
        val held = form.held ?: return
        if (form.saving || form.checking) return
        _state.update { it.copy(checking = true) }
        viewModelScope.launch {
            val landed = rowsOf(assetId).any { it.id == held.id }
            if (landed) {
                finish()
            } else {
                _state.update { it.copy(checking = false, handingOver = false) }
            }
        }
    }

    /** The one write: [heldId] is P82-4's pre-allocated row id, and null for S16's shipped path. */
    private suspend fun commit(condition: OperationalCondition, on: LocalDate, reason: String, heldId: String?) {
        try {
            record.run(
                assetId,
                ConditionCommand(
                    condition = condition,
                    occurredOn = on.toString(),
                    occurredTime = null,
                    tzId = zone().id,
                    reason = reason,
                ),
                rowId = heldId,
            )
            finish()
        } catch (refused: ConditionValidation) {
            // The day turned over between the check above and the write: the same answer, S25.
            // Nothing else is reachable from this form, and nothing else has ratified words. A held
            // answer is dropped with it, so the form is back as typed under the refusal.
            val late = ConditionProblem.DateInFuture in refused.problems
            if (!late) Log.w(TAG, "a condition the form allowed was refused", refused)
            _state.update {
                it.copy(saving = false, held = null, refusal = if (late) DATE_NOT_LATER_THAN_TODAY else null)
            }
        } catch (gone: NoSuchAsset) {
            Log.w(TAG, "the asset left while its condition was being changed", gone)
            finish()
        } catch (held: AssetTransferredOut) {
            // #77 (B4 hand-off 1): the asset was transferred out while the sheet was open — P77-35, nothing written.
            _state.update { it.copy(saving = false, held = null, refusal = TransferImportStrings.ASSET_TRANSFERRED_OUT) }
        }
    }

    /**
     * **Cancel** (and the sheet dismissed): nothing is written. Ignored while a Save is in flight — its
     * row is landing, so the form ends as saved and never as cancelled (the button is disabled too).
     */
    fun cancel() {
        if (_state.value.saving) return
        finish()
    }

    private fun finish() {
        _state.value = fresh()
        _finished.tryEmit(Unit)
    }

    private companion object {
        const val TAG = "ChangeCondition"
    }
}

private fun isIsoDate(value: String): Boolean = try {
    LocalDate.parse(value.trim())
    true
} catch (_: DateTimeParseException) {
    false
}
