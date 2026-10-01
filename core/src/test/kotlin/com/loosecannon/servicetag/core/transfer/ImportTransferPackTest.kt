package com.loosecannon.servicetag.core.transfer

import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.merge.MergeReason
import com.loosecannon.servicetag.core.merge.MergeTable
import com.loosecannon.servicetag.core.merge.MergeVerdict
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentSource
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.testing.activationOf
import com.loosecannon.servicetag.core.testing.assetSupplyOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.specificationOf
import com.loosecannon.servicetag.core.testing.supplyItemOf
import com.loosecannon.servicetag.core.transfer.TransferFixtures.ANODE
import com.loosecannon.servicetag.core.transfer.TransferFixtures.HEATER
import com.loosecannon.servicetag.core.transfer.TransferPackTesting.Raw
import com.loosecannon.servicetag.core.transfer.TransferPackTesting.entriesOf
import com.loosecannon.servicetag.core.transfer.TransferPackTesting.zipOf
import com.loosecannon.servicetag.core.usecase.PackDuplicate
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
