package com.loosecannon.servicetag.core.journal

/** One dropdown row: a free-text category label with an optional creation-time template hint. */
data class CategorySuggestion(val label: String, val suggestedTemplateKey: String?)

/**
 * The category catalog (spec §8). Category is free text; these are suggestions, never authority
 * — typing anything else is equally valid, and nothing reads the category string after creation.
 *
 * Superseded in part by #74 (`docs/superpowers/plans/2026-09-26-issue-74-durable-categories.md`):
 * these are now the compiled **built-ins** of a durable catalog ([CategoryCatalog]) whose other half
 * is the owner's saved categories, and a save stores the catalog's spelling of the category.
 */
object CategorySuggestions {

    val all: List<CategorySuggestion> = listOf(
        CategorySuggestion("Generator", "power_equipment"),
        CategorySuggestion("Lawn mower", "power_equipment"),
        CategorySuggestion("Snowblower", "power_equipment"),
        CategorySuggestion("UPS", "ups"),
        CategorySuggestion("Battery", null),
        CategorySuggestion("Inverter / charger", null),
        CategorySuggestion("Solar charge controller", null),
        CategorySuggestion("RO system", "ro_water"),
        CategorySuggestion("Hot tub", "hot_tub"),
        CategorySuggestion("HVAC", null),
        CategorySuggestion("Pump", null),
        CategorySuggestion("Other", "generic"),
    )

    /**
     * A built-in's hint, matched by key (#74, R74-12): `hot  tub` and `HOT TUB` carry the Hot tub hint
     * as `ro SYSTEM` does. Null for text that is no built-in's key — an owner's category never has one.
     */
    fun templateFor(category: String): String? {
        val key = CategoryKey.of(category) ?: return null
        return all.firstOrNull { CategoryKey.of(it.label) == key }?.suggestedTemplateKey
    }
}
