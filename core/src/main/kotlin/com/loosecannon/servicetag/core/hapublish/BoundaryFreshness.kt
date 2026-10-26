package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.season.HaBoundary
import com.loosecannon.servicetag.core.seasonsync.SyncCadence

/**
 * #114 (C9a) — the last valid read of a date binding's season-ended helper, as the delivery caches it: the date
 * text, the read time and the binding revision it was read at.
 */
data class BoundaryCache(val value: String?, val readAt: Long?, val bindingRevision: Long?)

/** The boundary cache a delivery row carries. */
val PublishDelivery.boundaryCache: BoundaryCache
    get() = BoundaryCache(boundaryValue, boundaryReadAt, boundaryBindingRevision)

/** #114 (C9a) — the shortest freshness window: a week, widened to the connection's cadence when that is longer. */
const val MIN_BOUNDARY_WINDOW_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

/**
 * #114 (C9a, D) — who decides this asset's season end, and whether Home Assistant's cached date stands now. Pure:
 * every caller passes [now] from the shipped clock, so readiness stays clock-free.
 *
 * - A `CALENDAR` or `YEAR_ROUND` asset → [HaBoundary.NotUsed]: its window decides, and Home Assistant's date never
 *   overrides it.
 * - A `MANUAL` asset decides by Home Assistant only with an enabled inbound #16 binding ([inboundEnabled]) **and** a
 *   season-ended helper named on its date [binding]; any other `MANUAL` asset → [HaBoundary.NotUsed] (the phone's
 *   END decides).
 * - Such an asset is [HaBoundary.Fresh] while the [cache] holds a valid date read at the binding's **current**
 *   revision, no longer than max(a week, [cadence]) before [now], and not before the asset's newest activation row
 *   ([newestActivationCreatedAt]: an observation after the read may mean HA stamped a newer date).
 * - Otherwise it is [HaBoundary.Unverified]: the cached date when one is valid, since the last valid read, else
 *   since the binding's validation (its creation when it never validated).
 */
fun haBoundaryOf(
    cache: BoundaryCache?,
    binding: PublishBinding?,
    assetMode: SeasonMode,
    inboundEnabled: Boolean,
    cadence: SyncCadence,
    newestActivationCreatedAt: Long?,
    now: Long,
): HaBoundary {
    val helperBinding = binding?.takeIf { it.kind == PublishKind.SEASON_SERVICE_DATE && it.boundaryEntityId != null }
    val haDecides = when (assetMode) {
        SeasonMode.YEAR_ROUND, SeasonMode.CALENDAR -> false
        SeasonMode.MANUAL -> inboundEnabled && helperBinding != null
    }
    if (!haDecides || helperBinding == null) return HaBoundary.NotUsed

    val cachedOn = cache?.value?.let(::isoDayOrNull)
    val readAt = cache?.readAt?.takeIf { cachedOn != null }
    if (cachedOn != null && readAt != null) {
        val window = maxOf(MIN_BOUNDARY_WINDOW_MILLIS, cadence.hours * 60 * 60 * 1000)
        val fresh = cache.bindingRevision == helperBinding.revision &&
            now - readAt <= window &&
            (newestActivationCreatedAt == null || readAt >= newestActivationCreatedAt)
        if (fresh) return HaBoundary.Fresh(cachedOn, readAt)
    }
    return HaBoundary.Unverified(cachedOn, since = readAt ?: helperBinding.validatedAt ?: helperBinding.createdAt)
}
