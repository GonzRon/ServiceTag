package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Sets or clears an asset's maintenance break (spec §4.4; master plan §7.2), never together with the
 * season. It refuses:
 *
 * - 422 [SeasonValidation]: one bound without the other, a bound that is not a real `MM-DD`, or a
 *   break that leaves some common or leap year with no allowed day ([breakProblems]);
 * - 409 [BreakStrandsPolicy] when the change would remove or re-kind the boundary a PRE_SERVICE
 *   schedule counts back from ([strandedBy]) — on a YEAR_ROUND or MANUAL asset the break's start *is*
 *   that boundary (O-3). On a CALENDAR asset the season's start is, so its break may change freely.
 *
 * The break is only ever what the command says: nothing prefills or defaults it (inv. 121). An
 * unchanged pair writes nothing. A change recomputes the asset's schedules and touches no event,
 * closure or schedule column (inv. 86).
 */
class SetMaintenanceBreak(
    private val assets: AssetRepository,
    private val schedules: ScheduleRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
    private val recompute: RecomputeSchedules,
) {
    suspend fun run(assetId: AssetId, cmd: BreakCommand): Asset = uow.write {
        val current = assets.get(assetId) ?: throw NoSuchAsset(assetId)
        val clean = cmd.trimmed()
        val problems = breakProblems(clean)
        if (problems.isNotEmpty()) throw SeasonValidation(problems)

        if (current.blackoutStartMmdd == clean.blackoutStartMmdd && current.blackoutEndMmdd == clean.blackoutEndMmdd) {
            return@write current
        }

        val next = current.copy(
            blackoutStartMmdd = clean.blackoutStartMmdd,
            blackoutEndMmdd = clean.blackoutEndMmdd,
            updatedAt = clock.nowMillis(),
        )
        val stranded = strandedBy(current, next, schedules.forAsset(assetId))
        if (stranded.isNotEmpty()) throw BreakStrandsPolicy(assetId, stranded)

        assets.upsert(next)
        recompute.forAsset(assetId)
        next
    }
}
