package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.DeadlineLocalDelivery
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `deadline_local_delivery` as the delivery path will use it (#79, C17): a device-local stamp keyed by
 * `(kind, subject_id)` with **no foreign key**. What is worth proving is what the schema allows and
 * the port promises: a row for a subject no table knows is accepted, an asset's delete takes nothing
 * with it (the delivery path forgets a stale row, not the database), and the port's five calls do
 * what they say through the production mappers.
 */
class DeadlineDeliveryRoomTest {

    private val db = inMemoryDb()
    private val assets = RoomAssetRepository(db.assetDao())
    private val deliveries = RoomDeadlineLocalDeliveryRepository(db.deadlineLocalDeliveryDao())

    @After fun close() = db.close()

    private fun stamp(subjectId: String, hash: String = "0123456789abcdef", boot: Int? = 7, kind: String = "WARRANTY_EXPIRY") =
        DeadlineLocalDelivery(kind, subjectId, hash, boot, updatedAt = 1_000L)

    @Test
    fun aRowForAnUnknownAssetIsAcceptedAndOutlivesItsAsset() = runTest {
        assets.upsert(Asset(id = AssetId("a1"), name = "Example Heater", createdAt = 1L, updatedAt = 1L))
        deliveries.upsert(stamp("a1"))
        deliveries.upsert(stamp("a-never-stored", boot = null))

        assets.delete(AssetId("a1"))

        assertNull("the asset is gone", assets.get(AssetId("a1")))
        assertEquals(
            "both rows stay: nothing cascades into a device-local stamp",
            listOf(stamp("a-never-stored", boot = null), stamp("a1")),
            deliveries.all(),
        )
    }

    @Test
    fun theRowRoundTripsAndIsForgottenOneAtATime() = runTest {
        deliveries.upsert(stamp("a2"))
        deliveries.upsert(stamp("a1"))
        deliveries.upsert(stamp("a1", kind = "OTHER_KIND"))
        assertEquals(stamp("a1"), deliveries.get("WARRANTY_EXPIRY", "a1"))
        assertNull(deliveries.get("WARRANTY_EXPIRY", "a9"))

        val moved = stamp("a1", hash = "fedcba9876543210", boot = 8).copy(updatedAt = 2_000L)
        deliveries.upsert(moved)
        assertEquals("upsert replaces the whole row", moved, deliveries.get("WARRANTY_EXPIRY", "a1"))
        assertEquals("ordered by kind, then subject", listOf("OTHER_KIND:a1", "WARRANTY_EXPIRY:a1", "WARRANTY_EXPIRY:a2"),
            deliveries.all().map { "${it.kind}:${it.subjectId}" })

        deliveries.delete("WARRANTY_EXPIRY", "a1")
        assertNull(deliveries.get("WARRANTY_EXPIRY", "a1"))
        assertEquals("the same subject under another kind is its own row", stamp("a1", kind = "OTHER_KIND"),
            deliveries.get("OTHER_KIND", "a1"))

        deliveries.deleteAll()
        assertEquals(emptyList<DeadlineLocalDelivery>(), deliveries.all())
    }
}
