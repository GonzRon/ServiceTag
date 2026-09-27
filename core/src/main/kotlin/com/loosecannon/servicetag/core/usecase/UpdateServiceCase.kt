package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Replaces a case's editable header in full (#79, C14): every field of [ServiceCaseCommand], trimmed,
 * over the stored row, in one write of that row alone. It **never** moves the asset, the Incident, the
 * status, `closedOn` or `createdAt` — the status and `closedOn` move only by a status entry (R79-5) —
 * and a header equal to the stored one writes nothing, not even the stamp.
 *
 * Linking the repair record is this write too (R79-10, link-only): a changed [ServiceCaseCommand.
 * resolutionEventId] must name a MAINTENANCE or REPLACEMENT of the case's asset, a null one removes the
 * link, and the stored link sent back unchanged passes even when its event is gone. Nothing here
 * writes the event, offers anything about it, or records a condition; the repair's own "Mark
 * operational?" offer is the event's, untouched.
 *
 * Every problem is collected before the write into one [ServiceCaseValidation]; a missing case is
 * [NoSuchServiceCase].
 */
class UpdateServiceCase(
    private val events: EventRepository,
    private val cases: ServiceCaseRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
    private val today: Today,
) {
    suspend fun run(caseId: ServiceCaseId, cmd: ServiceCaseCommand): ServiceCase = uow.write {
        val stored = cases.get(caseId) ?: throw NoSuchServiceCase(caseId)
        val clean = cmd.trimmed()
        val problems = serviceCaseHeaderProblems(
            clean.title, clean.openedOn, clean.costMinor, clean.currency, today.localDate(),
        ).toMutableList()
        resolutionProblem(events, stored.assetId, clean.resolutionEventId, stored.resolutionEventId)?.let { problems += it }
        if (problems.isNotEmpty()) throw ServiceCaseValidation(problems)

        val header = stored.copy(
            title = clean.title,
            type = clean.type,
            openedOn = clean.openedOn,
            provider = clean.provider,
            contact = clean.contact,
            caseRef = clean.caseRef,
            coverage = clean.coverage,
            outboundTracking = clean.outboundTracking,
            outboundCarrier = clean.outboundCarrier,
            returnTracking = clean.returnTracking,
            returnCarrier = clean.returnCarrier,
            costMinor = clean.costMinor,
            currency = clean.currency,
            notes = clean.notes,
            resolutionEventId = clean.resolutionEventId,
        )
        if (header == stored) return@write stored
        val row = header.copy(updatedAt = clock.nowMillis())
        cases.upsert(row)
        row
    }
}
