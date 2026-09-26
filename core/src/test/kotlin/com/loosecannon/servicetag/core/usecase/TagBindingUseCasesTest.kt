package com.loosecannon.servicetag.core.usecase

import com.loosecannon.nfc.tagcore.TagIdentity
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.nfc.NdefCodec
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryCategoryRepository
import com.loosecannon.servicetag.core.testing.InMemoryDefinitionRepository
import com.loosecannon.servicetag.core.testing.InMemoryProfileRepository
import com.loosecannon.servicetag.core.testing.InMemoryTagRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagBindingUseCasesTest {
    /** Any identity will do here: what is under test is that a provisioned id encodes at all. */
    private val ndefCodec = NdefCodec(TagIdentity("com.example.app", "tag", "com.example.app"))
    private val assets = InMemoryAssetRepository()
    private val tags = InMemoryTagRepository()
    private val categories = InMemoryCategoryRepository()
    private val uow = FakeUnitOfWork(assets, tags, categories)
    private var seq = 0
    private val ids = IdGenerator { "00000000-0000-4000-8000-%012d".format(++seq) }
    private val clock = Clock { 7_000L }
    private val bind = BindTag(tags, assets, uow, clock)
    private val provision = ProvisionTag(tags, assets, uow, ids, clock)
    private val defs = InMemoryDefinitionRepository()
    private val profiles = InMemoryProfileRepository()
    private val applyTemplate = ApplyTemplate(defs, profiles, assets, uow, ids, clock)
    private val create = CreateAsset(assets, uow, ids, clock, applyTemplate, PromoteCategory(categories))

    private val scanned = TagId("123e4567-e89b-12d3-a456-426614174000")
    private val a1 = TagTarget.AssetTarget(AssetId("a1"))

    private fun seedAsset() { assets.rows["a1"] = Asset(AssetId("a1"), "Hot tub", createdAt = 1L, updatedAt = 1L) }

    // --- BindTag ---------------------------------------------------------------------------

    @Test fun bindingAnUnknownV1TagCreatesARowWhoseIdIsTheTagId() = runTest {
        seedAsset()
        val row = bind.run(PayloadFormat.V1, scanned.value, a1, label = "lid")
        assertEquals(scanned, row.id)
        assertEquals(scanned.value, row.payloadKey)
        assertEquals(PayloadFormat.V1, row.payloadFormat)
        assertEquals(a1, row.target)
        assertEquals(TagStatus.ACTIVE, row.status)
        assertEquals("lid", row.label)
        assertEquals(7_000L, row.createdAt)
        assertEquals(row, tags.rows[scanned.value])
    }
    @Test fun bindingAKnownRowRetargetsItAndReactivates() = runTest {
        seedAsset()
        assets.rows["a2"] = Asset(AssetId("a2"), "Mower", createdAt = 1L, updatedAt = 1L)
        tags.rows[scanned.value] = TagBinding(scanned, PayloadFormat.V1, scanned.value, TagTarget.None, TagStatus.UNBOUND, label = "spare", createdAt = 1L, updatedAt = 1L)
        val row = bind.run(PayloadFormat.V1, scanned.value, TagTarget.AssetTarget(AssetId("a2")))
        assertEquals(TagTarget.AssetTarget(AssetId("a2")), row.target)
        assertEquals(TagStatus.ACTIVE, row.status)
        assertEquals("spare", row.label)          // label kept when none is given
        assertEquals(1L, row.createdAt)
        assertEquals(7_000L, row.updatedAt)
        assertEquals(1, tags.rows.size)
    }
    @Test fun bindingToAMissingTargetFailsAndWritesNothing() = runTest {
        assertFailsWith<UnknownTarget> { bind.run(PayloadFormat.V1, scanned.value, a1) }
        assertTrue(tags.rows.isEmpty())
        assertEquals(0, uow.commits)
    }
    @Test fun bindingToNoTargetIsRefused() = runTest {
        assertFailsWith<IllegalArgumentException> { bind.run(PayloadFormat.V1, scanned.value, TagTarget.None) }
    }
    @Test fun bindingRefusesANonCanonicalV1Key() = runTest {
        seedAsset()
        assertFailsWith<IllegalArgumentException> { bind.run(PayloadFormat.V1, "NOT-A-UUID", a1) }
        assertTrue(tags.rows.isEmpty())
    }
    /** 2.6 — binding a link target is refused by type, and the refusal writes nothing. */
    @Test fun bindingALinkTargetIsRefusedAndWritesNothing() = runTest {
        assertFailsWith<LinkTargetUnsupported> {
            bind.run(PayloadFormat.V1, scanned.value, TagTarget.LinkTarget(LinkId("l1")))
        }
        assertTrue(tags.rows.isEmpty())
        assertEquals(0, uow.commits)
    }

    /** The same refusal on the provisioning side: no row is minted for a link. */
    @Test fun provisioningALinkTargetIsRefusedAndWritesNothing() = runTest {
        assertFailsWith<LinkTargetUnsupported> { provision.begin(TagTarget.LinkTarget(LinkId("l1")), "x") }
        assertTrue(tags.rows.isEmpty())
        assertEquals(0, uow.commits)
    }

    // --- ProvisionTag ----------------------------------------------------------------------

    @Test fun beginCreatesAnEncodableUnwrittenRow() = runTest {
        seedAsset()
        val row = provision.begin(a1, label = "panel")
        assertEquals(row.id.value, row.payloadKey)
        assertEquals(PayloadFormat.V1, row.payloadFormat)
        assertEquals(TagStatus.ACTIVE, row.status)
        assertNull(row.writtenAt)
        assertEquals(2, ndefCodec.encodeV1(row.id).size)   // the id is a canonical UUID
        assertEquals(row, tags.rows[row.id.value])
    }
    @Test fun beginWithNoTargetIsASpare() = runTest {
        val row = provision.begin(TagTarget.None, label = null)
        assertEquals(TagStatus.UNBOUND, row.status)
        assertEquals(TagTarget.None, row.target)
    }
    @Test fun beginRefusesAMissingTarget() = runTest {
        assertFailsWith<UnknownTarget> { provision.begin(a1, null) }
        assertTrue(tags.rows.isEmpty())
    }
    @Test fun completeStampsWrittenAtAndUid() = runTest {
        val row = provision.begin(TagTarget.None, null)
        val done = provision.complete(row.id, physicalUid = "04a1b2c3d4e5f6")
        assertEquals(7_000L, done.writtenAt)
        assertEquals("04a1b2c3d4e5f6", done.physicalUid)
        assertEquals(done, tags.rows[row.id.value])
    }
    @Test fun completeOnAnUnknownRowFails() = runTest {
        assertFailsWith<IllegalStateException> { provision.complete(TagId("nope"), null) }
    }
    @Test fun abandonDeletesOnlyAnUnwrittenRow() = runTest {
        val unwritten = provision.begin(TagTarget.None, null)
        val written = provision.complete(provision.begin(TagTarget.None, null).id, null)
        provision.abandon(unwritten.id)
        provision.abandon(written.id)
        provision.abandon(TagId("nope"))
        assertNull(tags.rows[unwritten.id.value])
        assertEquals(written, tags.rows[written.id.value])
    }

    // --- CreateAsset -----------------------------------------------------------------------

    @Test fun createAssetTrimsAndStores() = runTest {
        val asset = create.run(
            AssetCommand(
                name = "  Pool pump ",
                category = "Yard",
                manufacturer = " Pentair ",
                serialNumber = " SN-3 ",
                purchasePriceMinor = 45_000L,
                currency = "USD",
                seasonStartMmdd = "05-01",
                seasonEndMmdd = "09-30",
            ),
        )
        assertEquals("Pool pump", asset.name)
        assertEquals("Yard", asset.category)
        assertEquals("Pentair", asset.manufacturer)
        assertEquals("SN-3", asset.serialNumber)
        assertEquals(45_000L, asset.purchasePriceMinor)
        assertEquals("USD", asset.currency)
        assertEquals("05-01", asset.seasonStartMmdd)
        assertEquals("09-30", asset.seasonEndMmdd)
        assertEquals(7_000L, asset.createdAt)
        assertEquals(asset, assets.rows[asset.id.value])
    }
    @Test fun createAssetRefusesABlankName() = runTest {
        assertFailsWith<IllegalArgumentException> { create.run("   ") }
        assertTrue(assets.rows.isEmpty())
    }
    @Test fun createAssetRefusesAnUnknownParent() = runTest {
        val boom = assertFailsWith<AssetValidation> {
            create.run(AssetCommand(name = "Heater", parentAssetId = AssetId("ghost")))
        }
        assertEquals(listOf(AssetProblem.UnknownParent), boom.problems)
        assertTrue(assets.rows.isEmpty())
    }
}
