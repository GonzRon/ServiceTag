package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.journal.CategoryCatalog
import com.loosecannon.servicetag.core.journal.CategoryKey
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.CategoryRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Renames one of the owner's categories (#74, C6; R74-5). The new key and display come from
 * [CategoryKey]; the refusals, in order: blank → [CategoryValidation], a built-in's key →
 * [CategoryIsBuiltIn], another row's key → [CategoryExists]. A display equal to the row's is a
 * no-op and writes nothing.
 *
 * Otherwise, in one transaction: the same key updates the row's display in place; a new key inserts
 * a row keeping the old row's `createdAt` and deletes the old row (R74-2's accepted cost: an older
 * archive re-inserts the old key). **Either way every Asset of the old key — archived and retired
 * included — takes the new display, and its `updatedAt` moves**: an owner action changed it (R74-3).
 */
class RenameCategory(
    private val categories: CategoryRepository,
    private val assets: AssetRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
) {
    suspend fun run(key: String, newText: String): AssetCategory = uow.write {
        val row = categories.get(key) ?: throw NoSuchCategory(key)
        val newKey = CategoryKey.of(newText) ?: throw CategoryValidation()
        val display = CategoryKey.display(newText)
        CategoryCatalog.builtIn(newKey)?.let { throw CategoryIsBuiltIn(it.display) }
        if (newKey != key) categories.get(newKey)?.let { throw CategoryExists(it.display) }
        if (display == row.display) return@write row

        val now = clock.nowMillis()
        val renamed = if (newKey == key) {
            row.copy(display = display, updatedAt = now)
        } else {
            AssetCategory(newKey, display, row.createdAt, now)
        }
        categories.upsert(renamed)
        if (newKey != key) categories.delete(key)
        assets.all()
            .filter { CategoryKey.of(it.category) == key }
            .forEach { assets.upsert(it.copy(category = display, updatedAt = now)) }
        renamed
    }
}
