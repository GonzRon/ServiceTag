package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind

/**
 * #114 (C1) — the service date the date kind publishes whenever its asset has no qualifying service: Home Assistant's
 * own comparison then reads it as "no service". Declared here once; every other site names this constant.
 */
const val INVALID_SERVICE_DATE = "1970-01-01"

/** #113/#114 (C1) — what one outbound binding publishes. A closed set, drawn by exhaustive `when`s. */
enum class PublishKind { EQUIPMENT_STATUS, SEASON_SERVICE_DATE }

/**
 * #113/#114 (C14) — one asset's outbound binding to one Home Assistant helper: the columns of `ha_publish_binding`
 * (C4.2) under their Kotlin names, but for `claim_entity_id`, which the store derives from [enabled].
 *
 * [revision] is the **generation**: it moves by one on enable, disable, target change, re-validation and the
 * connection's re-authorisation (C28), so a reply read at an older generation records nothing (C17).
 * [activatedInstallationId] names the installation that may write (C27); [validatedRevision] equals [revision]
 * while the helper's last validation stands. [boundaryEntityId] is the date kind's season-ended helper, read and
 * never written (C9a); null for the status kind. No field holds a token.
 */
data class PublishBinding(
    val id: String,
    val assetId: AssetId,
    val connectionId: String,
    val kind: PublishKind,
    val entityId: String,
    val enabled: Boolean,
    val revision: Long,
    val activatedInstallationId: String?,
    val validatedAt: Long?,
    val validatedRevision: Long?,
    val boundaryEntityId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * #113/#114 (C14) — the delivery record of one binding: the columns of `ha_publish_delivery` (C4.3).
 *
 * Three facts are kept apart: [lastPublishedAt] moves only when **our** POST at the current
 * `(revision, desiredRevision)` was accepted and its read-back matched; [lastVerifiedAt] moves whenever a read equals
 * the desired value, with or without our POST; [remoteValue] is whatever the last read saw. Each of the first two
 * records the pair it holds for.
 *
 * An error is **current** only while `(errorBindingRevision, errorDesiredRevision)` equals the binding's
 * `(revision, desiredRevision)` (A04); every write that moves the pair clears it. [needsDelivery] is an outstanding
 * delivery or repair (A05), [reconcilePending] a reconcile begun and not yet completed by a read (C).
 * [attemptsAtRevision] counts POSTs begun at this desired revision: the write-ahead moves it, and a new desired
 * revision resets it.
 * [lastRequestId] and [lastRequestAt] are written only by the API's request stamp (C35b), never by a delivery record.
 */
data class PublishDelivery(
    val bindingId: String,
    val desiredValue: String?,
    val desiredRevision: Long,
    val desiredAt: Long?,
    val lastPublishedValue: String? = null,
    val lastPublishedAt: Long? = null,
    val publishedBindingRevision: Long? = null,
    val publishedDesiredRevision: Long? = null,
    val lastVerifiedValue: String? = null,
    val lastVerifiedAt: Long? = null,
    val verifiedBindingRevision: Long? = null,
    val verifiedDesiredRevision: Long? = null,
    val remoteValue: String? = null,
    val remoteReadAt: Long? = null,
    val errorKind: PublishErrorKind? = null,
    val errorDetail: String? = null,
    val errorAt: Long? = null,
    val errorBindingRevision: Long? = null,
    val errorDesiredRevision: Long? = null,
    val outcomeUnknownSince: Long? = null,
    val needsDelivery: Boolean = false,
    val attemptsAtRevision: Int = 0,
    val lastAttemptAt: Long? = null,
    val boundaryValue: String? = null,
    val boundaryReadAt: Long? = null,
    val boundaryBindingRevision: Long? = null,
    val reconcilePending: Boolean = false,
    val lastRequestId: String? = null,
    val lastRequestAt: Long? = null,
)

/** A04 — whether the stored error stands: only at the pair it was recorded at. */
fun PublishDelivery.errorIsCurrent(binding: PublishBinding): Boolean =
    errorKind != null && errorBindingRevision == binding.revision && errorDesiredRevision == desiredRevision

/** #114 (C34) — a decommission obligation's state. */
enum class DecommissionState { PENDING, UNDELIVERABLE }

/**
 * #114 (C14, C34) — one `(connection, entity)` this installation let go of and still owes `1970-01-01`: the columns
 * of `ha_publish_decommission`. [assetId] is for display only (a soft link).
 */
data class DecommissionTombstone(
    val id: String,
    val connectionId: String,
    val entityId: String,
    val assetId: AssetId?,
    val installationId: String,
    val state: DecommissionState,
    val requestedAt: Long,
    val errorKind: PublishErrorKind?,
    val errorAt: Long?,
    val revision: Long,
)

/**
 * #113/#114 (C14, A09) — what one fresh read proves and where it was read: the exact [target], the connection's
 * [connectionId] and its [configGeneration] at the read, the binding's revision at the read (0 when none exists)
 * and the read time. Built only by the runner's `validate` (C30); the commands verify its provenance (C25).
 */
data class ValidationResult(
    val target: String,
    val remote: RemoteEntity,
    val connectionId: String,
    val configGeneration: Long,
    val bindingRevision: Long,
    val readAt: Long,
)

/**
 * #113/#114 (C14) — why a read, a write or a binding could not act. A separate set from #16's [SyncErrorKind]: the
 * shared transport members carry the same names ([publishKindOf]); the rest are the outbound path's own.
 */
enum class PublishErrorKind {
    ENDPOINT_REFUSED,
    DENIED,
    UNREACHABLE,
    TIMED_OUT,
    AUTH_REFUSED,
    REDIRECTED,
    HTTP_ERROR,
    MALFORMED,
    NEEDS_TOKEN,
    NOT_ON_LOCAL_NETWORK,
    NAME_NOT_LOCAL,
    TLS_FAILED,
    ENTITY_NOT_FOUND,
    WRITE_FORBIDDEN,
    WRONG_DOMAIN,
    OPTIONS_MISMATCH,
    NOT_DATE_ONLY,
    SERVICE_REFUSED,
    OUTCOME_UNKNOWN,
    REMOTE_DIFFERS,
    COPIED_INSTALLATION,
}

/**
 * #113 (C14) — what an error asks of the sender. [TRANSIENT]: retried with backoff. [WAIT]: no retry until the next
 * start, resume, eligibility change or Publish now. [NEEDS_OWNER]: no retry until a command moves the binding's pair
 * or Publish now.
 */
enum class RetryClass { TRANSIENT, WAIT, NEEDS_OWNER }

/**
 * #113 (C14) — each kind's retry class. `HTTP_ERROR` is transient only for a GET's 5xx ([detail] the status code);
 * a 4xx is permanent, and a POST's 5xx never reaches here as `HTTP_ERROR` (it is `OUTCOME_UNKNOWN`, C23).
 */
fun retryClassOf(kind: PublishErrorKind, detail: String?): RetryClass = when (kind) {
    PublishErrorKind.UNREACHABLE,
    PublishErrorKind.TIMED_OUT,
    PublishErrorKind.OUTCOME_UNKNOWN,
    -> RetryClass.TRANSIENT

    PublishErrorKind.HTTP_ERROR ->
        if (detail?.toIntOrNull() in 500..599) RetryClass.TRANSIENT else RetryClass.NEEDS_OWNER

    PublishErrorKind.NOT_ON_LOCAL_NETWORK,
    PublishErrorKind.DENIED,
    -> RetryClass.WAIT

    PublishErrorKind.ENDPOINT_REFUSED,
    PublishErrorKind.AUTH_REFUSED,
    PublishErrorKind.REDIRECTED,
    PublishErrorKind.MALFORMED,
    PublishErrorKind.NEEDS_TOKEN,
    PublishErrorKind.NAME_NOT_LOCAL,
    PublishErrorKind.TLS_FAILED,
    PublishErrorKind.ENTITY_NOT_FOUND,
    PublishErrorKind.WRITE_FORBIDDEN,
    PublishErrorKind.WRONG_DOMAIN,
    PublishErrorKind.OPTIONS_MISMATCH,
    PublishErrorKind.NOT_DATE_ONLY,
    PublishErrorKind.SERVICE_REFUSED,
    PublishErrorKind.REMOTE_DIFFERS,
    PublishErrorKind.COPIED_INSTALLATION,
    -> RetryClass.NEEDS_OWNER
}

/**
 * #113 (C14) — #16's transport kinds under their outbound names, for the shared request gate (C21). The four
 * season-only members never come from a read: `UNSUPPORTED_STATE` is #16's on/off rule, so for a helper it is
 * `MALFORMED`; `NOT_MAINTAINED_HERE`, `NOT_MANUAL` and `DATE_BEFORE_HISTORY` are #16 binding states the gate and the
 * transport never answer, mapped to `MALFORMED` so the function stays total without a throw.
 */
fun publishKindOf(kind: SyncErrorKind): PublishErrorKind = when (kind) {
    SyncErrorKind.ENDPOINT_REFUSED -> PublishErrorKind.ENDPOINT_REFUSED
    SyncErrorKind.DENIED -> PublishErrorKind.DENIED
    SyncErrorKind.UNREACHABLE -> PublishErrorKind.UNREACHABLE
    SyncErrorKind.TIMED_OUT -> PublishErrorKind.TIMED_OUT
    SyncErrorKind.AUTH_REFUSED -> PublishErrorKind.AUTH_REFUSED
    SyncErrorKind.ENTITY_NOT_FOUND -> PublishErrorKind.ENTITY_NOT_FOUND
    SyncErrorKind.REDIRECTED -> PublishErrorKind.REDIRECTED
    SyncErrorKind.HTTP_ERROR -> PublishErrorKind.HTTP_ERROR
    SyncErrorKind.MALFORMED -> PublishErrorKind.MALFORMED
    SyncErrorKind.NEEDS_TOKEN -> PublishErrorKind.NEEDS_TOKEN
    SyncErrorKind.NOT_ON_LOCAL_NETWORK -> PublishErrorKind.NOT_ON_LOCAL_NETWORK
    SyncErrorKind.NAME_NOT_LOCAL -> PublishErrorKind.NAME_NOT_LOCAL
    SyncErrorKind.TLS_FAILED -> PublishErrorKind.TLS_FAILED
    // #16's season-only members: never a transport answer for a helper (see above).
    SyncErrorKind.UNSUPPORTED_STATE,
    SyncErrorKind.NOT_MAINTAINED_HERE,
    SyncErrorKind.NOT_MANUAL,
    SyncErrorKind.DATE_BEFORE_HISTORY,
    -> PublishErrorKind.MALFORMED
}

/**
 * #113/#114 (C14) — a binding's state for display and the API; derived when read, never stored. Each member has
 * one sentence on the phone (§5).
 */
enum class PublishState {
    NOT_ENABLED,
    COPIED_INSTALLATION,
    NEEDS_VALIDATION,
    NEEDS_TOKEN,
    WAITING_FOR_SOURCE,
    NOT_MAINTAINED_HERE,
    PENDING,
    OUTCOME_UNKNOWN,
    ERROR,
    PUBLISHED,
    MATCHES,
    INVALIDATED_INELIGIBLE,
}
