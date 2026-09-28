package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.ServiceCaseDto
import com.loosecannon.servicetag.core.backup.ServiceCaseEntryDto
import com.loosecannon.servicetag.core.model.CaseCoverage
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.CaseType
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.usecase.CaseEntryCommand
import com.loosecannon.servicetag.core.usecase.ServiceCaseCommand
import kotlinx.serialization.Serializable

/*
 * #79b's shapes on the wire (C24; R79-18).
 *
 * The rows are the archive's own: a case answers as `core.backup.ServiceCaseDto` and an entry as
 * `ServiceCaseEntryDto`, the format-12 rows, so the enum names (`CaseType`, `CaseCoverage`,
 * `CaseStatus`) travel verbatim and nothing derived rides on either — a case has no derived value.
 *
 * The requests are the command's fields and nothing else. **`status` and `closedOn` are in no
 * request**: they move only through a status entry (R79-5), so a header body naming either is the
 * shipped unknown-field 400 and is never applied. The API applies **none of the phone's form
 * defaults** — no coverage suggestion, no type from it, no `openedOn` of today, no asset currency:
 * `OpenServiceCase` stores what it is sent. `docs/api/command-shapes.json` pins the header's keys as
 * `serviceCase` (the open's; the replace takes every key but `assetId` and `incidentEventId`) and the
 * entry's as `caseEntry`.
 */

/**
 * `POST /v1/service-cases`: the header command, plus the case's asset and, optionally, the INCIDENT it
 * began from (R79-3: the API accepts an Incident-less case). `title`, `type`, `openedOn` and `coverage`
 * have no default.
 */
@Serializable
internal data class ServiceCaseCreateRequest(
    val assetId: String,
    val title: String,
    val type: String,
    val openedOn: String,
    val provider: String = "",
    val contact: String = "",
    val caseRef: String = "",
    val coverage: String,
    val outboundTracking: String = "",
    val outboundCarrier: String = "",
    val returnTracking: String = "",
    val returnCarrier: String = "",
    val costMinor: Long? = null,
    val currency: String? = null,
    val notes: String = "",
    val incidentEventId: String? = null,
    val resolutionEventId: String? = null,
) {
    /** The header command this body carries: every key but [assetId] and [incidentEventId]. */
    fun header() = ServiceCaseUpdateRequest(
        title = title,
        type = type,
        openedOn = openedOn,
        provider = provider,
        contact = contact,
        caseRef = caseRef,
        coverage = coverage,
        outboundTracking = outboundTracking,
        outboundCarrier = outboundCarrier,
        returnTracking = returnTracking,
        returnCarrier = returnCarrier,
        costMinor = costMinor,
        currency = currency,
        notes = notes,
        resolutionEventId = resolutionEventId,
    )
}

/**
 * `PATCH /v1/service-cases/{id}`: a **full replace** of the header's command fields. An optional field
 * left out is cleared — `resolutionEventId` included, so an omitted or `null` link removes the repair
 * record (`ServiceCaseCommand.resolutionEventId` has no default). The case's asset and Incident never
 * move, and its status and `closedOn` move only through `POST /v1/service-cases/{id}/entries`.
 */
@Serializable
internal data class ServiceCaseUpdateRequest(
    val title: String,
    val type: String,
    val openedOn: String,
    val provider: String = "",
    val contact: String = "",
    val caseRef: String = "",
    val coverage: String,
    val outboundTracking: String = "",
    val outboundCarrier: String = "",
    val returnTracking: String = "",
    val returnCarrier: String = "",
    val costMinor: Long? = null,
    val currency: String? = null,
    val notes: String = "",
    val resolutionEventId: String? = null,
)

internal fun ServiceCaseUpdateRequest.toCommand() = ServiceCaseCommand(
    title = title,
    type = enumOr400<CaseType>(type, "type"),
    openedOn = openedOn,
    coverage = enumOr400<CaseCoverage>(coverage, "coverage"),
    resolutionEventId = resolutionEventId?.let(::EventId),
    provider = provider,
    contact = contact,
    caseRef = caseRef,
    outboundTracking = outboundTracking,
    outboundCarrier = outboundCarrier,
    returnTracking = returnTracking,
    returnCarrier = returnCarrier,
    costMinor = costMinor,
    currency = currency,
    notes = notes,
)

/**
 * `POST /v1/service-cases/{id}/entries`: one immutable timeline entry. A note, a status, or both; a
 * status also moves the header's status, and CLOSED or CANCELLED sets its `closedOn` to [occurredOn]
 * (any other status clears it). `tzId` is a zone this phone resolves.
 */
@Serializable
internal data class CaseEntryRequest(
    val occurredOn: String,
    val occurredTime: String? = null,
    val tzId: String,
    val note: String = "",
    val status: String? = null,
)

internal fun CaseEntryRequest.toCommand() = CaseEntryCommand(
    occurredOn = occurredOn,
    occurredTime = occurredTime,
    tzId = tzId,
    note = note,
    status = status?.let { enumOr400<CaseStatus>(it, "status") },
)

/** `GET /v1/assets/{id}/service-cases`: the asset's cases, newest opened first, then by id. */
@Serializable
internal data class ServiceCaseListResponse(val serviceCases: List<ServiceCaseDto>)

/** `POST /v1/service-cases` and `PATCH /v1/service-cases/{id}`: the header as stored. */
@Serializable
internal data class ServiceCaseResponse(val serviceCase: ServiceCaseDto)

/** `GET /v1/service-cases/{id}`: the header and its whole timeline, in the timeline's order. */
@Serializable
internal data class ServiceCaseDetailResponse(val serviceCase: ServiceCaseDto, val entries: List<ServiceCaseEntryDto>)

/** `POST /v1/service-cases/{id}/entries`: the header after the entry — moved only by a status — and the entry. */
@Serializable
internal data class CaseEntryResponse(val serviceCase: ServiceCaseDto, val entry: ServiceCaseEntryDto)
