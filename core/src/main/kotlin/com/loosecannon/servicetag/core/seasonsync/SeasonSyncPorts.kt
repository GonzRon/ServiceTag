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

    /**
     * #113/#114 (C24, rev 6) — every binding, emitted again on each change: a link, an entity or mode change, a stop,
     * a resume or a CASCADE. The publishing projector and #116 read it; no #16 runner, worker or command does.
     */
    fun observeAll(): Flow<List<SeasonSyncBinding>>

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

    /**
     * #113/#114 (C24, rev 6) — the one connection, emitted again on each save and null once it is forgotten. The
     * publishing projector and #116 read it; no #16 runner, worker or command does.
     */
    fun observe(): Flow<HaConnection?>
}

/**
 * #113/#114 (C26, C28) — what the four #16 commands ask of the outbound side, inside their own write. Each command
 * takes it as a trailing parameter defaulted to [NONE], so every shipped construction compiles unchanged (F-6);
 * production always passes the publishing side's own.
 */
interface OutboundTargets {
    /**
     * C26 — whether this phone writes [entityId] on [connectionId]: a publish binding names it as its target, enabled
     * or not, or a PENDING tombstone does. Linking or editing an inbound entity refuses such an id.
     */
    suspend fun writes(connectionId: String, entityId: String): Boolean

    /**
     * C28, O-12 — whether an address change of [connectionId] must wait: a date binding is enabled, or a tombstone of
     * this connection is PENDING, so the old Home Assistant still has to hear `1970-01-01`.
     */
    suspend fun blocksAddressChange(connectionId: String): Boolean

    /**
     * C28, A04, F-2 — a re-authorised save: every publish binding of [connectionId] moves its revision by one, its
     * delivery error cleared with the pair, a standing validation kept, so a send built before is dropped and a
     * pending invalidation is still delivered.
     */
    suspend fun reauthorize(connectionId: String)

    /**
     * C28, O-11 — Forget, in its write and before the delete: each enabled date binding of [connectionId] that still
     * owes `1970-01-01`, and each PENDING tombstone of it, becomes an UNDELIVERABLE tombstone, which no key ties to
     * the connection.
     */
    suspend fun forget(connectionId: String)

    companion object {
        /** No outbound side: writes nothing, refuses nothing. Only shipped constructions and tests reach it. */
        val NONE: OutboundTargets = object : OutboundTargets {
            override suspend fun writes(connectionId: String, entityId: String): Boolean = false

            override suspend fun blocksAddressChange(connectionId: String): Boolean = false

            override suspend fun reauthorize(connectionId: String) = Unit

            override suspend fun forget(connectionId: String) = Unit
        }
    }
}

/**
 * #16 (C7, C18, R16-5) — where the access token lives, keyed by the connection id; never a Room column. [get]
 * answers null when the key is absent **or** its value cannot be decrypted (a platform restore on a new phone),
 * never a throw. [keys] serves the orphan sweep.
 */
interface SecretStore {
    suspend fun put(key: String, secret: Secret)

    suspend fun get(key: String): Secret?

    /**
     * Whether a value is stored for [key], its file and its key both present, without decrypting it (C18): a stored
     * value that cannot be decrypted answers `has` true while [get] answers null.
     */
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
