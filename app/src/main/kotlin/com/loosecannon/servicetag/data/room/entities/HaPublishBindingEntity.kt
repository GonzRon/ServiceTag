package com.loosecannon.servicetag.data.room.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Schema v22 (#113/#114, C4.2): one asset's outbound publication of one kind to one Home Assistant helper —
 * **device-local, never exported, merged or packed** (C6, G5). At most one per asset and kind (`UNIQUE(asset_id,
 * kind)`). `asset_id` is a **soft link with no foreign key**: deleting the asset, or a replace restore's wipe, leaves
 * the row for the projector to decommission (C34, R-N2). The connection's key CASCADEs (Forget writes its tombstones
 * first, C28), and `connection_id` is indexed for it.
 *
 * `claim_entity_id` is the claim: equal to `entity_id` while `enabled`, null while disabled, and written from `enabled`
 * by every DAO write in the same statement — so `UNIQUE(connection_id, claim_entity_id)`, whose nulls SQLite treats as
 * distinct, is "one **enabled** binding per helper" while disabled bindings may share a target (G1, F-9).
 * `boundary_entity_id` is the date kind's HA `season_ended` helper, read and never written (C9a); null for the status
 * kind. `revision` is the generation (C14); the enum columns hold the enum's name and `enabled` is 0 or 1.
 */
@Entity(
    tableName = "ha_publish_binding",
    foreignKeys = [
        ForeignKey(
            entity = HaConnectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["connection_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["asset_id", "kind"], unique = true),
        Index(value = ["connection_id", "claim_entity_id"], unique = true),
        Index("connection_id"),
    ],
)
data class HaPublishBindingEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "connection_id") val connectionId: String,
    val kind: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val enabled: Boolean,
    val revision: Long,
    @ColumnInfo(name = "activated_installation_id") val activatedInstallationId: String?,
    @ColumnInfo(name = "validated_at") val validatedAt: Long?,
    @ColumnInfo(name = "validated_revision") val validatedRevision: Long?,
    /** Derived from [enabled]; the DAO's writes ignore what is passed here and write the derivation. */
    @ColumnInfo(name = "claim_entity_id") val claimEntityId: String? = if (enabled) entityId else null,
    @ColumnInfo(name = "boundary_entity_id") val boundaryEntityId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
