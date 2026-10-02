package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.backupOf
import com.loosecannon.servicetag.core.testing.compositionEntryOf
import com.loosecannon.servicetag.core.testing.installedComponentOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.supplyItemOf
import com.loosecannon.servicetag.core.testing.transferOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * #47 (C12) — the merge planner's installed components.
 *
 * Same shape as `MergePlannerSupplyTest`: a hand-built archive and a snapshot in, one plan out, nothing written —
 * except where a case says the plan goes through `BuildBackupMergePlan` on [BackupInstall], whose snapshot is read
 * from the core doubles, so a snapshot that forgot the installed components fails there for that reason (N-5). A row
 * is decided by id, every field and its ordered composition compared, no UPDATE; parents first; then the entry ids,
 * the owners (asset, SupplyItems, parent) and the replaced row. The fixtures are fictional: an Example UPS with a
 * battery tray of four positions naming one SupplyItem, and a battery pack made of `4 ×` that SupplyItem.
 */
class MergePlannerInstalledComponentTest {

    // --- fixtures ---------------------------------------------------------------------------

    private val ups = plainAssetOf("x1", "Example UPS")
    private val battery = supplyItemOf("s1", "Example 12 V Battery")
    private val packSku = supplyItemOf("s2", "Example Battery Pack")
    private val strap = supplyItemOf("s3", "Example Battery Strap")
    private val items = listOf(battery, packSku, strap)

    private val tray = installedComponentOf("ic-tray", name = "Example Battery Tray", installedOn = "2026-01-10")

    private fun position(n: Int, id: String = "ic-pos$n", removedOn: String? = null, replacesId: String? = null) =
        installedComponentOf(
            id, name = "Position $n", parentId = "ic-tray", supplyId = "s1", installedOn = "2026-01-10",
            removedOn = removedOn, replacesId = replacesId, sortOrder = n,
        )

    /** The pack: this unit is the pack SKU, and it is made of four batteries and one strap. */
    private val pack = installedComponentOf(
        "ic-pack", name = "Example Battery Pack", supplyId = "s2", installedOn = "2026-02-01",
        composition = listOf(
            compositionEntryOf("ce1", "s1", quantity = 4.0, unit = "ea", sortOrder = 0),
            compositionEntryOf("ce2", "s3", quantity = 1.0, unit = "", sortOrder = 1),
        ),
    )

    /** The first three lists by position, as `MergePlannerSupplyTest` passes them. */
    private fun archive(
        rows: List<InstalledComponent>,
        assets: List<Asset> = listOf(ups),
        supplyItems: List<SupplyItem> = items,
    ) = backupOf(
        BackupData(
            assets.map { it.toDto() }, emptyList(), emptyList(),
            supplyItems = supplyItems.map { it.toDto() },
            installedComponents = rows.map { it.toDto() },
        ),
    )

    private fun snapshot(
        rows: List<InstalledComponent> = emptyList(),
        assets: List<Asset> = listOf(ups),
        supplyItems: List<SupplyItem> = items,
        transfers: List<TransferRecord> = emptyList(),
    ) = MergeSnapshot(
        assets = assets,
        supplyItems = supplyItems,
        installedComponents = rows,
        transfers = transfers,
        attachmentStoreConfigured = true,
    )

    private fun MergePlan.decision(id: String): MergeDecision =
        decisions.single { it.table == MergeTable.INSTALLED_COMPONENTS && it.id == id }

    private fun inserted(id: String) = MergeDecision(MergeTable.INSTALLED_COMPONENTS, id, MergeVerdict.INSERT)

    private fun conflict(id: String, reason: MergeReason, detail: String) =
        MergeDecision(MergeTable.INSTALLED_COMPONENTS, id, MergeVerdict.CONFLICT, reason, detail)

    // --- row 19: by id ----------------------------------------------------------------------

    /** The ordered composition: entries read in another order are the same row. */
    @Test
    fun theSameRowIsIdentical() {
        val plan = mergePlanOf(
            archive(listOf(pack)),
            snapshot(listOf(pack.copy(composition = pack.composition.reversed()))),
        )

        assertEquals(MergeDecision(MergeTable.INSTALLED_COMPONENTS, "ic-pack", MergeVerdict.IDENTICAL), plan.decision("ic-pack"))
        assertTrue(plan.applicable)
        assertEquals(emptyList(), plan.writes.installedComponents)
        assertEquals(MergeTally(insert = 0, identical = 1, conflict = 0, skipped = 0), plan.report().installedComponents)
    }

    @Test
    fun aRowRemovedHereAfterTheExportIsAConflict() {
        val plan = mergePlanOf(
            archive(listOf(tray, position(2))),
            snapshot(listOf(tray, position(2, removedOn = "2026-06-01"))),
        )

        assertEquals(conflict("ic-pos2", MergeReason.CONTENT_DIFFERS, "ic-pos2"), plan.decision("ic-pos2"))
        assertFalse(plan.applicable)
        assertEquals(MergeWrites(), plan.writes)
    }

    /** A different quantity on one entry, every other field equal: no UPDATE, so a conflict. */
    @Test
    fun aCompositionOnlyDifferenceIsAConflict() {
        val recomposed = pack.copy(composition = listOf(pack.composition[0].copy(quantity = 3.0), pack.composition[1]))
        val plan = mergePlanOf(archive(listOf(pack)), snapshot(listOf(recomposed)))

        assertEquals(conflict("ic-pack", MergeReason.CONTENT_DIFFERS, "ic-pack"), plan.decision("ic-pack"))
        assertEquals(MergeTally(insert = 0, identical = 0, conflict = 1, skipped = 0), plan.report().installedComponents)
    }

    @Test
    fun aNewRowInserts() {
        val plan = mergePlanOf(archive(listOf(pack)), snapshot())

        assertEquals(inserted("ic-pack"), plan.decision("ic-pack"))
        assertTrue(plan.applicable)
        assertEquals(listOf(pack), plan.writes.installedComponents)
        assertEquals(MergeTally(insert = 1, identical = 0, conflict = 0, skipped = 0), plan.report().installedComponents)
        // Row 26: the table is the twenty-third, last, and its tally is the report's last.
        assertEquals(MergeTable.INSTALLED_COMPONENTS, MergeTable.entries.last())
    }

    // --- row 20: parents first --------------------------------------------------------------

    /** In file order, a position would meet its tray undecided; parents first, every row lands, the tray first. */
    @Test
    fun aShuffledNewTreeInsertsParentsFirst() {
        val plan = mergePlanOf(
            archive(listOf(position(3), position(1), tray, position(4), position(2))),
            snapshot(),
        )

        assertEquals(listOf("ic-pos1", "ic-pos2", "ic-pos3", "ic-pos4", "ic-tray").map(::inserted), plan.of())
        assertEquals(listOf(tray, position(1), position(2), position(3), position(4)), plan.writes.installedComponents)
    }

    /** The parent is new and accepted earlier in this pass, though the file lists the child first. */
    @Test
    fun aChildOfAnAcceptedParentInserts() {
        val plan = mergePlanOf(archive(listOf(position(1), tray)), snapshot())

        assertEquals(inserted("ic-pos1"), plan.decision("ic-pos1"))
        assertEquals(listOf(tray, position(1)), plan.writes.installedComponents)
    }

    @Test
    fun aChildOfALocalParentInserts() {
        val plan = mergePlanOf(archive(listOf(tray, position(1))), snapshot(listOf(tray)))

        assertEquals(MergeDecision(MergeTable.INSTALLED_COMPONENTS, "ic-tray", MergeVerdict.IDENTICAL), plan.decision("ic-tray"))
        assertEquals(inserted("ic-pos1"), plan.decision("ic-pos1"))
        assertEquals(listOf(position(1)), plan.writes.installedComponents)
    }

    /** A new parent this plan refuses is neither here nor accepted: its child is refused naming it. */
    @Test
    fun aChildOfARefusedParentIsOwnerNotAvailableNamingTheParent() {
        val heldEntry =
            installedComponentOf("ic-other", name = "Example Spare Pack", composition = listOf(compositionEntryOf("ce7", "s1")))
        val trayWithEntry = tray.copy(composition = listOf(compositionEntryOf("ce7", "s3")))
        val plan = mergePlanOf(archive(listOf(trayWithEntry, position(1))), snapshot(listOf(heldEntry)))

        assertEquals(conflict("ic-tray", MergeReason.CHILD_ROW_ID_TAKEN, "ce7"), plan.decision("ic-tray"))
        assertEquals(conflict("ic-pos1", MergeReason.OWNER_NOT_AVAILABLE, "ic-tray"), plan.decision("ic-pos1"))
    }

    /** Reachable only from a hand-built archive: the codec holds every parent in the file. */
    @Test
    fun anOrphanIsOwnerNotAvailable() {
        val orphan = position(1).copy(parentId = InstalledComponentId("ic-gone"))
        val plan = mergePlanOf(archive(listOf(orphan)), snapshot())

        assertEquals(conflict("ic-pos1", MergeReason.OWNER_NOT_AVAILABLE, "ic-gone"), plan.decision("ic-pos1"))
    }

    // --- row 21: owners and child ids -------------------------------------------------------

    @Test
    fun anAbsentAssetIsOwnerNotAvailable() {
        val plan = mergePlanOf(archive(listOf(pack.copy(assetId = AssetId("x9")))), snapshot())

        assertEquals(conflict("ic-pack", MergeReason.OWNER_NOT_AVAILABLE, "x9"), plan.decision("ic-pack"))
    }

    @Test
    fun anAbsentDirectSupplyItemIsOwnerNotAvailable() {
        val plan = mergePlanOf(
            archive(listOf(pack), supplyItems = listOf(battery, strap)),
            snapshot(supplyItems = listOf(battery, strap)),
        )

        assertEquals(conflict("ic-pack", MergeReason.OWNER_NOT_AVAILABLE, "s2"), plan.decision("ic-pack"))
    }

    /** The direct link resolves; the second entry names a SupplyItem neither here nor in the archive. */
    @Test
    fun anAbsentEntrySupplyItemIsOwnerNotAvailable() {
        val plan = mergePlanOf(
            archive(listOf(pack), supplyItems = listOf(battery, packSku)),
            snapshot(supplyItems = listOf(battery, packSku)),
        )

        assertEquals(conflict("ic-pack", MergeReason.OWNER_NOT_AVAILABLE, "s3"), plan.decision("ic-pack"))
        assertFalse(plan.applicable)
    }

    /** No SupplyItem here: the archive's are inserted by this plan, so the pack's link and entries resolve. */
    @Test
    fun aSupplyItemThisPlanInsertsResolves() {
        val plan = mergePlanOf(archive(listOf(pack)), snapshot(supplyItems = emptyList()))

        assertEquals(inserted("ic-pack"), plan.decision("ic-pack"))
        assertTrue(plan.applicable)
        assertEquals(items, plan.writes.supplyItems)
        assertEquals(listOf(pack), plan.writes.installedComponents)
    }

    @Test
    fun anEntryIdHeldByAnotherRowIsChildRowIdTaken() {
        val spare = installedComponentOf("ic-spare", name = "Example Spare Pack", composition = listOf(compositionEntryOf("ce2", "s1")))
        val plan = mergePlanOf(archive(listOf(pack)), snapshot(listOf(spare)))

        assertEquals(conflict("ic-pack", MergeReason.CHILD_ROW_ID_TAKEN, "ce2"), plan.decision("ic-pack"))
    }

    /** Reachable only from a hand-built archive: the codec holds every entry id once per file. */
    @Test
    fun anEntryIdClaimedEarlierInThisPassIsChildRowIdTaken() {
        val second = installedComponentOf("ic-pack2", name = "Example Second Pack", composition = listOf(compositionEntryOf("ce1", "s1")))
        val plan = mergePlanOf(archive(listOf(second, pack)), snapshot())

        assertEquals(inserted("ic-pack"), plan.decision("ic-pack"))
        assertEquals(conflict("ic-pack2", MergeReason.CHILD_ROW_ID_TAKEN, "ce1"), plan.decision("ic-pack2"))
    }

    // --- row 22: the replaced row ------------------------------------------------------------

    /** Position 2 was replaced here by one row and in the archive by another: the unique index would refuse it. */
    @Test
    fun aReplacesIdHeldHereIsReplacementTaken() {
        val replacedHere = position(2, id = "ic-pos2b", replacesId = "ic-pos2")
        val replacedThere = position(2, id = "ic-pos2c", replacesId = "ic-pos2")
        val removed = position(2, removedOn = "2026-06-01")
        val plan = mergePlanOf(
            archive(listOf(tray, removed, replacedThere)),
            snapshot(listOf(tray, removed, replacedHere)),
        )

        assertEquals(
            conflict("ic-pos2c", MergeReason.INSTALLED_COMPONENT_REPLACEMENT_TAKEN, "ic-pos2b"),
            plan.decision("ic-pos2c"),
        )
        assertFalse(plan.applicable)
    }

    /** Reachable only from a hand-built archive: the codec holds a `replacesId` on one row per file. */
    @Test
    fun aReplacesIdHeldEarlierInThisPassIsReplacementTaken() {
        val removed = position(2, removedOn = "2026-06-01")
        val plan = mergePlanOf(
            archive(
                listOf(
                    position(2, id = "ic-pos2c", replacesId = "ic-pos2"), tray, removed,
                    position(2, id = "ic-pos2b", replacesId = "ic-pos2"),
                ),
            ),
            snapshot(listOf(tray, removed)),
        )

        assertEquals(inserted("ic-pos2b"), plan.decision("ic-pos2b"))
        assertEquals(
            conflict("ic-pos2c", MergeReason.INSTALLED_COMPONENT_REPLACEMENT_TAKEN, "ic-pos2b"),
            plan.decision("ic-pos2c"),
        )
    }

    // --- row 23: an older archive names none ------------------------------------------------

    /**
     * A format-18 archive names no installed component, so the plan decides none of this install's and every other
     * row compares as before: through `BuildBackupMergePlan`, against an install holding a tree and a pack.
     */
    @Test
    fun aFormat18ExportAgainstAnInstallWithComponentsIsApplicableAndIdentical() = runBlocking<Unit> {
        val phone = installWithTheUpsShapes()
        val bytes = archiveOf(
            BackupData(listOf(ups.toDto()), emptyList(), emptyList(), supplyItems = items.map { it.toDto() }),
            formatVersion = 18,
        )

        val plan = phone.build.run(bytes)

        assertTrue(plan.applicable, "${plan.conflicts}")
        assertTrue(plan.decisions.all { it.verdict == MergeVerdict.IDENTICAL }, "${plan.decisions}")
        assertEquals(4, plan.decisions.size)
        assertEquals(MergeTally(insert = 0, identical = 0, conflict = 0, skipped = 0), plan.report().installedComponents)
        assertEquals(MergeWrites(), plan.writes)
    }

    // --- N-5: the snapshot reads the port ---------------------------------------------------

    /** An unedited export, re-planned through `BuildBackupMergePlan` on the install that made it: IDENTICAL, not INSERT. */
    @Test
    fun reAPlanningAnUneditedExportOnANonEmptyInstallIsIdentical() = runBlocking<Unit> {
        val phone = installWithTheUpsShapes()

        val plan = phone.build.run(phone.export.run().data)

        assertTrue(plan.applicable, "${plan.conflicts}")
        assertEquals(MergeTally(insert = 0, identical = 7, conflict = 0, skipped = 0), plan.report().installedComponents)
        assertEquals(MergeWrites(), plan.writes)
    }

    /**
     * Merged into an install with the asset and none of its rows, through the core doubles (a child before its parent,
     * or a row before its SupplyItem, throws as the schema's keys would): every row and entry lands as the export
     * holds it, and a re-plan of the same archive is IDENTICAL.
     */
    @Test
    fun aMergeIntoAnInstallWithoutThemInsertsEveryRowAndEntryThenReplansIdentical() = runBlocking<Unit> {
        val source = installWithTheUpsShapes()
        val bytes = source.export.run().data
        val target = BackupInstall()
        target.assets.upsert(ups)

        val plan = target.build.run(bytes)
        assertEquals(MergeTally(insert = 7, identical = 0, conflict = 0, skipped = 0), plan.report().installedComponents)
        target.apply.run(plan)

        assertEquals(source.installedComponents.all(), target.installedComponents.all())
        assertEquals(source.installedComponents.entries.keys, target.installedComponents.entries.keys)
        val again = target.build.run(bytes)
        assertTrue(again.decisions.all { it.verdict == MergeVerdict.IDENTICAL }, "${again.decisions}")
    }

    // --- row 25: held (M2) ------------------------------------------------------------------

    /** The asset is out on a transfer here: a row the plan would insert on it is refused, naming the asset. */
    @Test
    fun anInsertForAHeldAssetIsAssetTransferredOut() {
        val plan = mergePlanOf(
            archive(listOf(pack)),
            snapshot(transfers = listOf(transferOf("r1", assetId = "x1"))),
        )

        assertEquals(conflict("ic-pack", MergeReason.ASSET_TRANSFERRED_OUT, "x1"), plan.decision("ic-pack"))
        assertEquals(MergeWrites(), plan.writes)
    }

    // --- helpers ----------------------------------------------------------------------------

    private fun MergePlan.of(): List<MergeDecision> = decisions.filter { it.table == MergeTable.INSTALLED_COMPONENTS }

    /**
     * One install holding both UPS shapes through the core doubles: the tray with four positions naming the battery,
     * position 2 replaced (the removed row and its successor), and the pack made of `4 ×` the battery and a strap.
     * Seven rows, two entries.
     */
    private suspend fun installWithTheUpsShapes(): BackupInstall {
        val phone = BackupInstall()
        phone.assets.upsert(ups)
        items.forEach { phone.supplyItems.upsert(it) }
        listOf(
            tray,
            position(1),
            position(2, removedOn = "2026-06-01"),
            position(2, id = "ic-pos2b", replacesId = "ic-pos2"),
            position(3),
            position(4),
            pack,
        ).forEach { phone.installedComponents.insert(it) }
        return phone
    }
}
