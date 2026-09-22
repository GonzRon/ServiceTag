package com.loosecannon.servicetag.ui.scan

import com.loosecannon.servicetag.core.merge.MergeTally
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.nfc.TagPayload
import com.loosecannon.servicetag.core.usecase.BindTag
import com.loosecannon.servicetag.core.usecase.Resolution
import com.loosecannon.servicetag.core.usecase.ResolveTag
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.ui.asset.AssetDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertIs

/**
 * B11, #49 — the placement label as a reused, already-shipped field: `nfc_tag.label` becomes the
 * human-readable "Tag placement" value with no schema change, no second column, and no touch to
 * core, `app/.../data` or `app/.../api` (proved separately by the review gate's four structural
 * checks, not by a JUnit test here — see the brief's Review gate section).
 *
 * One test per hazard class (the proportionality ruling): each method's name is the hazard, and
 * its body is the mechanism that fails without the change under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TagPlacementTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private fun bindTag() = BindTag(graph.tags, graph.assets, graph.uow, graph.clock)
    private fun resolveTag() = ResolveTag(graph.tags, graph.assets, graph.uow, graph.clock)

    private fun detailModel(id: AssetId) = AssetDetailViewModel(
        graph.assets, graph.tags,
        graph.definitions, graph.profiles, graph.events,
        graph.archiveAsset, graph.retireAsset, graph.deleteAsset,
        graph.applyTemplate, graph.clock, id,
    )

    // --- 1: two tags, one asset ------------------------------------------------------------

    /**
     * The shipped schema really does allow it (#49 AC 1, AC 2): a unique index or a lookup keyed
     * on `asset_id` would make the second binding impossible, and this fails loudly if it were.
     */
    @Test fun twoActiveTagsOnOneAssetBothResolveToTheSameAssetWithDifferentLabels() = runTest {
        val asset = graph.createAsset.run("Pool heater", "Water")
        val bind = bindTag()
        bind.run(PayloadFormat.V1, TAG_A, TagTarget.AssetTarget(asset.id), "Indoor head")
        bind.run(PayloadFormat.V1, TAG_B, TagTarget.AssetTarget(asset.id), "Outdoor head")

        val resolve = resolveTag()
        val resultA = resolve.run(TagPayload.V1(TagId(TAG_A)))
        val resultB = resolve.run(TagPayload.V1(TagId(TAG_B)))

        assertIs<Resolution.OpenAsset>(resultA)
        assertIs<Resolution.OpenAsset>(resultB)
        assertEquals(asset.id, resultA.asset.id)
        assertEquals(asset.id, resultB.asset.id)
        assertEquals(
            setOf("Indoor head", "Outdoor head"),
            graph.tags.forAsset(asset.id).map { it.label }.toSet(),
        )
    }

    // --- 2: the scan point unidentifiable --------------------------------------------------

    /**
     * #49 AC 3: shown when set, and blank or whitespace-only both count as unset — the same
     * predicate [TagResultSheet] and the asset detail's tags section both call.
     */
    @Test fun thePlacementIsShownOnlyWhenReallySet() {
        assertEquals("Garage bay 2", tagWithLabel("Garage bay 2").placementOrNull())
        assertNull(tagWithLabel(null).placementOrNull())
        assertNull(tagWithLabel("").placementOrNull())
        assertNull(tagWithLabel("   ").placementOrNull())
    }

    // --- 3: editing rewriting the tag -------------------------------------------------------

    /**
     * Invariant 59, #49 AC 4: the edit changes `label` and `updated_at` only — id, payload
     * identity, target, status and `physical_uid` are byte-identical before and after.
     */
    @Test fun editingALabelChangesOnlyLabelAndUpdatedAt() = runTest {
        val asset = graph.createAsset.run("Water softener", "Water")
        graph.now = 1_000L
        bindTag().run(PayloadFormat.V1, TAG_A, TagTarget.AssetTarget(asset.id), "Utility room")
        val before = graph.tags.get(TagId(TAG_A))!!

        graph.now = 2_000L
        val model = detailModel(asset.id)
        // `editTagLabel` is fire-and-forget on `viewModelScope`, exactly like this screen's other
        // mutators (`archive`, `retire`, …) — the test waits for the observed state to catch up
        // rather than reading the repository straight after the call.
        model.editTagLabel(TagId(TAG_A), "Basement, north wall")
        model.state.first { it != null && it.tags.singleOrNull()?.label == "Basement, north wall" }

        val after = graph.tags.get(TagId(TAG_A))!!
        assertEquals("Basement, north wall", after.label)
        assertEquals(2_000L, after.updatedAt)
        assertEquals(before.id, after.id)
        assertEquals(before.payloadFormat, after.payloadFormat)
        assertEquals(before.payloadKey, after.payloadKey)
        assertEquals(before.target, after.target)
        assertEquals(before.status, after.status)
        assertEquals(before.physicalUid, after.physicalUid)
        assertEquals(before.writtenAt, after.writtenAt)
        assertEquals(before.lastScannedAt, after.lastScannedAt)
        assertEquals(before.createdAt, after.createdAt)
    }

    // --- 4: only the first tag shown ---------------------------------------------------------

    /**
     * #49 AC 6: the detail state lists every tag — an active one, a lost one and a retired one —
     * not only the first, each with its own label and status.
     */
    @Test fun theDetailStateListsEveryTagIncludingLostAndRetired() = runTest {
        val asset = graph.createAsset.run("Sprinkler system", "Water")
        bindTag().run(PayloadFormat.V1, TAG_A, TagTarget.AssetTarget(asset.id), "Zone 1")
        bindTag().run(PayloadFormat.V1, TAG_B, TagTarget.AssetTarget(asset.id), "Zone 2")
        bindTag().run(PayloadFormat.V1, TAG_C, TagTarget.AssetTarget(asset.id), "Zone 3 (spare)")
        graph.tags.upsert(graph.tags.get(TagId(TAG_B))!!.copy(status = TagStatus.LOST))
        graph.tags.upsert(graph.tags.get(TagId(TAG_C))!!.copy(status = TagStatus.RETIRED))

        val state = detailModel(asset.id).state.first { it != null && it.tags.size == 3 }!!

        assertEquals(setOf(TAG_A, TAG_B, TAG_C), state.tags.map { it.id.value }.toSet())
        assertEquals(
            mapOf(TAG_A to TagStatus.ACTIVE, TAG_B to TagStatus.LOST, TAG_C to TagStatus.RETIRED),
            state.tags.associate { it.id.value to it.status },
        )
        assertEquals(
            setOf("Zone 1", "Zone 2", "Zone 3 (spare)"),
            state.tags.map { it.label }.toSet(),
        )
    }

    // --- 5: history lost on retirement --------------------------------------------------------

    /** A lost, a retired and a rebound tag each keep their historical label. */
    @Test fun lostRetiredAndReboundTagsKeepTheirHistoricalLabel() = runTest {
        val asset = graph.createAsset.run("Generator", "Power")
        val bind = bindTag()
        bind.run(PayloadFormat.V1, TAG_A, TagTarget.AssetTarget(asset.id), "Shed, east wall")
        bind.run(PayloadFormat.V1, TAG_B, TagTarget.AssetTarget(asset.id), "Shed, west wall")

        graph.tags.upsert(graph.tags.get(TagId(TAG_A))!!.copy(status = TagStatus.LOST))
        graph.tags.upsert(graph.tags.get(TagId(TAG_B))!!.copy(status = TagStatus.RETIRED))
        assertEquals("Shed, east wall", graph.tags.get(TagId(TAG_A))!!.label)
        assertEquals("Shed, west wall", graph.tags.get(TagId(TAG_B))!!.label)

        // "Bind to asset" on a revoked tag (TagResultSheet's own flow) passes no label of its
        // own — BindTag.run's `label ?: existing.label` is what carries the history forward.
        val rebound = bind.run(PayloadFormat.V1, TAG_A, TagTarget.AssetTarget(asset.id), label = null)

        assertEquals(TagStatus.ACTIVE, rebound.status)
        assertEquals("Shed, east wall", rebound.label)
    }

    // --- 6: a blank label rejected -------------------------------------------------------------

    /** Binding with no placement succeeds; the row's label is blank/null, never refused. */
    @Test fun bindingWithNoPlacementLeavesTheLabelBlank() = runTest {
        val asset = graph.createAsset.run("Water heater", "Water")

        val bound = bindTag().run(PayloadFormat.V1, TAG_A, TagTarget.AssetTarget(asset.id), label = null)

        assertNull(bound.label)
        assertNull(bound.placementOrNull())
    }

    // --- 7: the label lost in transit -----------------------------------------------------------

    /**
     * #49 AC 5, the composed proof: the label survives export, restore (import-replace into a
     * brand-new phone) and merge byte-identically, and re-planning the same archive against the
     * phone it already merged into is all `IDENTICAL` — the test that proves #49 needed no
     * migration after all.
     */
    @Test fun theLabelRoundTripsThroughExportRestoreAndMergeAndAReimportPlansIdentical() = runTest {
        // `donor` and `restored` are plain data fixtures, never touched by a ViewModel's fire-
        // and-forget launch, so each takes `FakeGraph`'s default query context (`Dispatchers.
        // Default`) rather than the shared virtual scheduler `graph` needs for the ViewModel
        // tests above (`ApiRouterTest`'s `donorArchive` states the same reasoning).
        val donor = FakeGraph()
        val archive = try {
            // This test's destination `graph` is still empty at this point, so the donor's
            // default id sequence cannot collide with it — no disjoint generator needed here
            // (contrast `ApiRouterTest.donorArchive`, whose destination already has rows).
            val donorAsset = donor.createAsset.run("Pool pump", "Water")
            BindTag(donor.tags, donor.assets, donor.uow, donor.clock)
                .run(PayloadFormat.V1, TAG_A, TagTarget.AssetTarget(donorAsset.id), "Pump house")
            donor.exportBackupSet.run().data
        } finally {
            donor.close()
        }

        // Restore: a brand-new empty phone, wiped and loaded — the label survives whole.
        val restored = FakeGraph()
        try {
            restored.importBackupReplace.run(archive)
            val restoredTag = restored.tags.all().single()
            assertEquals("Pump house", restoredTag.label)
        } finally {
            restored.close()
        }

        // Merge into this test's (empty) destination phone.
        val merged = graph.importBackupMerge.run(archive)
        assertEquals(MergeTally(insert = 1, identical = 0, conflict = 0, skipped = 0), merged.tags)
        val mergedTag = graph.tags.all().single { it.payloadKey == TAG_A }
        assertEquals("Pump house", mergedTag.label)

        // Re-planning the same archive against the phone it already merged into: all IDENTICAL.
        val replanned = graph.importBackupMerge.plan(archive)
        assertEquals(MergeTally(insert = 0, identical = 1, conflict = 0, skipped = 0), replanned.tags)
        assertTrue(replanned.conflicts.isEmpty())
    }

    // --- 8: a scan mutating -----------------------------------------------------------------

    /**
     * Invariant 57: a scan writes nothing but the shipped informational `lastScannedAt`. The
     * label, and every other column, is byte-identical before and after.
     */
    @Test fun aScanChangesOnlyLastScannedAtAndNeverTheLabel() = runTest {
        val asset = graph.createAsset.run("Dehumidifier", "Climate")
        graph.now = 5_000L
        bindTag().run(PayloadFormat.V1, TAG_A, TagTarget.AssetTarget(asset.id), "Basement, south corner")
        val before = graph.tags.get(TagId(TAG_A))!!
        assertNull(before.lastScannedAt)

        graph.now = 6_000L
        val resolved = resolveTag().run(TagPayload.V1(TagId(TAG_A)))

        assertIs<Resolution.OpenAsset>(resolved)
        val after = graph.tags.get(TagId(TAG_A))!!
        assertEquals(6_000L, after.lastScannedAt)
        assertEquals("Basement, south corner", after.label)
        assertEquals(before.label, after.label)
        assertEquals(before.updatedAt, after.updatedAt)
        assertEquals(before.status, after.status)
        assertEquals(before.target, after.target)
    }

    // --- 9: a second tag becoming a second asset ---------------------------------------------

    /**
     * #49 "Do not overload Asset hierarchy": binding a second tag to one Asset creates no child
     * Asset row. (This codebase carries no schedule concept yet — B02/B14 land it in a later
     * wave — so the "no second schedule" half of this hazard has nothing to assert against here;
     * this is a base-commit fact, not a gap in this brief, and is called out in the report.)
     */
    @Test fun aSecondTagOnOneAssetCreatesNoChildAsset() = runTest {
        val asset = graph.createAsset.run("Water main shutoff", "Water")
        val bind = bindTag()
        bind.run(PayloadFormat.V1, TAG_A, TagTarget.AssetTarget(asset.id), "Front valve")
        bind.run(PayloadFormat.V1, TAG_B, TagTarget.AssetTarget(asset.id), "Back valve")

        val all = graph.assets.all()
        assertEquals(1, all.size)
        assertEquals(asset.id, all.single().id)
        assertEquals(2, graph.tags.forAsset(asset.id).size)
    }

    private fun tagWithLabel(label: String?): TagBinding = TagBinding(
        id = TagId(TAG_A),
        payloadFormat = PayloadFormat.V1,
        payloadKey = TAG_A,
        label = label,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private companion object {
        const val TAG_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val TAG_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val TAG_C = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    }
}
