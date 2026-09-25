package com.loosecannon.servicetag.ui.dashboard

import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.ui.maintenance.AttentionItem
import com.loosecannon.servicetag.ui.maintenance.AttentionKind
import com.loosecannon.servicetag.ui.maintenance.AttentionSection
import com.loosecannon.servicetag.ui.maintenance.DueItem
import com.loosecannon.servicetag.ui.maintenance.statusLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dashboard's filters (spec §10.2; master plan §13.2, plan decision 26, the controller's ruling
 * on M14), as a truth table over hand-built rows:
 *
 * - a **schedule** row passes when it is in the category, matches the status picker (if set) **and**
 *   its Asset's condition matches a selected chip (if any);
 * - a **group** row has no condition and passes only while no chip is selected;
 * - an **asset** row passes when no status is selected **or** at least one chip is, and it matches
 *   the chips (if any) — so asset rows hide only when **only** a status is selected;
 * - category applies to both kinds; filters narrow and never re-rank.
 */
class DashboardFiltersTest {

    @Test fun deferredIsAStatusOption() {
        // The picker offers every status, DEFERRED among them, drawn with its status word (S92).
        assertEquals(DueStatus.entries.toSet(), FILTERABLE_STATUSES.toSet())
        assertEquals(FILTERABLE_STATUSES.size, FILTERABLE_STATUSES.distinct().size)
        assertEquals("DEFERRED", statusLabel(DueStatus.DEFERRED))
        assertTrue("DEFERRED" in FILTERABLE_STATUSES.map(::statusLabel))
        // Where its section is: after OK (CURRENT), before OUT OF SEASON.
        assertEquals(
            FILTERABLE_STATUSES.indexOf(DueStatus.OK) + 1,
            FILTERABLE_STATUSES.indexOf(DueStatus.DEFERRED),
        )
        assertEquals(
            FILTERABLE_STATUSES.indexOf(DueStatus.DEFERRED) + 1,
            FILTERABLE_STATUSES.indexOf(DueStatus.INACTIVE_SEASON),
        )

        // A Deferred row answers DEFERRED, and never OVERDUE or DUE.
        val held = schedule("held", AttentionSection.DEFERRED, DueStatus.DEFERRED)
        assertTrue(DashboardFilters(status = DueStatus.DEFERRED).admits(held))
        assertFalse(DashboardFilters(status = DueStatus.OVERDUE).admits(held))
        assertFalse(DashboardFilters(status = DueStatus.DUE).admits(held))
    }

    /**
     * The four chips are the ratified words S12, S10, S8 and S26 and nothing else, in spec §10.2's
     * order: worst first.
     */
    @Test fun theChipsAreTheFourRatifiedWords() {
        assertEquals(
            listOf("Down", "Degraded", "Operational", "Not recorded"),
            ConditionChip.entries.map { it.label },
        )
        assertTrue(ConditionChip.NOT_RECORDED.matches(null))
        assertFalse(ConditionChip.NOT_RECORDED.matches(OperationalCondition.OPERATIONAL))
    }

    @Test fun statusOnlyHidesAssetRows() {
        val statusOnly = DashboardFilters(status = DueStatus.OVERDUE)
        for (condition in listOf(OperationalCondition.DOWN, OperationalCondition.DEGRADED, OperationalCondition.OPERATIONAL, null)) {
            assertFalse("an asset row ($condition) under a status alone", statusOnly.admitsAssetRow("Yard", condition))
        }
        // The schedule rows the status selects are still there.
        assertTrue(statusOnly.admits(schedule("s1", AttentionSection.ATTENTION, DueStatus.OVERDUE)))
        // And with nothing chosen at all, every asset row passes.
        assertTrue(DashboardFilters().admitsAssetRow("Yard", OperationalCondition.DOWN))
        assertTrue(DashboardFilters().admitsAssetRow("Yard", null))
    }

    /** M14: a status and a chip together let a **matching** asset row pass, and only a matching one. */
    @Test fun aStatusPlusAMatchingChipLetsTheAssetRowPass() {
        val both = DashboardFilters(status = DueStatus.OVERDUE, conditions = setOf(ConditionChip.DOWN))

        assertTrue("the DOWN unit's row passes", both.admitsAssetRow("Yard", OperationalCondition.DOWN))
        assertFalse(both.admitsAssetRow("Yard", OperationalCondition.DEGRADED))
        assertFalse(both.admitsAssetRow("Yard", null))

        // Schedule rows need both: the status **and** the chip.
        assertTrue(both.admits(schedule("s1", AttentionSection.ATTENTION, DueStatus.OVERDUE, condition = OperationalCondition.DOWN)))
        assertFalse(both.admits(schedule("s2", AttentionSection.ATTENTION, DueStatus.OVERDUE, condition = OperationalCondition.DEGRADED)))
        assertFalse(both.admits(schedule("s3", AttentionSection.ATTENTION, DueStatus.DUE, condition = OperationalCondition.DOWN)))
    }

    @Test fun conditionChipsSelectScheduleRowsByTheirAssetsCondition() {
        val rows = listOf(
            schedule("down", AttentionSection.ATTENTION, DueStatus.OVERDUE, condition = OperationalCondition.DOWN),
            schedule("degraded", AttentionSection.ATTENTION, DueStatus.DUE, condition = OperationalCondition.DEGRADED),
            schedule("fine", AttentionSection.CURRENT, DueStatus.OK, condition = OperationalCondition.OPERATIONAL),
            schedule("unknown", AttentionSection.CURRENT, DueStatus.OK, condition = null),
        )
        fun pass(vararg chips: ConditionChip) =
            rows.filter { DashboardFilters(conditions = chips.toSet()).admits(it) }.map { it.scheduleId.value }

        assertEquals(listOf("down", "degraded", "fine", "unknown"), pass())
        assertEquals(listOf("down"), pass(ConditionChip.DOWN))
        assertEquals(listOf("unknown"), pass(ConditionChip.NOT_RECORDED))
        assertEquals(listOf("degraded", "fine"), pass(ConditionChip.OPERATIONAL, ConditionChip.DEGRADED))
        assertEquals(listOf("down", "unknown"), pass(ConditionChip.DOWN, ConditionChip.NOT_RECORDED))
    }

    @Test fun groupRowsPassOnlyWithoutChips() {
        val group = schedule("g", AttentionSection.ATTENTION, DueStatus.DUE, assetId = null)

        assertTrue(DashboardFilters().admits(group))
        assertTrue("the status still selects a group row", DashboardFilters(status = DueStatus.DUE).admits(group))
        for (chip in ConditionChip.entries) {
            // "Not recorded" included: a group has no condition to be missing.
            assertFalse("a group row under $chip", DashboardFilters(conditions = setOf(chip)).admits(group))
        }
    }

    /**
     * Category narrows both kinds. The Dashboard has had no search box since 1.2 (B07 moved it to
     * the Assets screen), so the search half has nothing to act on here: the category is the one
     * text-free control that applies to both.
     */
    @Test fun categoryAndSearchApplyToBothKinds() {
        val water = DashboardFilters(category = "Water")

        assertTrue(water.admits(schedule("tub", AttentionSection.ATTENTION, DueStatus.OVERDUE, category = "Water")))
        assertFalse(water.admits(schedule("mow", AttentionSection.ATTENTION, DueStatus.OVERDUE, category = "Yard")))
        assertTrue(water.admitsAssetRow("Water", OperationalCondition.DOWN))
        assertFalse(water.admitsAssetRow("Yard", OperationalCondition.DOWN))

        val sections = assembleSections(
            schedules = listOf(
                schedule("tub", AttentionSection.ATTENTION, DueStatus.OVERDUE, category = "Water"),
                schedule("mow", AttentionSection.ATTENTION, DueStatus.OVERDUE, category = "Yard"),
            ),
            attention = listOf(unit("pump", OperationalCondition.DOWN), unit("gen", OperationalCondition.DEGRADED)),
            categoryOf = { id -> if (id.value == "pump") "Water" else "Yard" },
        )
        assertEquals(
            listOf("pump", "a-tub"),
            water.narrow(sections).single().entries.map { it.assetId },
        )
    }

    /**
     * Filters narrow and never re-rank: every survivor is where it was, in the same section, with
     * the same rank, and a section a filter empties is dropped rather than drawn bare.
     */
    @Test fun filtersNeverReRank() {
        val sections = assembleSections(
            schedules = listOf(
                schedule("s-a", AttentionSection.ATTENTION, DueStatus.OVERDUE, condition = OperationalCondition.DOWN, rank = 0),
                schedule("s-b", AttentionSection.ATTENTION, DueStatus.DUE, condition = OperationalCondition.OPERATIONAL, rank = 1),
                schedule("s-c", AttentionSection.ATTENTION, DueStatus.DUE, condition = OperationalCondition.DOWN, rank = 2),
                schedule("s-d", AttentionSection.UPCOMING, DueStatus.DUE_SOON, condition = OperationalCondition.OPERATIONAL, rank = 3),
                schedule("s-e", AttentionSection.CURRENT, DueStatus.OK, condition = OperationalCondition.DOWN, rank = 4),
            ),
            attention = listOf(
                unit("u-z", OperationalCondition.DOWN, rank = 0),
                unit("u-y", OperationalCondition.DEGRADED, rank = 1),
                health("u-x", HealthBand.CRITICAL, assetCondition = OperationalCondition.DOWN, rank = 2),
            ),
            categoryOf = { "Yard" },
        )
        assertEquals(
            listOf("u-z", "a-s-a", "a-s-b", "a-s-c", "u-y", "u-x"),
            sections.first().entries.map { it.assetId },
        )

        val narrowed = DashboardFilters(conditions = setOf(ConditionChip.DOWN)).narrow(sections)

        assertEquals(
            "UPCOMING lost its only row and is dropped; the rest keep their order",
            listOf(AttentionSection.ATTENTION, AttentionSection.CURRENT),
            narrowed.map { it.section },
        )
        assertEquals(listOf("u-z", "a-s-a", "a-s-c", "u-x"), narrowed.first().entries.map { it.assetId })
        // Each survivor is the very row it was, rank included.
        val before = sections.flatMap { it.entries }
        narrowed.flatMap { it.entries }.forEach { survivor -> assertTrue("$survivor unchanged", survivor in before) }
        assertEquals(listOf(0, 2), narrowed.first().items.map { it.rank })
        assertEquals(listOf(0, 2), narrowed.first().assetRows.map { it.rank })
    }

    private companion object {
        @Suppress("LongParameterList")
        fun schedule(
            id: String,
            section: AttentionSection,
            status: DueStatus,
            assetId: String? = "a-$id",
            condition: OperationalCondition? = null,
            category: String? = "Yard",
            rank: Int = 0,
        ): DueItem = DueItem(
            scheduleId = ScheduleId(id),
            title = id,
            target = assetId?.let { ScheduleTarget.AssetTarget(AssetId(it)) } ?: ScheduleTarget.GroupTarget(GroupId("g-$id")),
            assetName = assetId ?: "Group $id",
            parentName = null,
            category = if (assetId == null) null else category,
            status = status,
            section = section,
            requiredSetEmpty = false,
            effectiveDueOn = null,
            actionableDueOn = null,
            policyReason = PolicyReason.NONE,
            policyPhase = PolicyPhase.ACTIVE,
            quiet = false,
            seasonMode = null,
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
            assetCondition = if (assetId == null) null else condition,
            rank = rank,
        )

        fun unit(assetId: String, condition: OperationalCondition, rank: Int = 0): AttentionItem =
            attentionItem(assetId, AttentionKind.CONDITION, AttentionSection.ATTENTION, condition, condition, null, rank)

        fun health(assetId: String, band: HealthBand, assetCondition: OperationalCondition?, rank: Int = 0): AttentionItem =
            attentionItem(
                assetId, AttentionKind.HEALTH,
                if (band == HealthBand.CRITICAL) AttentionSection.ATTENTION else AttentionSection.UPCOMING,
                assetCondition, null, band, rank,
            )

        @Suppress("LongParameterList")
        private fun attentionItem(
            assetId: String,
            kind: AttentionKind,
            section: AttentionSection,
            assetCondition: OperationalCondition?,
            condition: OperationalCondition?,
            band: HealthBand?,
            rank: Int,
        ) = AttentionItem(
            kind = kind,
            section = section,
            assetId = AssetId(assetId),
            assetName = assetId,
            parentAssetId = null,
            parentName = null,
            assetCondition = assetCondition,
            condition = condition,
            reason = condition?.let { "" },
            occurredOn = condition?.let { "2026-04-01" },
            since = condition?.let { "2026-04-01" },
            healthSubjectId = band?.let { HealthSubjectId("h-$assetId") },
            subjectName = band?.let { "Battery age" },
            band = band,
            score = band?.let { 10 },
            rank = rank,
        )
    }
}
