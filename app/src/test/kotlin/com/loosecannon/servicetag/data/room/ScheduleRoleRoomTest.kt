package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.data.room.dao.ScheduleWithProviders
import com.loosecannon.servicetag.testing.scheduleOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * #114 (A2; C13, B) — schema 22's role columns and event marker through the Room mappers: a role, its boundary and the
 * `imported` marker round-trip, and the shipped full-row writes no longer put `'NONE'`/null or `0` back.
 */
class ScheduleRoleRoomTest {

    private fun heater() = Asset(id = AssetId("heater"), name = "Example Heater", createdAt = 1L, updatedAt = 2L)

    /** A completion of `s1` by the heater, dated [on]. */
    private fun completionOf(id: String, on: String) = AssetEvent(
        id = EventId(id), assetId = AssetId("heater"), kind = EventKind.MAINTENANCE, title = "Done", profileId = null,
        occurredOn = on, occurredTime = null, tzId = "UTC", notes = "", source = EventSource.SCHEDULE_QUICK_COMPLETE,
        sourceRef = null, createdAt = 5_000L, updatedAt = 5_000L, measurements = emptyList(), consumables = emptyList(),
        scheduleId = ScheduleId("s1"),
    )

    private fun roleSchedule() = scheduleOf(
        id = "s1", assetId = "heater", title = "Example annual service", timeInterval = 1,
        timeUnit = RecurrenceUnit.YEAR, anchorOn = "2026-01-15",
    ).copy(role = ScheduleRole.SEASON_START, roleSinceOn = "2026-09-01")

    @Test
    fun aRoleRoundTripsThroughTheMappers() = runTest {
        val db = inMemoryDb()
        try {
            RoomAssetRepository(db.assetDao()).upsert(heater())
            val schedules = RoomScheduleRepository(db.maintenanceScheduleDao())
            val s1 = roleSchedule()

            schedules.upsert(s1)

            assertEquals(s1, schedules.get(ScheduleId("s1")))
            val row = db.maintenanceScheduleDao().byId("s1")!!.schedule
            assertEquals("SEASON_START", row.role)
            assertEquals("2026-09-01", row.roleSinceOn)
        } finally {
            db.close()
        }
    }

    @Test
    fun aRoleSurvivesAnUnrelatedScheduleUpdate() = runTest {
        val db = inMemoryDb()
        try {
            RoomAssetRepository(db.assetDao()).upsert(heater())
            val schedules = RoomScheduleRepository(db.maintenanceScheduleDao())
            schedules.upsert(roleSchedule())

            // The shape of every shipped full-row writer (pause, postpone, a completion's clear): read, copy, upsert.
            val paused = schedules.get(ScheduleId("s1"))!!.copy(status = ScheduleStatus.PAUSED, updatedAt = 9_000L)
            schedules.upsert(paused)

            val stored = schedules.get(ScheduleId("s1"))!!
            assertEquals(ScheduleStatus.PAUSED, stored.status)
            assertEquals(ScheduleRole.SEASON_START, stored.role)
            assertEquals("2026-09-01", stored.roleSinceOn)
        } finally {
            db.close()
        }
    }

    @Test
    fun theImportedMarkerRoundTripsAndSurvivesAnEdit() = runTest {
        val db = inMemoryDb()
        try {
            RoomAssetRepository(db.assetDao()).upsert(heater())
            RoomScheduleRepository(db.maintenanceScheduleDao()).upsert(roleSchedule())
            val events = RoomEventRepository(db.eventDao())
            val imported = completionOf("e1", "2026-10-01").copy(imported = true)

            events.upsert(imported)
            assertEquals(imported, events.get(EventId("e1")))
            assertTrue(db.eventDao().byId("e1")!!.event.imported)

            events.upsert(events.get(EventId("e1"))!!.copy(notes = "Cleaned the burner too", updatedAt = 9_000L))
            assertTrue(events.get(EventId("e1"))!!.imported)

            val own = completionOf("e2", "2026-10-02")
            events.upsert(own)
            assertFalse(events.get(EventId("e2"))!!.imported)
        } finally {
            db.close()
        }
    }

    /** R1 (C7, C13): a stored row with a role and no boundary, or the reverse, fails a debug assertion only. */
    @Test
    fun aRowThatBreaksR1FailsOnlyADebugAssertion() {
        assumeTrue("JVM assertions are on in this test run", javaClass.desiredAssertionStatus())
        val good = roleSchedule()
        val noBoundary = good.toEntity().copy(roleSinceOn = null)
        val boundaryOnly = good.toEntity().copy(role = "NONE")

        assertThrows(AssertionError::class.java) { ScheduleWithProviders(noBoundary, emptyList()).toDomain() }
        assertThrows(AssertionError::class.java) { ScheduleWithProviders(boundaryOnly, emptyList()).toDomain() }
    }
}
