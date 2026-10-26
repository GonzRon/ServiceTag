package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.LastAppliedSource
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonActivation
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonActivationRepository
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonBinding
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonBindingRepository
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.data.room.dao.ScheduleSeasonActivationDao
import com.loosecannon.servicetag.data.room.dao.ScheduleSeasonSyncBindingDao
import com.loosecannon.servicetag.data.room.entities.ScheduleSeasonActivationEntity
import com.loosecannon.servicetag.data.room.entities.ScheduleSeasonSyncBindingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * #125 (U-C1) — the Room adapter for a schedule's own season rows: insert and query only, as the port is. Thin, as every
 * adapter here is; the rules live in the commands and the applier that write them.
 */
class RoomScheduleSeasonActivationRepository(
    private val dao: ScheduleSeasonActivationDao,
) : ScheduleSeasonActivationRepository {

    override suspend fun insert(row: ScheduleSeasonActivation) = dao.insert(row.toEntity())

    override suspend fun forSchedule(scheduleId: ScheduleId): List<ScheduleSeasonActivation> =
        dao.forSchedule(scheduleId.value).map { it.toDomain() }

    override suspend fun all(): List<ScheduleSeasonActivation> = dao.all().map { it.toDomain() }

    override fun observeForSchedule(scheduleId: ScheduleId): Flow<List<ScheduleSeasonActivation>> =
        dao.observeForSchedule(scheduleId.value).map { rows -> rows.map { it.toDomain() } }
}

/**
 * #125 (U-C1) — the Room adapter for the device-local schedule bindings, #16's [RoomSeasonSyncRepository] keyed by the
 * schedule, plus the one-row delete. Unguarded, as #16's: a binding is device configuration; a held asset's schedule is
 * refused by the applier's `maintainedHere`.
 */
class RoomScheduleSeasonBindingRepository(
    private val dao: ScheduleSeasonSyncBindingDao,
) : ScheduleSeasonBindingRepository {

    override suspend fun get(scheduleId: ScheduleId): ScheduleSeasonBinding? = dao.bySchedule(scheduleId.value)?.toDomain()

    override suspend fun all(): List<ScheduleSeasonBinding> = dao.all().map { it.toDomain() }

    override fun observeFor(scheduleId: ScheduleId): Flow<ScheduleSeasonBinding?> =
        dao.observeBySchedule(scheduleId.value).map { it?.toDomain() }

    override suspend fun insert(binding: ScheduleSeasonBinding) = dao.insert(binding.toEntity())

    /** Writes [binding] only over the stored row's `binding.revision - 1`; a missing row answers false (#16's C11). */
    override suspend fun update(binding: ScheduleSeasonBinding): Boolean {
        val e = binding.toEntity()
        return dao.updateAtRevision(
            scheduleId = e.scheduleId, expectedRevision = binding.revision - 1, connectionId = e.connectionId,
            entityId = e.entityId, mode = e.mode, enabled = e.enabled, revision = e.revision,
            observedState = e.observedState, observedChangedAt = e.observedChangedAt, lastSuccessAt = e.lastSuccessAt,
            lastAttemptAt = e.lastAttemptAt, errorKind = e.errorKind, errorDetail = e.errorDetail, errorAt = e.errorAt,
            appliedAction = e.appliedAction, appliedOn = e.appliedOn, appliedAt = e.appliedAt,
            lastAppliedSource = e.lastAppliedSource, createdAt = e.createdAt, updatedAt = e.updatedAt,
        ) == 1
    }

    override suspend fun delete(scheduleId: ScheduleId) = dao.delete(scheduleId.value)

    override suspend fun anyEnabled(): Boolean = dao.anyEnabled()
}

internal fun ScheduleSeasonActivationEntity.toDomain() = ScheduleSeasonActivation(
    id = id,
    scheduleId = ScheduleId(scheduleId),
    action = SeasonAction.valueOf(action),
    occurredOn = occurredOn,
    createdAt = createdAt,
)

internal fun ScheduleSeasonActivation.toEntity() = ScheduleSeasonActivationEntity(
    id = id,
    scheduleId = scheduleId.value,
    action = action.name,
    occurredOn = occurredOn,
    createdAt = createdAt,
)

internal fun ScheduleSeasonSyncBindingEntity.toDomain() = ScheduleSeasonBinding(
    scheduleId = ScheduleId(scheduleId),
    connectionId = connectionId,
    entityId = entityId,
    mode = SyncMode.valueOf(mode),
    enabled = enabled,
    revision = revision,
    observedState = observedState?.let(HaSwitchState::valueOf),
    observedChangedAt = observedChangedAt,
    lastSuccessAt = lastSuccessAt,
    lastAttemptAt = lastAttemptAt,
    errorKind = errorKind?.let(SyncErrorKind::valueOf),
    errorDetail = errorDetail,
    errorAt = errorAt,
    appliedAction = appliedAction?.let(SeasonAction::valueOf),
    appliedOn = appliedOn,
    appliedAt = appliedAt,
    lastAppliedSource = lastAppliedSource?.let(LastAppliedSource::valueOf),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun ScheduleSeasonBinding.toEntity() = ScheduleSeasonSyncBindingEntity(
    scheduleId = scheduleId.value,
    connectionId = connectionId,
    entityId = entityId,
    mode = mode.name,
    enabled = enabled,
    revision = revision,
    observedState = observedState?.name,
    observedChangedAt = observedChangedAt,
    lastSuccessAt = lastSuccessAt,
    lastAttemptAt = lastAttemptAt,
    errorKind = errorKind?.name,
    errorDetail = errorDetail,
    errorAt = errorAt,
    appliedAction = appliedAction?.name,
    appliedOn = appliedOn,
    appliedAt = appliedAt,
    lastAppliedSource = lastAppliedSource?.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
