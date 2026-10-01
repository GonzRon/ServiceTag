package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.Backup
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.BackupManifest
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetSupply
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.testing.SupplyEstate
import com.loosecannon.servicetag.core.testing.assetSupplyOf
import com.loosecannon.servicetag.core.testing.specificationOf
import com.loosecannon.servicetag.core.testing.supplyItemOf
import com.loosecannon.servicetag.core.testing.transferOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * #15 (C11, C12) — the merge planner's SupplyItems, their applicability and the material lines' links.
 *
 * Same shape as `MergePlannerReferenceTest`: one hand-built archive and one snapshot in, one plan out, nothing
 * written. A SupplyItem is the loan rule — by id, every field compared, no UPDATE — plus `CHILD_ROW_ID_TAKEN` for a
 * specification id; an applicability row is the reference rule's pair arm, mirrored on its triple
 * `(assetId, supplyId, role)`; a line naming a SupplyItem neither here nor inserted by this plan is
 * `OWNER_NOT_AVAILABLE`; and an archive older than format 18 compares a profile or event here that carries a link
 * without the link and without the stamp giving it moved (R67-12 option B). The names are fictional.
 */
class MergePlannerSupplyTest {

    // --- fixtures ---------------------------------------------------------------------------

    private val system = SupplyEstate.system
    private val softener = SupplyEstate.softener
    private val prefilter = SupplyEstate.prefilter
    private val membrane = SupplyEstate.membrane
    private val prefilterOnSystem = SupplyEstate.prefilterOnSystem

    /** The quick action, linked on `pc1` to `s1`. */
    private val quickAction = SupplyEstate.quickAction

    /** The event, with no quick action named, linked on `cu1` to `s1` and on `cu2` to `s2`. */
    private val change = SupplyEstate.change.copy(profileId = null)

    private fun EventProfile.unlinked() = copy(consumables = consumables.map { it.copy(supplyId = null) })
    private fun AssetEvent.unlinked() = copy(consumables = consumables.map { it.copy(supplyId = null) })

    private fun backupOf(
        assets: List<Asset> = listOf(system, softener),
        items: List<SupplyItem> = emptyList(),
        rows: List<AssetSupply> = emptyList(),
        profiles: List<EventProfile> = emptyList(),
        events: List<AssetEvent> = emptyList(),
        formatVersion: Int = BackupCodec.FORMAT_VERSION,
    ) = Backup(
        manifest = BackupManifest(
            formatVersion = formatVersion, appVersion = "1.5.0", schemaVersion = formatVersion, createdAt = 1L,
            counts = emptyMap(), dataSha256 = "a".repeat(64), backupSetId = "set-incoming",
        ),
        // The first three lists by position, as `SupplyEstate.data()` passes them.
        data = BackupData(
            assets.map { it.toDto() }, emptyList(), emptyList(),
            eventProfiles = profiles.map { it.toDto() },
            assetEvents = events.map { it.toDto() },
            supplyItems = items.map { it.toDto() },
            assetSupplies = rows.map { it.toDto() },
        ),
    )

    private fun snapshotOf(
        assets: List<Asset> = listOf(system, softener),
        items: List<SupplyItem> = emptyList(),
        rows: List<AssetSupply> = emptyList(),
        profiles: List<EventProfile> = emptyList(),
        events: List<AssetEvent> = emptyList(),
        transfers: List<TransferRecord> = emptyList(),
    ) = MergeSnapshot(
        assets = assets,
        profiles = profiles,
        events = events,
        supplyItems = items,
        assetSupplies = rows,
        transfers = transfers,
        attachmentStoreConfigured = true,
    )

    private fun MergePlan.decision(table: MergeTable, id: String): MergeDecision =
        decisions.single { it.table == table && it.id == id }

    private fun item(id: String) = MergeDecision(MergeTable.SUPPLY_ITEMS, id, MergeVerdict.INSERT)

    // --- row 15: a SupplyItem by id ---------------------------------------------------------

    /** The ordered DTO: a specification list read in another order is the same item. */
    @Test
    fun theSameItemIsIdentical() {
        val plan = mergePlanOf(
            backupOf(items = listOf(prefilter)),
            snapshotOf(items = listOf(prefilter.copy(specifications = prefilter.specifications.reversed()))),
        )

        assertEquals(MergeDecision(MergeTable.SUPPLY_ITEMS, "s1", MergeVerdict.IDENTICAL), plan.decision(MergeTable.SUPPLY_ITEMS, "s1"))
        assertTrue(plan.applicable)
        assertEquals(emptyList(), plan.writes.supplyItems)
        assertEquals(MergeTally(insert = 0, identical = 1, conflict = 0, skipped = 0), plan.report().supplyItems)
    }

    @Test
    fun aRenamedItemIsAConflict() {
        val plan = mergePlanOf(
            backupOf(items = listOf(prefilter.copy(name = "Example Sediment Cartridge"))),
            snapshotOf(items = listOf(prefilter)),
        )

        assertEquals(
            MergeDecision(MergeTable.SUPPLY_ITEMS, "s1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "s1"),
            plan.decision(MergeTable.SUPPLY_ITEMS, "s1"),
        )
        assertFalse(plan.applicable)
        assertEquals(MergeWrites(), plan.writes, "no partial merge")
    }

    /** No UPDATE (R15-7): one specification's value differing is the whole item's conflict. */
    @Test
    fun aSpecOnlyDifferenceIsAConflict() {
        val edited = prefilter.copy(
            specifications = listOf(prefilter.specifications[0], prefilter.specifications[1].copy(value = "1")),
        )
        val plan = mergePlanOf(backupOf(items = listOf(edited)), snapshotOf(items = listOf(prefilter)))

        assertEquals(
            MergeDecision(MergeTable.SUPPLY_ITEMS, "s1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "s1"),
            plan.decision(MergeTable.SUPPLY_ITEMS, "s1"),
        )
    }

    /** Archived state is a field like any other. */
    @Test
    fun anArchiveOnlyDifferenceIsAConflict() {
        val plan = mergePlanOf(
            backupOf(items = listOf(prefilter.copy(archivedAt = 3_000L))),
            snapshotOf(items = listOf(prefilter)),
        )

        assertEquals(
            MergeDecision(MergeTable.SUPPLY_ITEMS, "s1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "s1"),
            plan.decision(MergeTable.SUPPLY_ITEMS, "s1"),
        )
    }

    /** A new item inserts with its specifications; an archived one too (R15-6), and a row naming each lands. */
    @Test
    fun aNewItemInserts() {
        val membraneOnSoftener = SupplyEstate.membraneOnSoftener
        val plan = mergePlanOf(
            backupOf(items = listOf(prefilter, membrane), rows = listOf(prefilterOnSystem, membraneOnSoftener)),
            snapshotOf(),
        )

        assertEquals(listOf(item("s1"), item("s2")), plan.decisions.filter { it.table == MergeTable.SUPPLY_ITEMS })
        assertTrue(plan.applicable)
        assertEquals(listOf(prefilter, membrane), plan.writes.supplyItems)
        assertEquals(listOf(prefilterOnSystem, membraneOnSoftener), plan.writes.assetSupplies)
        assertEquals(MergeTally(insert = 2, identical = 0, conflict = 0, skipped = 0), plan.report().supplyItems)
        assertEquals(MergeTally(insert = 2, identical = 0, conflict = 0, skipped = 0), plan.report().assetSupplies)
    }

    // --- row 16: a specification's own id ---------------------------------------------------

    @Test
    fun aSpecIdHeldByAnotherItemIsChildRowIdTaken() {
        val other = supplyItemOf("s8", "Example Carbon Block", listOf(specificationOf("sp1", "length", "Length", "20", "in")))
        val plan = mergePlanOf(backupOf(items = listOf(prefilter)), snapshotOf(items = listOf(other)))

        assertEquals(
            MergeDecision(MergeTable.SUPPLY_ITEMS, "s1", MergeVerdict.CONFLICT, MergeReason.CHILD_ROW_ID_TAKEN, "sp1"),
            plan.decision(MergeTable.SUPPLY_ITEMS, "s1"),
        )
        assertEquals(MergeWrites(), plan.writes)
    }

    @Test
    fun aSpecIdClaimedEarlierInThisPlanIsChildRowIdTaken() {
        val second = supplyItemOf("s3", "Example Carbon Block", listOf(specificationOf("sp2", "length", "Length", "20", "in")))
        val plan = mergePlanOf(backupOf(items = listOf(prefilter, second)), snapshotOf())

        assertEquals(item("s1"), plan.decision(MergeTable.SUPPLY_ITEMS, "s1"))
        assertEquals(
            MergeDecision(MergeTable.SUPPLY_ITEMS, "s3", MergeVerdict.CONFLICT, MergeReason.CHILD_ROW_ID_TAKEN, "sp2"),
            plan.decision(MergeTable.SUPPLY_ITEMS, "s3"),
        )
    }

    // --- row 17: an applicability row -------------------------------------------------------

    @Test
    fun anAbsentAssetIsOwnerNotAvailable() {
        val plan = mergePlanOf(
            backupOf(assets = emptyList(), rows = listOf(assetSupplyOf("as9", "x9", "s1"))),
            snapshotOf(items = listOf(prefilter)),
        )

        assertEquals(
            MergeDecision(MergeTable.ASSET_SUPPLIES, "as9", MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, "x9"),
            plan.decision(MergeTable.ASSET_SUPPLIES, "as9"),
        )
    }

    @Test
    fun anAbsentItemIsOwnerNotAvailable() {
        val plan = mergePlanOf(backupOf(rows = listOf(assetSupplyOf("as9", "x1", "s9"))), snapshotOf(items = listOf(prefilter)))

        assertEquals(
            MergeDecision(MergeTable.ASSET_SUPPLIES, "as9", MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, "s9"),
            plan.decision(MergeTable.ASSET_SUPPLIES, "as9"),
        )
    }

    /** Field for field, timestamps included: reachable only from a copied archive, as the reference twin is. */
    @Test
    fun theSameTripleUnderAnotherIdEqualInEveryFieldIsIdenticalWithItsReason() {
        val plan = mergePlanOf(
            backupOf(items = listOf(prefilter), rows = listOf(prefilterOnSystem.copy(id = "as7"))),
            snapshotOf(items = listOf(prefilter), rows = listOf(prefilterOnSystem)),
        )

        assertEquals(
            MergeDecision(
                MergeTable.ASSET_SUPPLIES, "as7", MergeVerdict.IDENTICAL, MergeReason.ASSET_SUPPLY_HELD_BY_AN_EQUIVALENT_LOCAL_ROW, "as1",
            ),
            plan.decision(MergeTable.ASSET_SUPPLIES, "as7"),
        )
        assertTrue(plan.applicable)
        assertEquals(emptyList(), plan.writes.assetSupplies, "nothing written")
    }

    /** The live two-phone case: the local row wins (D-18 C), nothing is written and the archive is not refused. */
    @Test
    fun theSameTripleUnderAnotherIdWithOtherStampsIsSkippedWithItsReason() {
        val plan = mergePlanOf(
            backupOf(items = listOf(prefilter), rows = listOf(prefilterOnSystem.copy(id = "as7", createdAt = 5_000L, updatedAt = 6_000L))),
            snapshotOf(items = listOf(prefilter), rows = listOf(prefilterOnSystem)),
        )

        assertEquals(
            MergeDecision(MergeTable.ASSET_SUPPLIES, "as7", MergeVerdict.SKIPPED, MergeReason.ASSET_SUPPLY_HELD_BY_A_LOCAL_ROW, "as1"),
            plan.decision(MergeTable.ASSET_SUPPLIES, "as7"),
        )
        assertTrue(plan.applicable)
        assertEquals(emptyList(), plan.writes.assetSupplies, "nothing written")
        assertEquals(MergeTally(insert = 0, identical = 0, conflict = 0, skipped = 1), plan.report().assetSupplies)
    }

    @Test
    fun aReRoledRowIsAConflict() {
        val plan = mergePlanOf(
            backupOf(items = listOf(prefilter), rows = listOf(prefilterOnSystem.copy(role = "Sediment prefilter"))),
            snapshotOf(items = listOf(prefilter), rows = listOf(prefilterOnSystem)),
        )

        assertEquals(
            MergeDecision(MergeTable.ASSET_SUPPLIES, "as1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "as1"),
            plan.decision(MergeTable.ASSET_SUPPLIES, "as1"),
        )
    }

    /** M2: an applicability row is asset-owned; the SupplyItem beside it is global and lands. */
    @Test
    fun aHeldAssetsApplicabilityIsRefusedAndTheItemIsNot() {
        val plan = mergePlanOf(
            backupOf(items = listOf(prefilter), rows = listOf(prefilterOnSystem)),
            snapshotOf(transfers = listOf(transferOf("r1", assetId = "x1"))),
        )

        assertEquals(item("s1"), plan.decision(MergeTable.SUPPLY_ITEMS, "s1"))
        assertEquals(
            MergeDecision(MergeTable.ASSET_SUPPLIES, "as1", MergeVerdict.CONFLICT, MergeReason.ASSET_TRANSFERRED_OUT, "x1"),
            plan.decision(MergeTable.ASSET_SUPPLIES, "as1"),
        )
    }

    // --- row 18: a material line's link -------------------------------------------------------

    @Test
    fun aProfileLineNamingAnUnknownItemIsOwnerNotAvailable() {
        val plan = mergePlanOf(backupOf(profiles = listOf(quickAction)), snapshotOf())

        assertEquals(
            MergeDecision(MergeTable.PROFILES, "p1", MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, "s1"),
            plan.decision(MergeTable.PROFILES, "p1"),
        )
    }

    @Test
    fun anEventLineNamingAnUnknownItemIsOwnerNotAvailable() {
        val plan = mergePlanOf(backupOf(events = listOf(change)), snapshotOf(items = listOf(prefilter)))

        assertEquals(
            MergeDecision(MergeTable.EVENTS, "e1", MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, "s2"),
            plan.decision(MergeTable.EVENTS, "e1"),
        )
    }

    /** From a well-formed file the arm fires only behind a refused new item: a second conflict, never a dangling link. */
    @Test
    fun aLineNamingANewItemRefusedForChildRowIdTakenIsASecondConflict() {
        val other = supplyItemOf("s8", "Example Carbon Block", listOf(specificationOf("sp1", "length", "Length", "20", "in")))
        val plan = mergePlanOf(
            backupOf(items = listOf(prefilter), profiles = listOf(quickAction)),
            snapshotOf(items = listOf(other)),
        )

        assertEquals(
            listOf(
                MergeDecision(MergeTable.PROFILES, "p1", MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, "s1"),
                MergeDecision(MergeTable.SUPPLY_ITEMS, "s1", MergeVerdict.CONFLICT, MergeReason.CHILD_ROW_ID_TAKEN, "sp1"),
            ),
            plan.conflicts,
        )
    }

    // --- row 19: the planning position (C-3) --------------------------------------------------

    /** The SupplyItem section is planned before the profiles: a new item and the line naming it land together. */
    @Test
    fun aProfileLineNamingAnItemThisPlanInsertsInserts() {
        val plan = mergePlanOf(backupOf(items = listOf(prefilter), profiles = listOf(quickAction)), snapshotOf())

        assertEquals(MergeDecision(MergeTable.PROFILES, "p1", MergeVerdict.INSERT), plan.decision(MergeTable.PROFILES, "p1"))
        assertTrue(plan.applicable, "unexpected conflicts: ${plan.conflicts}")
        assertEquals(listOf(quickAction), plan.writes.profiles)
    }

    @Test
    fun anEventLineNamingAnItemThisPlanInsertsInserts() {
        val plan = mergePlanOf(backupOf(items = listOf(prefilter, membrane), events = listOf(change)), snapshotOf())

        assertEquals(MergeDecision(MergeTable.EVENTS, "e1", MergeVerdict.INSERT), plan.decision(MergeTable.EVENTS, "e1"))
        assertTrue(plan.applicable, "unexpected conflicts: ${plan.conflicts}")
        assertEquals(listOf(change), plan.writes.events)
    }

    // --- rows 22–25: the older-archive exception (C12) ----------------------------------------

    /** A link given here since that export moved the stamp; the format-17 archive cannot speak about links. */
    @Test
    fun `a format-17 archive against a profile later linked is identical`() {
        val plan = mergePlanOf(
            backupOf(profiles = listOf(quickAction.unlinked()), formatVersion = 17),
            snapshotOf(items = listOf(prefilter), profiles = listOf(quickAction.copy(updatedAt = 5_000L))),
        )

        assertEquals(MergeDecision(MergeTable.PROFILES, "p1", MergeVerdict.IDENTICAL), plan.decision(MergeTable.PROFILES, "p1"))
    }

    @Test
    fun `a format-17 archive against an event later linked is identical`() {
        val plan = mergePlanOf(
            backupOf(events = listOf(change.unlinked()), formatVersion = 17),
            snapshotOf(items = listOf(prefilter, membrane), events = listOf(change.copy(updatedAt = 5_000L))),
        )

        assertEquals(MergeDecision(MergeTable.EVENTS, "e1", MergeVerdict.IDENTICAL), plan.decision(MergeTable.EVENTS, "e1"))
    }

    @Test
    fun `a format-17 archive against a linked profile also renamed is a conflict`() {
        val plan = mergePlanOf(
            backupOf(profiles = listOf(quickAction.unlinked()), formatVersion = 17),
            snapshotOf(
                items = listOf(prefilter),
                profiles = listOf(quickAction.copy(name = "Example prefilter swap", updatedAt = 5_000L)),
            ),
        )

        assertEquals(
            MergeDecision(MergeTable.PROFILES, "p1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "p1"),
            plan.decision(MergeTable.PROFILES, "p1"),
        )
    }

    @Test
    fun `a format-17 archive against a linked event also retitled is a conflict`() {
        val plan = mergePlanOf(
            backupOf(events = listOf(change.unlinked()), formatVersion = 17),
            snapshotOf(
                items = listOf(prefilter, membrane),
                events = listOf(change.copy(title = "Prefilter and membrane change", updatedAt = 5_000L)),
            ),
        )

        assertEquals(
            MergeDecision(MergeTable.EVENTS, "e1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "e1"),
            plan.decision(MergeTable.EVENTS, "e1"),
        )
    }

    /** Limit 3: no link here any more, so the stamp the give-and-remove moved compares as always. */
    @Test
    fun `a format-17 archive against a profile linked then unlinked is a conflict`() {
        val plan = mergePlanOf(
            backupOf(profiles = listOf(quickAction.unlinked()), formatVersion = 17),
            snapshotOf(profiles = listOf(quickAction.unlinked().copy(updatedAt = 5_000L))),
        )

        assertEquals(
            MergeDecision(MergeTable.PROFILES, "p1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "p1"),
            plan.decision(MergeTable.PROFILES, "p1"),
        )
    }

    /** A format-18 archive compares the link like any field — even with the stamps equal. */
    @Test
    fun `a format-18 line linked here and unlinked there is a conflict`() {
        val plan = mergePlanOf(
            backupOf(profiles = listOf(quickAction.unlinked()), events = listOf(change.unlinked())),
            snapshotOf(items = listOf(prefilter, membrane), profiles = listOf(quickAction), events = listOf(change)),
        )

        assertEquals(
            MergeDecision(MergeTable.PROFILES, "p1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "p1"),
            plan.decision(MergeTable.PROFILES, "p1"),
        )
        assertEquals(
            MergeDecision(MergeTable.EVENTS, "e1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "e1"),
            plan.decision(MergeTable.EVENTS, "e1"),
        )
    }

    @Test
    fun `the same link is identical`() {
        val plan = mergePlanOf(
            backupOf(items = listOf(prefilter, membrane), profiles = listOf(quickAction), events = listOf(change)),
            snapshotOf(items = listOf(prefilter, membrane), profiles = listOf(quickAction), events = listOf(change)),
        )

        assertEquals(MergeDecision(MergeTable.PROFILES, "p1", MergeVerdict.IDENTICAL), plan.decision(MergeTable.PROFILES, "p1"))
        assertEquals(MergeDecision(MergeTable.EVENTS, "e1", MergeVerdict.IDENTICAL), plan.decision(MergeTable.EVENTS, "e1"))
        assertTrue(plan.applicable)
    }
}
