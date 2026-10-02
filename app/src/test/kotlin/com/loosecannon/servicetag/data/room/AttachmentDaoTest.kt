package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentMode
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.AttachmentSource
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.StorageProvider
import com.loosecannon.servicetag.data.room.entities.AssetEventEntity
import com.loosecannon.servicetag.data.room.entities.AttachmentEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `attachment` table as the app will actually use it (spec §9.1): a row per file, metadata
 * only, hanging off either an asset or an event. What is worth proving here is not the round trip
 * — that is one mapper — but the three rules the *schema* enforces and no Kotlin code re-checks:
 * the two CASCADEs that make a row die with its owner, the unique `(provider, locator)` that
 * stops two rows claiming the same bytes, and the exactly-one-owner `require` that stands in for
 * the CHECK constraint Room cannot express.
 */
class AttachmentDaoTest {

    private val db = inMemoryDb()
    private val assets = RoomAssetRepository(db.assetDao())
    private val events = RoomEventRepository(db.eventDao())
    private val attachments = RoomAttachmentRepository(db.attachmentDao())

    @After fun close() = db.close()

    @Test fun roundTripsARowOnAnAssetAndOnAnEvent() = runTest {
        seedAsset("a1")
        seedEvent("e1", "a1")

        val onAsset = attachment("att-1", AttachmentOwner.OfAsset(AssetId("a1")), "assets/a1/att-1.pdf")
        val onEvent = attachment(
            id = "att-2",
            owner = AttachmentOwner.OfEvent(EventId("e1")),
            locator = "events/e1/att-2.jpg",
            kind = AttachmentKind.PHOTO,
            mimeType = "image/jpeg",
            createdAt = 2L,
        )
        attachments.upsert(onAsset)
        attachments.upsert(onEvent)

        // Every field comes back, both owners included, through the production mappers.
        assertEquals(onAsset, attachments.get(AttachmentId("att-1")))
        assertEquals(onEvent, attachments.get(AttachmentId("att-2")))
        assertNull(attachments.get(AttachmentId("att-nope")))

        // `forOwner` asks each owner separately; `forAsset` is the asset's own rows only.
        assertEquals(
            listOf(AttachmentId("att-1")),
            attachments.forOwner(AttachmentOwner.OfAsset(AssetId("a1"))).map { it.id },
        )
        assertEquals(
            listOf(AttachmentId("att-2")),
            attachments.forOwner(AttachmentOwner.OfEvent(EventId("e1"))).map { it.id },
        )
        assertEquals(
            listOf(AttachmentId("att-1")),
            attachments.forAsset(AssetId("a1")).map { it.id },
        )

        // `all()` is created_at order, and `count()` agrees with it.
        assertEquals(
            listOf(AttachmentId("att-1"), AttachmentId("att-2")),
            attachments.all().map { it.id },
        )
        assertEquals(2, attachments.count())

        // The update half of the update-then-insert upsert: the same id, a new name, no new row.
        attachments.upsert(onAsset.copy(displayName = "Renamed.pdf", updatedAt = 9L))
        assertEquals(2, attachments.count())
        assertEquals("Renamed.pdf", attachments.get(AttachmentId("att-1"))!!.displayName)
        assertEquals(9L, attachments.get(AttachmentId("att-1"))!!.updatedAt)

        // And a row can be taken out one at a time.
        attachments.delete(AttachmentId("att-1"))
        assertNull(attachments.get(AttachmentId("att-1")))
        assertEquals(1, attachments.count())

        attachments.deleteAll()
        assertEquals(0, attachments.count())
        assertEquals(emptyList<Attachment>(), attachments.all())
    }

    /** #85, C2: the four source columns travel both ways; a row with none reads back with no source at all. */
    @Test fun aSourceRoundTripsAndNullStaysNull() = runTest {
        seedAsset("a1")
        seedEvent("e1", "a1")
        val full = attachment("att-s1", AttachmentOwner.OfAsset(AssetId("a1")), "assets/a1/att-s1.pdf")
            .copy(
                source = AttachmentSource(
                    uri = "https://manuals.example.invalid/pump.pdf?rev=2",
                    resolvedUri = "https://cdn.example.invalid/files/pump.pdf",
                    retrievedAt = 1_758_900_000_123L,
                    name = "Example Pool Pump manual",
                ),
            )
        val bare = attachment("att-s2", AttachmentOwner.OfEvent(EventId("e1")), "events/e1/att-s2.pdf", createdAt = 2L)
            .copy(source = AttachmentSource("https://manuals.example.invalid/x.pdf", null, 5L, null))
        val none = attachment("att-s3", AttachmentOwner.OfAsset(AssetId("a1")), "assets/a1/att-s3.pdf", createdAt = 3L)
        attachments.upsert(full)
        attachments.upsert(bare)
        attachments.upsert(none)

        assertEquals(full, attachments.get(AttachmentId("att-s1")))
        assertEquals(1_758_900_000_123L, attachments.get(AttachmentId("att-s1"))!!.source!!.retrievedAt)
        assertEquals(bare, attachments.get(AttachmentId("att-s2")))
        assertNull(attachments.get(AttachmentId("att-s2"))!!.source!!.resolvedUri)
        assertNull(attachments.get(AttachmentId("att-s2"))!!.source!!.name)
        assertNull(attachments.get(AttachmentId("att-s3"))!!.source)
        assertNull(none.toEntity().sourceUri)
    }

    @Test fun deletingTheAssetCascadesItsAttachmentRows() = runTest {
        seedAsset("a1")
        seedAsset("a2")
        attachments.upsert(
            attachment("att-1", AttachmentOwner.OfAsset(AssetId("a1")), "assets/a1/att-1.pdf"),
        )
        attachments.upsert(
            attachment("att-2", AttachmentOwner.OfAsset(AssetId("a2")), "assets/a2/att-2.pdf"),
        )
        assertEquals(2, attachments.count())

        assets.delete(AssetId("a1"))

        assertNull(attachments.get(AttachmentId("att-1")))
        assertEquals(1, attachments.count())
        // Only the deleted asset's row went: a cascade is not a wipe.
        assertEquals(AttachmentId("att-2"), attachments.get(AttachmentId("att-2"))!!.id)
    }

    @Test fun deletingTheEventCascadesItsAttachmentRows() = runTest {
        seedAsset("a1")
        seedEvent("e1", "a1")
        seedEvent("e2", "a1")
        attachments.upsert(
            attachment("att-1", AttachmentOwner.OfEvent(EventId("e1")), "events/e1/att-1.pdf"),
        )
        attachments.upsert(
            attachment("att-2", AttachmentOwner.OfEvent(EventId("e2")), "events/e2/att-2.pdf"),
        )

        events.delete(EventId("e1"))

        assertNull(attachments.get(AttachmentId("att-1")))
        assertEquals(1, attachments.count())
        assertEquals(AttachmentId("att-2"), attachments.get(AttachmentId("att-2"))!!.id)
    }

    @Test fun deletingTheAssetAlsoTakesItsEventsAttachments() = runTest {
        // asset -> event -> attachment: two cascades in one delete.
        seedAsset("a1")
        seedEvent("e1", "a1")
        attachments.upsert(
            attachment("att-1", AttachmentOwner.OfAsset(AssetId("a1")), "assets/a1/att-1.pdf"),
        )
        attachments.upsert(
            attachment("att-2", AttachmentOwner.OfEvent(EventId("e1")), "events/e1/att-2.jpg"),
        )
        assertEquals(2, attachments.count())

        assets.delete(AssetId("a1"))

        assertEquals(0, attachments.count())
        assertNull(attachments.get(AttachmentId("att-1")))
        assertNull(attachments.get(AttachmentId("att-2")))
    }

    @Test fun twoRowsCannotClaimTheSameProviderAndLocator() = runTest {
        seedAsset("a1")
        attachments.upsert(
            attachment("att-1", AttachmentOwner.OfAsset(AssetId("a1")), "assets/a1/att-1.pdf"),
        )
        val second = attachment(
            "att-2", AttachmentOwner.OfAsset(AssetId("a1")), "assets/a1/att-1.pdf",
        )

        val boom = runCatching { attachments.upsert(second) }.exceptionOrNull()

        assertTrue("expected a unique-index failure, got $boom", boom != null)
        assertNull("the refused row must not be there", attachments.get(AttachmentId("att-2")))
        assertEquals(1, attachments.count())
    }

    @Test fun aRowWithBothOwnersOrNeitherIsRefusedByTheMapper() = runTest {
        val both = AttachmentEntity(
            id = "x", assetId = "a1", eventId = "e1", kind = "DOCUMENT", mode = "MANAGED",
            displayName = "x.pdf", mimeType = "application/pdf", sizeBytes = 12L,
            sha256 = "a".repeat(64), storageProvider = "SAF_TREE",
            storageLocator = "assets/a1/x.pdf", capturedOn = null, notes = "",
            createdAt = 1L, updatedAt = 1L, documentRole = null,
            sourceUri = null, sourceResolvedUri = null, sourceRetrievedAt = null, sourceName = null,
            supplyItemId = null, installedComponentId = null,
        )
        assertThrows(IllegalArgumentException::class.java) { both.requireExactlyOneOwner() }

        val neither = both.copy(assetId = null, eventId = null)
        assertThrows(IllegalArgumentException::class.java) { neither.requireExactlyOneOwner() }

        // One owner, either one, is what the rule allows — and it hands the row straight back.
        val onAsset = both.copy(eventId = null)
        assertEquals(onAsset, onAsset.requireExactlyOneOwner())
        val onEvent = both.copy(assetId = null)
        assertEquals(onEvent, onEvent.requireExactlyOneOwner())
    }

    @Test fun observeForOwnerEmitsOnEveryWriteAndIsOrderedByName() = runTest {
        seedAsset("a1")
        val owner = AttachmentOwner.OfAsset(AssetId("a1"))

        attachments.upsert(
            attachment("att-z", owner, "assets/a1/att-z.pdf", displayName = "Zebra.pdf"),
        )
        assertEquals(
            listOf("Zebra.pdf"),
            attachments.observeForOwner(owner).first().map { it.displayName },
        )

        attachments.upsert(
            attachment("att-a", owner, "assets/a1/att-a.pdf", displayName = "Apple.pdf"),
        )
        assertEquals(
            listOf("Apple.pdf", "Zebra.pdf"),
            attachments.observeForOwner(owner).first().map { it.displayName },
        )

        // A delete is a write too, and the flow re-queries after one.
        attachments.delete(AttachmentId("att-a"))
        assertEquals(
            listOf("Zebra.pdf"),
            attachments.observeForOwner(owner).first().map { it.displayName },
        )

        // An event owner's flow is the other query, and it never sees the asset's rows.
        seedEvent("e1", "a1")
        val eventOwner = AttachmentOwner.OfEvent(EventId("e1"))
        assertEquals(
            emptyList<String>(),
            attachments.observeForOwner(eventOwner).first().map { it.displayName },
        )
        attachments.upsert(
            attachment("att-e", eventOwner, "events/e1/att-e.jpg", displayName = "Label.jpg"),
        )
        assertEquals(
            listOf("Label.jpg"),
            attachments.observeForOwner(eventOwner).first().map { it.displayName },
        )
    }

    private suspend fun seedAsset(id: String) = assets.upsert(
        Asset(
            id = AssetId(id),
            name = "Asset $id",
            status = AssetStatus.ACTIVE,
            createdAt = 1L,
            updatedAt = 1L,
        ),
    )

    /** The event row on its own: no readings, no consumables — nothing here is about them. */
    private suspend fun seedEvent(id: String, assetId: String) = db.eventDao().upsert(
        AssetEventEntity(
            id = id, assetId = assetId, kind = "MEASUREMENT", title = "Water test",
            profileId = null, occurredOn = "2026-09-14", occurredTime = null, tzId = "UTC",
            notes = "", source = "MANUAL", sourceRef = id, createdAt = 1L, updatedAt = 1L,
        ),
        emptyList(),
        emptyList(),
    )

    private fun attachment(
        id: String,
        owner: AttachmentOwner,
        locator: String,
        displayName: String = "$id.pdf",
        kind: AttachmentKind = AttachmentKind.DOCUMENT,
        mimeType: String = "application/pdf",
        createdAt: Long = 1L,
    ) = Attachment(
        id = AttachmentId(id),
        owner = owner,
        kind = kind,
        mode = AttachmentMode.MANAGED,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = 12L,
        sha256 = "a".repeat(64),
        storageProvider = StorageProvider.SAF_TREE,
        storageLocator = locator,
        capturedOn = null,
        notes = "",
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
