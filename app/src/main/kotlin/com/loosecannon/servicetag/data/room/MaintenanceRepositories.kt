package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.ports.ClosureRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.data.room.dao.MaintenanceGroupDao
import com.loosecannon.servicetag.data.room.dao.MaintenanceScheduleDao
import com.loosecannon.servicetag.data.room.dao.OccurrenceClosureDao

// The adapters for the three **data** ports the export, the import and the merge plan need. They
// carry the data half only — the queries the engine, the group screens and the occurrence rules
// want are declared by the briefs that own those ports, and are added to the port and to the
// adapter together. The DAO operations they call are already declared on the DAOs, so extending a
// port here is one method, not a schema conversation.

/** Aggregate repository: one `upsert` writes the group row and replaces its membership windows. */
class RoomGroupRepository(private val dao: MaintenanceGroupDao) : GroupRepository {
    override suspend fun upsert(group: MaintenanceGroup) = dao.upsert(
        group = group.toEntity(),
        members = group.members.map { it.toEntity(group.id) },
    )

    override suspend fun get(id: GroupId): MaintenanceGroup? = dao.byId(id.value)?.toDomain()

    override suspend fun all(): List<MaintenanceGroup> = dao.all().map { it.toDomain() }

    override suspend fun deleteAll() = dao.deleteAll()
}

/** Aggregate repository: one `upsert` writes the schedule row and replaces its provider rows. */
class RoomScheduleRepository(private val dao: MaintenanceScheduleDao) : ScheduleRepository {
    override suspend fun upsert(schedule: MaintenanceSchedule) = dao.upsert(
        schedule = schedule.toEntity(),
        providers = schedule.providers.map { it.toEntity(schedule.id) },
    )

    override suspend fun get(id: ScheduleId): MaintenanceSchedule? = dao.byId(id.value)?.toDomain()

    override suspend fun all(): List<MaintenanceSchedule> = dao.all().map { it.toDomain() }

    /**
     * Clears the table. Its CASCADE is what clears `occurrence_closure` as well, which is the only
     * way a closure row ever leaves — see [RoomClosureRepository].
     */
    override suspend fun deleteAll() = dao.deleteAll()
}

/**
 * Insert and query only, exactly as the port and the DAO are: a closure row is immutable, and there
 * is no update, no delete and no `deleteAll` here to offer a caller one.
 */
class RoomClosureRepository(private val dao: OccurrenceClosureDao) : ClosureRepository {
    override suspend fun insert(closure: OccurrenceClosure) = dao.insert(closure.toEntity())

    override suspend fun forSchedule(scheduleId: ScheduleId): List<OccurrenceClosure> =
        dao.forSchedule(scheduleId.value).map { it.toDomain() }

    override suspend fun find(scheduleId: ScheduleId, occurrenceOn: String): OccurrenceClosure? =
        dao.find(scheduleId.value, occurrenceOn)?.toDomain()

    override suspend fun all(): List<OccurrenceClosure> = dao.all().map { it.toDomain() }
}
