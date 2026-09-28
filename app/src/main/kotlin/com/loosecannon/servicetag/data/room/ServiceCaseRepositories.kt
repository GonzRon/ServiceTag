package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CaseCoverage
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.CaseType
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseEntry
import com.loosecannon.servicetag.core.model.ServiceCaseEntryId
import com.loosecannon.servicetag.core.model.ServiceCaseId
import com.loosecannon.servicetag.core.ports.ServiceCaseEntryRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseRepository
import com.loosecannon.servicetag.data.room.dao.ServiceCaseDao
import com.loosecannon.servicetag.data.room.dao.ServiceCaseEntryDao
import com.loosecannon.servicetag.data.room.entities.ServiceCaseEntity
import com.loosecannon.servicetag.data.room.entities.ServiceCaseEntryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// The adapters for schema v12's two ports (#79). Thin, as every adapter here is: the rules about a case
// and its timeline live in the use cases that write them, and nothing here decides anything. The entry
// adapter inserts and queries only, exactly as its port and DAO do.

class RoomServiceCaseRepository(private val dao: ServiceCaseDao) : ServiceCaseRepository {
    override suspend fun upsert(case: ServiceCase) = dao.upsert(case.toEntity())

    override suspend fun get(id: ServiceCaseId): ServiceCase? = dao.byId(id.value)?.toDomain()

    override suspend fun forAsset(assetId: AssetId): List<ServiceCase> = dao.forAsset(assetId.value).map { it.toDomain() }

    override suspend fun all(): List<ServiceCase> = dao.all().map { it.toDomain() }

    override suspend fun deleteAll() = dao.deleteAll()

    override fun observeForAsset(assetId: AssetId): Flow<List<ServiceCase>> =
        dao.observeForAsset(assetId.value).map { rows -> rows.map { it.toDomain() } }
}

class RoomServiceCaseEntryRepository(private val dao: ServiceCaseEntryDao) : ServiceCaseEntryRepository {
    override suspend fun insert(entry: ServiceCaseEntry) = dao.insert(entry.toEntity())

    override suspend fun forCase(caseId: ServiceCaseId): List<ServiceCaseEntry> =
        dao.forCase(caseId.value).map { it.toDomain() }

    override suspend fun all(): List<ServiceCaseEntry> = dao.all().map { it.toDomain() }

    override suspend fun deleteAll() = dao.deleteAll()

    override fun observeForCase(caseId: ServiceCaseId): Flow<List<ServiceCaseEntry>> =
        dao.observeForCase(caseId.value).map { rows -> rows.map { it.toDomain() } }
}

fun ServiceCaseEntity.toDomain(): ServiceCase = ServiceCase(
    id = ServiceCaseId(id),
    assetId = AssetId(assetId),
    title = title,
    type = CaseType.valueOf(type),
    openedOn = openedOn,
    closedOn = closedOn,
    provider = provider,
    contact = contact,
    caseRef = caseRef,
    coverage = CaseCoverage.valueOf(coverage),
    status = CaseStatus.valueOf(status),
    outboundTracking = outboundTracking,
    outboundCarrier = outboundCarrier,
    returnTracking = returnTracking,
    returnCarrier = returnCarrier,
    costMinor = costMinor,
    currency = currency,
    notes = notes,
    incidentEventId = incidentEventId?.let(::EventId),
    resolutionEventId = resolutionEventId?.let(::EventId),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ServiceCase.toEntity(): ServiceCaseEntity = ServiceCaseEntity(
    id = id.value,
    assetId = assetId.value,
    title = title,
    type = type.name,
    openedOn = openedOn,
    closedOn = closedOn,
    provider = provider,
    contact = contact,
    caseRef = caseRef,
    coverage = coverage.name,
    status = status.name,
    outboundTracking = outboundTracking,
    outboundCarrier = outboundCarrier,
    returnTracking = returnTracking,
    returnCarrier = returnCarrier,
    costMinor = costMinor,
    currency = currency,
    notes = notes,
    incidentEventId = incidentEventId?.value,
    resolutionEventId = resolutionEventId?.value,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ServiceCaseEntryEntity.toDomain(): ServiceCaseEntry = ServiceCaseEntry(
    id = ServiceCaseEntryId(id),
    caseId = ServiceCaseId(caseId),
    occurredOn = occurredOn,
    occurredTime = occurredTime,
    tzId = tzId,
    note = note,
    status = status?.let(CaseStatus::valueOf),
    createdAt = createdAt,
)

fun ServiceCaseEntry.toEntity(): ServiceCaseEntryEntity = ServiceCaseEntryEntity(
    id = id.value,
    caseId = caseId.value,
    occurredOn = occurredOn,
    occurredTime = occurredTime,
    tzId = tzId,
    note = note,
    status = status?.name,
    createdAt = createdAt,
)
