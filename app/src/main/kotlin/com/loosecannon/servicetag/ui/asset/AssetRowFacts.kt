package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.condition.ConditionHistory
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.ui.health.ConditionView
import com.loosecannon.servicetag.ui.health.inService
import java.time.LocalDate

/**
 * #116 (U-C1) — the three facts an Assets row carries beside its asset. Three sources, kept apart: none is derived from
 * another, and none from health, due dates, readiness or a publication's delivery state.
 *
 * - [haLinked]: an **enabled** binding of either direction — #16's inbound season sync, or #113's or #114's
 *   publication of either kind — exists for the asset. Configuration, not delivery; one mark whatever the kinds.
 * - [condition]: the asset's current condition, while it is maintained here; null when none is recorded.
 * - [inSeason]: the asset is seasonal by its own mode and in season today by its own phase. A year-round asset never
 *   is, and an out-of-season one keeps the list's ratified "Out of season" badge instead.
 */
internal data class RowFacts(val haLinked: Boolean, val condition: ConditionView?, val inSeason: Boolean)

/**
 * The row's facts, pure: no clock, no store. [outOfSeason] is the list's shipped phase rule ([outOfSeasonOn]) over the
 * asset's own activations; [conditionRows] are this asset's condition rows only, in any order; [inboundEnabled] and
 * [publishingEnabled] say whether an enabled binding of each direction names this asset.
 *
 * Seasonality is configured per asset (G22, G27): only the asset's own `seasonMode` and its own phase take part —
 * never its category, type, template or label, and nothing it shares with another asset.
 *
 * The condition is [ConditionHistory.current], never the row written last, built field for field as the health read
 * model's `conditionViewOf` builds it, but with `eventExists = false`: the row never opens the event, so no event is
 * read per row. None while the asset is retired, archived or held, for #113 publishes nothing for an asset not
 * maintained here.
 */
internal fun rowFactsOf(
    asset: Asset,
    held: Boolean,
    outOfSeason: Boolean,
    conditionRows: List<AssetCondition>,
    inboundEnabled: Boolean,
    publishingEnabled: Boolean,
): RowFacts = RowFacts(
    haLinked = inboundEnabled || publishingEnabled,
    condition = if (asset.inService && !held) conditionViewOf(ConditionHistory.of(conditionRows)) else null,
    inSeason = asset.seasonMode != SeasonMode.YEAR_ROUND && !outOfSeason,
)

/** The current row and "since", as the health read model reads them, without the event lookup. */
private fun conditionViewOf(history: ConditionHistory): ConditionView? {
    val current = history.current ?: return null
    val since = history.since ?: return null
    return ConditionView(
        condition = current.condition,
        since = LocalDate.parse(since.occurredOn),
        reason = current.reason,
        occurredOn = LocalDate.parse(current.occurredOn),
        occurredTime = current.occurredTime,
        eventId = current.eventId,
        eventExists = false,
    )
}

/** The assets an enabled #16 binding names: one fold per emission of every binding. */
internal fun enabledInboundIds(bindings: List<SeasonSyncBinding>): Set<AssetId> =
    bindings.mapNotNullTo(HashSet()) { binding -> binding.assetId.takeIf { binding.enabled } }

/** The assets an enabled publish binding of either kind names: one fold per emission of every binding. */
internal fun enabledPublishingIds(bindings: List<PublishBinding>): Set<AssetId> =
    bindings.mapNotNullTo(HashSet()) { binding -> binding.assetId.takeIf { binding.enabled } }
