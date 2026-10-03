package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.model.AssetId
import kotlinx.coroutines.flow.Flow

/**
 * #16 (C7, C11) — the device-local `season_sync_binding` table: at most one binding per asset. The schema's
 * CASCADEs take an asset's binding with the asset and a connection's bindings with the connection (C9); there is
 * no per-binding delete (R16-14).
 */
interface SeasonSyncRepository {
    suspend fun get(assetId: AssetId): SeasonSyncBinding?

    suspend fun all(): List<SeasonSyncBinding>

    fun observeFor(assetId: AssetId): Flow<SeasonSyncBinding?>

    suspend fun insert(binding: SeasonSyncBinding)

    /**
     * A compare-and-set: writes [binding] only when the stored row's `revision` is `binding.revision - 1`, and
     * answers whether it wrote. A missing row answers false. So of two overlapping writers the second sees false
     * (C11, C13).
     */
    suspend fun update(binding: SeasonSyncBinding): Boolean

    suspend fun anyEnabled(): Boolean
}

/** #16 (C7, C11) — the device-local `ha_connection` table: one row per installation, or none (R16-10). */
interface HaConnectionRepository {
    suspend fun get(): HaConnection?

    suspend fun upsert(connection: HaConnection)

    /** Deletes the row; the schema's CASCADE takes every binding on it (C9, R16-14). */
    suspend fun delete(id: String)
}

/**
 * #16 (C7, C18, R16-5) — where the access token lives, keyed by the connection id; never a Room column. [get]
 * answers null when the key is absent **or** its value cannot be decrypted (a platform restore on a new phone),
 * never a throw. [keys] serves the orphan sweep.
 */
interface SecretStore {
    suspend fun put(key: String, secret: Secret)

    suspend fun get(key: String): Secret?

    suspend fun has(key: String): Boolean

    suspend fun delete(key: String)

    suspend fun keys(): Set<String>
}

/**
 * #16 (C7, C19) — the client's port: one authenticated read of one entity at [baseUrl]. It never throws for a
 * network outcome: every failure is a [HaReadOutcome.NoDecision] with its kind.
 */
interface HaStateReader {
    suspend fun read(baseUrl: String, entityId: String, token: Secret): HaReadOutcome
}
