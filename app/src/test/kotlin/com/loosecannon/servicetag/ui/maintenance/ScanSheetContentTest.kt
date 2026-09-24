package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.health.AssetHealthResult
import com.loosecannon.servicetag.core.health.DriverLine
import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.health.SubjectHealth
import com.loosecannon.servicetag.core.health.SubjectValue
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.testing.replacementOf
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.testing.subjectRow
import com.loosecannon.servicetag.ui.health.ComponentCondition
import com.loosecannon.servicetag.ui.health.ConditionView
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §10.1's **one predicate** (inv. 123, O-8): the scan sheet opens for a D-18a item, a DOWN or
 * DEGRADED asset, or a DOWN or DEGRADED component — and calculated health never opens it alone.
 * Health lines and DUE SOON rows are passengers.
 *
 * The first cases are the pure function over hand-built inputs; the last asks the graph's routing
 * question over the real projection and health view, and checks it is exactly [ScanSheetContent.opens].
 */
class ScanSheetContentTest {

    // ---------------------------------------------------------------- what opens it

    /** O-8: a CRITICAL subject alone, with the aggregate CRITICAL too, never opens the sheet. */
    @Test fun criticalHealthAloneNeverOpensTheSheet() {
        val content = scanSheetContent(
            items = listOf(item("s-ok", DueStatus.OK)),
            condition = view(OperationalCondition.OPERATIONAL),
            components = emptyList(),
            health = health(critical = listOf(battery), aggregate = SubjectValue.Scored(15, HealthBand.CRITICAL, null)),
            inService = true,
        )

        assertFalse("calculated health has no routing power in 1.4", content.opens)
        assertEquals(emptyList<DueItem>(), content.maintenance)
        // Still listed for asset detail, which shows the same lines.
        assertEquals(listOf(battery), content.critical)
    }

    @Test fun aDownAssetWithNothingDueOpensIt() {
        val content = scanSheetContent(
            items = emptyList(),
            condition = view(OperationalCondition.DOWN),
            components = emptyList(),
            health = null,
            inService = true,
        )

        assertTrue("a DOWN asset opens the sheet with nothing due", content.opens)
        assertTrue("DOWN offers Mark operational", content.offersMarkOperational)
        assertEquals(OperationalCondition.DOWN, content.condition?.condition)
    }

    @Test fun aDegradedComponentOpensIt() {
        val pack = ComponentCondition(AssetId("pack"), "Battery pack", OperationalCondition.DEGRADED, "", LocalDate.parse("2026-04-01"))
        val content = scanSheetContent(
            items = emptyList(),
            condition = view(OperationalCondition.OPERATIONAL),
            components = listOf(pack),
            health = null,
            inService = true,
        )

        assertTrue("a DEGRADED component opens the sheet", content.opens)
        assertFalse("the unit itself is operational", content.offersMarkOperational)
        assertEquals(listOf(pack), content.components)
    }

    @Test fun anActionableItemOpensIt() {
        val content = scanSheetContent(
            items = listOf(item("s-overdue", DueStatus.OVERDUE)),
            condition = null,
            components = emptyList(),
            health = null,
            inService = true,
        )

        assertTrue("an OVERDUE item opens the sheet", content.opens)
        assertFalse("no condition recorded offers nothing", content.offersMarkOperational)
        assertEquals(listOf("s-overdue"), content.maintenance.map { it.scheduleId.value })
    }

    /** A CRITICAL subject rides along when a due item opens the sheet. */
    @Test fun healthRidesAlongWhenSomethingElseOpens() {
        val content = scanSheetContent(
            items = listOf(item("s-due", DueStatus.DUE)),
            condition = view(OperationalCondition.OPERATIONAL),
            components = emptyList(),
            health = health(critical = listOf(battery), aggregate = SubjectValue.Scored(15, HealthBand.CRITICAL, null)),
            inService = true,
        )

        assertTrue("a DUE item opens the sheet", content.opens)
        assertEquals(listOf(battery), content.critical)
        assertEquals(HealthBand.CRITICAL, content.aggregate?.band)
    }

    /**
     * The controller's ruling on B07's review (M1): an archived or retired asset never opens the
     * sheet and is never offered "Mark operational", however DOWN it is and whatever is due on it;
     * the scan goes to asset detail, whose lines the content still carries.
     */
    @Test fun anArchivedOrRetiredAssetNeverOpensTheSheet() = runTest {
        val content = scanSheetContent(
            items = listOf(item("s-overdue", DueStatus.OVERDUE)),
            condition = view(OperationalCondition.DOWN),
            components = emptyList(),
            health = null,
            inService = false,
        )
        assertFalse("out of service: never the sheet", content.opens)
        assertFalse("and never Mark operational", content.offersMarkOperational)
        assertEquals(emptyList<DueItem>(), content.maintenance)
        assertEquals("asset detail still shows the condition", OperationalCondition.DOWN, content.condition?.condition)

        val graph = FakeGraph().also { it.today = LocalDate.parse("2026-04-15") }
        try {
            graph.assets.upsert(assetRow("gen", name = "Generator", status = AssetStatus.ARCHIVED))
            graph.conditions.insert(conditionRow("c-gen", "gen", OperationalCondition.DOWN, "2026-04-10"))
            graph.schedules.upsert(scheduleOf("s-gen", assetId = "gen", title = "Engine oil service", anchorOn = "2026-01-01", leadDays = 0))
            graph.assets.upsert(assetRow("mow", name = "Mower", retiredOn = "2026-03-01"))
            graph.conditions.insert(conditionRow("c-mow", "mow", OperationalCondition.DEGRADED, "2026-04-10"))
            graph.assets.upsert(assetRow("tub", name = "Hot tub"))
            graph.conditions.insert(conditionRow("c-tub", "tub", OperationalCondition.DOWN, "2026-04-10"))

            assertFalse("archived", graph.scanSheetOffer.has(AssetId("gen")))
            assertFalse("retired", graph.scanSheetOffer.has(AssetId("mow")))
            assertTrue("the in-service control", graph.scanSheetOffer.has(AssetId("tub")))
        } finally {
            graph.close()
        }
    }

    // ---------------------------------------------------------------- what it lists

    /** Inv. 119: an AVERAGE that reads NOMINAL still shows its one CRITICAL subject, and no aggregate. */
    @Test fun averageNominalPlusOneCriticalShowsTheCritical() {
        val content = scanSheetContent(
            items = listOf(item("s-due", DueStatus.DUE)),
            condition = null,
            components = emptyList(),
            health = health(critical = listOf(battery), aggregate = SubjectValue.Scored(73, HealthBand.NOMINAL, null)),
            inService = true,
        )

        assertEquals(listOf(battery), content.critical)
        assertNull("a NOMINAL aggregate is not shown", content.aggregate)
    }

    @Test fun theAggregateOnlyWhenWarningOrCritical() {
        fun shown(band: HealthBand, score: Int) = scanSheetContent(
            items = emptyList(),
            condition = view(OperationalCondition.DOWN),
            components = emptyList(),
            health = health(critical = emptyList(), aggregate = SubjectValue.Scored(score, band, null)),
            inService = true,
        ).aggregate

        assertNull(shown(HealthBand.NOMINAL, 90))
        assertEquals(SubjectValue.Scored(50, HealthBand.WARNING, null), shown(HealthBand.WARNING, 50))
        assertEquals(SubjectValue.Scored(10, HealthBand.CRITICAL, null), shown(HealthBand.CRITICAL, 10))
        assertNull("NOT TRACKED shows no aggregate", scanSheetContent(emptyList(), view(OperationalCondition.DOWN), emptyList(), health(emptyList(), null), inService = true).aggregate)
    }

    /**
     * Review M4: the content carries every subject with its value, in the engine's order, so the
     * aggregate line (S108) can name its contributors without a second health read.
     */
    @Test fun theSubjectsTheAggregateLineNamesRideWithTheContent() {
        val nominal = SubjectHealth(
            subject = subjectRow("h-fan", "ups", name = "Fan age", sortOrder = 1),
            value = SubjectValue.Scored(90, HealthBand.NOMINAL, 10),
            lines = listOf(DriverLine.Replaced(LocalDate.parse("2026-04-05"), 10)),
        )
        val content = scanSheetContent(
            items = emptyList(),
            condition = view(OperationalCondition.DOWN),
            components = emptyList(),
            health = AssetHealthResult(
                subjects = listOf(battery, nominal),
                aggregate = SubjectValue.Scored(52, HealthBand.WARNING, null),
                fallback = false,
                critical = listOf(battery),
            ),
            inService = true,
        )

        assertEquals(listOf(battery, nominal), content.subjects)
        assertEquals(listOf(battery), content.critical)
    }

    /** Plan decision 35: a DUE SOON row rides along whenever the sheet is open, condition included. */
    @Test fun dueSoonRidesAlongWhenConditionOpensTheSheet() {
        val items = listOf(item("s-soon", DueStatus.DUE_SOON), item("s-ok", DueStatus.OK))

        val degraded = scanSheetContent(items, view(OperationalCondition.DEGRADED), emptyList(), null, inService = true)
        assertTrue("DEGRADED opens the sheet", degraded.opens)
        assertEquals(listOf("s-soon"), degraded.maintenance.map { it.scheduleId.value })

        val operational = scanSheetContent(items, view(OperationalCondition.OPERATIONAL), emptyList(), null, inService = true)
        assertFalse("DUE SOON never opens the sheet alone", operational.opens)
        assertEquals(emptyList<DueItem>(), operational.maintenance)
    }

    /** A DOWN unit with no maintenance item opens a sheet whose maintenance list is empty (S139). */
    @Test fun nothingDueWhenNoItem() {
        val content = scanSheetContent(
            items = listOf(item("s-ok", DueStatus.OK), item("s-season", DueStatus.INACTIVE_SEASON)),
            condition = view(OperationalCondition.DOWN),
            components = emptyList(),
            health = null,
            inService = true,
        )

        assertTrue("DOWN opens the sheet with nothing due", content.opens)
        assertEquals(emptyList<DueItem>(), content.maintenance)
    }

    // ---------------------------------------------------------------- routing is content

    /**
     * Inv. 123: `ScanSheetOffer.has` is exactly `scanSheetContent(...).opens` over the same
     * projection and health view — for a DOWN asset with nothing due, a CRITICAL-only asset, an
     * overdue item, a DEGRADED component and a DUE SOON row alone.
     */
    @Test fun theOfferIsExactlyOpens() = runTest {
        val graph = FakeGraph().also { it.today = LocalDate.parse("2026-04-15") }
        try {
            graph.assets.upsert(assetRow("gen", name = "Generator"))
            graph.conditions.insert(conditionRow("c-gen", "gen", OperationalCondition.DOWN, "2026-04-10", reason = "Won't start"))

            graph.assets.upsert(assetRow("ups", name = "UPS"))
            graph.events.upsert(replacementOf("e-ups", "ups", "2025-12-01"))
            graph.healthSubjects.upsert(subjectRow("h-ups", "ups", name = "Battery age"))

            graph.assets.upsert(assetRow("mow", name = "Mower"))
            graph.schedules.upsert(scheduleOf("s-mow", assetId = "mow", title = "Engine oil service", anchorOn = "2026-01-01", leadDays = 0))

            graph.assets.upsert(assetRow("tub", name = "Hot tub"))
            graph.assets.upsert(assetRow("pack", name = "Battery pack", parent = "tub"))
            graph.conditions.insert(conditionRow("c-pack", "pack", OperationalCondition.DEGRADED, "2026-04-12"))

            graph.assets.upsert(assetRow("snow", name = "Snowblower"))
            graph.schedules.upsert(
                scheduleOf("s-snow", assetId = "snow", title = "Engine oil service", anchorOn = "2026-04-20", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, leadDays = 14),
            )

            val expected = mapOf("gen" to true, "ups" to false, "mow" to true, "tub" to true, "snow" to false)
            for ((id, opens) in expected) {
                val content = scanSheetContentFor(AssetId(id), graph.dueReadModel, graph.scanRoundMembership, graph.assetHealthReadModel)
                assertEquals("$id: the content", opens, content.opens)
                assertEquals("$id: the routing answer is the content's", content.opens, graph.scanSheetOffer.has(AssetId(id)))
            }
            // The CRITICAL-only asset really is CRITICAL, so its "no" is health's lack of routing power.
            assertEquals(
                HealthBand.CRITICAL,
                graph.assetHealthReadModel.forAsset(AssetId("ups")).result.critical.single().let { (it.value as SubjectValue.Scored).band },
            )
        } finally {
            graph.close()
        }
    }

    // ---------------------------------------------------------------- builders

    private val battery = SubjectHealth(
        subject = subjectRow("h-battery", "ups", name = "Battery age"),
        value = SubjectValue.Scored(15, HealthBand.CRITICAL, 90),
        lines = listOf(DriverLine.Replaced(LocalDate.parse("2026-01-15"), 90)),
    )

    private fun health(critical: List<SubjectHealth>, aggregate: SubjectValue.Scored?) =
        AssetHealthResult(subjects = critical, aggregate = aggregate, fallback = false, critical = critical)

    private fun view(condition: OperationalCondition) = ConditionView(
        condition = condition,
        since = LocalDate.parse("2026-04-01"),
        reason = "",
        occurredOn = LocalDate.parse("2026-04-01"),
        occurredTime = null,
        eventId = null,
        eventExists = false,
    )

    private fun item(id: String, status: DueStatus) = DueItem(
        scheduleId = ScheduleId(id),
        title = id,
        target = ScheduleTarget.AssetTarget(AssetId("a1")),
        assetName = "Generator",
        parentName = null,
        category = null,
        status = status,
        section = null,
        requiredSetEmpty = false,
        effectiveDueOn = LocalDate.parse("2026-04-15"),
        actionableDueOn = LocalDate.parse("2026-04-15"),
        policyReason = PolicyReason.NONE,
        policyPhase = PolicyPhase.ACTIVE,
        quiet = false,
        seasonMode = SeasonMode.YEAR_ROUND,
        dormantUntil = null,
        computedDueMeter = null,
        currentMeter = null,
        meterUnit = null,
        lastCompletedOn = null,
        completionMode = CompletionMode.QUICK,
        remindersEnabled = true,
        membersRequired = null,
        membersComplete = null,
        snoozedUntil = null,
        health = null,
        assetCondition = null,
        rank = 0,
    )
}
