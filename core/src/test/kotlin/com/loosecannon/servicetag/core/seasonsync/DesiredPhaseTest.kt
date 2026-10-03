package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome.NoDecision
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome.Observed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #16 (C6; row 6, AC6, I6) — the effective season: a forced phase whatever HA said, FOLLOW only from this request's
 * own answer, and no decision without one.
 */
class DesiredPhaseTest {

    private val on = Observed(HaSwitchState.ON, "2026-10-01T06:00:00+00:00")
    private val off = Observed(HaSwitchState.OFF, null)
    private val everyAnswer: List<HaReadOutcome?> =
        listOf(on, off, null) + SyncErrorKind.entries.map { NoDecision(it, null) }

    @Test
    fun forceInAndForceOutIgnoreTheRead() {
        for (fresh in everyAnswer) {
            assertEquals(SeasonPhase.IN_SEASON, desiredPhase(SyncMode.FORCE_IN, fresh), "FORCE_IN with $fresh")
            assertEquals(SeasonPhase.OUT_OF_SEASON, desiredPhase(SyncMode.FORCE_OUT, fresh), "FORCE_OUT with $fresh")
        }
    }

    @Test
    fun followMapsOnAndOff() {
        assertEquals(SeasonPhase.IN_SEASON, desiredPhase(SyncMode.FOLLOW, on))
        assertEquals(SeasonPhase.OUT_OF_SEASON, desiredPhase(SyncMode.FOLLOW, off))
    }

    @Test
    fun followWithoutAFreshObservationDecidesNothing() {
        assertNull(desiredPhase(SyncMode.FOLLOW, null), "no result of this request")
        for (kind in SyncErrorKind.entries) {
            assertNull(desiredPhase(SyncMode.FOLLOW, NoDecision(kind, "detail")), "FOLLOW with $kind")
        }
    }
}
