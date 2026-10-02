package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.InstalledComponentRepository
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * #47 (C15, C18; AC6; R47-2, R47-3, R47-6, R47-17b) — takes one installed component out on the command's
 * `replacedOn` and fits its successor in the same place, in one write. Nothing else moves.
 *
 * - **The command's shape first, before any transaction:** the name, the replacement date (an ISO day no later than
 *   [today]) and each entry's quantity, every problem collected. A refusal here opens none.
 * - **Then, inside the one `uow.write` and before its first write**, one refusal each, in order:
 *   [InstalledComponentProblem.NoSuchInstalledComponent]; [InstalledComponentProblem.AlreadyRemoved] — a row already
 *   replaced is closed, so a second replace of it is refused here, and no two successors ever name one predecessor;
 *   the successor's direct link and entries as an install checks them, so an archived SupplyItem is refused even when
 *   the predecessor names it (the successor's links are new); then
 *   [InstalledComponentProblem.RemovedBeforeInstalled] (`replacedOn`) when the date is before the predecessor's install
 *   date or any current descendant's.
 *
 * The write inserts the successor — a new id, the predecessor's Asset, parent and `sortOrder`, the command's trimmed
 * name, link, composition (every entry minted fresh, ids sent ignored), serial or lot and notes, installed on the
 * replacement date, naming the predecessor in `replacesId` — then closes the predecessor and every current descendant
 * on that date with one `updatedAt`, each keeping its composition (R47-6: the successor starts with no children).
 *
 * What the command carries is all the successor gets: no link, composition or name is read from the predecessor
 * (R47-17b). The successor takes the predecessor's Asset and parent by construction, so no pointer to another Asset
 * can be written. No event, line, applicability row or SupplyItem is written. A held asset's insert throws at the
 * guarded port, after every check (C14).
 */
class ReplaceInstalledComponent(
    private val items: SupplyItemRepository,
    private val installedComponents: InstalledComponentRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val today: Today,
) {
    suspend fun run(id: InstalledComponentId, cmd: ReplaceComponentCommand): InstalledComponentResult {
        val shape = installedComponentProblems(cmd.name, installedOn = null, removedOn = null) +
            closingDateProblems(FIELD, cmd.replacedOn, today.localDate()) +
            compositionInputProblems(cmd.composition)
        if (shape.isNotEmpty()) return InstalledComponentResult.Refused(shape)
        val replacedOn = checkNotNull(parseDate(cmd.replacedOn))

        return uow.write {
            val predecessor = installedComponents.get(id)
                ?: return@write refused(InstalledComponentProblem.NoSuchInstalledComponent)
            if (!predecessor.isCurrent) return@write refused(InstalledComponentProblem.AlreadyRemoved)
            val supply = supplyProblems(items, cmd.supplyId, cmd.composition, keptDirect = null, keptInComposition = emptySet())
            if (supply.isNotEmpty()) return@write InstalledComponentResult.Refused(supply)
            val subtree = currentDescendants(installedComponents.forAsset(predecessor.assetId), id)
            if (closesBeforeInstalled(replacedOn, subtree + predecessor)) {
                return@write refused(InstalledComponentProblem.RemovedBeforeInstalled(FIELD))
            }

            val now = clock.nowMillis()
            val successor = InstalledComponent(
                id = InstalledComponentId(ids.newId()),
                assetId = predecessor.assetId,
                parentId = predecessor.parentId,
                name = cmd.name.trim(),
                supplyId = cmd.supplyId,
                composition = compositionOf(cmd.composition, owned = emptySet(), ids = ids),
                serialOrLot = cmd.serialOrLot.trim(),
                installedOn = cmd.replacedOn,
                removedOn = null,
                replacesId = predecessor.id,
                sortOrder = predecessor.sortOrder,
                notes = cmd.notes.trim(),
                createdAt = now,
                updatedAt = now,
            )
            val replaced = predecessor.copy(removedOn = cmd.replacedOn, updatedAt = now)
            val closed = subtree.map { it.copy(removedOn = cmd.replacedOn, updatedAt = now) }
            installedComponents.insert(successor)
            installedComponents.update(replaced)
            closed.forEach { installedComponents.update(it) }
            InstalledComponentResult.Ok(successor, replaced = replaced, closed = closed)
        }
    }

    private fun refused(problem: InstalledComponentProblem) = InstalledComponentResult.Refused(listOf(problem))

    private companion object {
        const val FIELD = "replacedOn"
    }
}
