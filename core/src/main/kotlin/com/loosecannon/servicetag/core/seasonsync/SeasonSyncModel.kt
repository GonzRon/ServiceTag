package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonAction

/**
 * #16 (C4) — the one Home Assistant connection of this installation (R16-10). [baseUrl] is canonical
 * `scheme://host[:port]`: lowercase scheme and host, no path, no trailing slash. There is no token here and no
 * marker of one: whether a token exists is the [SecretStore]'s answer alone (C9, C18), keyed by [id].
 *
 * The connection's settings (C4a, R16-Q-D, R16-20) are the installation's, not a binding's: [cadence], how often it
 * is checked; [networkEligibility], where the token may be sent from; [homeNetworkSsid], the home network captured at
 * setup (C32, R16-21); and [backgroundChecks], whether the home-network mode also checks from background work.
 *
 * [homeNetworkSsid] is the Wi-Fi network's name as Android reports it, without its quotes, compared exactly; null when
 * nothing is captured. It is never a BSSID, an address, a location or a time. A wired network has no name, so it is
 * never captured. Every stored row keeps two invariants, which C17's writers hold:
 * [NetworkEligibility.HOME_NETWORK_ONLY] exactly when [homeNetworkSsid] is not null, and an `http` [baseUrl] only with
 * [NetworkEligibility.HOME_NETWORK_ONLY].
 *
 * Device-local configuration: never in a ServiceTag backup, export, merge or pack (R16-Q-E).
 */
data class HaConnection(
    val id: String,
    val baseUrl: String,
    val cadence: SyncCadence,
    val networkEligibility: NetworkEligibility,
    val homeNetworkSsid: String?,
    val backgroundChecks: BackgroundChecks,
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
 * at setup, [HaConnection.homeNetworkSsid]; the one mode an http address is allowed in (C8, C19).
 */
enum class NetworkEligibility { ANY_NETWORK, HOME_NETWORK_ONLY }

/**
 * #16 (C4a, R16-20 as amended) — whether a [NetworkEligibility.HOME_NETWORK_ONLY] connection is also checked from
 * background work. [OFF], what a new connection gets (C17): checks run only when the app is opened and on Sync now,
 * and nothing more is asked of Android. [ON]: the app may ask for what Android needs to name the Wi-Fi network from
 * the background, and the periodic work then checks only while the captured network matches; refused or withdrawn,
 * it falls back to [OFF]'s behaviour. Stored whatever the eligibility; [NetworkEligibility.ANY_NETWORK] always checks
 * in the background, so there it changes nothing.
 */
enum class BackgroundChecks { OFF, ON }

/**
 * #16 (C4a, C32) — the network this phone is on as C32's reader saw it, once, just before a request: a Wi-Fi
 * network and its name; a Wi-Fi network whose name Android hid ([WifiUnnamed]); [Wired], a wired network, which
 * has no name and so is never the home network; [Other], any other transport; or [None], no network at all.
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
 * Wi-Fi network whose name equals [HaConnection.homeNetworkSsid] exactly. A hidden name, a wired network and a
 * home-network connection with nothing captured are never eligible. A name match narrows where the token goes; it
 * cannot prove which network this is (limit 15).
 */
fun eligibleNow(connection: HaConnection, current: CurrentNetwork): Boolean =
    when (connection.networkEligibility) {
        NetworkEligibility.ANY_NETWORK -> true
        NetworkEligibility.HOME_NETWORK_ONLY -> when (current) {
            is CurrentNetwork.Wifi -> current.ssid == connection.homeNetworkSsid
            CurrentNetwork.WifiUnnamed, CurrentNetwork.Wired, CurrentNetwork.Other, CurrentNetwork.None -> false
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
