package com.loosecannon.servicetag.data.room.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey

/**
 * Schema v22 (#113/#114, C4.3): one binding's latest-desired-state record and its delivery bookkeeping —
 * **device-local, never exported, merged or packed** (C6, G5). Keyed by its binding, whose delete takes it (CASCADE).
 *
 * `desired_value` (null = nothing to send) and `desired_revision` are the projector's; the published, verified and
 * error **pairs** are each a `(binding revision, desired revision)` (F-1, A04), so an error is current only at its
 * pair and every write that moves either revision clears it (C5). `needs_delivery` (A05) and `reconcile_pending` (C)
 * are the two outstanding-work flags; `boundary_*` the last valid read of the date kind's boundary helper (A07, C9a);
 * `last_request_*` the last API Publish now that covered the binding, written only by the request stamp (rev 5, C35b).
 */
@Entity(
    tableName = "ha_publish_delivery",
    foreignKeys = [
        ForeignKey(
            entity = HaPublishBindingEntity::class,
            parentColumns = ["id"],
            childColumns = ["binding_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HaPublishDeliveryEntity(
    @PrimaryKey @ColumnInfo(name = "binding_id") val bindingId: String,
    @ColumnInfo(name = "desired_value") val desiredValue: String?,
    @ColumnInfo(name = "desired_revision") val desiredRevision: Long,
    @ColumnInfo(name = "desired_at") val desiredAt: Long?,
    @ColumnInfo(name = "last_published_value") val lastPublishedValue: String? = null,
    @ColumnInfo(name = "last_published_at") val lastPublishedAt: Long? = null,
    @ColumnInfo(name = "published_desired_revision") val publishedDesiredRevision: Long? = null,
    @ColumnInfo(name = "published_binding_revision") val publishedBindingRevision: Long? = null,
    @ColumnInfo(name = "last_verified_value") val lastVerifiedValue: String? = null,
    @ColumnInfo(name = "last_verified_at") val lastVerifiedAt: Long? = null,
    @ColumnInfo(name = "verified_binding_revision") val verifiedBindingRevision: Long? = null,
    @ColumnInfo(name = "verified_desired_revision") val verifiedDesiredRevision: Long? = null,
    @ColumnInfo(name = "remote_value") val remoteValue: String? = null,
    @ColumnInfo(name = "remote_read_at") val remoteReadAt: Long? = null,
    @ColumnInfo(name = "error_kind") val errorKind: String? = null,
    @ColumnInfo(name = "error_detail") val errorDetail: String? = null,
    @ColumnInfo(name = "error_at") val errorAt: Long? = null,
    @ColumnInfo(name = "error_binding_revision") val errorBindingRevision: Long? = null,
    @ColumnInfo(name = "error_desired_revision") val errorDesiredRevision: Long? = null,
    @ColumnInfo(name = "outcome_unknown_since") val outcomeUnknownSince: Long? = null,
    @ColumnInfo(name = "needs_delivery", defaultValue = "0") val needsDelivery: Boolean = false,
    @ColumnInfo(name = "attempts_at_revision", defaultValue = "0") val attemptsAtRevision: Int = 0,
    @ColumnInfo(name = "last_attempt_at") val lastAttemptAt: Long? = null,
    @ColumnInfo(name = "boundary_value") val boundaryValue: String? = null,
    @ColumnInfo(name = "boundary_read_at") val boundaryReadAt: Long? = null,
    @ColumnInfo(name = "boundary_binding_revision") val boundaryBindingRevision: Long? = null,
    @ColumnInfo(name = "reconcile_pending", defaultValue = "0") val reconcilePending: Boolean = false,
    @ColumnInfo(name = "last_request_id") val lastRequestId: String? = null,
    @ColumnInfo(name = "last_request_at") val lastRequestAt: Long? = null,
)
