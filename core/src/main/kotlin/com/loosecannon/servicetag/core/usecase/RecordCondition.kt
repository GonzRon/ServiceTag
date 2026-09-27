package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork
import java.time.LocalDate

/**
 * Records one operational condition (spec §5; master plan §9): **one immutable row, and nothing
 * else**. It writes no asset column — not even `updatedAt` (inv. 126) — recomputes nothing, and
 * touches no event, closure, schedule or health state (inv. 81, 83). A correction is simply a later
 * row; nothing here or anywhere amends or deletes one (inv. 107), so returning to OPERATIONAL leaves
 * every earlier row exactly as it was (inv. 110).
 *
 * The body is checked first, every problem collected into one 422 [ConditionValidation]:
 *
 * - `occurredOn` defaults to today; a value that is not an ISO date is [ConditionProblem.BadDate], and
 *   one after today is [ConditionProblem.DateInFuture];
 * - `occurredTime` and `tzId` in the shipped shape ([conditionFactProblems]);
 * - the reason is trimmed, may be empty, and holds at most 500 characters
 *   ([ConditionProblem.ReasonTooLong]);
 * - an `eventId` must name an event of this asset ([ConditionProblem.ForeignEvent]).
 *
 * A missing asset is the shipped [NoSuchAsset]. This is the one writer of a condition row: a scan, a
 * status, a season, an event, a completion or a health value never reaches it on its own — the
 * operational offer calls it only when the owner accepts ([AcceptOperationalOffer]), and #82's two
 * callers only on the owner's answer ([RecordConditionWithIncident], [AcceptImpairmentOffer]).
 *
 * #82 C2 (the contract G erratum): `run` takes an optional row id, never part of [ConditionCommand], so
 * no API body or MCP argument can reach it. It is answered **first**, before the body is checked: a
 * row of this asset already holding that id is returned as it is and nothing is written, so a held row
 * committed twice — a second tap, a Save after a lost screen — lands once, even if its command would
 * now be refused. Otherwise the new row takes that id instead of a minted one.
 */
class RecordCondition(
    private val assets: AssetRepository,
    private val events: EventRepository,
    private val conditions: ConditionRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val today: Today,
) {
    suspend fun run(assetId: AssetId, cmd: ConditionCommand, rowId: String? = null): AssetCondition = uow.write {
        assets.get(assetId) ?: throw NoSuchAsset(assetId)
        if (rowId != null) conditions.forAsset(assetId).firstOrNull { it.id == rowId }?.let { return@write it }
        val occurredOn = cmd.occurredOn.blankToNull() ?: today.localDate().toString()
        val occurredTime = cmd.occurredTime.blankToNull()
        val tzId = cmd.tzId.trim()
        val reason = cmd.reason.trim()

        val problems = recordedConditionProblems(cmd, today.localDate()).toMutableList()
        cmd.eventId?.let { id ->
            if (events.get(id)?.assetId != assetId) problems += ConditionProblem.ForeignEvent(id)
        }
        if (problems.isNotEmpty()) throw ConditionValidation(problems)

        val row = AssetCondition(
            id = rowId ?: ids.newId(),
            assetId = assetId,
            condition = cmd.condition,
            occurredOn = occurredOn,
            occurredTime = occurredTime,
            tzId = tzId,
            reason = reason,
            eventId = cmd.eventId,
            createdAt = clock.nowMillis(),
        )
        conditions.insert(row)
        row
    }
}

/**
 * Everything [RecordCondition] refuses in [cmd] before its link is looked at: the fact's shape
 * ([conditionFactProblems], in the zone data of this device) and a date after [today], first. #82's
 * combined write asks the same question of its condition half before anything is written.
 */
internal fun recordedConditionProblems(cmd: ConditionCommand, today: LocalDate): List<ConditionProblem> {
    val occurredOn = cmd.occurredOn.blankToNull() ?: today.toString()
    val problems = conditionFactProblems(
        occurredOn, cmd.occurredTime.blankToNull(), cmd.tzId.trim(), cmd.reason.trim(), ::resolvesHere,
    ).toMutableList()
    val on = parseDate(occurredOn)
    if (on != null && on > today) problems.add(0, ConditionProblem.DateInFuture)
    return problems
}
