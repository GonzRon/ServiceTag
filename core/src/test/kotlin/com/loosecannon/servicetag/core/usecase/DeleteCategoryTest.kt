package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.AttachmentStore
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentRepository
import com.loosecannon.servicetag.core.testing.InMemoryCategoryRepository
import com.loosecannon.servicetag.core.testing.InMemoryClosureRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryGroupRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** Delete (#74, C7; AC 5, 9; R74-6): an unused row goes; an in-use one is refused with the count, and nothing moves. */
class DeleteCategoryTest {

    private val categories = InMemoryCategoryRepository()
    private val assets = InMemoryAssetRepository()
    private val uow = FakeUnitOfWork(categories, assets)
    private val delete = DeleteCategory(categories, assets, uow)

    private val appliance = AssetCategory("appliance", "Appliance", 100L, 100L)

    private fun asset(id: String, category: String, status: AssetStatus = AssetStatus.ACTIVE, retiredOn: String? = null) =
        Asset(
            id = AssetId(id), name = "Asset $id", category = category, status = status, retiredOn = retiredOn,
            createdAt = 500L, updatedAt = 500L,
        ).also { assets.rows[id] = it }

    @Test
    fun anUnusedCategoryIsDeleted() = runBlocking<Unit> {
        categories.rows["appliance"] = appliance
        categories.rows["pool"] = AssetCategory("pool", "Pool", 100L, 100L)
        asset("p1", "Pool")

        delete.run("appliance")
        assertEquals(listOf("pool"), categories.rows.keys.toList())
        assertFailsWith<NoSuchCategory> { delete.run("appliance") }
    }

    /** Refused with every user's count — archived and retired included — and nothing is written or reassigned. */
    @Test
    fun anInUseDeleteIsRefusedWithTheCountAndWritesNothing() = runBlocking<Unit> {
        categories.rows["appliance"] = appliance
        asset("f1", "Appliance")
        asset("f2", "Appliance", status = AssetStatus.ARCHIVED)
        asset("f3", "Appliance", retiredOn = "2025-01-01")
        asset("p1", "Pump")
        val assetsBefore = assets.rows.toMap()

        val refusal = assertFailsWith<CategoryInUse> { delete.run("appliance") }
        assertEquals("Appliance" to 3, refusal.display to refusal.count)
        assertEquals(mapOf("appliance" to appliance), categories.rows.toMap())
        assertEquals(assetsBefore, assets.rows.toMap())
        assertEquals(3, CategoryUsage.count(assets.rows.values, "appliance"))
    }

    /** AC 5: deleting the last Asset that uses a category leaves the category. */
    @Test
    fun deletingTheLastAssetUsingACategoryLeavesTheRow() = runBlocking<Unit> {
        val events = InMemoryEventRepository()
        val attachments = InMemoryAttachmentRepository()
        val closures = InMemoryClosureRepository()
        val schedules = InMemoryScheduleRepository(closures, InMemoryScheduleStateRepository())
        val groups = InMemoryGroupRepository()
        val storage = object : AttachmentStorage {
            override fun state(): StoreState = StoreState.NotConfigured
            override fun store(): AttachmentStore? = null
        }
        val deleteAsset = DeleteAsset(
            assets, events, attachments, storage, FakeUnitOfWork(assets, events, attachments, schedules, groups, closures),
            groups, schedules, closures,
        )
        categories.rows["appliance"] = appliance
        asset("f1", "Appliance")

        deleteAsset.run(AssetId("f1"))
        assertTrue(assets.rows.isEmpty())
        assertEquals(mapOf("appliance" to appliance), categories.rows.toMap())
    }
}
