package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.model.AssetId
import kotlinx.coroutines.flow.Flow

/**
 * #113/#114 (C24) — the device-local `ha_publish_binding` table: at most one binding per asset and kind, and at most
 * one **enabled** binding per `(connection, entity)` (the store derives the claim from `enabled`, F-9). Never in a
 * backup, an export, a merge or a pack (G5).
 */
interface PublishBindingRepository {
    suspend fun get(id: String): PublishBinding?

    /** The asset's bindings, at most one per kind. */
    suspend fun forAsset(assetId: AssetId): List<PublishBinding>

    /** Every binding naming [entityId] on [connectionId] as its target, enabled or not. */
    suspend fun byTarget(connectionId: String, entityId: String): List<PublishBinding>

    suspend fun all(): List<PublishBinding>

    fun observeAll(): Flow<List<PublishBinding>>

    /** A second binding for one asset and kind, or a second enabled claim of one helper, is refused by the store. */
    suspend fun insert(binding: PublishBinding)

    /**
     * The compare-and-set (C5): writes [binding] only over the stored row's `binding.revision - 1`, and answers
     * whether it wrote. A write moves the generation, so the delivery's error and its pair are cleared with it (A04).
     */
    suspend fun update(binding: PublishBinding): Boolean

    /**
     * C17 — a validator failed: the binding's `validatedRevision` cleared while it is still at [atRevision], which does
     * not move. Answers whether it wrote.
     */
    suspend fun clearValidation(id: String, atRevision: Long): Boolean

    /** Deletes the binding by its id; its delivery goes with it. */
    suspend fun delete(id: String)
}

/**
 * #113/#114 (C24) — the device-local `ha_publish_delivery` table, one record per binding. Four writes: [upsert] (a
 * command, inside its own write: a new record, or Enable's and Check helper's resets), [writeDesired] (the projector),
 * [record] (the sender's, and Check helper's failure path, which records its validator's kind inside its own write)
 * and [stampRequest] (the API's Publish now).
 */
interface PublishDeliveryRepository {
    suspend fun get(bindingId: String): PublishDelivery?

    /** Writes [delivery] whole, every column; only a command's write, which read the row in the same transaction. */
    suspend fun upsert(delivery: PublishDelivery)

    /**
     * The projector's write (C31): [desiredValue] at `expectedDesiredRevision + 1`, only while the binding is still at
     * [bindingRevision] and the record at [expectedDesiredRevision]. A new desired revision clears the error and its
     * pair (A04) and starts the attempt count again (`attemptsAtRevision = 0`). Answers whether it wrote.
     */
    suspend fun writeDesired(
        bindingId: String,
        bindingRevision: Long,
        expectedDesiredRevision: Long,
        desiredValue: String?,
        desiredAt: Long,
    ): Boolean

    /**
     * The sender's record (C17), the delivery half of [DeliverySteps.commit]: [delivery]'s bookkeeping only while the
     * binding is still at [bindingRevision] and the record still at `delivery.desiredRevision`; a moved generation or a
     * newer desired value drops it whole. It never writes the desired value, the boundary cache or the request stamp,
     * and an error is written with the compare-and-set pair (A04). Answers whether it wrote. A [DeliveryRecord] whose
     * `clearValidation` is set needs [PublishBindingRepository.clearValidation] as well: the caller makes both calls
     * in one `uow.write`.
     */
    suspend fun record(bindingRevision: Long, delivery: PublishDelivery): Boolean

    /** C35b — the API's Publish now: the two request columns over [bindingIds], and nothing else. */
    suspend fun stampRequest(bindingIds: List<String>, requestId: String, at: Long)
}

/**
 * #114 (C24, C34) — the device-local `ha_publish_decommission` table: one obligation per `(connection, entity)`, no key
 * to an asset or a connection, so it outlives both.
 */
interface DecommissionRepository {
    suspend fun get(id: String): DecommissionTombstone?

    suspend fun byTarget(connectionId: String, entityId: String): DecommissionTombstone?

    suspend fun all(): List<DecommissionTombstone>

    fun observeAll(): Flow<List<DecommissionTombstone>>

    /**
     * Writes the obligation [tombstone], or merges it into the one held for its helper: that row keeps its id and
     * `requestedAt`, takes [tombstone]'s state, asset and installation, drops its error and moves its revision by one.
     * Answers the id of the row that holds the obligation.
     */
    suspend fun upsertMerging(tombstone: DecommissionTombstone): String

    /**
     * The drain's compare-and-set: [tombstone]'s state and error only, while the stored row is still at
     * `tombstone.revision`, which this never moves. Answers whether it wrote.
     */
    suspend fun update(tombstone: DecommissionTombstone): Boolean

    /** Deletes one obligation by its id, never by the pair. */
    suspend fun delete(id: String)
}
