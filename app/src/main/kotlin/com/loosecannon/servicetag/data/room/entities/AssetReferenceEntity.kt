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
 * Since schema v20 (#69, C7) a reference has three possible owners, each a nullable foreign key,
 * CASCADE so deleting the owner takes the row with it — there are no bytes for a delete use case to
 * sweep first: `asset_id` (an asset), `supply_item_id` (a SupplyItem) and `installed_component_id`
 * (an installed component). Exactly one is set — enforced on every Room write by
 * [com.loosecannon.servicetag.data.room.requireExactlyOneOwner], since there is no `CHECK` (below) —
 * and a reference never changes owner (I-6). The owner model is D14's (`docs/design/14-asset-model.md`).
 *
 * A URI is unique **per owner** (I-7): one owner holds a URI once, and the **same** URI on two
 * different owners is ordinary. That takes one unique index per owner column — `(asset_id, uri)`,
 * `(supply_item_id, uri)`, `(installed_component_id, uri)` — because SQLite treats NULLs as distinct,
 * so no single index over the three columns could hold. The second index on `asset_id` alone is a
 * left prefix of the first and so redundant to the query planner; it is declared because spec §3.2
 * declares it, and because a Room entity whose `indices` disagree with the exported schema will not
 * open. The two newer owner columns get no single-column twin: each unique pair's left prefix serves.
 *
 * There is deliberately **no SQL `CHECK`** — Room does not model one in its schema hash, so it
 * would be invisible to migration validation — and no `provenance` column (D-21 C). The length
 * caps and the blank-name rule are the use case's, the same shape as `attachment`'s.
 *
 * Schema v17 (#91, C5; R91-6): `document_role` is the attachment column's name and shape — nullable
 * `TEXT` holding a `DocumentRole` name, no CHECK and no index (nothing queries by role). A row written
 * before #91 has none, and nothing is ever read off the kind, name or URI to fill it.
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
        ForeignKey(
            entity = SupplyItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["supply_item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = InstalledComponentEntity::class,
            parentColumns = ["id"],
            childColumns = ["installed_component_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["asset_id", "uri"], unique = true),
        Index("asset_id"),
        Index(value = ["supply_item_id", "uri"], unique = true),
        Index(value = ["installed_component_id", "uri"], unique = true),
    ],
)
data class AssetReferenceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "asset_id") val assetId: String?,
    val kind: String,
    val uri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val description: String,
    val scheme: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "document_role") val documentRole: String?,
    @ColumnInfo(name = "supply_item_id") val supplyItemId: String?,
    @ColumnInfo(name = "installed_component_id") val installedComponentId: String?,
)
