package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.ports.InstalledComponentRepository
import com.loosecannon.servicetag.data.room.dao.InstalledComponentDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The adapter for schema v19's port (#47, C6). Thin, as the supply adapters are: every rule about an installed
 * component — the name, the dates, the parent, the SupplyItems, the one successor per row — lives in the use cases
 * and the schema, so nothing here decides anything. There is no delete: a row leaves only by its Asset's CASCADE.
 */
class RoomInstalledComponentRepository(private val dao: InstalledComponentDao) : InstalledComponentRepository {
    override suspend fun get(id: InstalledComponentId): InstalledComponent? = dao.byId(id.value)?.toDomain()

    override suspend fun forAsset(assetId: AssetId): List<InstalledComponent> =
        dao.forAsset(assetId.value).map { it.toDomain() }

    override suspend fun all(): List<InstalledComponent> = dao.all().map { it.toDomain() }

    /** The row, then its entries, in one transaction. */
    override suspend fun insert(row: InstalledComponent) = dao.insert(
        row = row.toEntity(),
        composition = row.composition.map { it.toEntity(row.id) },
    )

    /** The row by an SQL `UPDATE`, then its entries replaced wholesale, in one transaction. */
    override suspend fun update(row: InstalledComponent) = dao.update(
        row = row.toEntity(),
        composition = row.composition.map { it.toEntity(row.id) },
    )

    override fun observeForAsset(assetId: AssetId): Flow<List<InstalledComponent>> =
        dao.observeForAsset(assetId.value).map { rows -> rows.map { it.toDomain() } }
}
