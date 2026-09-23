package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteException
import com.loosecannon.servicetag.data.room.entities.AssetEntity
import com.loosecannon.servicetag.data.room.entities.AssetReferenceEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        description = "", scheme = "https", createdAt = 10L, updatedAt = 20L,
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
}
