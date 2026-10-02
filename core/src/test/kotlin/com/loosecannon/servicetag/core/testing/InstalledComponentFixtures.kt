package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompositionEntry
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.ports.InstalledComponentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** #47 (B1a) — one installed component with fictional defaults: a current top-level row with no link or composition. */
fun installedComponentOf(
    id: String,
    assetId: String = "x1",
    name: String = "Example Battery Tray",
    parentId: String? = null,
    supplyId: String? = null,
    composition: List<CompositionEntry> = emptyList(),
    serialOrLot: String = "",
    installedOn: String? = null,
    removedOn: String? = null,
    replacesId: String? = null,
    sortOrder: Int = 0,
    notes: String = "",
    createdAt: Long = 1_000L,
    updatedAt: Long = 2_000L,
) = InstalledComponent(
    id = InstalledComponentId(id), assetId = AssetId(assetId), parentId = parentId?.let(::InstalledComponentId),
    name = name, supplyId = supplyId?.let(::SupplyId), composition = composition, serialOrLot = serialOrLot,
    installedOn = installedOn, removedOn = removedOn, replacesId = replacesId?.let(::InstalledComponentId),
    sortOrder = sortOrder, notes = notes, createdAt = createdAt, updatedAt = updatedAt,
)

/** One composition entry: [quantity] of [supplyId] in [unit]. */
fun compositionEntryOf(id: String, supplyId: String, quantity: Double = 1.0, unit: String = "ea", sortOrder: Int = 0) =
    CompositionEntry(id = id, supplyId = SupplyId(supplyId), quantity = quantity, unit = unit, sortOrder = sortOrder)

/**
 * #47 (C5, C-2) — installed components as the port is, and as the two tables refuse. Core doubles enforce no
 * foreign key unless they model one, so this one models the schema's: on [insert] and [update], the parent must
 * already be stored **on the same asset**; a non-null direct `supplyId` and every entry's `supplyId` must name a
 * SupplyItem [supplyItemExists] knows; an entry id is held by one row only; a `replacesId` by one row only; a row
 * id once. Each violation is an [IllegalStateException], as an FK failure is, and nothing is written.
 *
 * Each entry is stored as its own keyed record in [entries] — as `installed_component_composition` holds it — so an
 * entry that moves to another row or is re-used is observable; [rows] holds each row with its composition taken
 * out, and every read puts it back in `(sortOrder, id)` order. There is no delete: [cascadeFromAsset] is the
 * schema's `asset_id` CASCADE, for an asset double that registers it ([BackupInstall] does), and it takes the
 * asset's rows and their entries (a parent is on the same asset, so its CASCADE comes with the asset's).
 *
 * An [update] of a row that is not stored writes no row, as an SQL `UPDATE` matching nothing does; its entries
 * would name no row, so a non-empty composition throws.
 */
class InMemoryInstalledComponentRepository(
    private val supplyItemExists: (SupplyId) -> Boolean,
) : InstalledComponentRepository, Rollbackable, Witnessed {

    /** Over #15's catalog double: a SupplyItem exists when the catalog holds it, archived or not. */
    constructor(catalog: InMemorySupplyItemRepository) : this({ it in catalog.rows })

    /** One `installed_component_composition` record: the entry and the row it belongs to. */
    data class StoredEntry(val componentId: InstalledComponentId, val entry: CompositionEntry)

    /** Each row as stored, its composition held apart in [entries]. */
    val rows = LinkedHashMap<InstalledComponentId, InstalledComponent>()

    /** Each composition entry by its id. */
    val entries = LinkedHashMap<String, StoredEntry>()

    override var witness: TransactionWitness? = null
    private val version = MutableStateFlow(0)

    override fun snapshot(): () -> Unit {
        val rowsCopy = LinkedHashMap(rows)
        val entriesCopy = LinkedHashMap(entries)
        return {
            rows.clear(); rows.putAll(rowsCopy)
            entries.clear(); entries.putAll(entriesCopy)
            version.value += 1
        }
    }

    override suspend fun get(id: InstalledComponentId): InstalledComponent? = rows[id]?.let(::assembled)

    override suspend fun forAsset(assetId: AssetId): List<InstalledComponent> = rowsOf(assetId)

    override suspend fun all(): List<InstalledComponent> {
        witness?.observeAll()
        return rows.values.sortedBy { it.id.value }.map(::assembled)
    }

    override suspend fun insert(row: InstalledComponent) {
        check(row.id !in rows) { "installed_component already holds ${row.id.value}" }
        checkConstraints(row)
        store(row)
    }

    override suspend fun update(row: InstalledComponent) {
        if (row.id !in rows) {
            check(row.composition.isEmpty()) { "installed_component_composition names ${row.id.value}, which is not stored (FK)" }
            return
        }
        checkConstraints(row)
        entries.values.removeAll { it.componentId == row.id }
        store(row)
    }

    override fun observeForAsset(assetId: AssetId): Flow<List<InstalledComponent>> = version.map { rowsOf(assetId) }

    /** The schema's `asset_id` CASCADE: the asset's rows go, and their entries with them. */
    fun cascadeFromAsset(assetId: AssetId) {
        val gone = rows.values.filter { it.assetId == assetId }.mapTo(HashSet()) { it.id }
        if (gone.isEmpty()) return
        rows.keys.removeAll(gone)
        entries.values.removeAll { it.componentId in gone }
        version.value += 1
    }

    private fun checkConstraints(row: InstalledComponent) {
        row.parentId?.let { parentId ->
            val parent = checkNotNull(rows[parentId]) {
                "installed_component ${row.id.value} names parent ${parentId.value}, which is not stored (FK)"
            }
            check(parent.assetId == row.assetId) {
                "installed_component ${row.id.value} names parent ${parentId.value}, which is on another asset"
            }
        }
        row.supplyId?.let { supplyId ->
            check(supplyItemExists(supplyId)) { "installed_component ${row.id.value} names supply item ${supplyId.value}, which is not stored (FK)" }
        }
        val seen = HashSet<String>()
        row.composition.forEach { entry ->
            check(supplyItemExists(entry.supplyId)) {
                "installed_component_composition ${entry.id} names supply item ${entry.supplyId.value}, which is not stored (FK)"
            }
            check(seen.add(entry.id)) { "installed_component_composition holds ${entry.id} twice in one write" }
            val holder = entries[entry.id]?.componentId
            check(holder == null || holder == row.id) {
                "installed_component_composition already holds ${entry.id} for ${holder?.value}"
            }
        }
        row.replacesId?.let { replacesId ->
            val other = rows.values.firstOrNull { it.replacesId == replacesId && it.id != row.id }
            check(other == null) { "installed_component ${other?.id?.value} already replaces ${replacesId.value} (UNIQUE)" }
        }
    }

    private fun store(row: InstalledComponent) {
        rows[row.id] = row.copy(composition = emptyList())
        row.composition.forEach { entries[it.id] = StoredEntry(row.id, it) }
        version.value += 1
    }

    private fun assembled(row: InstalledComponent): InstalledComponent = row.copy(
        composition = entries.values.filter { it.componentId == row.id }.map { it.entry }
            .sortedWith(compareBy({ it.sortOrder }, { it.id })),
    )

    private fun rowsOf(assetId: AssetId): List<InstalledComponent> =
        rows.values.filter { it.assetId == assetId }.sortedBy { it.id.value }.map(::assembled)
}
