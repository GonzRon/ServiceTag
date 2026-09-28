package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.TransferredOutInArchive
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.testing.transferOf
import com.loosecannon.servicetag.core.transfer.TransferFixtures
import com.loosecannon.servicetag.core.transfer.TransferPackTesting
import kotlin.test.assertFailsWith
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.caseEntryOf
import com.loosecannon.servicetag.core.testing.caseOf
import com.loosecannon.servicetag.core.testing.loanOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * #74 (C12) — the replace import and the owner's categories. The archive's rows go in first, any
 * filed under a built-in's key dropped; then **every** restored asset is promoted in the same
 * transaction by the one chooser (`CategoryBackfill.plan`, N6), so a format ≤8 archive — which never
 * carries a row — or a format-9 archive whose assets name a category it does not carry leaves the
 * catalog complete. Assets are written in the canonical spelling with the file's own `updatedAt`.
 */
class ImportBackupReplaceTest {

    private fun asset(id: String, category: String, createdAt: Long, updatedAt: Long): Asset =
        plainAssetOf(id, updatedAt = updatedAt).copy(category = category, createdAt = createdAt)

    private fun data(assets: List<Asset>, categories: List<AssetCategory> = emptyList()) = BackupData(
        assets = assets.map { it.toDto() },
        nfcTags = emptyList(),
        externalLinks = emptyList(),
        assetCategories = categories.map { it.toDto() },
    )

    private suspend fun BackupInstall.spellings(): Map<String, String> =
        assets.all().associate { it.id.value to it.category }

    private suspend fun BackupInstall.updatedAts(): Map<String, Long> =
        assets.all().associate { it.id.value to it.updatedAt }

    /**
     * A format-9 archive whose rows are complete restores them exactly — the unused one included —
     * adds nothing, and replaces whatever rows this install held before.
     */
    @Test
    fun aFormat9ArchiveRestoresItsRowsAndAddsNothing() = runBlocking<Unit> {
        val install = BackupInstall()
        install.categories.upsert(AssetCategory("old key", "Old key", 1L, 1L))
        val rows = listOf(
            AssetCategory("appliance", "Appliance", 100L, 150L),
            AssetCategory("spare parts", "Spare parts", 50L, 50L),
        )

        install.replace.run(archiveOf(data(listOf(asset("a1", "Appliance", 100L, 200L)), rows)))

        assertEquals(rows, install.categories.all())
        assertEquals(mapOf("a1" to "Appliance"), install.spellings())
        assertEquals(1, install.uow.commits)
    }

    /**
     * A format-8 archive has no rows at all: its assets' categories are promoted by C9's rule — one
     * row per key, spelled as the oldest asset spells it, every variant rewritten to it, a built-in's
     * key rewritten to the label with no row, a blank ignored — and **no `updatedAt` moves**.
     */
    @Test
    fun aFormat8ArchivesAssetsPromoteTheirCategories() = runBlocking<Unit> {
        val install = BackupInstall()

        install.replace.run(
            archiveOf(
                data(
                    listOf(
                        asset("a1", "appliance", createdAt = 300L, updatedAt = 900L),
                        asset("a2", "Appliance", createdAt = 100L, updatedAt = 910L),
                        asset("a3", " APPLIANCE", createdAt = 200L, updatedAt = 920L),
                        asset("h1", "hot  TUB", createdAt = 50L, updatedAt = 930L),
                        asset("b1", "", createdAt = 10L, updatedAt = 940L),
                        asset("w1", "Water  heater", createdAt = 400L, updatedAt = 950L),
                    ),
                ),
                formatVersion = 8,
            ),
        )

        assertEquals(
            listOf(
                AssetCategory("appliance", "Appliance", 100L, 100L),
                AssetCategory("water heater", "Water heater", 400L, 400L),
            ),
            install.categories.all(),
        )
        assertEquals(
            mapOf(
                "a1" to "Appliance", "a2" to "Appliance", "a3" to "Appliance",
                "h1" to "Hot tub", "b1" to "", "w1" to "Water heater",
            ),
            install.spellings(),
        )
        assertEquals(
            mapOf("a1" to 900L, "a2" to 910L, "a3" to 920L, "h1" to 930L, "b1" to 940L, "w1" to 950L),
            install.updatedAts(),
        )
        assertEquals(1, install.uow.commits)
    }

    /**
     * A format-9 archive whose assets name a category its rows do not carry gets that row; a key
     * its rows **do** carry keeps the row's spelling, even against an asset older than the row.
     */
    @Test
    fun aFormat9ArchiveNamingAnAbsentCategoryGetsIt() = runBlocking<Unit> {
        val install = BackupInstall()
        val appliance = AssetCategory("appliance", "Appliance", 100L, 150L)

        install.replace.run(
            archiveOf(
                data(
                    listOf(
                        asset("a1", "APPLIANCE", createdAt = 50L, updatedAt = 500L),
                        asset("t1", "Test gear", createdAt = 300L, updatedAt = 600L),
                    ),
                    listOf(appliance),
                ),
            ),
        )

        assertEquals(listOf(appliance, AssetCategory("test gear", "Test gear", 300L, 300L)), install.categories.all())
        assertEquals(mapOf("a1" to "Appliance", "t1" to "Test gear"), install.spellings())
        assertEquals(mapOf("a1" to 500L, "t1" to 600L), install.updatedAts())
    }

    /**
     * A row filed under a **built-in's** key decodes (never refused) and is dropped here: the
     * built-in wins its key, its asset takes the label, and the owner's own row beside it lands.
     */
    @Test
    fun aBuiltInKeyedRowIsDropped() = runBlocking<Unit> {
        val install = BackupInstall()
        val appliance = AssetCategory("appliance", "Appliance", 2L, 2L)

        install.replace.run(
            archiveOf(
                data(
                    listOf(asset("h1", "hot tub", createdAt = 5L, updatedAt = 700L)),
                    listOf(AssetCategory("hot tub", "hot tub", 1L, 1L), appliance),
                ),
            ),
        )

        assertEquals(listOf(appliance), install.categories.all())
        assertEquals(mapOf("h1" to "Hot tub"), install.spellings())
        assertEquals(mapOf("h1" to 700L), install.updatedAts())
    }

    /**
     * #79 (C18): the replace wipes this install's cases and their timelines, and lands the archive's —
     * the headers after their assets, the entries after their cases — in the one transaction.
     */
    @Test
    fun theArchivesCasesAndEntriesReplaceThisInstalls() = runBlocking<Unit> {
        val install = BackupInstall()
        install.assets.upsert(plainAssetOf("a0", "Old heater"))
        install.serviceCases.upsert(caseOf("c-old", assetId = "a0"))
        install.caseEntries.insert(caseEntryOf("n-old", caseId = "c-old"))
        val cases = listOf(caseOf("c1"), caseOf("c2", status = CaseStatus.CANCELLED, closedOn = "2026-09-22"))
        val entries = listOf(caseEntryOf("n1"), caseEntryOf("n2", caseId = "c2", status = CaseStatus.CANCELLED, note = ""))

        install.replace.run(
            archiveOf(
                data(listOf(asset("a1", "Appliance", 100L, 200L))).copy(
                    serviceCases = cases.map { it.toDto() },
                    serviceCaseEntries = entries.map { it.toDto() },
                ),
            ),
        )

        assertEquals(cases, install.serviceCases.all())
        assertEquals(entries, install.caseEntries.all())
        assertEquals(1, install.uow.commits)
    }

    /**
     * #72 (C6): the archive's loans — an open one and the returned history — replace this install's, the
     * old ones wiped by name, and land after their assets in the one transaction.
     */
    @Test
    fun theArchivesLoansReplaceThisInstalls() = runBlocking<Unit> {
        val install = BackupInstall()
        install.assets.upsert(plainAssetOf("a0", "Old drill"))
        install.loans.upsert(loanOf("l-old", assetId = "a0"))
        val loans = listOf(
            loanOf("l1", assetId = "a1"),
            loanOf("l2", assetId = "a1", lentOn = "2026-08-01", returnedOn = "2026-08-02", contactLookupUri = null),
        )

        install.replace.run(archiveOf(data(listOf(asset("a1", "Appliance", 100L, 200L))).copy(assetLoans = loans.map { it.toDto() })))

        assertEquals(loans, install.loans.all())
        assertEquals(1, install.uow.commits)
    }

    // --- #77 (C9, R77-13): records are wiped and reloaded; a held graph is refused first -------------

    private val heater = AssetId(TransferFixtures.HEATER)
    private val anode = AssetId(TransferFixtures.ANODE)

    private suspend fun seeded(): BackupInstall = BackupInstall().also { TransferFixtures.seed(it) }

    private suspend fun BackupInstall.snapshot() = readSnapshot(TransferPackTesting.repositoriesOf(this))

    /** A post-transfer backup lands its records and what stays, and nothing of the held graph (AC 12). */
    @Test
    fun aPostTransferBackupRestoresTheRecordsOnly() = runBlocking<Unit> {
        val source = seeded()
        source.transfers.append(transferOf("r1", assetId = heater.value, packId = "pack-q"))
        source.transfers.append(transferOf("r2", assetId = anode.value, packId = "pack-q"))
        val bytes = source.export.run().data
        val target = BackupInstall()
        target.assets.upsert(plainAssetOf("z1", "Example Pump"))
        target.transfers.append(transferOf("r9", assetId = "z9", kind = TransferKind.IN, packId = "pack-z"))

        target.replace.run(bytes)

        assertEquals(source.transfers.all(), target.transfers.all(), "wiped, then reloaded from the archive")
        assertEquals(listOf("g1", "x1"), target.assets.all().map { it.id.value }.sorted())
        assertEquals(setOf(heater, anode), target.transfers.heldIds())
    }

    /**
     * R77-13: a stale pre-transfer backup must never silently bring back what this phone transferred out.
     * Checked against this phone's open OUTs before the wipe, in the same write: refused, nothing wiped.
     */
    @Test
    fun aReplaceOfAPreTransferBackupIsRefusedForAHeldAsset() = runBlocking<Unit> {
        val install = seeded()
        val preTransfer = install.export.run().data
        install.transfers.append(transferOf("r1", assetId = heater.value, packId = "pack-q"))
        install.transfers.append(transferOf("r2", assetId = anode.value, packId = "pack-q"))
        val before = install.snapshot() to install.transfers.all()
        val commits = install.uow.commits

        val refusal = assertFailsWith<TransferredOutInArchive> { install.replace.run(preTransfer) }

        assertEquals(listOf(heater, anode), refusal.assetIds)
        assertEquals(before, install.snapshot() to install.transfers.all(), "nothing wiped")
        assertEquals(commits, install.uow.commits)
        assertEquals(0, install.rebuilds)
    }

    /**
     * R77-13's recovery path: a Replace restore from the installation the assets legitimately came back to —
     * its own records carry INs whose lineage names this phone's OUT — restores, the returned assets included.
     * rm-8: a lineage naming an OUT this phone withdrew by mistake still closes it. An asset whose IN does not
     * name this phone's OUT is still refused.
     */
    @Test
    fun aReplaceCarryingALaterClosingInRestores() = runBlocking<Unit> {
        val other = seeded()
        other.transfers.append(transferOf("i1", assetId = heater.value, kind = TransferKind.IN, packId = "pack-p", lineage = listOf("pack-q")))
        other.transfers.append(transferOf("i2", assetId = anode.value, kind = TransferKind.IN, packId = "pack-p", lineage = listOf("pack-q")))
        val returned = other.export.run().data

        // Only the records matter to the check; the rows here are wiped either way.
        val here = BackupInstall()
        here.transfers.append(transferOf("r1", assetId = heater.value, packId = "pack-q"))
        here.transfers.append(transferOf("r2", assetId = anode.value, packId = "pack-q2"))
        here.transfers.append(transferOf("r3", assetId = anode.value, packId = "pack-q"))
        here.transfers.append(transferOf("r4", assetId = anode.value, kind = TransferKind.WITHDRAWN, packId = "pack-q"))
        check(here.transfers.heldIds() == setOf(heater, anode))

        here.replace.run(returned)

        assertEquals(other.transfers.all(), here.transfers.all())
        assertEquals(emptySet(), here.transfers.heldIds())
        assertEquals(TransferFixtures.assets.map { it.id.value }.sorted(), here.assets.all().map { it.id.value }.sorted())

        val foreign = seeded()
        foreign.transfers.append(transferOf("i1", assetId = heater.value, kind = TransferKind.IN, packId = "pack-p", lineage = listOf("pack-q")))
        foreign.transfers.append(transferOf("i2", assetId = anode.value, kind = TransferKind.IN, packId = "pack-p", lineage = listOf("pack-x")))
        val stranger = BackupInstall()
        stranger.transfers.append(transferOf("r1", assetId = heater.value, packId = "pack-q"))
        stranger.transfers.append(transferOf("r2", assetId = anode.value, packId = "pack-q"))
        val refusal = assertFailsWith<TransferredOutInArchive> { stranger.replace.run(foreign.export.run().data) }
        assertEquals(listOf(anode), refusal.assetIds)
    }

    /**
     * R77-B2a-MJ1 with R77-13 unchanged: a same-pack IN is never a return. The recipient's archive carries the
     * heater and anode live with its own `IN(q)` — and, once it has merged this phone's backup, this phone's
     * `OUT(q)` too, which its `IN(q)` cancels, so its export now carries the graph — but no lineage naming q. A
     * Replace from it on this phone, which still holds `OUT(q)`, is refused before anything is wiped.
     */
    @Test
    fun aReplaceFromTheRecipientsArchiveIsStillRefused() = runBlocking<Unit> {
        val sender = seeded()
        sender.transfers.append(transferOf("r1", assetId = heater.value, packId = "pack-q"))
        sender.transfers.append(transferOf("r2", assetId = anode.value, packId = "pack-q"))
        val before = sender.snapshot() to sender.transfers.all()
        val commits = sender.uow.commits

        val recipient = seeded()
        recipient.transfers.append(transferOf("i1", assetId = heater.value, kind = TransferKind.IN, packId = "pack-q"))
        recipient.transfers.append(transferOf("i2", assetId = anode.value, kind = TransferKind.IN, packId = "pack-q"))
        val arrivedOnly = recipient.export.run().data
        val onlyIns = assertFailsWith<TransferredOutInArchive> { sender.replace.run(arrivedOnly) }
        assertEquals(listOf(heater, anode), onlyIns.assetIds)

        recipient.transfers.append(transferOf("r1", assetId = heater.value, packId = "pack-q"))
        recipient.transfers.append(transferOf("r2", assetId = anode.value, packId = "pack-q"))
        val merged = recipient.export.run().data
        val refusal = assertFailsWith<TransferredOutInArchive> { sender.replace.run(merged) }

        assertEquals(listOf(heater, anode), refusal.assetIds)
        assertEquals(before, sender.snapshot() to sender.transfers.all(), "nothing wiped")
        assertEquals(commits, sender.uow.commits)
    }
}
