package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.journal.CategorySuggestions
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.testing.InMemoryCategoryRepository
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** The two halves of promotion (#74, C5; AC 1–3, 7): `resolve` decides and never writes, `write` writes a new row only. */
class PromoteCategoryTest {

    private val categories = InMemoryCategoryRepository()
    private val promote = PromoteCategory(categories)

    @Test
    fun blankResolvesToBlankWithNoRow() = runBlocking<Unit> {
        assertEquals(Promotion("", null), promote.resolve("", 500L))
        assertEquals(Promotion("", null), promote.resolve(" \t ", 500L))
    }

    /** A built-in is never a row: any spelling of one resolves to its label. */
    @Test
    fun aBuiltInResolvesToItsLabelWithNoRow() = runBlocking<Unit> {
        assertEquals(Promotion("Hot tub", null), promote.resolve("hot  tub", 500L))
        assertEquals(Promotion("UPS", null), promote.resolve("ups", 500L))
        promote.write(promote.resolve("HOT TUB", 500L))
        assertTrue(categories.rows.isEmpty())
    }

    /** First use keeps the typed spelling, collapsed, and names the row it would add — with the command's `now`. */
    @Test
    fun aNewTextResolvesToANewRow() = runBlocking<Unit> {
        assertEquals(
            Promotion("Water heater", AssetCategory("water heater", "Water heater", 500L, 500L)),
            promote.resolve("  Water  heater ", 500L),
        )
        assertTrue(categories.rows.isEmpty(), "resolve reads; it never writes")
    }

    /** A variant of a stored row resolves to the row's spelling, and adds nothing. */
    @Test
    fun aVariantResolvesToTheStoredDisplayWithNoRow() = runBlocking<Unit> {
        categories.rows["appliance"] = AssetCategory("appliance", "Appliance", 100L, 100L)
        assertEquals(Promotion("Appliance", null), promote.resolve("APPLIANCE ", 900L))
        assertEquals(Promotion("Appliance", null), promote.resolve("appliance", 900L))
    }

    @Test
    fun writeUpsertsOnlyANewRow() = runBlocking<Unit> {
        promote.write(Promotion("Appliance", null))
        assertTrue(categories.rows.isEmpty())

        promote.write(promote.resolve("Appliance", 700L))
        assertEquals(mapOf("appliance" to AssetCategory("appliance", "Appliance", 700L, 700L)), categories.rows.toMap())

        promote.write(promote.resolve("  appliance", 900L))
        assertEquals(mapOf("appliance" to AssetCategory("appliance", "Appliance", 700L, 700L)), categories.rows.toMap())
    }

    /** The hint stays on built-ins only, now by key (R74-12): a saved custom category never acquires one. */
    @Test
    fun theHintIsABuiltInsOnly() = runBlocking<Unit> {
        promote.write(promote.resolve("Appliance", 700L))
        assertNull(CategorySuggestions.templateFor("Appliance"))
        assertEquals("hot_tub", CategorySuggestions.templateFor("hot  tub"))
        assertEquals("hot_tub", CategorySuggestions.templateFor("HOT TUB"))
    }
}
