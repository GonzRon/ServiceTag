package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonRefusal
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonRefused
import com.loosecannon.servicetag.core.testing.scheduleSeasonActivationOf
import com.loosecannon.servicetag.core.usecase.ScheduleSeasonHarness.Companion.HEATER
import com.loosecannon.servicetag.core.usecase.ScheduleSeasonHarness.Companion.SWITCH
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * #125 (U-C2, U-C4; rows h1–h3, h20) — the season an `OWN` schedule's due rules read: `MANUAL`, no window, the asset's
 * break and its own rows, through the untouched engine; the asset's other schedules keep the asset's season. A
 * `CALENDAR` "Example Heater" in use 11-01…03-31; every name is fictional and the zone UTC.
 */
class ScheduleSeasonRecomputeTest {
    private val h = ScheduleSeasonHarness()

    private suspend fun rebuild() = h.recompute.forAsset(AssetId(HEATER))

    // ---- h1: its own START decides its date; the asset's other schedules keep the window ----------------------

    @Test fun anOwnScheduleIsDueOnItsOwnStartWhileTheAssetsOtherSchedulesKeepTheWindow() = runTest {
        h.on("2026-10-20")
        h.asset()
        h.schedule("s-own", policy = ServicePolicy.IN_SERVICE_AT_START, offset = 0, source = ScheduleSeasonSource.OWN)
        h.schedule("s-asset", policy = ServicePolicy.IN_SERVICE_AT_START, offset = 0)
        h.rows.insert(scheduleSeasonActivationOf("r-1", "s-own", SeasonAction.START, "2026-10-20"))

        rebuild()

        val own = h.state("s-own")
        assertEquals("2026-10-20" to PolicyReason.SEASON_START, own.actionableDueOn to own.policyReason, "its START at offset 0")
        assertEquals(PolicyPhase.ACTIVE, own.policyPhase)
        val asset = h.state("s-asset")
        assertEquals("2026-11-01" to PolicyPhase.DORMANT, asset.actionableDueOn to asset.policyPhase, "the window's next start")
        val inputs = h.recompute.seasonFor(h.stored("s-own"))
        assertEquals(SeasonMode.MANUAL to null, inputs?.mode to inputs?.seasonStartMmdd, "MANUAL, no window: the one answer")
    }

    // ---- h2: PRE_SERVICE counts back from the break only; no break, no link -----------------------------------

    @Test fun anOwnPreServiceScheduleCountsBackFromTheBreakNeverTheSeasonStart() = runTest {
        h.on("2026-04-15")
        h.asset(breakStart = "06-01", breakEnd = "08-31")
        h.schedule("s-own", policy = ServicePolicy.PRE_SERVICE, offset = -14, anchorOn = "2026-05-25", source = ScheduleSeasonSource.OWN)
        h.schedule("s-asset", policy = ServicePolicy.PRE_SERVICE, offset = -14, anchorOn = "2026-05-25")

        rebuild()

        val own = h.state("s-own")
        assertEquals("2026-05-18" to PolicyReason.BEFORE_BREAK, own.actionableDueOn to own.policyReason, "14 days before the break")
        val asset = h.state("s-asset")
        assertEquals("2026-05-25" to PolicyReason.NONE, asset.actionableDueOn to asset.policyReason, "well before 11-01 less 14")
    }

    @Test fun aPreServiceScheduleOnAnAssetWithNoBreakCannotLink() = runTest {
        h.asset()
        h.connect()
        h.schedule("s-pre", policy = ServicePolicy.PRE_SERVICE, offset = -14)
        val before = h.stored("s-pre")

        val refused = assertFailsWith<ScheduleSeasonRefused> { h.link.run(ScheduleId("s-pre"), SWITCH) }

        assertEquals(ScheduleSeasonRefusal.STRANDS_PRE_SERVICE, refused.reason)
        assertEquals(before, h.stored("s-pre"))
        assertTrue(h.bindings.rows.isEmpty())
    }

    // ---- h3: out of its own season it waits; the asset's break quiets it as it does the others -----------------

    @Test fun outOfItsOwnSeasonItWaitsWithNoPredictedStart() = runTest {
        h.on("2026-12-01") // in the asset's window
        h.asset()
        h.schedule("s-own", policy = ServicePolicy.IN_SERVICE_AT_START, offset = 0, source = ScheduleSeasonSource.OWN)
        h.rows.insert(scheduleSeasonActivationOf("r-1", "s-own", SeasonAction.START, "2026-10-20"))
        h.rows.insert(scheduleSeasonActivationOf("r-2", "s-own", SeasonAction.END, "2026-11-20"))

        rebuild()

        val own = h.state("s-own")
        assertNull(own.actionableDueOn, "a Home Assistant start is never predicted")
        assertEquals(PolicyReason.AWAITING_START to PolicyPhase.DORMANT, own.policyReason to own.policyPhase)
    }

    @Test fun theAssetsBreakQuietsAnOwnScheduleAsItQuietsTheAssetsOwn() = runTest {
        h.on("2026-12-25")
        h.asset(breakStart = "12-20", breakEnd = "01-05")
        h.schedule("s-own", policy = ServicePolicy.IN_SERVICE_AT_START, offset = 0, source = ScheduleSeasonSource.OWN)
        h.schedule("s-asset", policy = ServicePolicy.IN_SERVICE_AT_START, offset = 0)
        h.rows.insert(scheduleSeasonActivationOf("r-1", "s-own", SeasonAction.START, "2026-10-20"))

        rebuild()

        for (id in listOf("s-own", "s-asset")) {
            assertTrue(h.state(id).quiet, "$id is quiet in the break")
            assertEquals(LocalDate.parse("2027-01-06"), h.recompute.quietUntil(h.stored(id)), "$id until the first allowed day")
        }
    }

    // ---- h20: the unlink is the return, explicitly; the history stays ------------------------------------------

    @Test fun theUnlinkedScheduleReadsTheAssetsWindowFromTheUnlinkOnAndKeepsItsRows() = runTest {
        h.on("2026-10-20")
        h.asset()
        h.connect()
        h.schedule("s-own", policy = ServicePolicy.IN_SERVICE_AT_START, offset = 0)
        h.link.run(ScheduleId("s-own"), SWITCH)
        h.answer("s-own", HaSwitchState.ON)
        assertEquals("2026-10-20", h.state("s-own").actionableDueOn)

        h.on("2026-10-25")
        rebuild()
        assertEquals("2026-10-20", h.state("s-own").actionableDueOn, "still its own season: no silent return")

        h.unlink.run(ScheduleId("s-own"))

        val state = h.states.rows.getValue("s-own")
        assertEquals("2026-11-01" to PolicyPhase.DORMANT, state.actionableDueOn to state.policyPhase, "the asset's window, written by the unlink")
        assertEquals(ScheduleSeasonSource.ASSET, h.stored("s-own").seasonSource)
        assertEquals(listOf(SeasonAction.START to "2026-10-20"), h.rows.rows.values.map { it.action to it.occurredOn }, "kept as history")
        assertEquals(SeasonMode.CALENDAR, h.recompute.seasonFor(h.stored("s-own"))?.mode)
    }
}
