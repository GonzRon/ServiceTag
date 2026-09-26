package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.model.AssetId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * `CreateAsset` promotes (#74, C5): the row it returns is the canonical one it stored, a first-use
 * category's row is written with it, and a refusal adds no row.
 */
class CreateAssetTest {

    private val h = SeasonCommandHarness()

    @Test
    fun aNewCategoryIsStoredAsTypedAndBecomesARow() = runBlocking<Unit> {
        val created = h.createAsset.run(AssetCommand(name = "Compressor", category = "  Appliance "))

        assertEquals("Appliance", created.category)
        assertEquals(created, h.stored(created.id.value), "the returned row is the stored row")
        assertEquals(
            mapOf("appliance" to AssetCategory("appliance", "Appliance", created.createdAt, created.createdAt)),
            h.categories.rows.toMap(),
            "the row and the asset share the command's timestamp",
        )
    }

    /** A variant of a saved category stores the row's spelling and adds nothing (AC 3). */
    @Test
    fun aVariantStoresTheSavedSpellingAndAddsNoRow() = runBlocking<Unit> {
        val first = h.createAsset.run(AssetCommand(name = "Compressor", category = "Appliance"))
        h.now += 5_000L
        val second = h.createAsset.run(AssetCommand(name = "Blower", category = "APPLIANCE  "))

        assertEquals("Appliance", second.category)
        assertEquals("Appliance", h.stored(second.id.value).category)
        assertEquals(
            mapOf("appliance" to AssetCategory("appliance", "Appliance", first.createdAt, first.createdAt)),
            h.categories.rows.toMap(),
        )
    }

    /** A built-in stores its label and is never a row; blank stores blank. */
    @Test
    fun aBuiltInStoresItsLabelAndBlankStoresBlank() = runBlocking<Unit> {
        assertEquals("Hot tub", h.createAsset.run(AssetCommand(name = "Spa", category = "hot  tub")).category)
        assertEquals("", h.createAsset.run(AssetCommand(name = "Spare part", category = "   ")).category)
        assertTrue(h.categories.rows.isEmpty())
    }

    /** Every refusal comes before the row: a 422 of any kind adds no category. */
    @Test
    fun aRefusedCreateAddsNoRow() = runBlocking<Unit> {
        assertFailsWith<AssetValidation> { h.createAsset.run(AssetCommand(name = "  ", category = "Appliance")) }
        assertFailsWith<AssetValidation> {
            h.createAsset.run(AssetCommand(name = "Compressor", category = "Appliance", parentAssetId = AssetId("nope")))
        }
        assertFailsWith<UnknownTemplate> {
            h.createAsset.run(AssetCommand(name = "Compressor", category = "Appliance"), templateKey = "no_such_template")
        }
        assertTrue(h.categories.rows.isEmpty())
        assertTrue(h.assets.rows.isEmpty())
    }
}
