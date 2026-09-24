package com.loosecannon.servicetag.data.room.dao

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Relation
import androidx.room3.Transaction
import androidx.room3.Update
import com.loosecannon.servicetag.data.room.entities.MaintenanceGroupEntity
import com.loosecannon.servicetag.data.room.entities.MaintenanceGroupMemberEntity
import com.loosecannon.servicetag.data.room.entities.MaintenanceScheduleEntity
import com.loosecannon.servicetag.data.room.entities.OccurrenceClosureEntity
import com.loosecannon.servicetag.data.room.entities.ScheduleLocalDeliveryEntity
import com.loosecannon.servicetag.data.room.entities.ScheduleProviderEntity
import com.loosecannon.servicetag.data.room.entities.ScheduleStateEntity
import kotlinx.coroutines.flow.Flow

/** A group with its membership windows: the aggregate, loaded whole. */
data class GroupWithMembers(
    @Embedded val group: MaintenanceGroupEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["group_id"])
    val members: List<MaintenanceGroupMemberEntity>,
)

@Dao
interface MaintenanceGroupDao {
    /**
     * Aggregate upsert, the shape [ProfileDao.upsert] already uses: the row, then its members
     * replaced wholesale. Deleting the old child rows and inserting the new ones is what makes a
     * removed member actually disappear; the ids come from the caller, so a window that survived
     * the edit keeps its identity.
     */
    @Transaction
    suspend fun upsert(group: MaintenanceGroupEntity, members: List<MaintenanceGroupMemberEntity>) {
        if (update(group) == 0) insert(group)
        deleteMembers(group.id)
        members.forEach { insertMember(it) }
    }

    @Update suspend fun update(e: MaintenanceGroupEntity): Int

    @Insert suspend fun insert(e: MaintenanceGroupEntity)

    @Insert suspend fun insertMember(e: MaintenanceGroupMemberEntity)

    @Query("DELETE FROM maintenance_group_member WHERE group_id = :groupId")
    suspend fun deleteMembers(groupId: String)

    @Transaction
    @Query("SELECT * FROM maintenance_group WHERE id = :id")
    suspend fun byId(id: String): GroupWithMembers?

    @Transaction
    @Query("SELECT * FROM maintenance_group ORDER BY id")
    suspend fun all(): List<GroupWithMembers>

    @Transaction
    @Query("SELECT * FROM maintenance_group ORDER BY name, id")
    fun observeAll(): Flow<List<GroupWithMembers>>

    /**
     * The groups an asset is an **open** member of — `removed_at IS NULL`, the windows that are
     * still running. Distinct, because one asset may hold several closed windows in one group.
     */
    @Transaction
    @Query(
        "SELECT * FROM maintenance_group WHERE id IN (" +
            "SELECT DISTINCT group_id FROM maintenance_group_member " +
            "WHERE asset_id = :assetId AND removed_at IS NULL) ORDER BY name, id",
    )
    suspend fun openForAsset(assetId: String): List<GroupWithMembers>

    /** Every group an asset has *ever* been in, open window or closed: retained history. */
    @Transaction
    @Query(
        "SELECT * FROM maintenance_group WHERE id IN (" +
            "SELECT DISTINCT group_id FROM maintenance_group_member " +
            "WHERE asset_id = :assetId) ORDER BY name, id",
    )
    suspend fun everForAsset(assetId: String): List<GroupWithMembers>

    /**
     * Clears the table. Members CASCADE from the group row, but they are deleted explicitly so the
     * intent reads here and not only in the schema, and so the behaviour does not depend on
     * `PRAGMA foreign_keys` being on.
     */
    @Transaction
    suspend fun deleteAll() {
        deleteAllMembers()
        deleteAllGroups()
    }

    @Query("DELETE FROM maintenance_group_member")
    suspend fun deleteAllMembers()

    @Query("DELETE FROM maintenance_group")
    suspend fun deleteAllGroups()
}

/** A schedule with its enabled-provider rows: the aggregate, loaded whole. */
data class ScheduleWithProviders(
    @Embedded val schedule: MaintenanceScheduleEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["schedule_id"])
    val providers: List<ScheduleProviderEntity>,
)

@Dao
interface MaintenanceScheduleDao {
    /** Aggregate upsert, same shape as [MaintenanceGroupDao.upsert]. */
    @Transaction
    suspend fun upsert(
        schedule: MaintenanceScheduleEntity,
        providers: List<ScheduleProviderEntity>,
    ) {
        if (update(schedule) == 0) insert(schedule)
        deleteProviders(schedule.id)
        providers.forEach { insertProvider(it) }
    }

    @Update suspend fun update(e: MaintenanceScheduleEntity): Int

    @Insert suspend fun insert(e: MaintenanceScheduleEntity)

    @Insert suspend fun insertProvider(e: ScheduleProviderEntity)

    @Query("DELETE FROM schedule_provider WHERE schedule_id = :scheduleId")
    suspend fun deleteProviders(scheduleId: String)

    @Transaction
    @Query("SELECT * FROM maintenance_schedule WHERE id = :id")
    suspend fun byId(id: String): ScheduleWithProviders?

    @Transaction
    @Query("SELECT * FROM maintenance_schedule ORDER BY id")
    suspend fun all(): List<ScheduleWithProviders>

    @Transaction
    @Query("SELECT * FROM maintenance_schedule WHERE asset_id = :assetId ORDER BY title, id")
    suspend fun forAsset(assetId: String): List<ScheduleWithProviders>

    @Transaction
    @Query("SELECT * FROM maintenance_schedule WHERE group_id = :groupId ORDER BY title, id")
    suspend fun forGroup(groupId: String): List<ScheduleWithProviders>

    /** Every schedule, for a screen that follows the table rather than asking again. */
    @Transaction
    @Query("SELECT * FROM maintenance_schedule ORDER BY title, id")
    fun observeAll(): Flow<List<ScheduleWithProviders>>

    /**
     * Clears the table — and with it, by CASCADE, `schedule_provider`, `occurrence_closure`,
     * `schedule_state` and `schedule_local_delivery`. The provider rows are cleared explicitly for
     * [MaintenanceGroupDao.deleteAll]'s reason.
     *
     * **The closure cascade is load-bearing, and this is the one place that depends on it.** The
     * closure table has no delete of its own — the row is immutable — so unlike
     * [MaintenanceGroupDao.deleteAll] there is no explicit statement this could fall back on if
     * `PRAGMA foreign_keys` were off. That is why `MaintenanceDaoConstraintTest` proves the cascade
     * against a real database rather than assuming it.
     */
    @Transaction
    suspend fun deleteAll() {
        deleteAllProviders()
        deleteAllSchedules()
    }

    @Query("DELETE FROM schedule_provider")
    suspend fun deleteAllProviders()

    @Query("DELETE FROM maintenance_schedule")
    suspend fun deleteAllSchedules()
}

/**
 * **Insert and query only.** There is no update method, no delete method and no delete statement
 * anywhere in here, because the closure row is immutable: it is written once and leaves only when
 * its schedule is deleted and the CASCADE takes it. Every other DAO in this package offers an
 * upsert; copying that shape here would hand a caller the amendment the closure fact forbids.
 */
@Dao
interface OccurrenceClosureDao {
    @Insert suspend fun insert(e: OccurrenceClosureEntity)

    @Query("SELECT * FROM occurrence_closure WHERE id = :id")
    suspend fun byId(id: String): OccurrenceClosureEntity?

    @Query("SELECT * FROM occurrence_closure WHERE schedule_id = :scheduleId ORDER BY occurrence_on")
    suspend fun forSchedule(scheduleId: String): List<OccurrenceClosureEntity>

    /** The row holding the unique `(schedule_id, occurrence_on)` — the closure's second identity. */
    @Query(
        "SELECT * FROM occurrence_closure " +
            "WHERE schedule_id = :scheduleId AND occurrence_on = :occurrenceOn",
    )
    suspend fun find(scheduleId: String, occurrenceOn: String): OccurrenceClosureEntity?

    @Query("SELECT * FROM occurrence_closure ORDER BY id")
    suspend fun all(): List<OccurrenceClosureEntity>
}

/**
 * Derived state. One writer — the recompute function — and it upserts whole rows, so there is no
 * partial-update method here that could become a second write path.
 */
@Dao
interface ScheduleStateDao {
    @Transaction
    suspend fun upsert(e: ScheduleStateEntity) {
        if (update(e) == 0) insert(e)
    }

    @Update suspend fun update(e: ScheduleStateEntity): Int

    @Insert suspend fun insert(e: ScheduleStateEntity)

    @Query("SELECT * FROM schedule_state WHERE schedule_id = :scheduleId")
    suspend fun byId(scheduleId: String): ScheduleStateEntity?

    @Query("SELECT * FROM schedule_state ORDER BY schedule_id")
    suspend fun all(): List<ScheduleStateEntity>

    /** By the actionable date, the sort key since the policy can move it off the effective one. */
    @Query("SELECT * FROM schedule_state ORDER BY actionable_due_on, schedule_id")
    fun observeAll(): Flow<List<ScheduleStateEntity>>

    @Query("DELETE FROM schedule_state")
    suspend fun deleteAll()
}

/** Device-local delivery bookkeeping. Nothing here is canonical, exported or merged. */
@Dao
interface ScheduleLocalDeliveryDao {
    @Transaction
    suspend fun upsert(e: ScheduleLocalDeliveryEntity) {
        if (update(e) == 0) insert(e)
    }

    @Update suspend fun update(e: ScheduleLocalDeliveryEntity): Int

    @Insert suspend fun insert(e: ScheduleLocalDeliveryEntity)

    @Query("SELECT * FROM schedule_local_delivery WHERE schedule_id = :scheduleId")
    suspend fun byId(scheduleId: String): ScheduleLocalDeliveryEntity?

    @Query("SELECT * FROM schedule_local_delivery ORDER BY schedule_id")
    suspend fun all(): List<ScheduleLocalDeliveryEntity>

    @Query("DELETE FROM schedule_local_delivery")
    suspend fun deleteAll()
}
