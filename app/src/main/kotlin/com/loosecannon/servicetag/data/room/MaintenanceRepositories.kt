package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.ports.ClosureRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.ScheduleStateRepository
import com.loosecannon.servicetag.data.room.dao.MaintenanceGroupDao
import com.loosecannon.servicetag.data.room.dao.MaintenanceScheduleDao
import com.loosecannon.servicetag.data.room.dao.OccurrenceClosureDao
import com.loosecannon.servicetag.data.room.dao.ScheduleStateDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// The adapters for the three **data** ports the export, the import and the merge plan need. They
// carry the data half only — the queries the engine, the group screens and the occurrence rules
// want are declared by the briefs that own those ports, and are added to the port and to the
// adapter together. The DAO operations they call are already declared on the DAOs, so extending a
// port here is one method, not a schema conversation.

/**
 * Aggregate repository: one `upsert` writes the group row and replaces its membership windows.
 *
 * Replacing the child rows wholesale is what makes the use case's job expressible at all: it hands
 * down the complete window list — the rows it kept, the ones it has just stamped `removed_at` on,
 * the closed ones it left alone and the new ones it inserted — and the ids come from it, so a window
 * that survived the edit keeps its identity. Nothing here decides anything about a window, and there
 * is no method on this class a caller could use to clear a `removed_at` even if it wanted one.
 */
class RoomGroupRepository(private val dao: MaintenanceGroupDao) : GroupRepository {
    override suspend fun upsert(group: MaintenanceGroup) = dao.upsert(
        group = group.toEntity(),
        members = group.members.map { it.toEntity(group.id) },
    )

    override suspend fun get(id: GroupId): MaintenanceGroup? = dao.byId(id.value)?.toDomain()

    override suspend fun all(): List<MaintenanceGroup> = dao.all().map { it.toDomain() }

    override suspend fun forAsset(assetId: AssetId): List<MaintenanceGroup> =
        dao.openForAsset(assetId.value).map { it.toDomain() }

    override suspend fun allWindowsFor(assetId: AssetId): List<MaintenanceGroup> =
        dao.everForAsset(assetId.value).map { it.toDomain() }

    override suspend fun deleteAll() = dao.deleteAll()

    override fun observeAll(): Flow<List<MaintenanceGroup>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /**
     * Filtered off [MaintenanceGroupDao.observeAll] rather than asked of SQLite.
     *
     * The DAO's two asset-scoped queries are `suspend` reads; a Flow of "the groups holding an open
     * window for this asset" would be a new `@Query` on a table whose DAO belongs to another brief.
     * The membership rows arrive with each group in the same aggregate, so the predicate here *is*
     * the predicate that query would carry, over a store of a few dozen rows.
     */
    override fun observeForAsset(assetId: AssetId): Flow<List<MaintenanceGroup>> =
        dao.observeAll().map { rows ->
            rows.map { it.toDomain() }
                .filter { group -> group.members.any { it.assetId == assetId && it.removedAt == null } }
        }
}

/** Aggregate repository: one `upsert` writes the schedule row and replaces its provider rows. */
class RoomScheduleRepository(private val dao: MaintenanceScheduleDao) : ScheduleRepository {
    override suspend fun upsert(schedule: MaintenanceSchedule) = dao.upsert(
        schedule = schedule.toEntity(),
        providers = schedule.providers.map { it.toEntity(schedule.id) },
    )

    override suspend fun get(id: ScheduleId): MaintenanceSchedule? = dao.byId(id.value)?.toDomain()

    override suspend fun all(): List<MaintenanceSchedule> = dao.all().map { it.toDomain() }

    override suspend fun forAsset(assetId: AssetId): List<MaintenanceSchedule> =
        dao.forAsset(assetId.value).map { it.toDomain() }

    override suspend fun forGroup(groupId: GroupId): List<MaintenanceSchedule> =
        dao.forGroup(groupId.value).map { it.toDomain() }

    /**
     * Clears the table. Its CASCADE is what clears `occurrence_closure` as well, which is the only
     * way a closure row ever leaves — see [RoomClosureRepository] — and `schedule_state` and
     * `schedule_local_delivery` with it.
     */
    override suspend fun deleteAll() = dao.deleteAll()

    override fun observeAll(): Flow<List<MaintenanceSchedule>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }
}

/**
 * The derived state. One writer, and it writes whole rows: there is no partial update here for the
 * same reason there is none on the DAO or the port, so the recompute cannot be worked around.
 */
class RoomScheduleStateRepository(private val dao: ScheduleStateDao) : ScheduleStateRepository {
    override suspend fun upsert(state: ScheduleState) = dao.upsert(state.toEntity())

    override suspend fun get(scheduleId: ScheduleId): ScheduleState? =
        dao.byId(scheduleId.value)?.toDomain()

    override suspend fun all(): List<ScheduleState> = dao.all().map { it.toDomain() }

    override suspend fun deleteAll() = dao.deleteAll()

    override fun observeAll(): Flow<List<ScheduleState>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }
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
