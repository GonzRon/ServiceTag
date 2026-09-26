package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.CategoryRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.InMemoryCategoryRepository
import com.loosecannon.servicetag.core.testing.Rollbackable
import com.loosecannon.servicetag.core.testing.Witnessed
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.backupOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.usecase.ApplyBackupMergePlan
import com.loosecannon.servicetag.core.usecase.BuildBackupMergePlan
import com.loosecannon.servicetag.core.usecase.DeleteCategory
import com.loosecannon.servicetag.core.usecase.MergePlanStale
import com.loosecannon.servicetag.core.usecase.RenameCategory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * #74 (C13, R74-7) — the merge and the owner's categories. An archive row is an `INSERT`, an
 * `IDENTICAL` on key and display (timestamps never compared), or a `SKIPPED` — a built-in's key, or a
 * key held here under another spelling, the local spelling winning — and **never a conflict**. The
 * categories accepted assets need are **planned**: synthesised `INSERT` decisions, in the tallies,
 * the report and the fingerprint, with every accepted asset written in its canonical spelling and
 * its own `updatedAt`. The asset comparison reads `category` by key on both sides, so re-planning an
 * applied archive, or a pre-upgrade export against its own phone, is all `IDENTICAL` (AC 10).
 */
class MergePlannerCategoryTest {

    // --- fixture ---------------------------------------------------------------------------------

    private fun asset(id: String, category: String, createdAt: Long = 100L, updatedAt: Long = 200L): Asset =
        plainAssetOf(id, updatedAt = updatedAt).copy(category = category, createdAt = createdAt)

    private fun row(key: String, display: String, createdAt: Long = 10L, updatedAt: Long = 20L) =
        AssetCategory(key, display, createdAt, updatedAt)

    private fun data(assets: List<Asset> = emptyList(), categories: List<AssetCategory> = emptyList()) = BackupData(
        assets = assets.map { it.toDto() },
        nfcTags = emptyList(),
        externalLinks = emptyList(),
        assetCategories = categories.map { it.toDto() },
    )

    private fun snapshot(assets: List<Asset> = emptyList(), categories: List<AssetCategory> = emptyList()) =
        MergeSnapshot(assets = assets, categories = categories, attachmentStoreConfigured = true)

    private fun plan(data: BackupData, snapshot: MergeSnapshot, formatVersion: Int = 9): MergePlan =
        mergePlanOf(backupOf(data, formatVersion), snapshot)

    private fun MergePlan.categoryDecisions() = decisions.filter { it.table == MergeTable.CATEGORIES }

    private fun MergePlan.spellings() = writes.assets.associate { it.id.value to it.category }

    private fun inserted(key: String) = MergeDecision(MergeTable.CATEGORIES, key, MergeVerdict.INSERT)

    // --- an archive's own rows ---------------------------------------------------------------------

    /** A row whose key nothing here holds is an INSERT, tallied, and written. */
    @Test
    fun aRowNotHeldHereIsInserted() {
        val merged = plan(data(categories = listOf(row("appliance", "Appliance"))), snapshot())

        assertEquals(listOf(inserted("appliance")), merged.categoryDecisions())
        assertEquals(listOf(row("appliance", "Appliance")), merged.writes.categories)
        assertEquals(MergeTally(1, 0, 0, 0), merged.report().categories)
    }

    /** Key and display equal → IDENTICAL, however different the timestamps: two phones never agree on those. */
    @Test
    fun theSameKeyAndDisplayIsIdenticalWhateverTheTimestamps() {
        val merged = plan(
            data(categories = listOf(row("appliance", "Appliance", createdAt = 10L, updatedAt = 20L))),
            snapshot(categories = listOf(row("appliance", "Appliance", createdAt = 500L, updatedAt = 900L))),
        )

        assertEquals(
            listOf(MergeDecision(MergeTable.CATEGORIES, "appliance", MergeVerdict.IDENTICAL)),
            merged.categoryDecisions(),
        )
        assertTrue(merged.applicable)
        assertEquals(emptyList(), merged.writes.categories)
    }

    /**
     * The same key under another spelling is SKIPPED `CATEGORY_KEY_HELD`, never a conflict: the local
     * row is left as it is and an accepted asset of that key is written in the **local** spelling.
     */
    @Test
    fun theSameKeyUnderAnotherSpellingIsSkippedAndTheLocalSpellingWins() {
        val merged = plan(
            data(assets = listOf(asset("n1", "APPLIANCE")), categories = listOf(row("appliance", "APPLIANCE"))),
            snapshot(categories = listOf(row("appliance", "Appliance"))),
        )

        assertEquals(
            listOf(
                MergeDecision(
                    MergeTable.CATEGORIES, "appliance", MergeVerdict.SKIPPED, MergeReason.CATEGORY_KEY_HELD, "appliance",
                ),
            ),
            merged.categoryDecisions(),
        )
        assertTrue(merged.applicable, "a spelling never refuses an archive: ${merged.conflicts}")
        assertEquals(emptyList(), merged.writes.categories)
        assertEquals(mapOf("n1" to "Appliance"), merged.spellings())
    }

    /** A built-in's key is SKIPPED `CATEGORY_IS_BUILT_IN`; its asset takes the label and no row is planned. */
    @Test
    fun aBuiltInsKeyIsSkippedAndItsAssetTakesTheLabel() {
        val merged = plan(
            data(assets = listOf(asset("h1", "hot  TUB")), categories = listOf(row("hot tub", "hot tub"))),
            snapshot(),
        )

        assertEquals(
            listOf(
                MergeDecision(
                    MergeTable.CATEGORIES, "hot tub", MergeVerdict.SKIPPED, MergeReason.CATEGORY_IS_BUILT_IN, "hot tub",
                ),
            ),
            merged.categoryDecisions(),
        )
        assertEquals(emptyList(), merged.writes.categories)
        assertEquals(mapOf("h1" to "Hot tub"), merged.spellings())
    }

    /** Reachable only from a hand-built `Backup` (the codec refuses the duplicate): the first row wins. */
    @Test
    fun aSecondRowOfOneArchiveUnderOneKeyIsSkipped() {
        val merged = plan(
            data(categories = listOf(row("appliance", "Appliance"), row("appliance", "APPLIANCE"))),
            snapshot(),
        )

        assertEquals(
            listOf(
                inserted("appliance"),
                MergeDecision(
                    MergeTable.CATEGORIES, "appliance", MergeVerdict.SKIPPED, MergeReason.CATEGORY_KEY_HELD, "appliance",
                ),
            ),
            merged.categoryDecisions(),
        )
        assertEquals(listOf(row("appliance", "Appliance")), merged.writes.categories)
    }

    // --- promotions are planned ---------------------------------------------------------------------

    /**
     * A legacy (format 8) archive carries no rows, so the category its inserted assets need is a
     * **synthesised** INSERT: spelled as the oldest of them spells it (C9), in the decisions, the
     * tally, the report and the fingerprint — which differs from the same archive planned where the
     * row already exists. Every asset is written in the canonical spelling, its `updatedAt` its own.
     */
    @Test
    fun aLegacyArchivesInsertedAssetSynthesisesAnInsert() {
        val legacy = data(
            assets = listOf(
                asset("n1", "Test gear", createdAt = 300L, updatedAt = 310L),
                asset("n2", "TEST  GEAR", createdAt = 100L, updatedAt = 110L),
                asset("n3", "Pump", createdAt = 50L, updatedAt = 60L),
                asset("n4", "", createdAt = 40L, updatedAt = 70L),
            ),
        )

        val merged = plan(legacy, snapshot(), formatVersion = 8)

        assertEquals(listOf(inserted("test gear")), merged.categoryDecisions())
        assertEquals(listOf(row("test gear", "TEST GEAR", 100L, 100L)), merged.writes.categories)
        assertEquals(
            mapOf("n1" to "TEST GEAR", "n2" to "TEST GEAR", "n3" to "Pump", "n4" to ""),
            merged.spellings(),
        )
        assertEquals(
            mapOf("n1" to 310L, "n2" to 110L, "n3" to 60L, "n4" to 70L),
            merged.writes.assets.associate { it.id.value to it.updatedAt },
        )
        assertEquals(MergeTally(1, 0, 0, 0), merged.report().categories)
        assertEquals(MergeTally(4, 0, 0, 0), merged.report().assets)

        val alreadyHeld = plan(legacy, snapshot(categories = listOf(row("test gear", "Test gear"))), formatVersion = 8)
        assertEquals(emptyList(), alreadyHeld.categoryDecisions())
        assertEquals(MergeTally(0, 0, 0, 0), alreadyHeld.report().categories)
        assertNotEquals(merged.fingerprint, alreadyHeld.fingerprint)
        assertEquals("Test gear", alreadyHeld.spellings().getValue("n2"))
    }

    /** An accepted archive row wins its key for the assets that name it: no synthesised row beside it. */
    @Test
    fun anAcceptedArchiveRowSpellsItsAssets() {
        val merged = plan(
            data(assets = listOf(asset("n1", "appliance", createdAt = 1L)), categories = listOf(row("appliance", "Appliance"))),
            snapshot(),
        )

        assertEquals(listOf(inserted("appliance")), merged.categoryDecisions())
        assertEquals(mapOf("n1" to "Appliance"), merged.spellings())
    }

    /** "No partial merge" covers the categories: one conflict anywhere empties every write list. */
    @Test
    fun aConflictElsewhereWritesNoCategory() {
        val merged = plan(
            data(assets = listOf(asset("x1", "Pump"), asset("n1", "Test gear")), categories = listOf(row("appliance", "Appliance"))),
            snapshot(assets = listOf(asset("x1", "Appliance"))),
        )

        assertEquals(listOf("x1"), merged.conflicts.map { it.id })
        assertEquals(MergeReason.CONTENT_DIFFERS, merged.conflicts.single().reason)
        assertEquals(listOf(inserted("appliance"), inserted("test gear")), merged.categoryDecisions())
        assertEquals(MergeWrites(), merged.writes)
    }

    // --- the key-normalised asset comparison -------------------------------------------------------

    /**
     * R74-13 / AC 10: a pre-upgrade export spelled `appliance` against this phone's canonical
     * `Appliance` (the migration or a rename canonicalised it without an edit) is the same asset —
     * IDENTICAL, no category decision, nothing to write.
     */
    @Test
    fun aPreUpgradeArchiveSpelledAnotherWayIsIdentical() {
        val merged = plan(
            data(assets = listOf(asset("x1", "appliance"), asset("h1", "hot tub"))),
            snapshot(assets = listOf(asset("x1", "Appliance"), asset("h1", "Hot tub")), categories = listOf(row("appliance", "Appliance"))),
            formatVersion = 8,
        )

        assertEquals(
            listOf(
                MergeDecision(MergeTable.ASSETS, "h1", MergeVerdict.IDENTICAL),
                MergeDecision(MergeTable.ASSETS, "x1", MergeVerdict.IDENTICAL),
            ),
            merged.decisions,
        )
        assertEquals(MergeWrites(), merged.writes)
    }

    // --- apply, then plan again ---------------------------------------------------------------------

    /**
     * **Apply, then re-plan the same archive → all IDENTICAL**, for a pre-upgrade (format 8) archive
     * in every variant spelling: one asset already here spelled differently, one new under a local
     * row's key, one under a built-in's, one under a new key. The apply wrote the canonical spellings
     * and the synthesised row, and moved no `updatedAt`.
     */
    @Test
    fun applyThenReplanALegacyArchiveIsAllIdentical() = runBlocking<Unit> {
        val phone = BackupInstall()
        phone.assets.upsert(asset("x1", "Appliance"))
        phone.categories.upsert(row("appliance", "Appliance"))
        val bytes = archiveOf(
            data(
                assets = listOf(
                    asset("x1", "appliance"),
                    asset("n1", " APPLIANCE", updatedAt = 410L),
                    asset("h1", "HOT TUB", updatedAt = 420L),
                    asset("t1", "Test  gear", createdAt = 300L, updatedAt = 430L),
                ),
            ),
            formatVersion = 8,
        )

        val first = phone.build.run(bytes)
        assertEquals(listOf(inserted("test gear")), first.categoryDecisions())
        phone.apply.run(first)

        assertEquals(listOf(row("appliance", "Appliance"), row("test gear", "Test gear", 300L, 300L)), phone.categories.all())
        assertEquals(
            mapOf("x1" to "Appliance", "n1" to "Appliance", "h1" to "Hot tub", "t1" to "Test gear"),
            phone.assets.all().associate { it.id.value to it.category },
        )
        assertEquals(
            mapOf("x1" to 200L, "n1" to 410L, "h1" to 420L, "t1" to 430L),
            phone.assets.all().associate { it.id.value to it.updatedAt },
        )

        val again = phone.build.run(bytes)
        assertTrue(again.decisions.all { it.verdict == MergeVerdict.IDENTICAL }, "${again.decisions}")
        assertEquals(4, again.decisions.size)
        assertEquals(MergeWrites(), again.writes)
    }

    /** The same for a format-9 export of a second phone: its rows and assets re-plan IDENTICAL once applied. */
    @Test
    fun applyThenReplanAnotherPhonesExportIsAllIdentical() = runBlocking<Unit> {
        val other = BackupInstall(setId = "set-other")
        other.categories.upsert(row("appliance", "Appliance", 5L, 5L))
        other.categories.upsert(row("spare parts", "Spare parts", 6L, 6L))
        other.assets.upsert(asset("o1", "Appliance"))
        val bytes = other.export.run().data
        val phone = BackupInstall()
        phone.categories.upsert(row("appliance", "Appliance", 500L, 500L))

        val first = phone.build.run(bytes)
        assertEquals(
            listOf(MergeDecision(MergeTable.CATEGORIES, "appliance", MergeVerdict.IDENTICAL), inserted("spare parts")),
            first.categoryDecisions(),
        )
        phone.apply.run(first)
        val again = phone.build.run(bytes)

        assertTrue(again.decisions.all { it.verdict == MergeVerdict.IDENTICAL }, "${again.decisions}")
        assertEquals(3, again.decisions.size)
        assertEquals(listOf(row("appliance", "Appliance", 500L, 500L), row("spare parts", "Spare parts", 6L, 6L)), phone.categories.all())
    }

    /**
     * The apply writes `MergeWrites` and nothing else, so a category a local save adds between the
     * plan and the apply changes a decision (the synthesised INSERT is gone) and the apply is refused
     * as stale — writing nothing — rather than promoting behind the plan.
     */
    @Test
    fun aCategorySavedBetweenPlanAndApplyMakesThePlanStale() = runBlocking<Unit> {
        val phone = BackupInstall()
        val bytes = archiveOf(data(assets = listOf(asset("n1", "Test gear"))), formatVersion = 8)
        val planned = phone.build.run(bytes)
        phone.categories.upsert(row("test gear", "TEST GEAR"))

        assertFailsWith<MergePlanStale> { phone.apply.run(planned) }

        assertEquals(emptyList(), phone.assets.all())
        assertEquals(listOf(row("test gear", "TEST GEAR")), phone.categories.all())
        assertEquals(0, phone.rebuilds)
    }

    /** `MergeWrites.categories` is the first field, so the apply writes categories before any asset. */
    @Test
    fun theApplyWritesCategoriesFirst() = runBlocking<Unit> {
        val phone = BackupInstall()
        val log = mutableListOf<String>()
        val assets = object : AssetRepository by phone.assets, Rollbackable by phone.assets, Witnessed by phone.assets {
            override suspend fun upsert(asset: Asset) = phone.assets.upsert(asset).also { log += "asset:${asset.id.value}" }
        }
        val categories = object : CategoryRepository by phone.categories, Rollbackable by phone.categories,
            Witnessed by phone.categories {
            override suspend fun upsert(row: AssetCategory) = phone.categories.upsert(row).also { log += "category:${row.key}" }
        }
        val apply = ApplyBackupMergePlan(
            assets, phone.groups, phone.tags, phone.links, phone.definitions, phone.profiles, phone.schedules,
            phone.closures, phone.events, phone.attachments, phone.references, phone.activations, phone.conditions,
            phone.subjects, categories, phone.storage, phone.uow, rebuildAll = { log += "rebuild" },
        )
        val bytes = archiveOf(
            data(assets = listOf(asset("n1", "Test gear"), asset("n2", "Appliance")), categories = listOf(row("appliance", "Appliance"))),
        )

        apply.run(phone.build.run(bytes))

        assertEquals(listOf("category:appliance", "category:test gear", "asset:n1", "asset:n2", "rebuild"), log)
    }

    // --- the accepted costs of key-as-identity (R74-2) ----------------------------------------------

    /**
     * An archive made before a **new-key rename** re-inserts the old key as an unused row: the rename
     * was a delete and an insert, and nothing in the archive says the old key was retired.
     */
    @Test
    fun anArchiveFromBeforeANewKeyRenameReInsertsTheOldKey() = runBlocking<Unit> {
        val phone = BackupInstall()
        phone.assets.upsert(asset("x1", "Pump"))
        phone.categories.upsert(row("appliance", "Appliance"))
        val before = phone.export.run().data
        RenameCategory(phone.categories, phone.assets, phone.uow, Clock { 9_000L }).run("appliance", "Kitchen kit")

        val merged = phone.build.run(before)
        assertEquals(listOf(inserted("appliance")), merged.categoryDecisions())
        phone.apply.run(merged)

        assertEquals(listOf(row("appliance", "Appliance"), row("kitchen kit", "Kitchen kit", 10L, 9_000L)), phone.categories.all())
    }

    /** A deleted category comes back from an archive that holds it — a uuid identity would bring it back too. */
    @Test
    fun aDeletedCategoryResurrectsFromAnArchiveThatHoldsIt() = runBlocking<Unit> {
        val phone = BackupInstall()
        phone.categories.upsert(row("appliance", "Appliance"))
        val before = phone.export.run().data
        DeleteCategory(phone.categories, phone.assets, phone.uow).run("appliance")
        assertEquals(emptyList(), phone.categories.all())

        phone.apply.run(phone.build.run(before))

        assertEquals(listOf(row("appliance", "Appliance")), phone.categories.all())
    }

    /** A category saved on two phones independently is one row, not two (AC 10). */
    @Test
    fun theSameCategoryTypedOnTwoPhonesMergesToOneRow() = runBlocking<Unit> {
        val other = BackupInstall(setId = "set-other")
        other.categories.upsert(row("appliance", "APPLIANCE", 7L, 7L))
        other.assets.upsert(asset("o1", "APPLIANCE"))
        val phone = BackupInstall()
        phone.categories.upsert(row("appliance", "Appliance", 3L, 3L))
        phone.assets.upsert(asset("p1", "Appliance"))

        phone.apply.run(phone.build.run(other.export.run().data))

        assertEquals(listOf(row("appliance", "Appliance", 3L, 3L)), phone.categories.all())
        assertEquals(
            mapOf("o1" to "Appliance", "p1" to "Appliance"),
            phone.assets.all().associate { it.id.value to it.category },
        )
    }

    /** The builder the tests above use is the production one: the snapshot really reads the categories. */
    @Test
    fun theBuiltPlanReadsTheLocalCatalog() = runBlocking<Unit> {
        val phone = BackupInstall()
        phone.categories.upsert(row("appliance", "Appliance"))
        val build = BuildBackupMergePlan(
            phone.assets, phone.groups, phone.tags, phone.links, phone.definitions, phone.profiles, phone.schedules,
            phone.closures, phone.events, phone.attachments, phone.references, phone.activations, phone.conditions,
            phone.subjects, InMemoryCategoryRepository(), phone.storage, phone.uow,
        )
        val bytes = archiveOf(data(categories = listOf(row("appliance", "APPLIANCE"))))

        assertEquals(listOf(inserted("appliance")), build.run(bytes).categoryDecisions())
        assertEquals(
            MergeReason.CATEGORY_KEY_HELD,
            phone.build.run(bytes).categoryDecisions().single().reason,
        )
    }
}
