package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.journal.CategoryKey
import com.loosecannon.servicetag.core.model.AssetSupply

/**
 * #15 (C17; R15-3) — the roles the asset section's role sheet offers as chips: a pure function, never a catalog. A
 * suggestion fills the field only when a person picks it; nothing here or anywhere fills a role by itself.
 */
object AssetSupplyRoles {

    /**
     * The distinct cleaned roles in use on any asset in [rows] — distinct on the exact cleaned text, as uniqueness is
     * (limit 9: "Oil filter" and "oil filter" are two) — ordered case-insensitively and then exactly, the order the
     * category picker gives the owner's own categories (R74-10).
     */
    fun suggestions(rows: List<AssetSupply>): List<String> =
        rows.map { CategoryKey.display(it.role) }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER.thenBy { it })
}
