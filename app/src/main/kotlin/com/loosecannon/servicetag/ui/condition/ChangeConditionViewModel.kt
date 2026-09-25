package com.loosecannon.servicetag.ui.condition

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.usecase.ConditionCommand
import com.loosecannon.servicetag.core.usecase.ConditionProblem
import com.loosecannon.servicetag.core.usecase.ConditionValidation
import com.loosecannon.servicetag.core.usecase.MAX_CONDITION_REASON
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.RecordCondition
import com.loosecannon.servicetag.di.AppGraph
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
const val DATE_NOT_LATER_THAN_TODAY = "The date cannot be later than today."

/**
 * The Change condition form as typed. [choice] starts **unanswered** — none of the three is
 * preselected (spec §10.1) — and [occurredOn] starts on today in the device zone, past dates
 * allowed. [refusal] is S25 or nothing: it is set only by a Save that named a later date.
 */
data class ChangeConditionState(
    val choice: OperationalCondition? = null,
    val reason: String = "",
    val occurredOn: String,
    val refusal: String? = null,
    val saving: Boolean = false,
) {
    /**
     * Save needs an answer and a real ISO date. A malformed date has no ratified sentence, so it is
     * made unreachable by the held button rather than answered with invented words (plan decision
     * 46's rule); the calendar picker only ever writes a real one.
     */
    val canSave: Boolean get() = choice != null && !saving && isIsoDate(occurredOn)
}

/**
 * **Change condition** (spec §5.4, §10.1): one of three conditions, an optional reason, the day it
 * changed — and one [RecordCondition] call on Save, which is the only write here (inv. 81).
 *
 * Cancel writes nothing: it is a way out, not a call. Every way out — a save, a Cancel, the sheet
 * dismissed — resets the form, so the next time the sheet opens nothing is preselected again.
 * `occurredTime` is left null; `tzId` is the device zone.
 */
class ChangeConditionViewModel(
    private val record: RecordCondition,
    private val today: Today,
    private val assetId: AssetId,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {

    constructor(graph: AppGraph, assetId: String) : this(graph.recordCondition, graph.today, AssetId(assetId))

    private val _state = MutableStateFlow(fresh())
    val state: StateFlow<ChangeConditionState> = _state.asStateFlow()

    /** One shot per finished form — saved or cancelled — so the host can close the sheet. */
    private val _finished = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val finished: SharedFlow<Unit> = _finished.asSharedFlow()

    private fun fresh() = ChangeConditionState(occurredOn = today.localDate().toString())

    fun choose(condition: OperationalCondition) = _state.update { it.copy(choice = condition) }

    /** The reason is capped at the command's 500 characters as it is typed, so no refusal is needed. */
    fun onReason(value: String) = _state.update { it.copy(reason = value.take(MAX_CONDITION_REASON)) }

    fun onDate(value: String) = _state.update { it.copy(occurredOn = value, refusal = null) }

    /**
     * **"Save condition"**: exactly one [RecordCondition] call. A date later than today is answered
     * by S25 and writes nothing; the guard is set before the first suspension, so two taps in one
     * frame record one row.
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
            try {
                record.run(
                    assetId,
                    ConditionCommand(
                        condition = choice,
                        occurredOn = on.toString(),
                        occurredTime = null,
                        tzId = zone().id,
                        reason = form.reason,
                    ),
                )
                finish()
            } catch (refused: ConditionValidation) {
                // The day turned over between the check above and the write: the same answer, S25.
                // Nothing else is reachable from this form, and nothing else has ratified words.
                val late = ConditionProblem.DateInFuture in refused.problems
                if (!late) Log.w(TAG, "a condition the form allowed was refused", refused)
                _state.update { it.copy(saving = false, refusal = if (late) DATE_NOT_LATER_THAN_TODAY else null) }
            } catch (gone: NoSuchAsset) {
                Log.w(TAG, "the asset left while its condition was being changed", gone)
                finish()
            }
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
