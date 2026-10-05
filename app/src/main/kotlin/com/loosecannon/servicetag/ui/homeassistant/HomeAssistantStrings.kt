package com.loosecannon.servicetag.ui.homeassistant

import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.l10n.localized

// #16 (§5) — the Home Assistant screen's sentences and the check outcomes both screens draw, each declared once here
// and imported, never copied. P16-1…21 and P16-48…52 RATIFIED 2026-10-02 (R16-Q-C); P16-50 and P16-53…82 RATIFIED
// 2026-10-03 with the owner's amendments to P16-60, P16-66, P16-67, P16-69, P16-75 and P16-80 (P16-64 withdrawn).
// P16-32 lives with the season card's strings.
//
// #102: the English text is in res/values/strings_home_assistant_settings.xml, byte for byte, under each P16 id; these
// names read it in the current language when drawn, so the screen, the models and the tests keep their names.

/** P16-1: the Settings → Utilities row and the screen's title. */
internal val HA_TITLE: String get() = localized(R.string.ha_title)

/** P16-2: the address field's label. */
internal val HA_SERVER_ADDRESS: String get() = localized(R.string.ha_server_address)

/** P16-3: the address field's helper. */
internal val HA_SERVER_ADDRESS_HELP: String get() = localized(R.string.ha_server_address_help)

/** P16-4: the masked field's label. */
internal val HA_ACCESS_TOKEN: String get() = localized(R.string.ha_access_token)

/** P16-5: the token field's helper. */
internal val HA_ACCESS_TOKEN_HELP: String get() = localized(R.string.ha_access_token_help)

/** P16-6: the button. */
internal val HA_TEST_CONNECTION: String get() = localized(R.string.ha_test_connection)

/** P16-7: Test connection's success line. */
internal val HA_CONNECTION_WORKS: String get() = localized(R.string.ha_connection_works)

/** P16-8: the button and the dialog's confirm. */
internal val HA_DISCONNECT: String get() = localized(R.string.ha_disconnect)

/** P16-9: the confirm dialog's body, no title. */
internal val HA_DISCONNECT_BODY: String get() = localized(R.string.ha_disconnect_body)

/** P16-10: the screen with no connection. */
internal val HA_NOT_CONNECTED: String get() = localized(R.string.ha_not_connected)

/** P16-11: `NEEDS_TOKEN`, on both screens. */
internal val HA_ENTER_TOKEN_AGAIN: String get() = localized(R.string.ha_enter_token_again)

/** P16-12: `ENDPOINT_REFUSED`. */
internal val HA_ADDRESS_NOT_ALLOWED: String get() = localized(R.string.ha_address_not_allowed)

/** P16-13: `UNREACHABLE`. */
internal val HA_COULD_NOT_REACH: String get() = localized(R.string.ha_could_not_reach)

/** P16-14: `TIMED_OUT`. */
internal val HA_TOOK_TOO_LONG: String get() = localized(R.string.ha_took_too_long)

/** P16-15: `DENIED`, with "Open app settings". */
internal val HA_NETWORK_NOT_ALLOWED: String get() = localized(R.string.ha_network_not_allowed)

/** P16-16: `AUTH_REFUSED`. */
internal val HA_TOKEN_REFUSED: String get() = localized(R.string.ha_token_refused)

/** P16-17: `ENTITY_NOT_FOUND`; [entityId] the linked entity. */
internal fun haNoSuchEntity(entityId: String): String = localized(R.string.ha_no_such_entity, entityId)

/** P16-18: `UNSUPPORTED_STATE`; [state] HA's reported state (bounded by C5), [entityId] the linked entity. */
internal fun haNeitherOnNorOff(state: String, entityId: String): String =
    localized(R.string.ha_neither_on_nor_off, state, entityId)

/** P16-19: `MALFORMED`. */
internal val HA_ANSWER_UNREADABLE: String get() = localized(R.string.ha_answer_unreadable)

/** P16-20: `REDIRECTED`. */
internal val HA_ANSWERED_WITH_REDIRECT: String get() = localized(R.string.ha_answered_with_redirect)

/** P16-21: `HTTP_ERROR`; [status] the HTTP status. */
internal fun haAnsweredWithError(status: String): String = localized(R.string.ha_answered_with_error, status)

/** P16-48: the token field after a save. */
internal val HA_TOKEN_SAVED: String get() = localized(R.string.ha_token_saved)

/** P16-50: `NOT_ON_LOCAL_NETWORK` on a different Wi-Fi, mobile data or no network. */
internal val HA_NOT_ON_HOME_WIFI: String get() = localized(R.string.ha_not_on_home_wifi)

/** P16-51: `NAME_NOT_LOCAL`. */
internal val HA_NAME_NOT_PRIVATE: String get() = localized(R.string.ha_name_not_private)

/** P16-52: `TLS_FAILED`. */
internal val HA_CERTIFICATE_NOT_VERIFIED: String get() = localized(R.string.ha_certificate_not_verified)

/** P16-53: the cadence control's label. */
internal val HA_HOW_OFTEN: String get() = localized(R.string.ha_how_often)

/** P16-54…57: the four cadence choices, in this order; "Once a day" is preselected (R16-Q-D). */
internal val HA_EVERY_12_HOURS: String get() = localized(R.string.ha_every_12_hours)
internal val HA_ONCE_A_DAY: String get() = localized(R.string.ha_once_a_day)
internal val HA_ONCE_A_WEEK: String get() = localized(R.string.ha_once_a_week)
internal val HA_ONCE_A_MONTH: String get() = localized(R.string.ha_once_a_month)

/** P16-54…57 by cadence: an exhaustive `when`, so a fifth cadence cannot reach the screen without its words. */
internal fun cadenceLabel(cadence: SyncCadence): String = when (cadence) {
    SyncCadence.EVERY_12_HOURS -> HA_EVERY_12_HOURS
    SyncCadence.DAILY -> HA_ONCE_A_DAY
    SyncCadence.WEEKLY -> HA_ONCE_A_WEEK
    SyncCadence.MONTHLY -> HA_ONCE_A_MONTH
}

/** P16-58: the network control's label. */
internal val HA_WHERE_TO_CHECK: String get() = localized(R.string.ha_where_to_check)

/** P16-59: network choice (https only). */
internal val HA_ANY_NETWORK: String get() = localized(R.string.ha_any_network)

/** P16-60 (amended 2026-10-03): network choice. */
internal val HA_HOME_WIFI_ONLY: String get() = localized(R.string.ha_home_wifi_only)

/** P16-61: under "Any network". */
internal val HA_ANY_NETWORK_HELP: String get() = localized(R.string.ha_any_network_help)

/** P16-62: the capture button under P16-60. */
internal val HA_USE_THIS_NETWORK: String get() = localized(R.string.ha_use_this_network)

/** P16-63: after a capture; [name] the captured Wi-Fi name. */
internal fun haHomeWifi(name: String): String = localized(R.string.ha_home_wifi, name)

/** P16-65: before the precise-location request, when P16-60 is chosen. */
internal val HA_PRECISE_LOCATION_WHY: String get() = localized(R.string.ha_precise_location_why)

/** P16-66 (amended 2026-10-03): after a refusal or an approximate-only grant. */
internal val HA_PRECISE_LOCATION_REFUSED: String get() = localized(R.string.ha_precise_location_refused)

/** P16-67 (amended 2026-10-03): saving an http address with "Any network". */
internal val HA_HTTP_NEEDS_HOME_WIFI: String get() = localized(R.string.ha_http_needs_home_wifi)

/** P16-68: at capture with Location off; after P16-77 when Location is off. */
internal val HA_TURN_ON_LOCATION: String get() = localized(R.string.ha_turn_on_location)

/** P16-69 (amended 2026-10-03): under P16-70 while background checks are off or paused. */
internal val HA_BACKGROUND_OFF: String get() = localized(R.string.ha_background_off)

/** P16-70: under P16-60, the sub-setting's label. */
internal val HA_BACKGROUND_CHECKS: String get() = localized(R.string.ha_background_checks)

/** P16-71: background checks choice, preselected. */
internal val HA_OFF: String get() = localized(R.string.ha_off)

/** P16-72: background checks choice. */
internal val HA_ON: String get() = localized(R.string.ha_on)

/**
 * P16-73: before the background-location request; [label] is Android's own label for the option, which Android
 * already gives in the phone's language — passed in, never translated here.
 */
internal fun haBackgroundLocationWhy(label: String): String = localized(R.string.ha_background_location_why, label)

/** P16-74: under P16-70 while background checks run. */
internal val HA_BACKGROUND_RUNNING: String get() = localized(R.string.ha_background_running)

/** P16-75 (amended 2026-10-03): On without the background grant. */
internal val HA_BACKGROUND_PAUSED: String get() = localized(R.string.ha_background_paused)

/** P16-76: the action under P16-75 and P16-78, re-running the matching permission flow. */
internal val HA_ALLOW_AGAIN: String get() = localized(R.string.ha_allow_again)

/** P16-77: the Wi-Fi name could not be read, so nothing was sent. */
internal val HA_COULD_NOT_CONFIRM_WIFI: String get() = localized(R.string.ha_could_not_confirm_wifi)

/** P16-78: after P16-77, or on the screen: permission withdrawn, a one-time grant expired, or reset by Android. */
internal val HA_PRECISE_LOCATION_WITHDRAWN: String get() = localized(R.string.ha_precise_location_withdrawn)

/** P16-79: after P16-77, or on the screen, with "Open app settings". */
internal val HA_APPROXIMATE_ONLY: String get() = localized(R.string.ha_approximate_only)

/** P16-80 (amended 2026-10-03): after P16-77, the read ran outside the foreground. */
internal val HA_NOT_READ_IN_BACKGROUND: String get() = localized(R.string.ha_not_read_in_background)

/** P16-81: at capture on Ethernet; after P16-77 on Ethernet. */
internal val HA_ON_ETHERNET: String get() = localized(R.string.ha_on_ethernet)

/** P16-82: at capture on mobile data or with no network. */
internal val HA_CONNECT_TO_HOME_WIFI: String get() = localized(R.string.ha_connect_to_home_wifi)
