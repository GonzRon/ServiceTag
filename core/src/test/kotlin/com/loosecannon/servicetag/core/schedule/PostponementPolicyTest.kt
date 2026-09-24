package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.SeasonInputs
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.SeasonFixtures.seasonOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Inv. 101 (S-1, O-6): a postponed date goes through the policy like a raw due — re-entered after
 * dormancy, moved out of a break — is never moved earlier, and is never OVERDUE on the first
 * in-season day. The occurrence key stays `computedDueOn` throughout.
 */
class PostponementPolicyTest {

    private fun on(date: String) = LocalDate.parse(date)

    /** The mower's season (04-15 → 10-31) and break, with an AT_START monthly schedule. */
    private val mowerSeason = SeasonFixtures.mowerAsset().seasonInputs(emptyList())
    private val monthly = scheduleOf(
        timeInterval = 1,
        timeUnit = RecurrenceUnit.MONTH,
        timeBasis = TimeBasis.FIXED,
        anchorOn = "2026-10-01",
        leadDays = 7,
        servicePolicy = ServicePolicy.IN_SERVICE_AT_START,
        policyOffsetDays = 0,
        createdOn = "2026-09-01",
    )

    private fun rebuild(schedule: MaintenanceSchedule, at: String, season: SeasonInputs) =
        ScheduleRecompute.rebuild(schedule, emptyList(), emptyList(), emptyList(), on(at), ZoneOffset.UTC, season)

    /**
     * Postponed into the off-season (10 Nov, after the season ends on 31 Oct): OUT OF SEASON while the
     * season is shut, then re-entered at the next start and DUE — not OVERDUE — on 15 Apr.
     */
    @Test
    fun aPostponementIntoDormancyIsReEnteredNotOverdue() {
        val postponed = monthly.copy(postponedDueOn = "2026-11-10")
        val dormant = rebuild(postponed, "2026-11-11", mowerSeason)
        assertEquals(DueStatus.INACTIVE_SEASON, statusOf(postponed, dormant, on("2026-11-11")))

        val reentry = rebuild(postponed, "2027-04-15", mowerSeason)
        assertEquals("2026-10-01", reentry.computedDueOn, "the key never moves")
        assertEquals("2026-11-10", reentry.effectiveDueOn)
        assertEquals("2027-04-15", reentry.actionableDueOn)
        assertEquals(PolicyReason.SEASON_START, reentry.policyReason)
        assertEquals(DueStatus.DUE, statusOf(postponed, reentry, on("2027-04-15")))
    }

    /**
     * Postponed into the break on a YEAR_ROUND asset: moved to the first day after it, and DEFERRED
     * (held) rather than DUE while the break lasts.
     */
    @Test
    fun aPostponementIntoTheBreakMovesAfterIt() {
        val generatorSeason = SeasonFixtures.generatorAsset().seasonInputs(emptyList())
        val schedule = SeasonFixtures.generatorSchedule().copy(postponedDueOn = "2026-12-15")
        val state = rebuild(schedule, "2026-12-10", generatorSeason)
        assertEquals("2026-06-20", state.computedDueOn)
        assertEquals("2027-03-01", state.actionableDueOn)
        assertEquals(PolicyReason.AFTER_BREAK, state.policyReason)
        assertEquals(DueStatus.DEFERRED, statusOf(schedule, state, on("2026-12-10")))

        // PRE_SERVICE likewise: a postponement inside the break moves past it.
        val snow = SeasonFixtures.snowblowerSchedule().copy(postponedDueOn = "2026-12-25")
        val snowState = ScheduleRecompute.rebuild(
            snow, listOf(SeasonFixtures.snowblowerLastDone()), emptyList(), emptyList(), on("2026-12-10"), ZoneOffset.UTC,
            SeasonFixtures.snowblowerAsset().seasonInputs(emptyList()),
        )
        assertEquals("2026-12-20", snowState.computedDueOn)
        assertEquals("2027-03-01", snowState.actionableDueOn)
        assertEquals(PolicyReason.AFTER_BREAK, snowState.policyReason)
    }

    /**
     * Inv. 101: PRE_SERVICE never pulls a postponement earlier. The snowblower's raw due would be
     * pulled to 1 Nov (it opened long before); postponed to 20 Nov, it stays on 20 Nov.
     */
    @Test
    fun preServiceNeverMovesAPostponementEarlier() {
        val snowSeason = SeasonFixtures.snowblowerAsset().seasonInputs(emptyList())
        val events = listOf(SeasonFixtures.snowblowerLastDone())
        val raw = SeasonFixtures.snowblowerSchedule()
        val pulled = ScheduleRecompute.rebuild(raw, events, emptyList(), emptyList(), on("2026-10-01"), ZoneOffset.UTC, snowSeason)
        assertEquals("2026-11-01", pulled.actionableDueOn, "the raw due is pulled")

        val postponed = raw.copy(postponedDueOn = "2026-11-20")
        val kept = ScheduleRecompute.rebuild(postponed, events, emptyList(), emptyList(), on("2026-10-01"), ZoneOffset.UTC, snowSeason)
        assertEquals("2026-11-20", kept.actionableDueOn, "the postponement is never moved earlier")
        assertEquals(PolicyReason.NONE, kept.policyReason)

        // And on a MANUAL asset with a break (O-3's boundary), the same.
        val manual = seasonOf(SeasonMode.MANUAL, breakStart = "12-01", breakEnd = "02-28")
        val outcome = ServicePolicyEngine.evaluate(
            PolicyInputs(ServicePolicy.PRE_SERVICE, -30, on("2026-12-20"), on("2026-11-25"), on("2026-01-01")),
            SeasonContext.of(manual),
            on("2026-10-01"),
        )
        assertEquals(on("2026-11-25"), outcome.actionableOn)
    }
}
