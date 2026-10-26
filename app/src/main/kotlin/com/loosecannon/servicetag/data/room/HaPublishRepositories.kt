package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.hapublish.DecommissionRepository
import com.loosecannon.servicetag.core.hapublish.DecommissionState
import com.loosecannon.servicetag.core.hapublish.DecommissionTombstone
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishBindingRepository
import com.loosecannon.servicetag.core.hapublish.PublishDelivery
import com.loosecannon.servicetag.core.hapublish.PublishDeliveryRepository
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.data.room.dao.HaPublishBindingDao
import com.loosecannon.servicetag.data.room.dao.HaPublishDecommissionDao
import com.loosecannon.servicetag.data.room.dao.HaPublishDeliveryDao
import com.loosecannon.servicetag.data.room.entities.HaPublishBindingEntity
import com.loosecannon.servicetag.data.room.entities.HaPublishDecommissionEntity
import com.loosecannon.servicetag.data.room.entities.HaPublishDeliveryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * #113/#114 (C24) — the Room adapter for the outbound bindings. Unguarded, as #16's: device configuration, never in an
 * archive. The DAO derives the claim from `enabled` on every write, and its update clears the delivery's error with
 * the pair (A04).
 */
class RoomPublishBindingRepository(private val dao: HaPublishBindingDao) : PublishBindingRepository {

    override suspend fun get(id: String): PublishBinding? = dao.get(id)?.toDomain()

    override suspend fun forAsset(assetId: AssetId): List<PublishBinding> = dao.forAsset(assetId.value).map { it.toDomain() }

    override suspend fun byTarget(connectionId: String, entityId: String): List<PublishBinding> =
        dao.byTarget(connectionId, entityId).map { it.toDomain() }

    override suspend fun all(): List<PublishBinding> = dao.all().map { it.toDomain() }

    override fun observeAll(): Flow<List<PublishBinding>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun insert(binding: PublishBinding) = dao.insert(binding.toEntity())

    override suspend fun update(binding: PublishBinding): Boolean = dao.update(binding.toEntity())

    override suspend fun clearValidation(id: String, atRevision: Long): Boolean = dao.clearValidation(id, atRevision) == 1

    override suspend fun delete(id: String) {
        dao.delete(id)
    }
}

/**
 * #113/#114 (C24) — the Room adapter for the delivery records: a command's whole-row [upsert], the projector's
 * [writeDesired], the sender's [record] (which, as `DeliverySteps.commit`, writes neither the desired value, the
 * boundary cache nor the request stamp) and the API's [stampRequest].
 */
class RoomPublishDeliveryRepository(private val dao: HaPublishDeliveryDao) : PublishDeliveryRepository {

    override suspend fun get(bindingId: String): PublishDelivery? = dao.get(bindingId)?.toDomain()

    override fun observe(bindingId: String): Flow<PublishDelivery?> =
        dao.observe(bindingId).map { it?.toDomain() }.distinctUntilChanged()

    override suspend fun upsert(delivery: PublishDelivery) = dao.upsert(delivery.toEntity())

    override suspend fun writeDesired(
        bindingId: String,
        bindingRevision: Long,
        expectedDesiredRevision: Long,
        desiredValue: String?,
        desiredAt: Long,
    ): Boolean = dao.writeDesired(bindingId, bindingRevision, expectedDesiredRevision, desiredValue, desiredAt)

    override suspend fun record(bindingRevision: Long, delivery: PublishDelivery): Boolean =
        dao.record(bindingRevision, delivery.toEntity())

    override suspend fun stampRequest(bindingIds: List<String>, requestId: String, at: Long) {
        if (bindingIds.isNotEmpty()) dao.stampRequest(bindingIds, requestId, at)
    }
}

/**
 * #114 (C24, C34) — the Room adapter for the tombstones: written or merged one per helper, a state and error write
 * that compares and sets on a revision it never moves, a delete by id.
 */
class RoomDecommissionRepository(private val dao: HaPublishDecommissionDao) : DecommissionRepository {

    override suspend fun get(id: String): DecommissionTombstone? = dao.get(id)?.toDomain()

    override suspend fun byTarget(connectionId: String, entityId: String): DecommissionTombstone? =
        dao.byTarget(connectionId, entityId)?.toDomain()

    override suspend fun all(): List<DecommissionTombstone> = dao.all().map { it.toDomain() }

    override fun observeAll(): Flow<List<DecommissionTombstone>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun upsertMerging(tombstone: DecommissionTombstone): String = dao.insertOrMerge(tombstone.toEntity())

    override suspend fun update(tombstone: DecommissionTombstone): Boolean = dao.updateAtRevision(
        tombstone.id, tombstone.revision, tombstone.state.name, tombstone.errorKind?.name, tombstone.errorAt,
    ) == 1

    override suspend fun delete(id: String) {
        dao.delete(id)
    }
}

internal fun HaPublishBindingEntity.toDomain() = PublishBinding(
    id = id,
    assetId = AssetId(assetId),
    connectionId = connectionId,
    kind = PublishKind.valueOf(kind),
    entityId = entityId,
    enabled = enabled,
    revision = revision,
    activatedInstallationId = activatedInstallationId,
    validatedAt = validatedAt,
    validatedRevision = validatedRevision,
    boundaryEntityId = boundaryEntityId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/** The claim is not passed: the DAO's writes derive it from `enabled` (F-9). */
internal fun PublishBinding.toEntity() = HaPublishBindingEntity(
    id = id,
    assetId = assetId.value,
    connectionId = connectionId,
    kind = kind.name,
    entityId = entityId,
    enabled = enabled,
    revision = revision,
    activatedInstallationId = activatedInstallationId,
    validatedAt = validatedAt,
    validatedRevision = validatedRevision,
    boundaryEntityId = boundaryEntityId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun HaPublishDeliveryEntity.toDomain() = PublishDelivery(
    bindingId = bindingId,
    desiredValue = desiredValue,
    desiredRevision = desiredRevision,
    desiredAt = desiredAt,
    lastPublishedValue = lastPublishedValue,
    lastPublishedAt = lastPublishedAt,
    publishedBindingRevision = publishedBindingRevision,
    publishedDesiredRevision = publishedDesiredRevision,
    lastVerifiedValue = lastVerifiedValue,
    lastVerifiedAt = lastVerifiedAt,
    verifiedBindingRevision = verifiedBindingRevision,
    verifiedDesiredRevision = verifiedDesiredRevision,
    remoteValue = remoteValue,
    remoteReadAt = remoteReadAt,
    errorKind = errorKind?.let(PublishErrorKind::valueOf),
    errorDetail = errorDetail,
    errorAt = errorAt,
    errorBindingRevision = errorBindingRevision,
    errorDesiredRevision = errorDesiredRevision,
    outcomeUnknownSince = outcomeUnknownSince,
    needsDelivery = needsDelivery,
    attemptsAtRevision = attemptsAtRevision,
    lastAttemptAt = lastAttemptAt,
    boundaryValue = boundaryValue,
    boundaryReadAt = boundaryReadAt,
    boundaryBindingRevision = boundaryBindingRevision,
    reconcilePending = reconcilePending,
    lastRequestId = lastRequestId,
    lastRequestAt = lastRequestAt,
)

internal fun PublishDelivery.toEntity() = HaPublishDeliveryEntity(
    bindingId = bindingId,
    desiredValue = desiredValue,
    desiredRevision = desiredRevision,
    desiredAt = desiredAt,
    lastPublishedValue = lastPublishedValue,
    lastPublishedAt = lastPublishedAt,
    publishedDesiredRevision = publishedDesiredRevision,
    publishedBindingRevision = publishedBindingRevision,
    lastVerifiedValue = lastVerifiedValue,
    lastVerifiedAt = lastVerifiedAt,
    verifiedBindingRevision = verifiedBindingRevision,
    verifiedDesiredRevision = verifiedDesiredRevision,
    remoteValue = remoteValue,
    remoteReadAt = remoteReadAt,
    errorKind = errorKind?.name,
    errorDetail = errorDetail,
    errorAt = errorAt,
    errorBindingRevision = errorBindingRevision,
    errorDesiredRevision = errorDesiredRevision,
    outcomeUnknownSince = outcomeUnknownSince,
    needsDelivery = needsDelivery,
    attemptsAtRevision = attemptsAtRevision,
    lastAttemptAt = lastAttemptAt,
    boundaryValue = boundaryValue,
    boundaryReadAt = boundaryReadAt,
    boundaryBindingRevision = boundaryBindingRevision,
    reconcilePending = reconcilePending,
    lastRequestId = lastRequestId,
    lastRequestAt = lastRequestAt,
)

internal fun HaPublishDecommissionEntity.toDomain() = DecommissionTombstone(
    id = id,
    connectionId = connectionId,
    entityId = entityId,
    assetId = assetId?.let(::AssetId),
    installationId = installationId,
    state = DecommissionState.valueOf(state),
    requestedAt = requestedAt,
    errorKind = errorKind?.let(PublishErrorKind::valueOf),
    errorAt = errorAt,
    revision = revision,
)

internal fun DecommissionTombstone.toEntity() = HaPublishDecommissionEntity(
    id = id,
    connectionId = connectionId,
    entityId = entityId,
    assetId = assetId?.value,
    installationId = installationId,
    state = state.name,
    requestedAt = requestedAt,
    errorKind = errorKind?.name,
    errorAt = errorAt,
    revision = revision,
)
