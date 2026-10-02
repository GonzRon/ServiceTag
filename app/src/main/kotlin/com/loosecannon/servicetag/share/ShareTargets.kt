package com.loosecannon.servicetag.share

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.InstalledComponentTree
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.model.maintainedHere

// #69 (C30 step 4): the pure pieces of Share's direct lists — which assets, installed components and SupplyItems
// are destinations, how a query narrows the component and supply lists, the order and the lines a row draws, and
// whether an empty list means "nothing of this type" or "nothing matches". Nothing here reads a store or holds state:
// the view model hands in the rows it already reads, the held set and the one query, and draws what comes back.

/**
 * One of Share's direct lists for a query (C30 step 4). [NoneEligible]: no destination of this type exists before any
 * filtering, whatever the query — the screen draws that type's empty-list sentence. [NothingMatches]: some exist and
 * the query hits none — "Nothing matches that.". [Rows]: the hits, never empty, in the list's order.
 */
internal sealed interface ShareTargetList<out T> {
    data object NoneEligible : ShareTargetList<Nothing>
    data object NothingMatches : ShareTargetList<Nothing>
    data class Rows<out T>(val rows: List<T>) : ShareTargetList<T> {
        init {
            require(rows.isNotEmpty()) { "an empty list is NoneEligible or NothingMatches" }
        }
    }
}

/**
 * One installed component as the Installed components list offers it: a **current** row on an asset maintained here.
 * [path] is the asset's name, each ancestor component's name from the top down, then this row's own, as
 * [InstalledComponentTree.current]'s walk places it — a current row under a removed or missing parent is a root, so
 * its path is the asset's name and its own (N-4).
 */
internal data class ComponentTarget(
    val assetId: AssetId,
    val componentId: InstalledComponentId,
    val path: List<String>,
) {
    init {
        require(path.size >= 2) { "a component's path names its asset and itself" }
    }

    /** The row's own name: [path]'s last segment. */
    val name: String get() = path.last()

    /** The quiet context line's segments: the asset's name, then each ancestor's. Never empty. */
    val context: List<String> get() = path.subList(0, path.size - 1)
}

/**
 * One SupplyItem as the Supplies list offers it: an unarchived catalog item, reached directly whether or not an asset
 * names it. [productLine] is [productLineOf]'s, the row's quiet line and the save form's.
 */
internal data class SupplyTarget(
    val supplyId: SupplyId,
    val name: String,
    val productLine: String,
)

/**
 * The assets Share offers, and whose installed components it offers: [Asset.maintainedHere] — in service and not
 * transferred out ([held] is the transfer records' held ids). Archived, retired and held assets are left out; season
 * and DOWN play no part. [assets]' order is kept.
 */
internal fun shareableAssets(assets: Collection<Asset>, held: Set<AssetId>): List<Asset> =
    assets.filter { it.maintainedHere(held) }

/** Whether Share offers this SupplyItem anywhere — the direct list or a browsing level: unarchived (R69-10). */
internal val SupplyItem.isShareable: Boolean get() = archivedAt == null

/**
 * Every installed component the list offers before any query: the current rows of [shareableAssets], each with its
 * path, in [componentOrder]. A removed or replaced row, and every row of an asset not maintained here or not in
 * [assets], is never offered.
 */
internal fun componentTargets(
    assets: Collection<Asset>,
    held: Set<AssetId>,
    components: Collection<InstalledComponent>,
): List<ComponentTarget> {
    val eligible = shareableAssets(assets, held).associateBy { it.id }
    return components.filter { it.assetId in eligible }
        .groupBy { it.assetId }
        .flatMap { (assetId, rows) -> targetsOn(eligible.getValue(assetId), rows) }
        .sortedWith(componentOrder)
}

/** [rows] of one [asset] as targets, each path built from the walk's branch above it. */
private fun targetsOn(asset: Asset, rows: List<InstalledComponent>): List<ComponentTarget> {
    // The names on the walk's current branch, the asset's first: a row at depth d sits under the first d + 1.
    val branch = mutableListOf(asset.name)
    return InstalledComponentTree.current(rows).map { (row, depth) ->
        while (branch.size > depth + 1) branch.removeAt(branch.lastIndex)
        val path = branch + row.name
        branch += row.name
        ComponentTarget(asset.id, row.id, path)
    }
}

/**
 * The Installed components list's order (C-11): the paths compared segment by segment, each casefolded, a path before
 * the longer ones it begins, then the component id. Never the joined line: a name holding the separator, or one
 * running on past a shorter sibling's ("Bay 10" beside "Bay"'s children), would sort out of its branch.
 */
internal val componentOrder: Comparator<ComponentTarget> =
    Comparator<ComponentTarget> { a, b -> comparePaths(a.path, b.path) }.thenBy { it.componentId.value }

private fun comparePaths(a: List<String>, b: List<String>): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val bySegment = a[i].lowercase().compareTo(b[i].lowercase())
        if (bySegment != 0) return bySegment
    }
    return a.size.compareTo(b.size)
}

/**
 * Whether [query] hits this row: the asset's name, any ancestor's, or its own — each [path] segment on its own, never
 * the joined line. The rule `Asset.matches` applies (`AssetSearch.kt`): the query trimmed, a blank one hitting every
 * row, otherwise a case-insensitive substring of one field.
 */
internal fun ComponentTarget.matches(query: String): Boolean = anyFieldHolds(path, query)

/**
 * Whether [query] hits this SupplyItem: its name, manufacturer, model or part number, by the rule
 * [ComponentTarget.matches] states.
 */
internal fun SupplyItem.matches(query: String): Boolean =
    anyFieldHolds(listOf(name, manufacturer, model, partNumber), query)

// `Asset.matches` keeps its trim-and-contains inline beside a private Asset field list, so there is nothing to import
// for another type; this is the same rule over the fields a caller names.
private fun anyFieldHolds(fields: List<String>, query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return fields.any { it.contains(needle, ignoreCase = true) }
}

/**
 * A SupplyItem's product line (C30, the owner's): manufacturer, model and part number joined by a middle dot, each
 * only when it is there; a part number equal to the model, casefolded, is not repeated. Empty when none is there.
 */
internal fun productLineOf(item: SupplyItem): String {
    val partNumber = item.partNumber.takeUnless { it.trim().lowercase() == item.model.trim().lowercase() }.orEmpty()
    return listOf(item.manufacturer, item.model, partNumber).filter { it.isNotBlank() }.joinToString(" · ")
}

/** [item] as the Supplies list's row. */
internal fun supplyTargetOf(item: SupplyItem): SupplyTarget =
    SupplyTarget(supplyId = item.id, name = item.name, productLine = productLineOf(item))

/**
 * The Installed components list for [query]: [componentTargets] narrowed by [ComponentTarget.matches], or why it is
 * empty.
 */
internal fun componentList(
    assets: Collection<Asset>,
    held: Set<AssetId>,
    components: Collection<InstalledComponent>,
    query: String,
): ShareTargetList<ComponentTarget> =
    listed(componentTargets(assets, held, components), query) { row, q -> row.matches(q) }

/**
 * The Supplies list for [query]: every [SupplyItem.isShareable] item in the Supplies screen's order (name casefolded,
 * then id), narrowed by [SupplyItem.matches], each as [supplyTargetOf]'s row — or why it is empty. Asset links play no
 * part: an item no asset names is offered, and one only a retired asset names is too.
 */
internal fun supplyList(items: Collection<SupplyItem>, query: String): ShareTargetList<SupplyTarget> {
    val eligible = items.filter { it.isShareable }.sortedWith(compareBy({ it.name.lowercase() }, { it.id.value }))
    return when (val listed = listed(eligible, query) { item, q -> item.matches(q) }) {
        ShareTargetList.NoneEligible -> ShareTargetList.NoneEligible
        ShareTargetList.NothingMatches -> ShareTargetList.NothingMatches
        is ShareTargetList.Rows -> ShareTargetList.Rows(listed.rows.map(::supplyTargetOf))
    }
}

/** The empty-versus-miss rule every direct list shares: [eligible] is the list before any query. */
private fun <T> listed(eligible: List<T>, query: String, hits: (T, String) -> Boolean): ShareTargetList<T> {
    if (eligible.isEmpty()) return ShareTargetList.NoneEligible
    val matched = eligible.filter { hits(it, query) }
    return if (matched.isEmpty()) ShareTargetList.NothingMatches else ShareTargetList.Rows(matched)
}
