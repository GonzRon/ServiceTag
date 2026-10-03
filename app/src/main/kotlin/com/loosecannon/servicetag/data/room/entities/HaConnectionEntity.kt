package com.loosecannon.servicetag.data.room.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Schema v21 (#16, C9; R16-4, R16-10, R16-Q-D): the one Home Assistant connection of this installation — **device-local,
 * never exported, merged or packed** (R16-Q-E), the `deadline_local_delivery` precedent. One row or none; the writers
 * keep it so (C17). Eight columns for the model's eight fields, in its order; the three enum columns hold the enum's
 * name. Whether the connection can be used is never a column here: it is the platform store's answer alone (C18), so a
 * platform restore that brings this row back cannot bring back a stale marker with it (R16-Q-F).
 */
@Entity(tableName = "ha_connection")
data class HaConnectionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    val cadence: String,
    @ColumnInfo(name = "network_eligibility") val networkEligibility: String,
    @ColumnInfo(name = "home_network_ssid") val homeNetworkSsid: String?,
    @ColumnInfo(name = "background_checks") val backgroundChecks: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
