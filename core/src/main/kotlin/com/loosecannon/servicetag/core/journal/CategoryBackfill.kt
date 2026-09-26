package com.loosecannon.servicetag.core.journal

import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.model.AssetId

/** What the backfill reads of one Asset: its id, its stored category and when it was created. */
data class AssetRow(val id: AssetId, val category: String, val createdAt: Long)

/**
 * What the backfill writes: [newRows], ordered by key, and [rewrites] — only the Assets whose stored
 * spelling differs from their key's canonical one, each with the spelling it takes.
 */
data class Backfill(val newRows: List<AssetCategory>, val rewrites: Map<AssetId, String>)

/**
 * The promotion rule applied retroactively (#74, C9): pure and deterministic. `MIGRATION_8_9` runs
 * it once over the saves made before the catalog existed, and the replace import and the merge
 * planner choose a new key's spelling by this same rule, so three paths cannot pick three spellings.
 *
 * For every non-blank category, its [CategoryKey]:
 * - a built-in's key: the Asset is rewritten to the built-in's label, and no row is made;
 * - any other key: one row, spelled as the Asset with the smallest `createdAt` (ties by id) spells
 *   it — through [CategoryKey.display] — with `createdAt = updatedAt =` that smallest `createdAt`,
 *   and every Asset of the key rewritten to that spelling.
 *
 * Blank categories are ignored. Nothing here reads or moves an Asset's `updatedAt`.
 */
object CategoryBackfill {

    fun plan(rows: List<AssetRow>): Backfill {
        val keyed = rows.mapNotNull { row -> CategoryKey.of(row.category)?.let { it to row } }
        val newRows = mutableListOf<AssetCategory>()
        val rewrites = mutableMapOf<AssetId, String>()
        for ((key, group) in keyed.groupBy({ it.first }, { it.second }).toSortedMap()) {
            val builtIn = CategoryCatalog.builtIn(key)
            val spelling = if (builtIn != null) {
                builtIn.display
            } else {
                val oldest = group.minWith(compareBy<AssetRow> { it.createdAt }.thenBy { it.id.value })
                CategoryKey.display(oldest.category)
                    .also { newRows += AssetCategory(key, it, oldest.createdAt, oldest.createdAt) }
            }
            group.filter { it.category != spelling }.forEach { rewrites[it.id] = spelling }
        }
        return Backfill(newRows, rewrites)
    }
}
