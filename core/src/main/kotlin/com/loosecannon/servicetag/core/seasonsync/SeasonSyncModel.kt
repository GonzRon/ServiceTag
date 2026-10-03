package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonAction

/**
 * #16 (C4) — the one Home Assistant connection of this installation (R16-10). [baseUrl] is canonical
 * `scheme://host[:port]`: lowercase scheme and host, no path, no trailing slash. There is no token here and no
 * marker of one: whether a token exists is the [SecretStore]'s answer alone (C9, C18), keyed by [id].
 *
 * The connection's three settings (C4a, R16-Q-D) are the installation's, not a binding's: [cadence], how often it
 * is checked; [networkEligibility], where the token may be sent from; and [homeNetwork], the network captured at
 * setup (C32). Every stored row keeps two invariants, which C17's writers hold: [NetworkEligibility.HOME_NETWORK_ONLY]
 * exactly when [homeNetwork] is not null, and an `http` [baseUrl] only with [NetworkEligibility.HOME_NETWORK_ONLY].
 *
 * Device-local configuration: never in a ServiceTag backup, export, merge or pack (R16-Q-E).
 */
data class HaConnection(
    val id: String,
    val baseUrl: String,
    val cadence: SyncCadence,
    val networkEligibility: NetworkEligibility,
    val homeNetwork: HomeNetwork?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * #16 (C4a, R16-Q-D) — how often the connection is checked: exactly these four, and no other period exists. [hours]
 * is the requested period of the background work (C22), which may run late and is never a deadline, and the stale
 * threshold (C21, C27). A new connection gets [DAILY] (C17).
 */
enum class SyncCadence(val hours: Long) {
    EVERY_12_HOURS(12),
    DAILY(24),
    WEEKLY(168),
    MONTHLY(720),
}

/**
 * #16 (C4a, R16-Q-D) — where the token may be sent from. [ANY_NETWORK]: from any network, to an https origin the
 * owner made reachable themselves (limit 18). [HOME_NETWORK_ONLY]: only while this phone is on the network captured
 * at setup, [HaConnection.homeNetwork]; the one mode an http address is allowed in (C8, C19).
 */
enum class NetworkEligibility { ANY_NETWORK, HOME_NETWORK_ONLY }

/**
 * #16 (C4a, C32, R16-21) — the home network's identity, captured at setup: the Wi-Fi network's name as Android
 * reports it, without its quotes, compared exactly; or [Wired] for Ethernet, which has no name. Never a BSSID, an
 * address, a location or a time.
 */
sealed interface HomeNetwork {
    data class Wifi(val ssid: String) : HomeNetwork

    data object Wired : HomeNetwork
}

/**
 * #16 (C4a, C32) — the network this phone is on as C32's reader saw it, once, just before a request: a Wi-Fi
 * network and its name; a Wi-Fi network whose name Android hid ([WifiUnnamed]); [Wired]; [Other], any other
 * transport; or [None], no network at all.
 */
sealed interface CurrentNetwork {
    data class Wifi(val ssid: String) : CurrentNetwork

    data object WifiUnnamed : CurrentNetwork

    data object Wired : CurrentNetwork

    data object Other : CurrentNetwork

    data object None : CurrentNetwork
}

/**
 * #16 (C4a, C19 step 1a, I10) — whether [connection]'s token may be sent from [current].
 * [NetworkEligibility.ANY_NETWORK] is true on every network. [NetworkEligibility.HOME_NETWORK_ONLY] is true only on a
 * Wi-Fi network whose name equals the captured one exactly, or on a wired network when the captured one is
 * [HomeNetwork.Wired]. A hidden name is never eligible, and neither is a home-network connection with nothing
 * captured. A name match narrows where the token goes; it cannot prove which network this is (limit 15).
 */
fun eligibleNow(connection: HaConnection, current: CurrentNetwork): Boolean =
    when (connection.networkEligibility) {
        NetworkEligibility.ANY_NETWORK -> true
        NetworkEligibility.HOME_NETWORK_ONLY -> when (current) {
            is CurrentNetwork.Wifi -> connection.homeNetwork == HomeNetwork.Wifi(current.ssid)
            CurrentNetwork.Wired -> connection.homeNetwork == HomeNetwork.Wired
            CurrentNetwork.WifiUnnamed, CurrentNetwork.Other, CurrentNetwork.None -> false
        }
    }

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
