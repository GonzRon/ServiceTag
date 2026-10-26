package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Takes a group and its schedules out of the active views without deleting anything. Archiving
 * **refuses nothing** but #124's one strand (below): archive is not delete, so there is no other state it can be wrong
 * to ask for.
 *
 * It writes one column. Every membership window, every member completion and every closure survives
 * it untouched, and unarchiving puts the group back exactly as it was — which is the point: a group
 * archived with an open round still holds the rows a past round's required set is derived from, and a
 * cascade here would destroy precisely the history #55 asks to be kept.
 *
 * `archived_at` is not an input to any group schedule's derived due state; which views hide an archived group is the
 * views' question and is answered on the read side. The one exception is a **role group** (one a live Season Start role
 * schedule targets, #124, N2a's C41/C42 erratum): its archive and restore move whether that role covers each open member,
 * so the same write recomputes each member's asset for the schedules counting from their opening service.
 *
 * **The Season Start role** (#114, C11 erratum): restoring a group a live role schedule targets is 409
 * [SeasonStartStrands] when that role would cover a member that may not have one, or one another live role covers
 * ([seasonStartRestoreStrandOf]), read inside the write. **Archiving** one is 409 [SeasonStartStrands]
 * (`HAS_DEPENDENT_SCHEDULES`, #124, C42) while a live schedule of an open member counts from that member's opening
 * service and no other live role would cover it ([openingServiceStrandOf]); nothing is written.
 */
class ArchiveGroup(
    private val groups: GroupRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
    private val schedules: ScheduleRepository,
    private val assets: AssetRepository,
    private val transfers: TransferRecordRepository,
    private val recompute: RecomputeSchedules,
) {
    suspend fun run(id: GroupId, archived: Boolean): MaintenanceGroup = uow.write {
        val current = groups.get(id) ?: throw NoSuchGroup(id)
        val now = clock.nowMillis()
        val saved = current.copy(archivedAt = if (archived) now else null, updatedAt = now)
        if (!archived && current.archivedAt != null) seasonStartStrandOf(saved)?.let { throw it }
        val roleGroup = liveRolesAimedAt(id).isNotEmpty()
        if (archived && current.archivedAt == null && roleGroup) archiveStrandOf(current, saved)?.let { throw it }
        groups.upsert(saved)
        // #124 (C41 erratum): the role's coverage of each open member moved with the group.
        if (roleGroup && current.archivedAt != saved.archivedAt) saved.openMembers().forEach { recompute.forAsset(it.assetId) }
        saved
    }

    private suspend fun liveRolesAimedAt(id: GroupId) = schedules.forGroup(id)
        .filter { it.role == ScheduleRole.SEASON_START && it.status != ScheduleStatus.ARCHIVED }

    /** #124 (C42): the dependents' strand of archiving a role group, over the groups as the archive leaves them. */
    private suspend fun archiveStrandOf(current: MaintenanceGroup, saved: MaintenanceGroup): SeasonStartStrands? {
        val before = groups.all()
        val after = before.map { if (it.id == saved.id) saved else it }
        val every = schedules.all()
        return openingServiceStrandOf(current.openMembers().map { it.assetId }, assets.all(), every, before, every, after)
    }

    /** The restore rule over the role schedules aimed at [saved], judged on the groups as the restore leaves them. */
    private suspend fun seasonStartStrandOf(saved: MaintenanceGroup): SeasonStartStrands? {
        val roles = liveRolesAimedAt(saved.id).map { it.id }.toSet()
        if (roles.isEmpty()) return null
        val after = groups.all().map { if (it.id == saved.id) saved else it }
        return seasonStartRestoreStrandOf(roles, assets.all(), transfers.heldIds(), schedules.all(), after)
    }
}
