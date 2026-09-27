package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.ports.DeadlineLocalDelivery
import com.loosecannon.servicetag.core.ports.DeadlineLocalDeliveryRepository
import com.loosecannon.servicetag.data.room.dao.DeadlineLocalDeliveryDao
import com.loosecannon.servicetag.data.room.entities.DeadlineLocalDeliveryEntity

/**
 * The Room adapter for the device-local deadline delivery table (#79, C17), the sibling of
 * [RoomScheduleLocalDeliveryRepository] and, like it, with **no `observe`**: nothing draws this table.
 * `all()` is ordered by the DAO's `ORDER BY kind, subject_id`, so a proof reads a stable list.
 */
class RoomDeadlineLocalDeliveryRepository(
    private val dao: DeadlineLocalDeliveryDao,
) : DeadlineLocalDeliveryRepository {

    override suspend fun get(kind: String, subjectId: String): DeadlineLocalDelivery? =
        dao.byKey(kind, subjectId)?.toDomain()

    override suspend fun upsert(row: DeadlineLocalDelivery) = dao.upsert(row.toEntity())

    override suspend fun delete(kind: String, subjectId: String) = dao.delete(kind, subjectId)

    override suspend fun all(): List<DeadlineLocalDelivery> = dao.all().map { it.toDomain() }

    override suspend fun deleteAll() = dao.deleteAll()
}

internal fun DeadlineLocalDeliveryEntity.toDomain() = DeadlineLocalDelivery(
    kind = kind,
    subjectId = subjectId,
    announcedHash = announcedHash,
    announcedBoot = announcedBoot,
    updatedAt = updatedAt,
)

internal fun DeadlineLocalDelivery.toEntity() = DeadlineLocalDeliveryEntity(
    kind = kind,
    subjectId = subjectId,
    announcedHash = announcedHash,
    announcedBoot = announcedBoot,
    updatedAt = updatedAt,
)
