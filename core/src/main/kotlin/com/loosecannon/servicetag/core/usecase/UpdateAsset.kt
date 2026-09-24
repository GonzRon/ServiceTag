package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/** The asset this edit or archive was aimed at is no longer there — a stale back stack, usually. */
class NoSuchAsset(id: AssetId) : IllegalArgumentException("no asset ${id.value}")

/**
 * Edits the fields a person types. Identity is not editable: id, `createdAt`, `status`,
 * `retiredOn` and `templateKey` come from the stored row, so an edit can never resurrect an
 * archived asset or un-retire one by accident (R-9 keeps those on [ArchiveAsset] and
 * [RetireAsset], where they are deliberate acts). Everything else goes through [validateAsset],
 * the same gate [CreateAsset] uses.
 *
 * **The legacy pair** (spec §3.2, §9.3; inv. 88, 128). The command's `MM-DD` pair is 1.3's season:
 *
 * - a pair **equal to the stored one** leaves the mode, the window and the break exactly as they are,
 *   which is how a MANUAL asset's null pair round-trips;
 * - a different pair on a **MANUAL** asset is 422 [LegacyWriteCannotRepresent], and nothing is written;
 * - any other different pair becomes CALENDAR(window) or YEAR_ROUND **through the season-mode rules**
 *   ([seasonModeProblems] and the strands rule, [strandedBy]: 409 [SeasonModeStrandsPolicy]), laid
 *   over with the rest of the edit.
 *
 * Everything is validated before anything is written, and the row and the recompute its new season
 * needs commit in one transaction. A 1.4-only field — the break, the health policy — is never reset.
 */
class UpdateAsset(
    private val assets: AssetRepository,
    private val schedules: ScheduleRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
    private val recompute: RecomputeSchedules,
) {
    suspend fun run(id: AssetId, cmd: AssetCommand): Asset = uow.write {
        val all = assets.all()
        val current = all.firstOrNull { it.id == id } ?: throw NoSuchAsset(id)
        val clean = validateAsset(cmd, all, id)
        val edited = current.applying(clean, clock.nowMillis())
        val pairChanged = clean.seasonStartMmdd != current.seasonStartMmdd ||
            clean.seasonEndMmdd != current.seasonEndMmdd
        val saved = if (pairChanged) translated(current, edited, clean) else edited
        assets.upsert(saved)
        if (pairChanged) recompute.forAsset(id)
        saved
    }

    /** A different pair, through the season-mode rules: refused on MANUAL, else CALENDAR or YEAR_ROUND. */
    private suspend fun translated(current: Asset, edited: Asset, clean: AssetCommand): Asset {
        if (current.seasonMode == SeasonMode.MANUAL) throw LegacyWriteCannotRepresent(current.id)
        val mode = SeasonModeCommand(clean.legacyPairMode(), clean.seasonStartMmdd, clean.seasonEndMmdd)
        // A pair `validateAsset` let through always passes here; asked anyway, so the pair can never
        // reach a mode the season-mode command itself would refuse.
        val problems = seasonModeProblems(current.seasonMode, mode)
        if (problems.isNotEmpty()) throw SeasonValidation(problems)
        val next = edited.copy(seasonMode = mode.seasonMode)
        val stranded = strandedBy(current, next, schedules.forAsset(current.id))
        if (stranded.isNotEmpty()) throw SeasonModeStrandsPolicy(current.id, stranded)
        return next
    }
}
