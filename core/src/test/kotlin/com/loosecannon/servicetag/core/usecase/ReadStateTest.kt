package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ScheduleStateRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryClosureRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryGroupRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.InMemorySeasonActivationRepository
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.scheduleOf
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

/**
 * `RecomputeSchedules.readState` (master plan §8.6; inv. 105's `:core` half): the one accessor every
 * read path asks. A row computed for today is returned as stored; a stale or missing row is derived
 * in memory; and nothing is ever written — the recording repository below counts every upsert.
 */
class ReadStateTest {

    /** Delegates to the in-memory table and counts the one call a read must never make. */
    private class RecordingStates(val inner: InMemoryScheduleStateRepository) : ScheduleStateRepository by inner {
        var upserts = 0
        override suspend fun upsert(state: ScheduleState) {
            upserts++
            inner.upsert(state)
        }
    }

    private val assets = InMemoryAssetRepository()
    private val events = InMemoryEventRepository()
    private val closures = InMemoryClosureRepository()
    private val inner = InMemoryScheduleStateRepository()
    private val states = RecordingStates(inner)
    private val schedules = InMemoryScheduleRepository(closures, inner)
    private val groups = InMemoryGroupRepository()
    private var today = LocalDate.parse("2026-04-15")

    private val recompute = RecomputeSchedules(
        schedules, states, events, closures, groups, assets, InMemorySeasonActivationRepository(),
        Today { today }, Clock { dayMillis("2026-04-15") },
    ) { ZoneOffset.UTC }

    private val schedule = scheduleOf(
        id = "s1",
        assetId = "a1",
        timeInterval = 1,
        timeUnit = RecurrenceUnit.MONTH,
        anchorOn = "2026-01-01",
        servicePolicy = ServicePolicy.IN_SERVICE_AT_START,
        policyOffsetDays = 0,
    )

    private suspend fun seed() {
        assets.upsert(
            SeasonFixtures.assetOf(id = "a1", name = "Pump", mode = SeasonMode.CALENDAR, seasonStart = "10-15", seasonEnd = "04-15"),
        )
        schedules.upsert(schedule)
    }

    /** A row computed for today is the row: the very object stored, with nothing re-derived or written. */
    @Test
    fun aFreshRowIsReturned() = runTest {
        seed()
        recompute.all()
        assertEquals(1, states.upserts, "the explicit rebuild writes once")
        val stored = inner.get(schedule.id)!!.copy(computedAt = 42L)
        inner.upsert(stored)

        val read = recompute.readState(schedule)
        assertSame(stored, read)
        assertEquals(1, states.upserts)
    }

    /**
     * A row left from yesterday — here one computed in season on 15 Apr, read on 16 Apr when the
     * season has ended — is derived again for today, and so is a schedule with no row at all. Neither
     * read writes: the table still holds yesterday's row, and the missing row is still missing.
     */
    @Test
    fun aStaleOrMissingRowIsDerivedAndNothingIsWritten() = runTest {
        seed()
        recompute.all()
        val yesterday = inner.get(schedule.id)!!
        assertEquals(PolicyPhase.ACTIVE, yesterday.policyPhase)
        val writesBefore = states.upserts

        today = LocalDate.parse("2026-04-16")
        val stale = recompute.readState(schedule)
        assertEquals("2026-04-16", stale.computedForOn)
        assertEquals(PolicyPhase.DORMANT, stale.policyPhase, "the season ended overnight")
        assertNotEquals(yesterday, stale)
        assertEquals(yesterday, inner.get(schedule.id), "the stored row is untouched")

        val unseen = schedule.copy(id = ScheduleId("s2"))
        schedules.upsert(unseen)
        val missing = recompute.readState(unseen)
        assertEquals(unseen.id, missing.scheduleId)
        assertEquals("2026-04-16", missing.computedForOn)
        assertEquals(null, inner.get(unseen.id), "a missing row stays missing")

        assertEquals(writesBefore, states.upserts, "no read path writes schedule_state")
    }
}
