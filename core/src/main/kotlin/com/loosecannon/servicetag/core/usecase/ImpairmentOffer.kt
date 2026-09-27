package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.condition.ConditionHistory
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Workflow B's predicate (#82 C3, AC 4; K1, K2): true after an INCIDENT that is not a completion, on
 * the asset whose current condition — the last of [history] — is OPERATIONAL or not recorded, while
 * no row of [history] names that Incident yet. Anything else, including an INCIDENT on an asset that
 * is already DOWN or DEGRADED (R82-9), answers false.
 *
 * **Pure, and an offer only.** No text or kind decides a condition (inv. 81): nothing is written unless
 * the owner answers "Mark down" or "Mark degraded", through [AcceptImpairmentOffer]; "No change" writes
 * nothing. The app adds its own gates (dated no later than today, a resolving zone, in service).
 */
fun impairmentOfferFor(history: ConditionHistory, event: AssetEvent): Boolean {
    if (event.kind != EventKind.INCIDENT || event.scheduleId != null) return false
    val current = history.current
    if (current != null && (current.assetId != event.assetId || current.condition != OperationalCondition.OPERATIONAL)) {
        return false
    }
    return history.ordered.none { it.eventId == event.id }
}

/**
 * Accepting Workflow B (#82 C3, R82-5): one DOWN or DEGRADED row through [RecordCondition], linked to
 * the Incident by `eventId`, with an empty reason (the link names what went wrong) and the Incident's
 * zone. It is dated `max(the Incident's date, the current row's date)` and on that day carries the
 * later of the two known times, as [AcceptOperationalOffer] dates its row (plan decision 51), so the
 * answer becomes the current condition even after a backdated Incident.
 *
 * The rows are re-read inside **one write transaction**: a row that already names the Incident — a
 * second tap, a second screen — is returned as it is and nothing is written. OPERATIONAL is never an
 * answer here and is refused before anything is read.
 */
class AcceptImpairmentOffer(
    private val conditions: ConditionRepository,
    private val record: RecordCondition,
    private val uow: UnitOfWork,
) {
    suspend fun run(assetId: AssetId, event: AssetEvent, condition: OperationalCondition): AssetCondition {
        require(condition != OperationalCondition.OPERATIONAL) { "Workflow B records DOWN or DEGRADED" }
        return uow.write {
            val rows = conditions.forAsset(assetId)
            rows.firstOrNull { it.eventId == event.id }?.let { return@write it }
            val current: AssetCondition? = ConditionHistory.of(rows).current
            val on = listOfNotNull(event.occurredOn, current?.occurredOn).max()
            val at = listOfNotNull(
                event.occurredTime?.takeIf { event.occurredOn == on },
                current?.occurredTime?.takeIf { current.occurredOn == on },
            ).maxOrNull()
            record.run(
                assetId,
                ConditionCommand(
                    condition = condition,
                    occurredOn = on,
                    occurredTime = at,
                    tzId = event.tzId,
                    reason = "",
                    eventId = event.id,
                ),
            )
        }
    }
}
