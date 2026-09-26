package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.archiveOf
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
}
