package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonInputs
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.maintainedHere
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.schedule.BoundaryKind
import com.loosecannon.servicetag.core.schedule.SeasonContext
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.NoSuchSchedule
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import com.loosecannon.servicetag.core.usecase.boundaryKindFor
import java.time.LocalDate

/**
 * #125 (U-C1) — one row of a schedule's own season: the asset activation's shape without `eventId`, since a schedule's
 * season writes no journal entry. An immutable fact; the latest by `(occurredOn, createdAt, id)` is the phase.
 */
data class ScheduleSeasonActivation(
    val id: String,
    val scheduleId: ScheduleId,
    val action: SeasonAction,
    /** ISO `YYYY-MM-DD`, the day it was applied (R16-3). */
    val occurredOn: String,
    val createdAt: Long,
)

/**
 * #125 (U-C1) — one schedule's link to one Home Assistant `input_boolean`: #16's [SeasonSyncBinding] with
 * [scheduleId] in place of the asset, the same columns, revision rule, three times and provenance. [mode] is always
 * [SyncMode.FOLLOW] in 1.9.0 (H-3). Device-local: it never travels.
 */
data class ScheduleSeasonBinding(
    val scheduleId: ScheduleId,
    val connectionId: String,
    val entityId: String,
    val mode: SyncMode,
    val enabled: Boolean,
    val revision: Long,
    val observedState: HaSwitchState?,
    val observedChangedAt: String?,
    val lastSuccessAt: Long?,
    val lastAttemptAt: Long?,
    val errorKind: SyncErrorKind?,
    val errorDetail: String?,
    val errorAt: Long?,
    val appliedAction: SeasonAction?,
    val appliedOn: String?,
    val appliedAt: Long?,
    val lastAppliedSource: LastAppliedSource?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * #125 (U-C3) — why a schedule's link was refused before anything was written: a code the phone and the API map to a
 * sentence, never a sentence. Beside #16's [SeasonSyncLinkRefusal], never inside it.
 */
enum class ScheduleSeasonRefusal {
    /** The schedule's asset is archived, retired or held by an open transfer (P16-36). */
    NOT_MAINTAINED_HERE,

    /** No connection is saved on this phone (P16-10). */
    NO_CONNECTION,

    /** The connection's token is not on this phone (P16-11). */
    NEEDS_TOKEN,

    /** The entity id is not an `input_boolean` id under #16's rule (P16-49). */
    BAD_ENTITY_ID,

    /** The schedule already has its one binding. */
    ALREADY_LINKED,

    /** A helper this phone writes (C26, P113-43). */
    OUTBOUND_TARGET,

    /** A group's schedule (inv. 106, P125-6). */
    GROUP_TARGET,

    /** The schedule is archived (P114-19). */
    SCHEDULE_ARCHIVED,

    /** A `PRE_SERVICE` schedule whose own season would have no boundary: its asset has no break (P125-7). */
    STRANDS_PRE_SERVICE,
}

/** #125 (U-C3) — a schedule's link refused for [reason]; nothing was written. The message is the code only. */
class ScheduleSeasonRefused(val reason: ScheduleSeasonRefusal) :
    IllegalStateException("schedule season refused: $reason")

/** #125 (U-C3) — the entity rule of a schedule's link: #16's id rule, and the `input_boolean` domain only (#105). */
fun isScheduleSeasonEntity(entityId: String): Boolean =
    isValidEntityId(entityId) && entityId.substringBefore('.') == EntityScope.INPUT_BOOLEANS.domain

/**
 * #125 (U-C2) — the season an [ScheduleSeasonSource.OWN] schedule of this asset reads: `MANUAL`, no calendar window,
 * **the asset's break** (the break is the equipment's, H-2), and the schedule's own [rows]. So `PRE_SERVICE` counts
 * back only from the break, and an in-service policy waits for a START that is never predicted.
 */
fun Asset.ownSeasonInputs(rows: List<ScheduleSeasonActivation>): SeasonInputs = SeasonInputs(
    mode = SeasonMode.MANUAL,
    seasonStartMmdd = null,
    seasonEndMmdd = null,
    blackoutStartMmdd = blackoutStartMmdd,
    blackoutEndMmdd = blackoutEndMmdd,
    activations = rows.map { row ->
        SeasonActivation(row.id, id, row.action, row.occurredOn, eventId = null, createdAt = row.createdAt)
    },
)

/**
 * #125 (U-C3) — **Follow Home Assistant** for one asset-targeted schedule, in one write, refusals first:
 * [ScheduleSeasonRefusal.GROUP_TARGET] and [ScheduleSeasonRefusal.SCHEDULE_ARCHIVED] (the schedule), then #16's
 * [ScheduleSeasonRefusal.NOT_MAINTAINED_HERE], [ScheduleSeasonRefusal.NO_CONNECTION],
 * [ScheduleSeasonRefusal.NEEDS_TOKEN] and [ScheduleSeasonRefusal.BAD_ENTITY_ID], C26's
 * [ScheduleSeasonRefusal.OUTBOUND_TARGET],
 * [ScheduleSeasonRefusal.ALREADY_LINKED], and last [ScheduleSeasonRefusal.STRANDS_PRE_SERVICE]. Every refusal writes
 * nothing. A `YEAR_ROUND` asset's schedule may link (U-C3).
 *
 * The write: the binding (FOLLOW, enabled, revision 1, no status yet) and the schedule's source `OWN`, then its
 * recompute. No activation row: until the post-link check's first read, which its caller launches, the schedule is
 * `MANUAL` with no row, out of its season. A stranded `OWN` schedule (no binding) links again with its rows kept.
 * After the commit the work is ensured.
 */
class LinkScheduleSeason(
    private val schedules: ScheduleRepository,
    private val assets: AssetRepository,
    private val transfers: TransferRecordRepository,
    private val bindings: ScheduleSeasonBindingRepository,
    private val connections: HaConnectionRepository,
    private val secrets: SecretStore,
    private val recompute: RecomputeSchedules,
    private val scheduler: SeasonSyncScheduler,
    private val uow: UnitOfWork,
    private val clock: Clock,
    private val outbound: OutboundTargets,
) {
    suspend fun run(scheduleId: ScheduleId, entityId: String): ScheduleSeasonBinding {
        val linked = uow.write {
            val schedule = schedules.get(scheduleId) ?: throw NoSuchSchedule(scheduleId)
            val target = schedule.target as? ScheduleTarget.AssetTarget ?: refuse(ScheduleSeasonRefusal.GROUP_TARGET)
            if (schedule.status == ScheduleStatus.ARCHIVED) refuse(ScheduleSeasonRefusal.SCHEDULE_ARCHIVED)
            val asset = assets.get(target.assetId) ?: throw NoSuchAsset(target.assetId)
            if (!asset.maintainedHere(transfers.heldIds())) refuse(ScheduleSeasonRefusal.NOT_MAINTAINED_HERE)
            val connection = connections.get() ?: refuse(ScheduleSeasonRefusal.NO_CONNECTION)
            if (!secrets.has(connection.id)) refuse(ScheduleSeasonRefusal.NEEDS_TOKEN)
            if (!isScheduleSeasonEntity(entityId)) refuse(ScheduleSeasonRefusal.BAD_ENTITY_ID)
            if (outbound.writes(connection.id, entityId)) refuse(ScheduleSeasonRefusal.OUTBOUND_TARGET)
            if (bindings.get(scheduleId) != null) refuse(ScheduleSeasonRefusal.ALREADY_LINKED)
            if (schedule.servicePolicy == ServicePolicy.PRE_SERVICE &&
                asset.boundaryKindFor(ScheduleSeasonSource.OWN) == BoundaryKind.NONE
            ) {
                refuse(ScheduleSeasonRefusal.STRANDS_PRE_SERVICE)
            }
            val now = clock.nowMillis()
            val binding = ScheduleSeasonBinding(
                scheduleId = scheduleId,
                connectionId = connection.id,
                entityId = entityId,
                mode = SyncMode.FOLLOW,
                enabled = true,
                revision = 1,
                observedState = null,
                observedChangedAt = null,
                lastSuccessAt = null,
                lastAttemptAt = null,
                errorKind = null,
                errorDetail = null,
                errorAt = null,
                appliedAction = null,
                appliedOn = null,
                appliedAt = null,
                lastAppliedSource = null,
                createdAt = now,
                updatedAt = now,
            )
            bindings.insert(binding)
            schedules.upsert(schedule.copy(seasonSource = ScheduleSeasonSource.OWN, updatedAt = now))
            recompute.forSchedule(scheduleId)
            binding
        }
        scheduler.ensure()
        return linked
    }

    private fun refuse(reason: ScheduleSeasonRefusal): Nothing = throw ScheduleSeasonRefused(reason)
}

/**
 * #125 (U-C3, constraint 6) — **Use the asset's season**: deletes the binding, if any, and sets the source `ASSET` in
 * one write, then recomputes the schedule, which reads its asset's season from this write on. The schedule's own rows
 * are kept as history, no longer read. Unlinking an `ASSET` schedule writes nothing; a stranded `OWN` schedule (its
 * binding gone with the connection) unlinks too. After a change the work is ensured, which cancels it when nothing is
 * enabled.
 */
class UnlinkScheduleSeason(
    private val schedules: ScheduleRepository,
    private val bindings: ScheduleSeasonBindingRepository,
    private val recompute: RecomputeSchedules,
    private val scheduler: SeasonSyncScheduler,
    private val uow: UnitOfWork,
    private val clock: Clock,
) {
    suspend fun run(scheduleId: ScheduleId): MaintenanceSchedule {
        var changed = false
        val saved = uow.write {
            val schedule = schedules.get(scheduleId) ?: throw NoSuchSchedule(scheduleId)
            when (schedule.seasonSource) {
                ScheduleSeasonSource.ASSET -> schedule
                ScheduleSeasonSource.OWN -> {
                    changed = true
                    unlinkedInTransaction(schedule, bindings).copy(updatedAt = clock.nowMillis()).also {
                        schedules.upsert(it)
                        recompute.forSchedule(scheduleId)
                    }
                }
            }
        }
        if (changed) scheduler.ensure()
        return saved
    }
}

/**
 * #125 (U-C3) — the return to the asset's season inside the caller's write: [schedule]'s binding deleted and the
 * schedule answered with source `ASSET`, for the caller to store (the unlink, and the archive in its own write).
 */
internal suspend fun unlinkedInTransaction(
    schedule: MaintenanceSchedule,
    bindings: ScheduleSeasonBindingRepository,
): MaintenanceSchedule {
    bindings.delete(schedule.id)
    return schedule.copy(seasonSource = ScheduleSeasonSource.ASSET)
}

/** #125 (U-C5) — what one [RecordScheduleSeasonSyncResult.run] came to, as #16's [SeasonSyncRecorded]. */
sealed interface ScheduleSeasonRecorded {
    data object Dropped : ScheduleSeasonRecorded

    data class Recorded(val binding: ScheduleSeasonBinding) : ScheduleSeasonRecorded
}

/**
 * #125 (U-C5) — **the one writer of a schedule binding's status and of a schedule's own season row**, #16's
 * [RecordSeasonSyncResult] over the schedule's rows, in one write:
 *
 * - **the revision guard**: a result whose `readRevision` is not the stored revision, or whose binding is stopped,
 *   writes nothing; the binding update is a compare-and-set;
 * - **status**: a [HaReadOutcome.NoDecision] records its kind and never moves `lastSuccessAt`; an
 *   [HaReadOutcome.Observed] stores the state, HA's change text and the fetch time, and clears the error;
 * - **three times (R16-3)**: a row is dated the day it is applied, never by HA's text;
 * - **the decision** is #16's [desiredPhase] against the schedule's own season today ([ownSeasonInputs]), never the
 *   asset's; a forced mode (never set in 1.9.0) would be re-asserted as #16's;
 * - **lifecycle**: an archived schedule, a group's, or one whose asset is not maintained here takes no decision and
 *   shows [SyncErrorKind.NOT_MAINTAINED_HERE]; today before the latest row shows [SyncErrorKind.DATE_BEFORE_HISTORY].
 *
 * At most one activation row and one binding update per read, then the schedule's recompute; nothing else is
 * written: no asset activation, event, asset column or other schedule (constraint 3).
 */
class RecordScheduleSeasonSyncResult(
    private val bindings: ScheduleSeasonBindingRepository,
    private val schedules: ScheduleRepository,
    private val assets: AssetRepository,
    private val activations: ScheduleSeasonActivationRepository,
    private val transfers: TransferRecordRepository,
    private val recompute: RecomputeSchedules,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val today: Today,
) {
    /**
     * Records [outcome], read for [scheduleId] at the binding's [readRevision]. [startedAt] is when the request was
     * built; [fetchedAt] is the runner's clock when the read returned.
     */
    suspend fun run(
        scheduleId: ScheduleId,
        readRevision: Long,
        startedAt: Long,
        fetchedAt: Long,
        outcome: HaReadOutcome,
    ): ScheduleSeasonRecorded = uow.write {
        val stored = bindings.get(scheduleId) ?: return@write ScheduleSeasonRecorded.Dropped
        if (stored.revision != readRevision || !stored.enabled) return@write ScheduleSeasonRecorded.Dropped
        val schedule = schedules.get(scheduleId) ?: return@write ScheduleSeasonRecorded.Dropped
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
        val decided = if (desired != null) applyIfChanged(schedule, desired, read) else read
        val next = decided.copy(revision = stored.revision + 1, updatedAt = now)
        check(bindings.update(next)) { "the binding of schedule ${scheduleId.value} moved inside its own write" }
        ScheduleSeasonRecorded.Recorded(next)
    }

    private suspend fun applyIfChanged(
        schedule: MaintenanceSchedule,
        desired: SeasonPhase,
        binding: ScheduleSeasonBinding,
    ): ScheduleSeasonBinding {
        val now = clock.nowMillis()
        val target = schedule.target as? ScheduleTarget.AssetTarget
        val asset = target?.let { assets.get(it.assetId) }
        if (schedule.status == ScheduleStatus.ARCHIVED || asset == null || !asset.maintainedHere(transfers.heldIds())) {
            return binding.failing(SyncErrorKind.NOT_MAINTAINED_HERE, null, now)
        }
        when (schedule.seasonSource) {
            ScheduleSeasonSource.OWN -> Unit
            ScheduleSeasonSource.ASSET -> return binding.failing(SyncErrorKind.NOT_MANUAL, null, now)
        }
        val day = today.localDate()
        val rows = activations.forSchedule(schedule.id)
        if (SeasonContext.of(asset.ownSeasonInputs(rows)).phaseAt(day) == desired) return binding
        val latest = rows.maxOfOrNull { LocalDate.parse(it.occurredOn) }
        if (latest != null && day < latest) return binding.failing(SyncErrorKind.DATE_BEFORE_HISTORY, null, now)
        val action = when (desired) {
            SeasonPhase.IN_SEASON -> SeasonAction.START
            SeasonPhase.OUT_OF_SEASON -> SeasonAction.END
        }
        activations.insert(ScheduleSeasonActivation(ids.newId(), schedule.id, action, day.toString(), now))
        recompute.forSchedule(schedule.id)
        return binding.copy(
            appliedAction = action,
            appliedOn = day.toString(),
            appliedAt = now,
            lastAppliedSource = appliedSourceOf(binding.mode),
        )
    }

    private fun ScheduleSeasonBinding.failing(kind: SyncErrorKind, detail: String?, at: Long): ScheduleSeasonBinding =
        copy(errorKind = kind, errorDetail = detail, errorAt = at)
}
