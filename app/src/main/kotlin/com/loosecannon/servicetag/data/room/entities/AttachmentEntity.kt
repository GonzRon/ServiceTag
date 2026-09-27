package com.loosecannon.servicetag.data.room.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Schema v5 (spec §9.1). One row per attached file; the bytes are never here — they live in the
 * owner's SAF folder, and `storage_locator` is how to find them, relative to that folder's root.
 *
 * Two nullable foreign keys, exactly one of which is set: `asset_id` for a file on an asset,
 * `event_id` for one on an entry, both CASCADE so deleting the owner takes the row with it (the
 * *bytes* are `DeleteAsset`/`DeleteEvent`'s job, which read the locators before the cascade runs).
 * There is deliberately **no SQL `CHECK`** for the exactly-one rule (spec §11.5): Room does not
 * model one in its schema hash, so it would be invisible to migration validation. The rule is
 * enforced in [com.loosecannon.servicetag.data.room.requireExactlyOneOwner] on the way into the
 * table and in the backup reader on the way in from a file, the same shape as `nfc_tag`'s
 * at-most-one-target rule.
 *
 * `(storage_provider, storage_locator)` is unique: two rows must never claim the same bytes.
 *
 * Schema v10 (#67, C3) appends `document_role`: nullable, no default, never backfilled — a row
 * written before it has no role. Asset-owned rows only, which the use cases and the backup reader
 * enforce; there is no `CHECK` here, for the reason above.
 */
@Entity(
    tableName = "attachment",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AssetEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("asset_id"),
        Index("event_id"),
        Index(value = ["storage_provider", "storage_locator"], unique = true),
    ],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "asset_id") val assetId: String?,
    @ColumnInfo(name = "event_id") val eventId: String?,
    val kind: String,
    val mode: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    val sha256: String,
    @ColumnInfo(name = "storage_provider") val storageProvider: String,
    @ColumnInfo(name = "storage_locator") val storageLocator: String,
    @ColumnInfo(name = "captured_on") val capturedOn: String?,
    val notes: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "document_role") val documentRole: String?,
)
