package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.seasonsync.AppliedLine
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncLinkRefusal
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.seasonsync.UnconfirmedCause
import com.loosecannon.servicetag.ui.homeassistant.HA_ADDRESS_NOT_ALLOWED
import com.loosecannon.servicetag.ui.homeassistant.HA_ANSWERED_WITH_REDIRECT
import com.loosecannon.servicetag.ui.homeassistant.HA_ANSWER_UNREADABLE
import com.loosecannon.servicetag.ui.homeassistant.HA_CERTIFICATE_NOT_VERIFIED
import com.loosecannon.servicetag.ui.homeassistant.HA_COULD_NOT_CONFIRM_WIFI
import com.loosecannon.servicetag.ui.homeassistant.HA_COULD_NOT_REACH
import com.loosecannon.servicetag.ui.homeassistant.HA_ENTER_TOKEN_AGAIN
import com.loosecannon.servicetag.ui.homeassistant.HA_NAME_NOT_PRIVATE
import com.loosecannon.servicetag.ui.homeassistant.HA_NETWORK_NOT_ALLOWED
import com.loosecannon.servicetag.ui.homeassistant.HA_NOT_CONNECTED
import com.loosecannon.servicetag.ui.homeassistant.HA_NOT_ON_HOME_WIFI
import com.loosecannon.servicetag.ui.homeassistant.HA_TOKEN_REFUSED
import com.loosecannon.servicetag.ui.homeassistant.HA_TOOK_TOO_LONG
import com.loosecannon.servicetag.ui.homeassistant.Notice
import com.loosecannon.servicetag.ui.homeassistant.NoticeAction
import com.loosecannon.servicetag.ui.homeassistant.haAnsweredWithError
import com.loosecannon.servicetag.ui.homeassistant.haNeitherOnNorOff
import com.loosecannon.servicetag.ui.homeassistant.haNoSuchEntity
import com.loosecannon.servicetag.ui.homeassistant.remedyFor

// #16 (C27, §5) — the season card's Home Assistant block: its ratified sentences, each declared once here, and the
// maps from a code to its sentence. The outcome sentences the Home Assistant screen also draws live in
// HomeAssistantStrings.kt and are imported, never copied. The setup sheet's and the editor's (P16-42…47, P16-49)
// close the file.

/** P16-22 — the card's action on an asset with no binding while a connection exists. */
internal const val SEASON_SYNC_LINK = "Link to Home Assistant"

/** P16-23…25 — the three-way mode control's choices. */
internal const val SEASON_SYNC_FOLLOW = "Follow Home Assistant"
internal const val SEASON_SYNC_FORCE_IN = "Force in season"
internal const val SEASON_SYNC_FORCE_OUT = "Force out of season"

/** The mode control's word for [mode], in the order drawn ([SyncMode.entries]). */
internal fun seasonSyncModeLabel(mode: SyncMode): String = when (mode) {
    SyncMode.FOLLOW -> SEASON_SYNC_FOLLOW
    SyncMode.FORCE_IN -> SEASON_SYNC_FORCE_IN
    SyncMode.FORCE_OUT -> SEASON_SYNC_FORCE_OUT
}

/** P16-26. */
internal const val SEASON_SYNC_NOW = "Sync now"

/** P16-27 — the source line in FOLLOW. */
internal fun seasonSyncFollows(entityId: String): String = "Follows $entityId"

/** P16-28 — the source line in FORCE_IN. */
internal fun seasonSyncForcedIn(entityId: String): String =
    "Forced in season. $entityId is still checked, but it does not change the season until you choose Follow " +
        "Home Assistant."

/** P16-29 — the source line in FORCE_OUT. */
internal fun seasonSyncForcedOut(entityId: String): String =
    "Forced out of season. $entityId is still checked, but it does not change the season until you choose " +
        "Follow Home Assistant."

/** The source line for [mode]: Home Assistant's entity decides, or the owner's forced season does. */
internal fun seasonSyncSourceLine(mode: SyncMode, entityId: String): String = when (mode) {
    SyncMode.FOLLOW -> seasonSyncFollows(entityId)
    SyncMode.FORCE_IN -> seasonSyncForcedIn(entityId)
    SyncMode.FORCE_OUT -> seasonSyncForcedOut(entityId)
}

/** P16-30 — the last valid observation's fetch time, in the shipped date-and-time display. */
internal fun seasonSyncLastSuccess(at: String): String = "Last successful check $at"

/** P16-31 — no valid observation yet. */
internal const val SEASON_SYNC_NO_READING_YET = "No reading from Home Assistant yet."

/** P16-32 — the stale marker: no success within one cadence. */
internal const val SEASON_SYNC_NOT_CHECKED_IN_TIME = "Not checked successfully within the chosen interval."

/** P16-33 — Home Assistant's own change time, information only. */
internal fun seasonSyncChangedInHa(at: String): String = "Changed in Home Assistant $at"

/** P16-36 — `NOT_MAINTAINED_HERE`, as the binding's state and as an error. */
internal const val SEASON_SYNC_NOT_MAINTAINED_HERE =
    "This asset is no longer maintained here, so Home Assistant no longer changes its season."

/** P16-37 — `DATE_BEFORE_HISTORY`. */
internal const val SEASON_SYNC_DATE_BEFORE_HISTORY =
    "This phone's date is before the latest season entry, so the season stays as it is until the date catches up."

/** P16-38 — `NOT_MANUAL`. */
internal const val SEASON_SYNC_NOT_MANUAL =
    "This asset's season is no longer started and ended by hand, so Home Assistant cannot change it. Choose Stop " +
        "syncing, then Resume syncing."

/** P16-39. */
internal const val SEASON_SYNC_STOP = "Stop syncing"

/** P16-40. */
internal const val SEASON_SYNC_RESUME = "Resume syncing"

/** P16-41 — a stopped binding's line, on every season mode. */
internal const val SEASON_SYNC_STOPPED = "Syncing is stopped. Home Assistant no longer changes this asset's season."

/**
 * C33(4) — the provenance line for core's [AppliedLine], dated [on]: P16-34/35 from Home Assistant, P16-83/84 forced.
 * The line is chosen by `appliedLineOf` in core, never here.
 */
internal fun appliedLineText(line: AppliedLine, on: String): String = when (line) {
    AppliedLine.STARTED_FROM_HOME_ASSISTANT -> seasonSyncStartedFromHa(on)
    AppliedLine.ENDED_FROM_HOME_ASSISTANT -> seasonSyncEndedFromHa(on)
    AppliedLine.FORCED_IN_SEASON -> seasonSyncForcedInOn(on)
    AppliedLine.FORCED_OUT_OF_SEASON -> seasonSyncForcedOutOn(on)
}

/** P16-34 — a START applied from Home Assistant (source `HOME_ASSISTANT`). */
internal fun seasonSyncStartedFromHa(on: String): String = "Started from Home Assistant on $on"

/** P16-35 — an END applied from Home Assistant. */
internal fun seasonSyncEndedFromHa(on: String): String = "Ended from Home Assistant on $on"

/** P16-83 — a START applied under Force in season (source `FORCED_IN`). */
internal fun seasonSyncForcedInOn(on: String): String = "Forced in season on $on"

/** P16-84 — an END applied under Force out of season (source `FORCED_OUT`). */
internal fun seasonSyncForcedOutOn(on: String): String = "Forced out of season on $on"

/**
 * C27 — the latest error's line, by its kind: an exhaustive `when`, no `else`. [detail] is the stored one: the status
 * for `HTTP_ERROR`, the reported state for `UNSUPPORTED_STATE`, and for `NOT_ON_LOCAL_NETWORK` null (another network:
 * P16-50) or the cause the Wi-Fi name could not be read for (P16-77, then the cause's remedy and its button, C-3).
 */
internal fun seasonSyncErrorNotices(kind: SyncErrorKind, detail: String?, entityId: String): List<Notice> = when (kind) {
    SyncErrorKind.ENDPOINT_REFUSED -> listOf(Notice(HA_ADDRESS_NOT_ALLOWED))
    SyncErrorKind.DENIED -> listOf(Notice(HA_NETWORK_NOT_ALLOWED, NoticeAction.OPEN_APP_SETTINGS))
    SyncErrorKind.UNREACHABLE -> listOf(Notice(HA_COULD_NOT_REACH))
    SyncErrorKind.TIMED_OUT -> listOf(Notice(HA_TOOK_TOO_LONG))
    SyncErrorKind.AUTH_REFUSED -> listOf(Notice(HA_TOKEN_REFUSED))
    SyncErrorKind.ENTITY_NOT_FOUND -> listOf(Notice(haNoSuchEntity(entityId)))
    SyncErrorKind.REDIRECTED -> listOf(Notice(HA_ANSWERED_WITH_REDIRECT))
    SyncErrorKind.HTTP_ERROR -> listOfNotNull(detail?.let { Notice(haAnsweredWithError(it)) })
    SyncErrorKind.MALFORMED -> listOf(Notice(HA_ANSWER_UNREADABLE))
    SyncErrorKind.UNSUPPORTED_STATE -> listOfNotNull(detail?.let { Notice(haNeitherOnNorOff(it, entityId)) })
    SyncErrorKind.NEEDS_TOKEN -> listOf(Notice(HA_ENTER_TOKEN_AGAIN))
    SyncErrorKind.NOT_MAINTAINED_HERE -> listOf(Notice(SEASON_SYNC_NOT_MAINTAINED_HERE))
    SyncErrorKind.NOT_MANUAL -> listOf(Notice(SEASON_SYNC_NOT_MANUAL))
    SyncErrorKind.DATE_BEFORE_HISTORY -> listOf(Notice(SEASON_SYNC_DATE_BEFORE_HISTORY))
    SyncErrorKind.NOT_ON_LOCAL_NETWORK -> when (val cause = UnconfirmedCause.entries.firstOrNull { it.name == detail }) {
        null -> listOf(Notice(HA_NOT_ON_HOME_WIFI))
        else -> listOf(Notice(HA_COULD_NOT_CONFIRM_WIFI), remedyFor(cause))
    }
    SyncErrorKind.NAME_NOT_LOCAL -> listOf(Notice(HA_NAME_NOT_PRIVATE))
    SyncErrorKind.TLS_FAILED -> listOf(Notice(HA_CERTIFICATE_NOT_VERIFIED))
}

/**
 * Resume's refusals (C17), each its ratified sentence. The two that only an entity id can cause never come from Resume
 * — they are the setup sheet's — so they draw nothing here.
 */
internal fun seasonSyncRefusalNotices(reason: SeasonSyncLinkRefusal): List<Notice> = when (reason) {
    SeasonSyncLinkRefusal.NOT_MAINTAINED_HERE -> listOf(Notice(SEASON_SYNC_NOT_MAINTAINED_HERE))
    SeasonSyncLinkRefusal.NO_CONNECTION -> listOf(Notice(HA_NOT_CONNECTED))
    SeasonSyncLinkRefusal.NEEDS_TOKEN -> listOf(Notice(HA_ENTER_TOKEN_AGAIN))
    SeasonSyncLinkRefusal.BAD_ENTITY_ID, SeasonSyncLinkRefusal.ALREADY_LINKED -> emptyList()
}

/** P16-42 — the setup sheet's field. */
internal const val SEASON_SYNC_ENTITY_ID = "Entity ID"

/** P16-43 — the field's helper (AC2, H10). */
internal const val SEASON_SYNC_ENTITY_ID_HELP =
    "An on/off helper that is on while this asset is in season, for example input_boolean.example_heater_in_season. " +
        "Not the appliance's own power switch."

/** P16-44 — the setup sheet on a CALENDAR asset (C16; R16-Q-G: the cadence move, disclosed). */
internal const val SEASON_SYNC_LINK_CALENDAR =
    "Linking this asset to Home Assistant replaces its calendar dates. From today, its season starts and ends when " +
        "Home Assistant says. Maintenance that counts from the start of the season will count from today."

/** P16-45 — the setup sheet on a YEAR_ROUND asset. */
internal const val SEASON_SYNC_LINK_YEAR_ROUND =
    "This asset has no operating season now. Linking it to Home Assistant gives it one: in season from today, then " +
        "started and ended when Home Assistant says. Maintenance that counts from the start of the season will count " +
        "from today."

/** P16-46 — the setup sheet on a MANUAL asset. */
internal const val SEASON_SYNC_LINK_MANUAL =
    "From now on Home Assistant starts and ends this asset's season. Its season history stays as it is."

/** The setup sheet's reconciliation sentence for an asset in [mode], drawn before anything is written (C16, C-4). */
internal fun seasonSyncLinkSentence(mode: SeasonMode): String = when (mode) {
    SeasonMode.CALENDAR -> SEASON_SYNC_LINK_CALENDAR
    SeasonMode.YEAR_ROUND -> SEASON_SYNC_LINK_YEAR_ROUND
    SeasonMode.MANUAL -> SEASON_SYNC_LINK_MANUAL
}

/** P16-47 — the editor's read-only season block (R16-16), and the guard's refusal wherever the phone reaches it. */
internal const val SEASON_SYNC_FOLLOWS_HA =
    "This asset's season follows Home Assistant. Stop syncing on the asset's page to change it here."

/** P16-49 — C16's `BAD_ENTITY_ID`, under the field. */
internal const val SEASON_SYNC_BAD_ENTITY_ID =
    "Enter an entity ID such as input_boolean.example_heater_in_season: lowercase letters, digits and underscores, " +
        "with one dot."
