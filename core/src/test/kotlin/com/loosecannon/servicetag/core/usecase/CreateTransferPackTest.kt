package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.transfer.TransferFixtures
import com.loosecannon.servicetag.core.transfer.TransferFixtures.ANODE
import com.loosecannon.servicetag.core.transfer.TransferFixtures.COMPRESSOR
import com.loosecannon.servicetag.core.transfer.TransferFixtures.HEATER
import com.loosecannon.servicetag.core.transfer.TransferPackTesting
import com.loosecannon.servicetag.core.transfer.TransferPackTesting.PACK_ID
import com.loosecannon.servicetag.core.transfer.TransferRefusal
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** #77 (C5; AC 1, 2, 15) — creation reads the pack's contents out of one snapshot and writes nothing. */
class CreateTransferPackTest {

    private suspend fun seeded() = BackupInstall().also { TransferFixtures.seed(it) }

    /** The manifest's counts, tallies and content hash are the inner archive's own; the set id is the pack id. */
    @Test
    fun countsEqualTheInnerManifest() = runBlocking<Unit> {
        val install = seeded()
        val lineage: suspend (AssetId) -> List<String> = { if (it.value == HEATER) listOf("pack-older") else emptyList() }

        val draft = assertIs<CreateTransferPackResult.Created>(
            TransferPackTesting.creationOf(install, lineageOf = lineage).run(listOf(AssetId(HEATER)), "Example note"),
        ).draft

        val inner = BackupCodec.decode(draft.data).manifest
        assertEquals(inner.counts, draft.counts)
        assertEquals(2 to 2, inner.counts["assets"] to inner.counts["attachments"])
        assertEquals(inner.artifactCount to inner.artifactBytes, draft.attachments to draft.attachmentBytes)
        assertEquals(inner.dataSha256, draft.contentSha256)
        assertEquals(PACK_ID to PACK_ID, inner.backupSetId to draft.packId)
        assertEquals(listOf(HEATER) to listOf(HEATER, ANODE), draft.rootAssetIds to draft.assetIds)
        assertEquals(mapOf(HEATER to listOf("pack-older"), ANODE to emptyList()), draft.lineage)
        assertEquals(listOf(HEATER, ANODE).sorted(), draft.lineage.keys.toList(), "keyed in order")
        assertEquals("Example note", draft.note)
    }

    /** The artifacts plan is the pack's MANAGED rows — the heater's document and its completion's photo — and nothing the sender keeps. */
    @Test
    fun thePlanNamesOnlyThePacksManagedRows() = runBlocking<Unit> {
        val draft = assertIs<CreateTransferPackResult.Created>(
            TransferPackTesting.creationOf(seeded()).run(listOf(AssetId(HEATER))),
        ).draft

        assertEquals(listOf("at1", "at2"), draft.plan.entries.map { it.attachmentId.value })
        assertEquals(PACK_ID, draft.plan.backupSetId)
        val rows = BackupCodec.decode(draft.data).data.attachments
        assertEquals(rows.map { it.sha256 to it.sizeBytes }, draft.plan.entries.map { it.sha256 to it.sizeBytes })
    }

    /** A refused selection mints no pack id and opens no write. */
    @Test
    fun aRefusedSelectionCreatesNothing() = runBlocking<Unit> {
        val install = seeded()
        var minted = 0
        val creation = TransferPackTesting.creationOf(install, ids = IdGenerator { minted += 1; "pack-x" })

        val result = creation.run(listOf(AssetId(COMPRESSOR)))

        assertEquals(CreateTransferPackResult.Refused(listOf(TransferRefusal.OpenLoan(AssetId(COMPRESSOR), "l2"))), result)
        assertEquals(0 to 0, minted to install.uow.commits)
    }

    /** P77-59: over the data cap the pack is refused, not built for every recipient to call damaged. */
    @Test
    fun overTheCapIsRefused() = runBlocking<Unit> {
        val result = TransferPackTesting.creationOf(seeded(), maxDataBytes = 1_000L).run(listOf(AssetId(HEATER)))

        val tooLarge = assertIs<CreateTransferPackResult.TooLarge>(result)
        assertEquals(CreateTransferPackResult.TooLarge.Part.DATA to 1_000L, tooLarge.part to tooLarge.limit)
        assertEquals(true, tooLarge.bytes > 1_000L)
    }

    /** mn-1: a manifest the reader would call damaged (over its cap) is refused at creation, not shipped. */
    @Test
    fun aManifestOverTheCapIsRefused() = runBlocking<Unit> {
        val result = TransferPackTesting.creationOf(seeded(), maxManifestBytes = 500L).run(listOf(AssetId(HEATER)))

        val tooLarge = assertIs<CreateTransferPackResult.TooLarge>(result)
        assertEquals(CreateTransferPackResult.TooLarge.Part.MANIFEST to 500L, tooLarge.part to tooLarge.limit)
        assertEquals(true, tooLarge.bytes > 500L)
    }

    /** mn-1: a data archive small as stored but past the reader's inflated cap is refused at creation too. */
    @Test
    fun inflatedDataOverTheCapIsRefused() = runBlocking<Unit> {
        val result = TransferPackTesting.creationOf(seeded(), maxJsonBytes = 2_000L).run(listOf(AssetId(HEATER)))

        val tooLarge = assertIs<CreateTransferPackResult.TooLarge>(result)
        assertEquals(CreateTransferPackResult.TooLarge.Part.INFLATED_DATA to 2_000L, tooLarge.part to tooLarge.limit)
        assertEquals(true, tooLarge.bytes > 2_000L)
    }

    /** One read transaction, no write, every table read inside it, and the store exactly as it was. */
    @Test
    fun createWritesNothing() = runBlocking<Unit> {
        val install = seeded()
        val before = install.export.run().data
        val files = install.storage.store.files.mapValues { it.value.toList() }
        val readsBefore = install.uow.reads

        assertIs<CreateTransferPackResult.Created>(TransferPackTesting.creationOf(install).run(listOf(AssetId(HEATER))))

        assertEquals(0 to 0, install.uow.commits to install.uow.rollbacks)
        assertEquals(1, install.uow.reads - readsBefore)
        assertEquals(0, install.uow.readsOutsideSnapshot)
        assertContentEquals(before, install.export.run().data)
        assertEquals(files, install.storage.store.files.mapValues { it.value.toList() })
    }
}
