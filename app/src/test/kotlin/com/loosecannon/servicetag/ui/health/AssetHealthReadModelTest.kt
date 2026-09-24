package com.loosecannon.servicetag.ui.health

import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.health.NotTrackedReason
import com.loosecannon.servicetag.core.health.SubjectValue
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.replacementOf
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.testing.subjectRow
import com.loosecannon.servicetag.ui.maintenance.AttentionKind
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one health view (master plan §13.1, inv. 119): the engine's result beside the asset's
 * current condition and its DOWN or DEGRADED in-service components at any depth. `T` is 2026-04-15;
 * the fixture thresholds are 0 / 40 / 75.
 */
class AssetHealthReadModelTest {

    private val graph = FakeGraph().also { it.today = LocalDate.parse("2026-04-15") }

    @After fun tearDown() = graph.close()

    private suspend fun view(id: String) = graph.assetHealthReadModel.forAsset(AssetId(id))

    /**
     * Inv. 119: an AVERAGE that reads NOMINAL still lists its CRITICAL subject. Three AGE subjects
     * share one replacement 90 days back; only the first has thresholds tight enough to be CRITICAL.
     */
    @Test fun anAverageNominalAssetStillListsItsCriticalSubject() = runTest {
        graph.assets.upsert(assetRow("ups", name = "UPS", aggregation = HealthAggregation.AVERAGE))
        graph.events.upsert(replacementOf("e1", "ups", "2026-01-15"))
        graph.healthSubjects.upsert(subjectRow("h1", "ups", name = "Battery age", sortOrder = 0))
        graph.healthSubjects.upsert(subjectRow("h2", "ups", name = "Fan age", nominalUntilDays = 1000, warningFromDays = 2000, criticalFromDays = 3000, sortOrder = 1))
        graph.healthSubjects.upsert(subjectRow("h3", "ups", name = "Case age", nominalUntilDays = 1000, warningFromDays = 2000, criticalFromDays = 3000, sortOrder = 2))

        val result = view("ups").result

        assertEquals("the average is NOMINAL", HealthBand.NOMINAL, result.aggregate?.band)
        assertEquals(listOf(HealthSubjectId("h1")), result.critical.map { it.subject.id })
        assertNull("an aggregate carries no day count of its own", result.aggregate?.trackedDays)
    }

    /**
     * Spec §6.5, "at any depth": a DOWN grandchild is listed on its grandparent's view and on its
     * parent's; a DEGRADED child is listed; a retired or archived component is not, and neither is
     * an operational one. DOWN before DEGRADED.
     */
    @Test fun downOrDegradedComponentsAtAnyDepthAreListed() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.assets.upsert(assetRow("eng", name = "Engine", parent = "gen"))
        graph.conditions.insert(conditionRow("c1", "eng", OperationalCondition.OPERATIONAL, "2026-04-01"))
        graph.assets.upsert(assetRow("pack", name = "Battery pack", parent = "eng"))
        graph.conditions.insert(conditionRow("c2", "pack", OperationalCondition.DOWN, "2026-04-02", reason = "Won't hold charge"))
        graph.assets.upsert(assetRow("fan", name = "Fan", parent = "gen"))
        graph.conditions.insert(conditionRow("c3", "fan", OperationalCondition.DEGRADED, "2026-04-03"))
        graph.assets.upsert(assetRow("old", name = "Old pack", parent = "gen", retiredOn = "2026-01-01"))
        graph.conditions.insert(conditionRow("c4", "old", OperationalCondition.DOWN, "2026-04-04"))
        graph.assets.upsert(assetRow("gone", name = "Old fan", parent = "gen", status = AssetStatus.ARCHIVED))
        graph.conditions.insert(conditionRow("c5", "gone", OperationalCondition.DEGRADED, "2026-04-05"))

        val top = view("gen").components

        assertEquals(listOf("pack", "fan"), top.map { it.assetId.value })
        assertEquals(
            ComponentCondition(AssetId("pack"), "Battery pack", OperationalCondition.DOWN, "Won't hold charge", LocalDate.parse("2026-04-02")),
            top[0],
        )
        assertEquals(OperationalCondition.DEGRADED, top[1].condition)
        assertEquals("the grandchild is on its parent's view too", listOf("pack"), view("eng").components.map { it.assetId.value })
    }

    /**
     * Inv. 119's second half: the view carries the current condition even when there is no health
     * to show, and a condition row whose linked event was deleted still reads (`eventExists` false).
     * "since" is the first row of the latest run of the same word.
     */
    @Test fun theViewAlwaysCarriesTheCurrentCondition() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.events.upsert(replacementOf("e-kept", "gen", "2026-03-01"))
        graph.conditions.insert(conditionRow("c1", "gen", OperationalCondition.DEGRADED, "2026-02-20"))
        graph.conditions.insert(conditionRow("c2", "gen", OperationalCondition.DOWN, "2026-03-01", reason = "Won't start", eventId = "e-kept"))
        graph.conditions.insert(
            conditionRow("c3", "gen", OperationalCondition.DOWN, "2026-03-05", reason = "Still won't start", occurredTime = "08:30", eventId = "e-gone"),
        )

        val health = view("gen")

        assertNull("no subject: health is NOT TRACKED", health.result.aggregate)
        assertEquals(
            ConditionView(
                condition = OperationalCondition.DOWN,
                since = LocalDate.parse("2026-03-01"),
                reason = "Still won't start",
                occurredOn = LocalDate.parse("2026-03-05"),
                occurredTime = "08:30",
                eventId = EventId("e-gone"),
                eventExists = false,
            ),
            health.condition,
        )
        assertEquals(LocalDate.parse("2026-04-15"), health.computedForOn)

        // The earlier row's link resolves.
        graph.conditions.insert(conditionRow("c4", "gen", OperationalCondition.DOWN, "2026-03-06", eventId = "e-kept"))
        assertTrue(view("gen").condition!!.eventExists)
    }

    /**
     * O-6, inv. 131 (the snooze half): snoozing an overdue item through the app's real snooze path
     * writes the device-local delivery row and leaves every health number exactly as it was.
     */
    @Test fun aSnoozeLeavesHealthUnchanged() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.schedules.upsert(scheduleOf("s-oil", assetId = "gen", title = "Engine oil service", anchorOn = "2026-01-01", leadDays = 0))
        graph.recomputeSchedules.forSchedule(ScheduleId("s-oil"))
        graph.healthSubjects.upsert(
            subjectRow(
                "h-oil", "gen", name = "Oil", driver = HealthDriver.MAINTENANCE_OVERDUE, scheduleId = "s-oil",
                nominalUntilDays = 0, warningFromDays = 100, criticalFromDays = 400,
            ),
        )
        val before = view("gen")
        val scored = before.result.subjects.single().value as SubjectValue.Scored
        assertEquals("104 days late: a real number to move", 104L, scored.trackedDays)

        val until = dayMillis("2026-06-01")
        graph.reminderSnooze.snooze(ScheduleId("s-oil"), until)
        assertEquals("the snooze really wrote", until, graph.scheduleLocalDelivery.get(ScheduleId("s-oil"))?.snoozedUntilAt)

        assertEquals(before, view("gen"))
    }

    /**
     * The controller's ruling on B05's concern 3: a malformed subject (a merged row no command
     * would accept) is screened out before the engine and listed NOT TRACKED with no driver line,
     * and nothing throws. It is the TRACK_ONE primary here, so the aggregate is NOT TRACKED with
     * no fallback (plan decision 18), while the other subject's CRITICAL still shows.
     */
    @Test fun aMalformedSubjectIsNotTrackedWithNoDriverLineAndNothingThrows() = runTest {
        graph.assets.upsert(assetRow("ups", name = "UPS", aggregation = HealthAggregation.TRACK_ONE, primary = "h-bad"))
        graph.events.upsert(replacementOf("e1", "ups", "2026-01-15"))
        graph.healthSubjects.upsert(subjectRow("h-bad", "ups", name = "Fan age", nominalUntilDays = 50, warningFromDays = 40, criticalFromDays = 75, sortOrder = 0))
        graph.healthSubjects.upsert(subjectRow("h-good", "ups", name = "Battery age", sortOrder = 1))

        val result = view("ups").result

        assertEquals(listOf("h-bad", "h-good"), result.subjects.map { it.subject.id.value })
        val bad = result.subjects[0]
        assertEquals("NOT TRACKED for its own reason", SubjectValue.NotTracked(NotTrackedReason.UNSCORABLE), bad.value)
        assertEquals("no driver line", emptyList<Any>(), bad.lines)
        assertEquals(HealthBand.CRITICAL, (result.subjects[1].value as SubjectValue.Scored).band)
        assertEquals(listOf("h-good"), result.critical.map { it.subject.id.value })
        assertNull("TRACK_ONE on an untracked primary", result.aggregate)
        assertFalse("the primary exists, so nothing falls back", result.fallback)
        // The other read paths over the same asset do not throw either.
        assertEquals(listOf("h-good"), graph.attentionReadModel.items().map { it.healthSubjectId?.value })
    }

    /**
     * B06's follow-up: a restored condition may carry a zone this device cannot resolve. It is
     * shown as its row's text — nothing resolves the zone — so the row neither crashes nor hides.
     */
    @Test fun aConditionInAZoneThisDeviceCannotResolveStillReads() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.assets.upsert(assetRow("pack", name = "Battery pack", parent = "gen"))
        graph.conditions.insert(conditionRow("c1", "gen", OperationalCondition.DOWN, "2026-04-10", tzId = "Nowhere/Fictional_City"))
        graph.conditions.insert(conditionRow("c2", "pack", OperationalCondition.DEGRADED, "2026-04-11", tzId = "Nowhere/Fictional_City"))

        val health = view("gen")

        assertEquals(OperationalCondition.DOWN, health.condition?.condition)
        assertEquals(listOf("pack"), health.components.map { it.assetId.value })
        val rows = graph.attentionReadModel.items()
        assertEquals(listOf("gen", "pack"), rows.map { it.assetId.value })
        assertEquals(AttentionKind.CONDITION, rows.first().kind)
        assertNotNull(graph.dueReadModel.forAsset(AssetId("gen")))
    }
}
