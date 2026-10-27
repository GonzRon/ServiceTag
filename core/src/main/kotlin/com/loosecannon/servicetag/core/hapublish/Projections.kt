package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.condition.ConditionHistory
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.season.Readiness

/** #113 (C1) — the equipment-status helper's three options, exactly; no fourth exists. */
const val STATUS_UP = "UP"
const val STATUS_DEGRADED = "DEGRADED"
const val STATUS_DOWN = "DOWN"

/** The option set an equipment-status helper must carry, compared as a set (C16). */
val STATUS_OPTIONS: Set<String> = setOf(STATUS_UP, STATUS_DEGRADED, STATUS_DOWN)

/**
 * #113 (C15) — the equipment-status value: the current condition alone. No row → null, never a fabricated `UP`.
 * Reads [ConditionHistory.current] only, so a new reason with the same value, a duplicate or a backdated row that
 * does not become current changes nothing.
 */
fun equipmentStatusOf(history: ConditionHistory): String? = when (history.current?.condition) {
    OperationalCondition.OPERATIONAL -> STATUS_UP
    OperationalCondition.DEGRADED -> STATUS_DEGRADED
    OperationalCondition.DOWN -> STATUS_DOWN
    null -> null
}

/** #114 (C15) — the service date: [Readiness.Ready]'s day, or [INVALID_SERVICE_DATE] for every not-ready answer. */
fun seasonDateOf(readiness: Readiness): String = when (readiness) {
    is Readiness.Ready -> readiness.serviceOn.toString()
    is Readiness.NotReady -> INVALID_SERVICE_DATE
}

/** #113/#114 (C15) — what a binding should hold now: a value to send, or nothing, with the reason. */
sealed interface Desired {
    data class Send(val value: String) : Desired

    data class Nothing(val state: PublishState) : Desired
}

/**
 * #113/#114 (C15) — the desired value of [binding], pure. [asset] is null when its row is gone; [maintainedHere] is
 * the shipped `Asset.maintainedHere(held)` answer.
 *
 * The two kinds part ways when the asset leaves (K12): the status kind stops and keeps the remote
 * (`Nothing(NOT_MAINTAINED_HERE)`); the date kind invalidates first (`Send(1970-01-01)`). Otherwise the status kind
 * is [equipmentStatusOf] (null → `Nothing(WAITING_FOR_SOURCE)`) and the date kind is [seasonDateOf]. The status kind
 * never reads [readiness], the asset's season or anything but the condition history.
 */
fun desiredValueOf(
    binding: PublishBinding,
    asset: Asset?,
    maintainedHere: Boolean,
    history: ConditionHistory,
    readiness: Readiness,
): Desired {
    val here = asset != null && maintainedHere
    return when (binding.kind) {
        PublishKind.EQUIPMENT_STATUS ->
            if (!here) {
                Desired.Nothing(PublishState.NOT_MAINTAINED_HERE)
            } else {
                equipmentStatusOf(history)?.let { Desired.Send(it) } ?: Desired.Nothing(PublishState.WAITING_FOR_SOURCE)
            }
        PublishKind.SEASON_SERVICE_DATE ->
            Desired.Send(if (here) seasonDateOf(readiness) else INVALID_SERVICE_DATE)
    }
}
