package com.loosecannon.servicetag.data.room.dao

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Relation
import androidx.room3.Transaction
import androidx.room3.Update
import com.loosecannon.servicetag.data.room.entities.AssetSupplyEntity
import com.loosecannon.servicetag.data.room.entities.SupplyItemEntity
import com.loosecannon.servicetag.data.room.entities.SupplySpecificationEntity

/** A SupplyItem row with its specification rows — the aggregate as one read. */
data class SupplyItemWithSpecifications(
    @Embedded val item: SupplyItemEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["supply_id"])
    val specifications: List<SupplySpecificationEntity>,
)

/**
 * Schema v18's catalog (#15, C5). **There is no delete of a SupplyItem here** (R15-5): the only writes are the
 * aggregate [upsert] and [setArchived]. [clearSpecifications] removes a SupplyItem's specification rows, never
 * the SupplyItem, and only inside [upsert]'s transaction.
 */
@Dao
interface SupplyItemDao {
    /**
     * Aggregate upsert, the [ProfileDao.upsert] shape: the row, then its specifications replaced wholesale.
     * Clearing the old rows before inserting the new ones is what makes a removed specification disappear and
     * lets two rows trade keys in one edit; the ids come from the caller, so a kept row keeps its identity.
     */
    @Transaction
    suspend fun upsert(item: SupplyItemEntity, specifications: List<SupplySpecificationEntity>) {
        if (update(item) == 0) insert(item)
        clearSpecifications(item.id)
        specifications.forEach { insertSpecification(it) }
    }

    @Update suspend fun update(e: SupplyItemEntity): Int

    @Insert suspend fun insert(e: SupplyItemEntity)

    @Insert suspend fun insertSpecification(e: SupplySpecificationEntity)

    @Query("DELETE FROM supply_specification WHERE supply_id = :supplyId")
    suspend fun clearSpecifications(supplyId: String)

    @Transaction
    @Query("SELECT * FROM supply_item WHERE id = :id")
    suspend fun byId(id: String): SupplyItemWithSpecifications?

    @Transaction
    @Query("SELECT * FROM supply_item ORDER BY id")
    suspend fun all(): List<SupplyItemWithSpecifications>

    /** Archive or unarchive: `archived_at` and the stamp, nothing else (R15-5). */
    @Query("UPDATE supply_item SET archived_at = :archivedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: String, archivedAt: Long?, updatedAt: Long): Int
}

/**
 * Schema v18's applicability rows (#15, C5). Insert, update and delete one row; read by Asset, by SupplyItem
 * and whole. A row leaves with its Asset by the CASCADE too.
 */
@Dao
interface AssetSupplyDao {
    @Insert suspend fun insert(e: AssetSupplyEntity)

    @Update suspend fun update(e: AssetSupplyEntity): Int

    @Query("SELECT * FROM asset_supply WHERE id = :id")
    suspend fun byId(id: String): AssetSupplyEntity?

    @Query("SELECT * FROM asset_supply WHERE asset_id = :assetId ORDER BY role, id")
    suspend fun forAsset(assetId: String): List<AssetSupplyEntity>

    @Query("SELECT * FROM asset_supply WHERE supply_id = :supplyId ORDER BY asset_id, role, id")
    suspend fun forSupply(supplyId: String): List<AssetSupplyEntity>

    @Query("SELECT * FROM asset_supply ORDER BY id")
    suspend fun all(): List<AssetSupplyEntity>

    @Query("DELETE FROM asset_supply WHERE id = :id")
    suspend fun delete(id: String)
}
