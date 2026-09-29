package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.TransferredGraphEntangled
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.transferOf
import com.loosecannon.servicetag.core.transfer.EntangledRef
import com.loosecannon.servicetag.core.transfer.TransferFixtures
import kotlin.test.assertFailsWith
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.toDomain
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.caseEntryOf
import com.loosecannon.servicetag.core.testing.caseOf
import com.loosecannon.servicetag.core.testing.loanOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * #74 (C11) — the export writes the owner's categories: **every** row, the ones no asset uses
 * included, because a category outlives the last asset that used it and a backup that dropped the
 * unused ones would quietly undo that on restore.
 */
class ExportBackupSetTest {

    @Test
    fun everyCategoryRowIsExportedUsedOrNot() = runBlocking<Unit> {
        val install = BackupInstall()
        install.assets.upsert(plainAssetOf("a1", "Compressor").copy(category = "Appliance"))
        val rows = listOf(
            AssetCategory("appliance", "Appliance", 100L, 150L),
            AssetCategory("spare parts", "Spare parts", 90L, 90L),
        )
        rows.forEach { install.categories.upsert(it) }

        val decoded = BackupCodec.decode(install.export.run().data)

        assertEquals(15, decoded.manifest.formatVersion)   // this build's export: format 15 since #86
        assertEquals(rows, decoded.data.assetCategories.map { it.toDomain() })
        assertEquals(2, decoded.manifest.counts["assetCategories"])
    }

    /** No row of the owner's own, no list entry: the built-ins are compiled, never exported. */
    @Test
    fun anInstallWithOnlyBuiltInsExportsNoCategories() = runBlocking<Unit> {
        val install = BackupInstall()
        install.assets.upsert(plainAssetOf("h1").copy(category = "Hot tub"))

        val decoded = BackupCodec.decode(install.export.run().data)

        assertEquals(emptyList(), decoded.data.assetCategories)
        assertEquals(0, decoded.manifest.counts["assetCategories"])
    }

    /**
     * #79 (C18): the warranty reminder's lead leaves with its asset and lands with it — by a replace,
     * and by a merge into an install that has neither asset — and this install's own export re-plans
     * IDENTICAL against it. An unset lead travels as none.
     */
    @Test
    fun theWarrantyReminderLeadTravelsWithItsAsset() = runBlocking<Unit> {
        val source = BackupInstall()
        val led = plainAssetOf("h1", "Example Heater").copy(warrantyExpiresOn = "2027-03-01", warrantyReminderLeadDays = 30)
        val plain = plainAssetOf("h2", "Example Heater two")
        source.assets.upsert(led)
        source.assets.upsert(plain)
        val bytes = source.export.run().data

        val replaced = BackupInstall()
        replaced.replace.run(bytes)
        assertEquals(listOf(led, plain), replaced.assets.all().sortedBy { it.id.value })

        val merged = BackupInstall()
        merged.apply.run(merged.build.run(bytes))
        assertEquals(listOf(30, null), merged.assets.all().sortedBy { it.id.value }.map { it.warrantyReminderLeadDays })

        val again = source.build.run(bytes)
        assertEquals(true, again.applicable)
        assertEquals(emptyList(), again.writes.assets, "IDENTICAL against the phone it came from")
    }

    /**
     * #79 (C18, C19): a case and its timeline leave with their asset — every header, every entry — and
     * land with it, field for field and the soft links dangling as they were, by a replace and by a
     * merge into an install without them; and this install's own export re-plans IDENTICAL.
     */
    @Test
    fun casesAndTheirEntriesTravelWithTheirAsset() = runBlocking<Unit> {
        val source = BackupInstall()
        source.assets.upsert(plainAssetOf("a1", "Example Heater"))
        val cases = listOf(caseOf("c1", status = CaseStatus.CLOSED, closedOn = "2026-09-24"), caseOf("c2"))
        val entries = listOf(
            caseEntryOf("n1"),
            caseEntryOf("n2", status = CaseStatus.CLOSED, note = "Repaired", occurredOn = "2026-09-24"),
            caseEntryOf("n3", caseId = "c2", occurredTime = null),
        )
        cases.forEach { source.serviceCases.upsert(it) }
        entries.forEach { source.caseEntries.insert(it) }
        val bytes = source.export.run().data

        val decoded = BackupCodec.decode(bytes)
        assertEquals(cases, decoded.data.serviceCases.map { it.toDomain() })
        assertEquals(entries, decoded.data.serviceCaseEntries.map { it.toDomain() })
        assertEquals(2 to 3, decoded.manifest.counts["serviceCases"] to decoded.manifest.counts["serviceCaseEntries"])

        val replaced = BackupInstall()
        replaced.replace.run(bytes)
        assertEquals(cases, replaced.serviceCases.all())
        val timeline = entries.sortedWith(compareBy({ it.occurredOn }, { it.occurredTime }, { it.createdAt }))
        assertEquals(timeline, replaced.caseEntries.all())

        val merged = BackupInstall()
        merged.apply.run(merged.build.run(bytes))
        assertEquals(cases to timeline, merged.serviceCases.all() to merged.caseEntries.all(), "by a merge into an install without them")

        val again = source.build.run(bytes)
        assertEquals(true, again.applicable)
        assertEquals(emptyList(), again.writes.serviceCases + again.writes.caseEntries, "IDENTICAL against the phone it came from")
    }

    /**
     * #72 (C6, C7): a loan leaves with its asset — the open one and the returned history, the contact link
     * beside the name snapshot, a name-only loan's null link as it is — and lands with it, field for
     * field, by a replace and by a merge into an install without them; and this install's own export
     * re-plans IDENTICAL.
     */
    @Test
    fun loansTravelWithTheirAsset() = runBlocking<Unit> {
        val source = BackupInstall()
        source.assets.upsert(plainAssetOf("a1", "Example Drill"))
        val loans = listOf(
            loanOf("l1", lentOn = "2026-08-01", returnedOn = "2026-08-02"),
            loanOf("l2", contactLookupUri = null, borrowerName = "Example Rentals Ltd"),
        )
        loans.forEach { source.loans.upsert(it) }
        val bytes = source.export.run().data

        val decoded = BackupCodec.decode(bytes)
        assertEquals(loans, decoded.data.assetLoans.map { it.toDomain() })
        assertEquals(2, decoded.manifest.counts["assetLoans"])

        val replaced = BackupInstall()
        replaced.replace.run(bytes)
        assertEquals(loans, replaced.loans.all(), "by a replace")

        val merged = BackupInstall()
        merged.apply.run(merged.build.run(bytes))
        assertEquals(loans, merged.loans.all(), "by a merge into an install without them")

        val again = source.build.run(bytes)
        assertEquals(true, again.applicable)
        assertEquals(emptyList(), again.writes.loans, "IDENTICAL against the phone it came from")
    }

    // --- #77 (C9; AC 11–13): an ordinary backup never carries a transferred graph -------------------

    /** The fixtures' estate with the heater and its anode held here: an OUT each, as marking appends. */
    private suspend fun heldEstate(): BackupInstall {
        val install = BackupInstall()
        TransferFixtures.seed(install)
        install.transfers.append(transferOf("r1", assetId = TransferFixtures.HEATER, packId = "pack-q"))
        install.transfers.append(transferOf("r2", assetId = TransferFixtures.ANODE, packId = "pack-q"))
        return install
    }

    /**
     * The held assets, everything they own, the group wholly theirs with its schedule and closure, their tags,
     * the returned loan, the 2.6 link and its tag: none of it leaves. What stays decodes, the unrelated
     * compressor and the archived opener included, and the empty group is still here.
     */
    @Test
    fun aHeldGraphIsNotExportedAndDecodes() = runBlocking<Unit> {
        val decoded = BackupCodec.decode(heldEstate().export.run().data)

        val data = decoded.data
        assertEquals(listOf("g1", "x1"), data.assets.map { it.id })
        assertEquals(listOf("t3", "t5"), data.nfcTags.map { it.id })
        assertEquals(emptyList(), data.externalLinks)
        assertEquals(listOf("G0"), data.maintenanceGroups.map { it.id })
        assertEquals(listOf("s2"), data.maintenanceSchedules.map { it.id })
        assertEquals(listOf("cl3"), data.occurrenceClosures.map { it.id })
        assertEquals(listOf("e3"), data.assetEvents.map { it.id })
        assertEquals(listOf("d2"), data.measurementDefinitions.map { it.id })
        assertEquals(emptyList(), data.eventProfiles + data.assetReferences + data.seasonActivations + data.assetConditions)
        assertEquals(listOf("hs2"), data.healthSubjects.map { it.id })
        assertEquals(listOf("sc2") to listOf("n2"), data.serviceCases.map { it.id } to data.serviceCaseEntries.map { it.id })
        assertEquals(listOf("l2"), data.assetLoans.map { it.id }, "the heater's returned loan stays with the sender only")
        assertEquals(TransferFixtures.categories.map { it.key }, data.assetCategories.map { it.key }, "categories always stay")
    }

    /** MJ-1: the plan comes from what is exported, so the artifacts archive names no held document. */
    @Test
    fun theArtifactsPlanNamesNoHeldAttachment() = runBlocking<Unit> {
        val set = heldEstate().export.run()

        assertEquals(listOf("at3"), set.plan.entries.map { it.attachmentId.value })
        assertEquals(listOf("at3"), BackupCodec.decode(set.data).data.attachments.map { it.id })
    }

    @Test
    fun artifactCountEqualsThePlan() = runBlocking<Unit> {
        val set = heldEstate().export.run()
        val manifest = BackupCodec.decode(set.data).manifest

        assertEquals(set.plan.entries.size, manifest.artifactCount)
        assertEquals(set.plan.entries.sumOf { it.sizeBytes }, manifest.artifactBytes)
    }

    /** Every record leaves, the held assets' OUTs included: they are what a restore needs to stay correct. */
    @Test
    fun recordsAreExported() = runBlocking<Unit> {
        val install = heldEstate()
        install.transfers.append(transferOf("r0", assetId = "a9", kind = TransferKind.IN, packId = "pack-o"))

        val decoded = BackupCodec.decode(install.export.run().data)

        assertEquals(install.transfers.all(), decoded.data.transferRecords.map { it.toDomain() })
        assertEquals(3, decoded.manifest.counts["transferRecords"])
    }

    /** AC 11: archive is not transfer — an archived, retired asset nobody transferred leaves as it always did. */
    @Test
    fun archivedButNotHeldStillExports() = runBlocking<Unit> {
        val install = BackupInstall()
        TransferFixtures.seed(install)
        check(install.assets.get(AssetId(TransferFixtures.OPENER))!!.status == AssetStatus.ARCHIVED)
        install.assets.upsert(install.assets.get(AssetId(TransferFixtures.COMPRESSOR))!!.copy(status = AssetStatus.ARCHIVED))

        val decoded = BackupCodec.decode(install.export.run().data)

        assertEquals(listOf("g1", "h1", "h2", "x1"), decoded.data.assets.map { it.id })
        assertEquals(listOf("at1", "at2", "at3"), decoded.data.attachments.map { it.id })
    }

    /**
     * C3's `Entangled` stops the export before any byte exists (P77-58, mapped by the Backup screen): a
     * staying row — the compressor's event, set by hand onto the heater's schedule — names a held row.
     */
    @Test
    fun entangledFailsBeforeWriting() = runBlocking<Unit> {
        val install = heldEstate()
        install.events.upsert(
            completionOf("e9", "2026-05-01", "2026-05-01", assetId = TransferFixtures.COMPRESSOR, scheduleId = "s1"),
        )

        val refusal = assertFailsWith<TransferredGraphEntangled> { install.export.run() }

        assertEquals(listOf(EntangledRef("assetEvents", "e9", "maintenanceSchedules", "s1")), refusal.refs)
    }
}
