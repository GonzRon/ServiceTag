package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncGuard

/**
 * Records one manual START or END (spec §3.3; master plan §7.2). An activation is an **immutable fact**:
 * this writes exactly one row and **no asset column** — not even `updatedAt` (inv. 126) — and then
 * recomputes the asset's schedules, whose phase it may have changed.
 *
 * The body is checked first, every problem collected into one 422 [SeasonValidation]:
 *
 * - `occurredOn` defaults to today; a value that is not an ISO date is [SeasonProblem.BadDate], and one
 *   after today is [SeasonProblem.SeasonDateOutOfRange];
 * - on a MANUAL asset, a date before the latest row's is out of range too — equal is allowed, and the
 *   later-created row then wins (inv. 91);
 * - an `eventId` must name an event of this asset ([SeasonProblem.ForeignEvent]).
 *
 * Then the state, each a 409: first #16's [SeasonSyncOwnsSeason] while an enabled binding owns the season (C15),
 * then [SeasonNotManual]; a START when the latest row is a START
 * ([SeasonAlreadyStarted]); an END when it is an END, or when there is no row at all, because a MANUAL
 * asset with no history reads OUT_OF_SEASON (plan decision 32; [SeasonAlreadyEnded]).
 *
 * "Latest" is by `(occurredOn, createdAt, id)` — the order the phase itself is read in — and never the
 * first row. Nothing here is reached by a journal event on its own: an event only ever *offers* one
 * ([seasonOfferFor]), and [AcceptSeasonOffer] calls this when the owner accepts (inv. 93).
 */
class RecordSeasonActivation(
    private val assets: AssetRepository,
    private val events: EventRepository,
    private val activations: SeasonActivationRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val today: Today,
    private val recompute: RecomputeSchedules,
    private val seasonSync: SeasonSyncGuard,
) {
    suspend fun run(assetId: AssetId, cmd: ActivationCommand): SeasonActivation = uow.write {
        recordInTransaction(assetId, cmd, guarded = true)
    }

    /**
     * #16 (C12) — the work itself, without opening its own transaction. [FakeUnitOfWork][com.loosecannon.servicetag.core.testing.FakeUnitOfWork]'s
     * `write` is not safely re-entrant (a nested call double-counts commits and can reset the
     * transaction witness before the outer block finishes), so
     * [RecordSeasonSyncResult][com.loosecannon.servicetag.core.seasonsync.RecordSeasonSyncResult] — already inside its
     * own `uow.write` — calls this directly instead of nesting through [run].
     *
     * [guarded] asks [SeasonSyncGuard] after the 422 and before the first 409 (C15): [run] passes `true`; the applier,
     * the binding's own writer, passes `false`.
     */
    internal suspend fun recordInTransaction(assetId: AssetId, cmd: ActivationCommand, guarded: Boolean): SeasonActivation {
        val asset = assets.get(assetId) ?: throw NoSuchAsset(assetId)
        val t = today.localDate()
        val manual = asset.seasonMode == SeasonMode.MANUAL
        // Rows are read only for a MANUAL asset, the one mode that consults them (inv. 90).
        val latest = if (manual) latestOf(activations.forAsset(assetId)) else null

        val problems = mutableListOf<SeasonProblem>()
        val occurredOn = cmd.occurredOn?.trim()?.takeIf { it.isNotEmpty() } ?: t.toString()
        val on = parseDate(occurredOn)
        when {
            on == null -> problems += SeasonProblem.BadDate("occurredOn")
            on > t -> problems += SeasonProblem.SeasonDateOutOfRange
            latest != null && on < (parseDate(latest.occurredOn) ?: on) -> problems += SeasonProblem.SeasonDateOutOfRange
        }
        cmd.eventId?.let { id ->
            if (events.get(id)?.assetId != assetId) problems += SeasonProblem.ForeignEvent(id)
        }
        if (problems.isNotEmpty()) throw SeasonValidation(problems)

        if (guarded) seasonSync.requireNotSynced(assetId)
        if (!manual) throw SeasonNotManual(assetId)
        when (cmd.action) {
            SeasonAction.START -> if (latest?.action == SeasonAction.START) throw SeasonAlreadyStarted(assetId)
            SeasonAction.END -> if (latest == null || latest.action == SeasonAction.END) throw SeasonAlreadyEnded(assetId)
        }

        val row = SeasonActivation(
            id = ids.newId(),
            assetId = assetId,
            action = cmd.action,
            // Parsed above by the strict `YYYY-MM-DD` rule, so the text is already the canonical date.
            occurredOn = occurredOn,
            eventId = cmd.eventId,
            createdAt = clock.nowMillis(),
        )
        activations.insert(row)
        recompute.forAsset(assetId)
        return row
    }
}
