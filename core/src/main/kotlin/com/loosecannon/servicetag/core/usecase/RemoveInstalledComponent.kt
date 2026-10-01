package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.InstalledComponentRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * #47 (C15, C17; R47-6, R47-8) — takes an installed component out on [run]'s `removedOn`, which is required. Nothing
 * is deleted: the row stays as history with its removal date, as does everything it closes.
 *
 * - **The date's shape first, before any transaction:** an ISO day no later than [today].
 * - **Then, inside the one `uow.write` and before its first write:** [InstalledComponentProblem.NoSuchInstalledComponent],
 *   [InstalledComponentProblem.AlreadyRemoved], then [InstalledComponentProblem.RemovedBeforeInstalled] (`removedOn`)
 *   when the date is before this row's install date or any current descendant's.
 *
 * The write closes the row **and every current descendant** on the same date with the same `updatedAt` (R47-6), each
 * keeping its composition untouched; rows already removed below it keep their own dates. Nothing else is written. A
 * held asset's update throws at the guarded port, after every check (C14).
 */
class RemoveInstalledComponent(
    private val components: InstalledComponentRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
    private val today: Today,
) {
    suspend fun run(id: InstalledComponentId, removedOn: String): InstalledComponentResult {
        val shape = closingDateProblems(FIELD, removedOn, today.localDate())
        if (shape.isNotEmpty()) return InstalledComponentResult.Refused(shape)
        val closedOn = checkNotNull(parseDate(removedOn))

        return uow.write {
            val row = components.get(id) ?: return@write refused(InstalledComponentProblem.NoSuchInstalledComponent)
            if (!row.isCurrent) return@write refused(InstalledComponentProblem.AlreadyRemoved)
            val subtree = currentDescendants(components.forAsset(row.assetId), id)
            if (closesBeforeInstalled(closedOn, subtree + row)) {
                return@write refused(InstalledComponentProblem.RemovedBeforeInstalled(FIELD))
            }

            val now = clock.nowMillis()
            val removed = row.copy(removedOn = removedOn, updatedAt = now)
            val closed = subtree.map { it.copy(removedOn = removedOn, updatedAt = now) }
            components.update(removed)
            closed.forEach { components.update(it) }
            InstalledComponentResult.Ok(removed, replaced = null, closed = closed)
        }
    }

    private fun refused(problem: InstalledComponentProblem) = InstalledComponentResult.Refused(listOf(problem))

    private companion object {
        const val FIELD = "removedOn"
    }
}
