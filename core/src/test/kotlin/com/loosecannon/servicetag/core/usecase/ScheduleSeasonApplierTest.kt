package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.LastAppliedSource
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonActivation
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonRecorded
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.core.testing.scheduleSeasonActivationOf
import com.loosecannon.servicetag.core.testing.scheduleSeasonBindingOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * #125 (U-C5; row h11) — the applier: one fresh read, one write, at most one row of the **schedule's own** season,
 * decided against that season and never the asset's, and the binding's status beside it. The "Example Heater" is in
 * use 11-01…03-31; every name is fictional.
 */
class ScheduleSeasonApplierTest {
    private val h = ScheduleSeasonHarness()

    /** An `OWN` schedule as a link leaves it, its binding stored as it is. */
    private suspend fun linked(status: ScheduleStatus = ScheduleStatus.ACTIVE) {
        h.schedule("s-own", policy = ServicePolicy.IN_SERVICE_AT_START, offset = 0, status = status, source = ScheduleSeasonSource.OWN)
        h.bindings.insert(scheduleSeasonBindingOf("s-own"))
    }

    private fun dated(): List<Pair<SeasonAction, String>> =
        h.rows.rows.values.sortedBy { it.occurredOn }.map { it.action to it.occurredOn }

    @Test fun onOverItsOwnOffSeasonWritesOneStartDatedTheApplyDayWhateverTheAssetsWindowSays() = runTest {
        h.on("2026-12-01") // the asset is in season; the schedule's own season has no row
        h.asset()
        linked()

        val written = assertIs<ScheduleSeasonRecorded.Recorded>(h.answer("s-own", HaSwitchState.ON))

        assertEquals(listOf(SeasonAction.START to "2026-12-01"), dated())
        assertEquals(
            ScheduleSeasonActivation(h.rows.rows.values.single().id, ScheduleId("s-own"), SeasonAction.START, "2026-12-01", h.now),
            h.rows.rows.values.single(),
            "dated the day it is applied (R16-3), not by HA's text",
        )
        assertEquals(
            listOf(SeasonAction.START, "2026-12-01", h.now, LastAppliedSource.HOME_ASSISTANT, 2L),
            with(written.binding) { listOf(appliedAction, appliedOn, appliedAt, lastAppliedSource, revision) },
        )
        assertEquals(written.binding, h.bindings.rows.getValue("s-own"))
        assertEquals("2026-12-01", h.states.rows.getValue("s-own").actionableDueOn, "recomputed in the write")
        assertTrue(h.raw.activations.rows.isEmpty(), "never an asset activation")
    }

    @Test fun offOverItsOwnSeasonWritesOneEndWhileTheAssetIsOutOfSeason() = runTest {
        h.on("2026-10-25")
        h.asset()
        linked()
        h.rows.insert(scheduleSeasonActivationOf("r-1", "s-own", SeasonAction.START, "2026-10-20"))

        h.answer("s-own", HaSwitchState.OFF)

        assertEquals(listOf(SeasonAction.START to "2026-10-20", SeasonAction.END to "2026-10-25"), dated())
        assertEquals(SeasonAction.END, h.bindings.rows.getValue("s-own").appliedAction)
    }

    @Test fun anUnchangedAnswerWritesNoRowButStillRecordsTheRead() = runTest {
        h.on("2026-10-20")
        h.asset()
        linked()
        h.answer("s-own", HaSwitchState.ON)
        h.on("2026-10-21")

        val again = assertIs<ScheduleSeasonRecorded.Recorded>(h.answer("s-own", HaSwitchState.ON))

        assertEquals(listOf(SeasonAction.START to "2026-10-20"), dated())
        assertEquals(3L to h.now - 10_000L, again.binding.revision to again.binding.lastSuccessAt)
        assertEquals("2026-10-20", again.binding.appliedOn, "the provenance stays the last change")
    }

    @Test fun aStaleRevisionOrAStoppedBindingIsDroppedAndWritesNothing() = runTest {
        h.on("2026-10-20")
        h.asset()
        linked()
        val stored = h.bindings.rows.getValue("s-own")

        val stale = h.applier.run(ScheduleId("s-own"), stored.revision + 1, h.now, h.now, HaReadOutcome.Observed(HaSwitchState.ON, null))
        assertEquals(ScheduleSeasonRecorded.Dropped, stale)
        h.bindings.rows["s-own"] = stored.copy(enabled = false)
        assertEquals(ScheduleSeasonRecorded.Dropped, h.answer("s-own", HaSwitchState.ON))

        assertEquals(emptyList(), dated())
        assertEquals(stored.copy(enabled = false), h.bindings.rows.getValue("s-own"))
    }

    @Test fun anArchivedScheduleOrOneWhoseAssetIsNotMaintainedHereTakesNoDecision() = runTest {
        h.on("2026-10-20")
        h.asset()
        linked(status = ScheduleStatus.ARCHIVED)
        h.answer("s-own", HaSwitchState.ON)
        assertEquals(SyncErrorKind.NOT_MAINTAINED_HERE, h.bindings.rows.getValue("s-own").errorKind)

        h.raw.schedules.rows["s-own"] = h.stored("s-own").copy(status = ScheduleStatus.ACTIVE)
        h.raw.assets.rows["heater"] = h.raw.assets.rows.getValue("heater").copy(retiredOn = "2026-10-01")
        h.answer("s-own", HaSwitchState.ON)
        assertEquals(SyncErrorKind.NOT_MAINTAINED_HERE, h.bindings.rows.getValue("s-own").errorKind)
        assertEquals(emptyList(), dated())
    }

    @Test fun noDecisionRecordsItsKindAndMovesNoSeason() = runTest {
        h.on("2026-10-20")
        h.asset()
        linked()

        h.applier.run(ScheduleId("s-own"), 1, h.now, h.now, HaReadOutcome.NoDecision(SyncErrorKind.UNREACHABLE, null))

        val binding = h.bindings.rows.getValue("s-own")
        assertEquals(SyncErrorKind.UNREACHABLE to null, binding.errorKind to binding.lastSuccessAt)
        assertEquals(emptyList(), dated())
    }

    @Test fun todayBeforeItsLatestRowIsDateBeforeHistory() = runTest {
        h.on("2026-10-20")
        h.asset()
        linked()
        h.rows.insert(scheduleSeasonActivationOf("r-1", "s-own", SeasonAction.END, "2026-10-25"))

        h.answer("s-own", HaSwitchState.ON)

        assertEquals(SyncErrorKind.DATE_BEFORE_HISTORY, h.bindings.rows.getValue("s-own").errorKind)
        assertEquals(listOf(SeasonAction.END to "2026-10-25"), dated())
    }
}
