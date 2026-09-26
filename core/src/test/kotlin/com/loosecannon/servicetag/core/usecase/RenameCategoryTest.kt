package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryCategoryRepository
import com.loosecannon.servicetag.core.testing.RiggedFailure
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** Rename (#74, C6; AC 9; R74-5): the row and every Asset of its key, archived and retired included, in one transaction. */
class RenameCategoryTest {

    private val categories = InMemoryCategoryRepository()
    private val assets = InMemoryAssetRepository()
    private val uow = FakeUnitOfWork(categories, assets)
    private var now = 2_000L
    private val rename = RenameCategory(categories, assets, uow, Clock { now })

    private val appliance = AssetCategory("appliance", "Appliance", 100L, 150L)

    private fun asset(id: String, category: String, status: AssetStatus = AssetStatus.ACTIVE, retiredOn: String? = null) =
        Asset(
            id = AssetId(id), name = "Asset $id", category = category, status = status, retiredOn = retiredOn,
            createdAt = 500L, updatedAt = 500L,
        ).also { assets.rows[id] = it }

    private fun seed() {
        categories.rows["appliance"] = appliance
        asset("f1", "Appliance")
        asset("f2", "Appliance", status = AssetStatus.ARCHIVED)
        asset("f3", "Appliance", retiredOn = "2025-01-01")
        asset("p1", "Pump")
    }

    private fun categoryOf(id: String) = assets.rows.getValue(id).category to assets.rows.getValue(id).updatedAt

    @Test
    fun aSpellingOnlyRenameUpdatesTheRowInPlaceAndEveryMatchingAsset() = runBlocking<Unit> {
        seed()
        val renamed = rename.run("appliance", "APPLIANCE")

        assertEquals(AssetCategory("appliance", "APPLIANCE", 100L, 2_000L), renamed)
        assertEquals(mapOf("appliance" to renamed), categories.rows.toMap())
        for (id in listOf("f1", "f2", "f3")) assertEquals("APPLIANCE" to 2_000L, categoryOf(id), id)
        assertEquals("Pump" to 500L, categoryOf("p1"))
    }

    @Test
    fun aNewKeyInsertsKeepingCreatedAtRewritesEveryAssetAndDeletesTheOldRow() = runBlocking<Unit> {
        seed()
        val renamed = rename.run("appliance", "Large  appliance")

        assertEquals(AssetCategory("large appliance", "Large appliance", 100L, 2_000L), renamed)
        assertEquals(mapOf("large appliance" to renamed), categories.rows.toMap())
        for (id in listOf("f1", "f2", "f3")) assertEquals("Large appliance" to 2_000L, categoryOf(id), id)
        assertEquals("Pump" to 500L, categoryOf("p1"))
        assertEquals(1, uow.commits)
    }

    /** The same display is a no-op: nothing is written and no `updatedAt` moves. */
    @Test
    fun anUnchangedRenameWritesNothing() = runBlocking<Unit> {
        seed()
        val before = assets.rows.toMap()
        assertEquals(appliance, rename.run("appliance", "  Appliance "))
        assertEquals(mapOf("appliance" to appliance), categories.rows.toMap())
        assertEquals(before, assets.rows.toMap())
    }

    @Test
    fun blankBuiltInAndTakenNamesAreRefusedAndWriteNothing() = runBlocking<Unit> {
        seed()
        categories.rows["water heater"] = AssetCategory("water heater", "Water heater", 50L, 50L)
        val rowsBefore = categories.rows.toMap()
        val assetsBefore = assets.rows.toMap()

        assertFailsWith<CategoryValidation> { rename.run("appliance", "   ") }
        assertEquals("Hot tub", assertFailsWith<CategoryIsBuiltIn> { rename.run("appliance", "hot  TUB") }.label)
        assertEquals("Water heater", assertFailsWith<CategoryExists> { rename.run("appliance", "WATER heater") }.existingDisplay)
        assertFailsWith<NoSuchCategory> { rename.run("gone", "Anything") }

        assertEquals(rowsBefore, categories.rows.toMap())
        assertEquals(assetsBefore, assets.rows.toMap())
    }

    /** All or nothing: an Asset write that fails leaves the old row and every Asset as they were. */
    @Test
    fun aFailingAssetWriteRollsTheWholeRenameBack() = runBlocking<Unit> {
        seed()
        val assetsBefore = assets.rows.toMap()
        assets.failOnUpsert = 2
        assertFailsWith<RiggedFailure> { rename.run("appliance", "Large appliance") }

        assertEquals(mapOf("appliance" to appliance), categories.rows.toMap())
        assertEquals(assetsBefore, assets.rows.toMap())
        assertEquals(1, uow.rollbacks)
    }
}
