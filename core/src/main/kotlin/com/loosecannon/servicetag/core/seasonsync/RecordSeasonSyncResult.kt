package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.maintainedHere
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.schedule.SeasonContext
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.transfer.AssetTransferredOut
import com.loosecannon.servicetag.core.usecase.ActivationCommand
import com.loosecannon.servicetag.core.usecase.RecordSeasonActivation
import com.loosecannon.servicetag.core.usecase.SeasonAlreadyEnded
import com.loosecannon.servicetag.core.usecase.SeasonAlreadyStarted
import com.loosecannon.servicetag.core.usecase.SeasonNotManual
import com.loosecannon.servicetag.core.usecase.SeasonProblem
import com.loosecannon.servicetag.core.usecase.SeasonValidation

/**
 * #16 (C13) — what one [RecordSeasonSyncResult.run] came to. [Dropped]: nothing was written, because the binding
 * is gone, stopped or edited since the read was built, or its asset is gone. [Recorded]: the binding was written
 * once, as [Recorded.binding], with at most one season row beside it.
 */
sealed interface SeasonSyncRecorded {
    data object Dropped : SeasonSyncRecorded

    data class Recorded(val binding: SeasonSyncBinding) : SeasonSyncRecorded
}

/**
 * #16 (C13, C14) — **the one writer of a binding's status and the one Home Assistant path to a season row.** Given
 * one fresh read, it decides inside **one** write whether the season the binding wants differs from the asset's
 * season today, read from the activation rows in the same transaction (R16-2), and records a START or END through
 * [RecordSeasonActivation]'s own body, never its `run` (C12: the transaction is not re-entrant).
 *
 * - **The revision guard (AC6, AC8).** A result whose `readRevision` is not the stored revision, or whose binding is
 *   stopped, writes nothing at all. Every write to a binding bumps its revision by one, so of two overlapping
 *   results the second is dropped; the binding update is a compare-and-set, and a refusal throws inside the write
 *   so a season row written with it rolls back.
 * - **Status (AC5).** A [HaReadOutcome.NoDecision] records its kind and never moves `lastSuccessAt`; an
 *   [HaReadOutcome.Observed] stores the state, HA's change text and the fetch time, and clears the error, an
 *   unchanged answer included.
 * - **Three times (R16-3).** HA's change text is information only; the fetch time is `lastSuccessAt`; the application
 *   time is `appliedAt`, and a row is dated the day it is applied, never by HA's text.
 * - **A forced phase (R16-15, C-5)** is re-asserted on every run, whatever HA answered: at most one row.
 * - **Lifecycle (R16-8, R16-18).** An asset that is not maintained here takes no decision and shows
 *   [SyncErrorKind.NOT_MAINTAINED_HERE]; once it is again, the next run applies the current state only.
 *
 * It writes at most one activation row (through the body), one binding update and the body's recompute, and nothing
 * else: no event, closure, condition, case, schedule or asset column (C14).
 */
class RecordSeasonSyncResult(
    private val bindings: SeasonSyncRepository,
    private val assets: AssetRepository,
    private val activations: SeasonActivationRepository,
    private val transfers: TransferRecordRepository,
    private val record: RecordSeasonActivation,
    private val uow: UnitOfWork,
    private val clock: Clock,
    private val today: Today,
) {
    /**
     * Records [outcome], read for [assetId] at the binding's [readRevision]. [startedAt] is when the request was
     * built; [fetchedAt] is the runner's clock when the read returned, the reader's time and not this write's.
     */
    suspend fun run(
        assetId: AssetId,
        readRevision: Long,
        startedAt: Long,
        fetchedAt: Long,
        outcome: HaReadOutcome,
    ): SeasonSyncRecorded = uow.write {
        val stored = bindings.get(assetId) ?: return@write SeasonSyncRecorded.Dropped
        if (stored.revision != readRevision || !stored.enabled) return@write SeasonSyncRecorded.Dropped
        val asset = assets.get(assetId) ?: return@write SeasonSyncRecorded.Dropped
        val now = clock.nowMillis()
        val attempted = stored.copy(lastAttemptAt = startedAt)
        val read = when (outcome) {
            is HaReadOutcome.NoDecision -> attempted.failing(outcome.kind, outcome.detail, now)
            is HaReadOutcome.Observed -> attempted.copy(
                observedState = outcome.state,
                observedChangedAt = outcome.haLastChanged,
                lastSuccessAt = fetchedAt,
                errorKind = null,
                errorDetail = null,
                errorAt = null,
            )
        }
        val desired = desiredPhase(stored.mode, outcome)
        val decided = if (desired != null) applyIfChanged(asset, desired, read) else read
        val next = decided.copy(revision = stored.revision + 1, updatedAt = now)
        check(bindings.update(next)) { "the binding of ${assetId.value} moved inside its own write" }
        SeasonSyncRecorded.Recorded(next)
    }

    /**
     * Brings [asset]'s season today to [desired] inside the caller's write, and answers [binding] with what that
     * came to: unchanged when the season already agrees or a shipped refusal says it is already applied; with an
     * error when the asset is not maintained here, not MANUAL, or today is before its latest row; with the
     * provenance when one row was written. The caller writes the binding.
     */
    internal suspend fun applyIfChanged(asset: Asset, desired: SeasonPhase, binding: SeasonSyncBinding): SeasonSyncBinding {
        val now = clock.nowMillis()
        if (!asset.maintainedHere(transfers.heldIds())) {
            return binding.failing(SyncErrorKind.NOT_MAINTAINED_HERE, null, now)
        }
        if (asset.seasonMode != SeasonMode.MANUAL) return binding.failing(SyncErrorKind.NOT_MANUAL, null, now)
        val day = today.localDate()
        val phase = SeasonContext.of(asset.seasonInputs(activations.forAsset(asset.id))).phaseAt(day)
        if (phase == desired) return binding
        val action = when (desired) {
            SeasonPhase.IN_SEASON -> SeasonAction.START
            SeasonPhase.OUT_OF_SEASON -> SeasonAction.END
        }
        return try {
            record.recordInTransaction(asset.id, ActivationCommand(action, occurredOn = day.toString()), guarded = false)
            binding.copy(appliedAction = action, appliedOn = day.toString(), appliedAt = now)
        } catch (e: SeasonAlreadyStarted) {
            binding
        } catch (e: SeasonAlreadyEnded) {
            binding
        } catch (e: SeasonValidation) {
            if (SeasonProblem.SeasonDateOutOfRange !in e.problems) throw e
            binding.failing(SyncErrorKind.DATE_BEFORE_HISTORY, null, now)
        } catch (e: SeasonNotManual) {
            binding.failing(SyncErrorKind.NOT_MANUAL, null, now)
        } catch (e: AssetTransferredOut) {
            binding.failing(SyncErrorKind.NOT_MAINTAINED_HERE, null, now)
        }
    }

    private fun SeasonSyncBinding.failing(kind: SyncErrorKind, detail: String?, at: Long): SeasonSyncBinding =
        copy(errorKind = kind, errorDetail = detail, errorAt = at)
}
