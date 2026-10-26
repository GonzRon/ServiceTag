package com.loosecannon.servicetag.hapublish

import android.util.Log
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncRepository
import com.loosecannon.servicetag.data.room.dao.HaPublishControlRow
import com.loosecannon.servicetag.data.room.dao.HaPublishObligationRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * #113/#114 (A03, C32) — the one call that asks for delivery after a commit: idempotent, cheap, never awaited. The
 * projector's observer depends on it, never on the dispatch behind it; B4b2's `PublishDispatch` implements it.
 */
fun interface DeliveryRequester {
    fun requestDelivery()
}

/**
 * B4b1's in-process [DeliveryRequester]: launches [drain] (the runner's, single flight behind its lock) on [scope] and
 * returns at once. Guarded, because the graph's scope carries no handler. B4b2 replaces it with `PublishDispatch`.
 */
class InProcessDeliveryRequester(
    private val scope: CoroutineScope,
    private val drain: suspend () -> Unit,
) : DeliveryRequester {
    override fun requestDelivery() {
        scope.launch {
            try {
                drain()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Log.w(TAG, "ha publish could not run; it will retry on the next resume, scheduled run, or app start") // l10n-ok
            }
        }
    }

    private companion object {
        const val TAG = "HaPublish"
    }
}

/**
 * #113/#114 (C31, C) — **work arrived**: what the projector observes, and nothing else. The source rows —
 * every condition, asset, schedule and group, the held ids, and the events and activations of each asset an enabled
 * binding names; the bindings' control fields ([controls], `observeControls()`, distinct until changed); the #16
 * bindings' `(assetId, enabled)` and the connection's cadence (C9a: they decide the boundary's authority and its
 * window); and each **new** obligation ([obligations], `observeObligations()`: an `(id, revision)` not seen before).
 *
 * No delivery flow is an input, and no tombstone's state or error: the runner's bookkeeping — a write-ahead, an
 * error, a verified or published record, the boundary cache, `reconcilePending`, a cleared validation, a tombstone's
 * error or state, a deletion — never triggers a projection, so a failure never triggers the next attempt (I28).
 */
class PublishInputs(
    private val conditions: ConditionRepository,
    private val assets: AssetRepository,
    private val schedules: ScheduleRepository,
    private val groups: GroupRepository,
    private val transferRecords: TransferRecordRepository,
    private val events: EventRepository,
    private val activations: SeasonActivationRepository,
    private val controls: Flow<List<HaPublishControlRow>>,
    private val seasonSync: SeasonSyncRepository,
    private val connections: HaConnectionRepository,
    private val obligations: Flow<List<HaPublishObligationRow>>,
) {
    /** One `Unit` per arrival; Room's first answer of each flow counts as one, so a new observer projects once. */
    fun workArrived(): Flow<Unit> = merge(
        conditions.observeAll().changes(),
        assets.observeAll().changes(),
        schedules.observeAll().changes(),
        groups.observeAll().changes(),
        transferRecords.observeHeldIds().changes(),
        controls.changes(),
        seasonSync.observeAll().map { rows -> rows.map { it.assetId to it.enabled } }.changes(),
        connections.observe().map { it?.cadence }.changes(),
        newObligations(),
        boundAssetHistory(),
    )

    /** The events and activations of each asset an enabled binding names, re-subscribed when that set moves. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun boundAssetHistory(): Flow<Unit> =
        controls.map { rows -> rows.filter { it.enabled }.map { AssetId(it.assetId) }.toSet() }
            .distinctUntilChanged()
            .flatMapLatest { bound ->
                merge(
                    *bound.flatMap { id ->
                        listOf(events.observeForAsset(id).changes(), activations.observeForAsset(id).changes())
                    }.toTypedArray(),
                )
            }

    /** A03: only an `(id, revision)` this observer has not seen — a state or error write moves neither. */
    private fun newObligations(): Flow<Unit> = flow {
        val seen = HashSet<HaPublishObligationRow>()
        obligations.collect { rows -> if (seen.addAll(rows)) emit(Unit) }
    }

    private fun Flow<*>.changes(): Flow<Unit> = distinctUntilChanged().map { }
}
