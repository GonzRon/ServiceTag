package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.nfc.TagPayload
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryTagRepository
import com.loosecannon.servicetag.core.testing.InMemoryTransferRecordRepository
import com.loosecannon.servicetag.core.testing.transferOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResolveTagTest {
    private val assets = InMemoryAssetRepository()
    private val tags = InMemoryTagRepository()
    private val uow = FakeUnitOfWork(assets, tags)
    private val clock = Clock { 9_000L }
    private val resolve = ResolveTag(tags, assets, uow, clock, InMemoryTransferRecordRepository())

    private val v1Id = TagId("123e4567-e89b-12d3-a456-426614174000")
    private val asset = Asset(AssetId("a1"), "Hot tub", createdAt = 1L, updatedAt = 1L)

    private fun row(id: String, format: PayloadFormat, key: String, target: TagTarget, status: TagStatus = TagStatus.ACTIVE) =
        TagBinding(TagId(id), format, key, target, status, createdAt = 1L, updatedAt = 1L)

    /**
     * #77 (C20, R77-11; row 34): a tag whose asset is transferred out from this phone answers TransferredOut with the
     * asset and the open OUT that holds it — never OpenAsset — and a scan does not stamp it. The peek agrees.
     */
    @Test fun aHeldAssetsTagIsTransferredOutAndUnstamped() = runTest {
        val transfers = InMemoryTransferRecordRepository()
        val resolveHere = ResolveTag(tags, assets, uow, clock, transfers)
        assets.rows["a1"] = asset
        tags.rows[v1Id.value] = row(v1Id.value, PayloadFormat.V1, v1Id.value, TagTarget.AssetTarget(AssetId("a1")))
        val out = transferOf("out-1", assetId = "a1", packId = "pack-q", nameSnapshot = "Hot tub")
        transfers.append(out)

        val scanned = resolveHere.run(TagPayload.V1(v1Id))
        assertIs<Resolution.TransferredOut>(scanned)
        assertEquals(asset, scanned.asset)
        assertEquals(out, scanned.record)
        assertNull(tags.rows[v1Id.value]!!.lastScannedAt, "a held asset's tag is never stamped")

        val peeked = resolveHere.peek(TagPayload.V1(v1Id))
        assertIs<Resolution.TransferredOut>(peeked)
        assertEquals(out, peeked.record)
    }

    @Test fun boundToAnAssetOpensIt() = runTest {
        assets.rows["a1"] = asset
        tags.rows[v1Id.value] = row(v1Id.value, PayloadFormat.V1, v1Id.value, TagTarget.AssetTarget(AssetId("a1")))
        val r = resolve.run(TagPayload.V1(v1Id))
        assertIs<Resolution.OpenAsset>(r)
        assertEquals(asset, r.asset)
        assertEquals(9_000L, r.tag.lastScannedAt)
        assertEquals(9_000L, tags.rows[v1Id.value]!!.lastScannedAt)
        assertEquals(1L, tags.rows[v1Id.value]!!.updatedAt)
    }
    /**
     * 2.6 — a tag bound to a pre-split link resolves to its own outcome. No link row is read (the
     * resolver has no `LinkRepository` at all), nothing is launched, and it is never mistaken for
     * an asset even when an asset with the link's id exists.
     */
    @Test fun aLinkBoundTagIsPreSplitAndNeverAnAsset() = runTest {
        assets.rows["l1"] = Asset(AssetId("l1"), "Not this", createdAt = 1L, updatedAt = 1L)
        tags.rows[v1Id.value] = row(v1Id.value, PayloadFormat.V1, v1Id.value, TagTarget.LinkTarget(LinkId("l1")))
        val r = resolve.run(TagPayload.V1(v1Id))
        assertIs<Resolution.PreSplitLink>(r)
        assertEquals(v1Id.value, r.tag.payloadKey)
        assertEquals(9_000L, r.tag.lastScannedAt)
        // the scan is still recorded; the row is not rewritten, retargeted or deleted
        assertEquals(TagTarget.LinkTarget(LinkId("l1")), tags.rows[v1Id.value]!!.target)
        assertEquals(1L, tags.rows[v1Id.value]!!.updatedAt)
    }
    @Test fun lostAndRetiredAreRevokedEvenWhenStillTargeted() = runTest {
        assets.rows["a1"] = asset
        tags.rows[v1Id.value] = row(v1Id.value, PayloadFormat.V1, v1Id.value, TagTarget.AssetTarget(AssetId("a1")), TagStatus.LOST)
        assertIs<Resolution.Revoked>(resolve.run(TagPayload.V1(v1Id)))
        tags.rows[v1Id.value] = row(v1Id.value, PayloadFormat.V1, v1Id.value, TagTarget.AssetTarget(AssetId("a1")), TagStatus.RETIRED)
        assertIs<Resolution.Revoked>(resolve.run(TagPayload.V1(v1Id)))
    }
    @Test fun unboundRowIsUnbound() = runTest {
        tags.rows[v1Id.value] = row(v1Id.value, PayloadFormat.V1, v1Id.value, TagTarget.None, TagStatus.UNBOUND)
        assertIs<Resolution.Unbound>(resolve.run(TagPayload.V1(v1Id)))
    }
    @Test fun activeRowWhoseTargetVanishedIsUnbound() = runTest {
        // ON DELETE SET NULL leaves an ACTIVE row with no target; a dangling id is treated the same
        tags.rows[v1Id.value] = row(v1Id.value, PayloadFormat.V1, v1Id.value, TagTarget.None)
        assertIs<Resolution.Unbound>(resolve.run(TagPayload.V1(v1Id)))
        tags.rows[v1Id.value] = row(v1Id.value, PayloadFormat.V1, v1Id.value, TagTarget.AssetTarget(AssetId("gone")))
        assertIs<Resolution.Unbound>(resolve.run(TagPayload.V1(v1Id)))
    }
    @Test fun unknownV1ScanResolvesUnknownV1WithoutTouchingTheStore() = runTest {
        assertEquals(Resolution.UnknownV1(v1Id), resolve.run(TagPayload.V1(v1Id)))
        assertTrue(tags.rows.isEmpty())
    }
    @Test fun lookupIsByPayloadKeyNotByRowId() = runTest {
        // the row id is "t9"; the scan only ever matches on the payload key it carries
        tags.rows["t9"] = row("t9", PayloadFormat.V1, v1Id.value, TagTarget.None)
        assertIs<Resolution.Unbound>(resolve.run(TagPayload.V1(v1Id)))
    }
    @Test fun newerVersionAndForeignContentNeverTouchTheStore() = runTest {
        assertEquals(Resolution.NeedsNewerApp(3), resolve.run(TagPayload.NewerVersion(3)))
        assertEquals(Resolution.NotOurs(TagPayload.Empty), resolve.run(TagPayload.Empty))
        assertEquals(Resolution.NotOurs(TagPayload.Foreign("x")), resolve.run(TagPayload.Foreign("x")))
        assertEquals(Resolution.NotOurs(TagPayload.Malformed("y")), resolve.run(TagPayload.Malformed("y")))
        assertEquals(0, uow.commits)
    }

    // --- #70 C1: the write flow's question asks the same resolver, read-only -------------------

    /** One row under [v1Id], replacing whatever the last fixture left. */
    private fun seed(target: TagTarget, status: TagStatus = TagStatus.ACTIVE) {
        tags.rows.clear()
        tags.rows[v1Id.value] = row(v1Id.value, PayloadFormat.V1, v1Id.value, target, status)
    }

    /** `peek` first, so the row it sees is the one `run` then stamps. */
    private suspend fun peekThenRun(payload: TagPayload): Pair<Resolution, Resolution> =
        resolve.peek(payload) to resolve.run(payload)

    private fun Resolution.kindAndAsset() = this::class to (this as? Resolution.OpenAsset)?.asset

    @Test fun peekClassifiesLikeRun() = runTest {
        assets.rows["a1"] = asset

        seed(TagTarget.AssetTarget(AssetId("a1")))
        peekThenRun(TagPayload.V1(v1Id)).let { (peeked, ran) ->
            assertIs<Resolution.OpenAsset>(peeked)
            assertEquals(asset, peeked.asset)
            assertEquals(ran.kindAndAsset(), peeked.kindAndAsset())
        }

        seed(TagTarget.None, TagStatus.UNBOUND)
        peekThenRun(TagPayload.V1(v1Id)).let { (peeked, ran) ->
            assertIs<Resolution.Unbound>(peeked)
            assertEquals(ran.kindAndAsset(), peeked.kindAndAsset())
        }

        seed(TagTarget.AssetTarget(AssetId("gone")))
        peekThenRun(TagPayload.V1(v1Id)).let { (peeked, ran) ->
            assertIs<Resolution.Unbound>(peeked)
            assertEquals(ran.kindAndAsset(), peeked.kindAndAsset())
        }

        for (status in listOf(TagStatus.LOST, TagStatus.RETIRED)) {
            seed(TagTarget.AssetTarget(AssetId("a1")), status)
            peekThenRun(TagPayload.V1(v1Id)).let { (peeked, ran) ->
                assertIs<Resolution.Revoked>(peeked)
                assertEquals(status, peeked.tag.status)
                assertEquals(ran.kindAndAsset(), peeked.kindAndAsset())
            }
        }

        seed(TagTarget.LinkTarget(LinkId("l1")))
        peekThenRun(TagPayload.V1(v1Id)).let { (peeked, ran) ->
            assertIs<Resolution.PreSplitLink>(peeked)
            assertEquals(ran.kindAndAsset(), peeked.kindAndAsset())
        }

        tags.rows.clear()
        peekThenRun(TagPayload.V1(v1Id)).let { (peeked, ran) ->
            assertEquals(Resolution.UnknownV1(v1Id), peeked)
            assertEquals(ran, peeked)
        }

        peekThenRun(TagPayload.NewerVersion(3)).let { (peeked, ran) ->
            assertEquals(Resolution.NeedsNewerApp(3), peeked)
            assertEquals(ran, peeked)
        }

        peekThenRun(TagPayload.Foreign("x")).let { (peeked, ran) ->
            assertEquals(Resolution.NotOurs(TagPayload.Foreign("x")), peeked)
            assertEquals(ran, peeked)
        }
    }

    /** R70-1: asking what a tag is records no scan — one read transaction, no commit, rows untouched. */
    @Test fun peekRecordsNothing() = runTest {
        assets.rows["a1"] = asset
        seed(TagTarget.AssetTarget(AssetId("a1")))
        val before = LinkedHashMap(tags.rows)
        assertNull(before.getValue(v1Id.value).lastScannedAt)

        val peeked = resolve.peek(TagPayload.V1(v1Id))

        assertIs<Resolution.OpenAsset>(peeked)
        assertNull(peeked.tag.lastScannedAt)
        assertEquals(before, tags.rows)
        assertTrue(uow.reads == 1 && uow.commits == 0, "reads=${uow.reads} commits=${uow.commits}")
    }

    /** The inspect flow is unchanged: a hit still stamps `lastScannedAt` in one commit. */
    @Test fun runStillRecordsTheScan() = runTest {
        seed(TagTarget.None, TagStatus.UNBOUND)
        assertNull(tags.rows.getValue(v1Id.value).lastScannedAt)

        resolve.run(TagPayload.V1(v1Id))

        assertEquals(9_000L, tags.rows.getValue(v1Id.value).lastScannedAt)
        assertEquals(1, uow.commits)
    }

    /**
     * #86 (C14; row 13): after a Replace, a moved tag opens the new asset and a tag left behind opens the old one —
     * through the unchanged classification, with no new resolution.
     */
    @Test fun aMovedTagOpensTheSuccessorAndALeftTagThePredecessor() = runTest {
        val h = ReplaceHarness()
        h.put(h.assetRow(PRED, "Example Water Heater"))
        h.put(h.tagRow("TEST-moved"))
        h.put(h.tagRow("TEST-left"))
        val successor = h.replaceWith(h.draft(movedTagIds = setOf("TEST-moved"))).successor
        val resolveThere = ResolveTag(h.tags, h.assets, h.uow, clock, h.raw.transfers)

        val moved = resolveThere.run(TagPayload.V1(TagId("TEST-moved")))
        assertIs<Resolution.OpenAsset>(moved)
        assertEquals(successor, moved.asset)
        val left = resolveThere.run(TagPayload.V1(TagId("TEST-left")))
        assertIs<Resolution.OpenAsset>(left)
        assertEquals(h.raw.assets.rows.getValue(PRED), left.asset)
    }
}
