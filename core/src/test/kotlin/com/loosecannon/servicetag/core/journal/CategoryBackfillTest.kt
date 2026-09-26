package com.loosecannon.servicetag.core.journal

import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.model.AssetId
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/** The pure backfill rule (#74, C9) that `MIGRATION_8_9` applies once, over the saves made before the catalog. */
class CategoryBackfillTest {

    private fun asset(id: String, category: String, createdAt: Long) = AssetRow(AssetId(id), category, createdAt)

    /** Variants of one key make one row, spelled as the **oldest** Asset spells it; every other variant is rewritten. */
    @Test
    fun variantsOfOneKeyBecomeOneRowSpelledAsTheOldest() {
        val plan = CategoryBackfill.plan(
            listOf(
                asset("a1", "appliance", 300L),
                asset("a2", "Appliance", 100L),
                asset("a3", " APPLIANCE", 200L),
            ),
        )
        assertEquals(listOf(AssetCategory("appliance", "Appliance", 100L, 100L)), plan.newRows)
        assertEquals(mapOf(AssetId("a1") to "Appliance", AssetId("a3") to "Appliance"), plan.rewrites)
    }

    /** A `createdAt` tie goes to the smaller id, and the spelling kept is the key rule's display of it. */
    @Test
    fun aTieGoesToTheSmallerIdAndTheSpellingIsCollapsed() {
        val plan = CategoryBackfill.plan(
            listOf(
                asset("t-2", "WATER heater", 100L),
                asset("t-1", "Water  heater", 100L),
            ),
        )
        assertEquals(listOf(AssetCategory("water heater", "Water heater", 100L, 100L)), plan.newRows)
        assertEquals(mapOf(AssetId("t-2") to "Water heater", AssetId("t-1") to "Water heater"), plan.rewrites)
    }

    /** A built-in's key makes no row: its Assets are rewritten to the label, and one already spelled so is left. */
    @Test
    fun builtInsAreRewrittenToTheLabelWithNoRow() {
        val plan = CategoryBackfill.plan(
            listOf(
                asset("h1", "hot tub", 100L),
                asset("h2", "Hot tub", 50L),
                asset("r1", "RO  SYSTEM", 70L),
            ),
        )
        assertEquals(emptyList(), plan.newRows)
        assertEquals(mapOf(AssetId("h1") to "Hot tub", AssetId("r1") to "RO system"), plan.rewrites)
    }

    @Test
    fun blanksAreIgnoredAndNoAssetsIsNothing() {
        assertEquals(Backfill(emptyList(), emptyMap()), CategoryBackfill.plan(listOf(asset("b1", "", 1L), asset("b2", "   ", 2L))))
        assertEquals(Backfill(emptyList(), emptyMap()), CategoryBackfill.plan(emptyList()))
    }

    /** Several keys at once: one row each, ordered by key. */
    @Test
    fun oneRowPerKeyOrderedByKey() {
        val plan = CategoryBackfill.plan(
            listOf(
                asset("w1", "Water", 500L),
                asset("p1", "Pool", 400L),
                asset("x1", "Backup power", 900L),
                asset("g1", "generator", 10L),
            ),
        )
        assertEquals(
            listOf(
                AssetCategory("backup power", "Backup power", 900L, 900L),
                AssetCategory("pool", "Pool", 400L, 400L),
                AssetCategory("water", "Water", 500L, 500L),
            ),
            plan.newRows,
        )
        assertEquals(mapOf(AssetId("g1") to "Generator"), plan.rewrites)
    }

    // --- N6: the one chooser, with rows that already exist (the replace and the merge planner) -----

    /**
     * An existing row wins over every Asset's spelling, however old the Asset: its variants are
     * rewritten to it and it is never emitted again. A key nobody holds yet still gets the oldest rule.
     */
    @Test
    fun anExistingRowWinsAndIsNotReEmitted() {
        val plan = CategoryBackfill.plan(
            listOf(
                asset("a1", "appliance", 100L),
                asset("a2", "APPLIANCE", 50L),
                asset("a3", "Appliance", 70L),
                asset("w1", "Water", 10L),
            ),
            existing = listOf(AssetCategory("appliance", "Appliance", 900L, 950L)),
        )
        assertEquals(listOf(AssetCategory("water", "Water", 10L, 10L)), plan.newRows)
        assertEquals(mapOf(AssetId("a1") to "Appliance", AssetId("a2") to "Appliance"), plan.rewrites)
    }

    /** A built-in still beats a row filed under its key — the catalog's own precedence (C3, C5). */
    @Test
    fun aBuiltInBeatsAnExistingRowUnderItsKey() {
        val plan = CategoryBackfill.plan(
            listOf(asset("h1", "HOT TUB", 1L)),
            existing = listOf(AssetCategory("hot tub", "hot tub", 1L, 1L)),
        )
        assertEquals(emptyList(), plan.newRows)
        assertEquals(mapOf(AssetId("h1") to "Hot tub"), plan.rewrites)
    }

    /** An existing row no Asset names is left alone: nothing emitted, nothing rewritten. */
    @Test
    fun anUnusedExistingRowIsNothing() {
        assertEquals(
            Backfill(emptyList(), emptyMap()),
            CategoryBackfill.plan(emptyList(), existing = listOf(AssetCategory("spare", "Spare", 1L, 1L))),
        )
    }
}
