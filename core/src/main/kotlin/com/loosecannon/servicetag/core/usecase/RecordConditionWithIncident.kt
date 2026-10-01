package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * What [RecordConditionWithIncident] stored: the Incident and the row that names it. On a repeat of a
 * committed save [incident] is the event the stored row names, or null once that event was deleted
 * (the link then dangles, spec §5.3).
 */
data class IncidentWithCondition(val incident: AssetEvent?, val condition: AssetCondition)

/**
 * The combined Save refused before any write (#82 C1, K3): both halves' problems, collected once —
 * the Incident's field problems, the held condition's, and whether the Incident is dated after
 * today, which an event alone may be but the combined Save may not (R82-3; the app says S25).
 */
class IncidentConditionRefused(
    val eventProblems: List<FieldProblem>,
    val conditionProblems: List<ConditionProblem>,
    val incidentAfterToday: Boolean,
) : IllegalArgumentException("combined save refused")

/**
 * Workflow A's commit (#82 C1, AC 3): an INCIDENT event and the DOWN or DEGRADED row that names it,
 * written in **one** `uow.write` — both facts, or neither. The condition row is still written by
 * [RecordCondition], nested here as [AcceptOperationalOffer] nests it (plan decision 51), so the one
 * writer of a condition row and its checks stay the only ones; the row's `eventId` is set at insert
 * and never afterwards (inv. 107, 109).
 *
 * In order, inside the transaction:
 * 1. the asset exists ([NoSuchAsset]);
 * 2. **idempotency** — a row of the asset already holding `conditionId` is answered with the pair it
 *    names and nothing is written, before anything is checked, so a Save after the commit landed
 *    (a second tap, a screen rebuilt after process death) succeeds without a second Incident;
 * 3. programming errors, before any write: the Incident is this asset's, kind INCIDENT and not a
 *    completion; the condition is DOWN or DEGRADED with no link of its own;
 * 4. both halves are checked and every problem collected into one [IncidentConditionRefused]: the
 *    event through [resolveOwnedProfile] and [buildEvent] (an ownership failure still throws
 *    [EventOwnership]), the condition through [recordedConditionProblems], and the Incident's date;
 * 5. the event is stored and its schedules recomputed, as [LogEvent] does, then the row is recorded
 *    under `conditionId` with the Incident's id. Any throw rolls back both.
 */
class RecordConditionWithIncident(
    private val events: EventRepository,
    private val definitions: DefinitionRepository,
    private val profiles: ProfileRepository,
    private val assets: AssetRepository,
    private val supplyItems: SupplyItemRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val recompute: RecomputeSchedules,
    private val conditions: ConditionRepository,
    private val today: Today,
    private val record: RecordCondition,
) {
    suspend fun run(
        assetId: AssetId,
        conditionId: String,
        condition: ConditionCommand,
        incident: EventCommand,
    ): IncidentWithCondition = uow.write {
        assets.get(assetId) ?: throw NoSuchAsset(assetId)
        conditions.forAsset(assetId).firstOrNull { it.id == conditionId }?.let { stored ->
            return@write IncidentWithCondition(stored.eventId?.let { events.get(it) }, stored)
        }
        require(incident.assetId == assetId) { "the Incident is another asset's" }
        require(incident.kind == EventKind.INCIDENT) { "the combined write logs an INCIDENT, not ${incident.kind}" }
        require(incident.scheduleId == null) { "a completion is never the combined write's Incident" }
        require(condition.condition != OperationalCondition.OPERATIONAL) { "the combined write records DOWN or DEGRADED" }
        require(condition.eventId == null) { "the condition's link is the Incident this write logs" }

        val profile = resolveOwnedProfile(incident, definitions, profiles)
        val (event, eventProblems) = try {
            buildEvent(incident, definitions, supplyItems, profile, existing = null, ids, clock.nowMillis()) to emptyList()
        } catch (e: EventValidation) {
            null to e.problems
        }
        val conditionProblems = recordedConditionProblems(condition, today.localDate())
        val incidentAfterToday = parseDate(incident.occurredOn)?.let { it > today.localDate() } == true
        if (event == null || conditionProblems.isNotEmpty() || incidentAfterToday) {
            throw IncidentConditionRefused(eventProblems, conditionProblems, incidentAfterToday)
        }

        events.upsert(event)
        recompute.forAsset(assetId)
        val row = record.run(assetId, condition.copy(eventId = event.id), rowId = conditionId)
        IncidentWithCondition(event, row)
    }
}
