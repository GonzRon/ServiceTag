package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetSupply
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.ports.AssetSupplyRepository
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.data.room.dao.AssetSupplyDao
import com.loosecannon.servicetag.data.room.dao.SupplyItemDao

/**
 * The adapters for schema v18's two ports (#15, C5). Thin, as the reference adapter is: every rule about a
 * SupplyItem or an applicability row — the name, the keys, the role, the archived refusal — lives in the use
 * cases, so nothing here decides anything.
 */
class RoomSupplyItemRepository(private val dao: SupplyItemDao) : SupplyItemRepository {
    override suspend fun get(id: SupplyId): SupplyItem? = dao.byId(id.value)?.toDomain()

    override suspend fun all(): List<SupplyItem> = dao.all().map { it.toDomain() }

    /** The row, then its specification rows replaced wholesale, in one transaction. */
    override suspend fun upsert(item: SupplyItem) = dao.upsert(
        item = item.toEntity(),
        specifications = item.specifications.map { it.toEntity(item.id) },
    )

    override suspend fun setArchived(id: SupplyId, archivedAt: Long?, updatedAt: Long) {
        dao.setArchived(id.value, archivedAt, updatedAt)
    }
}

class RoomAssetSupplyRepository(private val dao: AssetSupplyDao) : AssetSupplyRepository {
    override suspend fun get(id: String): AssetSupply? = dao.byId(id)?.toDomain()

    override suspend fun forAsset(assetId: AssetId): List<AssetSupply> =
        dao.forAsset(assetId.value).map { it.toDomain() }

    override suspend fun forSupply(supplyId: SupplyId): List<AssetSupply> =
        dao.forSupply(supplyId.value).map { it.toDomain() }

    override suspend fun all(): List<AssetSupply> = dao.all().map { it.toDomain() }

    override suspend fun insert(row: AssetSupply) = dao.insert(row.toEntity())

    override suspend fun update(row: AssetSupply) {
        dao.update(row.toEntity())
    }

    override suspend fun delete(id: String) = dao.delete(id)
}
