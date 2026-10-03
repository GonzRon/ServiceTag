package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncRepository
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.data.room.dao.HaConnectionDao
import com.loosecannon.servicetag.data.room.dao.SeasonSyncBindingDao
import com.loosecannon.servicetag.data.room.entities.HaConnectionEntity
import com.loosecannon.servicetag.data.room.entities.SeasonSyncBindingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * #16 (C11) — the Room adapter for the device-local bindings. Unguarded: a binding is device configuration, not an
 * asset-owned row the transfer guard wraps; a held asset's binding is refused by the applier's `maintainedHere` (C13).
 */
class RoomSeasonSyncRepository(private val dao: SeasonSyncBindingDao) : SeasonSyncRepository {

    override suspend fun get(assetId: AssetId): SeasonSyncBinding? = dao.byAsset(assetId.value)?.toDomain()

    override suspend fun all(): List<SeasonSyncBinding> = dao.all().map { it.toDomain() }

    override fun observeFor(assetId: AssetId): Flow<SeasonSyncBinding?> =
        dao.observeByAsset(assetId.value).map { it?.toDomain() }

    override suspend fun insert(binding: SeasonSyncBinding) = dao.insert(binding.toEntity())

    /** Writes [binding] only over the stored row's `binding.revision - 1`; a missing row answers false (C11). */
    override suspend fun update(binding: SeasonSyncBinding): Boolean {
        val e = binding.toEntity()
        return dao.updateAtRevision(
            assetId = e.assetId, expectedRevision = binding.revision - 1, connectionId = e.connectionId,
            entityId = e.entityId, mode = e.mode, enabled = e.enabled, revision = e.revision,
            observedState = e.observedState, observedChangedAt = e.observedChangedAt, lastSuccessAt = e.lastSuccessAt,
            lastAttemptAt = e.lastAttemptAt, errorKind = e.errorKind, errorDetail = e.errorDetail, errorAt = e.errorAt,
            appliedAction = e.appliedAction, appliedOn = e.appliedOn, appliedAt = e.appliedAt, createdAt = e.createdAt,
            updatedAt = e.updatedAt,
        ) == 1
    }

    override suspend fun anyEnabled(): Boolean = dao.anyEnabled()
}

/**
 * #16 (C11) — the Room adapter for the one connection (R16-10). [get] fails loudly on a second row, which no writer may
 * leave (C17), rather than choose one. Unguarded, as the bindings.
 */
class RoomHaConnectionRepository(private val dao: HaConnectionDao) : HaConnectionRepository {

    override suspend fun get(): HaConnection? {
        val rows = dao.all()
        check(rows.size <= 1) { "ha_connection holds ${rows.size} rows; one installation has one connection" }
        return rows.firstOrNull()?.toDomain()
    }

    override suspend fun upsert(connection: HaConnection) = dao.upsert(connection.toEntity())

    override suspend fun delete(id: String) = dao.delete(id)
}

internal fun HaConnectionEntity.toDomain() = HaConnection(
    id = id,
    baseUrl = baseUrl,
    cadence = SyncCadence.valueOf(cadence),
    networkEligibility = NetworkEligibility.valueOf(networkEligibility),
    homeNetworkSsid = homeNetworkSsid,
    backgroundChecks = BackgroundChecks.valueOf(backgroundChecks),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun HaConnection.toEntity() = HaConnectionEntity(
    id = id,
    baseUrl = baseUrl,
    cadence = cadence.name,
    networkEligibility = networkEligibility.name,
    homeNetworkSsid = homeNetworkSsid,
    backgroundChecks = backgroundChecks.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun SeasonSyncBindingEntity.toDomain() = SeasonSyncBinding(
    assetId = AssetId(assetId),
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
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun SeasonSyncBinding.toEntity() = SeasonSyncBindingEntity(
    assetId = assetId.value,
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
    createdAt = createdAt,
    updatedAt = updatedAt,
)
