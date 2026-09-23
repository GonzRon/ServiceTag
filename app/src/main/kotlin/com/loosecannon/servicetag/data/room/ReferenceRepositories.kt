package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.data.room.dao.AssetReferenceDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The adapter for schema v7's one new port. Its own file, so the lanes that follow never collide
 * with it, and a thin one: every rule about a reference — the scheme, the caps, the blank name —
 * lives in the use case, so nothing here decides anything.
 */
class RoomReferenceRepository(private val dao: AssetReferenceDao) : ReferenceRepository {
    override suspend fun upsert(reference: AssetReference) = dao.upsert(reference.toEntity())

    override suspend fun get(id: ReferenceId): AssetReference? = dao.byId(id.value)?.toDomain()

    override suspend fun forAsset(assetId: AssetId): List<AssetReference> =
        dao.forAsset(assetId.value).map { it.toDomain() }

    override suspend fun findByUri(assetId: AssetId, uri: String): AssetReference? =
        dao.findByUri(assetId.value, uri)?.toDomain()

    override suspend fun all(): List<AssetReference> = dao.all().map { it.toDomain() }

    override suspend fun delete(id: ReferenceId) = dao.delete(id.value)

    override suspend fun deleteAll() = dao.deleteAll()

    override fun observeForAsset(assetId: AssetId): Flow<List<AssetReference>> =
        dao.observeForAsset(assetId.value).map { rows -> rows.map { it.toDomain() } }
}
