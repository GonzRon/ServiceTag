package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.model.heldIds
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.transferOf
import com.loosecannon.servicetag.core.transfer.EntangledRef
import com.loosecannon.servicetag.core.transfer.TransferFixtures
import com.loosecannon.servicetag.core.transfer.TransferFixtures.ANODE
import com.loosecannon.servicetag.core.transfer.TransferFixtures.COMPRESSOR
import com.loosecannon.servicetag.core.transfer.TransferFixtures.HEATER
import com.loosecannon.servicetag.core.transfer.TransferFixtures.OPENER
import com.loosecannon.servicetag.core.transfer.TransferPackTesting
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * #77 (C23, R77-5, R77-WITHDRAW; row 33) — the explicit, append-only, phone-only withdrawal, **pack-wide and atomic**:
 * one write appends a WITHDRAWN for every asset whose OUT of the named pack is still open here, so none of them is
 * held; each stays archived; nothing is ever updated or deleted. The estate that would remain must retain cleanly by
 * marking's rule, or the whole withdrawal is refused and nothing is written. With no open OUT of that pack for that
 * asset there is nothing to withdraw, and nothing is written. Fictional names only.
 */
class WithdrawTransferRecordTest {

    private val heater = AssetId("h1")

    private suspend fun heldHeater(): BackupInstall = BackupInstall().also { install ->
        install.assets.upsert(plainAssetOf("h1", "Example Water Heater").copy(status = AssetStatus.ARCHIVED))
        install.transfers.append(transferOf("out-1", assetId = "h1", packId = "pack-q", lineage = listOf("pack-p")))
    }

    private fun withdrawerOf(install: BackupInstall): WithdrawTransferRecord {
        var next = 0
        return WithdrawTransferRecord(
            TransferPackTesting.repositoriesOf(install), install.uow, IdGenerator { "wd-${++next}" }, Clock { WITHDRAWN_AT },
        )
    }

    @Test
    fun appendsWithdrawnSoTheAssetIsNoLongerHeldAndStaysArchived() = runBlocking<Unit> {
        val install = heldHeater()
        val before = install.transfers.all()

        val done = assertIs<WithdrawTransferResult.Withdrawn>(withdrawerOf(install).run(heater, "pack-q"))

        val expected = TransferRecord(
            id = "wd-1", assetId = heater, kind = TransferKind.WITHDRAWN, packId = "pack-q", lineage = listOf("pack-p"),
            at = WITHDRAWN_AT, packSha256 = "ab".repeat(32), nameSnapshot = "Example Water Heater", note = "",
        )
        assertEquals(listOf(expected), done.records)
        assertEquals(before + expected, install.transfers.all(), "appended; the OUT is untouched")
        assertTrue(heater !in heldIds(install.transfers.all()), "no longer held")
        assertEquals(AssetStatus.ARCHIVED, install.assets.get(heater)!!.status, "it stays archived")
        assertEquals(1, install.uow.commits, "one write")
    }

    @Test
    fun withNoOpenOutNothingIsWithdrawn() = runBlocking<Unit> {
        val install = heldHeater()
        // Another pack's id, an unknown asset, and an OUT already withdrawn: none is open here.
        assertIs<WithdrawTransferResult.NoOpenOut>(withdrawerOf(install).run(heater, "pack-other"))
        assertIs<WithdrawTransferResult.NoOpenOut>(withdrawerOf(install).run(AssetId("nobody"), "pack-q"))
        install.transfers.append(
            transferOf("wd-0", assetId = "h1", kind = TransferKind.WITHDRAWN, packId = "pack-q", lineage = listOf("pack-p")),
        )
        val before = install.transfers.all()

        assertIs<WithdrawTransferResult.NoOpenOut>(withdrawerOf(install).run(heater, "pack-q"))

        assertEquals(before, install.transfers.all(), "nothing appended")
    }

    /**
     * MJ-1: the anode's detail offers the withdrawal of the heater's pack. Both the parent and its forced component are
     * withdrawn, in one write — never the component alone, which would still name its held parent — and the estate,
     * with an unrelated pack still held, exports.
     */
    @Test
    fun withdrawingAComponentOfAMultiAssetPackWithdrawsTheWholePack() = runBlocking<Unit> {
        val install = BackupInstall().also { TransferFixtures.seed(it) }
        install.transfers.append(transferOf("out-h", assetId = HEATER, packId = "pack-q"))
        install.transfers.append(transferOf("out-a", assetId = ANODE, packId = "pack-q", nameSnapshot = "Example Anode Rod"))
        install.transfers.append(transferOf("out-x", assetId = COMPRESSOR, packId = "pack-c", nameSnapshot = "Example Compressor"))
        val before = install.transfers.all()

        val done = assertIs<WithdrawTransferResult.Withdrawn>(withdrawerOf(install).run(AssetId(ANODE), "pack-q"))

        assertEquals(
            listOf(Triple(HEATER, "pack-q", TransferKind.WITHDRAWN), Triple(ANODE, "pack-q", TransferKind.WITHDRAWN)),
            done.records.map { Triple(it.assetId.value, it.packId, it.kind) },
        )
        assertEquals(1, install.uow.commits, "one write")
        assertEquals((before + done.records).toSet(), install.transfers.all().toSet(), "appended; nothing updated")
        assertEquals(setOf(AssetId(COMPRESSOR)), heldIds(install.transfers.all()), "the other pack stays held")
        install.export.run() // the estate exports: no TransferredGraphEntangled
    }

    /** MJ-1: a group wholly inside the pack. Withdrawing one member withdraws the pack, so the group is never half held. */
    @Test
    fun withdrawingOneMemberOfAGroupWholePackWithdrawsTheWholePack() = runBlocking<Unit> {
        val install = pooled()
        install.transfers.append(transferOf("out-1", assetId = "a1", packId = "pack-q", nameSnapshot = "Example Pool Pump"))
        install.transfers.append(transferOf("out-2", assetId = "a2", packId = "pack-q", nameSnapshot = "Example Pool Filter"))

        val done = assertIs<WithdrawTransferResult.Withdrawn>(withdrawerOf(install).run(AssetId("a1"), "pack-q"))

        assertEquals(listOf("a1", "a2"), done.records.map { it.assetId.value })
        assertEquals(1, install.uow.commits, "one write")
        assertEquals(emptySet(), heldIds(install.transfers.all()))
        install.export.run() // the estate exports
    }

    /**
     * R77-WITHDRAW: the complete estate the withdrawal would leave must retain cleanly, with no exemption for a reference
     * this withdrawal did not introduce. Merged history (an event of the archived opener set onto the held compressor's
     * schedule) and a group split across two packs each refuse the whole withdrawal, and nothing is written.
     */
    @Test
    fun aWithdrawalThatWouldLeaveTheEstateEntangledIsRefusedAndWritesNothing() = runBlocking<Unit> {
        val merged = BackupInstall().also { TransferFixtures.seed(it) }
        merged.transfers.append(transferOf("out-h", assetId = HEATER, packId = "pack-q"))
        merged.transfers.append(transferOf("out-a", assetId = ANODE, packId = "pack-q", nameSnapshot = "Example Anode Rod"))
        merged.transfers.append(transferOf("out-x", assetId = COMPRESSOR, packId = "pack-c", nameSnapshot = "Example Compressor"))
        merged.events.upsert(completionOf("e9", "2026-05-01", "2026-05-01", assetId = OPENER, scheduleId = "s2"))
        val mergedBefore = merged.transfers.all()

        val refused = assertIs<WithdrawTransferResult.Entangled>(withdrawerOf(merged).run(heater, "pack-q"))

        assertEquals(listOf(EntangledRef("assetEvents", "e9", "maintenanceSchedules", "s2")), refused.refs)
        assertEquals(0, merged.uow.commits)
        assertEquals(mergedBefore, merged.transfers.all())

        val split = pooled()
        split.transfers.append(transferOf("out-1", assetId = "a1", packId = "pack-q", nameSnapshot = "Example Pool Pump"))
        split.transfers.append(transferOf("out-2", assetId = "a2", packId = "pack-r", nameSnapshot = "Example Pool Filter"))
        val splitBefore = split.transfers.all()

        val kept = assertIs<WithdrawTransferResult.Entangled>(withdrawerOf(split).run(AssetId("a1"), "pack-q"))

        assertEquals(listOf(EntangledRef("maintenanceGroups", "G2", "assets", "a2")), kept.refs)
        assertEquals(0, split.uow.commits)
        assertEquals(splitBefore, split.transfers.all())
    }

    /** Two archived pool assets and a round covering exactly the two of them. */
    private suspend fun pooled(): BackupInstall = BackupInstall().also { install ->
        install.assets.upsert(plainAssetOf("a1", "Example Pool Pump").copy(status = AssetStatus.ARCHIVED))
        install.assets.upsert(plainAssetOf("a2", "Example Pool Filter").copy(status = AssetStatus.ARCHIVED))
        install.groups.upsert(
            groupOf("G2", "Example Pool Round", archivedAt = 100L, members = listOf(
                Triple("a1", "2026-01-01", null),
                Triple("a2", "2026-01-01", null),
            )),
        )
    }

    private companion object {
        const val WITHDRAWN_AT = 1_759_000_000_000L
    }
}
