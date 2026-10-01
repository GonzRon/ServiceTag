package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.InstalledComponentRepository
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * #47 (C15, C16; R47-3, R47-8, R47-11) — the only way an installed component is fitted: the phone, the API and the
 * MCP all arrive here, so every check lives here and none in a caller.
 *
 * - **The command's shape first, before any transaction:** the name, the install date (not after [today]) and each
 *   entry's quantity, every problem collected. A refusal here opens none.
 * - **Then the other rows, inside the one `uow.write` and before its first write**, one refusal each, in order:
 *   [InstalledComponentProblem.OwnerMissing]; with a parent, [InstalledComponentProblem.ParentMissing],
 *   [InstalledComponentProblem.ParentOnAnotherAsset] (the schema cannot refuse it) and
 *   [InstalledComponentProblem.ParentRemoved]; with a direct link, [InstalledComponentProblem.SupplyItemMissing] and
 *   [InstalledComponentProblem.SupplyItemArchived]; then every entry's SupplyItem, collected by index. An archived
 *   SupplyItem enters no new link or composition. Nothing is minted before the last of them answers.
 *
 * The row stores the trimmed text, the install date as given (null when not recorded), no removal date and no
 * predecessor; its entries are minted fresh in list order; `sortOrder` is the command's, or one more than the
 * greatest among this parent's current children (the Asset's current top-level rows for a top-level row), 0 when
 * there are none. One insert. Nothing else is written: no applicability row, event, material line or SupplyItem, and
 * nothing is linked by matching names. A held asset's insert throws at the guarded port, after every check (C14).
 */
class InstallComponent(
    private val assets: AssetRepository,
    private val items: SupplyItemRepository,
    private val installedComponents: InstalledComponentRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val today: Today,
) {
    suspend fun run(cmd: InstallComponentCommand): InstalledComponentResult {
        val shape = installedComponentProblems(cmd.name, cmd.installedOn, removedOn = null, today = today.localDate()) +
            compositionInputProblems(cmd.composition)
        if (shape.isNotEmpty()) return InstalledComponentResult.Refused(shape)

        return uow.write {
            val problems = stateProblems(cmd)
            if (problems.isNotEmpty()) return@write InstalledComponentResult.Refused(problems)

            val siblings = installedComponents.forAsset(cmd.assetId).filter { it.isCurrent && it.parentId == cmd.parentId }
            val now = clock.nowMillis()
            val row = InstalledComponent(
                id = InstalledComponentId(ids.newId()),
                assetId = cmd.assetId,
                parentId = cmd.parentId,
                name = cmd.name.trim(),
                supplyId = cmd.supplyId,
                composition = compositionOf(cmd.composition, owned = emptySet(), ids = ids),
                serialOrLot = cmd.serialOrLot.trim(),
                installedOn = cmd.installedOn,
                removedOn = null,
                replacesId = null,
                sortOrder = cmd.sortOrder ?: ((siblings.maxOfOrNull { it.sortOrder } ?: -1) + 1),
                notes = cmd.notes.trim(),
                createdAt = now,
                updatedAt = now,
            )
            installedComponents.insert(row)
            InstalledComponentResult.Ok(row, replaced = null, closed = emptyList())
        }
    }

    /** The other rows' steps, in order: the first that fails answers alone, except the entries, which are collected. */
    private suspend fun stateProblems(cmd: InstallComponentCommand): List<InstalledComponentProblem> {
        if (assets.get(cmd.assetId) == null) return listOf(InstalledComponentProblem.OwnerMissing)
        if (cmd.parentId != null) {
            val parent = installedComponents.get(cmd.parentId) ?: return listOf(InstalledComponentProblem.ParentMissing)
            if (parent.assetId != cmd.assetId) return listOf(InstalledComponentProblem.ParentOnAnotherAsset)
            if (!parent.isCurrent) return listOf(InstalledComponentProblem.ParentRemoved)
        }
        return supplyProblems(items, cmd.supplyId, cmd.composition, keptDirect = null, keptInComposition = emptySet())
    }
}
