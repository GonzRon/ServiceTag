package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteException
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #16 (C9, C11; rows 16–17) — the two device-local tables through their Room adapters: an asset's delete takes its
 * binding and a connection's delete takes its bindings (both CASCADE), the asset is the binding's key, every field of
 * both rows round-trips, no column of either table could hold a credential, and the conditional update writes only
 * over the revision before the one it carries. The asset, entity, address and Wi-Fi name are fictional.
 */
class SeasonSyncDaoTest {

    private val db = inMemoryDb()
    private val assets = RoomAssetRepository(db.assetDao())
    private val connections = RoomHaConnectionRepository(db.haConnectionDao())
    private val bindings = RoomSeasonSyncRepository(db.seasonSyncBindingDao())

    @After fun close() = db.close()

    private val heater = AssetId("asset-heater")
    private val dehumidifier = AssetId("asset-dehumidifier")

    private fun connection(id: String = CONNECTION) = HaConnection(
        id = id, baseUrl = "http://192.168.0.10:8123", cadence = SyncCadence.DAILY,
        networkEligibility = NetworkEligibility.HOME_NETWORK_ONLY, homeNetworkSsid = "ExampleHomeWifi",
        backgroundChecks = BackgroundChecks.OFF, createdAt = 1_000L, updatedAt = 1_000L,
    )

    /** A binding as a link leaves it: FOLLOW, enabled, no status yet. */
    private fun binding(assetId: AssetId, connectionId: String = CONNECTION, revision: Long = 1) = SeasonSyncBinding(
        assetId = assetId, connectionId = connectionId, entityId = ENTITY, mode = SyncMode.FOLLOW, enabled = true,
        revision = revision, observedState = null, observedChangedAt = null, lastSuccessAt = null, lastAttemptAt = null,
        errorKind = null, errorDetail = null, errorAt = null, appliedAction = null, appliedOn = null, appliedAt = null,
        createdAt = 2_000L, updatedAt = 2_000L,
    )

    private suspend fun seed(vararg ids: String) {
        for (id in ids) assets.upsert(Asset(id = AssetId(id), name = "Example $id", createdAt = 500L, updatedAt = 500L))
        connections.upsert(connection())
    }

    // --- row 16: the cascades and the key ------------------------------------------------------

    @Test
    fun deletingAnAssetTakesItsBinding() = runTest {
        seed(heater.value, dehumidifier.value)
        bindings.insert(binding(heater))
        bindings.insert(binding(dehumidifier))

        assets.delete(heater)

        assertNull(bindings.get(heater))
        assertEquals(listOf(dehumidifier), bindings.all().map { it.assetId })
        assertEquals("the connection stays", connection(), connections.get())
    }

    @Test
    fun deletingTheConnectionTakesItsBindings() = runTest {
        seed(heater.value, dehumidifier.value)
        bindings.insert(binding(heater))
        bindings.insert(binding(dehumidifier))

        connections.delete(CONNECTION)

        assertNull(connections.get())
        assertEquals(emptyList<SeasonSyncBinding>(), bindings.all())
        assertEquals("the assets stay", listOf(dehumidifier, heater), assets.all().map { it.id }.sortedBy { it.value })
    }

    @Test
    fun oneBindingPerAsset() = runTest {
        seed(heater.value, dehumidifier.value)
        bindings.insert(binding(heater))

        val second = runCatching { bindings.insert(binding(heater, revision = 7)) }.exceptionOrNull()
        assertTrue("expected the primary key to refuse a second binding, got $second", second is SQLiteException)
        assertEquals(listOf(1L), bindings.all().map { it.revision })

        for (orphan in listOf(binding(AssetId("no-such-asset")), binding(dehumidifier, connectionId = "no-such-connection"))) {
            val thrown = runCatching { bindings.insert(orphan) }.exceptionOrNull()
            assertTrue("expected a foreign key to refuse $orphan, got $thrown", thrown is SQLiteException)
        }
    }

    /** An upsert is an `UPDATE` in place: a REPLACE would delete the row and, by the CASCADE, every binding on it. */
    @Test
    fun anUpsertChangesTheConnectionInPlaceAndKeepsItsBindings() = runTest {
        seed(heater.value)
        bindings.insert(binding(heater))
        val changed = connection().copy(
            baseUrl = "https://ha.example:8123", cadence = SyncCadence.WEEKLY,
            networkEligibility = NetworkEligibility.ANY_NETWORK, homeNetworkSsid = null,
            backgroundChecks = BackgroundChecks.ON, updatedAt = 3_000L,
        )

        connections.upsert(changed)

        assertEquals(changed, connections.get())
        assertEquals(listOf(heater), bindings.all().map { it.assetId })
    }

    @Test
    fun aSecondConnectionRowIsRefusedOnRead() = runTest {
        connections.upsert(connection())
        connections.upsert(connection(id = "conn-2"))

        val thrown = runCatching { connections.get() }.exceptionOrNull()
        assertTrue("one installation has one connection (R16-10), got $thrown", thrown is IllegalStateException)
    }

    // --- row 17: the round trip, no credential column, the conditional update -----------------

    @Test
    fun aBindingRoundTripsEveryField() = runTest {
        seed(heater.value)
        assertEquals(connection(), connections.get())
        val full = binding(heater).copy(
            mode = SyncMode.FORCE_IN, enabled = false, revision = 9, observedState = HaSwitchState.OFF,
            observedChangedAt = "2026-10-02T06:00:00+00:00", lastSuccessAt = 4_000L, lastAttemptAt = 5_000L,
            errorKind = SyncErrorKind.TIMED_OUT, errorDetail = "504", errorAt = 5_000L,
            appliedAction = SeasonAction.END, appliedOn = "2026-10-02", appliedAt = 4_500L, updatedAt = 5_000L,
        )

        bindings.insert(full)

        assertEquals(full, bindings.get(heater))
        assertEquals(listOf(full), bindings.all())
        assertEquals(full, bindings.observeFor(heater).first())
        assertNull(bindings.observeFor(dehumidifier).first())
    }

    @Test
    fun noColumnOfEitherTableIsATokenOrSecret() = runTest {
        val file = File.createTempFile("servicetag-season-sync-columns", ".db").also { it.delete() }
        try {
            openFresh(file).let { fresh ->
                try {
                    // touching it is what makes Room actually create the file
                    assertEquals(0, fresh.seasonSyncBindingDao().all().size)
                } finally {
                    fresh.close()
                }
            }
            val columns = withConnection(file) { c -> V21_TABLES.associateWith { c.columnNamesOf(it) } }
            assertEquals(8, columns.getValue("ha_connection").size)
            assertEquals(18, columns.getValue("season_sync_binding").size)
            val credentialLike = Regex("token|secret|bearer|password|credential|auth", RegexOption.IGNORE_CASE)
            for ((table, names) in columns) {
                assertEquals("$table holds no credential column", emptyList<String>(), names.filter { credentialLike.containsMatchIn(it) })
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun theConditionalUpdateAnswersFalseOnAStaleRevision() = runTest {
        seed(heater.value)
        bindings.insert(binding(heater, revision = 3))
        assertTrue(bindings.anyEnabled())

        // every field set, distinct within each type, so a transposed SET pair in the hand-written UPDATE shows
        val next = binding(heater, revision = 4).copy(
            mode = SyncMode.FORCE_IN, enabled = false, observedState = HaSwitchState.ON,
            observedChangedAt = "2026-10-02T07:00:00+00:00", lastSuccessAt = 6_100L, lastAttemptAt = 6_200L,
            errorKind = SyncErrorKind.HTTP_ERROR, errorDetail = "503", errorAt = 6_300L,
            appliedAction = SeasonAction.END, appliedOn = "2026-10-02", appliedAt = 6_400L, updatedAt = 6_500L,
        )
        assertTrue("revision 3 -> 4 writes", bindings.update(next))
        assertEquals(next, bindings.get(heater))
        assertFalse(bindings.anyEnabled())

        // a writer that read revision 3 and carries 4 again is stale now; so is one that skips ahead
        for (stale in listOf(next.copy(mode = SyncMode.FORCE_OUT), next.copy(revision = 6, mode = SyncMode.FORCE_OUT))) {
            assertFalse("stale $stale", bindings.update(stale))
        }
        assertEquals(next, bindings.get(heater))
        assertFalse("no row answers false", bindings.update(binding(dehumidifier, revision = 2)))
        assertEquals(listOf(heater), bindings.all().map { it.assetId })
    }

    private companion object {
        const val CONNECTION = "conn-1"
        const val ENTITY = "input_boolean.example_heater_in_season"
    }
}
