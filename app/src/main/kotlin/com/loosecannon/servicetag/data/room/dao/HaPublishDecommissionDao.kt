package com.loosecannon.servicetag.data.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import com.loosecannon.servicetag.data.room.entities.HaPublishDecommissionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** One PENDING obligation as the projector watches it (C5, C): its id and the revision it was written or merged at. */
data class HaPublishObligationRow(val id: String, val revision: Long)

/**
 * #113/#114 (C5, C34): the device-local decommission tombstones. An obligation is written by [insertOrMerge], one per
 * `(connection_id, entity_id)`, and that is the only write that moves a tombstone's `revision`; the drain's state and
 * error writes ([updateAtRevision]) compare-and-set on it and never move it; a delete is by id, never by the pair.
 * Nothing here is exported, merged or packed.
 */
@Dao
interface HaPublishDecommissionDao {

    /**
     * Writes the obligation [e], or merges it into the one already held for its helper: that row keeps its id and
     * `requested_at`, takes [e]'s state, asset and installation, drops its old error and moves its revision by one.
     * Answers the id of the row that holds the obligation.
     */
    @Transaction
    suspend fun insertOrMerge(e: HaPublishDecommissionEntity): String {
        val held = byTarget(e.connectionId, e.entityId) ?: return e.id.also { insert(e) }
        merge(held.id, e.state, e.assetId, e.installationId)
        return held.id
    }

    @Insert suspend fun insert(e: HaPublishDecommissionEntity)

    @Query(
        "UPDATE ha_publish_decommission SET state = :state, asset_id = :assetId, installation_id = :installationId, " +
            "error_kind = NULL, error_at = NULL, revision = revision + 1 WHERE id = :id",
    )
    suspend fun merge(id: String, state: String, assetId: String?, installationId: String): Int

    /** The drain's write: state and error only, while the row is still at [revision], which it never moves. */
    @Query(
        "UPDATE ha_publish_decommission SET state = :state, error_kind = :errorKind, error_at = :errorAt " +
            "WHERE id = :id AND revision = :revision",
    )
    suspend fun updateAtRevision(id: String, revision: Long, state: String, errorKind: String?, errorAt: Long?): Int

    @Query("DELETE FROM ha_publish_decommission WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT * FROM ha_publish_decommission WHERE id = :id")
    suspend fun get(id: String): HaPublishDecommissionEntity?

    @Query("SELECT * FROM ha_publish_decommission WHERE connection_id = :connectionId AND entity_id = :entityId")
    suspend fun byTarget(connectionId: String, entityId: String): HaPublishDecommissionEntity?

    @Query("SELECT * FROM ha_publish_decommission ORDER BY id")
    suspend fun all(): List<HaPublishDecommissionEntity>

    @Query("SELECT * FROM ha_publish_decommission ORDER BY id")
    fun observeAll(): Flow<List<HaPublishDecommissionEntity>>

    /** The PENDING obligations' `(id, revision)`, distinct until changed (C31: new obligations are work arrived). */
    fun observeObligations(): Flow<List<HaPublishObligationRow>> = observeObligationRows().distinctUntilChanged()

    @Query("SELECT id, revision FROM ha_publish_decommission WHERE state = 'PENDING' ORDER BY id")
    fun observeObligationRows(): Flow<List<HaPublishObligationRow>>
}
