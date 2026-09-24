package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.schedule.SeasonPhase

/**
 * Changes how an asset's season is decided (spec §3.2, §3.4; master plan §7.2): YEAR_ROUND, a
 * CALENDAR window, or MANUAL. The editor, the API and the asset command's legacy pair all reach the
 * same rules here, so all three refuse the same things:
 *
 * - 422 [SeasonValidation] for the body ([seasonModeProblems]);
 * - 409 [SeasonModeStrandsPolicy] when the change would remove or re-kind the boundary a PRE_SERVICE
 *   schedule counts back from ([strandedBy]), naming those schedules.
 *
 * **A switch into MANUAL** writes exactly one activation dated today, in the same transaction: START
 * for IN_SEASON, END for OUT_OF_SEASON — even when the latest historical row already says the same,
 * because END, END is valid history (inv. 92). Leaving MANUAL keeps every row as unread history. No
 * other mode change writes a row.
 *
 * **A command equal to the stored mode and window writes nothing** — no `updatedAt`, no row, no
 * recompute — because the settings editor sends every command on every save.
 *
 * Every write recomputes the asset's schedules: a season is an input to each of their states, and
 * 1.3's asset edit never rebuilt them. Nothing else is touched — no event, no closure, no schedule
 * column (inv. 86).
 */
class SetSeasonMode(
    private val assets: AssetRepository,
    private val schedules: ScheduleRepository,
    private val activations: SeasonActivationRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val today: Today,
    private val recompute: RecomputeSchedules,
) {
    suspend fun run(assetId: AssetId, cmd: SeasonModeCommand): Asset = uow.write {
        val current = assets.get(assetId) ?: throw NoSuchAsset(assetId)
        val clean = cmd.trimmed()
        val problems = seasonModeProblems(current.seasonMode, clean)
        if (problems.isNotEmpty()) throw SeasonValidation(problems)

        if (current.seasonMode == clean.seasonMode &&
            current.seasonStartMmdd == clean.seasonStartMmdd &&
            current.seasonEndMmdd == clean.seasonEndMmdd
        ) {
            return@write current
        }

        val now = clock.nowMillis()
        val next = current.copy(
            seasonMode = clean.seasonMode,
            seasonStartMmdd = clean.seasonStartMmdd,
            seasonEndMmdd = clean.seasonEndMmdd,
            updatedAt = now,
        )
        val stranded = strandedBy(current, next, schedules.forAsset(assetId))
        if (stranded.isNotEmpty()) throw SeasonModeStrandsPolicy(assetId, stranded)

        assets.upsert(next)
        if (next.seasonMode == SeasonMode.MANUAL && current.seasonMode != SeasonMode.MANUAL) {
            activations.insert(
                SeasonActivation(
                    id = ids.newId(),
                    assetId = assetId,
                    action = if (clean.manualPhase == SeasonPhase.IN_SEASON) SeasonAction.START else SeasonAction.END,
                    occurredOn = today.localDate().toString(),
                    eventId = null,
                    createdAt = now,
                ),
            )
        }
        recompute.forAsset(assetId)
        next
    }
}
