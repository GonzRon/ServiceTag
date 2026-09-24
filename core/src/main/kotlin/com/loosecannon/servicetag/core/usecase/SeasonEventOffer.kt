package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.schedule.SeasonContext
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import java.time.LocalDate

/**
 * The season event offer's predicate (spec §3.3; master plan §7.2; inv. 93): after a `SEASON_START`
 * event, START; after a `SEASON_END` event, END — but only on a MANUAL asset whose phase on [today] is
 * the opposite, so the offer is never one that would be refused. Anything else answers null.
 *
 * **Pure, and an offer only.** A journal event never changes a phase by itself: nothing is written
 * unless the owner accepts, through [AcceptSeasonOffer].
 */
fun seasonOfferFor(asset: Asset, activations: List<SeasonActivation>, event: AssetEvent, today: LocalDate): SeasonAction? {
    if (asset.seasonMode != SeasonMode.MANUAL || event.assetId != asset.id) return null
    val phase = SeasonContext.of(asset.seasonInputs(activations)).phaseAt(today)
    return when (event.kind) {
        EventKind.SEASON_START -> SeasonAction.START.takeIf { phase == SeasonPhase.OUT_OF_SEASON }
        EventKind.SEASON_END -> SeasonAction.END.takeIf { phase == SeasonPhase.IN_SEASON }
        else -> null
    }
}

/**
 * Accepting the season offer (plan decision 51): one activation through [RecordSeasonActivation],
 * dated on the event's day **clamped** to `[the latest row's date, today]` — so a backdated event can
 * never slip in before history already recorded, nor a future one land ahead of today — with the
 * event linked by `eventId`. Every refusal is [RecordSeasonActivation]'s own.
 */
class AcceptSeasonOffer(
    private val activations: SeasonActivationRepository,
    private val record: RecordSeasonActivation,
    private val today: Today,
) {
    suspend fun run(assetId: AssetId, event: AssetEvent, action: SeasonAction): SeasonActivation {
        val t = today.localDate()
        val latest = latestOf(activations.forAsset(assetId))?.let { parseDate(it.occurredOn) }
        val eventOn = parseDate(event.occurredOn) ?: t
        val upToToday = if (eventOn > t) t else eventOn
        val clamped = if (latest != null && upToToday < latest) latest else upToToday
        return record.run(assetId, ActivationCommand(action = action, occurredOn = clamped.toString(), eventId = event.id))
    }
}
