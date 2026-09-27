package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.toDomain
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.testing.BackupInstall
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

        assertEquals(11, decoded.manifest.formatVersion)   // this build's export: format 11 since #79
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
}
