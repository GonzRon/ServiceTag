package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.data.room.dao.AssetLoanDao
import com.loosecannon.servicetag.data.room.entities.AssetLoanEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// The adapter for schema v13's one port (#72). Thin, as every adapter here is: the rules about a loan
// live in the use cases that write it. The one thing this file decides is the open marker, from the
// return date alone, and it is decided here so that no caller can set it wrong.

class RoomAssetLoanRepository(private val dao: AssetLoanDao) : AssetLoanRepository {
    override suspend fun upsert(loan: AssetLoan) = dao.upsert(loan.toEntity())

    override suspend fun get(id: AssetLoanId): AssetLoan? = dao.byId(id.value)?.toDomain()

    override suspend fun forAsset(assetId: AssetId): List<AssetLoan> = dao.forAsset(assetId.value).map { it.toDomain() }

    override suspend fun openFor(assetId: AssetId): AssetLoan? = dao.openFor(assetId.value)?.toDomain()

    override suspend fun open(): List<AssetLoan> = dao.open().map { it.toDomain() }

    override suspend fun all(): List<AssetLoan> = dao.all().map { it.toDomain() }

    override suspend fun deleteAll() = dao.deleteAll()

    override fun observeForAsset(assetId: AssetId): Flow<List<AssetLoan>> =
        dao.observeForAsset(assetId.value).map { rows -> rows.map { it.toDomain() } }

    override fun observeOpen(): Flow<List<AssetLoan>> = dao.observeOpen().map { rows -> rows.map { it.toDomain() } }
}

/** The marker is not read back: open is `returned_on IS NULL`, which the marker only mirrors. */
fun AssetLoanEntity.toDomain(): AssetLoan = AssetLoan(
    id = AssetLoanId(id),
    assetId = AssetId(assetId),
    borrowerName = borrowerName,
    contactLookupUri = contactLookupUri,
    lentOn = lentOn,
    dueOn = dueOn,
    returnedOn = returnedOn,
    reminderMode = LoanReminderMode.valueOf(reminderMode),
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/** `open_marker` is 1 exactly while the loan is open, and NULL once it is returned (C2 ii). */
fun AssetLoan.toEntity(): AssetLoanEntity = AssetLoanEntity(
    id = id.value,
    assetId = assetId.value,
    borrowerName = borrowerName,
    contactLookupUri = contactLookupUri,
    lentOn = lentOn,
    dueOn = dueOn,
    returnedOn = returnedOn,
    reminderMode = reminderMode.name,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    openMarker = if (returnedOn == null) 1 else null,
)
