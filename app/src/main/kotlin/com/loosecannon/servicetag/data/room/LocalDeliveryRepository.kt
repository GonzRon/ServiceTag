package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.ports.ScheduleLocalDelivery
import com.loosecannon.servicetag.core.ports.ScheduleLocalDeliveryRepository
import com.loosecannon.servicetag.data.room.dao.ScheduleLocalDeliveryDao
import com.loosecannon.servicetag.data.room.entities.ScheduleLocalDeliveryEntity

/**
 * The Room adapter for the device-local delivery table.
 *
 * It belongs to this brief because the port does (master plan decision 17: the adapter for a port
 * belongs to the brief that declares it). There is **no `observe`**, and deliberately: nothing
 * draws this table — the snooze badge a screen shows is read through B08's own projection — and a
 * `Flow` over it would be an invitation to render delivery state, which is the one thing this table
 * exists to keep out of the canonical, exportable world.
 *
 * `all()` is ordered by the DAO's own `ORDER BY schedule_id`, so the rebuildable-from-nothing proof
 * and the export-carries-none-of-it proof both read a stable list.
 */
class RoomScheduleLocalDeliveryRepository(
    private val dao: ScheduleLocalDeliveryDao,
) : ScheduleLocalDeliveryRepository {

    override suspend fun get(id: ScheduleId): ScheduleLocalDelivery? = dao.byId(id.value)?.toDomain()

    override suspend fun upsert(row: ScheduleLocalDelivery) = dao.upsert(row.toEntity())

    override suspend fun all(): List<ScheduleLocalDelivery> = dao.all().map { it.toDomain() }

    override suspend fun deleteAll() = dao.deleteAll()
}

internal fun ScheduleLocalDeliveryEntity.toDomain() = ScheduleLocalDelivery(
    scheduleId = ScheduleId(scheduleId),
    snoozedUntilAt = snoozedUntilAt,
    lastNotifiedAt = lastNotifiedAt,
    firstEntrySeen = firstEntrySeen,
    actionNonce = actionNonce,
    nonceIssuedAt = nonceIssuedAt,
    updatedAt = updatedAt,
)

internal fun ScheduleLocalDelivery.toEntity() = ScheduleLocalDeliveryEntity(
    scheduleId = scheduleId.value,
    snoozedUntilAt = snoozedUntilAt,
    lastNotifiedAt = lastNotifiedAt,
    firstEntrySeen = firstEntrySeen,
    actionNonce = actionNonce,
    nonceIssuedAt = nonceIssuedAt,
    updatedAt = updatedAt,
)
