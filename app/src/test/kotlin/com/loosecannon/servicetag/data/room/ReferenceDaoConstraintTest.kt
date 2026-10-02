package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteException
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.data.room.entities.AssetEntity
import com.loosecannon.servicetag.data.room.entities.AssetReferenceEntity
import com.loosecannon.servicetag.data.room.entities.InstalledComponentEntity
import com.loosecannon.servicetag.data.room.entities.SupplyItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The v7 constraints that carry a rule, asserted against a real database rather than against the
 * code that is supposed to respect them — because the point of each is that it holds even when the
 * caller forgets.
 *
 * The last case is structural instead: it reads the source, because Room's annotations have BINARY
 * retention and are invisible to runtime reflection, and because "no query anywhere moves a saved
 * URI or re-parents a row" is a claim about every line of the DAO, not about one call.
 */
class ReferenceDaoConstraintTest {

    private fun asset(id: String) = AssetEntity(
        id = id, name = "Asset $id", description = "", category = "", notes = "",
        status = "ACTIVE", templateKey = null, createdAt = 1L, updatedAt = 1L,
    )

    private fun reference(
        id: String,
        assetId: String,
        uri: String = "https://example-mower.invalid/manual",
        displayName: String = "Manual",
    ) = AssetReferenceEntity(
        id = id, assetId = assetId, kind = "WEB_URL", uri = uri, displayName = displayName,
        description = "", scheme = "https", createdAt = 10L, updatedAt = 20L, documentRole = null,
        supplyItemId = null, installedComponentId = null,
    )

    /**
     * `UNIQUE(asset_id, uri)` (I-7): **one asset holds a URI once**, and it is the database that
     * says so, not the use case. The second half is the other reason the index is shaped this way —
     * the *same* URI on two different assets is ordinary and both rows land, which an index on
     * `uri` alone would have refused.
     */
    @Test
    fun oneAssetHoldsAUriOnceAndTwoAssetsMayHoldTheSameOne() = runTest {
        val db = inMemoryDb()
        try {
            db.assetDao().upsert(asset("a1"))
            db.assetDao().upsert(asset("a2"))
            val dao = db.assetReferenceDao()

            dao.upsert(reference("r1", "a1"))
            try {
                dao.upsert(reference("r2", "a1"))
                fail("expected a UNIQUE violation on (asset_id, uri)")
            } catch (e: Exception) {
                // The driver reports this as androidx.sqlite.SQLiteException; its message is not
                // asserted, for the reason NfcTagDaoTest records.
                assertTrue("unexpected exception: $e", e is SQLiteException)
            }

            // the same URI on a different asset is ordinary
            dao.upsert(reference("r3", "a2"))
            // and so is a different URI on the first one
            dao.upsert(reference("r4", "a1", uri = "https://example-mower.invalid/parts"))

            assertEquals(listOf("r1", "r3", "r4"), dao.all().map { it.id })
        } finally {
            db.close()
        }
    }

    /**
     * `ON DELETE CASCADE`: deleting the asset takes its references with it and leaves every other
     * asset's alone. Left at Room's default the rows would outlive their owner and the next
     * foreign-key check would fail on them.
     */
    @Test
    fun deletingAnAssetRemovesItsReferencesAndNobodyElses() = runTest {
        val db = inMemoryDb()
        try {
            db.assetDao().upsert(asset("a1"))
            db.assetDao().upsert(asset("a2"))
            val dao = db.assetReferenceDao()
            dao.upsert(reference("r1", "a1"))
            dao.upsert(reference("r2", "a2"))

            db.assetDao().delete("a1")

            assertEquals(listOf("r2"), dao.all().map { it.id })
        } finally {
            db.close()
        }
    }

    /**
     * The read order later briefs compile against: `display_name COLLATE NOCASE`, then `id`. The
     * collation is what puts a lower-cased name where a reader expects it, and the id is the
     * tie-break that makes the key total — without it two references sharing a name come back in
     * whatever order the table hands them over.
     */
    @Test
    fun anAssetsReferencesComeBackByNameThenId() = runTest {
        val db = inMemoryDb()
        try {
            db.assetDao().upsert(asset("a1"))
            val dao = db.assetReferenceDao()
            dao.upsert(reference("r2", "a1", uri = "https://example-mower.invalid/2", displayName = "manual"))
            dao.upsert(reference("r1", "a1", uri = "https://example-mower.invalid/1", displayName = "Manual"))
            dao.upsert(reference("r3", "a1", uri = "https://example-mower.invalid/3", displayName = "Parts"))

            assertEquals(listOf("r1", "r2", "r3"), dao.forAsset("a1").map { it.id })
        } finally {
            db.close()
        }
    }

    /**
     * I-1 and I-6, structurally: a saved `uri` is never edited and a reference never changes owner,
     * so no statement anywhere in `src/main` may set either column. `upsert` writes the whole row
     * through Room's `@Update`/`@Insert` pair — the shape every other DAO here uses — and there is
     * no `@Query` that touches one column on its own.
     */
    @Test
    fun noQueryEverMovesASavedUriOrReParentsAReference() {
        val dao = mainSourceFile("kotlin/com/loosecannon/servicetag/data/room/dao/AssetReferenceDao.kt")
            .readText()
        val queries = Regex("@Query\\(\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .findAll(dao).map { it.groupValues[1] }.toList()
        assertTrue("AssetReferenceDao must declare queries", queries.isNotEmpty())
        val updates = queries.filter { it.trimStart().startsWith("UPDATE", ignoreCase = true) }
        assertEquals("no @Query on asset_reference may be an UPDATE", emptyList<String>(), updates)
        assertFalse("AssetReferenceDao must not offer a bare @Delete", "@Delete" in dao)
    }

    /**
     * #91 (C4, C5): the mapper carries `document_role` both ways. Each of the three roles and no role
     * at all go in through the adapter and come back as the same reference; the column holds the
     * enum's name, the attachment column's spelling, and SQL NULL for no role.
     */
    @Test
    fun eachRoleAndNoneRoundTripsThroughRoom() = runTest {
        val db = inMemoryDb()
        try {
            db.assetDao().upsert(asset("a1"))
            val repo = RoomReferenceRepository(db.assetReferenceDao())
            val written = (listOf(null) + DocumentRole.entries).mapIndexed { i, role ->
                AssetReference(
                    id = ReferenceId("r$i"),
                    owner = ReferenceOwner.OfAsset(AssetId("a1")),
                    kind = ReferenceKind.WEB_URL,
                    uri = "https://manuals.example.invalid/water-heater/$i",
                    displayName = "Example Water Heater document $i",
                    description = "",
                    scheme = "https",
                    createdAt = 10L + i,
                    updatedAt = 20L + i,
                    role = role,
                )
            }
            written.forEach { repo.upsert(it) }

            for (row in written) assertEquals("${row.role}", row, repo.get(row.id))
            assertEquals(written, repo.all())
            assertEquals(
                listOf("r0" to null) + DocumentRole.entries.mapIndexed { i, role -> "r${i + 1}" to role.name },
                db.assetReferenceDao().all().map { it.id to it.documentRole },
            )
        } finally {
            db.close()
        }
    }

    /**
     * #69 (C13, row 27): each owner travels in its own column and comes back as the same owner, through every read
     * the port has. An asset, a SupplyItem and a component deliberately share the id string `x1` and the URI, so a
     * mapper that wrote the wrong column would break a per-owner unique index or read back as another owner's link.
     */
    @Test
    fun threeOwnersRoundTrip() = runTest {
        val db = inMemoryDb()
        try {
            db.assetDao().upsert(asset("x1"))
            db.supplyItemDao().upsert(
                SupplyItemEntity(
                    id = "x1", name = "Example 12 V Battery", category = "Batteries", manufacturer = "Example Power Co.",
                    model = "EB-12", partNumber = "EB-12-1", preferredUnit = "ea", notes = "", archivedAt = null,
                    createdAt = 10L, updatedAt = 20L,
                ),
                emptyList(),
            )
            db.installedComponentDao().insert(
                InstalledComponentEntity(
                    id = "x1", assetId = "x1", parentId = null, name = "Example Battery Tray", supplyId = "x1",
                    serialOrLot = "", installedOn = null, removedOn = null, replacesId = null, sortOrder = 0, notes = "",
                    createdAt = 30L, updatedAt = 40L,
                ),
                emptyList(),
            )
            val repo = RoomReferenceRepository(db.assetReferenceDao())
            val owners = listOf(
                ReferenceOwner.OfAsset(AssetId("x1")),
                ReferenceOwner.OfSupplyItem(SupplyId("x1")),
                ReferenceOwner.OfInstalledComponent(InstalledComponentId("x1")),
            )
            val written = owners.mapIndexed { i, owner ->
                AssetReference(
                    id = ReferenceId("r$i"), owner = owner, kind = ReferenceKind.WEB_URL,
                    uri = "https://example.invalid/battery/manual.pdf", displayName = "Example manual $i",
                    description = "", scheme = "https", createdAt = 10L, updatedAt = 20L, role = DocumentRole.USER_MANUAL,
                )
            }
            written.forEach { repo.upsert(it) }

            for (row in written) {
                assertEquals("${row.owner}", row, repo.get(row.id))
                assertEquals("${row.owner}", listOf(row), repo.forOwner(row.owner))
                assertEquals("${row.owner}", row, repo.findByUri(row.owner, row.uri))
                assertEquals("${row.owner}", listOf(row), repo.observeForOwner(row.owner).first())
            }
            assertEquals(
                listOf(Triple("x1", null, null), Triple(null, "x1", null), Triple(null, null, "x1")),
                db.assetReferenceDao().all().sortedBy { it.id }.map { Triple(it.assetId, it.supplyItemId, it.installedComponentId) },
            )
        } finally {
            db.close()
        }
    }

    /** Exactly one owner on the way out of Room as well: a row naming none, or two, is refused by the mapper. */
    @Test
    fun theMapperRefusesNoOwner() {
        val none = reference("r1", "a1").copy(assetId = null)
        val two = reference("r2", "a1").copy(supplyItemId = "s1")
        for (row in listOf(none, two)) {
            val refused = assertThrows(IllegalArgumentException::class.java) { row.toDomain() }
            assertTrue("${refused.message}", refused.message!!.startsWith("reference '${row.id}' must name exactly one owner"))
        }
    }
}
