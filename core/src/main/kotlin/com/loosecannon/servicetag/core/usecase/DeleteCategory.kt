package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.CategoryRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Deletes one of the owner's categories, and only an unused one (#74, C7; R74-6). In one
 * transaction the Assets of its key are counted — archived and retired included, [CategoryUsage]'s
 * number — and any use refuses with [CategoryInUse] and writes nothing. Nothing is ever reclassified
 * by a delete: the owner edits the Assets, or renames the category.
 */
class DeleteCategory(
    private val categories: CategoryRepository,
    private val assets: AssetRepository,
    private val uow: UnitOfWork,
) {
    suspend fun run(key: String) = uow.write {
        val row = categories.get(key) ?: throw NoSuchCategory(key)
        val count = CategoryUsage.count(assets.all(), key)
        if (count > 0) throw CategoryInUse(row.display, count)
        categories.delete(key)
    }
}
