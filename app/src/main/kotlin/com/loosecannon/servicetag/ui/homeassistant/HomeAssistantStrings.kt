package com.loosecannon.servicetag.ui.homeassistant

import com.loosecannon.servicetag.core.seasonsync.SyncCadence

// #16 (§5) — the Home Assistant screen's sentences and the check outcomes both screens draw, each declared once here
// and imported, never copied. P16-1…21 and P16-48…52 RATIFIED 2026-10-02 (R16-Q-C); P16-50 and P16-53…82 RATIFIED
// 2026-10-03 with the owner's amendments to P16-60, P16-66, P16-67, P16-69, P16-75 and P16-80 (P16-64 withdrawn).
// P16-32 lives with the season card's strings.

/** P16-1: the Settings → Utilities row and the screen's title. */
internal const val HA_TITLE = "Home Assistant"

/** P16-2: the address field's label. */
internal const val HA_SERVER_ADDRESS = "Server address"

/** P16-3: the address field's helper. */
internal const val HA_SERVER_ADDRESS_HELP =
    "Use https:// with the server's name or private IPv4 address, or http:// with its private IPv4 address on your " +
        "home network."

/** P16-4: the masked field's label. */
internal const val HA_ACCESS_TOKEN = "Access token"

/** P16-5: the token field's helper. */
internal const val HA_ACCESS_TOKEN_HELP =
    "A long-lived access token from a Home Assistant user made for ServiceTag, without administrator rights. " +
        "ServiceTag uses it only for read requests."

/** P16-6: the button. */
internal const val HA_TEST_CONNECTION = "Test connection"

/** P16-7: Test connection's success line. */
internal const val HA_CONNECTION_WORKS = "Home Assistant answered. The address and the token work."

/** P16-8: the button and the dialog's confirm. */
internal const val HA_DISCONNECT = "Disconnect"

/** P16-9: the confirm dialog's body, no title. */
internal const val HA_DISCONNECT_BODY =
    "Disconnect Home Assistant? Linked assets stop following it and keep their current season and history. The " +
        "token is deleted from this phone; revoke it in Home Assistant as well."

/** P16-10: the screen with no connection. */
internal const val HA_NOT_CONNECTED = "Not connected. Enter the server address and an access token."

/** P16-11: `NEEDS_TOKEN`, on both screens. */
internal const val HA_ENTER_TOKEN_AGAIN = "Enter the access token again: this phone no longer has it."

/** P16-12: `ENDPOINT_REFUSED`. */
internal const val HA_ADDRESS_NOT_ALLOWED =
    "That address is not allowed. Use https:// with a server name or private IPv4 address, or http:// with a " +
        "private IPv4 address."

/** P16-13: `UNREACHABLE`. */
internal const val HA_COULD_NOT_REACH = "Could not reach Home Assistant. Check that this phone is on the same network."

/** P16-14: `TIMED_OUT`. */
internal const val HA_TOOK_TOO_LONG = "Home Assistant took too long to answer."

/** P16-15: `DENIED`, with "Open app settings". */
internal const val HA_NETWORK_NOT_ALLOWED =
    "ServiceTag is not allowed to use the network. Allow network access in the app settings, then close " +
        "ServiceTag and open it again."

/** P16-16: `AUTH_REFUSED`. */
internal const val HA_TOKEN_REFUSED = "Home Assistant refused the access token."

/** P16-17: `ENTITY_NOT_FOUND`; [entityId] the linked entity. */
internal fun haNoSuchEntity(entityId: String): String = "Home Assistant has no entity $entityId."

/** P16-18: `UNSUPPORTED_STATE`; [state] HA's reported state (bounded by C5), [entityId] the linked entity. */
internal fun haNeitherOnNorOff(state: String, entityId: String): String =
    "Home Assistant reports \"$state\" for $entityId, which is neither on nor off, so the season is unchanged."

/** P16-19: `MALFORMED`. */
internal const val HA_ANSWER_UNREADABLE = "Home Assistant sent an answer ServiceTag cannot read."

/** P16-20: `REDIRECTED`. */
internal const val HA_ANSWERED_WITH_REDIRECT =
    "Home Assistant answered with a redirect, which ServiceTag does not follow. Enter the address it redirects to."

/** P16-21: `HTTP_ERROR`; [status] the HTTP status. */
internal fun haAnsweredWithError(status: String): String = "Home Assistant answered with an error ($status)."

/** P16-48: the token field after a save. */
internal const val HA_TOKEN_SAVED = "A token is saved on this phone."

/** P16-50: `NOT_ON_LOCAL_NETWORK` on a different Wi-Fi, mobile data or no network. */
internal const val HA_NOT_ON_HOME_WIFI =
    "This phone is not on your home Wi-Fi, so ServiceTag did not contact Home Assistant."

/** P16-51: `NAME_NOT_LOCAL`. */
internal const val HA_NAME_NOT_PRIVATE =
    "That server name does not resolve only to private network addresses, so ServiceTag did not contact it."

/** P16-52: `TLS_FAILED`. */
internal const val HA_CERTIFICATE_NOT_VERIFIED = "Home Assistant's certificate could not be verified."

/** P16-53: the cadence control's label. */
internal const val HA_HOW_OFTEN = "How often to check"

/** P16-54…57: the four cadence choices, in this order; "Once a day" is preselected (R16-Q-D). */
internal const val HA_EVERY_12_HOURS = "Every 12 hours"
internal const val HA_ONCE_A_DAY = "Once a day"
internal const val HA_ONCE_A_WEEK = "Once a week"
internal const val HA_ONCE_A_MONTH = "Once a month"

/** P16-54…57 by cadence: an exhaustive `when`, so a fifth cadence cannot reach the screen without its words. */
internal fun cadenceLabel(cadence: SyncCadence): String = when (cadence) {
    SyncCadence.EVERY_12_HOURS -> HA_EVERY_12_HOURS
    SyncCadence.DAILY -> HA_ONCE_A_DAY
    SyncCadence.WEEKLY -> HA_ONCE_A_WEEK
    SyncCadence.MONTHLY -> HA_ONCE_A_MONTH
}

/** P16-58: the network control's label. */
internal const val HA_WHERE_TO_CHECK = "Where to check"

/** P16-59: network choice (https only). */
internal const val HA_ANY_NETWORK = "Any network"

/** P16-60 (amended 2026-10-03): network choice. */
internal const val HA_HOME_WIFI_ONLY = "Only on this home Wi-Fi"

/** P16-61: under "Any network". */
internal const val HA_ANY_NETWORK_HELP =
    "Only for an https:// address you have made reachable from outside your home. The token is sent from whatever " +
        "network this phone is on."

/** P16-62: the capture button under P16-60. */
internal const val HA_USE_THIS_NETWORK = "Use the network I'm on now"

/** P16-63: after a capture; [name] the captured Wi-Fi name. */
internal fun haHomeWifi(name: String): String = "Home Wi-Fi: $name"

/** P16-65: before the precise-location request, when P16-60 is chosen. */
internal const val HA_PRECISE_LOCATION_WHY =
    "To check that this phone is on your home Wi-Fi before it sends the access token, ServiceTag needs to read the " +
        "Wi-Fi network's name. Android allows that only with precise Location permission, so choose Precise. " +
        "ServiceTag does not use your location, but Android will list it among apps that used location."

/** P16-66 (amended 2026-10-03): after a refusal or an approximate-only grant. */
internal const val HA_PRECISE_LOCATION_REFUSED =
    "Without precise Location permission, ServiceTag cannot tell which Wi-Fi this is, so ‘Only on this home " +
        "Wi-Fi’ stays off."

/** P16-67 (amended 2026-10-03): saving an http address with "Any network". */
internal const val HA_HTTP_NEEDS_HOME_WIFI =
    "An http:// address needs ‘Only on this home Wi-Fi’. Choose it, or use an https:// address."

/** P16-68: at capture with Location off; after P16-77 when Location is off. */
internal const val HA_TURN_ON_LOCATION =
    "Turn on Location so ServiceTag can read the Wi-Fi network's name, then try again."

/** P16-69 (amended 2026-10-03): under P16-70 while background checks are off or paused. */
internal const val HA_BACKGROUND_OFF =
    "With background checks off, ServiceTag checks when you open or return to the app after the chosen interval, " +
        "and when you tap Sync now. It still needs precise Location while the app is in use."

/** P16-70: under P16-60, the sub-setting's label. */
internal const val HA_BACKGROUND_CHECKS = "Background checks on this home network"

/** P16-71: background checks choice, preselected. */
internal const val HA_OFF = "Off"

/** P16-72: background checks choice. */
internal const val HA_ON = "On"

/** P16-73: before the background-location request; [label] is Android's own label for the option. */
internal fun haBackgroundLocationWhy(label: String): String =
    "To check in the background, ServiceTag must read the Wi-Fi network's name while the app is closed. Android " +
        "allows that only when Location is set to \"$label\". ServiceTag reads only the network's name, never " +
        "where you are, and Android will remind you that it has this access."

/** P16-74: under P16-70 while background checks run. */
internal const val HA_BACKGROUND_RUNNING =
    "ServiceTag also checks in the background while this phone is on your home Wi-Fi."

/** P16-75 (amended 2026-10-03): On without the background grant. */
internal const val HA_BACKGROUND_PAUSED =
    "Background checks are paused because Android does not let ServiceTag read the Wi-Fi network's name in the " +
        "background. ServiceTag checks when you open or return to the app and when you tap Sync now."

/** P16-76: the action under P16-75 and P16-78, re-running the matching permission flow. */
internal const val HA_ALLOW_AGAIN = "Allow again"

/** P16-77: the Wi-Fi name could not be read, so nothing was sent. */
internal const val HA_COULD_NOT_CONFIRM_WIFI =
    "ServiceTag could not confirm that this phone is on your home Wi-Fi, so it did not contact Home Assistant."

/** P16-78: after P16-77, or on the screen: permission withdrawn, a one-time grant expired, or reset by Android. */
internal const val HA_PRECISE_LOCATION_WITHDRAWN =
    "ServiceTag no longer has permission to read the Wi-Fi network's name. Allow precise Location again."

/** P16-79: after P16-77, or on the screen, with "Open app settings". */
internal const val HA_APPROXIMATE_ONLY =
    "ServiceTag has only approximate Location, which hides the Wi-Fi network's name. Change it to Precise in the " +
        "app settings."

/** P16-80 (amended 2026-10-03): after P16-77, the read ran outside the foreground. */
internal const val HA_NOT_READ_IN_BACKGROUND =
    "ServiceTag could not read the Wi-Fi network's name in the background. It checks again when you open or " +
        "return to the app."

/** P16-81: at capture on Ethernet; after P16-77 on Ethernet. */
internal const val HA_ON_ETHERNET =
    "On Ethernet, ServiceTag cannot tell which network this is. Connect to your home Wi-Fi, or choose \"Any " +
        "network\" with an https:// address."

/** P16-82: at capture on mobile data or with no network. */
internal const val HA_CONNECT_TO_HOME_WIFI = "Connect this phone to your home Wi-Fi, then try again."
