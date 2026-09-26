package com.loosecannon.servicetag.data.room.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Schema v9's one new table (#74, C10): the owner's own Asset categories. The primary key **is** the
 * normalised key (`CategoryKey.of(display)`, R74-2), so the same category saved on two phones is one
 * row. There is no foreign key from `asset` — `asset.category` stays free text holding this row's
 * display — and no row for a compiled built-in. Instants are epoch-millisecond INTEGERs.
 */
@Entity(tableName = "asset_category")
data class AssetCategoryEntity(
    @PrimaryKey val key: String,
    val display: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
