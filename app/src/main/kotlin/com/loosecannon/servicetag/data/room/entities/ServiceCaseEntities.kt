package com.loosecannon.servicetag.data.room.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

// Schema v12's two tables (#79, C17; R79-1): the service case aggregate. The v6 rules hold: TEXT enum
// names, ISO dates as TEXT, instants as epoch-millisecond INTEGER, no `CHECK` anywhere (the value rules
// are the use cases', with tests).
//
// A case's `incident_event_id` and `resolution_event_id` are **soft links** with no foreign key: a
// deleted event leaves a readable dangling id (R79-4), exactly as a condition's `event_id` does. The
// entries carry no `updated_at`, because nothing ever moves one — the timeline is append-only (R79-8) —
// and their DAO inserts and queries only. A case leaves only by its asset's CASCADE, and an entry by its
// case's.

/** One service case's header: mutable, as an asset is, but its status moves only by a status entry. */
@Entity(
    tableName = "service_case",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("asset_id")],
)
data class ServiceCaseEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    val title: String,
    val type: String,
    @ColumnInfo(name = "opened_on") val openedOn: String,
    @ColumnInfo(name = "closed_on") val closedOn: String?,
    val provider: String,
    val contact: String,
    @ColumnInfo(name = "case_ref") val caseRef: String,
    val coverage: String,
    val status: String,
    @ColumnInfo(name = "outbound_tracking") val outboundTracking: String,
    @ColumnInfo(name = "outbound_carrier") val outboundCarrier: String,
    @ColumnInfo(name = "return_tracking") val returnTracking: String,
    @ColumnInfo(name = "return_carrier") val returnCarrier: String,
    @ColumnInfo(name = "cost_minor") val costMinor: Long?,
    val currency: String?,
    val notes: String,
    @ColumnInfo(name = "incident_event_id") val incidentEventId: String?,
    @ColumnInfo(name = "resolution_event_id") val resolutionEventId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** One immutable fact on a case's timeline; `status` is set when the entry moved the case's status. */
@Entity(
    tableName = "service_case_entry",
    foreignKeys = [
        ForeignKey(
            entity = ServiceCaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["case_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("case_id")],
)
data class ServiceCaseEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "case_id") val caseId: String,
    @ColumnInfo(name = "occurred_on") val occurredOn: String,
    @ColumnInfo(name = "occurred_time") val occurredTime: String?,
    @ColumnInfo(name = "tz_id") val tzId: String,
    val note: String,
    val status: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
