package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Takes a group and its schedules out of the active views without deleting anything, and **refuses
 * nothing**: archive is not delete, so there is no state it can be wrong to ask for.
 *
 * It writes one column. Every membership window, every member completion and every closure survives
 * it untouched, and unarchiving puts the group back exactly as it was — which is the point: a group
 * archived with an open round still holds the rows a past round's required set is derived from, and a
 * cascade here would destroy precisely the history #55 asks to be kept.
 *
 * Nothing is recomputed. `archived_at` is not an input to any derived due state; which views hide an
 * archived group is the views' question and is answered on the read side.
 */
class ArchiveGroup(
    private val groups: GroupRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
) {
    suspend fun run(id: GroupId, archived: Boolean): MaintenanceGroup {
        val current = groups.get(id) ?: throw NoSuchGroup(id)
        val now = clock.nowMillis()
        val saved = current.copy(archivedAt = if (archived) now else null, updatedAt = now)
        uow.write { groups.upsert(saved) }
        return saved
    }
}
