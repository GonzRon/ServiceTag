package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.execSQL
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompositionEntry
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.data.room.entities.AssetEntity
import com.loosecannon.servicetag.data.room.entities.InstalledComponentCompositionEntity
import com.loosecannon.servicetag.data.room.entities.SupplyItemEntity
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v19 constraints that carry a rule (#47, C6, C8; R47-2, R47-3; H1), asserted against a real database rather
 * than against the code that is supposed to respect them — the point of each is that it holds even when the caller
 * forgets — and the aggregate port's reads and writes through the Room adapter:
 *
 *  - H1: `asset_id`, `parent_id` and `component_id` are CASCADE, so an Asset delete — one row, or the replace
 *    import's children-first wipe — takes a three-deep tree and every entry at every depth, and leaves the
 *    SupplyItems;
 *  - both `supply_id` keys are RESTRICT: a SupplyItem a row names directly, or an entry names, cannot be deleted —
 *    there is no delete above the schema either (archive-only, R15-5), so the case speaks raw SQL;
 *  - `UNIQUE(replaces_id)`: one successor per row, any number of first fittings; and no foreign key on it;
 *  - an unknown asset, parent or SupplyItem is refused;
 *  - the composition is written with its row, read in `(sortOrder, id)` order and replaced whole by an update that
 *    is an SQL `UPDATE`, so a row's subtree survives its edit (N-4).
 *
 * Every name is fictional.
 */
class InstalledComponentDaoConstraintTest {

    private fun asset(id: String) = AssetEntity(
        id = id, name = "Example UPS $id", description = "", category = "", notes = "",
        status = "ACTIVE", templateKey = null, createdAt = 1L, updatedAt = 1L,
    )

    private fun item(id: String, name: String) = SupplyItemEntity(
        id = id, name = name, category = "Batteries", manufacturer = "Example Power Co.", model = "EP-$id",
        partNumber = "EP-$id-1", preferredUnit = "ea", notes = "", archivedAt = null, createdAt = 10L,
        updatedAt = 20L,
    )

    private fun entry(id: String, supplyId: String, quantity: Double = 1.0, unit: String = "ea", sortOrder: Int = 0) =
        CompositionEntry(id = id, supplyId = SupplyId(supplyId), quantity = quantity, unit = unit, sortOrder = sortOrder)

    private fun component(
        id: String,
        assetId: String = "a1",
        parentId: String? = null,
        name: String = "Example Battery Tray",
        supplyId: String? = null,
        composition: List<CompositionEntry> = emptyList(),
        installedOn: String? = "2026-03-01",
        removedOn: String? = null,
        replacesId: String? = null,
        sortOrder: Int = 0,
        updatedAt: Long = 40L,
    ) = InstalledComponent(
        id = InstalledComponentId(id), assetId = AssetId(assetId), parentId = parentId?.let(::InstalledComponentId),
        name = name, supplyId = supplyId?.let(::SupplyId), composition = composition, serialOrLot = "",
        installedOn = installedOn, removedOn = removedOn, replacesId = replacesId?.let(::InstalledComponentId),
        sortOrder = sortOrder, notes = "", createdAt = 30L, updatedAt = updatedAt,
    )

    /** Two assets and three SupplyItems: a battery, a tray SKU and a terminal bolt. */
    private suspend fun seedCatalog(db: AppDatabase) {
        db.assetDao().upsert(asset("a1"))
        db.assetDao().upsert(asset("a2"))
        db.supplyItemDao().upsert(item("s1", "Example 12 V Battery"), emptyList())
        db.supplyItemDao().upsert(item("s2", "Example Battery Tray"), emptyList())
        db.supplyItemDao().upsert(item("s3", "Example Terminal Bolt"), emptyList())
    }

    /**
     * Three deep on `a1`, an entry at every depth, written parents first: the tray (`c1`, a tray SKU and two bolts),
     * a position inside it (`c2`, a battery and a bolt), a pack inside that (`c3`, `4 ×` the battery); a removed
     * position and its successor beside `c2`; and one row on `a2` that must outlive `a1`'s delete.
     */
    private suspend fun seedTree(repo: RoomInstalledComponentRepository) {
        repo.insert(component("c1", supplyId = "s2", composition = listOf(entry("e1", "s3", 2.0))))
        repo.insert(component("c2", parentId = "c1", name = "Position 1", supplyId = "s1", composition = listOf(entry("e2", "s3"))))
        repo.insert(component("c3", parentId = "c2", name = "Example Battery Pack", composition = listOf(entry("e3", "s1", 4.0))))
        repo.insert(component("c4", parentId = "c1", name = "Position 2", supplyId = "s1", removedOn = "2026-05-01", sortOrder = 1))
        repo.insert(component("c5", parentId = "c1", name = "Position 2", supplyId = "s1", replacesId = "c4", sortOrder = 1,
            composition = listOf(entry("e5", "s3"))))
        repo.insert(component("d1", assetId = "a2", composition = listOf(entry("e9", "s1", 4.0))))
    }

    // --- H1 and the constraints (row 7) -------------------------------------------------------------

    /**
     * H1, on Room: deleting `a1` takes its whole tree — three deep, a removed row and its successor included — and
     * every composition entry at every depth, counted in the tables themselves; `a2`'s row and entry stay, and so do
     * all three SupplyItems. A RESTRICT `parent_id` would refuse the tray the asset's CASCADE reaches first.
     */
    @Test
    fun deletingAnAssetCascadesAThreeDeepTreeAndEveryEntry() = runTest {
        val file = File.createTempFile("servicetag-installed-cascade", ".db").also { it.delete() }
        try {
            val db = fileBackedDb(file)
            try {
                seedCatalog(db)
                val repo = RoomInstalledComponentRepository(db.installedComponentDao())
                seedTree(repo)
                assertEquals(listOf("c1", "c2", "c3", "c4", "c5"), repo.forAsset(AssetId("a1")).map { it.id.value })

                db.assetDao().delete("a1")

                assertEquals(emptyList<InstalledComponent>(), repo.forAsset(AssetId("a1")))
                assertEquals(listOf("d1"), repo.all().map { it.id.value })
                assertEquals(listOf("e9"), repo.all().flatMap { row -> row.composition.map { it.id } })
            } finally {
                db.close()
            }
            withConnection(file) { c ->
                assertEquals(listOf("d1|a2"), c.lines("SELECT id, asset_id FROM installed_component ORDER BY id"))
                assertEquals(listOf("e9|d1"), c.lines("SELECT id, component_id FROM installed_component_composition ORDER BY id"))
                assertEquals(listOf("s1", "s2", "s3"), c.lines("SELECT id FROM supply_item ORDER BY id"))
            }
        } finally {
            file.delete()
        }
    }

    /**
     * The replace import's wipe (`assets.deleteAll()`, children-first over the Asset tree) takes every installed
     * component on every asset and every entry; the catalog's own wipe then goes through — the two RESTRICTs never
     * fire, because the rows that name a SupplyItem left with their assets first.
     */
    @Test
    fun theReplaceWipeTakesTheTree() = runTest {
        val db = inMemoryDb()
        try {
            seedCatalog(db)
            val repo = RoomInstalledComponentRepository(db.installedComponentDao())
            seedTree(repo)
            val supplies = RoomSupplyItemRepository(db.supplyItemDao())

            val refused = runCatching { supplies.deleteAll() }.exceptionOrNull()
            assertTrue("expected the RESTRICT foreign keys to refuse the catalog wipe first, got $refused", refused is SQLiteException)

            RoomAssetRepository(db.assetDao()).deleteAll()

            assertEquals(emptyList<InstalledComponent>(), repo.all())
            assertEquals(emptyList<InstalledComponent>(), repo.forAsset(AssetId("a2")))
            supplies.deleteAll()
            assertEquals(0, supplies.all().size)
        } finally {
            db.close()
        }
    }

    /**
     * Both RESTRICTs, in raw SQL: nothing above the schema deletes a SupplyItem (R15-5), so the only way to ask is a
     * statement no DAO carries. `s1` is named only by a row's direct link, `s3` only by an entry, `s2` by nobody: the
     * first two stay and the third goes; once `a1` is gone — its rows and their entry by the CASCADE — so do the
     * other two.
     */
    @Test
    fun aSupplyItemNamedDirectlyOrByAnEntryCannotBeDeleted() = runTest {
        val file = File.createTempFile("servicetag-installed-restrict", ".db").also { it.delete() }
        try {
            val db = fileBackedDb(file)
            try {
                seedCatalog(db)
                val repo = RoomInstalledComponentRepository(db.installedComponentDao())
                repo.insert(component("c1", supplyId = "s1"))
                repo.insert(component("c2", name = "Example Battery Pack", composition = listOf(entry("e1", "s3", 4.0))))
            } finally {
                db.close()
            }
            withConnection(file) { c ->
                c.execSQL("PRAGMA foreign_keys = ON")
                val direct = runCatching { c.execSQL("DELETE FROM supply_item WHERE id = 's1'") }.exceptionOrNull()
                assertTrue("expected the row's RESTRICT to refuse, got $direct", direct is SQLiteException)
                val byEntry = runCatching { c.execSQL("DELETE FROM supply_item WHERE id = 's3'") }.exceptionOrNull()
                assertTrue("expected the entry's RESTRICT to refuse, got $byEntry", byEntry is SQLiteException)
                c.execSQL("DELETE FROM supply_item WHERE id = 's2'")
                assertEquals(listOf("s1", "s3"), c.lines("SELECT id FROM supply_item ORDER BY id"))

                c.execSQL("DELETE FROM asset WHERE id = 'a1'")
                c.execSQL("DELETE FROM supply_item WHERE id IN ('s1', 's3')")
                assertEquals(
                    listOf("0|0|0"),
                    c.lines(
                        "SELECT (SELECT COUNT(*) FROM supply_item), (SELECT COUNT(*) FROM installed_component), " +
                            "(SELECT COUNT(*) FROM installed_component_composition)",
                    ),
                )
            }
        } finally {
            file.delete()
        }
    }

    /**
     * `UNIQUE(replaces_id)`: a second successor of one row is refused, and nothing is written; any number of rows
     * name nothing (SQLite admits repeated NULLs); and a `replaces_id` naming no row is stored — it is soft.
     */
    @Test
    fun replacesIdIsUniqueAndNullsRepeat() = runTest {
        val db = inMemoryDb()
        try {
            seedCatalog(db)
            val repo = RoomInstalledComponentRepository(db.installedComponentDao())
            repo.insert(component("c1", removedOn = "2026-05-01"))
            repo.insert(component("c2", replacesId = "c1"))

            val thrown = runCatching {
                repo.insert(component("c3", replacesId = "c1", composition = listOf(entry("e3", "s1"))))
            }.exceptionOrNull()
            assertTrue("expected the UNIQUE (replaces_id) index to refuse, got $thrown", thrown is SQLiteException)
            assertNull(repo.get(InstalledComponentId("c3")))

            repo.insert(component("c4", name = "Position 1"))
            repo.insert(component("c5", name = "Position 2"))
            repo.insert(component("c6", name = "Position 3", replacesId = "never-stored"))

            assertEquals(
                listOf("c1" to null, "c2" to "c1", "c4" to null, "c5" to null, "c6" to "never-stored"),
                repo.all().map { it.id.value to it.replacesId?.value },
            )
        } finally {
            db.close()
        }
    }

    /**
     * The other foreign keys, each refusing on insert with nothing written: an asset, a parent, a direct SupplyItem or
     * an entry's SupplyItem that names no row. The entry's refusal rolls its row back with it.
     */
    @Test
    fun anUnknownParentAssetOrSupplyItemIsRefused() = runTest {
        val db = inMemoryDb()
        try {
            seedCatalog(db)
            val repo = RoomInstalledComponentRepository(db.installedComponentDao())
            val refusals = listOf(
                component("x1", assetId = "no-such-asset"),
                component("x2", parentId = "no-such-row"),
                component("x3", supplyId = "no-such-item"),
                component("x4", composition = listOf(entry("e4", "s1"), entry("e5", "no-such-item", sortOrder = 1))),
            )
            for (row in refusals) {
                val thrown = runCatching { repo.insert(row) }.exceptionOrNull()
                assertTrue("expected a foreign key to refuse ${row.id.value}, got $thrown", thrown is SQLiteException)
            }
            assertEquals(emptyList<InstalledComponent>(), repo.all())

            repo.insert(component("c1"))
            repo.insert(component("c2", parentId = "c1", supplyId = "s1", composition = listOf(entry("e1", "s1"))))
            assertEquals(listOf("c1", "c2"), repo.all().map { it.id.value })
        } finally {
            db.close()
        }
    }

    // --- the aggregate port (row 8) -----------------------------------------------------------------

    /**
     * The row and its entries in one insert, read back whole in `(sortOrder, id)` order whatever order they were
     * handed down in; one SupplyItem twice is two entries, and an entry may name the row's own direct SupplyItem.
     */
    @Test
    fun insertWritesTheEntriesInOrder() = runTest {
        val db = inMemoryDb()
        try {
            seedCatalog(db)
            val repo = RoomInstalledComponentRepository(db.installedComponentDao())
            val pack = component(
                "c1", name = "Example Battery Pack", supplyId = "s1",
                composition = listOf(
                    entry("e3", "s3", 8.0, sortOrder = 2),
                    entry("e2b", "s1", 2.0, sortOrder = 1),
                    entry("e1", "s1", 2.0, sortOrder = 0),
                    entry("e2a", "s2", 0.5, unit = "", sortOrder = 1),
                ),
            )
            repo.insert(pack)

            val read = repo.get(InstalledComponentId("c1"))!!
            assertEquals(listOf("e1", "e2a", "e2b", "e3"), read.composition.map { it.id })
            assertEquals(pack.copy(composition = pack.composition.sortedWith(compareBy({ it.sortOrder }, { it.id }))), read)
        } finally {
            db.close()
        }
    }

    /**
     * An update replaces the composition whole: first with entirely new ids (the old entries must be gone, not kept
     * beside them), then keeping one entry by its id with its quantity edited, dropping one and adding one. The row's
     * own fields are written whole each time.
     */
    @Test
    fun updateReplacesTheCompositionWhole() = runTest {
        val db = inMemoryDb()
        try {
            seedCatalog(db)
            val repo = RoomInstalledComponentRepository(db.installedComponentDao())
            val id = InstalledComponentId("c1")
            val first = component("c1", name = "Example Battery Pack", composition = listOf(entry("e1", "s1", 4.0), entry("e2", "s3", 2.0, sortOrder = 1)))
            repo.insert(first)

            val second = first.copy(
                name = "Example Battery Pack, rebuilt",
                updatedAt = 50L,
                composition = listOf(entry("e3", "s1", 4.0), entry("e4", "s3", 4.0, sortOrder = 1)),
            )
            repo.update(second)
            assertEquals(second, repo.get(id))

            val third = second.copy(
                serialOrLot = "LOT-EXAMPLE-1",
                updatedAt = 60L,
                composition = listOf(entry("e5", "s2", 1.0, sortOrder = 1), entry("e3", "s1", 6.0)),
            )
            repo.update(third)
            val read = repo.get(id)!!
            assertEquals(listOf("e3", "e5"), read.composition.map { it.id })
            assertEquals(third.copy(composition = third.composition.sortedBy { it.sortOrder }), read)
            assertEquals(listOf(id), repo.all().map { it.id })
        } finally {
            db.close()
        }
    }

    /** An Asset's rows, current and removed, by id, each with its entries; every row by id; another asset's apart. */
    @Test
    fun forAssetReturnsCurrentAndRemovedByIdWithEntries() = runTest {
        val db = inMemoryDb()
        try {
            seedCatalog(db)
            val repo = RoomInstalledComponentRepository(db.installedComponentDao())
            seedTree(repo)

            val rows = repo.forAsset(AssetId("a1"))
            assertEquals(listOf("c1", "c2", "c3", "c4", "c5"), rows.map { it.id.value })
            assertEquals(listOf(null, null, null, "2026-05-01", null), rows.map { it.removedOn })
            assertEquals(
                listOf(listOf("e1"), listOf("e2"), listOf("e3"), emptyList(), listOf("e5")),
                rows.map { row -> row.composition.map { it.id } },
            )
            assertEquals(listOf("d1"), repo.forAsset(AssetId("a2")).map { it.id.value })
            assertEquals(listOf("c1", "c2", "c3", "c4", "c5", "d1"), repo.all().map { it.id.value })
            assertEquals(listOf(4.0), repo.get(InstalledComponentId("c3"))!!.composition.map { it.quantity })
        } finally {
            db.close()
        }
    }

    /**
     * One live read of an Asset's rows: its first emission is the stored list, and an entry written to the composition
     * table alone — no row touched — is emitted on the same flow, so the read watches both tables. Another asset's
     * row is never in it.
     */
    @Test
    fun observeForAssetEmitsOnAnEntryChange() = runTest {
        val db = inMemoryDb()
        try {
            seedCatalog(db)
            val repo = RoomInstalledComponentRepository(db.installedComponentDao())
            repo.insert(component("c1", name = "Example Battery Pack", composition = listOf(entry("e1", "s1", 4.0))))
            repo.insert(component("d1", assetId = "a2"))

            val firstSeen = CompletableDeferred<List<InstalledComponent>>()
            val observed = async(Dispatchers.Default) {
                withTimeout(10_000) {
                    repo.observeForAsset(AssetId("a1"))
                        .onEach { firstSeen.complete(it) }
                        .first { rows -> rows.any { row -> row.composition.any { it.id == "e2" } } }
                }
            }
            assertEquals(listOf(listOf("e1")), firstSeen.await().map { row -> row.composition.map { it.id } })

            db.installedComponentDao().insertEntry(
                InstalledComponentCompositionEntity("e2", "c1", "s3", 8.0, "ea", 1),
            )

            val rows = observed.await()
            assertEquals(listOf("c1"), rows.map { it.id.value })
            assertEquals(listOf("e1", "e2"), rows.single().composition.map { it.id })
        } finally {
            db.close()
        }
    }

    /**
     * N-4: the update is an SQL `UPDATE`, never a delete-and-reinsert — which would fire the `parent_id` CASCADE. The
     * tray is renamed and its composition replaced; the position inside it, the pack inside that, and both their
     * entries read back exactly as before.
     */
    @Test
    fun updatingARowWithChildrenKeepsItsSubtreeAndEntries() = runTest {
        val db = inMemoryDb()
        try {
            seedCatalog(db)
            val repo = RoomInstalledComponentRepository(db.installedComponentDao())
            seedTree(repo)
            val before = repo.forAsset(AssetId("a1")).filter { it.id.value != "c1" }

            val tray = repo.get(InstalledComponentId("c1"))!!
            val edited = tray.copy(name = "Example Battery Tray, left", updatedAt = 70L, composition = listOf(entry("e6", "s3", 4.0)))
            repo.update(edited)

            assertEquals(edited, repo.get(InstalledComponentId("c1")))
            assertEquals(before, repo.forAsset(AssetId("a1")).filter { it.id.value != "c1" })
        } finally {
            db.close()
        }
    }

    /**
     * The update is one transaction: on a stored row, a new name and a composition whose second entry names no
     * SupplyItem is refused by that entry's foreign key, and nothing of the call stays — not the new name, not the
     * cleared entries, not the first new entry. The row reads back exactly as it was stored.
     */
    @Test
    fun aRefusedUpdateLeavesTheStoredRowAndItsEntriesWhole() = runTest {
        val db = inMemoryDb()
        try {
            seedCatalog(db)
            val repo = RoomInstalledComponentRepository(db.installedComponentDao())
            val stored = component(
                "c1", name = "Example Battery Pack",
                composition = listOf(entry("e1", "s1", 2.0), entry("e2", "s1", 2.0, sortOrder = 1)),
            )
            repo.insert(stored)

            val thrown = runCatching {
                repo.update(
                    stored.copy(
                        name = "Example Battery Pack, rebuilt",
                        updatedAt = 80L,
                        composition = listOf(entry("e3", "s1", 4.0), entry("e4", "no-such-item", sortOrder = 1)),
                    ),
                )
            }.exceptionOrNull()
            assertTrue("expected the entry's supply_id foreign key to refuse, got $thrown", thrown is SQLiteException)

            val read = repo.get(InstalledComponentId("c1"))!!
            assertEquals(listOf("e1", "e2"), read.composition.map { it.id })
            assertEquals(stored, read)
        } finally {
            db.close()
        }
    }

    /**
     * The core double's rule (C-2), on Room: an update of a row that is not stored writes no row, as an SQL `UPDATE`
     * matching nothing does, and does not throw; carrying entries, the entries name no row, so their foreign key
     * refuses and nothing is written.
     */
    @Test
    fun updatingAnUnstoredRowWritesNothingAndItsEntriesAreRefused() = runTest {
        val db = inMemoryDb()
        try {
            seedCatalog(db)
            val repo = RoomInstalledComponentRepository(db.installedComponentDao())

            repo.update(component("c1"))
            assertNull(repo.get(InstalledComponentId("c1")))

            val thrown = runCatching { repo.update(component("c1", composition = listOf(entry("e1", "s1")))) }.exceptionOrNull()
            assertTrue("expected the component_id foreign key to refuse, got $thrown", thrown is SQLiteException)
            assertEquals(emptyList<InstalledComponent>(), repo.all())
        } finally {
            db.close()
        }
    }

    /** Each row of [sql] as its columns joined by `|`. */
    private fun SQLiteConnection.lines(sql: String): List<String> = buildList {
        prepare(sql).use { s ->
            while (s.step()) add((0 until s.getColumnCount()).joinToString("|") { if (s.isNull(it)) "NULL" else s.getText(it) })
        }
    }
}
