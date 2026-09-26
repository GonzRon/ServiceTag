package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.LinkKind
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentRepository
import com.loosecannon.servicetag.core.testing.InMemoryCategoryRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryLinkRepository
import com.loosecannon.servicetag.core.testing.InMemoryTagRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 2.7.1 (#40) — "is there anything on this phone a restore would replace?"
 *
 * One case per kind of record, each on its own, because the question the Backup screen asks is
 * whether *anything at all* is here: a phone holding one tombstone link row and nothing else has
 * something to lose, and offering it a plain confirm would be a lie. The six kinds are the six
 * the use case reads; definitions and profiles are not among them because neither can exist
 * without the asset it names (`Journal.kt:10`, `Journal.kt:30`), so the asset check answers for
 * both. #74's category row is the sixth: it exists without any asset by design.
 */
class StoreIsEmptyTest {

    private val assets = InMemoryAssetRepository()
    private val tags = InMemoryTagRepository()
    private val events = InMemoryEventRepository()
    private val attachments = InMemoryAttachmentRepository()
    private val links = InMemoryLinkRepository()
    private val categories = InMemoryCategoryRepository()
    private val storeIsEmpty = StoreIsEmpty(assets, tags, events, attachments, links, categories)

    /** An invented canonical UUID, the same one `ResolveTagTest` uses. Never a real tag's id. */
    private val tagKey = "123e4567-e89b-12d3-a456-426614174000"

    private val asset = Asset(AssetId("a1"), "Hot tub", createdAt = 1L, updatedAt = 1L)

    private val tag = TagBinding(
        id = TagId(tagKey),
        payloadFormat = PayloadFormat.V1,
        payloadKey = tagKey,
        target = TagTarget.AssetTarget(AssetId("a1")),
        createdAt = 1L,
        updatedAt = 1L,
    )

    private val event = AssetEvent(
        id = EventId("e1"), assetId = AssetId("a1"), kind = EventKind.MAINTENANCE,
        title = "Filter change", profileId = null, occurredOn = "2026-09-18",
        occurredTime = null, tzId = "UTC", notes = "", source = EventSource.MANUAL,
        sourceRef = null, createdAt = 1L, updatedAt = 1L,
        measurements = emptyList(), consumables = emptyList(),
    )

    private val attachment = Attachment(
        id = AttachmentId("att-1"),
        owner = AttachmentOwner.OfAsset(AssetId("a1")),
        kind = AttachmentKind.DOCUMENT,
        displayName = "Guide.pdf",
        mimeType = "application/pdf",
        sizeBytes = 8L,
        sha256 = "0".repeat(64),
        storageLocator = "assets/a1/att-1.pdf",
        capturedOn = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private val link = ExternalLink(
        id = LinkId("l1"),
        kind = LinkKind.JOPLIN,
        label = "note",
        uri = "joplin://x",
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test fun anUntouchedInstallIsEmpty() = runTest {
        assertTrue(storeIsEmpty.run())
    }

    @Test fun oneAssetIsEnoughToMakeItNotEmpty() = runTest {
        assets.upsert(asset)
        assertFalse(storeIsEmpty.run())
    }

    @Test fun oneTagRowIsEnoughToMakeItNotEmpty() = runTest {
        tags.upsert(tag)
        assertFalse(storeIsEmpty.run())
    }

    @Test fun oneEventIsEnoughToMakeItNotEmpty() = runTest {
        events.upsert(event)
        assertFalse(storeIsEmpty.run())
    }

    @Test fun oneAttachmentRowIsEnoughToMakeItNotEmpty() = runTest {
        attachments.upsert(attachment)
        assertFalse(storeIsEmpty.run())
    }

    /** 2.6's tombstone: nothing displays it, and it is still a record this phone holds. */
    @Test fun oneTombstoneLinkRowIsEnoughToMakeItNotEmpty() = runTest {
        links.upsert(link)
        assertFalse(storeIsEmpty.run())
    }

    /**
     * #74: a category outlives the last asset that used it, so one row with no asset anywhere is a
     * record this phone holds — and one a restore would delete.
     */
    @Test fun oneCategoryRowIsEnoughToMakeItNotEmpty() = runTest {
        categories.upsert(AssetCategory("appliance", "Appliance", 1L, 1L))
        assertFalse(storeIsEmpty.run())
    }
}
