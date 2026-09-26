package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * `UpdateAsset` promotes (#74, C5): it always upserts, so a successful edit stores the catalog's
 * spelling and writes a first-use category's row; the 422 and the 409 come first and add no row.
 */
class UpdateAssetTest {

    private val h = SeasonCommandHarness()
    private val a1 = AssetId("a1")

    @Test
    fun anEditStoresTheCatalogSpellingAndWritesAFirstUseRow() = runBlocking<Unit> {
        h.asset()
        val saved = h.updateAsset.run(a1, AssetCommand(name = "Compressor", category = "Water  heater"))

        assertEquals("Water heater", saved.category)
        assertEquals(saved, h.stored())
        assertEquals(
            mapOf("water heater" to AssetCategory("water heater", "Water heater", saved.updatedAt, saved.updatedAt)),
            h.categories.rows.toMap(),
        )

        h.now += 1_000L
        assertEquals("Water heater", h.updateAsset.run(a1, AssetCommand(name = "Compressor", category = "WATER HEATER")).category)
        assertEquals(1, h.categories.rows.size, "a variant adds nothing")
        assertEquals("RO system", h.updateAsset.run(a1, AssetCommand(name = "Compressor", category = "ro  system")).category)
        assertEquals(1, h.categories.rows.size, "a built-in is never a row")
    }

    /** It always upserts: re-saving a category that predates the catalog, unchanged, still promotes it. */
    @Test
    fun anUnchangedEditStillPromotes() = runBlocking<Unit> {
        h.asset().also { h.assets.rows["a1"] = it.copy(category = "Water") }
        h.updateAsset.run(a1, AssetCommand(name = "Asset a1", category = "Water"))
        assertEquals(listOf("water"), h.categories.rows.keys.toList())
    }

    @Test
    fun the422AndThe409AddNoRow() = runBlocking<Unit> {
        h.asset()
        assertFailsWith<AssetValidation> { h.updateAsset.run(a1, AssetCommand(name = " ", category = "Appliance")) }

        h.asset(id = "m1", mode = SeasonMode.MANUAL)
        h.activation("act-1", SeasonAction.START, "2026-05-01", assetId = "m1")
        assertFailsWith<LegacyWriteCannotRepresent> {
            h.updateAsset.run(
                AssetId("m1"),
                AssetCommand(name = "Asset m1", category = "Appliance", seasonStartMmdd = "04-15", seasonEndMmdd = "10-31"),
            )
        }

        h.asset(id = "c1", mode = SeasonMode.CALENDAR, seasonStart = "11-15", seasonEnd = "03-31")
        h.schedule("s1", assetId = "c1", title = "Pre-season service")
        assertFailsWith<SeasonModeStrandsPolicy> {
            h.updateAsset.run(AssetId("c1"), AssetCommand(name = "Asset c1", category = "Appliance"))
        }

        assertTrue(h.categories.rows.isEmpty())
    }
}
