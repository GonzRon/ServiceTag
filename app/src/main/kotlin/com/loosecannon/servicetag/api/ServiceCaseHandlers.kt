package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.ServiceCaseId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseEntryRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseRepository
import com.loosecannon.servicetag.core.usecase.AddServiceCaseEntry
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.NoSuchServiceCase
import com.loosecannon.servicetag.core.usecase.OpenServiceCase
import com.loosecannon.servicetag.core.usecase.UpdateServiceCase
import com.loosecannon.servicetag.di.AppGraph

/**
 * #79b's five `/v1` rows (C24; R79-18), reached from the router as `handlers.serviceCases.*`.
 *
 * The rule is 1.2's: each write calls exactly one use case — `OpenServiceCase`, `UpdateServiceCase` or
 * `AddServiceCaseEntry` — and the two reads read the two repositories. **Nothing here deletes a case or
 * amends an entry**, because nothing in `:core` can (R79-8, R79-9): a case leaves the working set by a
 * CANCELLED or CLOSED status entry, and an entry is appended and never rewritten. No write here touches
 * an event, a condition, a schedule or its state.
 */
internal class ServiceCaseHandlers(
    private val assets: AssetRepository,
    private val cases: ServiceCaseRepository,
    private val entries: ServiceCaseEntryRepository,
    private val openServiceCase: OpenServiceCase,
    private val updateServiceCase: UpdateServiceCase,
    private val addServiceCaseEntry: AddServiceCaseEntry,
) {
    constructor(graph: AppGraph) : this(
        graph.assets, graph.serviceCases, graph.serviceCaseEntries,
        graph.openServiceCase, graph.updateServiceCase, graph.addServiceCaseEntry,
    )

    /** The two `/v1/status` counts, under the archive's own list names. */
    suspend fun counts(): Map<String, Int> = mapOf(
        "serviceCases" to cases.all().size,
        "serviceCaseEntries" to entries.all().size,
    )

    /** An asset's cases, newest opened first; a 404 for an asset that is not there. Writes nothing. */
    suspend fun listForAsset(assetId: String): ApiResponse {
        assets.get(AssetId(assetId)) ?: throw NoSuchAsset(AssetId(assetId))
        return ok(
            ServiceCaseListResponse.serializer(),
            ServiceCaseListResponse(cases.forAsset(AssetId(assetId)).map { it.toDto() }),
        )
    }

    /** Opens a case: OPEN, no `closedOn`, exactly the fields sent — the API applies no form default. */
    suspend fun open(request: ApiRequest): ApiResponse {
        val body = request.decode(ServiceCaseCreateRequest.serializer())
        val opened = openServiceCase.run(
            AssetId(body.assetId), body.header().toCommand(), body.incidentEventId?.let(::EventId),
        )
        return createdResponse(ServiceCaseResponse.serializer(), ServiceCaseResponse(opened.toDto()))
    }

    /** One case and its whole timeline. Writes nothing. */
    suspend fun get(caseId: String): ApiResponse {
        val id = ServiceCaseId(caseId)
        val case = cases.get(id) ?: throw NoSuchServiceCase(id)
        return ok(
            ServiceCaseDetailResponse.serializer(),
            ServiceCaseDetailResponse(case.toDto(), entries.forCase(id).map { it.toDto() }),
        )
    }

    /** A full replace of the header's command fields; status, `closedOn`, asset and Incident never move. */
    suspend fun update(caseId: String, request: ApiRequest): ApiResponse {
        val body = request.decode(ServiceCaseUpdateRequest.serializer())
        val saved = updateServiceCase.run(ServiceCaseId(caseId), body.toCommand())
        return ok(ServiceCaseResponse.serializer(), ServiceCaseResponse(saved.toDto()))
    }

    /** Appends one entry; a status entry moves the header's status and `closedOn` in the same write. */
    suspend fun addEntry(caseId: String, request: ApiRequest): ApiResponse {
        val body = request.decode(CaseEntryRequest.serializer())
        val added = addServiceCaseEntry.run(ServiceCaseId(caseId), body.toCommand())
        return createdResponse(CaseEntryResponse.serializer(), CaseEntryResponse(added.serviceCase.toDto(), added.entry.toDto()))
    }
}
