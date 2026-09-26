package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.journal.CategoryCatalog
import com.loosecannon.servicetag.core.journal.CategoryKey
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.ports.CategoryRepository

/**
 * What a save stores for its typed category: [spelling], the catalog's — a built-in's label, an
 * existing row's display, or the typed text's own display on first use — and [newRow], the row a
 * first use adds, or null.
 */
data class Promotion(val spelling: String, val newRow: AssetCategory?)

/**
 * **Promotion only by a successful save** (#74, C5), in two halves, so a caller can put each where it
 * belongs inside its own transaction:
 *
 * - [resolve] reads, inside the caller's transaction, and decides the canonical spelling the Asset
 *   stores — so `next.category` is canonical and an unchanged-save comparison sees it. `now` is the
 *   command's own, so a new row and its Asset share one timestamp.
 * - [write] writes the new row, if any. A command calls it **beside `assets.upsert`**, after its
 *   unchanged-save early return and after every refusal: an unchanged save writes nothing, a refused
 *   one adds no row, and a cancelled editor never reaches either half.
 *
 * Built-ins are never written: a built-in's key resolves to its label with no row (R74-1).
 */
class PromoteCategory(private val categories: CategoryRepository) {

    suspend fun resolve(typed: String, now: Long): Promotion {
        val key = CategoryKey.of(typed) ?: return Promotion("", null)
        CategoryCatalog.builtIn(key)?.let { return Promotion(it.display, null) }
        categories.get(key)?.let { return Promotion(it.display, null) }
        val display = CategoryKey.display(typed)
        return Promotion(display, AssetCategory(key, display, now, now))
    }

    suspend fun write(promotion: Promotion) {
        promotion.newRow?.let { categories.upsert(it) }
    }
}
