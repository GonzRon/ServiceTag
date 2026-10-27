package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.PublishRefusal
import com.loosecannon.servicetag.core.hapublish.PublishState
import com.loosecannon.servicetag.core.seasonsync.EntityScope
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
import com.loosecannon.servicetag.ui.homeassistant.haNoSuchEntity
import com.loosecannon.servicetag.ui.homeassistant.remedyFor

// #113 (C36, §5) — the publishing blocks' and the setup sheet's sentences, P113-1…32 and P113-44, RATIFIED 2026-10-26,
// each declared once here and read when drawn; the English is in res/values/strings_ha_publishing.xml under each id.
// The #16 transport sentences (C23) are HomeAssistantStrings.kt's, imported and never copied; the Home Assistant
// screen's P113-40/41 live there too. The date kind's own words (P114-30…39, P114-47/48) are PublishDateStrings.kt's,
// behind [publishWordsOf].

/** P113-1 — the status block's title and its switch's label. */
internal val HA_PUBLISH_STATUS_TITLE: String get() = localized(R.string.ha_publish_status_title)

/** P113-2 — the sheet's helper field, and the block's destination label. */
internal val HA_PUBLISH_HELPER_FIELD: String get() = localized(R.string.ha_publish_helper_field)

/** P113-3 — the sheet's browser button and the browser's title. */
internal val HA_PUBLISH_CHOOSE_HELPER: String get() = localized(R.string.ha_publish_choose_helper)

/** P113-4 — the browser's scope line for the status kind. */
internal val HA_PUBLISH_SCOPE_SELECTS: String get() = localized(R.string.ha_publish_scope_selects)

/** P113-5 — the status kind's enable confirm, naming the asset and the exact destination; the mapping is in it. */
internal fun haPublishStatusConfirm(assetName: String, entityId: String): String =
    localized(R.string.ha_publish_status_confirm, assetName, entityId)

/** P113-6 — the confirm's button. */
internal val HA_PUBLISH_CONFIRM_BUTTON: String get() = localized(R.string.ha_publish_confirm_button)

/** P113-7 — `WAITING_FOR_SOURCE`. */
internal val HA_PUBLISH_WAITING_FOR_STATUS: String get() = localized(R.string.ha_publish_waiting_for_status)

/** P113-8 — `PENDING` before any success. */
internal val HA_PUBLISH_NOT_PUBLISHED_YET: String get() = localized(R.string.ha_publish_not_published_yet)

/** P113-9 — `PENDING`, the value being sent. */
internal fun haPublishSending(value: String): String = localized(R.string.ha_publish_sending, value)

/** P113-10 — the last published line: our POST, read back. */
internal fun haPublishPublished(value: String, at: String): String = localized(R.string.ha_publish_published, value, at)

/** P113-11 — the last verified line: Home Assistant's value read equal to ServiceTag's, never "published". */
internal fun haPublishMatches(value: String, at: String): String = localized(R.string.ha_publish_matches, value, at)

/** P113-12. */
internal val HA_PUBLISH_NOW: String get() = localized(R.string.ha_publish_now)

/** P113-13 — the action and its confirm's button. */
internal val HA_PUBLISH_STOP: String get() = localized(R.string.ha_publish_stop)

/** P113-14 — the status kind's stop confirm. */
internal fun haPublishStopStatusConfirm(entityId: String): String =
    localized(R.string.ha_publish_stop_status_confirm, entityId)

/** P113-15 — `OPTIONS_MISMATCH`. */
internal fun haPublishOptionsMismatch(entityId: String): String = localized(R.string.ha_publish_options_mismatch, entityId)

/** P113-16 — `WRONG_DOMAIN` for the status kind. */
internal fun haPublishNotADropdown(entityId: String): String = localized(R.string.ha_publish_not_a_dropdown, entityId)

/** P113-17 — `SERVICE_REFUSED`: the value refused, for the helper. */
internal fun haPublishServiceRefused(value: String, entityId: String): String =
    localized(R.string.ha_publish_service_refused, value, entityId)

/** P113-18 — `WRITE_FORBIDDEN`. */
internal val HA_PUBLISH_WRITE_FORBIDDEN: String get() = localized(R.string.ha_publish_write_forbidden)

/** P113-19 — `OUTCOME_UNKNOWN`. */
internal val HA_PUBLISH_OUTCOME_UNKNOWN: String get() = localized(R.string.ha_publish_outcome_unknown)

/** P113-20 — `REMOTE_DIFFERS`: what Home Assistant shows, and the helper. */
internal fun haPublishRemoteDiffers(shown: String, entityId: String): String =
    localized(R.string.ha_publish_remote_differs, shown, entityId)

/** P113-21 — `COPIED_INSTALLATION`. */
internal val HA_PUBLISH_COPIED_INSTALLATION: String get() = localized(R.string.ha_publish_copied_installation)

/** P113-22 — `TARGET_IN_USE`. */
internal fun haPublishTargetInUse(entityId: String): String = localized(R.string.ha_publish_target_in_use, entityId)

/** P113-23 — `INBOUND_SOURCE` (C26). */
internal fun haPublishInboundSource(entityId: String): String = localized(R.string.ha_publish_inbound_source, entityId)

/** P113-24 — `NOT_MAINTAINED_HERE` for the status kind. */
internal val HA_PUBLISH_STATUS_NOT_MAINTAINED: String get() = localized(R.string.ha_publish_status_not_maintained)

/** P113-25 — Check helper: the block's revalidation and the sheet's fresh read. */
internal val HA_PUBLISH_CHECK_HELPER: String get() = localized(R.string.ha_publish_check_helper)

/** P113-26 — the delivery limit line, while a binding is enabled. */
internal val HA_PUBLISH_DELIVERY_LIMIT: String get() = localized(R.string.ha_publish_delivery_limit)

/** P113-27 — `HTTP_ERROR`, with the status code. */
internal fun haPublishHttpError(status: String): String = localized(R.string.ha_publish_http_error, status)

/** P113-28 — the sheet's fresh read, while it runs. */
internal val HA_PUBLISH_CHECKING: String get() = localized(R.string.ha_publish_checking)

/** P113-29 — `BAD_ENTITY_ID`, or nothing chosen. */
internal val HA_PUBLISH_CHOOSE_HELPER_FIRST: String get() = localized(R.string.ha_publish_choose_helper_first)

/** P113-30 — `NEEDS_VALIDATION` (F-16). */
internal val HA_PUBLISH_NEEDS_VALIDATION: String get() = localized(R.string.ha_publish_needs_validation)

/** P113-31 — the refusal `STALE_READ`. */
internal val HA_PUBLISH_STALE_READ: String get() = localized(R.string.ha_publish_stale_read)

/** P113-32 — the refusal `NOT_MAINTAINED_HERE`, naming the asset. */
internal fun haPublishAssetNotMaintained(assetName: String): String =
    localized(R.string.ha_publish_asset_not_maintained, assetName)

/** P113-44 — the refusal `STALE_PLAN` (C25a): the API's plan; the sheet passes none, so the arm keeps the `when` whole. */
internal val HA_PUBLISH_STALE_PLAN: String get() = localized(R.string.ha_publish_stale_plan)

/** C36 — the helper domain each kind's browser lists (C29b's scopes); never #16's on/off helpers. */
internal fun entityScopeOf(kind: PublishKind): EntityScope = when (kind) {
    PublishKind.EQUIPMENT_STATUS -> EntityScope.INPUT_SELECTS
    PublishKind.SEASON_SERVICE_DATE -> EntityScope.INPUT_DATETIMES
}

/**
 * C36 — the sentences that differ by kind: the block's title, the browser's scope line, the enable and stop confirms
 * (P113-5 / P114-33, P113-14 / P114-35), the domain and shape refusals, and the states only one kind reaches. Every
 * other sentence is the kinds' shared one.
 */
internal interface PublishKindWords {
    val title: String
    val scopeLine: String

    fun confirm(assetName: String, entityId: String): String

    fun stopConfirm(entityId: String): String

    /** `WRONG_DOMAIN`. */
    fun wrongDomain(entityId: String): String

    /** `OPTIONS_MISMATCH` / `NOT_DATE_ONLY`: what this kind's helper must be. */
    fun wrongShape(entityId: String): String

    /** `PublishRefusal.BOUNDARY_IS_TARGET`; null for a kind that names no season-ended helper. */
    fun boundaryIsTarget(entityId: String): String?

    /** The states one kind reaches and the other never does; null for a state this kind does not reach. */
    fun ownStateLine(state: PublishState, entityId: String): String?
}

/** #113 — the equipment status kind's words. */
internal object StatusWords : PublishKindWords {
    override val title: String get() = HA_PUBLISH_STATUS_TITLE
    override val scopeLine: String get() = HA_PUBLISH_SCOPE_SELECTS

    override fun confirm(assetName: String, entityId: String): String = haPublishStatusConfirm(assetName, entityId)

    override fun stopConfirm(entityId: String): String = haPublishStopStatusConfirm(entityId)

    override fun wrongDomain(entityId: String): String = haPublishNotADropdown(entityId)

    override fun wrongShape(entityId: String): String = haPublishOptionsMismatch(entityId)

    /** A status binding names no season-ended helper (C9a), so this refusal never reaches it. */
    override fun boundaryIsTarget(entityId: String): String? = null

    override fun ownStateLine(state: PublishState, entityId: String): String? = when (state) {
        PublishState.WAITING_FOR_SOURCE -> HA_PUBLISH_WAITING_FOR_STATUS
        PublishState.NOT_MAINTAINED_HERE -> HA_PUBLISH_STATUS_NOT_MAINTAINED
        // The date kind's quiet state (P114-39): a status binding is never invalidated.
        PublishState.INVALIDATED_INELIGIBLE -> null
        // The shared states: the block's own lines draw them.
        PublishState.NOT_ENABLED,
        PublishState.COPIED_INSTALLATION,
        PublishState.NEEDS_VALIDATION,
        PublishState.NEEDS_TOKEN,
        PublishState.PENDING,
        PublishState.OUTCOME_UNKNOWN,
        PublishState.ERROR,
        PublishState.PUBLISHED,
        PublishState.MATCHES,
        -> null
    }
}

/**
 * C36 — [kind]'s words: the status kind's here, the date kind's (P114-30…35, 39, 48) in `PublishDateStrings.kt`.
 * Nullable so a kind can be withheld from the phone without touching its callers.
 */
internal fun publishWordsOf(kind: PublishKind): PublishKindWords? = when (kind) {
    PublishKind.EQUIPMENT_STATUS -> StatusWords
    PublishKind.SEASON_SERVICE_DATE -> DateWords
}

/**
 * C23, C36 — a read's or a write's error as drawn, every kind its sentence (an exhaustive `when`, no `else`): the
 * transport kinds take #16's ratified sentences, the outbound kinds P113-15…20 and P113-27. [entityId] is the helper;
 * [detail] the kind's own (the status code, the refused value, #16's network cause); [remoteValue] what the last read
 * saw, which `REMOTE_DIFFERS` names. `HTTP_ERROR` with no code draws nothing, as #16's does.
 */
internal fun publishErrorLines(
    kind: PublishErrorKind,
    detail: String?,
    entityId: String,
    words: PublishKindWords,
    remoteValue: String? = null,
): List<String> = when (kind) {
    PublishErrorKind.ENDPOINT_REFUSED -> listOf(HA_ADDRESS_NOT_ALLOWED)
    PublishErrorKind.DENIED -> listOf(HA_NETWORK_NOT_ALLOWED)
    PublishErrorKind.UNREACHABLE -> listOf(HA_COULD_NOT_REACH)
    PublishErrorKind.TIMED_OUT -> listOf(HA_TOOK_TOO_LONG)
    PublishErrorKind.AUTH_REFUSED -> listOf(HA_TOKEN_REFUSED)
    PublishErrorKind.REDIRECTED -> listOf(HA_ANSWERED_WITH_REDIRECT)
    PublishErrorKind.HTTP_ERROR -> listOfNotNull(detail?.let(::haPublishHttpError))
    PublishErrorKind.MALFORMED -> listOf(HA_ANSWER_UNREADABLE)
    PublishErrorKind.NEEDS_TOKEN -> listOf(HA_ENTER_TOKEN_AGAIN)
    PublishErrorKind.NOT_ON_LOCAL_NETWORK -> {
        // #16's cause, when the gate named one: P16-50, or the could-not-confirm line and its remedy's sentence.
        val cause = UnconfirmedCause.entries.firstOrNull { it.name == detail }
        if (cause == null) listOf(HA_NOT_ON_HOME_WIFI) else listOf(HA_COULD_NOT_CONFIRM_WIFI, remedyFor(cause).text)
    }
    PublishErrorKind.NAME_NOT_LOCAL -> listOf(HA_NAME_NOT_PRIVATE)
    PublishErrorKind.TLS_FAILED -> listOf(HA_CERTIFICATE_NOT_VERIFIED)
    PublishErrorKind.ENTITY_NOT_FOUND -> listOf(haNoSuchEntity(entityId))
    PublishErrorKind.WRITE_FORBIDDEN -> listOf(HA_PUBLISH_WRITE_FORBIDDEN)
    PublishErrorKind.WRONG_DOMAIN -> listOf(words.wrongDomain(entityId))
    PublishErrorKind.OPTIONS_MISMATCH, PublishErrorKind.NOT_DATE_ONLY -> listOf(words.wrongShape(entityId))
    PublishErrorKind.SERVICE_REFUSED -> listOfNotNull(detail?.let { haPublishServiceRefused(it, entityId) })
    PublishErrorKind.OUTCOME_UNKNOWN -> listOf(HA_PUBLISH_OUTCOME_UNKNOWN)
    PublishErrorKind.REMOTE_DIFFERS -> listOfNotNull((detail ?: remoteValue)?.let { haPublishRemoteDiffers(it, entityId) })
    PublishErrorKind.COPIED_INSTALLATION -> listOf(HA_PUBLISH_COPIED_INSTALLATION)
}

/**
 * C25, C36 — a publishing control's refusal as drawn (an exhaustive `when`): [entityId] the helper named, [assetName]
 * the asset's. Null only for `BOUNDARY_IS_TARGET` on a kind with no season-ended helper.
 */
internal fun publishRefusalLine(
    reason: PublishRefusal,
    entityId: String,
    assetName: String,
    words: PublishKindWords,
): String? = when (reason) {
    PublishRefusal.BAD_ENTITY_ID -> HA_PUBLISH_CHOOSE_HELPER_FIRST
    PublishRefusal.WRONG_DOMAIN -> words.wrongDomain(entityId)
    PublishRefusal.OPTIONS_MISMATCH, PublishRefusal.NOT_DATE_ONLY -> words.wrongShape(entityId)
    PublishRefusal.NO_CONNECTION -> HA_NOT_CONNECTED
    PublishRefusal.NOT_MAINTAINED_HERE -> haPublishAssetNotMaintained(assetName)
    PublishRefusal.TARGET_IN_USE -> haPublishTargetInUse(entityId)
    PublishRefusal.INBOUND_SOURCE -> haPublishInboundSource(entityId)
    PublishRefusal.STALE_READ -> HA_PUBLISH_STALE_READ
    PublishRefusal.BOUNDARY_IS_TARGET -> words.boundaryIsTarget(entityId)
    PublishRefusal.STALE_PLAN -> HA_PUBLISH_STALE_PLAN
}
