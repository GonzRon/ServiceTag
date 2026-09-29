package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetSuccession
import com.loosecannon.servicetag.core.ports.AssetSuccessionRepository
import com.loosecannon.servicetag.data.room.dao.AssetSuccessionDao
import com.loosecannon.servicetag.data.room.entities.AssetSuccessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// The adapter for schema v15's one port (#86, C2). Thin, as every adapter here is: `append` is an abort-on-conflict
// insert, and the rules — I1–I4 — are core's `successionProblems`, asked by the codec, the merge and the use case.

class RoomAssetSuccessionRepository(private val dao: AssetSuccessionDao) : AssetSuccessionRepository {
    override suspend fun append(row: AssetSuccession) = dao.insert(row.toEntity())

    override suspend fun all(): List<AssetSuccession> = dao.all().map { it.toDomain() }

    override suspend fun replacedBy(predecessor: AssetId): AssetSuccession? = dao.byPredecessor(predecessor.value)?.toDomain()

    override suspend fun replaces(successor: AssetId): AssetSuccession? = dao.bySuccessor(successor.value)?.toDomain()

    override suspend fun deleteAll() = dao.deleteAll()

    override fun observeForAsset(assetId: AssetId): Flow<List<AssetSuccession>> =
        dao.observeForAsset(assetId.value).map { rows -> rows.map { it.toDomain() } }
}

fun AssetSuccessionEntity.toDomain(): AssetSuccession = AssetSuccession(
    id = id,
    predecessorAssetId = AssetId(predecessorAssetId),
    successorAssetId = AssetId(successorAssetId),
    replacedOn = replacedOn,
    createdAt = createdAt,
)

fun AssetSuccession.toEntity(): AssetSuccessionEntity = AssetSuccessionEntity(
    id = id,
    predecessorAssetId = predecessorAssetId.value,
    successorAssetId = successorAssetId.value,
    replacedOn = replacedOn,
    createdAt = createdAt,
)
