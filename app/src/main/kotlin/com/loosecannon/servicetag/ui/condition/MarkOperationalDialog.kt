package com.loosecannon.servicetag.ui.condition

import android.util.Log
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.usecase.ConditionCommand
import com.loosecannon.servicetag.core.usecase.RecordCondition
import com.loosecannon.servicetag.di.AppGraph
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** S7: the scan sheet's action, this dialog's confirm, and the offer's accept. */
const val MARK_OPERATIONAL = "Mark operational"

/** S17: this dialog's title, and the offer's. */
const val MARK_OPERATIONAL_TITLE = "Mark operational?"

/** S18, "The earlier <DOWN/DEGRADED> record stays in the history.", with the current word substituted. */
fun markOperationalBody(current: OperationalCondition): String =
    "The earlier ${conditionWord(current)} record stays in the history."

/**
 * The Mark operational confirmation's one write: OPERATIONAL, **dated today** (a null `occurredOn` is
 * today to [RecordCondition]), in the device zone, with no reason and no event. It is a new row —
 * the DOWN or DEGRADED one before it stays exactly as it was (inv. 110).
 */
class MarkOperationalConfirm(
    private val record: RecordCondition,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    suspend fun run(assetId: AssetId): AssetCondition =
        record.run(assetId, ConditionCommand(condition = OperationalCondition.OPERATIONAL, tzId = zone().id))
}

/**
 * The Mark operational confirmation as state: [busy] from the tap until the row is written, [done]
 * once the dialog should close — after the write, or at once for Cancel.
 */
data class MarkOperationalState(val busy: Boolean = false, val done: Boolean = false)

/**
 * The confirmation's write, in a **view model's** scope rather than the dialog's (the controller's
 * ruling on B12's review, M-3): a rotation disposes the dialog's composition, and a write started in
 * that composition's scope would be cancelled with it. The view model outlives the rotation, so an
 * S7 tap is written exactly once whatever the screen does meanwhile.
 */
class MarkOperationalViewModel(
    private val confirm: MarkOperationalConfirm,
    private val assetId: AssetId,
) : ViewModel() {

    constructor(graph: AppGraph, assetId: String) : this(MarkOperationalConfirm(graph.recordCondition), AssetId(assetId))

    private val _state = MutableStateFlow(MarkOperationalState())
    val state: StateFlow<MarkOperationalState> = _state.asStateFlow()

    /** **S7**: one OPERATIONAL row. The guard is set before the first suspension, so a double tap writes one. */
    fun confirm() {
        if (_state.value.busy || _state.value.done) return
        _state.value = MarkOperationalState(busy = true)
        viewModelScope.launch {
            try {
                confirm.run(assetId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: Exception) {
                // No ratified sentence exists for a refusal here; the sheet re-reads the store on
                // return, so what it shows is what is recorded.
                Log.w(TAG, "marking operational was refused", refused)
            }
            _state.value = MarkOperationalState(done = true)
        }
    }

    /** **Cancel** (and the dialog dismissed): nothing is written. Ignored while the write is in flight. */
    fun cancel() {
        if (_state.value.busy) return
        _state.value = MarkOperationalState(done = true)
    }

    /** The host has closed the dialog; the next opening starts fresh. */
    fun closed() {
        _state.value = MarkOperationalState()
    }

    private companion object {
        const val TAG = "MarkOperational"
    }
}

/**
 * **Mark operational** (spec §5.4, §10.1): S17 over S18 with the [current] word substituted, **S7**
 * confirming — one OPERATIONAL row dated today — and Cancel writing nothing. S7 disables on its tap,
 * so a double tap records one row, and the write belongs to [MarkOperationalViewModel], so a rotation
 * mid-write neither cancels it nor loses the dialog's state.
 *
 * It takes the [graph] every screen here takes, beside the pinned `assetId`, `current` and `onDone`.
 */
@Composable
fun MarkOperationalDialog(graph: AppGraph, assetId: String, current: OperationalCondition, onDone: () -> Unit) {
    val model: MarkOperationalViewModel = viewModel(key = "mark-operational/$assetId") {
        MarkOperationalViewModel(graph, assetId)
    }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) {
        if (state.done) {
            model.closed()
            onDone()
        }
    }
    AlertDialog(
        onDismissRequest = model::cancel,
        title = { Text(MARK_OPERATIONAL_TITLE) },
        text = { Text(markOperationalBody(current), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(enabled = !state.busy, onClick = model::confirm) { Text(MARK_OPERATIONAL) }
        },
        dismissButton = { TextButton(enabled = !state.busy, onClick = model::cancel) { Text("Cancel") } },
    )
}
