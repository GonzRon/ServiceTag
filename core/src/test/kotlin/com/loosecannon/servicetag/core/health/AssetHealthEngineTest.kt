package com.loosecannon.servicetag.core.health

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.testing.HealthFixtures
import com.loosecannon.servicetag.core.testing.HealthFixtures.SPEC_DAY
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.scheduleOf
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Spec §6.8 and §7.1's NOT TRACKED cases, the driver-line data (§6.6) and §12.4's fixtures. */
class AssetHealthEngineTest {

    private val asset = SeasonFixtures.assetOf(id = "a1", name = "Pump", mode = SeasonMode.YEAR_ROUND)

    /** Yearly, anchored 15 Aug 2026 and never done: on the spec's date it is 40 days late. */
    private fun lateSchedule(id: String, status: ScheduleStatus = ScheduleStatus.ACTIVE, postponedDueOn: String? = null) = scheduleOf(
        id = id, title = "Seal check $id", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = "2026-08-15",
        createdOn = "2026-01-01", status = status, postponedDueOn = postponedDueOn,
    )

    private fun overdueSubject(id: String, scheduleId: String?, kind: HealthSubjectKind = HealthSubjectKind.PART, sortOrder: Int = 0): HealthSubject =
        HealthFixtures.subjectOf(
            id = id, assetId = "a1", kind = kind, driver = HealthDriver.MAINTENANCE_OVERDUE, scheduleId = scheduleId,
            nominalUntil = 14, warningFrom = 45, criticalFrom = 120, sortOrder = sortOrder,
        )

    @Test
    fun notTrackedReasonsAndTheirLines() {
        val schedules = listOf(
            lateSchedule("s-other").copy(target = ScheduleTarget.AssetTarget(AssetId("a2"))),
            scheduleOf(id = "s-group", groupId = "g1", title = "Group round", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = "2026-08-15"),
            scheduleOf(id = "s-meter", title = "Meter only", meterDefinitionId = "hours", meterInterval = 100.0, anchorMeter = 0.0),
            lateSchedule("s-archived", ScheduleStatus.ARCHIVED),
            lateSchedule("s-paused", ScheduleStatus.PAUSED),
            // The order: a group target that is also archived is an invalid link first.
            scheduleOf(id = "s-group-archived", groupId = "g1", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = "2026-08-15", status = ScheduleStatus.ARCHIVED),
        )
        val subjects = listOf(
            overdueSubject("h1-missing", "s-gone", sortOrder = 1),
            overdueSubject("h2-unlinked", null, sortOrder = 2),
            overdueSubject("h3-other-asset", "s-other", sortOrder = 3),
            overdueSubject("h4-group", "s-group", sortOrder = 4),
            overdueSubject("h5-no-time-rule", "s-meter", sortOrder = 5),
            overdueSubject("h6-archived", "s-archived", sortOrder = 6),
            overdueSubject("h7-paused", "s-paused", sortOrder = 7),
            overdueSubject("h8-group-archived", "s-group-archived", sortOrder = 8),
        )
        val result = HealthFixtures.healthOn("2026-09-24", asset, subjects.reversed(), schedules)
        val link = SubjectValue.NotTracked(NotTrackedReason.LINK_INVALID) to listOf(DriverLine.NotTrackedLink)
        assertEquals(
            listOf(
                "h1-missing" to link,
                "h2-unlinked" to link,
                "h3-other-asset" to link,
                "h4-group" to link,
                "h5-no-time-rule" to link,
                "h6-archived" to (SubjectValue.NotTracked(NotTrackedReason.SCHEDULE_ARCHIVED) to emptyList()),
                "h7-paused" to (SubjectValue.NotTracked(NotTrackedReason.SCHEDULE_PAUSED) to listOf(DriverLine.NotTrackedPaused)),
                "h8-group-archived" to link,
            ),
            result.subjects.map { it.subject.id.value to (it.value to it.lines) },
        )
        assertNull(result.aggregate, "nothing contributes: NOT TRACKED, never 100")

        // MEDIUM on an IN_SERVICE schedule, dormant today: the hot tub on the spec's date (F5).
        val tub = HealthFixtures.healthOn(
            "2026-09-24", SeasonFixtures.hotTubAsset(), listOf(HealthFixtures.hotTubWaterCare()),
            listOf(SeasonFixtures.hotTubSchedule()), listOf(SeasonFixtures.hotTubCompletedOnApril11()),
            SeasonFixtures.hotTubActivationsUpTo("2026-09-24"),
        ).subjects.single()
        assertEquals(SubjectValue.NotTracked(NotTrackedReason.OUT_OF_SEASON), tub.value)
        assertEquals(listOf(DriverLine.NotTrackedOutOfSeason), tub.lines)

        // Paused comes before out of season.
        val pausedTub = HealthFixtures.healthOn(
            "2026-09-24", SeasonFixtures.hotTubAsset(), listOf(HealthFixtures.hotTubWaterCare()),
            listOf(SeasonFixtures.hotTubSchedule().copy(status = ScheduleStatus.PAUSED)), listOf(SeasonFixtures.hotTubCompletedOnApril11()),
            SeasonFixtures.hotTubActivationsUpTo("2026-09-24"),
        ).subjects.single()
        assertEquals(SubjectValue.NotTracked(NotTrackedReason.SCHEDULE_PAUSED), pausedTub.value)

        // Only IN_SERVICE: a MEDIUM on a CONTINUOUS schedule of the same dormant asset is tracked.
        val continuousTub = HealthFixtures.healthOn(
            "2026-09-24", SeasonFixtures.hotTubAsset(), listOf(HealthFixtures.hotTubWaterCare()),
            listOf(SeasonFixtures.hotTubSchedule().copy(servicePolicy = ServicePolicy.CONTINUOUS, policyOffsetDays = null)),
            listOf(SeasonFixtures.hotTubCompletedOnApril11()), SeasonFixtures.hotTubActivationsUpTo("2026-09-24"),
        ).subjects.single()
        assertEquals(SubjectValue.Scored(0, HealthBand.CRITICAL, 159), continuousTub.value, "18 Apr → 24 Sep")
    }

    @Test
    fun driverLineData() {
        val t1 = 14
        fun lines(date: String, schedule: MaintenanceSchedule) =
            HealthFixtures.healthOn(date, asset, listOf(overdueSubject("h1", schedule.id.value)), listOf(schedule)).subjects.single()

        val early = lines("2026-08-01", lateSchedule("s1"))
        assertEquals(listOf(DriverLine.UpToDate("Seal check s1")), early.lines, "not yet due")
        assertEquals(SubjectValue.Scored(100, HealthBand.NOMINAL, 0), early.value)

        val postponed = lateSchedule("s1", postponedDueOn = "2026-10-04")
        assertEquals(
            listOf(DriverLine.Postponed("Seal check s1", LocalDate.parse("2026-10-04"))),
            lines("2026-09-24", postponed).lines,
            "40 days late, postponed today to T + 10: postponed, not late",
        )
        assertEquals(listOf(DriverLine.Postponed("Seal check s1", LocalDate.parse("2026-10-04"))), lines("2026-10-04", postponed).lines, "on the date itself")
        assertEquals(
            listOf(DriverLine.Overdue("Seal check s1", 1), DriverLine.Grace(t1)),
            lines("2026-10-05", postponed).lines,
            "once the postponed date has passed it is late again, counted from it",
        )

        assertEquals(listOf(DriverLine.Overdue("Seal check s1", 14), DriverLine.Grace(t1)), lines("2026-08-29", lateSchedule("s1")).lines, "x = t1")
        assertEquals(listOf(DriverLine.Overdue("Seal check s1", 15)), lines("2026-08-30", lateSchedule("s1")).lines, "past the grace period")
        assertEquals(listOf(DriverLine.Overdue("Seal check s1", 40)), lines("2026-09-24", lateSchedule("s1")).lines)
    }

    /** §12.4 at `T` = 2026-09-24. */
    @Test
    fun fixturesAtTheSpecsDate() {
        val day = SPEC_DAY.toString()
        val f1 = HealthFixtures.healthOn(
            day, HealthFixtures.upsAsset(), listOf(HealthFixtures.upsBatteryAge()),
            events = listOf(HealthFixtures.upsBatteryReplaced(), HealthFixtures.upsSelfTest()),
        )
        assertEquals(SubjectValue.Scored(18, HealthBand.CRITICAL, 1576), f1.subjects.single().value, "F1")
        assertEquals(f1.subjects, f1.critical)

        val f2 = HealthFixtures.healthOn(
            day, HealthFixtures.packAsset(), listOf(HealthFixtures.packBatteryAge()),
            events = listOf(HealthFixtures.packBatteryReplaced(), HealthFixtures.packLoadTest()),
        )
        assertEquals(SubjectValue.Scored(47, HealthBand.WARNING, 1233), f2.subjects.single().value, "F2")

        val f3 = HealthFixtures.healthOn(
            day, SeasonFixtures.generatorAsset(), listOf(HealthFixtures.generatorEngineOil()), listOf(SeasonFixtures.generatorSchedule()),
        )
        assertEquals(SubjectValue.Scored(37, HealthBand.WARNING, 96), f3.subjects.single().value, "F3")

        val snowblower = HealthFixtures.healthOn(
            day, SeasonFixtures.snowblowerAsset(), listOf(HealthFixtures.snowblowerEngineOil()), listOf(SeasonFixtures.snowblowerSchedule()),
            listOf(SeasonFixtures.snowblowerLastDone()),
        )
        assertEquals(SubjectValue.Scored(100, HealthBand.NOMINAL, 0), snowblower.subjects.single().value, "F4 snowblower")
        val autumnMower = HealthFixtures.healthOn(
            day, SeasonFixtures.mowerAsset(), listOf(HealthFixtures.mowerEngineOil()), listOf(SeasonFixtures.mowerAutumnSchedule()),
            listOf(SeasonFixtures.mowerAutumnDone()),
        )
        assertEquals(SubjectValue.Scored(100, HealthBand.NOMINAL, 0), autumnMower.subjects.single().value, "F4 mower, R 5 Nov")

        val f5 = HealthFixtures.healthOn(
            day, SeasonFixtures.hotTubAsset(), listOf(HealthFixtures.hotTubWaterCare()), listOf(SeasonFixtures.hotTubSchedule()),
            listOf(SeasonFixtures.hotTubCompletedOnApril11()), SeasonFixtures.hotTubActivationsUpTo(day),
        )
        assertEquals(SubjectValue.NotTracked(NotTrackedReason.OUT_OF_SEASON), f5.subjects.single().value, "F5")
        assertNull(f5.aggregate, "F5: the asset's health is NOT TRACKED")

        // Archived subjects are not reported at all; the rest come in (sortOrder, id) order.
        val archived = HealthFixtures.upsBatteryAge().copy(archivedAt = dayMillis("2026-09-01"))
        assertEquals(emptyList(), HealthFixtures.healthOn(day, HealthFixtures.upsAsset(), listOf(archived), events = listOf(HealthFixtures.upsBatteryReplaced())).subjects)
    }

    /** Spec §6.1, "a subject never changes asset": another asset's subject is not this asset's health. */
    @Test
    fun onlyTheAssetsOwnSubjectsAreRead() {
        val ups = HealthFixtures.upsAsset()
        val own = HealthFixtures.upsBatteryAge()
        val packs = HealthFixtures.packBatteryAge()
        val events = listOf(HealthFixtures.upsBatteryReplaced(), HealthFixtures.packBatteryReplaced())

        val result = HealthFixtures.healthOn(SPEC_DAY.toString(), ups, listOf(packs, own), events = events)
        assertEquals(listOf(own.id), result.subjects.map { it.subject.id }, "the battery pack's subject is not the UPS's")
        assertEquals(SubjectValue.Scored(18, HealthBand.CRITICAL, null), result.aggregate)

        // Nor can another asset's subject be this asset's primary: it reads as missing.
        val following = ups.copy(healthAggregation = HealthAggregation.TRACK_ONE, healthPrimarySubjectId = packs.id)
        val tracked = HealthFixtures.healthOn(SPEC_DAY.toString(), following, listOf(packs, own), events = events)
        assertEquals(SubjectValue.Scored(18, HealthBand.CRITICAL, null), tracked.aggregate)
        assertEquals(true, tracked.fallback, "a foreign primary falls back to WORST")
    }
}
