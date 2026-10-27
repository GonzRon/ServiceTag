package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonActivation
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonBinding
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.scheduleOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Collections

/**
 * #129 (L-C2, constraint 8; row k9; hazard Z1) — the DAO's per-binding delete through the Room adapter: one asset's
 * row goes, and no other binding, connection, season row, schedule binding or schedule season row; Room's table flows
 * emit, `observeFor` null and `observeAll` the shorter list; a missing row deletes nothing. A DAO `DELETE` is no schema
 * change (R6 proves `app/schemas` unchanged). Every asset, helper and address is fictional.
 */
class SeasonSyncUnlinkRoomTest {

    private val graph = FakeGraph()

    @After fun close() = graph.close()

    private val heater = AssetId("example-heater")
    private val cooler = AssetId("example-cooler")
    private val schedule = ScheduleId("example-opening")

    private fun binding(assetId: AssetId, entityId: String) = SeasonSyncBinding(
        assetId = assetId, connectionId = CONNECTION, entityId = entityId, mode = SyncMode.FOLLOW, enabled = true,
        revision = 1, observedState = null, observedChangedAt = null, lastSuccessAt = null, lastAttemptAt = null,
        errorKind = null, errorDetail = null, errorAt = null, appliedAction = null, appliedOn = null, appliedAt = null,
        lastAppliedSource = null, createdAt = 2_000L, updatedAt = 2_000L,
    )

    /** Two linked assets with a season row each, and an `OWN` schedule following its own helper with its own row. */
    private suspend fun seed() {
        graph.assets.upsert(assetRow(heater.value, "Example Heater", seasonMode = SeasonMode.MANUAL))
        graph.assets.upsert(assetRow(cooler.value, "Example Cooler", seasonMode = SeasonMode.MANUAL))
        graph.haConnections.upsert(
            HaConnection(
                id = CONNECTION, baseUrl = "http://192.168.0.10:8123", cadence = SyncCadence.DAILY,
                networkEligibility = NetworkEligibility.HOME_NETWORK_ONLY, homeNetworkSsid = "ExampleHomeWifi",
                backgroundChecks = BackgroundChecks.OFF, createdAt = 1_000L, updatedAt = 1_000L,
            ),
        )
        graph.seasonSyncBindings.insert(binding(heater, HEATER_HELPER))
        graph.seasonSyncBindings.insert(binding(cooler, COOLER_HELPER))
        graph.seasonActivations.insert(SeasonActivation("act-1", heater, SeasonAction.START, "2026-01-05", null, 3_000L))
        graph.seasonActivations.insert(SeasonActivation("act-2", cooler, SeasonAction.START, "2026-01-06", null, 3_000L))
        graph.uow.write {
            graph.schedules.upsert(
                scheduleOf(schedule.value, assetId = cooler.value, title = "Example opening service")
                    .copy(seasonSource = ScheduleSeasonSource.OWN),
            )
        }
        graph.scheduleSeasonBindings.insert(
            ScheduleSeasonBinding(
                scheduleId = schedule, connectionId = CONNECTION, entityId = "input_boolean.example_cooler_winterize_season",
                mode = SyncMode.FOLLOW, enabled = true, revision = 1, observedState = null, observedChangedAt = null,
                lastSuccessAt = null, lastAttemptAt = null, errorKind = null, errorDetail = null, errorAt = null,
                appliedAction = null, appliedOn = null, appliedAt = null, lastAppliedSource = null, createdAt = 2_000L,
                updatedAt = 2_000L,
            ),
        )
        graph.scheduleSeasonActivations.insert(ScheduleSeasonActivation("ssa-1", schedule, SeasonAction.START, "2026-01-07", 3_000L))
    }

    /** Every row the delete must leave, but [gone]'s binding. */
    private suspend fun everythingBut(gone: AssetId): List<Any?> = listOf(
        graph.seasonSyncBindings.all().filter { it.assetId != gone },
        graph.haConnections.get(),
        graph.seasonActivations.all(),
        graph.scheduleSeasonBindings.all(),
        graph.scheduleSeasonActivations.all(),
        graph.assets.all(),
        graph.schedules.all(),
    )

    private suspend fun eventually(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < until) { "never: $what" }
            delay(20)
        }
    }

    @Test
    fun theDeleteRemovesOneAssetsRowAndNothingElse() = runBlocking {
        seed()
        val before = everythingBut(heater)

        graph.seasonSyncBindings.delete(heater)

        assertNull(graph.seasonSyncBindings.get(heater))
        assertEquals("the cooler keeps its own", listOf(cooler), graph.seasonSyncBindings.all().map { it.assetId })
        assertEquals("no other row of any table moved", before, everythingBut(heater))
    }

    @Test
    fun bothFlowsEmitOnTheDelete() = runBlocking {
        seed()
        val heaters: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        val links: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
        val jobs = listOf(
            launch(Dispatchers.Default) { graph.seasonSyncBindings.observeFor(heater).collect { heaters += it?.entityId } },
            launch(Dispatchers.Default) {
                graph.seasonSyncBindings.observeAll().collect { rows -> links += rows.map { it.assetId.value } }
            },
        )
        eventually("both flows emitted the seeded rows") { heaters.isNotEmpty() && links.isNotEmpty() }

        graph.seasonSyncBindings.delete(heater)

        eventually("observeFor emits null") { heaters.last() == null }
        eventually("observeAll emits the shorter list") { links.last() == listOf(cooler.value) }
        jobs.forEach { it.cancel() }
        assertEquals(HEATER_HELPER, heaters.first())
        assertEquals(listOf(cooler.value, heater.value), links.first())
    }

    @Test
    fun aMissingRowDeletesNothing() = runBlocking {
        seed()
        val before = everythingBut(AssetId("no-such-asset")) + graph.seasonSyncBindings.all()

        graph.seasonSyncBindings.delete(AssetId("no-such-asset"))

        assertEquals(before, everythingBut(AssetId("no-such-asset")) + graph.seasonSyncBindings.all())
    }

    private companion object {
        const val CONNECTION = "conn-1"
        const val HEATER_HELPER = "input_boolean.example_heater_in_season"
        const val COOLER_HELPER = "input_boolean.example_cooler_in_season"
    }
}
