package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ServiceCaseRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Opens one service case on an asset (#79, C14; R79-1, R79-3): **one header row, and nothing else** —
 * OPEN, with no `closedOn`, stamped created and updated now. It writes no timeline entry, no event, no
 * condition and no schedule; the Incident it names and the repair it may name are read, never written.
 *
 * [incidentEventId] is the failure the case is about: a non-completion INCIDENT of this asset (the
 * `currentIncident` rule, `CurrentIncident.kt:32`), or null. The phone's screens always open a case
 * from an Incident (R79-3); this use case, and the API over it, also accept one without, for
 * automation. The link is fixed here: no edit moves it.
 *
 * Every problem is collected before the write into one [ServiceCaseValidation] — the header's shape,
 * an `openedOn` after today, then the Incident, then the repair ([resolutionProblem]). A missing asset
 * is the shipped [NoSuchAsset].
 */
class OpenServiceCase(
    private val assets: AssetRepository,
    private val events: EventRepository,
    private val cases: ServiceCaseRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val today: Today,
) {
    suspend fun run(assetId: AssetId, cmd: ServiceCaseCommand, incidentEventId: EventId?): ServiceCase = uow.write {
        assets.get(assetId) ?: throw NoSuchAsset(assetId)
        val clean = cmd.trimmed()
        val problems = serviceCaseHeaderProblems(
            clean.title, clean.openedOn, clean.costMinor, clean.currency, today.localDate(),
        ).toMutableList()
        if (incidentEventId != null) {
            val incident = events.get(incidentEventId)
            val failure = incident != null && incident.assetId == assetId &&
                incident.kind == EventKind.INCIDENT && incident.scheduleId == null
            if (!failure) problems += ServiceCaseProblem.IncidentInvalid(incidentEventId)
        }
        resolutionProblem(events, assetId, clean.resolutionEventId, stored = null)?.let { problems += it }
        if (problems.isNotEmpty()) throw ServiceCaseValidation(problems)

        val now = clock.nowMillis()
        val row = ServiceCase(
            id = ServiceCaseId(ids.newId()),
            assetId = assetId,
            title = clean.title,
            type = clean.type,
            openedOn = clean.openedOn,
            closedOn = null,
            provider = clean.provider,
            contact = clean.contact,
            caseRef = clean.caseRef,
            coverage = clean.coverage,
            status = CaseStatus.OPEN,
            outboundTracking = clean.outboundTracking,
            outboundCarrier = clean.outboundCarrier,
            returnTracking = clean.returnTracking,
            returnCarrier = clean.returnCarrier,
            costMinor = clean.costMinor,
            currency = clean.currency,
            notes = clean.notes,
            incidentEventId = incidentEventId,
            resolutionEventId = clean.resolutionEventId,
            createdAt = now,
            updatedAt = now,
        )
        cases.upsert(row)
        row
    }
}
