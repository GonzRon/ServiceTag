package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.InstalledComponentRepository
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * #47 (C15, C19; R47-3, R47-15) — corrects one installed component, current or removed: its name, direct link,
 * composition, serial or lot, install date, notes and `sortOrder`. The command has no Asset, parent, removal date or
 * `replacesId`, so an edit never moves a row, reopens it or rewrites what it replaced.
 *
 * - **The command's shape first, before any transaction:** the name, the install date as an ISO day and each
 *   entry's quantity, every problem collected. A refusal here opens none.
 * - **Then, inside the one `uow.write` and before its first write:**
 *   [InstalledComponentProblem.NoSuchInstalledComponent]; then [InstalledComponentProblem.AfterToday] (`installedOn`)
 *   only when the command **changes** the install date to one after [today] — a stored date, written when it was
 *   today's or carried by a restore as written, is never judged again; then
 *   [InstalledComponentProblem.RemovedBeforeInstalled]
 *   (`installedOn`) when the row is removed and the command's install date falls after its stored removal date — the
 *   stored date itself is never judged against [today], since a restore may carry one later than this phone's; then
 *   the direct link and entries under R47-3: an archived SupplyItem is taken as the direct link only when the stored
 *   link is that SupplyItem, and in an entry only when the stored composition already names it.
 *
 * The composition is the whole ordered list: an entry keeps its id only when this row owns it, at its first
 * occurrence (C-7); every other entry is minted fresh. An edit equal to the stored row after trimming, every entry
 * kept, answers [InstalledComponentProblem.Unchanged] and writes nothing. Otherwise one update, the composition
 * replaced whole, `updatedAt` moved. A held asset's update throws at the guarded port, after every check (C14).
 */
class UpdateInstalledComponent(
    private val items: SupplyItemRepository,
    private val installedComponents: InstalledComponentRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val today: Today,
) {
    suspend fun run(id: InstalledComponentId, cmd: UpdateInstalledComponentCommand): InstalledComponentResult {
        val shape = installedComponentProblems(cmd.name, cmd.installedOn, removedOn = null) +
            compositionInputProblems(cmd.composition)
        if (shape.isNotEmpty()) return InstalledComponentResult.Refused(shape)

        return uow.write {
            val row = installedComponents.get(id) ?: return@write refused(InstalledComponentProblem.NoSuchInstalledComponent)
            if (cmd.installedOn != row.installedOn && installedAfterToday(cmd.installedOn)) {
                return@write refused(InstalledComponentProblem.AfterToday(FIELD))
            }
            if (installedAfterRemoval(cmd.installedOn, row.removedOn)) {
                return@write refused(InstalledComponentProblem.RemovedBeforeInstalled(FIELD))
            }
            val supply = supplyProblems(
                items, cmd.supplyId, cmd.composition,
                keptDirect = row.supplyId,
                keptInComposition = row.composition.mapTo(HashSet()) { it.supplyId },
            )
            if (supply.isNotEmpty()) return@write InstalledComponentResult.Refused(supply)

            // The entries as they would be kept, before anything is minted: a new entry is a change by itself.
            val owned = row.composition.mapTo(HashSet()) { it.id }
            var fresh = false
            val kept = compositionOf(cmd.composition, owned, IdGenerator { fresh = true; "" })
            val edited = row.copy(
                name = cmd.name.trim(),
                supplyId = cmd.supplyId,
                composition = kept,
                serialOrLot = cmd.serialOrLot.trim(),
                installedOn = cmd.installedOn,
                notes = cmd.notes.trim(),
                sortOrder = cmd.sortOrder,
            )
            if (!fresh && edited == row) return@write refused(InstalledComponentProblem.Unchanged)

            val written = edited.copy(
                composition = if (fresh) compositionOf(cmd.composition, owned, ids) else kept,
                updatedAt = clock.nowMillis(),
            )
            installedComponents.update(written)
            InstalledComponentResult.Ok(written, replaced = null, closed = emptyList())
        }
    }

    /** A changed install date later than [today]; the shape check already refused one that is not a date. */
    private fun installedAfterToday(installedOn: String?): Boolean =
        installedOn?.let(::parseDate)?.let { it > today.localDate() } == true

    /** The command's install date after the stored removal date; a date not recorded, or a current row, is never. */
    private fun installedAfterRemoval(installedOn: String?, removedOn: String?): Boolean {
        val installed = installedOn?.let(::parseDate) ?: return false
        val removed = removedOn?.let(::parseDate) ?: return false
        return installed > removed
    }

    private fun refused(problem: InstalledComponentProblem) = InstalledComponentResult.Refused(listOf(problem))

    private companion object {
        const val FIELD = "installedOn"
    }
}
