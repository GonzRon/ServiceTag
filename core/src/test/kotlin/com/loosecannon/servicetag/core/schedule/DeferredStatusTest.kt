package com.loosecannon.servicetag.core.schedule

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.readingOf
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * DEFERRED (spec §4.5, inv. 103): decided after the worst-of fold, from the time side alone, held
 * from `(P ?: R) − leadDays` until the actionable date; it never notifies and never counts as due.
 */
class DeferredStatusTest {

    private fun on(date: String) = LocalDate.parse(date)

    private val mowerSeason = SeasonFixtures.mowerAsset().seasonInputs(emptyList())
    private val januaryMower = SeasonFixtures.mowerSchedule()
    private val mowerHistory = listOf(SeasonFixtures.mowerJanuaryDone())

    private fun statusOn(schedule: MaintenanceSchedule, at: String, extra: List<AssetEvent> = emptyList()): DueStatus {
        val state = ScheduleRecompute.rebuild(
            schedule, mowerHistory + extra, emptyList(), emptyList(), on(at), ZoneOffset.UTC, mowerSeason,
        )
        return statusOf(schedule, state, on(at))
    }

    /**
     * The fold comes first. A held time side counts as OK in it, so a meter side at DUE_SOON or DUE
     * wins the fold and the status is that — never DEFERRED. Only when the fold answers OK does the
     * held time side make it DEFERRED.
     */
    @Test
    fun deferredIsDecidedAfterTheFoldFromTheTimeSide() {
        val metered = januaryMower.copy(meterDefinitionId = DefinitionId("hours"), meterInterval = 50.0, anchorMeter = 100.0, meterLead = 5.0)
        val at = "2027-01-20"
        assertEquals(DueStatus.DEFERRED, statusOn(metered, at), "no reading: the meter side is OK, the time side held")
        assertEquals(
            DueStatus.DUE_SOON,
            statusOn(metered, at, listOf(readingOf("r1", "2027-01-15", "hours", 146.0, assetId = "mow"))),
            "the meter's DUE_SOON wins over a held time side",
        )
        assertEquals(
            DueStatus.DUE,
            statusOn(metered, at, listOf(readingOf("r1", "2027-01-15", "hours", 151.0, assetId = "mow"))),
        )
    }

    @Test
    fun deferredNeverNotifiesNorCountsAsDue() {
        assertFalse(DueStatus.DEFERRED.notifies)
        assertFalse(DueStatus.DEFERRED.countsAsDue)
        assertEquals(DueStatus.entries.last(), DueStatus.DEFERRED, "appended, so the fold's ordinals stand")
        assertEquals(
            listOf(DueStatus.OK, DueStatus.DUE_SOON, DueStatus.DUE, DueStatus.OVERDUE),
            DueStatus.entries.take(4),
        )
    }

    /**
     * The mower of spec §7.3 (R = 12 Jan 2027, A = 1 Apr 2027, lead 14): OK before 29 Dec, DEFERRED
     * from 29 Dec to 31 Mar — including the fortnight before 1 Apr, where the actionable date alone
     * would read DUE SOON — and DUE on 1 Apr.
     */
    @Test
    fun heldOnlyFromDueMinusLeadUntilActionable() {
        val state = ScheduleRecompute.rebuild(januaryMower, mowerHistory, emptyList(), emptyList(), on("2027-01-20"), ZoneOffset.UTC, mowerSeason)
        assertEquals("2027-01-12", state.computedDueOn)
        assertEquals("2027-04-01", state.actionableDueOn)
        assertEquals(PolicyReason.AFTER_BREAK, state.policyReason)

        assertEquals(DueStatus.OK, statusOn(januaryMower, "2026-12-28"))
        assertEquals(DueStatus.DEFERRED, statusOn(januaryMower, "2026-12-29"))
        assertEquals(DueStatus.DEFERRED, statusOn(januaryMower, "2027-02-15"))
        assertEquals(DueStatus.DEFERRED, statusOn(januaryMower, "2027-03-25"))
        assertEquals(DueStatus.DEFERRED, statusOn(januaryMower, "2027-03-31"))
        assertEquals(DueStatus.DUE, statusOn(januaryMower, "2027-04-01"))
        assertEquals(DueStatus.OVERDUE, statusOn(januaryMower, "2027-04-02"))
    }
}
