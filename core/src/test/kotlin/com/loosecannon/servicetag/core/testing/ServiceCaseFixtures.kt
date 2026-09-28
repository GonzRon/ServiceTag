package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CaseCoverage
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.CaseType
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseEntry
import com.loosecannon.servicetag.core.model.ServiceCaseEntryId
import com.loosecannon.servicetag.core.model.ServiceCaseId

/**
 * #79 — a service case header as a restore or a merge sees one: every field set to something a test
 * could be wrong about, the Incident and repair links dangling unless given, and — as the use cases
 * keep it — a `closedOn` exactly when the status is CLOSED or CANCELLED. The names are fictional.
 */
fun caseOf(
    id: String,
    assetId: String = "a1",
    status: CaseStatus = CaseStatus.SENT_OUT,
    closedOn: String? = null,
    title: String = "Example Heater claim",
    costMinor: Long? = 4_500L,
    currency: String? = "EUR",
    incident: String? = "e-incident-gone",
    resolution: String? = null,
    updatedAt: Long = 1_758_600_000_000L,
): ServiceCase = ServiceCase(
    id = ServiceCaseId(id),
    assetId = AssetId(assetId),
    title = title,
    type = CaseType.WARRANTY_SERVICE,
    openedOn = "2026-09-20",
    closedOn = closedOn,
    provider = "Northwind Service",
    contact = "0100 000 000",
    caseRef = "RMA-0001",
    coverage = CaseCoverage.PARTLY_COVERED,
    status = status,
    outboundTracking = "TRK-OUT-1",
    outboundCarrier = "Parcel Co",
    returnTracking = "",
    returnCarrier = "",
    costMinor = costMinor,
    currency = currency,
    notes = "Photos sent with the claim",
    incidentEventId = incident?.let(::EventId),
    resolutionEventId = resolution?.let(::EventId),
    createdAt = 1_758_500_000_000L,
    updatedAt = updatedAt,
)

/** One timeline entry on [caseId]: a note, a status, or both. */
fun caseEntryOf(
    id: String,
    caseId: String = "c1",
    note: String = "Courier booked",
    status: CaseStatus? = null,
    occurredOn: String = "2026-09-21",
    occurredTime: String? = "09:30",
    createdAt: Long = 1_758_550_000_000L,
): ServiceCaseEntry = ServiceCaseEntry(
    id = ServiceCaseEntryId(id),
    caseId = ServiceCaseId(caseId),
    occurredOn = occurredOn,
    occurredTime = occurredTime,
    tzId = "UTC",
    note = note,
    status = status,
    createdAt = createdAt,
)
