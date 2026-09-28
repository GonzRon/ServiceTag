package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.model.heldIds
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.data.room.dao.TransferRecordDao
import com.loosecannon.servicetag.data.room.entities.TransferRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

// The adapter for schema v14's one port (#77, C6). Thin, as every adapter here is: the one rule it applies —
// which assets are held — is core's `heldIds`, asked of every row; the lineage is a JSON array in one column.

class RoomTransferRecordRepository(private val dao: TransferRecordDao) : TransferRecordRepository {
    override suspend fun append(record: TransferRecord) = dao.insert(record.toEntity())

    override suspend fun all(): List<TransferRecord> = dao.all().map { it.toDomain() }

    override suspend fun forAsset(assetId: AssetId): List<TransferRecord> = dao.forAsset(assetId.value).map { it.toDomain() }

    override suspend fun heldIds(): Set<AssetId> = heldIds(all())

    override fun observeHeldIds(): Flow<Set<AssetId>> =
        dao.observeAll().map { rows -> heldIds(rows.map { it.toDomain() }) }.distinctUntilChanged()

    override suspend fun deleteAll() = dao.deleteAll()
}

private val LINEAGE = ListSerializer(String.serializer())

fun TransferRecordEntity.toDomain(): TransferRecord = TransferRecord(
    id = id,
    assetId = AssetId(assetId),
    kind = TransferKind.valueOf(kind),
    packId = packId,
    lineage = Json.decodeFromString(LINEAGE, lineage),
    at = at,
    packSha256 = packSha256,
    nameSnapshot = nameSnapshot,
    note = note,
)

fun TransferRecord.toEntity(): TransferRecordEntity = TransferRecordEntity(
    id = id,
    assetId = assetId.value,
    kind = kind.name,
    packId = packId,
    lineage = Json.encodeToString(LINEAGE, lineage),
    at = at,
    packSha256 = packSha256,
    nameSnapshot = nameSnapshot,
    note = note,
)
