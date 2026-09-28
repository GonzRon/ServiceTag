package com.loosecannon.servicetag.data.room.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

// Schema v13's one table (#72, C5; R72-1): the loan aggregate. The v6 rules hold: TEXT enum names, ISO
// dates as TEXT, instants as epoch-millisecond INTEGER, no `CHECK` anywhere (the value rules are the use
// cases', with tests).
//
// **One open loan per asset is a constraint** (C2 ii; R72-2), on the `asset_event` completion index's
// precedent (`Journal.kt:66-70`): `open_marker` is 1 while the loan is open and NULL once it is returned,
// and `UNIQUE(asset_id, open_marker)` refuses a second 1 for an asset while SQLite's distinct NULLs let the
// returned rows pile up as history. The marker is set by the mapper from `returned_on` alone and is a
// storage detail: it is on no domain type, no backup row and no API body. The use cases refuse a second
// open loan in the same write transaction first, so the index is the database's last word, never a
// refusal anyone meets. A loan leaves only by its asset's CASCADE.

/** One loan of an asset; open while `returned_on` is null. */
@Entity(
    tableName = "asset_loan",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // Redundant beside the unique index's leading column, and kept on the house style: every
        // foreign key column here carries its own index.
        Index("asset_id"),
        Index(value = ["asset_id", "open_marker"], unique = true),
    ],
)
data class AssetLoanEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "borrower_name") val borrowerName: String,
    @ColumnInfo(name = "contact_lookup_uri") val contactLookupUri: String?,
    @ColumnInfo(name = "lent_on") val lentOn: String,
    @ColumnInfo(name = "due_on") val dueOn: String?,
    @ColumnInfo(name = "returned_on") val returnedOn: String?,
    @ColumnInfo(name = "reminder_mode") val reminderMode: String,
    val notes: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** 1 while open, NULL once returned — see the file comment. Never read back into the domain. */
    @ColumnInfo(name = "open_marker") val openMarker: Int?,
)
