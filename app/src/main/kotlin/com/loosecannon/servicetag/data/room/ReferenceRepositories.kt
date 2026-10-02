package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceOwner
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

    override suspend fun forOwner(owner: ReferenceOwner): List<AssetReference> = when (owner) {
        is ReferenceOwner.OfAsset -> dao.forAsset(owner.assetId.value)
        is ReferenceOwner.OfSupplyItem -> dao.forSupplyItem(owner.supplyId.value)
        is ReferenceOwner.OfInstalledComponent -> dao.forInstalledComponent(owner.componentId.value)
    }.map { it.toDomain() }

    override suspend fun findByUri(owner: ReferenceOwner, uri: String): AssetReference? = when (owner) {
        is ReferenceOwner.OfAsset -> dao.findByUri(owner.assetId.value, uri)
        is ReferenceOwner.OfSupplyItem -> dao.findByUriOnSupplyItem(owner.supplyId.value, uri)
        is ReferenceOwner.OfInstalledComponent -> dao.findByUriOnInstalledComponent(owner.componentId.value, uri)
    }?.toDomain()

    override suspend fun all(): List<AssetReference> = dao.all().map { it.toDomain() }

    override suspend fun delete(id: ReferenceId) = dao.delete(id.value)

    override suspend fun deleteAll() = dao.deleteAll()

    override fun observeForOwner(owner: ReferenceOwner): Flow<List<AssetReference>> = when (owner) {
        is ReferenceOwner.OfAsset -> dao.observeForAsset(owner.assetId.value)
        is ReferenceOwner.OfSupplyItem -> dao.observeForSupplyItem(owner.supplyId.value)
        is ReferenceOwner.OfInstalledComponent -> dao.observeForInstalledComponent(owner.componentId.value)
    }.map { rows -> rows.map { it.toDomain() } }
}
