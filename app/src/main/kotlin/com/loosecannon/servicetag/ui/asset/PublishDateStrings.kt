package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.hapublish.DecommissionState
import com.loosecannon.servicetag.core.hapublish.DecommissionTombstone
import com.loosecannon.servicetag.core.hapublish.INVALID_SERVICE_DATE
import com.loosecannon.servicetag.core.hapublish.PublishState
import com.loosecannon.servicetag.l10n.localized

// #114 (C36, §5) — the Season Start date block's and the setup sheet's own sentences, P114-30…39, P114-47 and P114-48,
// RATIFIED 2026-10-26, each declared once here and read when drawn; the English is in
// res/values/strings_ha_publishing_date.xml under each id. Every sentence naming the invalid date takes it from
// INVALID_SERVICE_DATE (C1: declared once), so the English reads verbatim. The sentences both kinds share are
// PublishStrings.kt's.

/** P114-30 — the date block's title and its switch's label. */
internal val HA_PUBLISH_DATE_TITLE: String get() = localized(R.string.ha_publish_date_title)

/** P114-31 — the browser's scope line for the date kind. */
internal val HA_PUBLISH_SCOPE_DATETIMES: String get() = localized(R.string.ha_publish_scope_datetimes)

/** P114-32 — `NOT_DATE_ONLY`. */
internal fun haPublishNotDateOnly(entityId: String): String = localized(R.string.ha_publish_date_not_date_only, entityId)

/** P114-33 — the date kind's enable confirm, naming the asset and the exact destination; the invalid date's meaning. */
internal fun haPublishDateConfirm(assetName: String, entityId: String): String =
    localized(R.string.ha_publish_date_confirm, assetName, entityId, INVALID_SERVICE_DATE)

/** P114-34 — `WRONG_DOMAIN` for the date kind. */
internal fun haPublishNotADateHelper(entityId: String): String =
    localized(R.string.ha_publish_date_not_a_date_helper, entityId)

/** P114-35 — the date kind's stop confirm. */
internal fun haPublishStopDateConfirm(entityId: String): String =
    localized(R.string.ha_publish_stop_date_confirm, entityId, INVALID_SERVICE_DATE)

/** P114-36 — a PENDING obligation, or an enabled date binding's `1970-01-01` not yet read back (C34, F-16). */
internal fun haPublishNotYetCleared(entityId: String): String =
    localized(R.string.ha_publish_date_not_yet_cleared, entityId, INVALID_SERVICE_DATE)

/** P114-37 — an UNDELIVERABLE obligation, or an enabled date binding whose error needs the owner (C34, F-16). */
internal fun haPublishCannotReach(entityId: String): String =
    localized(R.string.ha_publish_date_cannot_reach, entityId, INVALID_SERVICE_DATE)

/** P114-38 — the owner's own word that an UNDELIVERABLE helper is cleared: the only way that obligation is let go. */
internal val HA_PUBLISH_CLEARED_BY_HAND: String get() = localized(R.string.ha_publish_date_cleared_by_hand)

/** P114-39 — `INVALIDATED_INELIGIBLE`: `1970-01-01` read back, and nothing more sent while the asset is away. */
internal fun haPublishDateCleared(entityId: String): String =
    localized(R.string.ha_publish_date_cleared, entityId, INVALID_SERVICE_DATE)

/** P114-47 — the sheet's season-ended helper field (C9a). */
internal val HA_PUBLISH_BOUNDARY_FIELD: String get() = localized(R.string.ha_publish_boundary_field)

/** P114-48 — `PublishRefusal.BOUNDARY_IS_TARGET`, naming the season-ended helper as entered. */
internal fun haPublishBoundaryIsTarget(entityId: String): String =
    localized(R.string.ha_publish_boundary_is_target, entityId)

/** #114 — the Season Start date kind's words (C36). */
internal object DateWords : PublishKindWords {
    override val title: String get() = HA_PUBLISH_DATE_TITLE
    override val scopeLine: String get() = HA_PUBLISH_SCOPE_DATETIMES

    override fun confirm(assetName: String, entityId: String): String = haPublishDateConfirm(assetName, entityId)

    override fun stopConfirm(entityId: String): String = haPublishStopDateConfirm(entityId)

    override fun wrongDomain(entityId: String): String = haPublishNotADateHelper(entityId)

    override fun wrongShape(entityId: String): String = haPublishNotDateOnly(entityId)

    override fun boundaryIsTarget(entityId: String): String = haPublishBoundaryIsTarget(entityId)

    override fun ownStateLine(state: PublishState, entityId: String): String? = when (state) {
        PublishState.INVALIDATED_INELIGIBLE -> haPublishDateCleared(entityId)
        // The status kind's states: a date binding always has a value (`1970-01-01` while not ready, I5), and an
        // asset that leaves is invalidated, never left at its last date (C15).
        PublishState.WAITING_FOR_SOURCE, PublishState.NOT_MAINTAINED_HERE -> null
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
 * #114 (C34) — one obligation as drawn, on its asset's date block or, when its asset is gone, on the Home Assistant
 * screen: [text] is P114-36 while ServiceTag still tries, P114-37 once it cannot; only the latter offers P114-38
 * ([dismissible]), the owner's acknowledgement by the tombstone's [id].
 */
internal data class PublishTombstoneLine(val id: String, val text: String, val dismissible: Boolean)

/** C34 — [tombstone]'s line, by its state (exhaustive). */
internal fun tombstoneLineOf(tombstone: DecommissionTombstone): PublishTombstoneLine = when (tombstone.state) {
    DecommissionState.PENDING -> PublishTombstoneLine(tombstone.id, haPublishNotYetCleared(tombstone.entityId), dismissible = false)
    DecommissionState.UNDELIVERABLE ->
        PublishTombstoneLine(tombstone.id, haPublishCannotReach(tombstone.entityId), dismissible = true)
}

/** C34 — every obligation in the order the API lists them: by when it was owed, then by its id. */
internal fun tombstoneLinesOf(tombstones: List<DecommissionTombstone>): List<PublishTombstoneLine> =
    tombstones.sortedWith(compareBy({ it.requestedAt }, { it.id })).map(::tombstoneLineOf)
