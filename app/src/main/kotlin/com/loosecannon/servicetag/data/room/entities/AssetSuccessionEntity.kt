package com.loosecannon.servicetag.data.room.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

// Schema v15's one table (#86, C2; R86-1, R86-15): the successions. The v6 rules hold: ISO dates as TEXT, instants
// as epoch-millisecond INTEGER, no `CHECK` anywhere (the value rules are the codec's and the use case's, with tests).
//
// **Append-only, with two CASCADE keys.** A row is one immutable fact — this asset was replaced by that one on
// `replaced_on` — so it has no `updated_at` and the port has no update and no delete. Deleting either asset takes the
// row with it (R86-15), and nothing re-links around it. **At most one row per predecessor and one per successor** is
// a constraint (I2): each end carries its own UNIQUE index, which is also the index every foreign key column here
// carries on the house style. The use cases refuse a second row in the same write transaction first, so the indexes
// are the database's last word.

/** One asset replaced by a distinct successor; never updated, never deleted one by one. */
@Entity(
    tableName = "asset_succession",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["predecessor_asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["successor_asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["predecessor_asset_id"], unique = true),
        Index(value = ["successor_asset_id"], unique = true),
    ],
)
data class AssetSuccessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "predecessor_asset_id") val predecessorAssetId: String,
    @ColumnInfo(name = "successor_asset_id") val successorAssetId: String,
    @ColumnInfo(name = "replaced_on") val replacedOn: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
