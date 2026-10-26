package com.loosecannon.servicetag.data.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import com.loosecannon.servicetag.data.room.entities.HaPublishDeliveryEntity

/**
 * #113/#114 (C5): the device-local delivery records, one per binding. **No flow is offered** — the projector never
 * observes delivery bookkeeping (C31, I28). Three writes, each one statement and each a compare-and-set:
 *  - [writeDesired], the projector's: on the binding's revision **and** the `desired_revision` read in the same
 *    transaction (A02); it moves the desired revision, so it clears the error and its pair and restarts the attempt
 *    count (A04, C17);
 *  - [record], the sender's: on the binding's revision **and** the desired revision as snapshotted (C17); it never
 *    writes the desired value or the request stamp, and an error is written with the pair it was recorded at;
 *  - [stampRequest], the API's Publish now (rev 5, C35b): `last_request_id` and `last_request_at` only.
 * Nothing here is exported, merged or packed.
 */
@Dao
interface HaPublishDeliveryDao {

    @Insert suspend fun insert(e: HaPublishDeliveryEntity)

    @Query("SELECT * FROM ha_publish_delivery WHERE binding_id = :bindingId")
    suspend fun get(bindingId: String): HaPublishDeliveryEntity?

    @Query("SELECT * FROM ha_publish_delivery ORDER BY binding_id")
    suspend fun all(): List<HaPublishDeliveryEntity>

    /** The projector's write (C31): answers whether it wrote. */
    suspend fun writeDesired(
        bindingId: String,
        bindingRevision: Long,
        expectedDesiredRevision: Long,
        desiredValue: String?,
        desiredAt: Long,
    ): Boolean = writeDesiredRow(bindingId, bindingRevision, expectedDesiredRevision, desiredValue, desiredAt) == 1

    @Query(
        "UPDATE ha_publish_delivery SET desired_value = :desiredValue, " +
            "desired_revision = :expectedDesiredRevision + 1, desired_at = :desiredAt, attempts_at_revision = 0, " +
            "error_kind = NULL, error_detail = NULL, error_at = NULL, error_binding_revision = NULL, " +
            "error_desired_revision = NULL " +
            "WHERE binding_id = :bindingId AND desired_revision = :expectedDesiredRevision AND EXISTS " +
            "(SELECT 1 FROM ha_publish_binding WHERE id = :bindingId AND revision = :bindingRevision)",
    )
    suspend fun writeDesiredRow(
        bindingId: String,
        bindingRevision: Long,
        expectedDesiredRevision: Long,
        desiredValue: String?,
        desiredAt: Long,
    ): Int

    /**
     * The sender's record (C17): [e]'s bookkeeping, only while the binding is still at [bindingRevision] and the
     * delivery still at `e.desiredRevision`; a moved generation or a newer desired value drops it whole. Answers
     * whether it wrote. The error's pair is that compare-and-set pair, and null exactly when the error is (A04).
     */
    suspend fun record(bindingRevision: Long, e: HaPublishDeliveryEntity): Boolean = recordRow(
        bindingId = e.bindingId, bindingRevision = bindingRevision, desiredRevision = e.desiredRevision,
        lastPublishedValue = e.lastPublishedValue, lastPublishedAt = e.lastPublishedAt,
        publishedDesiredRevision = e.publishedDesiredRevision, publishedBindingRevision = e.publishedBindingRevision,
        lastVerifiedValue = e.lastVerifiedValue, lastVerifiedAt = e.lastVerifiedAt,
        verifiedBindingRevision = e.verifiedBindingRevision, verifiedDesiredRevision = e.verifiedDesiredRevision,
        remoteValue = e.remoteValue, remoteReadAt = e.remoteReadAt, errorKind = e.errorKind,
        errorDetail = e.errorDetail, errorAt = e.errorAt, outcomeUnknownSince = e.outcomeUnknownSince,
        needsDelivery = e.needsDelivery, attemptsAtRevision = e.attemptsAtRevision, lastAttemptAt = e.lastAttemptAt,
        boundaryValue = e.boundaryValue, boundaryReadAt = e.boundaryReadAt,
        boundaryBindingRevision = e.boundaryBindingRevision, reconcilePending = e.reconcilePending,
    ) == 1

    @Query(
        "UPDATE ha_publish_delivery SET last_published_value = :lastPublishedValue, " +
            "last_published_at = :lastPublishedAt, published_desired_revision = :publishedDesiredRevision, " +
            "published_binding_revision = :publishedBindingRevision, last_verified_value = :lastVerifiedValue, " +
            "last_verified_at = :lastVerifiedAt, verified_binding_revision = :verifiedBindingRevision, " +
            "verified_desired_revision = :verifiedDesiredRevision, remote_value = :remoteValue, " +
            "remote_read_at = :remoteReadAt, error_kind = :errorKind, error_detail = :errorDetail, " +
            "error_at = :errorAt, " +
            "error_binding_revision = CASE WHEN :errorKind IS NULL THEN NULL ELSE :bindingRevision END, " +
            "error_desired_revision = CASE WHEN :errorKind IS NULL THEN NULL ELSE :desiredRevision END, " +
            "outcome_unknown_since = :outcomeUnknownSince, needs_delivery = :needsDelivery, " +
            "attempts_at_revision = :attemptsAtRevision, last_attempt_at = :lastAttemptAt, " +
            "boundary_value = :boundaryValue, boundary_read_at = :boundaryReadAt, " +
            "boundary_binding_revision = :boundaryBindingRevision, reconcile_pending = :reconcilePending " +
            "WHERE binding_id = :bindingId AND desired_revision = :desiredRevision AND EXISTS " +
            "(SELECT 1 FROM ha_publish_binding WHERE id = :bindingId AND revision = :bindingRevision)",
    )
    suspend fun recordRow(
        bindingId: String,
        bindingRevision: Long,
        desiredRevision: Long,
        lastPublishedValue: String?,
        lastPublishedAt: Long?,
        publishedDesiredRevision: Long?,
        publishedBindingRevision: Long?,
        lastVerifiedValue: String?,
        lastVerifiedAt: Long?,
        verifiedBindingRevision: Long?,
        verifiedDesiredRevision: Long?,
        remoteValue: String?,
        remoteReadAt: Long?,
        errorKind: String?,
        errorDetail: String?,
        errorAt: Long?,
        outcomeUnknownSince: Long?,
        needsDelivery: Boolean,
        attemptsAtRevision: Int,
        lastAttemptAt: Long?,
        boundaryValue: String?,
        boundaryReadAt: Long?,
        boundaryBindingRevision: Long?,
        reconcilePending: Boolean,
    ): Int

    /** The request stamp (rev 5, C35b): the two request columns over [bindingIds], and nothing else. */
    @Query(
        "UPDATE ha_publish_delivery SET last_request_id = :requestId, last_request_at = :at " +
            "WHERE binding_id IN (:bindingIds)",
    )
    suspend fun stampRequest(bindingIds: List<String>, requestId: String, at: Long): Int
}
