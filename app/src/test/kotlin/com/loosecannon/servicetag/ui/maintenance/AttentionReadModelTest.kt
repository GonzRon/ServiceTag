package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.testing.replacementOf
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.testing.subjectRow
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The asset-level attention rows (spec §9.1, §10.2; master plan §11.2): DOWN and DEGRADED assets
 * and components with no schedule behind them, and independent (AGE) health scoring CRITICAL or
 * WARNING. `T` is 2026-04-15; with the fixture thresholds 0 / 40 / 75, a replacement 90 days back
 * is CRITICAL and one 50 days back is WARNING.
 */
class AttentionReadModelTest {

    private val graph = FakeGraph().also { it.today = LocalDate.parse("2026-04-15") }

    @After fun tearDown() = graph.close()

    private suspend fun items() = graph.attentionReadModel.items()

    /** An AGE subject on [assetId] whose only replacement was [daysAgo] days before `T`. */
    private suspend fun ageSubject(id: String, assetId: String, daysAgo: Long, name: String = "Battery age", sortOrder: Int = 0) {
        graph.events.upsert(replacementOf("e-$id", assetId, graph.today.minusDays(daysAgo).toString()))
        graph.healthSubjects.upsert(subjectRow(id, assetId, name = name, sortOrder = sortOrder))
    }

    /**
     * Inv. 122: a DOWN asset and a DEGRADED component reach ATTENTION with **no** schedule behind
     * either, and the component names its parent.
     */
    @Test fun downAndDegradedAssetsAndComponentsAppearWithNoSchedule() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.conditions.insert(conditionRow("c1", "gen", OperationalCondition.DOWN, "2026-04-10", reason = "Won't start"))
        graph.assets.upsert(assetRow("tub", name = "Hot tub"))
        graph.assets.upsert(assetRow("pack", name = "Battery pack", parent = "tub"))
        graph.conditions.insert(conditionRow("c2", "pack", OperationalCondition.DEGRADED, "2026-04-12", reason = "Slow to charge"))

        val rows = items()

        assertEquals(listOf("gen", "pack"), rows.map { it.assetId.value })
        val down = rows[0]
        assertEquals(AttentionKind.CONDITION, down.kind)
        assertEquals(AttentionSection.ATTENTION, down.section)
        assertEquals(OperationalCondition.DOWN, down.condition)
        assertEquals("Won't start", down.reason)
        assertEquals("2026-04-10", down.occurredOn)
        assertNull(down.parentAssetId)
        assertNull("a CONDITION row carries no health", down.band)

        val component = rows[1]
        assertEquals(OperationalCondition.DEGRADED, component.condition)
        assertEquals(AttentionSection.ATTENTION, component.section)
        assertEquals("the component names its parent", AssetId("tub"), component.parentAssetId)
        assertEquals("Hot tub", component.parentName)
        assertEquals("Battery pack", component.assetName)
    }

    /** Plan decision 36: rank is dense in the order DOWN, DEGRADED, CRITICAL, WARNING. */
    @Test fun rankIsDenseDownDegradedCriticalWarning() = runTest {
        // Named so that name order alone would give the reverse of the group order.
        graph.assets.upsert(assetRow("a-warn", name = "Alpha"))
        ageSubject("h-warn", "a-warn", daysAgo = 50)
        graph.assets.upsert(assetRow("b-crit", name = "Bravo"))
        ageSubject("h-crit", "b-crit", daysAgo = 90)
        graph.assets.upsert(assetRow("c-degr", name = "Charlie"))
        graph.conditions.insert(conditionRow("c1", "c-degr", OperationalCondition.DEGRADED, "2026-04-01"))
        graph.assets.upsert(assetRow("d-down", name = "Delta"))
        graph.conditions.insert(conditionRow("c2", "d-down", OperationalCondition.DOWN, "2026-04-01"))
        // An OPERATIONAL asset and a NOMINAL subject make no row at all.
        graph.assets.upsert(assetRow("e-fine", name = "Echo"))
        graph.conditions.insert(conditionRow("c3", "e-fine", OperationalCondition.OPERATIONAL, "2026-04-01"))
        ageSubject("h-fine", "e-fine", daysAgo = 10)

        val rows = items()

        assertEquals(listOf("d-down", "c-degr", "b-crit", "a-warn"), rows.map { it.assetId.value })
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.rank })
        assertEquals(
            listOf(AttentionKind.CONDITION, AttentionKind.CONDITION, AttentionKind.HEALTH, AttentionKind.HEALTH),
            rows.map { it.kind },
        )
        assertEquals(
            listOf(AttentionSection.ATTENTION, AttentionSection.ATTENTION, AttentionSection.ATTENTION, AttentionSection.UPCOMING),
            rows.map { it.section },
        )
        assertEquals(listOf(null, null, HealthBand.CRITICAL, HealthBand.WARNING), rows.map { it.band })
        val critical = rows[2]
        assertEquals(HealthSubjectId("h-crit"), critical.healthSubjectId)
        assertEquals("Battery age", critical.subjectName)
        assertNull("a HEALTH row carries no condition", critical.condition)
        assertEquals(true, critical.score!! <= 25)
    }

    /**
     * Plan decision 36: within a group, asset name case-insensitively, then asset id, then the
     * subject's `sortOrder` and id.
     */
    @Test fun tiesBreakByAssetNameThenId() = runTest {
        // Name order is not id order, and the two UPS rows differ only in case: case-insensitively
        // they tie, so the id decides — b1 before b2 — although "UPS" sorts before "ups" by code.
        graph.assets.upsert(assetRow("b2", name = "UPS"))
        graph.conditions.insert(conditionRow("c1", "b2", OperationalCondition.DOWN, "2026-04-01"))
        graph.assets.upsert(assetRow("b1", name = "ups"))
        graph.conditions.insert(conditionRow("c2", "b1", OperationalCondition.DOWN, "2026-04-01"))
        graph.assets.upsert(assetRow("k1", name = "Generator"))
        graph.conditions.insert(conditionRow("c3", "k1", OperationalCondition.DOWN, "2026-04-01"))
        // Two CRITICAL subjects on one asset: sortOrder first, then id.
        graph.assets.upsert(assetRow("pack", name = "Battery pack"))
        graph.events.upsert(replacementOf("e-pack", "pack", "2026-01-15"))
        graph.healthSubjects.upsert(subjectRow("h-b", "pack", name = "Cell age", sortOrder = 1))
        graph.healthSubjects.upsert(subjectRow("h-c", "pack", name = "Case age", sortOrder = 0))
        graph.healthSubjects.upsert(subjectRow("h-a", "pack", name = "Seal age", sortOrder = 1))

        val rows = items()

        assertEquals(listOf("k1", "b1", "b2", "pack", "pack", "pack"), rows.map { it.assetId.value })
        assertEquals(listOf("h-c", "h-a", "h-b"), rows.drop(3).map { it.healthSubjectId?.value })
        assertEquals((0..5).toList(), rows.map { it.rank })
    }

    /**
     * The controller's ruling on B07's concern 3: a CONDITION row's `since` is the day the current
     * run of that word began — the S22 "since" date, `ConditionView.since` — while `occurredOn` and
     * `reason` are the latest row's. A DOWN recorded again with a new reason moves neither `since`.
     */
    @Test fun sinceIsTheStartOfTheCurrentRun() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.conditions.insert(conditionRow("c1", "gen", OperationalCondition.DEGRADED, "2026-03-20", reason = "Rough idle"))
        graph.conditions.insert(conditionRow("c2", "gen", OperationalCondition.DOWN, "2026-04-01", reason = "Won't start"))
        graph.conditions.insert(conditionRow("c3", "gen", OperationalCondition.DOWN, "2026-04-05", reason = "Still won't start"))

        val row = items().single()

        assertEquals("2026-04-01", row.since)
        assertEquals("2026-04-05", row.occurredOn)
        assertEquals("Still won't start", row.reason)
        assertEquals(OperationalCondition.DOWN, row.condition)
        assertEquals(OperationalCondition.DOWN, row.assetCondition)
        assertEquals(
            "the same date the health view calls since",
            graph.assetHealthReadModel.forAsset(AssetId("gen")).condition?.since?.toString(),
            row.since,
        )
    }

    /**
     * The same ruling's second half: every row carries its asset's **current** condition, so a
     * condition filter matches a HEALTH row too. A DEGRADED asset's CRITICAL subject says DEGRADED;
     * an asset with nothing recorded says null. `condition` stays "this row is about a condition".
     */
    @Test fun aHealthRowCarriesItsAssetsCurrentCondition() = runTest {
        graph.assets.upsert(assetRow("ups", name = "UPS"))
        graph.conditions.insert(conditionRow("c1", "ups", OperationalCondition.DEGRADED, "2026-04-02"))
        ageSubject("h-ups", "ups", daysAgo = 90)
        graph.assets.upsert(assetRow("pack", name = "Battery pack"))
        ageSubject("h-pack", "pack", daysAgo = 50)

        val rows = items()

        val degraded = rows.single { it.kind == AttentionKind.CONDITION }
        val critical = rows.single { it.healthSubjectId?.value == "h-ups" }
        val warning = rows.single { it.healthSubjectId?.value == "h-pack" }
        assertEquals(OperationalCondition.DEGRADED, degraded.assetCondition)
        assertEquals("the health row carries its asset's condition", OperationalCondition.DEGRADED, critical.assetCondition)
        assertNull("but is not a condition row", critical.condition)
        assertNull(critical.since)
        assertNull("nothing recorded", warning.assetCondition)
    }

    /**
     * Plan decision 21: only AGE subjects are independent rows. A MAINTENANCE_OVERDUE subject
     * scoring CRITICAL rides its schedule's row and never appears here.
     */
    @Test fun onlyAgeSubjectsAreIndependentRows() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.schedules.upsert(scheduleOf("s-gen", assetId = "gen", title = "Engine oil service", anchorOn = "2026-01-01", leadDays = 0))
        graph.healthSubjects.upsert(
            subjectRow("h-oil", "gen", name = "Oil", driver = HealthDriver.MAINTENANCE_OVERDUE, scheduleId = "s-gen"),
        )
        // 104 days overdue with thresholds 0 / 40 / 75: CRITICAL, and still no attention row.
        assertEquals(
            HealthBand.CRITICAL,
            graph.assetHealthReadModel.bandsBySchedule(AssetId("gen"))[ScheduleId("s-gen")]?.band,
        )

        assertEquals(emptyList<AttentionItem>(), items())
    }

    /**
     * Rows cover in-service assets and components only, each on its own lifecycle: an archived
     * DOWN asset, a retired DEGRADED one and a retired CRITICAL one are not listed, while an
     * in-service component of a retired parent is — naming the parent, whose name still resolves.
     */
    @Test fun retiredOrArchivedAssetsAreNotListed() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator", status = AssetStatus.ARCHIVED))
        graph.conditions.insert(conditionRow("c1", "gen", OperationalCondition.DOWN, "2026-04-01"))
        graph.assets.upsert(assetRow("mow", name = "Mower", retiredOn = "2026-03-01"))
        graph.conditions.insert(conditionRow("c2", "mow", OperationalCondition.DEGRADED, "2026-04-01"))
        graph.assets.upsert(assetRow("ups", name = "UPS", retiredOn = "2026-03-01"))
        ageSubject("h-ups", "ups", daysAgo = 90)
        graph.assets.upsert(assetRow("pack", name = "Battery pack", parent = "mow"))
        graph.conditions.insert(conditionRow("c3", "pack", OperationalCondition.DOWN, "2026-04-02"))

        val rows = items()

        assertEquals(listOf("pack"), rows.map { it.assetId.value })
        assertEquals("Mower", rows.single().parentName)
    }
}
