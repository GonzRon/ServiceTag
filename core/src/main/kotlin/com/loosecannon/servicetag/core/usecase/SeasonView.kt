package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.schedule.SeasonContext
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import java.time.LocalDate

/**
 * One asset's season and break as every season surface reads it (master plan §7.2): the API's season
 * response and asset detail's Season section read this and nothing else, so the two cannot disagree
 * about a phase or a date.
 *
 * [phase], [nextBoundaryOn] and [inBreak] are [SeasonContext]'s answers for [computedForOn] — the
 * engine's own predicate, so the view and the schedules agree. [nextBoundaryOn] (plan decision 9) is
 * the day the current CALENDAR span ends when in season, the next start when out of it, and null for
 * YEAR_ROUND and for MANUAL, whose next boundary is never predicted (Q-6).
 *
 * [activations] is the asset's whole manual history ordered `(occurredOn, createdAt, id)`. The phase
 * reads it only in MANUAL mode; in any other mode it is the unread history a switch out of MANUAL
 * left behind (spec §3.2).
 */
data class SeasonView(
    val seasonMode: SeasonMode,
    val seasonStartMmdd: String?,
    val seasonEndMmdd: String?,
    val phase: SeasonPhase,
    val nextBoundaryOn: LocalDate?,
    val blackoutStartMmdd: String?,
    val blackoutEndMmdd: String?,
    val inBreak: Boolean,
    val activations: List<SeasonActivation>,
    val computedForOn: LocalDate,
) {
    companion object {
        /** Pure: the view of [asset] with its [activations] on [today]. */
        fun of(asset: Asset, activations: List<SeasonActivation>, today: LocalDate): SeasonView {
            val context = SeasonContext.of(asset.seasonInputs(activations))
            val phase = context.phaseAt(today)
            return SeasonView(
                seasonMode = asset.seasonMode,
                seasonStartMmdd = asset.seasonStartMmdd,
                seasonEndMmdd = asset.seasonEndMmdd,
                phase = phase,
                nextBoundaryOn = when (asset.seasonMode) {
                    SeasonMode.CALENDAR ->
                        if (phase == SeasonPhase.IN_SEASON) context.seasonEndAt(today) else context.cycleStartAt(today)
                    SeasonMode.YEAR_ROUND, SeasonMode.MANUAL -> null
                },
                blackoutStartMmdd = asset.blackoutStartMmdd,
                blackoutEndMmdd = asset.blackoutEndMmdd,
                inBreak = context.inBreak(today),
                activations = activations.sortedWith(ACTIVATION_ORDER),
                computedForOn = today,
            )
        }
    }
}

/** The [SeasonView] of one asset today. A read: it writes nothing. */
class GetAssetSeason(
    private val assets: AssetRepository,
    private val activations: SeasonActivationRepository,
    private val uow: UnitOfWork,
    private val today: Today,
) {
    suspend fun run(assetId: AssetId): SeasonView = uow.read {
        val asset = assets.get(assetId) ?: throw NoSuchAsset(assetId)
        SeasonView.of(asset, activations.forAsset(assetId), today.localDate())
    }
}
