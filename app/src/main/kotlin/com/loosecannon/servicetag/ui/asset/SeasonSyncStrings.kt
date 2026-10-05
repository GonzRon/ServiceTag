package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.seasonsync.AppliedLine
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncLinkRefusal
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.l10n.localized
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
// #102: the words live in res/values/strings_asset_edit.xml (`season_sync_*`), each beside its P16 id; every entry
// here reads them when drawn.

/** P16-22 — the card's action on an asset with no binding while a connection exists. */
internal val SEASON_SYNC_LINK: String get() = localized(R.string.season_sync_link)

/** P16-23…25 — the three-way mode control's choices. */
internal val SEASON_SYNC_FOLLOW: String get() = localized(R.string.season_sync_follow)
internal val SEASON_SYNC_FORCE_IN: String get() = localized(R.string.season_sync_force_in)
internal val SEASON_SYNC_FORCE_OUT: String get() = localized(R.string.season_sync_force_out)

/** The mode control's word for [mode], in the order drawn ([SyncMode.entries]). */
internal fun seasonSyncModeLabel(mode: SyncMode): String = when (mode) {
    SyncMode.FOLLOW -> SEASON_SYNC_FOLLOW
    SyncMode.FORCE_IN -> SEASON_SYNC_FORCE_IN
    SyncMode.FORCE_OUT -> SEASON_SYNC_FORCE_OUT
}

/** P16-26. */
internal val SEASON_SYNC_NOW: String get() = localized(R.string.season_sync_now)

/** P16-27 — the source line in FOLLOW. */
internal fun seasonSyncFollows(entityId: String): String = localized(R.string.season_sync_follows, entityId)

/** P16-28 — the source line in FORCE_IN. */
internal fun seasonSyncForcedIn(entityId: String): String = localized(R.string.season_sync_forced_in, entityId)

/** P16-29 — the source line in FORCE_OUT. */
internal fun seasonSyncForcedOut(entityId: String): String = localized(R.string.season_sync_forced_out, entityId)

/** The source line for [mode]: Home Assistant's entity decides, or the owner's forced season does. */
internal fun seasonSyncSourceLine(mode: SyncMode, entityId: String): String = when (mode) {
    SyncMode.FOLLOW -> seasonSyncFollows(entityId)
    SyncMode.FORCE_IN -> seasonSyncForcedIn(entityId)
    SyncMode.FORCE_OUT -> seasonSyncForcedOut(entityId)
}

/** P16-30 — the last valid observation's fetch time, in the shipped date-and-time display. */
internal fun seasonSyncLastSuccess(at: String): String = localized(R.string.season_sync_last_success, at)

/** P16-31 — no valid observation yet. */
internal val SEASON_SYNC_NO_READING_YET: String get() = localized(R.string.season_sync_no_reading_yet)

/** P16-32 — the stale marker: no success within one cadence. */
internal val SEASON_SYNC_NOT_CHECKED_IN_TIME: String get() = localized(R.string.season_sync_not_checked_in_time)

/** P16-33 — Home Assistant's own change time, information only. */
internal fun seasonSyncChangedInHa(at: String): String = localized(R.string.season_sync_changed_in_ha, at)

/** P16-36 — `NOT_MAINTAINED_HERE`, as the binding's state and as an error. */
internal val SEASON_SYNC_NOT_MAINTAINED_HERE: String get() = localized(R.string.season_sync_not_maintained_here)

/** P16-37 — `DATE_BEFORE_HISTORY`. */
internal val SEASON_SYNC_DATE_BEFORE_HISTORY: String get() = localized(R.string.season_sync_date_before_history)

/** P16-38 — `NOT_MANUAL`. */
internal val SEASON_SYNC_NOT_MANUAL: String get() = localized(R.string.season_sync_not_manual)

/** P16-39. */
internal val SEASON_SYNC_STOP: String get() = localized(R.string.season_sync_stop)

/** P16-40. */
internal val SEASON_SYNC_RESUME: String get() = localized(R.string.season_sync_resume)

/** P16-41 — a stopped binding's line, on every season mode. */
internal val SEASON_SYNC_STOPPED: String get() = localized(R.string.season_sync_stopped)

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
internal fun seasonSyncStartedFromHa(on: String): String = localized(R.string.season_sync_started_from_ha, on)

/** P16-35 — an END applied from Home Assistant. */
internal fun seasonSyncEndedFromHa(on: String): String = localized(R.string.season_sync_ended_from_ha, on)

/** P16-83 — a START applied under Force in season (source `FORCED_IN`). */
internal fun seasonSyncForcedInOn(on: String): String = localized(R.string.season_sync_forced_in_on, on)

/** P16-84 — an END applied under Force out of season (source `FORCED_OUT`). */
internal fun seasonSyncForcedOutOn(on: String): String = localized(R.string.season_sync_forced_out_on, on)

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
    SyncErrorKind.NOT_ON_LOCAL_NETWORK -> {
        val cause = UnconfirmedCause.entries.firstOrNull { it.name == detail }
        if (cause == null) {
            listOf(Notice(HA_NOT_ON_HOME_WIFI))
        } else {
            listOf(Notice(HA_COULD_NOT_CONFIRM_WIFI), remedyFor(cause))
        }
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
internal val SEASON_SYNC_ENTITY_ID: String get() = localized(R.string.season_sync_entity_id)

/** P16-43 — the field's helper (AC2, H10). */
internal val SEASON_SYNC_ENTITY_ID_HELP: String get() = localized(R.string.season_sync_entity_id_help)

/** P16-44 — the setup sheet on a CALENDAR asset (C16; R16-Q-G: the cadence move, disclosed). */
internal val SEASON_SYNC_LINK_CALENDAR: String get() = localized(R.string.season_sync_link_calendar)

/** P16-45 — the setup sheet on a YEAR_ROUND asset. */
internal val SEASON_SYNC_LINK_YEAR_ROUND: String get() = localized(R.string.season_sync_link_year_round)

/** P16-46 — the setup sheet on a MANUAL asset. */
internal val SEASON_SYNC_LINK_MANUAL: String get() = localized(R.string.season_sync_link_manual)

/** The setup sheet's reconciliation sentence for an asset in [mode], drawn before anything is written (C16, C-4). */
internal fun seasonSyncLinkSentence(mode: SeasonMode): String = when (mode) {
    SeasonMode.CALENDAR -> SEASON_SYNC_LINK_CALENDAR
    SeasonMode.YEAR_ROUND -> SEASON_SYNC_LINK_YEAR_ROUND
    SeasonMode.MANUAL -> SEASON_SYNC_LINK_MANUAL
}

/** P16-47 — the editor's read-only season block (R16-16), and the guard's refusal wherever the phone reaches it. */
internal val SEASON_SYNC_FOLLOWS_HA: String get() = localized(R.string.season_sync_follows_ha)

/** P16-49 — C16's `BAD_ENTITY_ID`, under the field. */
internal val SEASON_SYNC_BAD_ENTITY_ID: String get() = localized(R.string.season_sync_bad_entity_id)
