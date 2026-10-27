package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonActivation
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonBinding
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.scheduleOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #125 (H1 ruling 2, H1b ruling 3) — the graph wires the schedule season tables: the applier, the unlink and the
 * archive reach Room through `FakeGraph`, built as `AppGraph` is, so an `OWN` schedule never meets the empty ports that
 * fail loudly. The names are fictional.
 */
class ScheduleSeasonGraphTest {

    private val graph = FakeGraph()

    @After fun close() = graph.close()

    private val opening = scheduleOf(
        id = "s-open", assetId = "heater", title = "Example opening service", timeInterval = 1,
        timeUnit = RecurrenceUnit.YEAR, anchorOn = "2026-01-15", servicePolicy = ServicePolicy.IN_SERVICE_AT_START,
        policyOffsetDays = 0,
    ).copy(seasonSource = ScheduleSeasonSource.OWN)

    private val binding = ScheduleSeasonBinding(
        scheduleId = ScheduleId("s-open"), connectionId = "conn-1",
        entityId = "input_boolean.example_heater_winterize_season", mode = SyncMode.FOLLOW, enabled = true,
        revision = 1, observedState = null, observedChangedAt = null, lastSuccessAt = null, lastAttemptAt = null,
        errorKind = null, errorDetail = null, errorAt = null, appliedAction = null, appliedOn = null, appliedAt = null,
        lastAppliedSource = null, createdAt = 1_000L, updatedAt = 1_000L,
    )

    private suspend fun linked() {
        graph.assets.upsert(
            Asset(id = AssetId("heater"), name = "Example Heater", seasonMode = SeasonMode.MANUAL, createdAt = 1L, updatedAt = 1L),
        )
        graph.schedules.upsert(opening)
        graph.haConnections.upsert(
            HaConnection(
                id = "conn-1", baseUrl = "http://192.168.0.10:8123", cadence = SyncCadence.DAILY,
                networkEligibility = NetworkEligibility.HOME_NETWORK_ONLY, homeNetworkSsid = "ExampleHomeWifi",
                backgroundChecks = BackgroundChecks.OFF, createdAt = 1_000L, updatedAt = 1_000L,
            ),
        )
        graph.scheduleSeasonBindings.insert(binding)
    }

    @Test
    fun theApplierWritesTheSchedulesOwnRowAndTheRecomputeReadsIt() = runTest {
        linked()

        graph.recordScheduleSeasonSyncResult.run(
            ScheduleId("s-open"), readRevision = 1, startedAt = 2_000L, fetchedAt = 2_000L,
            outcome = HaReadOutcome.Observed(HaSwitchState.ON, haLastChanged = null),
        )

        val rows = graph.scheduleSeasonActivations.forSchedule(ScheduleId("s-open"))
        assertEquals(listOf(SeasonAction.START to graph.today.toString()), rows.map { it.action to it.occurredOn })
        assertEquals("no asset activation", 0, graph.seasonActivations.all().size)
        assertEquals(2L, graph.scheduleSeasonBindings.get(ScheduleId("s-open"))!!.revision)
        // the graph's recompute reads the row: the schedule is due on its own start
        assertEquals(graph.today.toString(), graph.recomputeSchedules.readState(graph.schedules.get(ScheduleId("s-open"))!!).actionableDueOn)
    }

    @Test
    fun archiveAndUnlinkReturnTheScheduleToItsAssetsSeasonAndKeepItsRows() = runTest {
        linked()
        graph.scheduleSeasonActivations.insert(ScheduleSeasonActivation("r-1", ScheduleId("s-open"), SeasonAction.START, "2026-02-01", 1_500L))

        graph.archiveSchedule.run(ScheduleId("s-open"), archived = true)

        assertEquals(ScheduleSeasonSource.ASSET, graph.schedules.get(ScheduleId("s-open"))!!.seasonSource)
        assertNull(graph.scheduleSeasonBindings.get(ScheduleId("s-open")))
        assertEquals(1, graph.scheduleSeasonActivations.forSchedule(ScheduleId("s-open")).size)

        // a stranded one (its binding gone with Forget) unlinks too
        graph.schedules.upsert(graph.schedules.get(ScheduleId("s-open"))!!.copy(seasonSource = ScheduleSeasonSource.OWN))
        graph.unlinkScheduleSeason.run(ScheduleId("s-open"))
        assertEquals(ScheduleSeasonSource.ASSET, graph.schedules.get(ScheduleId("s-open"))!!.seasonSource)
    }
}
