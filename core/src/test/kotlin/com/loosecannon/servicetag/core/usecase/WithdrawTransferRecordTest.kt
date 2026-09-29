package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.model.heldIds
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.transferOf
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * #77 (C23, R77-5; row 33) — the explicit, append-only, phone-only withdrawal: one WITHDRAWN naming an OUT that is
 * open here, so the asset is no longer held; the asset stays archived; nothing is ever updated or deleted. With no
 * open OUT of that pack for that asset there is nothing to withdraw, and nothing is written. Fictional names only.
 */
class WithdrawTransferRecordTest {

    private val heater = AssetId("h1")

    private suspend fun heldHeater(): BackupInstall = BackupInstall().also { install ->
        install.assets.upsert(plainAssetOf("h1", "Example Water Heater").copy(status = AssetStatus.ARCHIVED))
        install.transfers.append(transferOf("out-1", assetId = "h1", packId = "pack-q", lineage = listOf("pack-p")))
    }

    private fun withdrawerOf(install: BackupInstall) =
        WithdrawTransferRecord(install.transfers, install.uow, IdGenerator { "wd-1" }, Clock { WITHDRAWN_AT })

    @Test
    fun appendsWithdrawnSoTheAssetIsNoLongerHeldAndStaysArchived() = runBlocking<Unit> {
        val install = heldHeater()
        val before = install.transfers.all()

        val done = assertIs<WithdrawTransferResult.Withdrawn>(withdrawerOf(install).run(heater, "pack-q"))

        val expected = TransferRecord(
            id = "wd-1", assetId = heater, kind = TransferKind.WITHDRAWN, packId = "pack-q", lineage = listOf("pack-p"),
            at = WITHDRAWN_AT, packSha256 = "ab".repeat(32), nameSnapshot = "Example Water Heater", note = "",
        )
        assertEquals(expected, done.record)
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

    private companion object {
        const val WITHDRAWN_AT = 1_759_000_000_000L
    }
}
