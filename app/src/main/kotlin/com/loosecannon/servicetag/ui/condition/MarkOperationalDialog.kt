package com.loosecannon.servicetag.ui.condition

import android.util.Log
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.usecase.ConditionCommand
import com.loosecannon.servicetag.core.usecase.RecordCondition
import com.loosecannon.servicetag.di.AppGraph
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
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
 * **Mark operational** (spec §5.4, §10.1): S17 over S18 with the [current] word substituted, **S7**
 * confirming — one OPERATIONAL row dated today — and Cancel writing nothing. S7 disables on its tap,
 * so a double tap records one row.
 *
 * It takes the [graph] every screen here takes, beside the pinned `assetId`, `current` and `onDone`.
 */
@Composable
fun MarkOperationalDialog(graph: AppGraph, assetId: String, current: OperationalCondition, onDone: () -> Unit) {
    val confirm = remember(graph) { MarkOperationalConfirm(graph.recordCondition) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text(MARK_OPERATIONAL_TITLE) },
        text = { Text(markOperationalBody(current), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            confirm.run(AssetId(assetId))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (refused: Exception) {
                            // No ratified sentence exists for a refusal here; the sheet re-reads the
                            // store on return, so what it shows is what is recorded.
                            Log.w("MarkOperational", "marking operational was refused", refused)
                        }
                        onDone()
                    }
                },
            ) { Text(MARK_OPERATIONAL) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDone) { Text("Cancel") } },
    )
}
