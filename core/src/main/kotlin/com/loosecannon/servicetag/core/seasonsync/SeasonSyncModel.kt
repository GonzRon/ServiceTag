package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonAction

/**
 * #16 (C4) — the one Home Assistant connection of this installation (R16-10). [baseUrl] is canonical
 * `scheme://host[:port]`: lowercase scheme and host, no path, no trailing slash. There is no token here and no
 * marker of one: whether a token exists is the [SecretStore]'s answer alone (C9, C18), keyed by [id].
 *
 * Device-local configuration: never in a ServiceTag backup, export, merge or pack (R16-Q-E).
 */
data class HaConnection(
    val id: String,
    val baseUrl: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** #16 (C1) — who decides a linked asset's season: Home Assistant's answer, or the owner's forced phase. */
enum class SyncMode { FOLLOW, FORCE_IN, FORCE_OUT }

/** #16 (C1, C5) — the only two answers that are a decision: exactly `on` and exactly `off`. */
enum class HaSwitchState { ON, OFF }

/**
 * #16 (C4) — every outcome that is "no new decision", and every reason a binding cannot act. A closed set: each
 * member has exactly one ratified sentence on the phone (§5), drawn by an exhaustive `when`.
 */
enum class SyncErrorKind {
    ENDPOINT_REFUSED,
    DENIED,
    UNREACHABLE,
    TIMED_OUT,
    AUTH_REFUSED,
    ENTITY_NOT_FOUND,
    REDIRECTED,
    HTTP_ERROR,
    MALFORMED,
    UNSUPPORTED_STATE,
    NEEDS_TOKEN,
    NOT_MAINTAINED_HERE,
    NOT_MANUAL,
    DATE_BEFORE_HISTORY,
    NOT_ON_LOCAL_NETWORK,
    NAME_NOT_LOCAL,
    TLS_FAILED,
}

/**
 * #16 (C4) — one asset's link to one Home Assistant on/off entity: exactly the columns of `season_sync_binding`
 * (C9) under their Kotlin names. At most one per asset; enum columns hold the enum name.
 *
 * [revision] moves by one on every write to the row (C13, C17), so a result read at an older revision is dropped.
 * The three times are three fields (R16-3): HA's own change text [observedChangedAt], information only and never
 * parsed into a date for a decision; the fetch time of the last valid answer [lastSuccessAt], which a failure never
 * moves; and the application time [appliedAt], dated [appliedOn] (ISO `YYYY-MM-DD`, the day it was applied).
 * [appliedAction], [appliedOn] and [appliedAt] are the provenance of the last change the binding made (R16-11).
 *
 * Whether the binding is ACTIVE, STOPPED, NEEDS_TOKEN or NOT_MAINTAINED_HERE is derived when it is read (C17),
 * never stored. No field holds a token.
 */
data class SeasonSyncBinding(
    val assetId: AssetId,
    val connectionId: String,
    val entityId: String,
    val mode: SyncMode,
    val enabled: Boolean,
    val revision: Long,
    val observedState: HaSwitchState?,
    val observedChangedAt: String?,
    val lastSuccessAt: Long?,
    val lastAttemptAt: Long?,
    val errorKind: SyncErrorKind?,
    val errorDetail: String?,
    val errorAt: Long?,
    val appliedAction: SeasonAction?,
    val appliedOn: String?,
    val appliedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** #16 (C4) — the longest entity id a link accepts. */
const val MAX_ENTITY_ID_LENGTH = 255

private val ENTITY_ID_SHAPE = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")

/**
 * #16 (C4) — the entity id rule: a lowercase `domain.object_id` of `[a-z0-9_]` on each side of exactly one dot, at
 * most [MAX_ENTITY_ID_LENGTH] characters, so it is one URL path segment that needs no encoding. Anything else is
 * refused when it is linked (C16) and never sent.
 */
fun isValidEntityId(entityId: String): Boolean =
    entityId.length <= MAX_ENTITY_ID_LENGTH && ENTITY_ID_SHAPE.matches(entityId)

/**
 * #16 (C4, R16-5) — the access token, held only by the [SecretStore] and, for one request, by the client (C19).
 * Its [toString] is a constant, so a template, a log line or an exception message that meets one prints nothing of
 * it. No model type holds one as a field.
 */
@JvmInline
value class Secret(val value: String) {
    override fun toString(): String = "Secret(redacted)"
}
