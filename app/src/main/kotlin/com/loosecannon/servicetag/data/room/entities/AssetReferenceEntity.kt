package com.loosecannon.servicetag.data.room.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Schema v7 (spec §3.2). One row per URI on an asset; there are **no bytes** here and none
 * anywhere else for it — no locator, no sha256, no artifacts entry (I-3). The 2.6 tombstone
 * `external_link` is untouched beside it: this is a new table with a new name, id type and shape,
 * and that both hold a URI is not a reason to reuse a tombstone (I-5).
 *
 * `asset_id` is the only foreign key a reference has, CASCADE so deleting the asset takes the row
 * with it — there are no bytes for a delete use case to sweep first. Only an asset owns a
 * reference, and it can never change owner (I-6).
 *
 * `(asset_id, uri)` is unique (I-7): one asset holds a URI once, and the **same** URI on two
 * different assets is ordinary. The second index on `asset_id` alone is a left prefix of the first
 * and so redundant to the query planner; it is declared because spec §3.2 declares it, and because
 * a Room entity whose `indices` disagree with the exported schema will not open.
 *
 * There is deliberately **no SQL `CHECK`** — Room does not model one in its schema hash, so it
 * would be invisible to migration validation — and no `provenance` column (D-21 C). The length
 * caps and the blank-name rule are the use case's, the same shape as `attachment`'s.
 */
@Entity(
    tableName = "asset_reference",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["asset_id", "uri"], unique = true),
        Index("asset_id"),
    ],
)
data class AssetReferenceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    val kind: String,
    val uri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val description: String,
    val scheme: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
