package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * #15 (C16; R15-5, R15-6) — archives or unarchives one SupplyItem, the [ArchiveGroup] shape: it **refuses nothing**
 * (bar an id that is not there), writes `archived_at` and moves `updated_at`, and is reversible. This is the only way a
 * SupplyItem leaves the pickers: there is no delete, here or anywhere (R15-5).
 *
 * Every applicability row and every material line naming the item is left exactly as it is — an archived item stays
 * on its Assets and its lines, and is refused only for a **new** applicability row ([AddAssetSupply]).
 */
class ArchiveSupplyItem(
    private val items: SupplyItemRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
) {
    suspend fun run(id: SupplyId, archived: Boolean): SupplyItem {
        val current = items.get(id) ?: throw NoSuchSupplyItem(id)
        val now = clock.nowMillis()
        val archivedAt = if (archived) now else null
        uow.write { items.setArchived(id, archivedAt, now) }
        return current.copy(archivedAt = archivedAt, updatedAt = now)
    }
}
