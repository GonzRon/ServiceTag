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
 * **refuses nothing**: archive is not delete, so there is no state it can be wrong to ask for.
 *
 * It writes one column. Every membership window, every member completion and every closure survives
 * it untouched, and unarchiving puts the group back exactly as it was — which is the point: a group
 * archived with an open round still holds the rows a past round's required set is derived from, and a
 * cascade here would destroy precisely the history #55 asks to be kept.
 *
 * Nothing is recomputed. `archived_at` is not an input to any derived due state; which views hide an
 * archived group is the views' question and is answered on the read side.
 *
 * **The Season Start role** (#114, C11 erratum): restoring a group a live role schedule targets is 409
 * [SeasonStartStrands] when that role would cover a member that may not have one, or one another live role covers
 * ([seasonStartRestoreStrandOf]), read inside the write.
 */
class ArchiveGroup(
    private val groups: GroupRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
    private val schedules: ScheduleRepository,
    private val assets: AssetRepository,
    private val transfers: TransferRecordRepository,
) {
    suspend fun run(id: GroupId, archived: Boolean): MaintenanceGroup = uow.write {
        val current = groups.get(id) ?: throw NoSuchGroup(id)
        val now = clock.nowMillis()
        val saved = current.copy(archivedAt = if (archived) now else null, updatedAt = now)
        if (!archived && current.archivedAt != null) seasonStartStrandOf(saved)?.let { throw it }
        groups.upsert(saved)
        saved
    }

    /** The restore rule over the role schedules aimed at [saved], judged on the groups as the restore leaves them. */
    private suspend fun seasonStartStrandOf(saved: MaintenanceGroup): SeasonStartStrands? {
        val roles = schedules.forGroup(saved.id)
            .filter { it.role == ScheduleRole.SEASON_START && it.status != ScheduleStatus.ARCHIVED }
            .map { it.id }
            .toSet()
        if (roles.isEmpty()) return null
        val after = groups.all().map { if (it.id == saved.id) saved else it }
        return seasonStartRestoreStrandOf(roles, assets.all(), transfers.heldIds(), schedules.all(), after)
    }
}
