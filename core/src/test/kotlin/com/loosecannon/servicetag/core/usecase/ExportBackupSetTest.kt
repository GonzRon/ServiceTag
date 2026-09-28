package com.loosecannon.servicetag.core.usecase

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

        assertEquals(13, decoded.manifest.formatVersion)   // this build's export: format 13 since #72
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
}
