package com.loosecannon.servicetag.data.room.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import com.loosecannon.servicetag.data.room.entities.HaPublishBindingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The control projection of one binding (C5, C): what the projector reacts to, and nothing the sender writes — so a
 * cleared `validated_revision`, a validation time or any delivery bookkeeping is no input (C31, row 57c).
 */
data class HaPublishControlRow(
    val id: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    val kind: String,
    val enabled: Boolean,
    val revision: Long,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "boundary_entity_id") val boundaryEntityId: String?,
    @ColumnInfo(name = "activated_installation_id") val activatedInstallationId: String?,
)

/**
 * #113/#114 (C5): the device-local outbound bindings. Every write sets `claim_entity_id` from `enabled` in the same
 * statement, so the claim index holds exactly the enabled bindings (F-9). The update is a compare-and-set on
 * `revision` that always moves it by one, and so it also clears the binding's delivery error and its pair in the same
 * transaction (A04). Nothing here is exported, merged or packed, and no column could hold a credential.
 */
@Dao
interface HaPublishBindingDao {

    /** A plain insert, the claim derived: a second binding for one asset and kind, or a second claim, is refused. */
    suspend fun insert(e: HaPublishBindingEntity) = insertRow(
        e.id, e.assetId, e.connectionId, e.kind, e.entityId, e.enabled, e.revision, e.activatedInstallationId,
        e.validatedAt, e.validatedRevision, e.boundaryEntityId, e.createdAt, e.updatedAt,
    )

    @Query(
        "INSERT INTO ha_publish_binding (id, asset_id, connection_id, kind, entity_id, enabled, revision, " +
            "activated_installation_id, validated_at, validated_revision, claim_entity_id, boundary_entity_id, " +
            "created_at, updated_at) VALUES (:id, :assetId, :connectionId, :kind, :entityId, :enabled, :revision, " +
            ":activatedInstallationId, :validatedAt, :validatedRevision, " +
            "CASE WHEN :enabled THEN :entityId ELSE NULL END, :boundaryEntityId, :createdAt, :updatedAt)",
    )
    suspend fun insertRow(
        id: String,
        assetId: String,
        connectionId: String,
        kind: String,
        entityId: String,
        enabled: Boolean,
        revision: Long,
        activatedInstallationId: String?,
        validatedAt: Long?,
        validatedRevision: Long?,
        boundaryEntityId: String?,
        createdAt: Long,
        updatedAt: Long,
    )

    /**
     * The compare-and-set (C5): writes [next] whole — the claim derived — only while the stored row is at
     * `next.revision - 1`, and answers whether it wrote. A write moves the generation, so the delivery's error and
     * its pair are cleared with it, in a second statement, same transaction (A04).
     */
    @Transaction
    suspend fun update(next: HaPublishBindingEntity): Boolean {
        val wrote = updateAtRevision(
            id = next.id, expectedRevision = next.revision - 1, assetId = next.assetId,
            connectionId = next.connectionId, kind = next.kind, entityId = next.entityId, enabled = next.enabled,
            revision = next.revision, activatedInstallationId = next.activatedInstallationId,
            validatedAt = next.validatedAt, validatedRevision = next.validatedRevision,
            boundaryEntityId = next.boundaryEntityId, createdAt = next.createdAt, updatedAt = next.updatedAt,
        ) == 1
        if (wrote) clearDeliveryError(next.id)
        return wrote
    }

    @Query(
        "UPDATE ha_publish_binding SET asset_id = :assetId, connection_id = :connectionId, kind = :kind, " +
            "entity_id = :entityId, enabled = :enabled, revision = :revision, " +
            "activated_installation_id = :activatedInstallationId, validated_at = :validatedAt, " +
            "validated_revision = :validatedRevision, claim_entity_id = CASE WHEN :enabled THEN :entityId ELSE NULL END, " +
            "boundary_entity_id = :boundaryEntityId, created_at = :createdAt, updated_at = :updatedAt " +
            "WHERE id = :id AND revision = :expectedRevision",
    )
    suspend fun updateAtRevision(
        id: String,
        expectedRevision: Long,
        assetId: String,
        connectionId: String,
        kind: String,
        entityId: String,
        enabled: Boolean,
        revision: Long,
        activatedInstallationId: String?,
        validatedAt: Long?,
        validatedRevision: Long?,
        boundaryEntityId: String?,
        createdAt: Long,
        updatedAt: Long,
    ): Int

    /** A04's clearing rule for a moved generation: the error and both its revisions, one statement. */
    @Query(
        "UPDATE ha_publish_delivery SET error_kind = NULL, error_detail = NULL, error_at = NULL, " +
            "error_binding_revision = NULL, error_desired_revision = NULL WHERE binding_id = :bindingId",
    )
    suspend fun clearDeliveryError(bindingId: String): Int

    /**
     * Bookkeeping (C17: a validator failed): `validated_revision` cleared at [atRevision], which does not move — so it
     * is no control change and [observeControls] emits nothing for it. Answers the rows written, 1 or 0.
     */
    @Query(
        "UPDATE ha_publish_binding SET validated_revision = NULL, " +
            "claim_entity_id = CASE WHEN enabled THEN entity_id ELSE NULL END " +
            "WHERE id = :id AND revision = :atRevision",
    )
    suspend fun clearValidation(id: String, atRevision: Long): Int

    @Query("SELECT * FROM ha_publish_binding WHERE id = :id")
    suspend fun get(id: String): HaPublishBindingEntity?

    @Query("SELECT * FROM ha_publish_binding WHERE asset_id = :assetId ORDER BY kind")
    suspend fun forAsset(assetId: String): List<HaPublishBindingEntity>

    /** Every binding naming the helper, enabled or not. */
    @Query("SELECT * FROM ha_publish_binding WHERE connection_id = :connectionId AND entity_id = :entityId ORDER BY id")
    suspend fun byTarget(connectionId: String, entityId: String): List<HaPublishBindingEntity>

    @Query("SELECT * FROM ha_publish_binding ORDER BY id")
    suspend fun all(): List<HaPublishBindingEntity>

    @Query("SELECT * FROM ha_publish_binding ORDER BY id")
    fun observeAll(): Flow<List<HaPublishBindingEntity>>

    /** The projector's only binding input (C31): the control fields, distinct until changed. */
    fun observeControls(): Flow<List<HaPublishControlRow>> = observeControlRows().distinctUntilChanged()

    @Query(
        "SELECT id, asset_id, kind, enabled, revision, entity_id, boundary_entity_id, activated_installation_id " +
            "FROM ha_publish_binding ORDER BY id",
    )
    fun observeControlRows(): Flow<List<HaPublishControlRow>>

    /** The schema's CASCADE takes its delivery. */
    @Query("DELETE FROM ha_publish_binding WHERE id = :id")
    suspend fun delete(id: String): Int
}
