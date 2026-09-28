package com.loosecannon.servicetag.data.room.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

// Schema v14's one table (#77, C6; R77-3, R77-12): the transfer records. The v6 rules hold: TEXT enum names,
// instants as epoch-millisecond INTEGER, no `CHECK` anywhere (the value rules are the use cases' and the
// codec's, with tests).
//
// **Append-only, and no foreign key.** A record names its asset softly: it outlives the asset (`DeleteAsset`
// keeps it, R77-4) and is what every later ordinary backup carries in place of a transferred graph, so a
// CASCADE here would silently erase the one fact a restore needs. `lineage` is the pack ids before `pack_id`,
// oldest first, as a JSON array of strings. `asset_id` is indexed for the per-asset reads.

/** One OUT, IN or WITHDRAWN fact about an asset's custody; never updated, never deleted one by one. */
@Entity(
    tableName = "asset_transfer",
    indices = [Index("asset_id")],
)
data class TransferRecordEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    val kind: String,
    @ColumnInfo(name = "pack_id") val packId: String,
    /** A JSON array of pack ids, oldest first; `[]` at the asset's origin. */
    val lineage: String,
    val at: Long,
    @ColumnInfo(name = "pack_sha256") val packSha256: String,
    @ColumnInfo(name = "name_snapshot") val nameSnapshot: String,
    val note: String,
)
