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
 * The "Mark operational?" offer's predicate (spec §5.4; master plan §9): true after a completion (an
 * event that names a schedule) or a `MAINTENANCE` or `REPLACEMENT` event, on the asset whose
 * [current] condition is DOWN or DEGRADED. Anything else, including an asset with no condition
 * recorded, answers false.
 *
 * **Pure, and an offer only.** An event never changes a condition by itself (inv. 81): nothing is
 * written unless the owner accepts, through [AcceptOperationalOffer], and "Not yet" writes nothing.
 */
fun operationalOfferFor(current: AssetCondition?, event: AssetEvent): Boolean {
    if (current == null || current.assetId != event.assetId) return false
    val impaired = current.condition == OperationalCondition.DOWN || current.condition == OperationalCondition.DEGRADED
    val restores = event.scheduleId != null || event.kind == EventKind.MAINTENANCE || event.kind == EventKind.REPLACEMENT
    return impaired && restores
}

/**
 * Accepting the operational offer (plan decision 51): one OPERATIONAL row through [RecordCondition],
 * dated `max(the event's date, the current row's date)` so it can never sort before the condition it
 * ends, with the event linked by `eventId`. On that day the row carries the later of the two known
 * times (the event's, the current row's; none is earliest), for the same reason one level down: a
 * same-day DOWN recorded at 14:00 must not outlast an OPERATIONAL with no time, which the ordering
 * key would put first. The current row is read and the new one recorded in **one write
 * transaction**, so the date is taken from the history the row actually lands on.
 *
 * The earlier rows are left exactly as they were: the DOWN or DEGRADED row that prompted the offer
 * stays in the history (inv. 110).
 */
class AcceptOperationalOffer(
    private val conditions: ConditionRepository,
    private val record: RecordCondition,
    private val uow: UnitOfWork,
) {
    suspend fun run(assetId: AssetId, event: AssetEvent): AssetCondition = uow.write {
        val current = ConditionHistory.of(conditions.forAsset(assetId)).current
        val on = listOfNotNull(event.occurredOn, current?.occurredOn).max()
        val at = listOfNotNull(
            event.occurredTime?.takeIf { event.occurredOn == on },
            current?.occurredTime?.takeIf { current.occurredOn == on },
        ).maxOrNull()
        record.run(
            assetId,
            ConditionCommand(
                condition = OperationalCondition.OPERATIONAL,
                occurredOn = on,
                occurredTime = at,
                tzId = event.tzId,
                eventId = event.id,
            ),
        )
    }
}
