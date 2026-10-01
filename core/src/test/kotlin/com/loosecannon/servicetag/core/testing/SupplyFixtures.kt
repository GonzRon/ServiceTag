package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetSupply
import com.loosecannon.servicetag.core.model.ConsumableUsage
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.ProfileConsumable
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.model.SupplySpecification
import com.loosecannon.servicetag.core.ports.AssetSupplyRepository
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** #15 (B2a) — one SupplyItem with fictional defaults and the specifications given. */
fun supplyItemOf(
    id: String,
    name: String = "Example Prefilter Cartridge",
    specifications: List<SupplySpecification> = emptyList(),
    archivedAt: Long? = null,
    updatedAt: Long = 2_000L,
) = SupplyItem(
    id = SupplyId(id), name = name, category = "Filter", manufacturer = "Example Filters Co.", model = "PF-10",
    partNumber = "EF-PF10-5", preferredUnit = "ea", notes = "", archivedAt = archivedAt, createdAt = 1_000L,
    updatedAt = updatedAt, specifications = specifications,
)

/** One specification row; the key is given as stored. */
fun specificationOf(id: String, key: String, label: String, value: String, unit: String = "", sortOrder: Int = 0) =
    SupplySpecification(id = id, key = key, label = label, value = value, unit = unit, sortOrder = sortOrder)

/** One applicability row: [assetId] takes [supplyId] in [role]. */
fun assetSupplyOf(id: String, assetId: String, supplyId: String, role: String = "Prefilter") =
    AssetSupply(id, AssetId(assetId), SupplyId(supplyId), role, createdAt = 1_000L, updatedAt = 2_000L)

/**
 * #15's applicability, as the port is. [cascadeFromAsset] is the schema's `asset_id` CASCADE, for an asset
 * double that registers it.
 */
class InMemoryAssetSupplyRepository : AssetSupplyRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, AssetSupply>()
    override var witness: TransactionWitness? = null
    private val version = MutableStateFlow(0)

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun get(id: String): AssetSupply? = rows[id]
    override suspend fun forAsset(assetId: AssetId): List<AssetSupply> =
        rows.values.filter { it.assetId == assetId }.sortedWith(compareBy({ it.role }, { it.id }))
    override suspend fun forSupply(supplyId: SupplyId): List<AssetSupply> =
        rows.values.filter { it.supplyId == supplyId }.sortedWith(compareBy({ it.assetId.value }, { it.role }, { it.id }))

    override suspend fun all(): List<AssetSupply> {
        witness?.observeAll()
        return rows.values.sortedBy { it.id }
    }

    override suspend fun insert(row: AssetSupply) {
        if (row.id in rows) throw RiggedFailure("asset_supply already holds ${row.id}")
        rows[row.id] = row
        version.value += 1
    }

    override suspend fun update(row: AssetSupply) { rows[row.id] = row; version.value += 1 }
    override suspend fun delete(id: String) { rows.remove(id); version.value += 1 }

    override fun observeForAsset(assetId: AssetId): Flow<List<AssetSupply>> = version.map {
        rows.values.filter { it.assetId == assetId }.sortedWith(compareBy({ it.role }, { it.id }))
    }

    fun cascadeFromAsset(assetId: AssetId) {
        if (rows.values.removeAll { it.assetId == assetId }) version.value += 1
    }
}

/**
 * #15's catalog, as the port is: no delete but the replace wipe. [applicability], when given, is the schema's
 * RESTRICT on `asset_supply.supply_id`: [deleteAll] is refused while any applicability row remains.
 */
class InMemorySupplyItemRepository(
    private val applicability: InMemoryAssetSupplyRepository? = null,
) : SupplyItemRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<SupplyId, SupplyItem>()
    override var witness: TransactionWitness? = null
    private val version = MutableStateFlow(0)

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun get(id: SupplyId): SupplyItem? = rows[id]

    override suspend fun all(): List<SupplyItem> {
        witness?.observeAll()
        return rows.values.sortedBy { it.id.value }
    }

    override suspend fun upsert(item: SupplyItem) {
        rows[item.id] = item.copy(specifications = item.specifications.sortedWith(compareBy({ it.sortOrder }, { it.id })))
        version.value += 1
    }

    override suspend fun setArchived(id: SupplyId, archivedAt: Long?, updatedAt: Long) {
        rows[id]?.let { rows[id] = it.copy(archivedAt = archivedAt, updatedAt = updatedAt) }
        version.value += 1
    }

    override suspend fun deleteAll() {
        applicability?.rows?.values?.firstOrNull()?.let {
            throw RiggedFailure("asset_supply ${it.id} still names supply item ${it.supplyId.value} (RESTRICT)")
        }
        rows.clear()
        version.value += 1
    }

    override fun observeAll(): Flow<List<SupplyItem>> = version.map {
        rows.values.sortedWith(compareBy({ it.name.lowercase() }, { it.id.value }))
    }
}

/**
 * #15 (B2a) — one small estate with every format-18 shape: two assets, a SupplyItem with two specifications and an
 * archived one with one, an applicability row naming each, a quick action with a linked and an unlinked line, and
 * an event with a line linked to each item and one unlinked. Specifications are in `(sortOrder, id)` order, as a
 * decode returns them. The names are fictional.
 */
object SupplyEstate {
    val system = plainAssetOf("x1", "Example RO System")
    val softener = plainAssetOf("x2", "Example Water Softener")

    val prefilter = supplyItemOf(
        "s1", "Example Prefilter Cartridge",
        specifications = listOf(
            specificationOf("sp1", "length", "Length", "10", "in", sortOrder = 0),
            specificationOf("sp2", "micron_rating", "Micron rating", "5", "µm", sortOrder = 1),
        ),
    )
    val membrane = supplyItemOf(
        "s2", "Example RO Membrane", archivedAt = 3_000L,
        specifications = listOf(specificationOf("sp3", "capacity", "Capacity", "75", "gpd")),
    )

    val prefilterOnSystem = assetSupplyOf("as1", "x1", "s1", "Prefilter")
    val membraneOnSoftener = assetSupplyOf("as2", "x2", "s2", "Membrane")

    val quickAction = EventProfile(
        id = ProfileId("p1"), assetId = AssetId("x1"), name = "Example prefilter change", eventKind = EventKind.REPLACEMENT,
        defaultTitle = "Prefilter change", templateKey = null, sortOrder = 0, archivedAt = null, createdAt = 1_000L,
        updatedAt = 2_000L, fields = emptyList(),
        consumables = listOf(
            ProfileConsumable("pc1", "Example Prefilter Cartridge", 1.0, "ea", 0, supplyId = SupplyId("s1")),
            ProfileConsumable("pc2", "Example sealing ring", null, "", 1, supplyId = null),
        ),
    )
    val change = AssetEvent(
        id = EventId("e1"), assetId = AssetId("x1"), kind = EventKind.REPLACEMENT, title = "Prefilter change",
        profileId = ProfileId("p1"), occurredOn = "2026-09-20", occurredTime = null, tzId = "UTC", notes = "",
        source = EventSource.MANUAL, sourceRef = null, createdAt = 1_000L, updatedAt = 2_000L,
        measurements = emptyList(),
        consumables = listOf(
            ConsumableUsage("cu1", "Example Prefilter Cartridge", 1.0, "ea", 0, supplyId = SupplyId("s1")),
            ConsumableUsage("cu2", "Example RO Membrane", 1.0, "ea", 1, supplyId = SupplyId("s2")),
            ConsumableUsage("cu3", "Example sealing ring", 2.0, "ea", 2, supplyId = null),
        ),
    )

    fun data(): BackupData = BackupData(
        assets = listOf(system, softener).map { it.toDto() },
        nfcTags = emptyList(),
        externalLinks = emptyList(),
        eventProfiles = listOf(quickAction.toDto()),
        assetEvents = listOf(change.toDto()),
        supplyItems = listOf(prefilter, membrane).map { it.toDto() },
        assetSupplies = listOf(prefilterOnSystem, membraneOnSoftener).map { it.toDto() },
    )
}
