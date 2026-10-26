package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.hapublish.DecommissionTombstone
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishDelivery
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.PublishState
import com.loosecannon.servicetag.core.hapublish.errorIsCurrent
import kotlinx.serialization.Serializable

/**
 * #113/#114 (C35, G-R1) — `GET /v1/assets/{id}/ha-publishing`: the asset's outbound bindings, at most one per kind,
 * and the decommission obligations it left ([pendingDecommissions], PENDING or UNDELIVERABLE). Times are epoch millis.
 * **Never** the base URL, the Wi-Fi name, the token or an installation id (`COPIED_INSTALLATION` stays a `state`).
 */
@Serializable
internal data class HaPublishingResponse(
    val assetId: String,
    val bindings: List<HaPublishBindingDto>,
    val pendingDecommissions: List<HaDecommissionDto>,
)

/**
 * One binding (C35) with rev 4.1's delivery facts (G-R1) and rev 5's `request` (C35b): [state] a `PublishState`
 * name, derived now; [boundaryEntityId] the date kind's season-ended helper (else null); [revision] the binding's
 * generation; [validated] whether the last validation stands at it; [remote] the last value read; [boundary] the
 * date kind's cached season-ended read, null when none is cached or the kind names no helper.
 */
@Serializable
internal data class HaPublishBindingDto(
    val kind: String,
    val entityId: String,
    val boundaryEntityId: String?,
    val enabled: Boolean,
    val state: String,
    val desiredValue: String?,
    val lastPublished: HaPublishLineDto?,
    val lastVerified: HaPublishLineDto?,
    val lastError: HaPublishErrorDto?,
    val revision: Long,
    val validated: Boolean,
    val validatedAt: Long?,
    val remote: HaRemoteReadDto?,
    val needsDelivery: Boolean,
    val reconcilePending: Boolean,
    val outcomeUnknownSince: Long?,
    val lastAttemptAt: Long?,
    val boundary: HaBoundaryReadDto?,
    val request: HaPublishRequestDto?,
)

/** A published or verified line: the value, its time, and whether its `(binding, desired)` pair is the current one. */
@Serializable
internal data class HaPublishLineDto(val value: String, val at: Long, val current: Boolean)

/** The stored error: its `PublishErrorKind` name, detail (a status at most), time, and whether it stands (A04). */
@Serializable
internal data class HaPublishErrorDto(val kind: String, val detail: String?, val at: Long, val current: Boolean)

/** The last value read from the helper and when, as bounded when stored. */
@Serializable
internal data class HaRemoteReadDto(val value: String?, val readAt: Long)

/** The cached season-ended read (C9a): its date, read time, and whether it was read at the current revision. */
@Serializable
internal data class HaBoundaryReadDto(val value: String, val readAt: Long, val atCurrentRevision: Boolean)

/** The last API Publish now that covered this binding (C35b): its id, time, and [progress] a `RequestProgress` name. */
@Serializable
internal data class HaPublishRequestDto(val id: String, val at: Long, val progress: String)

/** One obligation the asset left (C34): its id, helper, `DecommissionState` name, request time and last error. */
@Serializable
internal data class HaDecommissionDto(
    val id: String,
    val entityId: String,
    val state: String,
    val requestedAt: Long,
    val lastError: HaDecommissionErrorDto?,
)

/** A tombstone's last error: its `PublishErrorKind` name and time (the row keeps no detail). */
@Serializable
internal data class HaDecommissionErrorDto(val kind: String, val at: Long)

/**
 * #113/#114 (C35b) — how far an API Publish now has come, read from the delivery's existing pairs and its request
 * stamp, never stored. A closed set, drawn by exhaustive `when`s.
 */
internal enum class RequestProgress {
    /** Accepted; no read, write or error at the current pair since it. */
    ACCEPTED,

    /** Our POST at the current pair was read back at or after the request. */
    PUBLISHED,

    /** A read at the current pair matched at or after the request, with no POST of ours. */
    VERIFIED,

    /** An error at the current pair was recorded at or after the request. */
    FAILED,
}

/**
 * #113/#114 (C35b) — the request's progress, pure: `PUBLISHED` ⇔ the published line is current and no older than the
 * request; else `VERIFIED` ⇔ the verified line is; else `FAILED` ⇔ the error is current and no older; else `ACCEPTED`.
 * Null when no API Publish now ever covered the binding.
 */
internal fun requestProgressOf(binding: PublishBinding, delivery: PublishDelivery): RequestProgress? {
    val at = delivery.lastRequestAt ?: return null
    if (delivery.lastRequestId == null) return null
    val published = delivery.publishedLine(binding)
    val verified = delivery.verifiedLine(binding)
    val error = delivery.errorLine(binding)
    if (published != null && published.current && published.at >= at) return RequestProgress.PUBLISHED
    if (verified != null && verified.current && verified.at >= at) return RequestProgress.VERIFIED
    if (error != null && error.current && error.at >= at) return RequestProgress.FAILED
    return RequestProgress.ACCEPTED
}

/** C35 — one binding as the route answers it, with [state] derived by the caller from the same snapshot. */
internal fun PublishBinding.toDto(delivery: PublishDelivery, state: PublishState): HaPublishBindingDto =
    HaPublishBindingDto(
        kind = kind.name,
        entityId = entityId,
        boundaryEntityId = when (kind) {
            PublishKind.EQUIPMENT_STATUS -> null
            PublishKind.SEASON_SERVICE_DATE -> boundaryEntityId
        },
        enabled = enabled,
        state = state.name,
        desiredValue = delivery.desiredValue,
        lastPublished = delivery.publishedLine(this),
        lastVerified = delivery.verifiedLine(this),
        lastError = delivery.errorLine(this),
        revision = revision,
        validated = validatedRevision == revision,
        validatedAt = validatedAt,
        remote = delivery.remoteReadAt?.let { HaRemoteReadDto(delivery.remoteValue, it) },
        needsDelivery = delivery.needsDelivery,
        reconcilePending = delivery.reconcilePending,
        outcomeUnknownSince = delivery.outcomeUnknownSince,
        lastAttemptAt = delivery.lastAttemptAt,
        boundary = boundaryReadOf(delivery),
        request = requestOf(delivery),
    )

/** C34 — one obligation as the route answers it. */
internal fun DecommissionTombstone.toDto(): HaDecommissionDto = HaDecommissionDto(
    id = id,
    entityId = entityId,
    state = state.name,
    requestedAt = requestedAt,
    lastError = errorKind?.let { kind -> errorAt?.let { HaDecommissionErrorDto(kind.name, it) } },
)

private fun PublishDelivery.publishedLine(binding: PublishBinding): HaPublishLineDto? {
    val value = lastPublishedValue ?: return null
    val at = lastPublishedAt ?: return null
    val current = publishedBindingRevision == binding.revision && publishedDesiredRevision == desiredRevision
    return HaPublishLineDto(value, at, current)
}

private fun PublishDelivery.verifiedLine(binding: PublishBinding): HaPublishLineDto? {
    val value = lastVerifiedValue ?: return null
    val at = lastVerifiedAt ?: return null
    val current = verifiedBindingRevision == binding.revision && verifiedDesiredRevision == desiredRevision
    return HaPublishLineDto(value, at, current)
}

private fun PublishDelivery.errorLine(binding: PublishBinding): HaPublishErrorDto? {
    val kind = errorKind ?: return null
    val at = errorAt ?: return null
    return HaPublishErrorDto(kind.name, errorDetail, at, errorIsCurrent(binding))
}

/** The date kind's cached season-ended read, only while the binding names a season-ended helper (C9a). */
private fun PublishBinding.boundaryReadOf(delivery: PublishDelivery): HaBoundaryReadDto? {
    val named = when (kind) {
        PublishKind.EQUIPMENT_STATUS -> false
        PublishKind.SEASON_SERVICE_DATE -> boundaryEntityId != null
    }
    if (!named) return null
    val value = delivery.boundaryValue ?: return null
    val readAt = delivery.boundaryReadAt ?: return null
    return HaBoundaryReadDto(value, readAt, delivery.boundaryBindingRevision == revision)
}

private fun PublishBinding.requestOf(delivery: PublishDelivery): HaPublishRequestDto? {
    val id = delivery.lastRequestId ?: return null
    val at = delivery.lastRequestAt ?: return null
    val progress = checkNotNull(requestProgressOf(this, delivery))
    return HaPublishRequestDto(id, at, progress.name)
}
