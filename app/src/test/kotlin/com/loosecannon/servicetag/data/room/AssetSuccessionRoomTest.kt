package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteException
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetSuccession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #86's succession table through the production adapter (B1, row 2; C2; R86-1, R86-15): the schema's own word on I2
 * and on delete. Each end carries a UNIQUE index, so a second row naming a predecessor, or a successor, another row
 * already names aborts at the index; both keys are `ON DELETE CASCADE`, so deleting either asset takes the row, and
 * nothing re-links around it. A rewrite of an asset keeps the row (update-then-insert, never a REPLACE's cascade). The
 * names are fictional.
 */
class AssetSuccessionRoomTest {

    private val db = inMemoryDb()
    private val assets = RoomAssetRepository(db.assetDao())
    private val successions = RoomAssetSuccessionRepository(db.assetSuccessionDao())

    @After fun close() = db.close()

    private fun asset(id: String, name: String) = Asset(id = AssetId(id), name = name, createdAt = 1L, updatedAt = 1L)

    private suspend fun fourAssets() {
        assets.upsert(asset("a1", "Example Water Heater"))
        assets.upsert(asset("a2", "Example Water Heater, second"))
        assets.upsert(asset("a3", "Example Water Heater, third"))
        assets.upsert(asset("a4", "Sample Pool Pump"))
    }

    private fun row(id: String, predecessor: String, successor: String, replacedOn: String = "2026-09-20") =
        AssetSuccession(id, AssetId(predecessor), AssetId(successor), replacedOn, 1_758_900_000_000L)

    /** A → B → C: two rows (I3). */
    private suspend fun chain(): Pair<AssetSuccession, AssetSuccession> {
        fourAssets()
        val first = row("s1", "a1", "a2", replacedOn = "2025-04-01")
        val second = row("s2", "a2", "a3")
        successions.append(first)
        successions.append(second)
        return first to second
    }

    @Test
    fun aSecondRowForOnePredecessorIsRefusedByTheIndex() = runTest {
        fourAssets()
        successions.append(row("s1", "a1", "a2"))

        val refused = runCatching { successions.append(row("s2", "a1", "a3")) }

        assertTrue("the index refuses a second successor of a1: ${refused.exceptionOrNull()}", refused.exceptionOrNull() is SQLiteException)
        assertEquals(listOf("s1"), successions.all().map { it.id })
    }

    @Test
    fun aSecondRowForOneSuccessorIsRefused() = runTest {
        fourAssets()
        successions.append(row("s1", "a1", "a3"))

        val refused = runCatching { successions.append(row("s2", "a2", "a3")) }

        assertTrue("the index refuses a second predecessor of a3: ${refused.exceptionOrNull()}", refused.exceptionOrNull() is SQLiteException)
        assertEquals(listOf("s1"), successions.all().map { it.id })
        val sameId = runCatching { successions.append(row("s1", "a2", "a4")) }
        assertTrue("and an id already held aborts: ${sameId.exceptionOrNull()}", sameId.exceptionOrNull() is SQLiteException)
    }

    @Test
    fun deletingThePredecessorCascades() = runTest {
        val (_, second) = chain()

        assets.delete(AssetId("a1"))

        assertEquals("the row goes with its predecessor", listOf(second), successions.all())
        assertEquals("nothing re-links: a2 and a3 stay", listOf("a2", "a3", "a4"), assets.all().map { it.id.value }.sorted())
    }

    @Test
    fun deletingTheSuccessorCascades() = runTest {
        val (first, _) = chain()

        assets.delete(AssetId("a3"))
        assertEquals("the row goes with its successor", listOf(first), successions.all())

        assets.delete(AssetId("a2"))
        assertEquals("an asset at both ends of a chain takes both rows", emptyList<AssetSuccession>(), successions.all())
        assertNull("A → C is never synthesised", successions.replacedBy(AssetId("a1")))
    }

    @Test
    fun observeForAssetSeesBothEnds() = runTest {
        val (first, second) = chain()

        assertEquals("field for field, both ends of the middle asset", listOf(first, second), successions.observeForAsset(AssetId("a2")).first())
        assertEquals(listOf(first), successions.observeForAsset(AssetId("a1")).first())
        assertEquals(listOf(second), successions.observeForAsset(AssetId("a3")).first())
        assertEquals(emptyList<AssetSuccession>(), successions.observeForAsset(AssetId("a4")).first())
        assertEquals(first, successions.replacedBy(AssetId("a1")))
        assertEquals(second, successions.replaces(AssetId("a3")))
        assertNull(successions.replaces(AssetId("a1")))
        assertEquals(listOf(first, second), successions.all())
    }

    /** The #79 lesson: an asset written again is update-then-insert, so no REPLACE's cascade takes the row. */
    @Test
    fun rewritingAnEndKeepsTheRow() = runTest {
        val (first, second) = chain()

        assets.upsert(asset("a2", "Example Water Heater, second, renamed").copy(updatedAt = 9L))

        assertEquals(listOf(first, second), successions.all())
    }
}
