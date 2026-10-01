package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteException
import androidx.sqlite.execSQL
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetSupply
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.model.SupplySpecification
import com.loosecannon.servicetag.data.room.entities.AssetEntity
import com.loosecannon.servicetag.data.room.entities.AssetSupplyEntity
import com.loosecannon.servicetag.data.room.entities.SupplyItemEntity
import com.loosecannon.servicetag.data.room.entities.SupplySpecificationEntity
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v18 constraints that carry a rule (#15, C5; R15-3, R15-5), asserted against a real database rather
 * than against the code that is supposed to respect them — the point of each is that it holds even when
 * the caller forgets:
 *
 *  - `UNIQUE(asset_id, supply_id, role)`: an Asset takes one SupplyItem in one role once;
 *  - `asset_supply.asset_id` CASCADE: an Asset's applicability goes with the Asset;
 *  - `asset_supply.supply_id` RESTRICT: a SupplyItem an Asset names cannot be deleted — there is no delete
 *    anywhere above the schema either (archive-only, R15-5), so the case speaks raw SQL;
 *  - `UNIQUE(supply_id, key)`: a specification key is unique within its SupplyItem, and only there;
 *  - the aggregate upsert replaces the specification list, the ids coming from the caller.
 *
 * Every name is fictional.
 */
class SupplyDaoConstraintTest {

    private fun asset(id: String) = AssetEntity(
        id = id, name = "Example RO System $id", description = "", category = "", notes = "",
        status = "ACTIVE", templateKey = null, createdAt = 1L, updatedAt = 1L,
    )

    private fun item(id: String, name: String = "Example Prefilter Cartridge") = SupplyItemEntity(
        id = id, name = name, category = "Filters", manufacturer = "Example Filters Co.", model = "PF-10",
        partNumber = "PF-10-5UM", preferredUnit = "ea", notes = "", archivedAt = null, createdAt = 10L,
        updatedAt = 20L,
    )

    private fun spec(id: String, supplyId: String, key: String, sortOrder: Int = 0) = SupplySpecificationEntity(
        id = id, supplyId = supplyId, key = key, label = key, value = "5", unit = "µm", sortOrder = sortOrder,
    )

    private fun applicability(id: String, assetId: String, supplyId: String, role: String) = AssetSupplyEntity(
        id = id, assetId = assetId, supplyId = supplyId, role = role, createdAt = 30L, updatedAt = 40L,
    )

    @Test
    fun theTripleIsUnique() = runTest {
        val db = inMemoryDb()
        try {
            db.assetDao().upsert(asset("a1"))
            db.assetDao().upsert(asset("a2"))
            db.supplyItemDao().upsert(item("s1"), emptyList())
            val dao = db.assetSupplyDao()

            dao.insert(applicability("as1", "a1", "s1", "Prefilter"))
            val thrown = runCatching { dao.insert(applicability("as2", "a1", "s1", "Prefilter")) }.exceptionOrNull()
            assertTrue("expected the UNIQUE (asset_id, supply_id, role) index to refuse, got $thrown", thrown is SQLiteException)

            // another role on the same pair is a second row, and so is the same role on another asset
            dao.insert(applicability("as3", "a1", "s1", "Spare prefilter"))
            dao.insert(applicability("as4", "a2", "s1", "Prefilter"))

            assertEquals(listOf("as1", "as3", "as4"), dao.all().map { it.id })
        } finally {
            db.close()
        }
    }

    @Test
    fun deletingAnAssetCascadesItsApplicability() = runTest {
        val db = inMemoryDb()
        try {
            db.assetDao().upsert(asset("a1"))
            db.assetDao().upsert(asset("a2"))
            db.supplyItemDao().upsert(item("s1"), emptyList())
            db.assetSupplyDao().insert(applicability("as1", "a1", "s1", "Prefilter"))
            db.assetSupplyDao().insert(applicability("as2", "a2", "s1", "Prefilter"))

            db.assetDao().delete("a1")

            assertEquals(listOf("as2"), db.assetSupplyDao().all().map { it.id })
            // the SupplyItem is catalog, not the Asset's: it stays
            assertEquals(listOf("s1"), db.supplyItemDao().all().map { it.item.id })
        } finally {
            db.close()
        }
    }

    /**
     * RESTRICT, in raw SQL: nothing above the schema deletes a SupplyItem (R15-5), so the only way to ask
     * is a statement no DAO carries. The row an Asset names stays; one nobody names goes, its
     * specifications with it (their CASCADE); and once the naming row is gone the first goes too.
     */
    @Test
    fun aSupplyItemNamedByApplicabilityCannotBeDeleted() = runTest {
        val file = File.createTempFile("servicetag-supply-restrict", ".db").also { it.delete() }
        try {
            val db = fileBackedDb(file)
            try {
                db.assetDao().upsert(asset("a1"))
                db.supplyItemDao().upsert(item("s1"), listOf(spec("sp1", "s1", "micron_rating")))
                db.supplyItemDao().upsert(item("s2", "Example Carbon Block"), listOf(spec("sp2", "s2", "micron_rating")))
                db.assetSupplyDao().insert(applicability("as1", "a1", "s1", "Prefilter"))
            } finally {
                db.close()
            }
            withConnection(file) { c ->
                c.execSQL("PRAGMA foreign_keys = ON")
                val thrown = runCatching { c.execSQL("DELETE FROM supply_item WHERE id = 's1'") }.exceptionOrNull()
                assertTrue("expected the RESTRICT foreign key to refuse, got $thrown", thrown is SQLiteException)
                assertEquals(listOf("s1|1"), c.lines("SELECT id, (SELECT COUNT(*) FROM supply_specification WHERE supply_id = 's1') FROM supply_item WHERE id = 's1'"))

                c.execSQL("DELETE FROM supply_item WHERE id = 's2'")
                assertEquals(listOf("0"), c.lines("SELECT COUNT(*) FROM supply_specification WHERE supply_id = 's2'"))

                c.execSQL("DELETE FROM asset_supply WHERE id = 'as1'")
                c.execSQL("DELETE FROM supply_item WHERE id = 's1'")
                assertEquals(listOf("0|0"), c.lines("SELECT (SELECT COUNT(*) FROM supply_item), (SELECT COUNT(*) FROM supply_specification)"))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun specificationKeysAreUniquePerItem() = runTest {
        val db = inMemoryDb()
        try {
            val dao = db.supplyItemDao()
            dao.upsert(item("s1"), listOf(spec("sp1", "s1", "micron_rating")))

            // one key twice in one item is refused, and the whole upsert with it
            val thrown = runCatching {
                dao.upsert(
                    item("s1", "Example Prefilter Cartridge, renamed"),
                    listOf(spec("sp1", "s1", "micron_rating"), spec("sp2", "s1", "micron_rating", 1)),
                )
            }.exceptionOrNull()
            assertTrue("expected the UNIQUE (supply_id, key) index to refuse, got $thrown", thrown is SQLiteException)
            val kept = dao.byId("s1")!!
            assertEquals("Example Prefilter Cartridge", kept.item.name)
            assertEquals(listOf("sp1"), kept.specifications.map { it.id })

            // the same key on another item is ordinary
            dao.upsert(item("s2", "Example Carbon Block"), listOf(spec("sp3", "s2", "micron_rating")))
            assertEquals(listOf("sp3"), dao.byId("s2")!!.specifications.map { it.id })
        } finally {
            db.close()
        }
    }

    /**
     * The aggregate upsert through the adapter: the second write keeps one row by its id (its label and
     * value edited, its key unchanged), drops one and adds one; the item's own fields are replaced; and the
     * read comes back in `(sortOrder, id)` order whatever order the rows were handed down in.
     */
    @Test
    fun theUpsertReplacesTheSpecificationList() = runTest {
        val db = inMemoryDb()
        try {
            val repo = RoomSupplyItemRepository(db.supplyItemDao())
            val id = SupplyId("s1")
            val first = SupplyItem(
                id = id, name = "Example Prefilter Cartridge", category = "Filters",
                manufacturer = "Example Filters Co.", model = "PF-10", partNumber = "PF-10-5UM",
                preferredUnit = "ea", notes = "", archivedAt = null, createdAt = 10L, updatedAt = 10L,
                specifications = listOf(
                    SupplySpecification("sp1", "micron_rating", "Micron rating", "5", "µm", 0),
                    SupplySpecification("sp2", "length", "Length", "10", "in", 1),
                ),
            )
            repo.upsert(first)
            assertEquals(first, repo.get(id))

            val second = first.copy(
                name = "Example Prefilter Cartridge 5 µm",
                notes = "Change with the post-filter.",
                updatedAt = 20L,
                specifications = listOf(
                    SupplySpecification("sp3", "thread", "Thread", "3/8 in", "", 1),
                    SupplySpecification("sp1", "micron_rating", "Rating", "1", "µm", 0),
                ),
            )
            repo.upsert(second)

            val read = repo.get(id)!!
            assertEquals(listOf("sp1", "sp3"), read.specifications.map { it.id })
            assertEquals(second.copy(specifications = second.specifications.sortedBy { it.sortOrder }), read)
            assertEquals(listOf("sp1", "sp3"), db.supplyItemDao().byId("s1")!!.specifications.map { it.id }.sorted())
            assertEquals(listOf(id), repo.all().map { it.id })
        } finally {
            db.close()
        }
    }

    /** Archive is one column and the stamp: nothing else on the row, and no specification, moves. */
    @Test
    fun setArchivedWritesTheArchiveStampAndNothingElse() = runTest {
        val db = inMemoryDb()
        try {
            val repo = RoomSupplyItemRepository(db.supplyItemDao())
            db.supplyItemDao().upsert(item("s1"), listOf(spec("sp1", "s1", "micron_rating")))
            val before = repo.get(SupplyId("s1"))!!

            repo.setArchived(SupplyId("s1"), archivedAt = 50L, updatedAt = 50L)
            assertEquals(before.copy(archivedAt = 50L, updatedAt = 50L), repo.get(SupplyId("s1")))

            repo.setArchived(SupplyId("s1"), archivedAt = null, updatedAt = 60L)
            assertEquals(before.copy(archivedAt = null, updatedAt = 60L), repo.get(SupplyId("s1")))
        } finally {
            db.close()
        }
    }

    /**
     * The applicability adapter: insert, update (the role), delete, and its three reads — an Asset's rows by
     * `(role, id)`, a SupplyItem's by `(assetId, role, id)`, and every row by id.
     */
    @Test
    fun theApplicabilityAdapterWritesAndReadsInItsOrders() = runTest {
        val db = inMemoryDb()
        try {
            db.assetDao().upsert(asset("a1"))
            db.assetDao().upsert(asset("a2"))
            db.supplyItemDao().upsert(item("s1"), emptyList())
            db.supplyItemDao().upsert(item("s2", "Example Carbon Block"), emptyList())
            val repo = RoomAssetSupplyRepository(db.assetSupplyDao())
            fun row(id: String, asset: String, supply: String, role: String) =
                AssetSupply(id, AssetId(asset), SupplyId(supply), role, 30L, 40L)

            repo.insert(row("as3", "a2", "s1", "Prefilter"))
            repo.insert(row("as1", "a1", "s2", "Post-filter"))
            repo.insert(row("as2", "a1", "s1", "Prefilter"))
            repo.insert(row("as4", "a1", "s1", "Spare"))

            assertEquals(listOf("as1", "as2", "as4"), repo.forAsset(AssetId("a1")).map { it.id })
            assertEquals(listOf("as2", "as4", "as3"), repo.forSupply(SupplyId("s1")).map { it.id })
            assertEquals(listOf("as1", "as2", "as3", "as4"), repo.all().map { it.id })

            val reRoled = row("as4", "a1", "s1", "Backup prefilter").copy(updatedAt = 50L)
            repo.update(reRoled)
            assertEquals(reRoled, repo.get("as4"))

            repo.delete("as4")
            assertNull(repo.get("as4"))
            assertEquals(listOf("as1", "as2", "as3"), repo.all().map { it.id })
        } finally {
            db.close()
        }
    }

    /** Each row of [sql] as its columns joined by `|`. */
    private fun androidx.sqlite.SQLiteConnection.lines(sql: String): List<String> = buildList {
        prepare(sql).use { s ->
            while (s.step()) add((0 until s.getColumnCount()).joinToString("|") { if (s.isNull(it)) "NULL" else s.getText(it) })
        }
    }
}
