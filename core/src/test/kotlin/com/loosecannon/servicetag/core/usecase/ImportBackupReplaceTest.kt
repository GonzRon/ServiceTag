package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.testing.successionOf
import com.loosecannon.servicetag.core.backup.TransferredOutInArchive
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.AttachmentSource
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.testing.transferOf
import com.loosecannon.servicetag.core.transfer.TransferFixtures
import com.loosecannon.servicetag.core.transfer.TransferPackTesting
import kotlin.test.assertFailsWith
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.InstalledComponentTree
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.RiggedFailure
import com.loosecannon.servicetag.core.testing.compositionEntryOf
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.installedComponentOf
import com.loosecannon.servicetag.core.testing.SupplyEstate
import com.loosecannon.servicetag.core.testing.assetSupplyOf
import com.loosecannon.servicetag.core.testing.dataTreeOf
import com.loosecannon.servicetag.core.testing.supplyItemOf
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

    // --- #86 (C5): the successions --------------------------------------------------------------------

    /** Wiped by name before the assets and reloaded after them: this install's rows go, the archive's land. */
    @Test
    fun successionsAreWipedAndReloaded() = runBlocking<Unit> {
        val install = BackupInstall()
        listOf("a1", "a2", "a3").forEach { install.assets.upsert(plainAssetOf(it, "Example Water Heater $it")) }
        install.successions.append(successionOf("old", predecessor = "a1", successor = "a2"))
        val incoming = listOf(successionOf("s1", predecessor = "a2", successor = "a3"), successionOf("s0", predecessor = "b1", successor = "b2"))
        val archive = data(listOf("a2", "a3", "b1", "b2").map { plainAssetOf(it, "Sample Pool Pump $it") })
            .copy(assetSuccessions = incoming.map { it.toDto() })

        install.replace.run(archiveOf(archive))

        assertEquals(incoming.sortedBy { it.id }, install.successions.all())
        assertEquals(1, install.uow.commits)
    }

    /** A format ≤14 archive carries no successions, so a Replace restore of one leaves the table empty. */
    @Test
    fun aFormat14RestoreEmptiesTheTable() = runBlocking<Unit> {
        val install = BackupInstall()
        listOf("a1", "a2").forEach { install.assets.upsert(plainAssetOf(it, "Example Water Heater $it")) }
        install.successions.append(successionOf("s1", predecessor = "a1", successor = "a2"))

        install.replace.run(archiveOf(data(listOf(plainAssetOf("a1", "Example Water Heater"))), formatVersion = 14))

        assertEquals(emptyList(), install.successions.all())
        assertEquals(listOf("a1"), install.assets.all().map { it.id.value })
    }

    // --- #85 (C5): the attachment's source provenance ---------------------------------------------------

    /** A Replace restore lands each document's source as the archive carries it, and none where it carries none. */
    @Test
    fun theSourceIsRestored() = runBlocking<Unit> {
        val install = BackupInstall()
        val provenance = AttachmentSource(
            uri = "https://manuals.example.invalid/heater/manual.pdf",
            resolvedUri = "https://cdn.example.invalid/heater/manual.pdf",
            retrievedAt = 1_758_900_000_000L,
            name = "Example Water Heater manual",
        )
        val saved = TransferFixtures.attachmentOf("at1", AttachmentOwner.OfAsset(AssetId("h1")), "assets/h1/at1.pdf", "application/pdf")
            .copy(source = provenance)
        val plain = TransferFixtures.attachmentOf("at3", AttachmentOwner.OfAsset(AssetId("x1")), "assets/x1/at3.pdf", "application/pdf")
        val archive = data(listOf(plainAssetOf("h1", "Example Water Heater"), plainAssetOf("x1", "Example Compressor")))
            .copy(attachments = listOf(saved, plain).map { it.toDto() })

        install.replace.run(archiveOf(archive))

        assertEquals(listOf(saved, plain), install.attachments.all().sortedBy { it.id.value })
        assertEquals(1, install.uow.commits)
    }

    /**
     * #15 (C10, row 13): a replace of a format-18 archive restores every SupplyItem, specification, applicability row
     * and line link byte-equal to the file, and leaves none of this install's own: the wipe takes the old catalog
     * after the assets (whose CASCADE took the old applicability first); the write order itself is read, not pinned here.
     */
    @Test
    fun aReplaceRestoresEverySupplyRowAndLinkByteEqual() = runBlocking<Unit> {
        val install = BackupInstall()
        install.assets.upsert(plainAssetOf("old", "Example Old Filter Housing"))
        install.supplyItems.upsert(supplyItemOf("s-old", "Example Old Cartridge"))
        install.assetSupplies.insert(assetSupplyOf("as-old", "old", "s-old", "Cartridge"))
        val bytes = archiveOf(SupplyEstate.data())

        install.replace.run(bytes)

        assertEquals(listOf(SupplyEstate.prefilter, SupplyEstate.membrane), install.supplyItems.all())
        assertEquals(listOf(SupplyEstate.prefilterOnSystem, SupplyEstate.membraneOnSoftener), install.assetSupplies.all())
        assertEquals(listOf(SupplyEstate.quickAction), install.profiles.all())
        assertEquals(1, install.uow.commits)
        val written = dataTreeOf(bytes)
        val again = dataTreeOf(install.export.run().data)
        for (list in listOf("supplyItems", "assetSupplies", "eventProfiles", "assetEvents")) {
            assertEquals(written.getValue(list).toString(), again.getValue(list).toString(), "$list byte-equal")
        }
    }

    // --- #47 (C11, row 17): the installed components ------------------------------------------------------

    /**
     * A fictional UPS whose ids run against its tree: every child's id sorts before its parent's, so the file's own
     * order (rows by id) is children first. A 3-deep tray with position 1 replaced in place and a monitor fitted in the
     * new position (the instance-level form), and a pack made of `4 ×` a battery and `2 ×` an archived strap (the
     * aggregate form), its entries' ids against their order too.
     */
    private object Ups {
        val asset = plainAssetOf("x1", "Example UPS")
        val battery = supplyItemOf("s1", "Example 12 V Battery")
        val strap = supplyItemOf("s2", "Example Terminal Strap", archivedAt = 3_000L)
        val tray = installedComponentOf("c9", name = "Example Battery Tray", installedOn = "2026-01-10")
        val removed = installedComponentOf(
            "c7", name = "Position 1", parentId = "c9", supplyId = "s1", installedOn = "2026-01-10", removedOn = "2026-06-01",
        )
        val position = installedComponentOf(
            "c5", name = "Position 1", parentId = "c9", supplyId = "s1", serialOrLot = "LOT-EX-0001",
            installedOn = "2026-06-01", replacesId = "c7",
        )
        val monitor = installedComponentOf("c1", name = "Example Cell Monitor", parentId = "c5", installedOn = "2026-06-01")
        val pack = installedComponentOf(
            "c3", name = "Example Battery Pack", sortOrder = 1, notes = "Example note",
            composition = listOf(compositionEntryOf("e2", "s1", 4.0, "ea", 0), compositionEntryOf("e1", "s2", 2.0, "ea", 1)),
        )
        val rows = listOf(tray, removed, position, monitor, pack)
    }

    private fun upsData(): BackupData = data(listOf(Ups.asset)).copy(
        supplyItems = listOf(Ups.battery, Ups.strap).map { it.toDto() },
        installedComponents = Ups.rows.map { it.toDto() },
    )

    /**
     * A file holding every child before its parent restores parents first — the core double refuses a child whose
     * parent is not stored, as the `parent_id` FK does — every row, entry, pointer and date as the file holds it, and
     * this install's export of it writes the list back byte-equal.
     */
    @Test
    fun aShuffledTreeRestoresParentsFirstByteEqual() = runBlocking<Unit> {
        val install = BackupInstall()
        val bytes = archiveOf(upsData())
        assertEquals(
            listOf("c1", "c3", "c5", "c7", "c9"),
            BackupCodec.decode(bytes).data.installedComponents.map { it.id },
            "the file's own order puts every child before its parent",
        )

        install.replace.run(bytes)

        assertEquals(Ups.rows.sortedBy { it.id.value }, install.installedComponents.all())
        assertEquals(setOf("e1", "e2"), install.installedComponents.entries.keys)
        assertEquals(1, install.uow.commits)
        val written = dataTreeOf(bytes)
        val again = dataTreeOf(install.export.run().data)
        assertEquals(
            written.getValue("installedComponents").toString(), again.getValue("installedComponents").toString(),
            "installedComponents byte-equal",
        )
    }

    /**
     * Over the core double, whose `asset_id` CASCADE `BackupInstall` registers: this install already holds the same
     * tree under the same row and entry ids, and a row on an asset the file does not carry. The wipe's
     * `assets.deleteAll()` takes them all with their entries, so the file's rows land — no id is refused as held
     * twice — and none of this install's own survives.
     */
    @Test
    fun replacingAnInstallHoldingATreeSucceeds() = runBlocking<Unit> {
        val install = BackupInstall()
        install.assets.upsert(Ups.asset)
        install.assets.upsert(plainAssetOf("x2", "Example RO System"))
        listOf(Ups.battery, Ups.strap).forEach { install.supplyItems.upsert(it) }
        val held = Ups.rows.map { it.copy(notes = "Example earlier note", updatedAt = 9_000L) }
        InstalledComponentTree.parentsFirst(held).forEach { install.installedComponents.insert(it) }
        install.installedComponents.insert(
            installedComponentOf(
                "c0", assetId = "x2", name = "Example Membrane Housing", composition = listOf(compositionEntryOf("e0", "s1")),
            ),
        )

        install.replace.run(archiveOf(upsData()))

        assertEquals(Ups.rows.sortedBy { it.id.value }, install.installedComponents.all())
        assertEquals(setOf("e1", "e2"), install.installedComponents.entries.keys)
        assertEquals(1, install.uow.commits)
    }

    /**
     * The twin of `BackupUseCasesTest`'s "a failed insert rolls the whole import back", for the installed components:
     * a restore that fails after their write — at the file's maintenance group, the next write — leaves this
     * install's rows and entries exactly as they were. The rollback takes back both the CASCADE that wiped them and
     * the file's rows written in their place.
     */
    @Test
    fun aRestoreFailingAfterTheComponentWriteRollsTheRowsBack() = runBlocking<Unit> {
        val install = BackupInstall()
        install.assets.upsert(Ups.asset)
        listOf(Ups.battery, Ups.strap).forEach { install.supplyItems.upsert(it) }
        install.installedComponents.insert(Ups.tray.copy(notes = "Example earlier note"))
        install.installedComponents.insert(Ups.pack.copy(composition = listOf(compositionEntryOf("e-held", "s1", 2.0))))
        val before = install.installedComponents.all()
        val archive = upsData().copy(maintenanceGroups = listOf(groupOf("g1", name = "Example Service Run").toDto()))
        install.groups.failOnUpsert = 1

        assertFailsWith<RiggedFailure> { install.replace.run(archiveOf(archive)) }

        assertEquals(before, install.installedComponents.all())
        assertEquals(setOf("e-held"), install.installedComponents.entries.keys)
        assertEquals(1, install.uow.rollbacks)
        assertEquals(0, install.uow.commits)
    }
}
