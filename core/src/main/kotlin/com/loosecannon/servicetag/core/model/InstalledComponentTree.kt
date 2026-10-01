package com.loosecannon.servicetag.core.model

import java.util.PriorityQueue

/**
 * #47 (C5; R47-14): pure tree logic over a flat collection of installed components — one parent, any depth, no
 * cycles — typed for [InstalledComponent] beside [AssetTree], which it does not touch. Nothing here reads or
 * writes a store: the restore, the merge, the use cases and the phone's section hand it the rows they hold.
 */
object InstalledComponentTree {

    /** One row of [current]'s view and how deep it sits: 0 for a row drawn at the top. */
    data class Indented(val row: InstalledComponent, val depth: Int)

    /** The order among siblings (R47-11): [InstalledComponent.sortOrder], then the name case-insensitively, then the id. */
    val siblingOrder: Comparator<InstalledComponent> =
        compareBy<InstalledComponent> { it.sortOrder }.thenBy { it.name.lowercase() }.thenBy { it.id.value }

    /**
     * A topological order (Kahn's algorithm, [AssetTree.parentsFirst]'s): roots first, ties broken by
     * [InstalledComponentId.value]. A row whose parent is not in [rows] is a root. Throws
     * [IllegalStateException] when [rows] hold a parent cycle.
     */
    fun parentsFirst(rows: Collection<InstalledComponent>): List<InstalledComponent> {
        val byId = rows.associateBy { it.id }
        val childrenOf = rows.groupBy { it.parentId }
        val indegree = rows.associateTo(mutableMapOf()) { row ->
            row.id to if (row.parentId != null && byId.containsKey(row.parentId)) 1 else 0
        }

        val ready = PriorityQueue<InstalledComponent>(compareBy { it.id.value })
        rows.filterTo(mutableListOf()) { indegree[it.id] == 0 }.forEach(ready::add)

        val result = mutableListOf<InstalledComponent>()
        while (ready.isNotEmpty()) {
            val node = ready.poll()
            result += node
            for (child in childrenOf[node.id].orEmpty()) {
                val remaining = (indegree[child.id] ?: 0) - 1
                indegree[child.id] = remaining
                if (remaining == 0) ready.add(child)
            }
        }

        if (result.size != rows.size) throw IllegalStateException("cycle detected in the installed component tree")
        return result
    }

    /** Every row transitively fitted inside [id], current and removed, direct children included. */
    fun descendants(rows: Collection<InstalledComponent>, id: InstalledComponentId): Set<InstalledComponentId> {
        val childrenOf = rows.groupBy { it.parentId }
        val result = mutableSetOf<InstalledComponentId>()
        fun collect(parent: InstalledComponentId) {
            for (child in childrenOf[parent].orEmpty()) {
                if (result.add(child.id)) collect(child.id)
            }
        }
        collect(id)
        return result
    }

    /**
     * The current rows as a pre-order list, each with its depth, siblings in [siblingOrder]. A current row whose
     * parent is not a current row in [rows] is drawn at the top rather than hidden; rows on a parent cycle, which
     * every writer refuses, have no top and are left out.
     */
    fun current(rows: Collection<InstalledComponent>): List<Indented> {
        val live = rows.filter { it.isCurrent }
        val liveIds = live.mapTo(HashSet()) { it.id }
        val childrenOf = live.groupBy { it.parentId }
        val result = mutableListOf<Indented>()
        val placed = HashSet<InstalledComponentId>()
        fun place(row: InstalledComponent, depth: Int) {
            if (!placed.add(row.id)) return
            result += Indented(row, depth)
            childrenOf[row.id].orEmpty().sortedWith(siblingOrder).forEach { place(it, depth + 1) }
        }
        live.filter { it.parentId == null || it.parentId !in liveIds }.sortedWith(siblingOrder).forEach { place(it, 0) }
        return result
    }

    /**
     * The instances that have held [id]'s position, newest first: [id]'s row, then the row it replaced, following
     * [InstalledComponent.replacesId] back. Empty when [rows] hold no [id]. The walk takes at most one step per
     * row, so a `replacesId` cycle, which every writer refuses, still ends, and a row it meets twice is listed once.
     */
    fun history(rows: Collection<InstalledComponent>, id: InstalledComponentId): List<InstalledComponent> {
        val byId = rows.associateBy { it.id }
        val result = LinkedHashSet<InstalledComponent>()
        var next = byId[id]
        var steps = 0
        while (next != null && steps < rows.size) {
            result += next
            steps += 1
            next = next.replacesId?.let(byId::get)
        }
        return result.toList()
    }

    /** The row that replaced [id] — the one whose [InstalledComponent.replacesId] is [id] — or null. */
    fun successorOf(rows: Collection<InstalledComponent>, id: InstalledComponentId): InstalledComponent? =
        rows.firstOrNull { it.replacesId == id }

    /**
     * The removed rows nothing replaced, by removal date newest first, then the name case-insensitively, then the
     * id: what the section lists behind its toggle (R47-16).
     */
    fun removedUnreplaced(rows: Collection<InstalledComponent>): List<InstalledComponent> {
        val replaced = rows.mapNotNullTo(HashSet()) { it.replacesId }
        return rows.filter { !it.isCurrent && it.id !in replaced }
            .sortedWith(compareByDescending<InstalledComponent> { it.removedOn }.thenBy { it.name.lowercase() }.thenBy { it.id.value })
    }
}
