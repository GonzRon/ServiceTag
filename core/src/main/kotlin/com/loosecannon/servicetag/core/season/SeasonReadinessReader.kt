package com.loosecannon.servicetag.core.season

import com.loosecannon.servicetag.core.hapublish.PublishBindingRepository
import com.loosecannon.servicetag.core.hapublish.PublishDeliveryRepository
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.boundaryCache
import com.loosecannon.servicetag.core.hapublish.haBoundaryOf
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncRepository
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import java.time.LocalDate
import java.time.ZoneId

/**
 * #124 (C41) — **the** [OpeningServiceReader]: #114's readiness rule (C9, [seasonReadiness]) for one asset today, and
 * its `serviceOn` when the answer is [Readiness.Ready]. It reads, inside the caller's transaction, exactly what the
 * publishing projector hands C9: the asset, every asset (the tree), every schedule and group, the asset's own events
 * and activations, today from the shipped [Today] and the [zone], and the asset's [HaBoundary] — B1's [haBoundaryOf]
 * over its date binding and that binding's delivery (the boundary cache), its inbound #16 binding, the connection's
 * cadence and the shipped [clock]. It decides nothing itself: which services qualify, under which role and from which
 * cutoff, is C9's alone, so the QUICK election (O-2), Transfer Pack history (O-20) and services dated after today or
 * written before their day (O-16) reach the engine as C9 answers them.
 *
 * The readiness route (`GET /v1/assets/{id}/season-readiness`, C29) reads C9's whole answer through [readiness], so the
 * route and the engine assemble the boundary once, here.
 */
class SeasonReadinessReader(
    private val assets: AssetRepository,
    private val schedules: ScheduleRepository,
    private val groups: GroupRepository,
    private val events: EventRepository,
    private val activations: SeasonActivationRepository,
    private val bindings: PublishBindingRepository,
    private val deliveries: PublishDeliveryRepository,
    private val seasonSync: SeasonSyncRepository,
    private val connections: HaConnectionRepository,
    private val today: Today,
    private val clock: Clock,
    private val zone: () -> ZoneId,
) : OpeningServiceReader {
    override suspend fun openingServiceOn(assetId: AssetId): LocalDate? = when (val answer = readiness(assetId)) {
        null -> null
        is Readiness.Ready -> answer.serviceOn
        is Readiness.NotReady -> null
    }

    /** C9's answer for [assetId] today with its Home Assistant boundary, or null when the asset is not there. */
    suspend fun readiness(assetId: AssetId): Readiness? {
        val asset = assets.get(assetId) ?: return null
        val own = activations.forAsset(assetId)
        val dateBinding = bindings.forAsset(assetId).firstOrNull { it.kind == PublishKind.SEASON_SERVICE_DATE }
        val boundary = haBoundaryOf(
            cache = dateBinding?.let { deliveries.get(it.id)?.boundaryCache },
            binding = dateBinding,
            assetMode = asset.seasonMode,
            inboundEnabled = seasonSync.get(assetId)?.enabled == true,
            // With no connection no #16 binding is enabled, so the cadence decides nothing (as the projector reads it).
            cadence = connections.get()?.cadence ?: SyncCadence.DAILY,
            newestActivationCreatedAt = own.maxOfOrNull { it.createdAt },
            now = clock.nowMillis(),
        )
        return seasonReadiness(
            asset, assets.all(), schedules.all(), groups.all(), events.forAsset(assetId), own, today.localDate(), zone(),
            boundary,
        )
    }
}
