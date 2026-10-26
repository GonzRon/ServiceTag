package com.loosecannon.servicetag.core.health

import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.schedule.SeasonContext
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonActivation
import com.loosecannon.servicetag.core.seasonsync.ownSeasonInputs
import com.loosecannon.servicetag.core.testing.HealthFixtures
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.scheduleOf
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * #125 (H1b, U-C2) — the health engine judges each linked schedule against the season its due rules read: a schedule
 * following its own Home Assistant season against its own (`RecomputeSchedules.seasonFor`'s answer, carried on the
 * link), every other link against its asset's, as before. The "Example Heater" is in use 11-01…03-31 (`CALENDAR`);
 * its opening service follows the winterize switch, which came on 10-20. Every name is fictional.
 */
class ScheduleOwnSeasonHealthTest {

    private val heater = SeasonFixtures.assetOf("heater", "Example Heater", SeasonMode.CALENDAR, "11-01", "03-31")

    /** The opening service: its own season, `IN_SERVICE_AT_START` at 0, its yearly due long reached. */
    private val open = schedule("s-open", ServicePolicy.IN_SERVICE_AT_START, RecurrenceUnit.YEAR, "2026-01-15", own = true)

    /** A weekly check on the asset's season. */
    private val weekly = schedule("s-weekly", ServicePolicy.IN_SERVICE_AT_START, RecurrenceUnit.WEEK, "2026-01-05", own = false)

    /** A check on its own season that counts from the asset's opening service (#124), which nobody has done. */
    private val check = schedule(
        "s-check", ServicePolicy.IN_SERVICE_AFTER_OPENING_SERVICE, RecurrenceUnit.WEEK, "2026-01-05", own = true, offset = 7,
    )

    private val subjects = listOf(open, weekly, check).mapIndexed { index, s -> medium("h-${s.id.value}", s.id.value, index) }

    private fun schedule(
        id: String,
        policy: ServicePolicy,
        unit: RecurrenceUnit,
        anchorOn: String,
        own: Boolean,
        offset: Int = 0,
    ): MaintenanceSchedule = scheduleOf(
        id, assetId = "heater", title = "Example $id", timeInterval = 1, timeUnit = unit, anchorOn = anchorOn,
        servicePolicy = policy, policyOffsetDays = offset,
    ).copy(seasonSource = if (own) ScheduleSeasonSource.OWN else ScheduleSeasonSource.ASSET)

    private fun medium(id: String, scheduleId: String, sortOrder: Int): HealthSubject = HealthFixtures.subjectOf(
        id = id, assetId = "heater", kind = HealthSubjectKind.MEDIUM, driver = HealthDriver.MAINTENANCE_OVERDUE,
        scheduleId = scheduleId, nominalUntil = 2, warningFrom = 7, criticalFrom = 14, sortOrder = sortOrder,
    )

    private fun row(action: SeasonAction, on: String, scheduleId: String) =
        ScheduleSeasonActivation("$scheduleId-$action-$on", ScheduleId(scheduleId), action, on, dayMillis(on))

    /**
     * The asset's health on [date], each link built as the read model builds it: its state rebuilt from the season
     * its due rules read, and for an `OWN` link that season carried on the link.
     */
    private fun healthOn(date: String, rows: List<ScheduleSeasonActivation>): Map<String, SubjectHealth> {
        val today = LocalDate.parse(date)
        val assetSeason = heater.seasonInputs(emptyList())
        val links = listOf(open, weekly, check).associate { s ->
            val own = heater.ownSeasonInputs(rows.filter { it.scheduleId == s.id })
            s.id to when (s.seasonSource) {
                ScheduleSeasonSource.ASSET -> HealthFixtures.linkedOn(s, emptyList(), today, assetSeason)
                ScheduleSeasonSource.OWN ->
                    HealthFixtures.linkedOn(s, emptyList(), today, own).copy(ownSeason = SeasonContext.of(own))
            }
        }
        return AssetHealthEngine.evaluate(heater, subjects, links, emptyList(), { true }, SeasonContext.of(assetSeason), today)
            .subjects.associateBy { it.subject.scheduleId!!.value }
    }

    @Test
    fun anOwnSeasonLinkCountsItsLateDaysFromItsOwnStartWhileTheAssetWaitsForItsWindow() {
        val starts = listOf(row(SeasonAction.START, "2026-10-20", "s-open"), row(SeasonAction.START, "2026-10-20", "s-check"))
        val health = healthOn("2026-10-25", starts)

        val open = health.getValue("s-open")
        assertEquals(5L, (open.value as SubjectValue.Scored).trackedDays, "late 10-21…10-25 against its own start")
        assertEquals(DriverLine.Overdue("Example s-open", 5), open.lines.first())
        assertEquals(
            SubjectValue.NotTracked(NotTrackedReason.OUT_OF_SEASON),
            health.getValue("s-weekly").value,
            "the asset's other schedule keeps the window: out of season until 11-01",
        )
    }

    @Test
    fun anOwnSeasonLinkWaitingForTheOpeningServiceInItsOwnSeasonStaysTrackedAtNoLateDay() {
        val health = healthOn("2026-10-25", listOf(row(SeasonAction.START, "2026-10-20", "s-check")))

        val check = health.getValue("s-check")
        assertEquals(0L, (check.value as SubjectValue.Scored).trackedDays, "in its own season, waiting: tracked, never out of season")
        assertEquals(listOf<DriverLine>(DriverLine.UpToDate("Example s-check")), check.lines)
    }

    @Test
    fun outOfItsOwnSeasonAnOwnLinkIsNotTrackedWhileTheAssetIsInSeason() {
        val rows = listOf(row(SeasonAction.START, "2026-10-20", "s-open"), row(SeasonAction.END, "2026-11-30", "s-open"))
        val health = healthOn("2026-12-05", rows)

        assertEquals(SubjectValue.NotTracked(NotTrackedReason.OUT_OF_SEASON), health.getValue("s-open").value)
        assertEquals(listOf<DriverLine>(DriverLine.NotTrackedOutOfSeason), health.getValue("s-open").lines)
        val weekly = health.getValue("s-weekly").value as SubjectValue.Scored
        assertEquals(34L, weekly.trackedDays, "the asset's schedule: late 11-02…12-05 in the asset's season")
    }

    /**
     * H1b review m2 — N1's rule on an own season: the check's first run of its own season (10-20…11-29, ended by its
     * END on 11-30) lies outside the cycle running today (from its START on 12-10), so it is counted as
     * `IN_SERVICE_AT_START` + 7 against its own rows: late 10-28…11-29, 33 days. Today's cycle adds none: its opening
     * service — the asset's readiness, done 10-22 — puts it due on 12-17. Counted as the policy it is, the run would
     * give 31 (late from 10-30); against the asset's window, another number again. A PART subject, so no MEDIUM restart
     * hides the earlier run.
     */
    @Test
    fun anOwnSeasonsEndedRunKeepsItsLateDaysCountedAsAtStartAgainstItsOwnRows() {
        val today = LocalDate.parse("2026-12-12")
        val rows = listOf(
            row(SeasonAction.START, "2026-10-20", "s-check"),
            row(SeasonAction.END, "2026-11-30", "s-check"),
            row(SeasonAction.START, "2026-12-10", "s-check"),
        )
        val own = heater.ownSeasonInputs(rows)
        val link = HealthFixtures.linkedOn(check, emptyList(), today, own).let { linked ->
            linked.copy(inputs = linked.inputs.copy(openingServiceOn = LocalDate.parse("2026-10-22")), ownSeason = SeasonContext.of(own))
        }
        val part = HealthFixtures.subjectOf(
            id = "p-check", assetId = "heater", kind = HealthSubjectKind.PART, driver = HealthDriver.MAINTENANCE_OVERDUE,
            scheduleId = "s-check", nominalUntil = 2, warningFrom = 7, criticalFrom = 14, sortOrder = 0,
        )

        val health = AssetHealthEngine.evaluate(
            heater, listOf(part), mapOf(check.id to link), emptyList(), { true },
            SeasonContext.of(heater.seasonInputs(emptyList())), today,
        ).subjects.single()

        assertEquals(33L, (health.value as SubjectValue.Scored).trackedDays, "late 10-28…11-29, its own ended run")
    }

    @Test
    fun aLinkIsJudgedAgainstExactlyOneSeasonAndAReaderThatForgetsTheOwnOneIsRefused() {
        val today = LocalDate.parse("2026-10-25")
        val assetSeason = heater.seasonInputs(emptyList())
        val own = heater.ownSeasonInputs(emptyList())
        fun evaluate(link: LinkedSchedule) = AssetHealthEngine.evaluate(
            heater, subjects.filter { it.scheduleId == link.schedule.id }, mapOf(link.schedule.id to link), emptyList(), { true },
            SeasonContext.of(assetSeason), today,
        )

        assertFailsWith<IllegalArgumentException>("an OWN link with no season of its own") {
            evaluate(HealthFixtures.linkedOn(open, emptyList(), today, own))
        }
        assertFailsWith<IllegalArgumentException>("an ASSET link carrying a season of its own") {
            evaluate(HealthFixtures.linkedOn(weekly, emptyList(), today, assetSeason).copy(ownSeason = SeasonContext.of(own)))
        }
    }
}
