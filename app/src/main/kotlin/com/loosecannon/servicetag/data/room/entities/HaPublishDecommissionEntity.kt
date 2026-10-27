package com.loosecannon.servicetag.data.room.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Schema v22 (#113/#114, C4.4): a decommission tombstone — the obligation to deliver `1970-01-01` to a date helper
 * ServiceTag has stopped writing (C34) — **device-local, never exported, merged or packed** (C6, G5). **No foreign
 * key:** it outlives a Forget of its connection and the delete of its asset (`asset_id` is for display only). One
 * obligation per helper, `UNIQUE(connection_id, entity_id)`: a second is merged into the first (C5). `state` is
 * `PENDING` or `UNDELIVERABLE`; `revision` moves only when an obligation is written or merged, never by the drain's
 * state and error writes, which compare-and-set on it. `installation_id` is the binding's activation (F-25).
 */
@Entity(
    tableName = "ha_publish_decommission",
    indices = [Index(value = ["connection_id", "entity_id"], unique = true)],
)
data class HaPublishDecommissionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "connection_id") val connectionId: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "asset_id") val assetId: String?,
    @ColumnInfo(name = "installation_id") val installationId: String,
    val state: String,
    @ColumnInfo(name = "requested_at") val requestedAt: Long,
    @ColumnInfo(name = "error_kind") val errorKind: String?,
    @ColumnInfo(name = "error_at") val errorAt: Long?,
    val revision: Long,
)
