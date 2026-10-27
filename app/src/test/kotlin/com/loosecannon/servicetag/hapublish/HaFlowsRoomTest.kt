package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.data.room.RoomAssetRepository
import com.loosecannon.servicetag.data.room.RoomHaConnectionRepository
import com.loosecannon.servicetag.data.room.RoomSeasonSyncRepository
import com.loosecannon.servicetag.data.room.inMemoryDb
import com.loosecannon.servicetag.testing.cancelAndJoinBeforeClose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #113/#114 (C24 rev 6, §0 r13; row 132) — the two read flows the publishing projector and #116 observe, through Room:
 * `SeasonSyncRepository.observeAll()` emits again on a link, an entity change, a mode change and an unlink (the
 * connection's CASCADE), and `HaConnectionRepository.observe()` on a save and null after Forget, each emission seen
 * by a collector that started **before** the write. Also C28's `config_generation` round trip: a full-row write keeps
 * the generation it is given. The ids, address and Wi-Fi name are fictional.
 */
class HaFlowsRoomTest {

    private val db = inMemoryDb()
    private val assets = RoomAssetRepository(db.assetDao())
    private val connections = RoomHaConnectionRepository(db.haConnectionDao())
    private val bindings = RoomSeasonSyncRepository(db.seasonSyncBindingDao())
    private val scope = CoroutineScope(Dispatchers.Default)

    @After fun close() {
        cancelAndJoinBeforeClose(mapOf("HaFlowsRoomTest.scope" to scope.coroutineContext.job))
        db.close()
    }

    private val heater = AssetId("asset-example-heater")

    private fun connection(generation: Long = 1) = HaConnection(
        id = "connection-1", baseUrl = "http://192.168.0.10:8123", cadence = SyncCadence.DAILY,
        networkEligibility = NetworkEligibility.HOME_NETWORK_ONLY, homeNetworkSsid = "ExampleHomeWifi",
        backgroundChecks = BackgroundChecks.OFF, createdAt = 1_000L, updatedAt = 1_000L, configGeneration = generation,
    )

    private fun binding() = SeasonSyncBinding(
        assetId = heater, connectionId = "connection-1", entityId = "input_boolean.example_heater_in_season",
        mode = SyncMode.FOLLOW, enabled = true, revision = 1, observedState = null, observedChangedAt = null,
        lastSuccessAt = null, lastAttemptAt = null, errorKind = null, errorDetail = null, errorAt = null,
        appliedAction = null, appliedOn = null, appliedAt = null, lastAppliedSource = null, createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    /** Collects [flow] from now on; [next] waits for the next emission. */
    private class Emissions<T>(scope: CoroutineScope, flow: Flow<T>) {
        private val channel = Channel<T>(Channel.UNLIMITED)

        init {
            scope.launch { flow.collect { channel.send(it) } }
        }

        suspend fun next(): T = withTimeout(5_000) { channel.receive() }

        /** Waits until an emission satisfies [predicate]; Room may emit the unchanged state once more first. */
        suspend fun until(predicate: (T) -> Boolean): T {
            while (true) {
                val value = next()
                if (predicate(value)) return value
            }
        }
    }

    @Test
    fun observeAllEmitsOnLinkEntityChangeModeChangeAndUnlink() = runBlocking<Unit> {
        assets.upsert(Asset(id = heater, name = "Example Heater", createdAt = 500L, updatedAt = 500L))
        connections.upsert(connection())
        val seen = Emissions(scope, bindings.observeAll())
        assertEquals(emptyList<SeasonSyncBinding>(), seen.next())

        bindings.insert(binding())
        seen.until { it == listOf(binding()) }

        val edited = binding().copy(entityId = "input_boolean.example_heater_in_season_2", revision = 2)
        bindings.update(edited)
        seen.until { it == listOf(edited) }

        val forced = edited.copy(mode = SyncMode.FORCE_IN, revision = 3)
        bindings.update(forced)
        seen.until { it == listOf(forced) }

        connections.delete("connection-1") // the CASCADE unlinks
        seen.until { it.isEmpty() }
    }

    @Test
    fun observeEmitsOnASaveAndNullAfterForget() = runBlocking<Unit> {
        val seen = Emissions(scope, connections.observe())
        assertNull(seen.next())

        connections.upsert(connection())
        seen.until { it == connection() }

        val weekly = connection(generation = 2).copy(cadence = SyncCadence.WEEKLY, updatedAt = 2_000L)
        connections.upsert(weekly)
        seen.until { it == weekly }

        connections.delete("connection-1")
        seen.until { it == null }
    }

    @Test
    fun aFullRowWriteKeepsTheConfigGeneration() = runBlocking<Unit> {
        connections.upsert(connection(generation = 3))
        assertEquals(3L, connections.get()!!.configGeneration)
        connections.upsert(connection(generation = 7).copy(cadence = SyncCadence.MONTHLY))
        assertEquals(7L, connections.get()!!.configGeneration)
        assertEquals(SyncCadence.MONTHLY, connections.get()!!.cadence)
    }
}
