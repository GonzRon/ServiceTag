package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.core.condition.ConditionHistory
import com.loosecannon.servicetag.core.hapublish.DecommissionRepository
import com.loosecannon.servicetag.core.hapublish.DecommissionState
import com.loosecannon.servicetag.core.hapublish.oweInvalidation
import com.loosecannon.servicetag.core.hapublish.DeliverySteps
import com.loosecannon.servicetag.core.hapublish.DeliveryTrigger
import com.loosecannon.servicetag.core.hapublish.Desired
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishBindingRepository
import com.loosecannon.servicetag.core.hapublish.PublishDeliveryRepository
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.boundaryCache
import com.loosecannon.servicetag.core.hapublish.desiredValueOf
import com.loosecannon.servicetag.core.hapublish.haBoundaryOf
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.maintainedHere
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.season.Readiness
import com.loosecannon.servicetag.core.season.seasonReadiness
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncRepository
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/**
 * #113/#114 (C31, A02) — **the projector**: what each enabled binding should hold now, written as its delivery's
 * desired value. One `uow.write` and no HTTP: it reads the bindings and their deliveries (the desired revision and the
 * boundary cache), the connection's cadence, the #16 bindings, the assets and the held ids, and each bound asset's
 * conditions, schedules, groups, events and activations; runs C15 (and C9 with C9a's `haBoundaryOf`, `now` from
 * [clock], the zone from [zone]) per binding, purely; and, for each binding whose value **differs** from the stored
 * one, writes it through `writeDesired` — a compare-and-set on the binding revision and on the desired revision read
 * in this same transaction, which clears the error and resets the attempt count. Room serialises write transactions,
 * so a projection's read and its write see one state (I20).
 *
 * A binding whose asset row is gone is retired here (C34, R-N2's soft link): deleted with its delivery; an **enabled**
 * date binding first leaves a PENDING tombstone owing `1970-01-01` to its helper (F-25: the binding's installation),
 * unless an enabled binding claims that helper (I19); a status binding (F-10) and a disabled one (F-9, R-1) owe
 * nothing. Idempotent: a second call over the same database writes nothing (I18).
 *
 * Under the runner's lock as its [DesiredStateReconciler]: it never calls the runner or asks for delivery.
 */
class PublishProjector(
    private val bindings: PublishBindingRepository,
    private val deliveries: PublishDeliveryRepository,
    private val decommissions: DecommissionRepository,
    private val connections: HaConnectionRepository,
    private val seasonSync: SeasonSyncRepository,
    private val assets: AssetRepository,
    private val transfers: TransferRecordRepository,
    private val conditions: ConditionRepository,
    private val schedules: ScheduleRepository,
    private val groups: GroupRepository,
    private val events: EventRepository,
    private val activations: SeasonActivationRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val zone: () -> ZoneId,
) : DesiredStateReconciler {

    /** Answers whether any desired value moved or any binding was retired. */
    override suspend fun reconcileDesired(today: LocalDate): Boolean = database {
        val zone = zone()
        uow.write {
            val all = bindings.all()
            var changed = false
            val present = all.map { it.assetId }.distinct().associateWith { assets.get(it) }
            for (binding in all) {
                if (present[binding.assetId] == null) {
                    retire(binding)
                    changed = true
                }
            }
            val enabled = all.filter { it.enabled && present[it.assetId] != null }
            if (enabled.isEmpty()) return@write changed

            val held = transfers.heldIds()
            val connection = connections.get()
            val now = clock.nowMillis()
            val everyAsset = assets.all()
            val everySchedule = schedules.all()
            val everyGroup = groups.all()
            for (binding in enabled) {
                val asset = checkNotNull(present[binding.assetId])
                val delivery = deliveries.get(binding.id) ?: continue
                val desired = desiredValueOf(
                    binding,
                    asset,
                    asset.maintainedHere(held),
                    ConditionHistory.of(conditions.forAsset(asset.id)),
                    readinessOf(asset, all, everyAsset, everySchedule, everyGroup, connection?.cadence, today, zone, now),
                )
                val value = when (desired) {
                    is Desired.Send -> desired.value
                    is Desired.Nothing -> null
                }
                if (value != delivery.desiredValue &&
                    deliveries.writeDesired(binding.id, binding.revision, delivery.desiredRevision, value, now)
                ) {
                    changed = true
                }
            }
            changed
        }
    }

    /**
     * A03 — the delivery-work signal, apart from the projection's answer: an enabled binding C17 would select on a
     * change (a generation not verified, `needsDelivery`, an unknown outcome, `reconcilePending`), or any PENDING
     * tombstone.
     */
    suspend fun deliveryWorkPending(): Boolean = database {
        uow.read {
            bindings.all().any { binding ->
                deliveries.get(binding.id)?.let { DeliverySteps.selects(binding, it, DeliveryTrigger.CHANGE) } == true
            } || decommissions.all().any { it.state == DecommissionState.PENDING }
        }
    }

    /** C9 for [asset] with its date binding's boundary (C9a), whichever kind asks: the status kind never reads it. */
    private suspend fun readinessOf(
        asset: Asset,
        all: List<PublishBinding>,
        everyAsset: List<Asset>,
        everySchedule: List<MaintenanceSchedule>,
        everyGroup: List<MaintenanceGroup>,
        cadence: SyncCadence?,
        today: LocalDate,
        zone: ZoneId,
        now: Long,
    ): Readiness {
        val own = activations.forAsset(asset.id)
        val dateBinding = all.firstOrNull { it.assetId == asset.id && it.kind == PublishKind.SEASON_SERVICE_DATE }
        val boundary = haBoundaryOf(
            cache = dateBinding?.let { deliveries.get(it.id)?.boundaryCache },
            binding = dateBinding,
            assetMode = asset.seasonMode,
            inboundEnabled = seasonSync.get(asset.id)?.enabled == true,
            // With no connection no #16 binding is enabled, so the cadence decides nothing (as the card reads it).
            cadence = cadence ?: SyncCadence.DAILY,
            newestActivationCreatedAt = own.maxOfOrNull { it.createdAt },
            now = now,
        )
        return seasonReadiness(asset, everyAsset, everySchedule, everyGroup, events.forAsset(asset.id), own, today, zone, boundary)
    }

    /**
     * C34 for a binding whose asset row is gone: the row deleted, and in the same write an enabled one's obligation
     * owed by core's [oweInvalidation] (the date kind only, I19).
     */
    private suspend fun retire(binding: PublishBinding) {
        bindings.delete(binding.id)
        if (binding.enabled) oweInvalidation(binding, bindings, decommissions, ids, clock.nowMillis())
    }
}

/** A Room failure of a projection is the runner's [PublishDatabaseFailed] (C30), so the worker retries it. */
private suspend fun <T> database(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: PublishDatabaseFailed) {
    throw e
} catch (e: Exception) {
    throw PublishDatabaseFailed(e)
}
