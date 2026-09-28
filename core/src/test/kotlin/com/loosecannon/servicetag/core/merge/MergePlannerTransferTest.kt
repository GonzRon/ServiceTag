package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.Backup
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.model.heldIds
import com.loosecannon.servicetag.core.model.shortPackId
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.conditionOf
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.transferOf
import com.loosecannon.servicetag.core.transfer.TransferFixtures
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * #77 (C10; R77-5, R77-12) — `TRANSFERS`, the nineteenth table, and the three rules that keep a merge from
 * undoing a transfer. A record by its id: IDENTICAL, or CONFLICT `CONTENT_DIFFERS`; one this phone lacks,
 * with R' = the local records and every incoming one absent here:
 *
 * - **M1** an IN or WITHDRAWN that would close an OUT open **here** → `ASSET_TRANSFERRED_OUT` (an ordinary
 *   archive never supersedes; a withdrawal never propagates); an OUT leaving its asset with two open OUTs in
 *   R' → `TRANSFER_DIVERGED`, both packs named; else INSERT — another install's OUT makes the asset held here;
 * - **M2** any row the rules would INSERT whose owning asset is held in R' → `ASSET_TRANSFERRED_OUT`;
 * - **M3** what stays after the merge must still retain cleanly: an inserted row naming a held row, or an
 *   inserted OUT that a local row names, → `ASSET_TRANSFERRED_OUT`.
 *
 * Every archive goes through the real decode. The names and pack ids are fictional.
 */
class MergePlannerTransferTest {

    private val heater = plainAssetOf("h1", "Example Water Heater")
    private val heldHeater = heater.copy(status = AssetStatus.ARCHIVED, updatedAt = 300L)
    private val compressor = plainAssetOf("x1", "Example Compressor")

    private val p1 = "0f1e2d3c-4b5a-4978-8796-a5b4c3d2e1f0"
    private val p2 = "9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d"

    private fun decoded(
        assets: List<Asset> = emptyList(),
        records: List<TransferRecord> = emptyList(),
        events: List<AssetEvent> = emptyList(),
        groups: List<MaintenanceGroup> = emptyList(),
        schedules: List<MaintenanceSchedule> = emptyList(),
        extra: (BackupData) -> BackupData = { it },
    ): Backup = BackupCodec.decode(
        archiveOf(
            extra(
                BackupData(
                    assets = assets.map { it.toDto() }, nfcTags = emptyList(), externalLinks = emptyList(),
                    assetEvents = events.map { it.toDto() }, maintenanceGroups = groups.map { it.toDto() },
                    maintenanceSchedules = schedules.map { it.toDto() }, transferRecords = records.map { it.toDto() },
                ),
            ),
        ),
    )

    private fun eventOf(id: String, assetId: String) = AssetEvent(
        id = EventId(id), assetId = AssetId(assetId), kind = EventKind.MAINTENANCE, title = "Example flush",
        profileId = null, occurredOn = "2026-05-01", occurredTime = null, tzId = "UTC", notes = "",
        source = EventSource.MANUAL, sourceRef = null, createdAt = 100L, updatedAt = 100L,
        measurements = emptyList(), consumables = emptyList(),
    )

    private fun MergePlan.of(table: MergeTable) = decisions.filter { it.table == table }
    private fun transferredOut(table: MergeTable, id: String, detail: String) =
        MergeDecision(table, id, MergeVerdict.CONFLICT, MergeReason.ASSET_TRANSFERRED_OUT, detail)

    private fun insert(id: String) = MergeDecision(MergeTable.TRANSFERS, id, MergeVerdict.INSERT)
    private fun identical(id: String) = MergeDecision(MergeTable.TRANSFERS, id, MergeVerdict.IDENTICAL)

    private val outQ = transferOf("r1", assetId = "h1", packId = "pack-q")

    // --- stale -------------------------------------------------------------------------------------

    /**
     * A backup made before the heater left: its asset row differs (it was archived at marking) and an event
     * this phone never had would be inserted for an asset that is no longer here to keep — both refused.
     */
    @Test
    fun aPreTransferBackupOfAHeldAssetIsRefused() {
        val e1 = eventOf("e1", "h1")
        val incoming = decoded(assets = listOf(heater, compressor), events = listOf(e1, eventOf("e9", "h1")))

        val plan = mergePlanOf(
            incoming,
            MergeSnapshot(assets = listOf(heldHeater, compressor), events = listOf(e1), transfers = listOf(outQ), attachmentStoreConfigured = true),
        )

        assertEquals(
            MergeDecision(MergeTable.ASSETS, "h1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "h1"),
            plan.of(MergeTable.ASSETS).first(),
        )
        assertEquals(
            listOf(MergeDecision(MergeTable.EVENTS, "e1", MergeVerdict.IDENTICAL), transferredOut(MergeTable.EVENTS, "e9", "h1")),
            plan.of(MergeTable.EVENTS),
        )
        assertFalse(plan.applicable)
        assertEquals(MergeWrites(), plan.writes)
    }

    /** M2, the asset row itself: a held asset whose graph this phone never had (its OUT merged in) stays out. */
    @Test
    fun aHeldAssetThisPhoneNeverHadIsNotInserted() {
        val incoming = decoded(assets = listOf(heater, compressor), events = listOf(eventOf("e1", "h1")))

        val plan = mergePlanOf(incoming, MergeSnapshot(assets = listOf(compressor), transfers = listOf(outQ), attachmentStoreConfigured = true))

        assertEquals(transferredOut(MergeTable.ASSETS, "h1", "h1"), plan.of(MergeTable.ASSETS).first())
        assertEquals(listOf(transferredOut(MergeTable.EVENTS, "e1", "h1")), plan.of(MergeTable.EVENTS))
        assertFalse(plan.applicable)
    }

    /** M2 (rm-2): a plan never says "applicable" for an insert the write guard would refuse. */
    @Test
    fun anIncomingEventOfAHeldAssetConflictsInThePlan() {
        val child = plainAssetOf("h3", "Example Drain Valve").copy(parentAssetId = AssetId("h1"))
        val incoming = decoded(
            assets = listOf(heldHeater, child),
            events = listOf(eventOf("e9", "h1")),
            extra = { it.copy(assetConditions = listOf(conditionOf("co9", assetId = "h1").toDto())) },
        )

        val plan = mergePlanOf(incoming, MergeSnapshot(assets = listOf(heldHeater), transfers = listOf(outQ), attachmentStoreConfigured = true))

        assertEquals(
            listOf(MergeDecision(MergeTable.ASSETS, "h1", MergeVerdict.IDENTICAL), transferredOut(MergeTable.ASSETS, "h3", "h1")),
            plan.of(MergeTable.ASSETS),
        )
        assertEquals(listOf(transferredOut(MergeTable.EVENTS, "e9", "h1")), plan.of(MergeTable.EVENTS))
        assertEquals(listOf(transferredOut(MergeTable.CONDITIONS, "co9", "h1")), plan.of(MergeTable.CONDITIONS))
        assertFalse(plan.applicable)
    }

    // --- M1 ----------------------------------------------------------------------------------------

    /**
     * An ordinary archive from the installation the heater came back to carries the IN that closed this phone's
     * OUT — refused: only the pack itself returns an asset (C15). A withdrawal from another phone is refused
     * the same way (R77-5). And the recipient's own IN(q), merged back into the sender: it would close the open
     * OUT(q) here (R77-B2a-MJ1, an IN of the same pack closes it) — refused loudly, never a silent unhold.
     */
    @Test
    fun anOrdinaryArchivesInClosingAnOpenOutIsRefused() {
        val here = MergeSnapshot(assets = listOf(heldHeater), transfers = listOf(outQ), attachmentStoreConfigured = true)
        val back = transferOf("i2", assetId = "h1", kind = TransferKind.IN, packId = "pack-p", lineage = listOf("pack-q"))

        val returned = mergePlanOf(decoded(records = listOf(outQ, back)), here)
        assertEquals(listOf(transferredOut(MergeTable.TRANSFERS, "i2", "h1"), identical("r1")), returned.of(MergeTable.TRANSFERS))
        assertFalse(returned.applicable)

        val withdrawn = transferOf("w3", assetId = "h1", kind = TransferKind.WITHDRAWN, packId = "pack-q")
        val elsewhere = mergePlanOf(decoded(records = listOf(outQ, withdrawn)), here)
        assertEquals(listOf(identical("r1"), transferredOut(MergeTable.TRANSFERS, "w3", "h1")), elsewhere.of(MergeTable.TRANSFERS))

        val recipients = transferOf("i4", assetId = "h1", kind = TransferKind.IN, packId = "pack-q")
        val arrived = mergePlanOf(decoded(records = listOf(recipients)), here)
        assertEquals(listOf(transferredOut(MergeTable.TRANSFERS, "i4", "h1")), arrived.of(MergeTable.TRANSFERS))
        assertFalse(arrived.applicable)
        assertEquals(MergeWrites(), arrived.writes, "still held here: nothing lands")
    }

    // --- the two ends of one transfer (R77-B2a-MJ1) ------------------------------------------------------

    /**
     * Review scenario A: this phone received the heater in q (`IN(q)`, the heater live here) and merges the
     * sender's ordinary backup, which carries only `OUT(q)`. The arrival here cancels that departure: the OUT
     * lands and the heater stays live — never silently held on the phone that actually has it.
     */
    @Test
    fun aRecipientMergingTheSendersExportKeepsItsAsset() {
        val arrived = transferOf("i1", assetId = "h1", kind = TransferKind.IN, packId = "pack-q")
        val here = MergeSnapshot(
            assets = listOf(heater, compressor), events = listOf(eventOf("e1", "h1")), transfers = listOf(arrived),
            attachmentStoreConfigured = true,
        )

        val plan = mergePlanOf(decoded(assets = listOf(compressor), records = listOf(outQ)), here)

        assertEquals(listOf(insert("r1")), plan.of(MergeTable.TRANSFERS))
        assertTrue(plan.applicable, plan.conflicts.toString())
        assertEquals(emptySet(), heldIds(here.transfers + plan.writes.transfers), "the heater stays live here")
    }

    /**
     * Review scenario B: the heater went out in q and came back in r (lineage `[q]`); this phone merges the former
     * recipient's backup, which carries `IN(q)` and `OUT(r, [q])`. This phone's own `IN(r)` cancels the incoming
     * `OUT(r)`, so the returned, live heater stays live.
     */
    @Test
    fun aReturnedAssetStaysLiveAfterMergingTheFormerRecipient() {
        val back = transferOf("i2", assetId = "h1", kind = TransferKind.IN, packId = "pack-r", lineage = listOf("pack-q"))
        val here = MergeSnapshot(
            assets = listOf(heater, compressor), events = listOf(eventOf("e1", "h1")), transfers = listOf(outQ, back),
            attachmentStoreConfigured = true,
        )
        check(heldIds(here.transfers).isEmpty())
        val theirIn = transferOf("i1", assetId = "h1", kind = TransferKind.IN, packId = "pack-q")
        val theirOut = transferOf("r3", assetId = "h1", packId = "pack-r", lineage = listOf("pack-q"))

        val plan = mergePlanOf(decoded(assets = listOf(compressor), records = listOf(theirIn, theirOut)), here)

        assertEquals(listOf(insert("i1"), insert("r3")), plan.of(MergeTable.TRANSFERS))
        assertTrue(plan.applicable, plan.conflicts.toString())
        assertEquals(emptySet(), heldIds(here.transfers + plan.writes.transfers), "the returned heater stays live")
    }

    // --- convergence -------------------------------------------------------------------------------

    /** R77-12 (1): production transferred the heater out; its backup merged into dev makes dev's live heater held. */
    @Test
    fun anotherInstallsOutMakesTheLiveAssetHeldHere() {
        val snapshot = MergeSnapshot(assets = listOf(heater, compressor), events = listOf(eventOf("e1", "h1")), attachmentStoreConfigured = true)

        val plan = mergePlanOf(decoded(assets = listOf(compressor), records = listOf(outQ)), snapshot)

        assertEquals(listOf(insert("r1")), plan.of(MergeTable.TRANSFERS))
        assertTrue(plan.applicable)
        assertEquals(listOf(outQ), plan.writes.transfers)
        assertEquals(setOf(AssetId("h1")), heldIds(plan.writes.transfers))
    }

    // --- double mark -------------------------------------------------------------------------------

    /** R77-12 (2): both phones marked the heater, in different packs — named, both packs, nothing written. */
    @Test
    fun twoOpenOutsAreTransferDiverged() {
        val mine = transferOf("r2", assetId = "h1", packId = p2)
        val theirs = transferOf("r1", assetId = "h1", packId = p1)

        val plan = mergePlanOf(
            decoded(records = listOf(theirs)),
            MergeSnapshot(assets = listOf(heldHeater), transfers = listOf(mine), attachmentStoreConfigured = true),
        )

        assertEquals(
            listOf(
                MergeDecision(
                    MergeTable.TRANSFERS, "r1", MergeVerdict.CONFLICT, MergeReason.TRANSFER_DIVERGED,
                    "$p2 (${shortPackId(p2)}), $p1 (${shortPackId(p1)})",
                ),
            ),
            plan.of(MergeTable.TRANSFERS),
        )
        assertEquals("9a8b7c6d", shortPackId(p2))
        assertFalse(plan.applicable)
    }

    /** The resolution: this phone withdrew its own OUT, so the other phone's OUT lands and is the one open. */
    @Test
    fun aWithdrawnOutThenMergesCleanly() {
        val mine = transferOf("r2", assetId = "h1", packId = p2)
        val withdrawal = transferOf("w2", assetId = "h1", kind = TransferKind.WITHDRAWN, packId = p2)
        val theirs = transferOf("r1", assetId = "h1", packId = p1)

        val plan = mergePlanOf(
            decoded(records = listOf(theirs)),
            MergeSnapshot(assets = listOf(heldHeater), transfers = listOf(mine, withdrawal), attachmentStoreConfigured = true),
        )

        assertEquals(listOf(insert("r1")), plan.of(MergeTable.TRANSFERS))
        assertTrue(plan.applicable)
    }

    /**
     * rm-11, both sides through the real export, build and apply: after the withdrawal each installation's
     * backup merges cleanly into the other, and both converge on {OUT(p1), OUT(p2), WITHDRAWN(p2)}, held under p1.
     */
    @Test
    fun theWithdrawingInstallsExportMergesCleanlyIntoTheOther() = runBlocking<Unit> {
        val x = BackupInstall(setId = "set-x")
        val y = BackupInstall(setId = "set-y")
        for (install in listOf(x, y)) {
            install.assets.upsert(heldHeater)
            install.assets.upsert(compressor)
        }
        val outP1 = transferOf("r1", assetId = "h1", packId = p1)
        val outP2 = transferOf("r2", assetId = "h1", packId = p2)
        x.transfers.append(outP1)
        y.transfers.append(outP2)

        assertFalse(y.build.run(x.export.run().data).applicable, "the double mark is divergence first")

        val withdrawal = transferOf("w2", assetId = "h1", kind = TransferKind.WITHDRAWN, packId = p2)
        y.transfers.append(withdrawal)
        val intoY = y.build.run(x.export.run().data)
        assertTrue(intoY.applicable)
        y.apply.run(intoY)

        val intoX = x.build.run(y.export.run().data)
        assertTrue(intoX.applicable, intoX.conflicts.toString())
        x.apply.run(intoX)

        val converged = listOf(outP1, outP2, withdrawal)
        assertEquals(converged, x.transfers.all())
        assertEquals(converged, y.transfers.all())
        for (install in listOf(x, y)) {
            val open = install.transfers.all().filter { r ->
                r.kind == TransferKind.OUT && install.transfers.all().none { it.kind == TransferKind.WITHDRAWN && it.packId == r.packId }
            }
            assertEquals(listOf(p1), open.map { it.packId }, "held under p1")
            assertEquals(setOf(AssetId("h1")), install.transfers.heldIds())
        }
    }

    // --- M3 ----------------------------------------------------------------------------------------

    /** An incoming event of the compressor naming the held heater's group schedule would break every later backup. */
    @Test
    fun anIncomingEventNamingAHeldGroupScheduleConflicts() {
        val round = groupOf("G1", "Example Flush Round", members = listOf(Triple("h1", "2026-01-01", null)))
        val roundSchedule = scheduleOf("sg", assetId = null, groupId = "G1", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR)
        val stray = completionOf("e9", "2026-02-01", "2026-02-01", assetId = "x1", scheduleId = "sg")
        val incoming = decoded(
            assets = listOf(heldHeater, compressor), groups = listOf(round), schedules = listOf(roundSchedule), events = listOf(stray),
        )

        val plan = mergePlanOf(
            incoming,
            MergeSnapshot(
                assets = listOf(heldHeater, compressor), groups = listOf(round), schedules = listOf(roundSchedule),
                transfers = listOf(outQ), attachmentStoreConfigured = true,
            ),
        )

        assertEquals(listOf(transferredOut(MergeTable.EVENTS, "e9", "h1")), plan.of(MergeTable.EVENTS))
        assertEquals(listOf(transferredOut(MergeTable.EVENTS, "e9", "h1")), plan.conflicts)
    }

    /** An incoming OUT for the heater while a local round mixes it with the staying compressor: refused on the OUT. */
    @Test
    fun anIncomingOutEntanglingALocalRowConflicts() {
        val mixed = groupOf(
            "G2", "Example Mixed Round",
            members = listOf(Triple("h1", "2026-01-01", null), Triple("x1", "2026-01-01", null)),
        )

        val plan = mergePlanOf(
            decoded(assets = listOf(compressor), records = listOf(outQ)),
            MergeSnapshot(assets = listOf(heater, compressor), groups = listOf(mixed), attachmentStoreConfigured = true),
        )

        assertEquals(listOf(transferredOut(MergeTable.TRANSFERS, "r1", "G2")), plan.of(MergeTable.TRANSFERS))
        assertFalse(plan.applicable)
    }

    // --- the report ---------------------------------------------------------------------------------

    @Test
    fun recordsTally() {
        val changed = outQ.copy(note = "Example changed note")
        val plan = mergePlanOf(
            decoded(records = listOf(changed, transferOf("r5", assetId = "a9", packId = "pack-s"), transferOf("r6", assetId = "a8", packId = "pack-t"))),
            MergeSnapshot(transfers = listOf(outQ, transferOf("r5", assetId = "a9", packId = "pack-s")), attachmentStoreConfigured = true),
        )

        assertEquals(
            MergeDecision(MergeTable.TRANSFERS, "r1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "r1"),
            plan.of(MergeTable.TRANSFERS).first(),
        )
        assertEquals(MergeTally(insert = 1, identical = 1, conflict = 1, skipped = 0), plan.report().transfers)
        assertEquals(MergeTable.entries.last(), MergeTable.TRANSFERS)
        assertEquals(19, MergeTable.entries.size, "nineteen tables")
    }

    /** The fixtures' estate, one install, as a sanity check that nothing here moves a merge without records. */
    @Test
    fun anEstateWithoutRecordsMergesAsItAlwaysDid() = runBlocking<Unit> {
        val source = BackupInstall()
        TransferFixtures.seed(source)
        val target = BackupInstall()

        val plan = target.build.run(source.export.run().data)

        assertTrue(plan.applicable, plan.conflicts.toString())
        assertEquals(emptyList(), plan.of(MergeTable.TRANSFERS))
    }
}
