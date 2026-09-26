package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.ports.CategoryRepository
import com.loosecannon.servicetag.data.room.dao.AssetCategoryDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Schema v9's adapter for #74's catalog rows. Thin: the key rule and promotion live in core. */
class RoomCategoryRepository(private val dao: AssetCategoryDao) : CategoryRepository {
    override suspend fun upsert(row: AssetCategory) = dao.upsert(row.toEntity())

    override suspend fun get(key: String): AssetCategory? = dao.byKey(key)?.toDomain()

    override suspend fun all(): List<AssetCategory> = dao.all().map { it.toDomain() }

    override suspend fun delete(key: String) = dao.delete(key)

    override suspend fun deleteAll() = dao.deleteAll()

    override fun observeAll(): Flow<List<AssetCategory>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }
}
