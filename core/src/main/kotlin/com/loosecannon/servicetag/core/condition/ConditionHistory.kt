package com.loosecannon.servicetag.core.condition

import com.loosecannon.servicetag.core.model.AssetCondition

/**
 * An asset's condition history, read (spec §5.1; master plan §9). Pure: it is handed rows and never
 * reads a store, and nothing here is ever written back — no column holds the current condition
 * (inv. 107), and there is no UNKNOWN to store (inv. 108).
 *
 * - [ordered] is every row by `(occurredOn, occurredTime with nulls first, createdAt, id)` — the
 *   order the stores list in, re-applied here so the answer never depends on how the rows arrived. A
 *   backdated correction sorts into place; nothing is edited to make that happen.
 * - [current] is the last of them, or null when nothing is recorded ("Condition not recorded").
 * - [since] is the **first** row of the latest run of equal values: recording DOWN again with a new
 *   reason adds a row but does not move "since".
 */
class ConditionHistory private constructor(val ordered: List<AssetCondition>) {

    val current: AssetCondition? get() = ordered.lastOrNull()

    val since: AssetCondition?
        get() {
            val latest = current ?: return null
            var start = ordered.lastIndex
            while (start > 0 && ordered[start - 1].condition == latest.condition) start--
            return ordered[start]
        }

    companion object {
        /** The ordering key. `compareBy` puts a null `occurredTime` first, as SQLite's ascending order does. */
        val ORDER: Comparator<AssetCondition> =
            compareBy({ it.occurredOn }, { it.occurredTime }, { it.createdAt }, { it.id })

        fun of(rows: List<AssetCondition>): ConditionHistory = ConditionHistory(rows.sortedWith(ORDER))
    }
}
