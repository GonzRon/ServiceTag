package com.loosecannon.servicetag.core.transfer

import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.merge.mergeSnapshotOf
import com.loosecannon.servicetag.core.merge.MergeReason
import com.loosecannon.servicetag.core.merge.MergeTable
import com.loosecannon.servicetag.core.merge.MergeVerdict
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentLocator
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.AttachmentSource
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentStore
import com.loosecannon.servicetag.core.testing.activationOf
import com.loosecannon.servicetag.core.testing.assetSupplyOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.compositionEntryOf
import com.loosecannon.servicetag.core.testing.installedComponentOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.specificationOf
import com.loosecannon.servicetag.core.testing.supplyItemOf
import com.loosecannon.servicetag.core.transfer.TransferFixtures.ANODE
import com.loosecannon.servicetag.core.transfer.TransferFixtures.HEATER
import com.loosecannon.servicetag.core.transfer.TransferPackTesting.Raw
import com.loosecannon.servicetag.core.transfer.TransferPackTesting.entriesOf
import com.loosecannon.servicetag.core.transfer.TransferPackTesting.zipOf
import com.loosecannon.servicetag.core.usecase.PackDuplicate
import com.loosecannon.servicetag.core.usecase.ReturnScope
import com.loosecannon.servicetag.core.usecase.TransferImportOutcome
import com.loosecannon.servicetag.core.usecase.TransferImportPreview
import com.loosecannon.servicetag.core.usecase.TransferImportResult
import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * #77 (B3; C14, rows 21–23) — a recipient imports a Transfer Pack additively, through the shipped merge core and the
 * write guard: the preview writes nothing and never lets the pack's bytes mask what the folder holds; Import stages
 * only absent locators, verifies every byte, applies inside one write with an IN record per pack asset, and on any
 * refusal or failure sweeps exactly what it staged. The sender is the fixtures' estate; the heater (with its anode and
 * its whole group) is the pack. Names and tag keys are fictional.
 */
class ImportTransferPackTest {

    /** #69 (row 19): the ids of the files [returnWithLocalFiles] lays down. */
    private val localFiles = setOf("fc1", "fc2", "fcx", "fs1")

    private suspend fun sender(): TransferInstall = TransferInstall("set-sender").also { TransferFixtures.seed(it.raw) }

    private suspend fun heaterPack(from: TransferInstall? = null): SealedPack =
        (from ?: sender()).pack("pack-0001", HEATER, note = "Example handover note")

    /** A recipient with one unrelated asset of its own: a tag, a completion and a document. */
    private suspend fun recipient(): TransferInstall = TransferInstall("set-recipient").also { r ->
        r.raw.assets.upsert(plainAssetOf("r1", "Sample Pump"))
        r.raw.tags.upsert(TransferFixtures.tagOf("rt1", "TEST-0100", TagTarget.AssetTarget(AssetId("r1"))))
        r.raw.events.upsert(completionOf("re1", "2026-05-01", null, assetId = "r1").copy(scheduleId = null))
        r.raw.activations.insert(activationOf("rsa1", "r1"))
        r.raw.storage.store.files["assets/r1/other.pdf"] = "Sample pump leaflet".toByteArray()
    }

    // --- row 21: preview -------------------------------------------------------------------------------------------

    @Test
    fun aDisjointPackPlansEveryInsertIncludingBytes() = runTest {
        val pack = heaterPack()
        val ready = recipient().ready(pack.bytes)

        assertEquals(TransferImportOutcome.READY, ready.outcome)
        assertTrue(ready.plan.decisions.all { it.verdict == MergeVerdict.INSERT }, "${ready.plan.decisions.filter { it.verdict != MergeVerdict.INSERT }}")
        assertEquals(
            listOf("at1", "at2"),
            ready.plan.decisions.filter { it.table == MergeTable.ATTACHMENTS }.map { it.id },
            "both documents plan INSERT on the pack's bytes",
        )
        assertEquals(2, ready.plan.report().transfers.insert, "one IN per pack asset")
        assertEquals(emptyList(), ready.returning)
        assertEquals(emptyList(), ready.refused)
    }

    @Test
    fun thePreviewWritesNothing() = runTest {
        val pack = heaterPack()
        val r = recipient()
        val before = r.data()
        val files = LinkedHashMap(r.raw.storage.store.files)
        val commits = r.raw.uow.commits

        r.ready(pack.bytes)

        assertEquals(before, r.data())
        assertEquals(files, r.raw.storage.store.files, "no byte was staged by the preview")
        assertEquals(emptyList(), r.raw.transfers.all())
        assertEquals(commits, r.raw.uow.commits)
    }

    @Test
    fun theOverlayNeverMasksAPresentLocator() = runTest {
        val pack = heaterPack()
        val r = recipient()
        r.raw.storage.store.files["assets/h1/at1.pdf"] = "Other bytes at the same place".toByteArray()

        val ready = r.ready(pack.bytes)

        val at1 = ready.plan.decisions.single { it.table == MergeTable.ATTACHMENTS && it.id == "at1" }
        assertEquals(MergeVerdict.CONFLICT, at1.verdict)
        assertEquals(MergeReason.ATTACHMENT_BYTES_DIFFER, at1.reason)
        assertEquals(TransferImportOutcome.CONFLICTS, ready.outcome)
    }

    @Test
    fun artifactsWithoutAFolderAreRefused() = runTest {
        val pack = heaterPack()
        val r = recipient()
        r.raw.storage.state = StoreState.NotConfigured
        val before = r.data()

        assertIs<TransferImportPreview.NoAttachmentFolder>(r.preview(pack.bytes))
        assertEquals(before, r.data())
    }

    @Test
    fun allIdenticalIsAlreadyHere() = runTest {
        val pack = heaterPack()
        val r = recipient()
        assertIs<TransferImportResult.Imported>(r.import(pack.bytes))

        val again = r.ready(pack.bytes)

        assertEquals(TransferImportOutcome.ALREADY_HERE, again.outcome)
        assertTrue(again.plan.decisions.all { it.verdict == MergeVerdict.IDENTICAL }, "${again.plan.decisions}")
    }

    // --- row 22: stage, apply, sweep -------------------------------------------------------------------------------

    @Test
    fun stagesThenMergesAndWritesInRecords() = runTest {
        val s = sender()
        // #85 (C5): the heater's manual was saved from a reference, and its source travels in the pack's archive
        val saved = s.raw.attachments.get(AttachmentId("at1"))!!.copy(
            source = AttachmentSource(
                uri = "https://manuals.example.invalid/heater/manual.pdf",
                resolvedUri = "https://cdn.example.invalid/heater/manual.pdf",
                retrievedAt = 1_758_900_000_000L,
                name = "Example Water Heater manual",
            ),
        )
        s.raw.attachments.upsert(saved)
        val pack = heaterPack(s)
        val r = recipient()

        val result = assertIs<TransferImportResult.Imported>(r.import(pack.bytes))

        assertEquals("pack-0001", result.manifest.packId)
        val sent = s.data()
        val here = r.data()
        assertEquals(sent.assets.filter { it.id in setOf(HEATER, ANODE) }, here.assets.filter { it.id in setOf(HEATER, ANODE) })
        assertEquals(TransferFixtures.bytesByLocator.getValue("assets/h1/at1.pdf").toList(), r.raw.storage.store.files.getValue("assets/h1/at1.pdf").toList())
        assertEquals(TransferFixtures.bytesByLocator.getValue("events/e1/at2.jpg").toList(), r.raw.storage.store.files.getValue("events/e1/at2.jpg").toList())
        assertEquals(saved, r.raw.attachments.get(AttachmentId("at1")), "the sourced document keeps its source")
        val ins = r.raw.transfers.all()
        assertEquals(listOf(HEATER, ANODE), ins.map { it.assetId.value }.sorted())
        assertTrue(ins.all { it.kind == TransferKind.IN && it.packId == "pack-0001" && it.lineage.isEmpty() }, "$ins")
        assertTrue(ins.all { it.packSha256 == pack.created.packSha256 && it.note == "Example handover note" }, "$ins")
        assertEquals(setOf("Example Water Heater", "Example Anode Rod"), ins.map { it.nameSnapshot }.toSet())
        assertEquals(emptySet(), r.raw.transfers.heldIds(), "an import holds nothing")
        assertEquals(1, r.rebuilds)
    }

    /**
     * #91 (C8, row 18): the pack is the codec's archive and its import plans through the same merge, so a held asset's
     * web link keeps the role the owner gave it — no code of its own, in the shape of #85's source check above.
     */
    @Test
    fun aHeldAssetsReferenceCarriesItsRoleThroughThePack() = runTest {
        val s = sender()
        val manual = s.raw.references.get(ReferenceId("r1"))!!.copy(role = DocumentRole.USER_MANUAL)
        s.raw.references.upsert(manual)
        val pack = heaterPack(s)
        s.mark(pack)
        assertTrue(AssetId(HEATER) in s.raw.transfers.heldIds(), "the heater is held once it is marked transferred out")
        assertEquals(manual, s.raw.references.get(ReferenceId("r1")), "holding it leaves the sender's row as it was")
        val r = recipient()

        assertIs<TransferImportResult.Imported>(r.import(pack.bytes))

        assertEquals(manual, r.raw.references.get(ReferenceId("r1")), "the reference arrives with its role")
        assertEquals(DocumentRole.USER_MANUAL, r.raw.references.get(ReferenceId("r1"))?.role)
    }

    /**
     * #15 (C13, row 29): a held asset's Supplies rows travel with it, and so does every SupplyItem a carried row names —
     * applicability or a material line — specifications and links as they were; an item no carried row names stays home.
     */
    @Test
    fun aHeldAssetsSuppliesAndLinksArriveThroughThePack() = runTest {
        val s = suppliedSender()
        val pack = heaterPack(s)
        s.mark(pack)
        val r = recipient()

        assertIs<TransferImportResult.Imported>(r.import(pack.bytes))

        assertEquals(s.raw.supplyItems.all().filter { it.id.value in setOf("s1", "s2") }, r.raw.supplyItems.all(), "s3 stays home")
        assertEquals(s.raw.assetSupplies.all().filter { it.id in setOf("as1", "as2") }, r.raw.assetSupplies.all())
        assertEquals(SupplyId("s2"), r.raw.profiles.get(ProfileId("p1"))!!.consumables.single().supplyId)
        assertEquals(SupplyId("s1"), r.raw.events.get(EventId("e1"))!!.consumables.single().supplyId)
    }

    /**
     * #15 (C13, C-4; row 67): the heater comes back with one Supplies row re-roled on the borrowing phone. The return
     * plans its rows on a snapshot without the heater's stale ones, so the apply succeeds and every row lands — the
     * re-roled one as the pack carries it, the other unchanged — and every SupplyItem here stays.
     */
    @Test
    fun aReturningPackWithAReRoledSuppliesRowAppliesAndEveryRowLands() = runTest {
        val s = suppliedSender()
        val q1 = s.pack("pack-q1", HEATER)
        s.mark(q1)
        val r = TransferInstall("set-recipient")
        assertIs<TransferImportResult.Imported>(r.import(q1.bytes))
        val row = r.raw.assetSupplies.get("as1")!!
        r.raw.assetSupplies.update(row.copy(role = "Spare anode kit", updatedAt = IMPORT_NOW + 1))
        val q2 = r.pack("pack-q2", HEATER)
        val items = s.raw.supplyItems.all()

        val ready = s.ready(q2.bytes)
        assertEquals(TransferImportOutcome.READY, ready.outcome, "${ready.plan.conflicts}")
        assertIs<TransferImportResult.Imported>(s.importer.import(ready) { q2.bytes.inputStream() })

        val back = s.raw.assetSupplies.all()
        assertEquals(r.raw.assetSupplies.all(), back.filter { it.assetId.value in setOf(HEATER, ANODE) })
        assertEquals("Spare anode kit", s.raw.assetSupplies.get("as1")!!.role)
        assertEquals(listOf("as1", "as2", "as3"), back.map { it.id }, "no row lost; the compressor's untouched")
        assertEquals(items, s.raw.supplyItems.all(), "every item here stays, s3 included")
    }

    /**
     * #47 (C13, row 30; #15's C-4 lesson): the heater comes back after the borrowing phone replaced its position 1 (the
     * row closed, a successor naming it inserted) and recomposed its pack (one entry's quantity changed, the other
     * dropped). The return plans the pack's rows on a snapshot without the heater's stale ones, so the apply succeeds:
     * the delete takes the old rows and entries, and every row lands as the pack carries it — the closed one included —
     * while the compressor's row, its entry and every SupplyItem here stay.
     */
    @Test
    fun aReturningPackWithARowReplacedAndAPackRecomposedOnTheBorrowingPhoneLands() = runTest {
        val s = fittedSender()
        val q1 = s.pack("pack-q1", HEATER)
        s.mark(q1)
        val r = TransferInstall("set-recipient")
        assertIs<TransferImportResult.Imported>(r.import(q1.bytes))
        val position = r.raw.installedComponents.get(InstalledComponentId("c2"))!!
        r.raw.installedComponents.update(position.copy(removedOn = "2026-09-20", updatedAt = IMPORT_NOW + 1))
        r.raw.installedComponents.insert(
            position.copy(
                id = InstalledComponentId("c5"), serialOrLot = "LOT-EX-0005", installedOn = "2026-09-20", removedOn = null,
                replacesId = position.id, createdAt = IMPORT_NOW + 1, updatedAt = IMPORT_NOW + 1,
            ),
        )
        val batteryPack = r.raw.installedComponents.get(InstalledComponentId("c3"))!!
        r.raw.installedComponents.update(
            batteryPack.copy(composition = listOf(batteryPack.composition.first().copy(quantity = 3.0)), updatedAt = IMPORT_NOW + 1),
        )
        val q2 = r.pack("pack-q2", HEATER)
        val items = s.raw.supplyItems.all()

        val ready = s.ready(q2.bytes)
        assertEquals(TransferImportOutcome.READY, ready.outcome, "${ready.plan.conflicts}")
        assertIs<TransferImportResult.Imported>(s.importer.import(ready) { q2.bytes.inputStream() })

        val back = s.raw.installedComponents.all()
        assertEquals(r.raw.installedComponents.all(), back.filter { it.assetId.value == HEATER }, "every row as the pack carries it")
        assertEquals(listOf("c1", "c2", "c3", "c5", "cx"), back.map { it.id.value }, "no row lost; the compressor's untouched")
        assertEquals("2026-09-20", s.raw.installedComponents.get(InstalledComponentId("c2"))!!.removedOn, "the closed row lands closed")
        assertEquals(setOf("k1", "kx"), s.raw.installedComponents.entries.keys, "the dropped entry went with the delete")
        assertEquals(items, s.raw.supplyItems.all(), "every item here stays")
    }

    // ---- #69 (C5, H2; row 19): a return takes a returning asset's component files and sweeps their bytes -------------

    /**
     * The heater comes back to [fittedSender]. Its component files are local only, never in either pack (N-15): they
     * leave the scoped snapshot with the heater's components, the asset delete's CASCADE takes the rows, and their
     * bytes are swept, while the staying compressor's component keeps its file.
     */
    @Test
    fun aReturningAssetsComponentFilesLeaveTheSnapshotAndTheirLocatorsAreSwept() = runTest {
        val (s, q2) = returnWithLocalFiles()
        val ready = s.ready(q2.bytes)
        assertEquals(TransferImportOutcome.READY, ready.outcome, "${ready.plan.conflicts}")

        val scope = returnScopeOf(s, HEATER, ANODE)
        assertEquals(setOf("fs1", "fcx"), scope.snapshot.attachments.map { it.id.value }.filter { it in localFiles }.toSet())
        assertEquals(
            setOf("installed-components/c1/fc1.pdf", "installed-components/c2/fc2.pdf"),
            scope.locators.filter { it.startsWith("installed-components/") || it.startsWith("supply-items/") }.toSet(),
        )
        assertIs<TransferImportResult.Imported>(s.importer.import(ready) { q2.bytes.inputStream() })

        assertEquals(setOf("fs1", "fcx"), localFiles.filter { s.raw.attachments.get(AttachmentId(it)) != null }.toSet())
        assertTrue("installed-components/c1/fc1.pdf" !in s.raw.storage.store.files, "the tray's file's bytes were swept")
        assertTrue("installed-components/c2/fc2.pdf" !in s.raw.storage.store.files, "the position's file's bytes were swept")
        assertTrue("installed-components/cx/fcx.pdf" in s.raw.storage.store.files, "the compressor's component keeps its bytes")
        assertEquals(listOf("c1", "c2", "c3", "cx"), s.raw.installedComponents.all().map { it.id.value }, "the pack's rows landed")
    }

    /** A SupplyItem is global and never returns with an asset: its file stays in the snapshot, its row and bytes here. */
    @Test
    fun aSupplyItemsFileStays() = runTest {
        val (s, q2) = returnWithLocalFiles()
        val file = s.raw.attachments.get(AttachmentId("fs1"))!!

        assertTrue(file in returnScopeOf(s, HEATER, ANODE).snapshot.attachments, "the scoped snapshot keeps it")
        assertIs<TransferImportResult.Imported>(s.import(q2.bytes))

        assertEquals(file, s.raw.attachments.get(AttachmentId("fs1")))
        assertTrue("supply-items/s1/fs1.pdf" in s.raw.storage.store.files, "its bytes stay")
    }

    // ---- #69 (C13; row 25): a return takes a returning asset's component links ------------------------------------

    /**
     * The heater comes back to [fittedSender]. Its tray's link is local only, never in either pack (laid down after
     * `pack-q1` was sealed): it leaves the scoped snapshot with the heater's components, the asset delete's CASCADE takes
     * the row and the return lands, while the compressor's component's link and the battery SupplyItem's stay.
     */
    @Test
    fun aReturningAssetsComponentLinkLeavesTheSnapshotAndTheReturnLands() = runTest {
        val s = fittedSender()
        val q1 = s.pack("pack-q1", HEATER)
        s.mark(q1)
        val links = mapOf(
            "rc1" to ReferenceOwner.OfInstalledComponent(InstalledComponentId("c1")),
            "rcx" to ReferenceOwner.OfInstalledComponent(InstalledComponentId("cx")),
            "rs1" to ReferenceOwner.OfSupplyItem(SupplyId("s1")),
        )
        links.forEach { (id, owner) ->
            s.raw.references.upsert(
                AssetReference(
                    id = ReferenceId(id), owner = owner, kind = ReferenceKind.WEB_URL, uri = "https://example.invalid/$id",
                    displayName = "Example $id page", description = "", scheme = "https", createdAt = 100L, updatedAt = 100L,
                ),
            )
        }
        val r = TransferInstall("set-recipient")
        assertIs<TransferImportResult.Imported>(r.import(q1.bytes))
        val q2 = r.pack("pack-q2", HEATER)

        val ready = s.ready(q2.bytes)
        assertEquals(TransferImportOutcome.READY, ready.outcome, "${ready.plan.conflicts}")
        val scoped = returnScopeOf(s, HEATER, ANODE).snapshot.references.map { it.id.value }
        assertEquals(setOf("rcx", "rs1"), scoped.filter { it in links }.toSet())
        assertIs<TransferImportResult.Imported>(s.importer.import(ready) { q2.bytes.inputStream() })

        assertEquals(setOf("rcx", "rs1"), links.keys.filter { s.raw.references.get(ReferenceId(it)) != null }.toSet())
        assertEquals(listOf("c1", "c2", "c3", "cx"), s.raw.installedComponents.all().map { it.id.value }, "the pack's rows landed")
    }

    /**
     * [fittedSender] with the heater out in `pack-q1` and back from the borrowing phone in `pack-q2`. Its files are
     * laid down raw, with bytes, **after** `pack-q1` was sealed, so neither pack carries them: one on the heater's tray
     * (c1), one on its position (c2), one on the compressor's housing (cx) and one on the battery SupplyItem (s1).
     */
    private suspend fun returnWithLocalFiles(): Pair<TransferInstall, SealedPack> {
        val s = fittedSender()
        val q1 = s.pack("pack-q1", HEATER)
        s.mark(q1)
        listOf(
            "fc1" to AttachmentOwner.OfInstalledComponent(InstalledComponentId("c1")),
            "fc2" to AttachmentOwner.OfInstalledComponent(InstalledComponentId("c2")),
            "fcx" to AttachmentOwner.OfInstalledComponent(InstalledComponentId("cx")),
            "fs1" to AttachmentOwner.OfSupplyItem(SupplyId("s1")),
        ).forEach { (id, owner) ->
            val bytes = "Example $id sheet".toByteArray()
            val locator = "${AttachmentLocator.dirFor(owner)}/$id.pdf"
            s.raw.storage.store.files[locator] = bytes
            s.raw.attachments.upsert(
                Attachment(
                    id = AttachmentId(id), owner = owner, kind = AttachmentKind.DOCUMENT, displayName = "$id file",
                    mimeType = "application/pdf", sizeBytes = bytes.size.toLong(), sha256 = InMemoryAttachmentStore.sha256Hex(bytes),
                    storageLocator = locator, capturedOn = null, createdAt = 100L, updatedAt = 100L,
                ),
            )
        }
        val r = TransferInstall("set-recipient")
        assertIs<TransferImportResult.Imported>(r.import(q1.bytes))
        return s to r.pack("pack-q2", HEATER)
    }

    /** The return's scope over [s]'s stores now, read as the apply reads it. */
    private suspend fun returnScopeOf(s: TransferInstall, vararg returning: String): ReturnScope = with(s.raw) {
        uow.read {
            ReturnScope.of(
                mergeSnapshotOf(
                    assets, groups, tags, links, definitions, profiles, schedules, closures, events, attachments,
                    references, activations, conditions, subjects, categories, serviceCases, caseEntries, loans, transfers,
                    successions, supplyItems, assetSupplies, installedComponents, emptyMap(), true,
                ),
                returning.mapTo(HashSet(), ::AssetId),
            )
        }
    }

    /**
     * The sender, seeded, with two SupplyItems (fictional) and installed components: on the heater a tray (c1) holding
     * position 1 (c2, an s1) and a pack (c3) composed of 4 × s1 and 2 × s2; on the compressor a housing (cx) composed of
     * one s2.
     */
    private suspend fun fittedSender(): TransferInstall = sender().also { s ->
        s.raw.supplyItems.upsert(supplyItemOf("s1", "Example 12 V Battery"))
        s.raw.supplyItems.upsert(supplyItemOf("s2", "Example Terminal Strap"))
        listOf(
            installedComponentOf("c1", assetId = HEATER, name = "Example Battery Tray", installedOn = "2026-01-10"),
            installedComponentOf("c2", assetId = HEATER, name = "Position 1", parentId = "c1", supplyId = "s1", installedOn = "2026-01-10"),
            installedComponentOf(
                "c3", assetId = HEATER, name = "Example Battery Pack", parentId = "c1", sortOrder = 1,
                composition = listOf(compositionEntryOf("k1", "s1", 4.0, sortOrder = 0), compositionEntryOf("k2", "s2", 2.0, sortOrder = 1)),
            ),
            installedComponentOf("cx", assetId = "x1", name = "Example Intake Housing", composition = listOf(compositionEntryOf("kx", "s2", 1.0))),
        ).forEach { s.raw.installedComponents.insert(it) }
    }

    /**
     * The sender, seeded, with three SupplyItems (fictional): s1 the heater and its anode take, and the heater's
     * completion line names; s2 only the heater's quick-action line names; s3 only the compressor takes.
     */
    private suspend fun suppliedSender(): TransferInstall = sender().also { s ->
        s.raw.supplyItems.upsert(supplyItemOf("s1", "Example Anode Kit", listOf(specificationOf("sp1", "length", "Length", "40", "in"))))
        s.raw.supplyItems.upsert(supplyItemOf("s2", "Example Descaler"))
        s.raw.supplyItems.upsert(supplyItemOf("s3", "Example Intake Filter"))
        s.raw.assetSupplies.insert(assetSupplyOf("as1", HEATER, "s1", "Anode kit"))
        s.raw.assetSupplies.insert(assetSupplyOf("as2", ANODE, "s1", "Replacement"))
        s.raw.assetSupplies.insert(assetSupplyOf("as3", "x1", "s3", "Intake filter"))
        val quickAction = s.raw.profiles.get(ProfileId("p1"))!!
        s.raw.profiles.upsert(quickAction.copy(consumables = quickAction.consumables.map { it.copy(supplyId = SupplyId("s2")) }))
        val completion = s.raw.events.get(EventId("e1"))!!
        s.raw.events.upsert(completion.copy(consumables = completion.consumables.map { it.copy(supplyId = SupplyId("s1")) }))
    }

    /** A pack whose second document's bytes changed after the preview: the first, already staged, is swept too. */
    @Test
    fun aDigestMismatchSweepsWhatItWrote() = runTest {
        val pack = heaterPack()
        val r = recipient()
        val before = r.data()
        val files = LinkedHashMap(r.raw.storage.store.files)
        val tampered = tamperLastDocument(pack.bytes)

        val result = r.import(pack.bytes) { ByteArrayInputStream(tampered) }

        assertIs<TransferImportResult.Failed>(result)
        assertEquals(files.keys, r.raw.storage.store.files.keys, "both staged documents were swept")
        assertEquals(before, r.data())
        assertEquals(emptyList(), r.raw.transfers.all())
    }

    /**
     * A local tag claims a pack tag's payload between the preview and Import: the apply's fresh plan conflicts, and
     * only the document this import staged is swept — the one already present at its locator is left alone.
     */
    @Test
    fun aRefusedApplySweepsOnlyStaged() = runTest {
        val pack = heaterPack()
        val r = recipient()
        val present = TransferFixtures.bytesByLocator.getValue("assets/h1/at1.pdf").copyOf()
        r.raw.storage.store.files["assets/h1/at1.pdf"] = present
        val ready = r.ready(pack.bytes)
        r.raw.tags.upsert(TransferFixtures.tagOf("rt9", "TEST-0001", TagTarget.AssetTarget(AssetId("r1"))))
        val before = r.data()

        val result = r.importer.import(ready) { ByteArrayInputStream(pack.bytes) }

        assertIs<TransferImportResult.Conflicted>(result)
        assertSame(present, r.raw.storage.store.files["assets/h1/at1.pdf"], "a present locator is never swept")
        assertTrue("events/e1/at2.jpg" !in r.raw.storage.store.files, "the staged document was swept")
        assertTrue("assets/r1/other.pdf" in r.raw.storage.store.files)
        assertEquals(before, r.data())
    }

    @Test
    fun aPresentLocatorIsNeverOverwritten() = runTest {
        val pack = heaterPack()
        val r = recipient()
        val present = TransferFixtures.bytesByLocator.getValue("assets/h1/at1.pdf").copyOf()
        r.raw.storage.store.files["assets/h1/at1.pdf"] = present

        assertIs<TransferImportResult.Imported>(r.import(pack.bytes))

        assertSame(present, r.raw.storage.store.files["assets/h1/at1.pdf"], "the bytes already here were not rewritten")
        assertTrue("events/e1/at2.jpg" in r.raw.storage.store.files)
    }

    @Test
    fun importingTwiceIsIdentical() = runTest {
        val pack = heaterPack()
        val r = recipient()
        assertIs<TransferImportResult.Imported>(r.import(pack.bytes))
        val once = r.data()
        val records = r.raw.transfers.all()

        val again = r.ready(pack.bytes)

        assertEquals(TransferImportOutcome.ALREADY_HERE, again.outcome)
        assertEquals(false, again.importable)
        assertEquals(once, r.data())
        assertEquals(records, r.raw.transfers.all())
    }

    @Test
    fun aRecipientScanMakesTheSecondImportConflict() = runTest {
        val pack = heaterPack()
        val r = recipient()
        assertIs<TransferImportResult.Imported>(r.import(pack.bytes))
        val t1 = r.raw.tags.get(TagId("t1"))!!
        r.tags.upsert(t1.copy(lastScannedAt = IMPORT_NOW + 60_000, updatedAt = IMPORT_NOW + 60_000))

        val again = r.ready(pack.bytes)

        assertEquals(TransferImportOutcome.CONFLICTS, again.outcome)
        assertEquals(
            listOf("t1" to MergeReason.CONTENT_DIFFERS),
            again.plan.conflicts.map { it.id to it.reason },
        )
    }

    // --- row 23: recipient semantics -------------------------------------------------------------------------------

    @Test
    fun aTagBoundHereElsewhereRefusesNamingIt() = runTest {
        val pack = heaterPack()
        val r = recipient()
        r.raw.tags.upsert(TransferFixtures.tagOf("rt9", "TEST-0001", TagTarget.AssetTarget(AssetId("r1"))))

        val ready = r.ready(pack.bytes)

        assertEquals(TransferImportOutcome.CONFLICTS, ready.outcome)
        assertEquals(listOf("Sample Pump"), ready.tagsUsedHere, "P77-45 names the asset here that uses the tag")
    }

    @Test
    fun unrelatedRowsAreByteIdentical() = runTest {
        val pack = heaterPack()
        val r = recipient()
        val before = r.data()
        val leaflet = r.raw.storage.store.files.getValue("assets/r1/other.pdf")

        assertIs<TransferImportResult.Imported>(r.import(pack.bytes))

        val after = r.data()
        assertEquals(before.assets, after.assets.filter { it.id == "r1" })
        assertEquals(before.nfcTags, after.nfcTags.filter { it.id == "rt1" })
        assertEquals(before.assetEvents, after.assetEvents.filter { it.id == "re1" })
        assertEquals(before.seasonActivations, after.seasonActivations.filter { it.id == "rsa1" })
        assertSame(leaflet, r.raw.storage.store.files["assets/r1/other.pdf"])
    }

    @Test
    fun seasonConditionHealthArrive() = runTest {
        val s = sender()
        val pack = heaterPack(s)
        val r = recipient()

        assertIs<TransferImportResult.Imported>(r.import(pack.bytes))

        val here = r.data()
        val sent = s.data()
        assertEquals(sent.seasonActivations.filter { it.assetId == HEATER }, here.seasonActivations.filter { it.assetId == HEATER })
        assertEquals(sent.assetConditions.filter { it.assetId == HEATER }, here.assetConditions.filter { it.assetId == HEATER })
        assertEquals(sent.healthSubjects.filter { it.assetId == HEATER }, here.healthSubjects.filter { it.assetId == HEATER })
        val heater = here.assets.single { it.id == HEATER }
        assertEquals(30, heater.warrantyReminderLeadDays)
        assertEquals("hs1", heater.healthPrimarySubjectId)
    }

    @Test
    fun aDuplicateCandidateIsReported() = runTest {
        val s = sender()
        s.raw.assets.upsert(
            s.raw.assets.get(AssetId(HEATER))!!.copy(manufacturer = "Example Co", model = "EX-40", serialNumber = "SN-TEST-1"),
        )
        val pack = heaterPack(s)
        val r = recipient()
        r.raw.assets.upsert(
            plainAssetOf("r2", "Sample Heater").copy(manufacturer = "Example Co", model = "EX-40", serialNumber = "SN-TEST-1"),
        )

        val ready = r.ready(pack.bytes)

        assertEquals(listOf(PackDuplicate("Example Water Heater", "Sample Heater")), ready.duplicates)
        assertEquals(TransferImportOutcome.READY, ready.outcome, "a hint never blocks Import")
    }

    @Test
    fun tagsResolveToTheImportedAsset() = runTest {
        val pack = heaterPack()
        val r = recipient()

        assertIs<TransferImportResult.Imported>(r.import(pack.bytes))

        val tag = r.raw.tags.findByPayload(PayloadFormat.V1, "TEST-0001")!!
        assertEquals(TagId("t1"), tag.id)
        assertEquals(TagTarget.AssetTarget(AssetId(HEATER)), tag.target)
        assertEquals(TransferFixtures.tags.single { it.id == TagId("t1") }.toDto(), tag.toDto())
    }

    /** The same pack with the last document's bytes replaced — its manifests untouched. */
    private fun tamperLastDocument(pack: ByteArray): ByteArray {
        val outer = entriesOf(pack)
        val artifacts = outer.single { it.name == TransferPack.ARTIFACTS_ENTRY }
        val inner = entriesOf(artifacts.bytes)
        val last = inner.last()
        val tamperedInner = inner.dropLast(1) + Raw(last.name, "Tampered bytes".toByteArray())
        return zipOf(outer.map { if (it.name == TransferPack.ARTIFACTS_ENTRY) Raw(it.name, zipOf(tamperedInner)) else it })
    }
}
