package com.loosecannon.servicetag.data.room.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

// Schema v18 (#15, C5; R15-1, R15-2, R15-3, R15-5): the SupplyItem catalog, its specifications and Asset
// applicability. Column order is the domain's. Same house rules as every table: snake_case columns, instants
// as epoch-millisecond INTEGER, `""` for no text, a durable id on every row and every child row so a backup
// round-trips it verbatim, and **no SQL `CHECK`** (Room cannot declare one; the rules are the use cases').
//
// The fence, by construction: no quantity, threshold, price, URL, position, fitting date, fitted serial,
// parent SupplyItem or owner column here (#95, #47, #69).

/**
 * One SupplyItem. Keyed by id and **never deleted** (R15-5): archiving is [archivedAt], and nothing above the
 * schema deletes a row — `asset_supply`'s RESTRICT is the last word for a row an Asset names. No index: the
 * catalog is small and every read is by id or whole.
 */
@Entity(tableName = "supply_item")
data class SupplyItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val manufacturer: String,
    val model: String,
    @ColumnInfo(name = "part_number") val partNumber: String,
    @ColumnInfo(name = "preferred_unit") val preferredUnit: String,
    val notes: String,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * One specification of a SupplyItem, owned by it (`supply_id`, CASCADE). `UNIQUE(supply_id, key)`: a key is
 * unique within its SupplyItem and only there; the index's left prefix serves the foreign key.
 */
@Entity(
    tableName = "supply_specification",
    foreignKeys = [
        ForeignKey(
            entity = SupplyItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["supply_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["supply_id", "key"], unique = true)],
)
data class SupplySpecificationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "supply_id") val supplyId: String,
    val key: String,
    val label: String,
    val value: String,
    val unit: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

/**
 * Applicability: an Asset takes a SupplyItem in a role. Owned by the Asset (`asset_id`, CASCADE — the
 * `asset_reference` shape); naming a SupplyItem the schema will not let go of (`supply_id`, RESTRICT, R15-5).
 * `UNIQUE(asset_id, supply_id, role)` (R15-3), whose left prefix serves the Asset's key; `supply_id` gets its
 * own index for the second foreign key.
 */
@Entity(
    tableName = "asset_supply",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SupplyItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["supply_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["asset_id", "supply_id", "role"], unique = true),
        Index("supply_id"),
    ],
)
data class AssetSupplyEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "supply_id") val supplyId: String,
    val role: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
