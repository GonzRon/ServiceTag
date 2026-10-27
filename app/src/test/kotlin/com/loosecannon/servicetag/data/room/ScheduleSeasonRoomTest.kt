package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.LastAppliedSource
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonActivation
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonBinding
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.testing.scheduleOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #125 (U-C1; S1's rule) — schema 22's schedule season column and its two tables through the Room adapters: the source
 * round-trips and survives the shipped full-row writes; a schedule's rows read in `(occurredOn, createdAt, id)` order
 * and leave only with their schedule; a binding is keyed by its schedule, written by #16's compare-and-set, deleted one
 * at a time, and taken by its connection's delete (Forget), which leaves the schedule's source and rows as they were.
 * The names are fictional.
 */
class ScheduleSeasonRoomTest {

    private fun heater() = Asset(id = AssetId("heater"), name = "Example Heater", createdAt = 1L, updatedAt = 2L)

    private fun openingService(source: ScheduleSeasonSource = ScheduleSeasonSource.OWN) = scheduleOf(
        id = "s-open", assetId = "heater", title = "Example opening service", timeInterval = 1,
        timeUnit = RecurrenceUnit.YEAR, anchorOn = "2026-01-15",
    ).copy(seasonSource = source)

    private fun weekly() = scheduleOf(
        id = "s-week", assetId = "heater", title = "Example weekly check", timeInterval = 1,
        timeUnit = RecurrenceUnit.WEEK, anchorOn = "2026-01-15",
    )

    private fun connection() = HaConnection(
        id = "conn-1", baseUrl = "http://192.168.0.10:8123", cadence = SyncCadence.DAILY,
        networkEligibility = NetworkEligibility.HOME_NETWORK_ONLY, homeNetworkSsid = "ExampleHomeWifi",
        backgroundChecks = BackgroundChecks.OFF, createdAt = 1_000L, updatedAt = 1_000L,
    )

    private fun binding(scheduleId: String, revision: Long = 1) = ScheduleSeasonBinding(
        scheduleId = ScheduleId(scheduleId), connectionId = "conn-1",
        entityId = "input_boolean.example_heater_winterize_season", mode = SyncMode.FOLLOW, enabled = true,
        revision = revision, observedState = null, observedChangedAt = null, lastSuccessAt = null, lastAttemptAt = null,
        errorKind = null, errorDetail = null, errorAt = null, appliedAction = null, appliedOn = null, appliedAt = null,
        lastAppliedSource = null, createdAt = 2_000L, updatedAt = 2_000L,
    )

    private fun row(id: String, action: SeasonAction, on: String, createdAt: Long, scheduleId: String = "s-open") =
        ScheduleSeasonActivation(id, ScheduleId(scheduleId), action, on, createdAt)

    @Test
    fun theSeasonSourceRoundTripsAndSurvivesAFullRowWrite() = runTest {
        val db = inMemoryDb()
        try {
            RoomAssetRepository(db.assetDao()).upsert(heater())
            val schedules = RoomScheduleRepository(db.maintenanceScheduleDao())

            schedules.upsert(openingService())
            schedules.upsert(weekly())

            assertEquals(openingService(), schedules.get(ScheduleId("s-open")))
            assertEquals("OWN", db.maintenanceScheduleDao().byId("s-open")!!.schedule.seasonSource)
            assertEquals(ScheduleSeasonSource.ASSET, schedules.get(ScheduleId("s-week"))!!.seasonSource)
            // the shipped full-row write (`@Update`) maps the column too: an edit never puts `ASSET` back
            val edited = openingService().copy(title = "Example opening service, renamed")
            schedules.upsert(edited)
            assertEquals(edited, schedules.get(ScheduleId("s-open")))
            assertEquals("OWN", db.maintenanceScheduleDao().byId("s-open")!!.schedule.seasonSource)
            // and back
            schedules.upsert(edited.copy(seasonSource = ScheduleSeasonSource.ASSET))
            assertEquals(ScheduleSeasonSource.ASSET, schedules.get(ScheduleId("s-open"))!!.seasonSource)
        } finally {
            db.close()
        }
    }

    @Test
    fun aSchedulesRowsReadInOrderAndLeaveOnlyWithTheirSchedule() = runTest {
        val db = inMemoryDb()
        try {
            val assets = RoomAssetRepository(db.assetDao())
            assets.upsert(heater())
            val schedules = RoomScheduleRepository(db.maintenanceScheduleDao())
            schedules.upsert(openingService())
            schedules.upsert(weekly())
            val rows = RoomScheduleSeasonActivationRepository(db.scheduleSeasonActivationDao())
            val end = row("r-end", SeasonAction.END, "2026-04-01", 3_000L)
            val start = row("r-start", SeasonAction.START, "2025-10-20", 2_000L)
            val other = row("r-week", SeasonAction.START, "2025-10-20", 1_000L, scheduleId = "s-week")

            rows.insert(end)
            rows.insert(start)
            rows.insert(other)

            assertEquals(listOf(start, end), rows.forSchedule(ScheduleId("s-open")))
            assertEquals(listOf(other, start, end), rows.all())
            // no asset activation was written
            assertEquals(0, db.seasonActivationDao().all().size)
            // the schedule's delete — here by its asset's CASCADE — takes them
            assets.delete(AssetId("heater"))
            assertEquals(emptyList<ScheduleSeasonActivation>(), rows.all())
        } finally {
            db.close()
        }
    }

    @Test
    fun aBindingIsKeyedByItsScheduleWrittenByCompareAndSetAndDeletedAlone() = runTest {
        val db = inMemoryDb()
        try {
            RoomAssetRepository(db.assetDao()).upsert(heater())
            val schedules = RoomScheduleRepository(db.maintenanceScheduleDao())
            schedules.upsert(openingService())
            schedules.upsert(weekly().copy(seasonSource = ScheduleSeasonSource.OWN))
            RoomHaConnectionRepository(db.haConnectionDao()).upsert(connection())
            val bindings = RoomScheduleSeasonBindingRepository(db.scheduleSeasonSyncBindingDao())

            assertFalse(bindings.anyEnabled())
            bindings.insert(binding("s-open"))
            bindings.insert(binding("s-week").copy(enabled = false))

            assertEquals(listOf("s-open", "s-week"), bindings.all().map { it.scheduleId.value })
            assertEquals(binding("s-open"), bindings.get(ScheduleId("s-open")))
            assertTrue(bindings.anyEnabled())
            // every column of a read, through the compare-and-set
            val read = binding("s-open", revision = 2).copy(
                observedState = HaSwitchState.ON, observedChangedAt = "2025-10-20T06:00:00Z", lastSuccessAt = 4_000L,
                lastAttemptAt = 4_000L, errorKind = SyncErrorKind.UNREACHABLE, errorDetail = "example", errorAt = 3_500L,
                appliedAction = SeasonAction.START, appliedOn = "2025-10-20", appliedAt = 4_000L,
                lastAppliedSource = LastAppliedSource.HOME_ASSISTANT, updatedAt = 4_000L,
            )
            assertTrue(bindings.update(read))
            assertEquals(read, bindings.get(ScheduleId("s-open")))
            assertFalse("a stale revision writes nothing", bindings.update(read.copy(errorDetail = "stale")))
            assertFalse("a missing row writes nothing", bindings.update(binding("s-none", revision = 2)))
            // one row at a time
            bindings.delete(ScheduleId("s-open"))
            assertNull(bindings.get(ScheduleId("s-open")))
            assertEquals(listOf("s-week"), bindings.all().map { it.scheduleId.value })
        } finally {
            db.close()
        }
    }

    @Test
    fun forgetTakesTheBindingsAndLeavesTheSourceAndTheRows() = runTest {
        val db = inMemoryDb()
        try {
            RoomAssetRepository(db.assetDao()).upsert(heater())
            val schedules = RoomScheduleRepository(db.maintenanceScheduleDao())
            schedules.upsert(openingService())
            val connections = RoomHaConnectionRepository(db.haConnectionDao())
            connections.upsert(connection())
            val bindings = RoomScheduleSeasonBindingRepository(db.scheduleSeasonSyncBindingDao())
            val rows = RoomScheduleSeasonActivationRepository(db.scheduleSeasonActivationDao())
            bindings.insert(binding("s-open"))
            rows.insert(row("r-start", SeasonAction.START, "2025-10-20", 2_000L))

            connections.delete("conn-1")

            assertEquals(emptyList<ScheduleSeasonBinding>(), bindings.all())
            assertEquals(ScheduleSeasonSource.OWN, schedules.get(ScheduleId("s-open"))!!.seasonSource)
            assertEquals(1, rows.forSchedule(ScheduleId("s-open")).size)
        } finally {
            db.close()
        }
    }
}
