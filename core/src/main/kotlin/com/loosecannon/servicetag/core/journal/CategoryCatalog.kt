package com.loosecannon.servicetag.core.journal

import com.loosecannon.servicetag.core.model.AssetCategory

/**
 * One choice the category picker, and #73's Type filter, offer (#74, C3). A built-in carries its
 * creation-time template hint; one of the owner's categories never has one (AC 7).
 */
data class CategoryChoice(
    val display: String,
    val key: String,
    val builtIn: Boolean,
    val templateKey: String?,
)

/**
 * The category catalog as a read model (#74, C3): the compiled built-ins ([CategorySuggestions.all])
 * united with the owner's rows. It is **never** derived from the categories Assets happen to hold —
 * a row outlives the last Asset using it, and the picker offers it all the same.
 */
object CategoryCatalog {

    /** The built-ins in compiled order, each keyed by [CategoryKey.of] its label and carrying its hint. */
    val builtIns: List<CategoryChoice> = CategorySuggestions.all.map {
        CategoryChoice(it.label, checkNotNull(CategoryKey.of(it.label)), builtIn = true, it.suggestedTemplateKey)
    }

    /**
     * Every choice: the built-ins first, in compiled order, then [custom] ordered by
     * `String.CASE_INSENSITIVE_ORDER` on the display and then by key (R74-10), deduplicated by key
     * with the built-in winning — a row under a built-in's key can exist once the built-in list grows.
     */
    fun choices(custom: List<AssetCategory>): List<CategoryChoice> {
        val rows = custom
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, AssetCategory::display).thenBy { it.key })
            .map { CategoryChoice(it.display, it.key, builtIn = false, templateKey = null) }
        return (builtIns + rows).distinctBy { it.key }
    }

    /** The choice whose key is [text]'s, or null — blank text, or a category nobody has saved yet. */
    fun resolve(text: String, custom: List<AssetCategory>): CategoryChoice? {
        val key = CategoryKey.of(text) ?: return null
        return choices(custom).firstOrNull { it.key == key }
    }

    /** The built-in whose key is [key], or null. */
    fun builtIn(key: String): CategoryChoice? = builtIns.firstOrNull { it.key == key }
}
