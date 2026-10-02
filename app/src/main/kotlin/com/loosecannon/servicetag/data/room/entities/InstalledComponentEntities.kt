package com.loosecannon.servicetag.data.room.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

// Schema v19 (#47, C6; R47-2, R47-3, R47-17a): installed components — one row per fitted instance — and each row's
// ordered composition. Column order is the domain's. The house rules hold: snake_case columns, ISO days as TEXT,
// instants as epoch-millisecond INTEGER, `""` for no text, a durable id on every row and every child row so a backup
// round-trips it verbatim, and **no SQL `CHECK`** (the name, date and quantity rules are the use cases').
//
// H1: every self- and child foreign key is **CASCADE**, never RESTRICT. SQLite checks a RESTRICT the moment a parent
// row is deleted, so a RESTRICT `parent_id` would refuse a parent the asset's CASCADE reaches before its child. The
// two `supply_id` keys are RESTRICT: a SupplyItem is archive-only (R15-5), and the replace wipe deletes the assets —
// and with them these rows — before the catalog. `replaces_id` is a **soft link** with no foreign key (the R79-4
// shape): the row it names is history on the same asset, and nothing deletes one row alone.

/**
 * One fitted instance in an Asset (`asset_id`, CASCADE), optionally inside another row of the same Asset
 * (`parent_id`, CASCADE — a row's subtree goes with it), optionally naming a SupplyItem it **is** (`supply_id`,
 * RESTRICT). `removed_on` null is a current row; a successor names its predecessor in `replaces_id`, which is
 * `UNIQUE` (one successor per row; SQLite admits any number of NULLs). The three key columns are indexed for their
 * foreign keys, `supply_id` non-unique (several rows may name one SupplyItem).
 */
@Entity(
    tableName = "installed_component",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = InstalledComponentEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
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
        Index("asset_id"),
        Index("parent_id"),
        Index("supply_id"),
        Index(value = ["replaces_id"], unique = true),
    ],
)
data class InstalledComponentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "parent_id") val parentId: String?,
    val name: String,
    @ColumnInfo(name = "supply_id") val supplyId: String?,
    @ColumnInfo(name = "serial_or_lot") val serialOrLot: String,
    @ColumnInfo(name = "installed_on") val installedOn: String?,
    @ColumnInfo(name = "removed_on") val removedOn: String?,
    @ColumnInfo(name = "replaces_id") val replacesId: String?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val notes: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * One entry of an installed component's composition, owned by its row (`component_id`, CASCADE — it closes and
 * leaves with its row) and naming a SupplyItem (`supply_id`, RESTRICT). `quantity` is how many of that SupplyItem
 * one unit is made of, in `unit`; nothing is counted, kept or used up by it. Both keys indexed, non-unique: one
 * SupplyItem may be named by several entries, on one row or several.
 */
@Entity(
    tableName = "installed_component_composition",
    foreignKeys = [
        ForeignKey(
            entity = InstalledComponentEntity::class,
            parentColumns = ["id"],
            childColumns = ["component_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SupplyItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["supply_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("component_id"), Index("supply_id")],
)
data class InstalledComponentCompositionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "component_id") val componentId: String,
    @ColumnInfo(name = "supply_id") val supplyId: String,
    val quantity: Double,
    val unit: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)
