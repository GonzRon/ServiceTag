package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.health.HealthScore
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TerminationKind
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.testing.groupOf
import com.loosecannon.servicetag.testing.meterDefinitionOf
import com.loosecannon.servicetag.testing.replacementOf
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.testing.subjectRow
import com.loosecannon.servicetag.ui.health.SubjectBandFact
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.4's half of the due projection (master plan §8.6, §13.1): states read through `readState`,
 * the order on the **actionable** date, the policy facts a why-line needs, the Deferred section,
 * and the band of the subject a schedule drives.
 *
 * The fixtures are the spec's F3–F5 shapes (a generator with a winter break, a snowblower and a
 * mower on calendar seasons, a hot tub with a manual season), with dates chosen per case.
 */
class DueReadModelPolicyTest {

    private val graph = FakeGraph()

    @After fun tearDown() = graph.close()

    private suspend fun items() = graph.dueReadModel.items().associateBy { it.scheduleId.value }

    /** Writes the schedule and lets the engine derive its state for the graph's current `T`. */
    private suspend fun seed(schedule: MaintenanceSchedule) {
        graph.schedules.upsert(schedule)
        graph.recomputeSchedules.forSchedule(schedule.id)
    }

    // ---------------------------------------------------------------- a stale row (inv. 23, 105)

    /**
     * A row the recompute wrote yesterday, the last day out of season, is not what today shows:
     * the season opened overnight with no history change, so the read derives today's state in
     * memory — ACTIVE and DUE on its first in-season day — and leaves the stored row as it was.
     */
    @Test fun aStaleStateRowIsDerivedForToday() = runTest {
        graph.assets.upsert(assetRow("mow", name = "Mower", seasonMode = SeasonMode.CALENDAR, seasonStart = "04-15", seasonEnd = "10-31"))
        graph.today = LocalDate.parse("2026-04-14")
        seed(scheduleOf("s-mow", assetId = "mow", title = "Engine oil service", anchorOn = "2026-01-01", servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0))
        val stored = graph.scheduleStates.get(ScheduleId("s-mow"))!!
        assertEquals(PolicyPhase.DORMANT, stored.policyPhase)

        graph.today = LocalDate.parse("2026-04-15")
        val row = items().getValue("s-mow")

        assertEquals(PolicyPhase.ACTIVE, row.policyPhase)
        assertEquals(DueStatus.DUE, row.status)
        assertEquals(AttentionSection.ATTENTION, row.section)
        assertEquals(LocalDate.parse("2026-04-15"), row.actionableDueOn)
        assertEquals(PolicyReason.SEASON_START, row.policyReason)
        assertEquals("the read wrote nothing", stored, graph.scheduleStates.get(ScheduleId("s-mow")))
    }

    // ---------------------------------------------------------------- the order (D-29)

    /**
     * The sort key is the actionable date: a mower pulled before its season to 1 Apr sorts ahead
     * of a CONTINUOUS row due 10 Apr, although its canonical date, 20 Apr, is later.
     */
    @Test fun theOrderFollowsTheActionableDate() = runTest {
        graph.today = LocalDate.parse("2027-03-10")
        graph.assets.upsert(
            assetRow("mow", name = "Mower", seasonMode = SeasonMode.CALENDAR, seasonStart = "04-15", seasonEnd = "10-31", breakStart = "12-01", breakEnd = "02-28"),
        )
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        seed(
            scheduleOf(
                "s-mow", assetId = "mow", title = "Engine oil service", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR,
                anchorOn = "2027-04-20", createdOn = "2026-06-01", servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = -14,
            ),
        )
        seed(
            scheduleOf("s-gen", assetId = "gen", title = "Air filter", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = "2027-04-10", createdOn = "2026-06-01"),
        )

        val rows = graph.dueReadModel.items()

        assertEquals(listOf("s-mow", "s-gen"), rows.map { it.scheduleId.value })
        assertEquals(listOf(AttentionSection.CURRENT, AttentionSection.CURRENT), rows.map { it.section })
        val mower = rows.first()
        assertEquals(LocalDate.parse("2027-04-01"), mower.actionableDueOn)
        assertEquals(LocalDate.parse("2027-04-20"), mower.effectiveDueOn)
        assertEquals(PolicyReason.BEFORE_SEASON, mower.policyReason)
        assertEquals(listOf(0, 1), rows.map { it.rank })
    }

    // ---------------------------------------------------------------- Deferred (inv. 103)

    /**
     * Spec §4.5: a row the maintenance break holds is DEFERRED, in its own section between CURRENT
     * and OUT OF SEASON, and counts for nothing.
     */
    @Test fun deferredRowsHaveTheirOwnSectionAndCountForNothing() = runTest {
        graph.today = LocalDate.parse("2026-12-10")
        graph.assets.upsert(assetRow("gen", name = "Generator", breakStart = "12-01", breakEnd = "02-28"))
        graph.assets.upsert(assetRow("mow", name = "Mower", seasonMode = SeasonMode.CALENDAR, seasonStart = "04-15", seasonEnd = "10-31"))
        seed(
            scheduleOf(
                "s-held", assetId = "gen", title = "Engine oil service", timeInterval = 6, anchorOn = "2026-12-20",
                createdOn = "2026-06-01", servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0,
            ),
        )
        seed(scheduleOf("s-ok", assetId = "gen", title = "Air filter", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = "2027-06-01", createdOn = "2026-06-01"))
        seed(scheduleOf("s-dormant", assetId = "mow", title = "Blade sharpen", anchorOn = "2026-06-01", createdOn = "2026-06-01", servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0))

        val rows = graph.dueReadModel.items()
        val held = rows.single { it.scheduleId.value == "s-held" }

        assertEquals(DueStatus.DEFERRED, held.status)
        assertEquals(AttentionSection.DEFERRED, held.section)
        assertFalse("DEFERRED never counts as due", held.countsAsDue)
        assertEquals(PolicyReason.AFTER_BREAK, held.policyReason)
        assertEquals(LocalDate.parse("2027-03-01"), held.actionableDueOn)
        assertEquals(
            "between CURRENT and OUT OF SEASON",
            listOf("s-ok", "s-held", "s-dormant"),
            rows.map { it.scheduleId.value },
        )
        assertEquals(0, rows.count { it.countsAsDue })
    }

    // ---------------------------------------------------------------- the why-line facts

    /**
     * Each row carries what its why-line needs: the policy's reason, its phase, `quiet`, the
     * target's season mode, and — for a DORMANT row on a CALENDAR asset, a meter-only one included
     * — the next season start (plan decision 37). MANUAL predicts no start; a group has no season.
     */
    @Test fun aRowCarriesReasonPhaseQuietModeAndDormantUntil() = runTest {
        graph.today = LocalDate.parse("2026-11-10")
        graph.assets.upsert(
            assetRow("snow", name = "Snowblower", seasonMode = SeasonMode.CALENDAR, seasonStart = "11-15", seasonEnd = "03-31", breakStart = "12-01", breakEnd = "02-28"),
        )
        graph.assets.upsert(assetRow("mow", name = "Mower", seasonMode = SeasonMode.CALENDAR, seasonStart = "04-15", seasonEnd = "10-31"))
        graph.assets.upsert(assetRow("gen", name = "Generator", breakStart = "11-01", breakEnd = "11-30"))
        graph.assets.upsert(assetRow("tub", name = "Hot tub", seasonMode = SeasonMode.MANUAL))
        graph.definitions.upsert(meterDefinitionOf("d-hours", assetId = "mow"))
        graph.groups.upsert(groupOf("g1", name = "Generators", members = listOf(Triple("gen", "2026-01-01", null))))

        seed(
            scheduleOf(
                "s-snow", assetId = "snow", title = "Engine oil service", timeInterval = 2, timeUnit = RecurrenceUnit.YEAR,
                anchorOn = "2026-12-20", createdOn = "2026-06-01", servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = -14,
            ),
        )
        seed(
            scheduleOf(
                "s-mow-hours", assetId = "mow", title = "Engine hours service", timeInterval = null, timeUnit = null, anchorOn = null,
                meterDefinitionId = "d-hours", meterInterval = 50.0, anchorMeter = 0.0, createdOn = "2026-06-01",
                servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0,
            ),
        )
        seed(
            scheduleOf(
                "s-gen", assetId = "gen", title = "Engine oil service", timeInterval = 6, anchorOn = "2026-11-20",
                createdOn = "2026-06-01", servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0,
            ),
        )
        seed(scheduleOf("s-tub", assetId = "tub", title = "Water care", timeInterval = 1, timeUnit = RecurrenceUnit.WEEK, anchorOn = "2026-06-06", createdOn = "2026-06-01", servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0))
        seed(scheduleOf("s-group", groupId = "g1", title = "Load test", anchorOn = "2026-06-01", createdOn = "2026-06-01"))

        val rows = items()

        val snow = rows.getValue("s-snow")
        assertEquals(PolicyReason.BEFORE_SEASON, snow.policyReason)
        assertEquals(PolicyPhase.ACTIVE, snow.policyPhase)
        assertEquals(LocalDate.parse("2026-11-01"), snow.actionableDueOn)
        assertEquals(DueStatus.OVERDUE, snow.status)
        assertFalse(snow.quiet)
        assertEquals(SeasonMode.CALENDAR, snow.seasonMode)
        assertNull("an ACTIVE row is not dormant", snow.dormantUntil)

        val hours = rows.getValue("s-mow-hours")
        assertEquals(PolicyPhase.DORMANT, hours.policyPhase)
        assertEquals(DueStatus.INACTIVE_SEASON, hours.status)
        assertNull("meter-only: no date side", hours.actionableDueOn)
        assertEquals(SeasonMode.CALENDAR, hours.seasonMode)
        assertEquals("the next season start", LocalDate.parse("2027-04-15"), hours.dormantUntil)

        val gen = rows.getValue("s-gen")
        assertTrue("inside the break under a non-CONTINUOUS policy", gen.quiet)
        assertEquals(PolicyReason.AFTER_BREAK, gen.policyReason)
        assertEquals(SeasonMode.YEAR_ROUND, gen.seasonMode)
        assertNull(gen.dormantUntil)

        val tub = rows.getValue("s-tub")
        assertEquals("MANUAL with no activation row reads out of season", PolicyPhase.DORMANT, tub.policyPhase)
        assertEquals(SeasonMode.MANUAL, tub.seasonMode)
        assertNull("a manual start is never predicted", tub.dormantUntil)

        val group = rows.getValue("s-group")
        assertNull(group.seasonMode)
        assertNull(group.dormantUntil)
        assertNull(group.health)
        assertFalse(group.quiet)
    }

    // ---------------------------------------------------------------- health rides its row

    /**
     * Spec §10.2: overdue-driven health rides the row of the schedule that drives it. The subject
     * on the overdue schedule gives that row its band; the other schedule on the same asset, the
     * AGE subject beside them and a subject on a paused schedule give their rows none.
     */
    @Test fun aScheduleRowCarriesTheBandOfTheSubjectItDrives() = runTest {
        graph.today = LocalDate.parse("2026-04-15")
        graph.assets.upsert(assetRow("ups", name = "UPS"))
        seed(scheduleOf("s-check", assetId = "ups", title = "Battery check", anchorOn = "2026-01-01", leadDays = 0))
        seed(scheduleOf("s-filter", assetId = "ups", title = "Filter change", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = "2026-12-01"))
        seed(scheduleOf("s-paused", assetId = "ups", title = "Load test", anchorOn = "2026-01-01", status = ScheduleStatus.PAUSED))
        graph.healthSubjects.upsert(
            subjectRow(
                "h-check", "ups", name = "Battery", driver = HealthDriver.MAINTENANCE_OVERDUE, scheduleId = "s-check",
                nominalUntilDays = 0, warningFromDays = 100, criticalFromDays = 400, sortOrder = 1,
            ),
        )
        graph.healthSubjects.upsert(
            subjectRow("h-load", "ups", name = "Load", driver = HealthDriver.MAINTENANCE_OVERDUE, scheduleId = "s-paused", sortOrder = 2),
        )
        graph.events.upsert(replacementOf("e1", "ups", "2025-01-01"))
        graph.healthSubjects.upsert(subjectRow("h-age", "ups", name = "Battery age", sortOrder = 0))

        val rows = items()

        // 104 days late, 0 / 100 / 400.
        val score = HealthScore.score(104, 0, 100, 400)
        assertEquals(
            SubjectBandFact(HealthSubjectId("h-check"), "Battery", HealthScore.band(score), score),
            rows.getValue("s-check").health,
        )
        assertEquals(HealthBand.WARNING, rows.getValue("s-check").health?.band)
        assertNull("no subject drives it", rows.getValue("s-filter").health)
        assertNull("its subject is NOT TRACKED while the schedule is paused", rows.getValue("s-paused").health)
    }

    // ---------------------------------------------------------------- the asset's condition

    /**
     * The controller's ruling on B07's concern 3: each row carries its target Asset's **current**
     * condition — a component's own, not its parent's — and null for a group target or when none is
     * recorded, so a condition filter reads it off the row.
     */
    @Test fun aRowCarriesItsTargetsCurrentCondition() = runTest {
        graph.today = LocalDate.parse("2026-04-15")
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.assets.upsert(assetRow("pack", name = "Battery pack", parent = "gen"))
        graph.assets.upsert(assetRow("mow", name = "Mower"))
        graph.conditions.insert(conditionRow("c1", "gen", OperationalCondition.DOWN, "2026-04-01"))
        graph.conditions.insert(conditionRow("c2", "gen", OperationalCondition.OPERATIONAL, "2026-04-10"))
        graph.conditions.insert(conditionRow("c3", "pack", OperationalCondition.DEGRADED, "2026-04-12"))
        graph.groups.upsert(groupOf("g1", name = "Generators", members = listOf(Triple("gen", "2026-01-01", null))))
        seed(scheduleOf("s-gen", assetId = "gen", title = "Engine oil service"))
        seed(scheduleOf("s-pack", assetId = "pack", title = "Load test"))
        seed(scheduleOf("s-mow", assetId = "mow", title = "Blade sharpen"))
        seed(scheduleOf("s-group", groupId = "g1", title = "Fuel check"))

        val rows = items()

        assertEquals("the latest row, not the first", OperationalCondition.OPERATIONAL, rows.getValue("s-gen").assetCondition)
        assertEquals("a component's own", OperationalCondition.DEGRADED, rows.getValue("s-pack").assetCondition)
        assertNull("not recorded", rows.getValue("s-mow").assetCondition)
        assertNull("a group has no condition", rows.getValue("s-group").assetCondition)
    }

    // ---------------------------------------------------------------- the shared fake's order

    /**
     * B02's carry-forward, made behavioural (review M5): the Room DAO orders the shared fixture
     * `core/src/test/resources/state-order/rows.csv` into its "expected" line, and `:core`'s
     * `InMemoryScheduleStateOrderTest` holds the shared in-memory fake to the same line over the
     * same rows — so the fake and the DAO agree on order. The two test source sets cannot see each
     * other, so the fixture file is what they share.
     */
    @Test fun theDaoOrdersTheSharedFixtureAsTheFakeMust() = runTest {
        val lines = sourceFile("core/src/test/resources/state-order/rows.csv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split(",") }
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        // Inserted in reverse, so the fixture's own order can never pass for the sorted one.
        lines.filter { it[0] == "row" }.reversed().forEach { (_, id, effective, actionable) ->
            graph.schedules.upsert(scheduleOf(id, assetId = "gen"))
            graph.scheduleStates.upsert(
                stateRow(id, effective, actionable.ifEmpty { null }),
            )
        }
        val expected = lines.single { it[0] == "expected" }.drop(1)

        assertEquals(expected, graph.scheduleStates.observeAll().first().map { it.scheduleId.value })
    }

    private companion object {
        fun stateRow(id: String, effective: String, actionable: String?) = ScheduleState(
            scheduleId = ScheduleId(id),
            lastCompletedOn = null,
            lastCompletionEventId = null,
            lastCompletedMeter = null,
            currentMeter = null,
            computedDueMeter = null,
            lastTerminationEffectiveOn = null,
            lastTerminationKind = TerminationKind.NONE,
            computedDueOn = effective,
            effectiveDueOn = effective,
            policyPhase = PolicyPhase.ACTIVE,
            actionableDueOn = actionable,
            policyReason = PolicyReason.NONE,
            quiet = false,
            computedForOn = "2026-09-24",
            computedAt = 0L,
        )

        fun sourceFile(path: String): java.io.File {
            var dir = java.io.File(".").absoluteFile
            while (!java.io.File(dir, "settings.gradle.kts").isFile) {
                dir = dir.parentFile ?: error("no settings.gradle.kts above ${java.io.File(".").absolutePath}")
            }
            return java.io.File(dir, path)
        }
    }
}
