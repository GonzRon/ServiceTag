package com.loosecannon.servicetag.core.seasonsync

/**
 * #105 — what the browser lists. One scope in 1.8.0 (owner ruling Q3): Home Assistant's on/off helpers, the entities
 * whose id is in the `input_boolean` domain. A candidate is in a scope by its id's domain alone — never by its state.
 */
enum class EntityScope(val domain: String) {
    INPUT_BOOLEANS("input_boolean"),
}

/** #105 — the most rows the browser lists; past it the owner is asked to search (P105-10). */
const val MAX_LISTED_ENTITIES = 2_000

/** #105 — the rows to draw, and whether more matched than are drawn. */
data class PickerRows(val rows: List<HaEntityCandidate>, val truncatedList: Boolean)

/**
 * #105 (B1) — the browser's rows, pure: [all]'s candidates in [scope] (the id's domain before the dot equals the
 * scope's), matching [query] — trimmed and casefolded, a substring of the casefolded friendly name or of the
 * casefolded entity id; empty matches all — sorted by the casefolded friendly name then the entity id, a candidate
 * with no name sorting by its id; at most [MAX_LISTED_ENTITIES], with [PickerRows.truncatedList] when more matched.
 */
fun pickerRows(all: List<HaEntityCandidate>, scope: EntityScope, query: String): PickerRows {
    val needle = query.trim().lowercase()
    val matched = all.asSequence()
        .filter { it.entityId.substringBefore('.') == scope.domain }
        .filter { candidate ->
            needle.isEmpty() ||
                candidate.entityId.lowercase().contains(needle) ||
                candidate.friendlyName?.lowercase()?.contains(needle) == true
        }
        .sortedWith(compareBy({ (it.friendlyName ?: it.entityId).lowercase() }, { it.entityId }))
        .toList()
    return PickerRows(matched.take(MAX_LISTED_ENTITIES), matched.size > MAX_LISTED_ENTITIES)
}
