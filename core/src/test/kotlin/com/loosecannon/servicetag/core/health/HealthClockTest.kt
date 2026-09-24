package com.loosecannon.servicetag.core.health

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonInputs
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.schedule.PolicyInputs
import com.loosecannon.servicetag.core.schedule.SeasonContext
import com.loosecannon.servicetag.core.schedule.ServicePolicyEngine
import com.loosecannon.servicetag.core.testing.HealthFixtures
import com.loosecannon.servicetag.core.testing.HealthFixtures.SPEC_DAY
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.scheduleOf
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec §7's clock: counted days (inv. 100, 112, 114–116), the span implementation against the
 * day-by-day definition, and the three worked timelines' health columns.
 */
class HealthClockTest {

    private fun on(date: String) = LocalDate.parse(date)

    /**
     * Spec §7.1, literally and one day at a time: `d` over `min(O, P ?: R) < d ≤ T`, counted when the
     * phase on `d` is ACTIVE, `d > A(d)` with today's postponement, and — MEDIUM on IN_SERVICE only —
     * `d` is on or after the latest cycle start at or before `T`.
     */
    private fun oracle(link: PolicyInputs, season: SeasonContext?, kind: HealthSubjectKind, today: LocalDate): Long {
        val due = link.postponedDueOn ?: link.rawDueOn ?: return 0
        val inService = link.policy == ServicePolicy.IN_SERVICE_AT_START || link.policy == ServicePolicy.IN_SERVICE_RESUME_CLAMPED
        val restart = if (kind == HealthSubjectKind.MEDIUM && inService) season?.latestCycleStartOnOrBefore(today) else null
        var counted = 0L
        var d = minOf(link.openedOn, due).plusDays(1)
        while (d <= today) {
            val outcome = ServicePolicyEngine.evaluate(link, season, at = d)
            val a = outcome.actionableOn
            if (outcome.phase == PolicyPhase.ACTIVE && a != null && d > a && (restart == null || d >= restart)) counted++
            d = d.plusDays(1)
        }
        return counted
    }

    @Test
    fun theSpanImplementationEqualsTheDayByDayOracle() {
        val random = Random(7_116)
        var rawBeforeOpened = 0
        var postponed = 0
        var restarted = 0
        var moved = 0
        var late = 0
        var dormantSpans = 0
        repeat(500) { case ->
            val inputs = randomSeason(random)
            val season = inputs?.let(SeasonContext::of)
            val policy = ServicePolicy.entries.random(random)
            val offset = when (policy) {
                ServicePolicy.IN_SERVICE_AT_START -> random.nextInt(0, 31)
                ServicePolicy.PRE_SERVICE -> -random.nextInt(1, 61)
                else -> null
            }
            val base = BASE.plusDays(random.nextLong(0, 900))
            val raw = if (random.nextInt(12) == 0) null else base
            val opened = base.plusDays(random.nextLong(-240, 120))
            val postponement = if (raw != null && random.nextInt(3) == 0) base.plusDays(random.nextLong(-40, 200)) else null
            val link = PolicyInputs(policy, offset, raw, postponement, opened)
            val kind = HealthSubjectKind.entries.random(random)

            if (raw != null && raw < opened) rawBeforeOpened++
            if (postponement != null) postponed++
            repeat(3) {
                val today = base.plusDays(random.nextLong(-30, 900))
                val expected = oracle(link, season, kind, today)
                assertEquals(
                    expected,
                    HealthClock.countedDays(link, season, kind, today),
                    "case $case: $link, $kind, T = $today, season = $inputs",
                )
                if (expected > 0) late++
                val a = ServicePolicyEngine.evaluate(link, season, today).actionableOn
                if (a != null && a != (postponement ?: raw)) moved++
                if (kind == HealthSubjectKind.MEDIUM && season?.latestCycleStartOnOrBefore(today) != null) restarted++
                if (season != null && season.phaseAt(today) != season.phaseAt(today.minusDays(200))) dormantSpans++
            }
        }
        // The property is only as good as what it reached.
        assertTrue(rawBeforeOpened > 40, "histories with R < O: $rawBeforeOpened")
        assertTrue(postponed > 40, "postponed occurrences: $postponed")
        assertTrue(restarted > 40, "MEDIUM restarts: $restarted")
        assertTrue(moved > 100, "actionable dates the policy moved: $moved")
        assertTrue(late > 300, "cases with counted days: $late")
        assertTrue(dormantSpans > 100, "cases crossing a phase change: $dormantSpans")
    }

    /**
     * Inv. 116 and 131: a schedule 40 days late, postponed today to T + 10, has no counted day until
     * the postponed actionable date has passed — the earlier lateness is not retained.
     */
    @Test
    fun noDayBeforeThePostponedActionableDateCounts() {
        val raw = SPEC_DAY.minusDays(40)
        val opened = raw.minusDays(365)
        val asIs = PolicyInputs(ServicePolicy.CONTINUOUS, null, raw, null, opened)
        assertEquals(40, HealthClock.countedDays(asIs, null, HealthSubjectKind.PART, SPEC_DAY), "40 days late before the postponement")

        val postponed = asIs.copy(postponedDueOn = SPEC_DAY.plusDays(10))
        assertEquals(0, HealthClock.countedDays(postponed, null, HealthSubjectKind.PART, SPEC_DAY), "today")
        assertEquals(0, HealthClock.countedDays(postponed, null, HealthSubjectKind.PART, SPEC_DAY.plusDays(10)), "on the postponed date")
        assertEquals(1, HealthClock.countedDays(postponed, null, HealthSubjectKind.PART, SPEC_DAY.plusDays(11)), "on T + 11")

        // The postponed **actionable** date, not the postponed date: the generator's open 20 Jun
        // occurrence postponed on 1 Dec to 10 Dec, inside the break, is actionable on 1 Mar 2027.
        val generator = SeasonContext.of(SeasonFixtures.generatorAsset().seasonInputs(emptyList()))
        val intoTheBreak = PolicyInputs(ServicePolicy.IN_SERVICE_AT_START, 0, on("2026-06-20"), on("2026-12-10"), on("2026-01-05"))
        for (date in listOf("2026-12-01", "2026-12-11", "2027-02-28", "2027-03-01")) {
            assertEquals(0, HealthClock.countedDays(intoTheBreak, generator, HealthSubjectKind.PART, on(date)), date)
        }
        assertEquals(1, HealthClock.countedDays(intoTheBreak, generator, HealthSubjectKind.PART, on("2027-03-02")), "the day after 1 Mar")
    }

    /** I7: a never-terminated COMPLETION schedule is due on its anchor, 30 days before it was created. */
    @Test
    fun aCompletionScheduleAnchoredBeforeItsCreationCountsFromTheAnchor() {
        val schedule = scheduleOf(
            timeInterval = 3, timeUnit = RecurrenceUnit.MONTH, timeBasis = TimeBasis.COMPLETION,
            anchorOn = "2026-08-02", createdOn = "2026-09-01",
        )
        val link = HealthFixtures.linkedOn(schedule, emptyList(), SPEC_DAY, null).inputs
        assertEquals(on("2026-08-02"), link.rawDueOn, "R is the anchor")
        assertEquals(on("2026-09-01"), link.openedOn, "O is the creation, after R")
        assertEquals(53, HealthClock.countedDays(link, null, HealthSubjectKind.ASSET, SPEC_DAY), "T − anchor, not T − O (23)")
    }

    /**
     * I7: a 1.3 row whose `updated_at` — seeded into `rule_changed_at` by the 7 → 8 migration — moved
     * after its anchor. O is that later date; the lateness still counts from R.
     */
    @Test
    fun aMigrated13RowWhoseUpdatedAtMovedAfterItsAnchorStillCountsFromItsDue() {
        val schedule = scheduleOf(
            timeInterval = 6, timeUnit = RecurrenceUnit.MONTH, timeBasis = TimeBasis.COMPLETION,
            anchorOn = "2026-07-01", createdOn = "2026-06-01", updatedOn = "2026-08-15",
        )
        val link = HealthFixtures.linkedOn(schedule, emptyList(), SPEC_DAY, null).inputs
        assertEquals(on("2026-07-01"), link.rawDueOn)
        assertEquals(on("2026-08-15"), link.openedOn, "rule_changed_at, seeded from 1.3's updated_at")
        assertEquals(85, HealthClock.countedDays(link, null, HealthSubjectKind.PART, SPEC_DAY), "T − R, not T − O (40)")
    }

    /** §7.2: MANUAL hot tub, "Water care" MEDIUM 2 / 7 / 14, across END and START (H.1c + H.1b). */
    @Test
    fun hotTubWaterCare() {
        val asset = SeasonFixtures.hotTubAsset()
        val schedule = SeasonFixtures.hotTubSchedule()
        val subject = HealthFixtures.hotTubWaterCare()
        val done = listOf(SeasonFixtures.hotTubCompletedOnApril11())
        fun waterCare(date: String, events: List<AssetEvent> = done, activations: List<SeasonActivation> = SeasonFixtures.hotTubActivationsUpTo(date)) =
            HealthFixtures.healthOn(date, asset, listOf(subject), listOf(schedule), events, activations).subjects.single().value

        assertEquals(SubjectValue.Scored(100, HealthBand.NOMINAL, 0), waterCare("2026-04-11"), "Sat 11 Apr: completed")
        assertEquals(SubjectValue.NotTracked(NotTrackedReason.OUT_OF_SEASON), waterCare("2026-04-16"), "Thu 16 Apr: END")
        assertEquals(SubjectValue.NotTracked(NotTrackedReason.OUT_OF_SEASON), waterCare("2026-07-01"), "1 Jul")
        assertEquals(SubjectValue.Scored(100, HealthBand.NOMINAL, 0), waterCare("2026-10-10"), "Sat 10 Oct: START")
        assertEquals(SubjectValue.Scored(92, HealthBand.NOMINAL, 3), waterCare("2026-10-13"))
        assertEquals(SubjectValue.Scored(76, HealthBand.NOMINAL, 5), waterCare("2026-10-15"))
        assertEquals(SubjectValue.Scored(60, HealthBand.WARNING, 7), waterCare("2026-10-17"))
        assertEquals(SubjectValue.Scored(25, HealthBand.CRITICAL, 14), waterCare("2026-10-24"))

        val alt = done + completionOf("e-tub-2", occurredOn = "2026-10-13", occurrenceOn = "2026-04-18", assetId = "tub", scheduleId = "s-tub")
        assertEquals(SubjectValue.Scored(100, HealthBand.NOMINAL, 0), waterCare("2026-10-13", alt), "Tue 13 Oct (alt.): completed")

        // The MEDIUM restart itself: with the contrast history (still open at an END on 22 Apr),
        // the three late days in April are not carried into the new season.
        val contrast = HealthFixtures.hotTubContrastActivations()
        assertEquals(SubjectValue.Scored(60, HealthBand.WARNING, 7), waterCare("2026-10-17", activations = contrast), "restarts at START")
    }

    /** §7.2's contrast (H.1a): a PART freezes while dormant and carries its lateness across. */
    @Test
    fun aPartCarriesAcrossDormancy() {
        val asset = SeasonFixtures.hotTubAsset()
        val schedule = SeasonFixtures.hotTubSchedule()
        val part = HealthFixtures.hotTubWaterCare(kind = HealthSubjectKind.PART)
        val done = listOf(SeasonFixtures.hotTubCompletedOnApril11())
        val contrast = HealthFixtures.hotTubContrastActivations()
        fun value(date: String) = HealthFixtures.healthOn(
            date, asset, listOf(part), listOf(schedule), done, contrast.filter { it.occurredOn <= date },
        ).subjects.single().value

        assertEquals(SubjectValue.Scored(92, HealthBand.NOMINAL, 3), value("2026-04-22"), "19–21 Apr count; the END day is dormant")
        assertEquals(SubjectValue.Scored(92, HealthBand.NOMINAL, 3), value("2026-07-01"), "frozen, and still tracked")
        assertEquals(SubjectValue.Scored(45, HealthBand.WARNING, 10), value("2026-10-17"), "3 + 7 = 10 → 45 WARNING")
    }

    /** §7.3's snowblower: PRE_SERVICE, A = 1 Nov, counted through the season start and the break (inv. 100, 115). */
    @Test
    fun snowblowerCountsFromThePointThroughTheBreak() {
        val asset = SeasonFixtures.snowblowerAsset()
        val schedule = SeasonFixtures.snowblowerSchedule()
        val subject = HealthFixtures.snowblowerEngineOil()
        val done = listOf(SeasonFixtures.snowblowerLastDone())
        fun oil(date: String) = HealthFixtures.healthOn(date, asset, listOf(subject), listOf(schedule), done).subjects.single().value

        val expected = listOf(
            "2026-10-18" to SubjectValue.Scored(100, HealthBand.NOMINAL, 0),
            "2026-11-01" to SubjectValue.Scored(100, HealthBand.NOMINAL, 0),
            "2026-11-15" to SubjectValue.Scored(100, HealthBand.NOMINAL, 14),
            "2026-11-30" to SubjectValue.Scored(81, HealthBand.NOMINAL, 29),
            "2026-12-16" to SubjectValue.Scored(60, HealthBand.WARNING, 45),
            "2027-01-20" to SubjectValue.Scored(44, HealthBand.WARNING, 80),
            "2027-03-01" to SubjectValue.Scored(25, HealthBand.CRITICAL, 120),
            "2027-05-15" to SubjectValue.Scored(0, HealthBand.CRITICAL, 195),
        )
        for ((date, value) in expected) assertEquals(value, oil(date), date)
    }

    /** §7.3's mower: R 12 Jan 2027 in the break → A = 1 Apr; deferred days count nothing, the season start resets nothing. */
    @Test
    fun mowerStartsAfterTheDeferral() {
        val asset = SeasonFixtures.mowerAsset()
        val schedule = SeasonFixtures.mowerSchedule()
        val subject = HealthFixtures.mowerEngineOil()
        val done = listOf(SeasonFixtures.mowerJanuaryDone())
        fun oil(date: String) = HealthFixtures.healthOn(date, asset, listOf(subject), listOf(schedule), done).subjects.single().value

        for (date in listOf("2026-12-29", "2027-01-12", "2027-03-31", "2027-04-01")) {
            assertEquals(SubjectValue.Scored(100, HealthBand.NOMINAL, 0), oil(date), "$date: deferred, not counted")
        }
        assertEquals(SubjectValue.Scored(100, HealthBand.NOMINAL, 14), oil("2027-04-15"), "the season starts and resets nothing")
        assertEquals(SubjectValue.Scored(60, HealthBand.WARNING, 45), oil("2027-05-16"))
        assertEquals(SubjectValue.Scored(25, HealthBand.CRITICAL, 120), oil("2027-07-30"))
    }

    /** §7.4's generator: late days count through the break (H.3c); days deferred past it do not (H.3a). */
    @Test
    fun generatorDeferredDaysAreNotCountedAndLateDaysAre() {
        val asset = SeasonFixtures.generatorAsset()
        val schedule = SeasonFixtures.generatorSchedule()
        val subject = HealthFixtures.generatorEngineOil()
        val completed = listOf(SeasonFixtures.generatorCompletedInOctober())
        fun oil(date: String, events: List<AssetEvent>) =
            HealthFixtures.healthOn(date, asset, listOf(subject), listOf(schedule), events).subjects.single().value

        assertEquals(SubjectValue.Scored(100, HealthBand.NOMINAL, 0), oil("2026-06-20", emptyList()), "R = A = 20 Jun")
        assertEquals(SubjectValue.Scored(60, HealthBand.WARNING, 45), oil("2026-08-04", emptyList()))
        assertEquals(SubjectValue.Scored(37, HealthBand.WARNING, 96), oil("2026-09-24", emptyList()))
        assertEquals(SubjectValue.Scored(25, HealthBand.CRITICAL, 120), oil("2026-10-18", emptyList()))
        assertEquals(SubjectValue.Scored(100, HealthBand.NOMINAL, 0), oil("2026-10-20", completed), "completed; R = 20 Dec → A = 1 Mar")
        for (date in listOf("2026-12-06", "2027-01-15", "2027-02-28", "2027-03-01")) {
            assertEquals(SubjectValue.Scored(100, HealthBand.NOMINAL, 0), oil(date, completed), "$date: deferred past the break")
        }
        assertEquals(SubjectValue.Scored(60, HealthBand.WARNING, 45), oil("2027-04-15", completed))

        // H.3c: had the 20 Jun occurrence stayed open, the break days are late days and count.
        assertEquals(SubjectValue.Scored(11, HealthBand.CRITICAL, 164), oil("2026-12-01", emptyList()))
        assertEquals(SubjectValue.Scored(0, HealthBand.CRITICAL, 195), oil("2027-01-01", emptyList()))
    }

    // ----------------------------------------------------------------------------------------
    // Generators for the property.
    // ----------------------------------------------------------------------------------------

    private fun mmdd(random: Random): String = "%02d-%02d".format(random.nextInt(1, 13), random.nextInt(1, 29))

    private fun randomSeason(random: Random): SeasonInputs? {
        val withBreak = random.nextBoolean()
        val breakStart = if (withBreak) mmdd(random) else null
        val breakEnd = if (withBreak) mmdd(random) else null
        return when (random.nextInt(4)) {
            0 -> null
            1 -> SeasonFixtures.seasonOf(SeasonMode.YEAR_ROUND, breakStart = breakStart, breakEnd = breakEnd)
            2 -> SeasonFixtures.seasonOf(
                SeasonMode.CALENDAR, seasonStart = mmdd(random), seasonEnd = mmdd(random), breakStart = breakStart, breakEnd = breakEnd,
            )
            else -> SeasonFixtures.seasonOf(
                SeasonMode.MANUAL, breakStart = breakStart, breakEnd = breakEnd, activations = randomActivations(random),
            )
        }
    }

    private fun randomActivations(random: Random): List<SeasonActivation> = List(random.nextInt(0, 8)) { i ->
        val on = BASE.plusDays(random.nextLong(-300, 1_200)).toString()
        val action = if (random.nextInt(5) < 3) SeasonAction.START else SeasonAction.END
        SeasonFixtures.activationOf("act-$i", "a1", action, on, createdAt = dayMillis(on) + i)
    }

    private companion object {
        val BASE: LocalDate = LocalDate.parse("2024-01-01")
    }
}
