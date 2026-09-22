package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.isRetired
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.testing.FakeAttachmentStorage
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentRepository
import com.loosecannon.servicetag.core.testing.InMemoryClosureRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryGroupRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.closureOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Retirement is data, not a status (spec §7): `retiredOn` carries it and [AssetStatus] is left
 * alone, so a retired asset can also be archived — or not — independently. Delete is the one
 * destructive asset action and it stops at a parent that still has children (§5).
 */
class RetireDeleteAssetTest {
    private val assets = InMemoryAssetRepository()
    private val events = InMemoryEventRepository()
    private val attachments = InMemoryAttachmentRepository()
    private val storage = FakeAttachmentStorage()
    private val groups = InMemoryGroupRepository()
    private val closures = InMemoryClosureRepository()
    private val states = InMemoryScheduleStateRepository()
    private val schedules = InMemoryScheduleRepository(closures, states)
    private val uow = FakeUnitOfWork(assets, events, attachments, groups, closures, schedules, states)
    private var now = 1_000L
    private val clock = Clock { now }
    // Explicitly nothing: see AssetUseCasesTest. The seam has no default.
    private val retire = RetireAsset(assets, uow, clock) { }
    private val delete = DeleteAsset(assets, events, attachments, storage, uow, groups, schedules, closures)
    private val archive = ArchiveAsset(assets, uow, clock) { }

    private suspend fun store(id: String, name: String, parent: AssetId? = null): Asset {
        val asset = Asset(
            id = AssetId(id),
            name = name,
            createdAt = 500L,
            updatedAt = 500L,
            parentAssetId = parent,
        )
        assets.upsert(asset)
        return asset
    }

    @Test fun retireSetsDateAndKeepsStatus() = runTest {
        store("a1", "Pool pump")
        now = 9_000L
        val retired = retire.retire(AssetId("a1"), "2026-09-15")
        assertEquals("2026-09-15", retired.retiredOn)
        assertTrue(retired.isRetired)
        assertEquals(AssetStatus.ACTIVE, retired.status)   // retirement is not archival
        assertEquals(500L, retired.createdAt)
        assertEquals(9_000L, retired.updatedAt)
        assertEquals(retired, assets.rows["a1"])
        assertEquals(1, uow.commits)

        // a date that is not an ISO date is refused, and writes nothing
        val boom = assertFailsWith<AssetValidation> { retire.retire(AssetId("a1"), "15/09/2026") }
        assertEquals(listOf(AssetProblem.BadDate("retiredOn")), boom.problems)
        assertFailsWith<NoSuchAsset> { retire.retire(AssetId("nope"), "2026-09-15") }
        assertEquals(1, uow.commits)
    }

    @Test fun unretireClears() = runTest {
        store("a1", "Pool pump")
        retire.retire(AssetId("a1"), "2026-09-15")
        now = 12_000L
        val back = retire.unretire(AssetId("a1"))
        assertNull(back.retiredOn)
        assertFalse(back.isRetired)
        assertEquals(12_000L, back.updatedAt)
        assertEquals(back, assets.rows["a1"])
        assertEquals(2, uow.commits)
        assertFailsWith<NoSuchAsset> { retire.unretire(AssetId("nope")) }
    }

    @Test fun deleteRefusedWithChildrenNamed() = runTest {
        store("a1", "Hot tub")
        store("a3", "Heater", parent = AssetId("a1"))
        store("a2", "Blower", parent = AssetId("a1"))
        store("a4", "Element", parent = AssetId("a3"))   // a grandchild, not a direct child

        val boom = assertFailsWith<AssetHasChildren> { delete.run(AssetId("a1")) }
        assertEquals(AssetId("a1"), boom.assetId)
        assertEquals(listOf(AssetId("a2"), AssetId("a3")), boom.children)   // by name, case-insensitive
        assertEquals(4, assets.rows.size)
        assertEquals(0, uow.commits)
        assertFailsWith<NoSuchAsset> { delete.run(AssetId("nope")) }
    }

    @Test fun deleteWithoutChildrenRemoves() = runTest {
        store("a1", "Hot tub")
        store("a2", "Heater", parent = AssetId("a1"))
        delete.run(AssetId("a2"))
        assertEquals(setOf("a1"), assets.rows.keys)
        assertEquals(1, uow.commits)
        // with the child gone the parent can go too
        delete.run(AssetId("a1"))
        assertTrue(assets.rows.isEmpty())
        assertEquals(2, uow.commits)
    }

    /**
     * Invariant 8 at the use case: a member of a group that has already recorded a round cannot be
     * deleted, because the membership table's cascade from its asset would take the windows
     * `required(D)` is derived from — and the round is already history.
     *
     * The control is the second group in the same store: nobody has serviced it, so it holds no
     * round to rewrite and its member is deletable. That pair is what makes this a rule about
     * recorded rounds rather than about membership as such.
     */
    @Test fun deleteRefusedWhileMembershipCarriesARecordedRound() = runTest {
        store("a1", "Sprinkler 1")
        store("a2", "Sprinkler 2")
        groups.upsert(groupOf("g1", members = listOf(Triple("a1", "2026-01-01", null))))
        groups.upsert(groupOf("g2", name = "South run", members = listOf(Triple("a2", "2026-01-01", null))))
        schedules.upsert(scheduleOf("s1", assetId = null, groupId = "g1", timeInterval = 3, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-01-01"))
        schedules.upsert(scheduleOf("s2", assetId = null, groupId = "g2", timeInterval = 3, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-01-01"))
        events.upsert(completionOf("e1", occurredOn = "2026-04-01", occurrenceOn = "2026-04-01", assetId = "a1", scheduleId = "s1"))

        val boom = assertFailsWith<AssetMembershipReferenced> { delete.run(AssetId("a1")) }
        assertEquals(AssetId("a1"), boom.assetId)
        assertEquals(listOf(GroupId("g1")), boom.groups)
        assertTrue(assets.rows.containsKey("a1"))
        assertEquals(1, groups.rows["g1"]!!.members.size)
        assertEquals(0, uow.commits)

        // A closure alone is the other recorded fact, and it refuses on its own.
        closures.insert(closureOf("c1", occurrenceOn = "2026-04-01", closedOn = "2026-04-02", scheduleId = "s2"))
        assertFailsWith<AssetMembershipReferenced> { delete.run(AssetId("a2")) }

        // And a group nobody has serviced holds nothing to rewrite.
        store("a3", "Sprinkler 3")
        groups.upsert(groupOf("g3", name = "West run", members = listOf(Triple("a3", "2026-01-01", null))))
        schedules.upsert(scheduleOf("s3", assetId = null, groupId = "g3", timeInterval = 3, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-01-01"))
        delete.run(AssetId("a3"))
        assertFalse(assets.rows.containsKey("a3"))
    }

    @Test fun archiveDoesNotCascade() = runTest {
        store("a1", "Hot tub")
        store("a2", "Heater", parent = AssetId("a1"))
        archive.run(AssetId("a1"))
        assertEquals(AssetStatus.ARCHIVED, assets.rows["a1"]!!.status)
        assertEquals(AssetStatus.ACTIVE, assets.rows["a2"]!!.status)
        assertNull(assets.rows["a2"]!!.retiredOn)
        assertEquals(AssetId("a1"), assets.rows["a2"]!!.parentAssetId)

        // nor does retiring one
        retire.retire(AssetId("a1"), "2026-09-15")
        assertNull(assets.rows["a2"]!!.retiredOn)
    }

    @Test fun deletingAnAssetRemovesItsOwnAndItsEventsAttachmentBytes() = runTest {
        store("a1", "Hot tub")
        events.upsert(
            AssetEvent(
                id = EventId("e1"), assetId = AssetId("a1"), kind = EventKind.MAINTENANCE,
                title = "Filter change", profileId = null, occurredOn = "2026-09-15",
                occurredTime = null, tzId = "UTC", notes = "", source = EventSource.MANUAL,
                sourceRef = null, createdAt = 1L, updatedAt = 1L,
                measurements = emptyList(), consumables = emptyList(),
            ),
        )
        val onAsset = attachment("att-1", AttachmentOwner.OfAsset(AssetId("a1")), "assets/a1/att-1.pdf")
        val onEvent = attachment("att-2", AttachmentOwner.OfEvent(EventId("e1")), "events/e1/att-2.jpg")
        // a third row on another asset, which must survive
        store("a2", "Mower")
        val elsewhere = attachment("att-3", AttachmentOwner.OfAsset(AssetId("a2")), "assets/a2/att-3.pdf")

        delete.run(AssetId("a1"))

        assertFalse(storage.store.exists(onAsset.storageLocator))
        assertFalse(storage.store.exists(onEvent.storageLocator))
        assertTrue(storage.store.exists(elsewhere.storageLocator))
        assertEquals(2, storage.store.deletes)   // the survivor was never asked about
        assertEquals(setOf("a2"), assets.rows.keys)
        assertEquals(1, uow.commits)             // locators read and rows deleted in one write
    }

    @Test fun anAbsentStoreIsNotAReasonToKeepTheAsset() = runTest {
        store("a1", "Hot tub")
        val orphaned = attachment("att-1", AttachmentOwner.OfAsset(AssetId("a1")), "assets/a1/att-1.pdf")
        storage.state = StoreState.AccessLost("Attachments")
        delete.run(AssetId("a1"))
        assertTrue(assets.rows.isEmpty())
        assertEquals(0, storage.store.deletes)
        assertTrue(storage.store.exists(orphaned.storageLocator))   // an orphan for 4B to sweep
    }

    /** Seeds a row and its bytes: the fake repository has no cascade, so the row goes too. */
    private suspend fun attachment(id: String, owner: AttachmentOwner, locator: String): Attachment {
        val row = Attachment(
            id = AttachmentId(id), owner = owner, kind = AttachmentKind.DOCUMENT,
            displayName = "$id.pdf", mimeType = "application/pdf", sizeBytes = 3L,
            sha256 = "0".repeat(64), storageLocator = locator, capturedOn = null,
            createdAt = 1L, updatedAt = 1L,
        )
        attachments.upsert(row)
        storage.store.put(locator, ByteSource { "abc".toByteArray().inputStream() })
        return row
    }
}
