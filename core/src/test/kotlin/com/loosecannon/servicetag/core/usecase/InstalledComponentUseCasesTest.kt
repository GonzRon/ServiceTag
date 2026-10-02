package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.InstalledComponentRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.InMemoryInstalledComponentRepository
import com.loosecannon.servicetag.core.testing.RecordingUnitOfWork
import com.loosecannon.servicetag.core.testing.assetSupplyOf
import com.loosecannon.servicetag.core.testing.compositionEntryOf
import com.loosecannon.servicetag.core.testing.dataTreeOf
import com.loosecannon.servicetag.core.testing.installedComponentOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.supplyItemOf
import com.loosecannon.servicetag.core.testing.transferOf
import com.loosecannon.servicetag.core.transfer.AssetTransferredOut
import com.loosecannon.servicetag.core.transfer.HeldWriteGuard
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #47 (C15–C19; R47-3, R47-6, R47-8, R47-11, R47-15, R47-17b) — install, remove, replace and edit, and that none of
 * them, nor applicability, infers anything (row 39). Command-only problems are collected before any transaction, so a
 * shape refusal opens none; the other rows are checked inside the one write, one step at a time, before anything is
 * minted or written. Every write goes through the guarded port over the core double ([BackupInstall]'s, which refuses
 * what the two tables refuse), so a held asset's write throws after every check. Fictional throughout ("Example UPS").
 */
class InstalledComponentUseCasesTest {

    private val install = BackupInstall()
    private val guard = HeldWriteGuard(
        install.transfers, install.events, install.definitions, install.profiles, install.groups, install.schedules,
        install.serviceCases, install.links, install.installedComponents,
    )
    private val guarded = guard.installedComponents(install.installedComponents)
    private val stored = install.installedComponents
    private val uow = RecordingUnitOfWork(
        install.assets, install.supplyItems, install.assetSupplies, install.installedComponents, install.events,
        install.transfers,
    )
    private var minted = 0
    private val ids = IdGenerator { "ic-%02d".format(++minted) }
    private var now = 9_000L
    private val clock = Clock { now }
    private val today = Today { LocalDate.parse("2026-09-30") }
    private val installComponent = InstallComponent(install.assets, install.supplyItems, guarded, uow, ids, clock, today)
    private val remove = RemoveInstalledComponent(guarded, uow, clock, today)

    /** The guarded port, counting the writes that reach it: an edit that changes nothing reaches none. */
    private val counted = CountingInstalledComponents(guarded)
    private val replace = ReplaceInstalledComponent(install.supplyItems, counted, uow, ids, clock, today)
    private val update = UpdateInstalledComponent(install.supplyItems, counted, uow, ids, clock, today)

    /**
     * "Example UPS" (x1), "Example RO System" (x2) and "Example Water Heater" (h1); a live 12 V battery (s1) and tray
     * (s2), an archived battery (s9).
     */
    private suspend fun seed() {
        install.assets.upsert(plainAssetOf("x1", "Example UPS"))
        install.assets.upsert(plainAssetOf("x2", "Example RO System"))
        install.assets.upsert(plainAssetOf("h1", "Example Water Heater"))
        install.supplyItems.upsert(supplyItemOf("s1", "Example 12 V Battery"))
        install.supplyItems.upsert(supplyItemOf("s2", "Example Battery Tray"))
        install.supplyItems.upsert(supplyItemOf("s9", "Example Old Battery", archivedAt = 3_000L))
    }

    private fun command(
        assetId: String = "x1",
        parentId: String? = null,
        name: String = "Example Battery Tray",
        supplyId: String? = null,
        composition: List<CompositionInput> = emptyList(),
        serialOrLot: String = "",
        installedOn: String? = "2026-09-01",
        notes: String = "",
        sortOrder: Int? = null,
    ) = InstallComponentCommand(
        assetId = AssetId(assetId),
        parentId = parentId?.let(::InstalledComponentId),
        name = name,
        supplyId = supplyId?.let(::SupplyId),
        composition = composition,
        serialOrLot = serialOrLot,
        installedOn = installedOn,
        notes = notes,
        sortOrder = sortOrder,
    )

    private fun entry(supplyId: String, quantity: String = "1", unit: String = "ea", id: String? = null) =
        CompositionInput(id = id, supplyId = SupplyId(supplyId), quantity = quantity, unit = unit)

    private fun ok(result: InstalledComponentResult): InstalledComponentResult.Ok {
        assertTrue(result is InstalledComponentResult.Ok, "expected a row, got $result")
        return result
    }

    private fun refused(result: InstalledComponentResult): List<InstalledComponentProblem> {
        assertTrue(result is InstalledComponentResult.Refused, "expected a refusal, got $result")
        return result.problems
    }

    private suspend fun installed(cmd: InstallComponentCommand): InstalledComponent = ok(installComponent.run(cmd)).row

    /** What the two tables hold: every row with its composition, and every entry record with the row that owns it. */
    private suspend fun tables() = stored.all() to LinkedHashMap(stored.entries)

    /**
     * [problems], exactly, with no id minted and nothing written; [transactions] is how many writes the refusal
     * opened: 0 for a shape refusal, 1 for a refusal about another row (checked inside the write).
     */
    private suspend fun assertRefused(
        problems: List<InstalledComponentProblem>,
        transactions: Int,
        run: suspend () -> InstalledComponentResult,
    ) {
        val before = tables()
        val writes = uow.writesEntered
        val mintedBefore = minted
        assertEquals(problems, refused(run()))
        assertEquals(writes + transactions, uow.writesEntered, "$problems: transactions opened")
        assertEquals(mintedBefore, minted, "$problems: no id minted")
        assertEquals(before, tables(), "$problems: nothing written")
    }

    // ---- row 32: install --------------------------------------------------------------------------------------------

    @Test
    fun installStoresTrimmedTextAndAppendsSortOrder() = runTest {
        seed()
        // Rows that are not this parent's current children never count: a removed top-level row, another asset's row.
        stored.insert(installedComponentOf("old", assetId = "x1", name = "Example Old Tray", removedOn = "2026-08-01", sortOrder = 7))
        stored.insert(installedComponentOf("ro", assetId = "x2", name = "Example Membrane Housing", sortOrder = 9))

        val tray = installed(
            command(
                name = "  Example Battery Tray ", supplyId = "s2", serialOrLot = " SN-0001 ", installedOn = "2026-09-01",
                notes = "  left bay  ",
            ),
        )
        val expected = installedComponentOf(
            "ic-01", assetId = "x1", name = "Example Battery Tray", supplyId = "s2", serialOrLot = "SN-0001",
            installedOn = "2026-09-01", sortOrder = 0, notes = "left bay", createdAt = 9_000L, updatedAt = 9_000L,
        )
        assertEquals(expected, tray, "trimmed text, the date as given, current, no predecessor, first top-level sortOrder 0")
        assertEquals(expected, stored.get(InstalledComponentId("ic-01")), "stored as answered")
        assertEquals(1, uow.writesEntered, "one write")

        now = 10_000L
        val fan = installed(command(name = "Example Fan"))
        assertEquals(1, fan.sortOrder, "appended after the asset's current top-level rows")
        val first = installed(command(parentId = "ic-01", name = "Position 1"))
        val second = installed(command(parentId = "ic-01", name = "Position 2"))
        assertEquals(listOf(0, 1), listOf(first.sortOrder, second.sortOrder), "a parent's own children count from 0")
        assertEquals(InstalledComponentId("ic-01"), second.parentId)
        assertEquals(5, installed(command(parentId = "ic-01", name = "Position 9", sortOrder = 5)).sortOrder, "a given sortOrder is kept")
        assertEquals(6, installed(command(parentId = "ic-01", name = "Position 10")).sortOrder, "one more than the greatest")
        assertEquals(10_000L, fan.createdAt)
        assertEquals(10_000L, fan.updatedAt)

        val result = ok(installComponent.run(command(name = "Example Display")))
        assertNull(result.replaced)
        assertEquals(emptyList(), result.closed)
        assertTrue(install.assetSupplies.rows.isEmpty(), "no applicability row")
        assertTrue(install.events.rows.isEmpty(), "no event")
        assertEquals(3, install.supplyItems.rows.size, "no SupplyItem")
    }

    @Test
    fun anUnknownDateIsAccepted() = runTest {
        seed()
        val row = installed(command(installedOn = null))
        assertNull(row.installedOn, "R47-8: a date not recorded")
        assertNull(stored.get(row.id)?.installedOn)
    }

    @Test
    fun ownerParentAndSupplyStepsRefuseInOrder() = runTest {
        seed()
        stored.insert(installedComponentOf("tray", assetId = "x1"))
        stored.insert(installedComponentOf("gone", assetId = "x1", name = "Example Old Tray", removedOn = "2026-08-01"))
        stored.insert(installedComponentOf("ro", assetId = "x2", name = "Example Membrane Housing"))
        stored.insert(installedComponentOf("ro-gone", assetId = "x2", name = "Example Old Housing", removedOn = "2026-08-01"))
        val badEntries = listOf(entry("s404"), entry("s9"))

        // Each step answers alone, before every later step's problem in the same command.
        assertRefused(listOf(InstalledComponentProblem.OwnerMissing), transactions = 1) {
            installComponent.run(command(assetId = "x404", parentId = "c404", supplyId = "s404", composition = badEntries))
        }
        assertRefused(listOf(InstalledComponentProblem.ParentMissing), transactions = 1) {
            installComponent.run(command(parentId = "c404", supplyId = "s404", composition = badEntries))
        }
        assertRefused(listOf(InstalledComponentProblem.ParentOnAnotherAsset), transactions = 1) {
            installComponent.run(command(parentId = "ro-gone", supplyId = "s404", composition = badEntries))
        }
        assertRefused(listOf(InstalledComponentProblem.ParentOnAnotherAsset), transactions = 1) {
            installComponent.run(command(parentId = "ro"))
        }
        assertRefused(listOf(InstalledComponentProblem.ParentRemoved), transactions = 1) {
            installComponent.run(command(parentId = "gone", supplyId = "s404", composition = badEntries))
        }
        assertRefused(listOf(InstalledComponentProblem.SupplyItemMissing), transactions = 1) {
            installComponent.run(command(parentId = "tray", supplyId = "s404", composition = badEntries))
        }
        assertRefused(listOf(InstalledComponentProblem.SupplyItemArchived), transactions = 1) {
            installComponent.run(command(parentId = "tray", supplyId = "s9", composition = badEntries))
        }
        assertRefused(
            listOf(InstalledComponentProblem.EntrySupplyItemMissing(0), InstalledComponentProblem.EntrySupplyItemArchived(1)),
            transactions = 1,
        ) {
            installComponent.run(command(parentId = "tray", supplyId = "s1", composition = badEntries))
        }
        assertEquals(InstalledComponentId("tray"), installed(command(parentId = "tray", supplyId = "s1")).parentId)
    }

    @Test
    fun aRefusalOpensNoTransaction() = runTest {
        seed()
        // Every shape problem collected, in field order, and none of the other rows' problems: they are never read.
        assertRefused(
            listOf(
                InstalledComponentProblem.NameRequired,
                InstalledComponentProblem.BadDate("installedOn"),
                InstalledComponentProblem.QuantityInvalid(0),
            ),
            transactions = 0,
        ) {
            installComponent.run(
                command(assetId = "x404", name = "  ", supplyId = "s404", installedOn = "2026-9-1", composition = listOf(entry("s9", quantity = "0"))),
            )
        }
        assertRefused(listOf(InstalledComponentProblem.AfterToday("installedOn")), transactions = 0) {
            installComponent.run(command(installedOn = "2026-10-01"))
        }
        assertRefused(listOf(InstalledComponentProblem.BadDate("installedOn")), transactions = 0) {
            installComponent.run(command(installedOn = ""))
        }
        assertEquals(0, uow.writesEntered, "no transaction for any of them")
        installed(command(installedOn = "2026-09-30"))
        assertEquals(1, uow.writesEntered, "today itself is accepted")
    }

    @Test
    fun anArchivedDirectSupplyItemIsRefused() = runTest {
        seed()
        assertRefused(listOf(InstalledComponentProblem.SupplyItemArchived), transactions = 1) {
            installComponent.run(command(supplyId = "s9"))
        }
        install.supplyItems.setArchived(SupplyId("s9"), archivedAt = null, updatedAt = 4_000L)
        assertEquals(SupplyId("s9"), installed(command(supplyId = "s9")).supplyId, "unarchived, it is taken again")
    }

    // ---- row 33: the composition on install -------------------------------------------------------------------------

    @Test
    fun entriesAreStoredInOrderWithMintedIds() = runTest {
        seed()
        val pack = installed(
            command(
                name = "Example Battery Pack", supplyId = "s2",
                composition = listOf(entry("s1", " 4 ", " ea ", id = "sent-1"), entry("s2", "1.5", "m"), entry("s1", "2e0", "")),
            ),
        )
        val expected = listOf(
            compositionEntryOf("ic-02", "s1", quantity = 4.0, unit = "ea", sortOrder = 0),
            compositionEntryOf("ic-03", "s2", quantity = 1.5, unit = "m", sortOrder = 1),
            compositionEntryOf("ic-04", "s1", quantity = 2.0, unit = "", sortOrder = 2),
        )
        assertEquals(InstalledComponentId("ic-01"), pack.id, "the row's id first, then each entry's, in list order")
        assertEquals(expected, pack.composition, "parsed, trimmed, sortOrder the position, a sent id ignored")
        assertEquals(expected, stored.get(pack.id)?.composition, "stored as answered")
        assertEquals(setOf("ic-02", "ic-03", "ic-04"), stored.entries.keys, "the sent id names no entry")
        assertTrue(stored.entries.values.all { it.componentId == pack.id })
    }

    @Test
    fun oneSupplyItemTwiceIsTwoEntries() = runTest {
        seed()
        val pack = installed(command(name = "Example Battery Pack", supplyId = "s1", composition = listOf(entry("s1", "2"), entry("s1", "2"))))
        assertEquals(listOf(SupplyId("s1"), SupplyId("s1")), pack.composition.map { it.supplyId })
        assertEquals(2, pack.composition.map { it.id }.toSet().size, "two entries, two ids")
        assertEquals(SupplyId("s1"), pack.supplyId, "an entry may name the direct link's SupplyItem")
    }

    @Test
    fun anArchivedEntryIsRefusedByIndex() = runTest {
        seed()
        assertRefused(
            listOf(
                InstalledComponentProblem.EntrySupplyItemArchived(1),
                InstalledComponentProblem.EntrySupplyItemMissing(2),
                InstalledComponentProblem.EntrySupplyItemArchived(3),
            ),
            transactions = 1,
        ) {
            installComponent.run(command(composition = listOf(entry("s1"), entry("s9"), entry("s404"), entry("s9"))))
        }
        install.supplyItems.setArchived(SupplyId("s9"), archivedAt = null, updatedAt = 4_000L)
        assertEquals(2, installed(command(composition = listOf(entry("s1"), entry("s9")))).composition.size, "unarchived, it is taken")
    }

    @Test
    fun aBadQuantityIsCollectedByIndex() = runTest {
        seed()
        val quantities = listOf("4", "0", "-1", "abc", "", "NaN", "Infinity", " 2 ")
        assertRefused(
            listOf(InstalledComponentProblem.NameRequired) + (1..6).map { InstalledComponentProblem.QuantityInvalid(it) },
            transactions = 0,
        ) {
            installComponent.run(command(name = "", composition = quantities.map { entry("s1", it) }))
        }
    }

    // ---- row 35: remove ---------------------------------------------------------------------------------------------

    /**
     * On x1: a tray t (installed 2026-09-01) holding p1 and p2, p2 holding p2a, and p3 removed on 2026-09-05; a fan u
     * beside the tray. On x2: a membrane housing. The tray and p2a carry compositions.
     */
    private suspend fun seedTree() {
        seed()
        stored.insert(
            installedComponentOf(
                "t", assetId = "x1", installedOn = "2026-09-01",
                composition = listOf(compositionEntryOf("e1", "s1", 4.0), compositionEntryOf("e2", "s2", sortOrder = 1)),
            ),
        )
        stored.insert(installedComponentOf("p1", assetId = "x1", parentId = "t", name = "Position 1", installedOn = "2026-09-01"))
        stored.insert(installedComponentOf("p2", assetId = "x1", parentId = "t", name = "Position 2", installedOn = null))
        stored.insert(
            installedComponentOf(
                "p2a", assetId = "x1", parentId = "p2", name = "Example Terminal", installedOn = "2026-09-10",
                composition = listOf(compositionEntryOf("e3", "s9", 2.0)),
            ),
        )
        stored.insert(
            installedComponentOf("p3", assetId = "x1", parentId = "t", name = "Position 3", installedOn = "2026-09-01", removedOn = "2026-09-05"),
        )
        stored.insert(installedComponentOf("u", assetId = "x1", name = "Example Fan", installedOn = "2026-09-25"))
        stored.insert(installedComponentOf("ro", assetId = "x2", name = "Example Membrane Housing", installedOn = "2026-09-25"))
    }

    @Test
    fun removeWritesRemovedOnAndUpdatedAtOnce() = runTest {
        seedTree()
        val before = stored.get(InstalledComponentId("u"))!!
        now = 12_000L
        val result = ok(remove.run(InstalledComponentId("u"), "2026-09-28"))
        val expected = before.copy(removedOn = "2026-09-28", updatedAt = 12_000L)
        assertEquals(expected, result.row)
        assertEquals(expected, stored.get(InstalledComponentId("u")), "stored as answered")
        assertNull(result.replaced)
        assertEquals(emptyList(), result.closed, "a row with no children closes alone")
        assertEquals(1, uow.writesEntered, "one write")
        assertEquals(0, minted, "nothing minted")
    }

    @Test
    fun removingTwiceIsAlreadyRemoved() = runTest {
        seedTree()
        ok(remove.run(InstalledComponentId("u"), "2026-09-28"))
        now = 13_000L
        assertRefused(listOf(InstalledComponentProblem.AlreadyRemoved), transactions = 1) {
            remove.run(InstalledComponentId("u"), "2026-09-29")
        }
        assertRefused(listOf(InstalledComponentProblem.AlreadyRemoved), transactions = 1) {
            remove.run(InstalledComponentId("p3"), "2026-09-29")
        }
        assertEquals(9_000L, stored.get(InstalledComponentId("u"))?.updatedAt, "the first removal's stamp holds")
    }

    @Test
    fun aBadOrFutureDateOpensNoTransactionAndAnUnknownRowIsNoSuchInstalledComponent() = runTest {
        seedTree()
        for (bad in listOf("", "2026-09-31", "28/09/2026", " 2026-09-28")) {
            assertRefused(listOf(InstalledComponentProblem.BadDate("removedOn")), transactions = 0) {
                remove.run(InstalledComponentId("u"), bad)
            }
        }
        assertRefused(listOf(InstalledComponentProblem.AfterToday("removedOn")), transactions = 0) {
            remove.run(InstalledComponentId("u"), "2026-10-01")
        }
        assertRefused(listOf(InstalledComponentProblem.NoSuchInstalledComponent), transactions = 1) {
            remove.run(InstalledComponentId("c404"), "2026-09-28")
        }
        assertEquals(1, uow.writesEntered, "only the unknown row's check opened one")
    }

    @Test
    fun beforeAnInstallDateIsRefused() = runTest {
        seedTree()
        assertRefused(listOf(InstalledComponentProblem.RemovedBeforeInstalled("removedOn")), transactions = 1) {
            remove.run(InstalledComponentId("u"), "2026-09-24")
        }
        // p2a, a current grandchild, was fitted on 2026-09-10: the tray cannot leave before it did.
        assertRefused(listOf(InstalledComponentProblem.RemovedBeforeInstalled("removedOn")), transactions = 1) {
            remove.run(InstalledComponentId("t"), "2026-09-09")
        }
        // p2 has no install date and refuses nothing; p3 left on 2026-09-05 and is not closed again.
        ok(remove.run(InstalledComponentId("t"), "2026-09-10"))
        assertEquals("2026-09-25", ok(remove.run(InstalledComponentId("ro"), "2026-09-25")).row.removedOn, "the install day itself is accepted")
    }

    @Test
    fun theCurrentSubtreeClosesOnTheSameDateInTheSameWrite() = runTest {
        seedTree()
        val before = stored.all().associateBy { it.id.value }
        now = 12_000L
        val result = ok(remove.run(InstalledComponentId("t"), "2026-09-20"))

        assertEquals(1, uow.writesEntered, "one write closes the row and its subtree")
        assertEquals(before.getValue("t").copy(removedOn = "2026-09-20", updatedAt = 12_000L), result.row)
        val closed = listOf("p1", "p2", "p2a").map { before.getValue(it).copy(removedOn = "2026-09-20", updatedAt = 12_000L) }
        assertEquals(closed, result.closed, "every current descendant, by id, on the same date and stamp")
        val after = stored.all().associateBy { it.id.value }
        (listOf(result.row) + closed).forEach { assertEquals(it, after.getValue(it.id.value), "${it.id.value} stored closed") }
        listOf("p3", "u", "ro").forEach { assertEquals(before.getValue(it), after.getValue(it), "$it untouched") }
    }

    @Test
    fun closedRowsKeepTheirComposition() = runTest {
        seedTree()
        val entries = LinkedHashMap(stored.entries)
        val result = ok(remove.run(InstalledComponentId("t"), "2026-09-20"))
        assertEquals(listOf("e1", "e2"), result.row.composition.map { it.id })
        assertEquals(listOf("e3"), result.closed.single { it.id.value == "p2a" }.composition.map { it.id })
        assertEquals(entries, stored.entries, "every entry record byte-equal, on the row it was on")
    }

    @Test
    fun nothingIsDeleted() = runTest {
        seedTree()
        val rowIds = stored.rows.keys.toSet()
        ok(remove.run(InstalledComponentId("t"), "2026-09-20"))
        ok(remove.run(InstalledComponentId("u"), "2026-09-28"))
        assertEquals(rowIds, stored.rows.keys.toSet(), "every row still stored")
        assertEquals(setOf("e1", "e2", "e3"), stored.entries.keys)
        assertTrue(stored.all().none { it.isCurrent && it.assetId == AssetId("x1") }, "x1's rows are all history now")
        assertTrue(install.assetSupplies.rows.isEmpty() && install.events.rows.isEmpty(), "no applicability row, no event")
    }

    // ---- the guarded port: a held asset's write throws after every check ---------------------------------------------

    @Test
    fun installingOnAHeldAssetThrowsAfterEveryCheck() = runTest {
        seed()
        install.transfers.append(transferOf("out-h1", assetId = "h1"))
        assertRefused(listOf(InstalledComponentProblem.NameRequired), transactions = 0) {
            installComponent.run(command(assetId = "h1", name = " "))
        }
        assertRefused(listOf(InstalledComponentProblem.SupplyItemArchived), transactions = 1) {
            installComponent.run(command(assetId = "h1", supplyId = "s9"))
        }
        val before = tables()
        val thrown = assertFailsWith<AssetTransferredOut> {
            installComponent.run(command(assetId = "h1", supplyId = "s1", composition = listOf(entry("s2", "4"))))
        }
        assertEquals(AssetId("h1"), thrown.assetId)
        assertEquals(before, tables(), "nothing written")
    }

    @Test
    fun removingOnAHeldAssetThrowsAfterEveryCheck() = runTest {
        seed()
        stored.insert(installedComponentOf("h-tray", assetId = "h1", name = "Example Element", installedOn = "2026-09-01"))
        stored.insert(installedComponentOf("h-seat", assetId = "h1", parentId = "h-tray", name = "Example Seat", installedOn = "2026-09-01"))
        stored.insert(installedComponentOf("h-old", assetId = "h1", name = "Example Old Element", removedOn = "2026-08-01"))
        install.transfers.append(transferOf("out-h1", assetId = "h1"))
        assertRefused(listOf(InstalledComponentProblem.BadDate("removedOn")), transactions = 0) {
            remove.run(InstalledComponentId("h-tray"), "soon")
        }
        assertRefused(listOf(InstalledComponentProblem.AlreadyRemoved), transactions = 1) {
            remove.run(InstalledComponentId("h-old"), "2026-09-20")
        }
        assertRefused(listOf(InstalledComponentProblem.RemovedBeforeInstalled("removedOn")), transactions = 1) {
            remove.run(InstalledComponentId("h-tray"), "2026-08-20")
        }
        val before = tables()
        assertFailsWith<AssetTransferredOut> { remove.run(InstalledComponentId("h-tray"), "2026-09-20") }
        assertEquals(before, tables(), "nothing written, the subtree included")
    }

    // ---- row 34: replace (C18; AC6; R47-6, R47-17b) -----------------------------------------------------------------

    private fun replacement(
        replacedOn: String = "2026-09-20",
        name: String = "Position 1",
        supplyId: String? = null,
        composition: List<CompositionInput> = emptyList(),
        serialOrLot: String = "",
        notes: String = "",
    ) = ReplaceComponentCommand(
        replacedOn = replacedOn,
        name = name,
        supplyId = supplyId?.let(::SupplyId),
        composition = composition,
        serialOrLot = serialOrLot,
        notes = notes,
    )

    /**
     * Every list a backup carries, as the export writes it, each row keyed by its id: the whole double's state, every
     * table, every row, each installed component with its composition nested.
     */
    private suspend fun everyTable(): Map<String, Map<String, String>> =
        dataTreeOf(install.export.run().data).filterValues { it is JsonArray }.mapValues { (_, list) ->
            list.jsonArray.associate { row -> (row.jsonObject["id"]?.jsonPrimitive?.content ?: row.toString()) to row.toString() }
        }

    private fun row(rows: List<InstalledComponent>, id: String) = rows.single { it.id.value == id }

    @Test
    fun replaceClosesOneAndInsertsOneInOneWrite() = runTest {
        seedTree()
        install.assetSupplies.insert(assetSupplyOf("as1", "x1", "s1", role = "Battery"))
        val tablesBefore = everyTable()
        val rowsBefore = stored.all()
        val entriesBefore = LinkedHashMap(stored.entries)
        now = 12_000L

        val result = ok(
            replace.run(
                InstalledComponentId("p1"),
                replacement(
                    name = " Position 1 ", supplyId = "s1", composition = listOf(entry("s1", "4")), serialOrLot = " SN-0002 ",
                    notes = " fitted new ",
                ),
            ),
        )

        val successor = installedComponentOf(
            "ic-01", assetId = "x1", parentId = "t", name = "Position 1", supplyId = "s1",
            composition = listOf(compositionEntryOf("ic-02", "s1", quantity = 4.0, unit = "ea", sortOrder = 0)),
            serialOrLot = "SN-0002", installedOn = "2026-09-20", replacesId = "p1", sortOrder = 0, notes = "fitted new",
            createdAt = 12_000L, updatedAt = 12_000L,
        )
        val predecessor = row(rowsBefore, "p1").copy(removedOn = "2026-09-20", updatedAt = 12_000L)
        assertEquals(successor, result.row, "the successor as written")
        assertEquals(predecessor, result.replaced, "the predecessor closed on the replacement date")
        assertEquals(emptyList(), result.closed, "a row with no children closes alone")
        assertEquals(1, uow.writesEntered, "one write")
        assertEquals(2, counted.writes, "one insert and one update, nothing else")

        // installed_component: the rows before, p1 closed, the successor added; every entry record before byte-equal.
        val rowsAfter = (rowsBefore.map { if (it.id.value == "p1") predecessor else it } + successor).sortedBy { it.id.value }
        assertEquals(rowsAfter, stored.all())
        assertEquals(
            entriesBefore + ("ic-02" to InMemoryInstalledComponentRepository.StoredEntry(successor.id, successor.composition.single())),
            stored.entries,
        )
        // Every other row of every table, byte-equal as an export writes it.
        val tablesAfter = everyTable()
        assertEquals(tablesBefore.keys, tablesAfter.keys)
        val touched = setOf("p1", "ic-01")
        tablesBefore.forEach { (list, rows) -> assertEquals(rows - touched, tablesAfter.getValue(list) - touched, "$list: every other row") }
        assertEquals(
            tablesBefore.getValue("installedComponents").keys + "ic-01",
            tablesAfter.getValue("installedComponents").keys,
        )
    }

    @Test
    fun theSuccessorTakesParentAndSortOrderAndNamesItsPredecessor() = runTest {
        seedTree()
        stored.insert(
            installedComponentOf(
                "p4", assetId = "x1", parentId = "t", name = "Position 4", supplyId = "s1", serialOrLot = "SN-OLD",
                installedOn = "2026-09-01", sortOrder = 4, notes = "old note",
            ),
        )
        now = 12_000L
        val result = ok(replace.run(InstalledComponentId("p4"), replacement(replacedOn = "2026-09-29", name = "Example New Battery")))

        val successor = result.row
        assertEquals(AssetId("x1"), successor.assetId, "the predecessor's asset")
        assertEquals(InstalledComponentId("t"), successor.parentId, "the predecessor's parent")
        assertEquals(4, successor.sortOrder, "the predecessor's sortOrder")
        assertEquals(InstalledComponentId("p4"), successor.replacesId, "names its predecessor")
        assertEquals("2026-09-29", successor.installedOn, "installed on the replacement date")
        assertTrue(successor.isCurrent)
        assertEquals("Example New Battery", successor.name, "the command's name")
        assertEquals(null to "", successor.supplyId to successor.serialOrLot, "only what the command carries")
        assertEquals("", successor.notes)
        assertEquals(successor, stored.get(successor.id))
        assertEquals("2026-09-29", stored.get(InstalledComponentId("p4"))?.removedOn)
    }

    @Test
    fun thePredecessorKeepsItsComposition() = runTest {
        seedTree()
        val tray = stored.get(InstalledComponentId("t"))!!
        val result = ok(replace.run(InstalledComponentId("t"), replacement(name = "Example Battery Tray", composition = listOf(entry("s1", "2")))))

        assertEquals(tray.composition, result.replaced?.composition, "answered with its own entries")
        assertEquals(tray.composition, stored.get(InstalledComponentId("t"))?.composition, "stored with its own entries")
        assertEquals(setOf("e1", "e2"), stored.entries.filterValues { it.componentId == tray.id }.keys)
        assertEquals(listOf(SupplyId("s1") to 2.0), result.row.composition.map { it.supplyId to it.quantity })
    }

    @Test
    fun anEmptyCompositionInTheCommandGivesTheSuccessorNone() = runTest {
        seed()
        stored.insert(
            installedComponentOf(
                "pack", assetId = "x1", name = "Example Battery Pack", supplyId = "s2", installedOn = "2026-09-01",
                composition = listOf(compositionEntryOf("e5", "s1", 4.0)),
            ),
        )
        val result = ok(replace.run(InstalledComponentId("pack"), replacement(name = "Example Battery Pack")))
        assertNull(result.row.supplyId, "R47-17b: no link sent, none taken")
        assertEquals(emptyList(), result.row.composition, "R47-17b: no composition sent, none taken")
        assertEquals(mapOf("e5" to InMemoryInstalledComponentRepository.StoredEntry(InstalledComponentId("pack"), compositionEntryOf("e5", "s1", 4.0))), stored.entries)
        assertEquals(SupplyId("s2"), result.replaced?.supplyId, "the predecessor keeps its own link")
    }

    @Test
    fun theSuccessorsAndPredecessorsEntryIdSetsAreDisjoint() = runTest {
        seed()
        stored.insert(
            installedComponentOf(
                "pack", assetId = "x1", name = "Example Battery Pack", installedOn = "2026-09-01",
                composition = listOf(compositionEntryOf("e5", "s1", 4.0), compositionEntryOf("e6", "s2", sortOrder = 1)),
            ),
        )
        // The caller sends the predecessor's own entry ids back, and one of its own: every one is ignored.
        val result = ok(
            replace.run(
                InstalledComponentId("pack"),
                replacement(name = "Example Battery Pack", composition = listOf(entry("s1", "4", id = "e5"), entry("s2", id = "e6"), entry("s1", id = "sent"))),
            ),
        )
        val successorIds = result.row.composition.map { it.id }.toSet()
        val predecessorIds = result.replaced!!.composition.map { it.id }.toSet()
        assertEquals(setOf("ic-02", "ic-03", "ic-04"), successorIds, "minted fresh, in list order")
        assertEquals(setOf("e5", "e6"), predecessorIds)
        assertTrue(successorIds.intersect(predecessorIds).isEmpty(), "disjoint")
        assertEquals(setOf("e5", "e6"), stored.entries.filterValues { it.componentId == InstalledComponentId("pack") }.keys)
        assertEquals(successorIds, stored.entries.filterValues { it.componentId == result.row.id }.keys)
    }

    @Test
    fun aCompositionNamingAnArchivedItemIsRefusedByIndexOnReplace() = runTest {
        seedTree()
        assertRefused(
            listOf(
                InstalledComponentProblem.EntrySupplyItemArchived(1),
                InstalledComponentProblem.EntrySupplyItemMissing(2),
                InstalledComponentProblem.EntrySupplyItemArchived(3),
            ),
            transactions = 1,
        ) {
            replace.run(InstalledComponentId("u"), replacement(name = "Example Fan", composition = listOf(entry("s1"), entry("s9"), entry("s404"), entry("s9"))))
        }
    }

    @Test
    fun anArchivedSupplyItemIsRefusedForTheSuccessorEvenIfThePredecessorNamesIt() = runTest {
        seedTree()
        stored.insert(
            installedComponentOf(
                "old-pack", assetId = "x1", name = "Example Old Pack", supplyId = "s9", installedOn = "2026-09-01",
                composition = listOf(compositionEntryOf("e6", "s9", 4.0)),
            ),
        )
        assertRefused(listOf(InstalledComponentProblem.SupplyItemArchived), transactions = 1) {
            replace.run(InstalledComponentId("old-pack"), replacement(name = "Example Old Pack", supplyId = "s9"))
        }
        assertRefused(listOf(InstalledComponentProblem.EntrySupplyItemArchived(0)), transactions = 1) {
            replace.run(InstalledComponentId("old-pack"), replacement(name = "Example Old Pack", composition = listOf(entry("s9", "4"))))
        }
        // p2a's composition names s9 too: its successor may not.
        assertRefused(listOf(InstalledComponentProblem.EntrySupplyItemArchived(0)), transactions = 1) {
            replace.run(InstalledComponentId("p2a"), replacement(name = "Example Terminal", composition = listOf(entry("s9", "2"))))
        }
        assertRefused(listOf(InstalledComponentProblem.SupplyItemMissing), transactions = 1) {
            replace.run(InstalledComponentId("old-pack"), replacement(name = "Example Old Pack", supplyId = "s404"))
        }
    }

    @Test
    fun replacingARemovedRowIsAlreadyRemoved() = runTest {
        seedTree()
        // p3 is removed: refused before its archived link and its early date are looked at.
        assertRefused(listOf(InstalledComponentProblem.AlreadyRemoved), transactions = 1) {
            replace.run(InstalledComponentId("p3"), replacement(replacedOn = "2026-08-01", name = "Position 3", supplyId = "s9"))
        }
        assertRefused(listOf(InstalledComponentProblem.NoSuchInstalledComponent), transactions = 1) {
            replace.run(InstalledComponentId("c404"), replacement())
        }
        // A replaced row is closed, so a second replace of it is refused the same way: one successor per predecessor.
        ok(replace.run(InstalledComponentId("u"), replacement(replacedOn = "2026-09-28", name = "Example Fan")))
        assertRefused(listOf(InstalledComponentProblem.AlreadyRemoved), transactions = 1) {
            replace.run(InstalledComponentId("u"), replacement(replacedOn = "2026-09-29", name = "Example Fan"))
        }
        assertEquals(1, stored.all().count { it.replacesId == InstalledComponentId("u") })
    }

    @Test
    fun aReplaceShapeRefusalOpensNoTransactionAndAnEarlyDateIsRefused() = runTest {
        seedTree()
        assertRefused(
            listOf(
                InstalledComponentProblem.NameRequired,
                InstalledComponentProblem.BadDate("replacedOn"),
                InstalledComponentProblem.QuantityInvalid(1),
            ),
            transactions = 0,
        ) {
            replace.run(InstalledComponentId("c404"), replacement(replacedOn = "2026-9-20", name = " ", composition = listOf(entry("s1"), entry("s404", "0"))))
        }
        assertRefused(listOf(InstalledComponentProblem.AfterToday("replacedOn")), transactions = 0) {
            replace.run(InstalledComponentId("u"), replacement(replacedOn = "2026-10-01", name = "Example Fan"))
        }
        assertRefused(listOf(InstalledComponentProblem.BadDate("replacedOn")), transactions = 0) {
            replace.run(InstalledComponentId("u"), replacement(replacedOn = "", name = "Example Fan"))
        }
        assertEquals(0, uow.writesEntered, "no transaction for any of them")
        // The supply step answers before the date step; then the date, against this row and its current subtree.
        assertRefused(listOf(InstalledComponentProblem.SupplyItemArchived), transactions = 1) {
            replace.run(InstalledComponentId("u"), replacement(replacedOn = "2026-09-24", name = "Example Fan", supplyId = "s9"))
        }
        assertRefused(listOf(InstalledComponentProblem.RemovedBeforeInstalled("replacedOn")), transactions = 1) {
            replace.run(InstalledComponentId("u"), replacement(replacedOn = "2026-09-24", name = "Example Fan"))
        }
        assertRefused(listOf(InstalledComponentProblem.RemovedBeforeInstalled("replacedOn")), transactions = 1) {
            replace.run(InstalledComponentId("t"), replacement(replacedOn = "2026-09-09", name = "Example Battery Tray"))
        }
        assertEquals("2026-09-25", ok(replace.run(InstalledComponentId("u"), replacement(replacedOn = "2026-09-25", name = "Example Fan"))).row.installedOn)
    }

    @Test
    fun replacingAPackClosesItsSubtreeAndTheSuccessorStartsEmpty() = runTest {
        seedTree()
        val before = stored.all()
        now = 12_000L
        val result = ok(replace.run(InstalledComponentId("t"), replacement(name = "Example Battery Tray")))

        assertEquals(1, uow.writesEntered, "one write")
        val closed = listOf("p1", "p2", "p2a").map { row(before, it).copy(removedOn = "2026-09-20", updatedAt = 12_000L) }
        assertEquals(closed, result.closed, "every current descendant, by id, on the same date and stamp, each with its parent")
        assertEquals(row(before, "t").copy(removedOn = "2026-09-20", updatedAt = 12_000L), result.replaced)
        val after = stored.all()
        closed.forEach { assertEquals(it, row(after, it.id.value), "${it.id.value} stored closed") }
        listOf("p3", "u", "ro").forEach { assertEquals(row(before, it), row(after, it), "$it untouched") }
        assertTrue(after.none { it.parentId == result.row.id }, "the successor starts with no children")
        assertEquals(listOf(result.row.id), after.filter { it.isCurrent && it.assetId == AssetId("x1") && it.parentId == null && it.id != InstalledComponentId("u") }.map { it.id })
    }

    @Test
    fun replacingOnAHeldAssetThrowsAfterEveryCheck() = runTest {
        seed()
        stored.insert(installedComponentOf("h-tray", assetId = "h1", name = "Example Element", installedOn = "2026-09-01"))
        stored.insert(installedComponentOf("h-seat", assetId = "h1", parentId = "h-tray", name = "Example Seat", installedOn = "2026-09-01"))
        stored.insert(installedComponentOf("h-old", assetId = "h1", name = "Example Old Element", removedOn = "2026-08-01"))
        install.transfers.append(transferOf("out-h1", assetId = "h1"))
        assertRefused(listOf(InstalledComponentProblem.NameRequired), transactions = 0) {
            replace.run(InstalledComponentId("h-tray"), replacement(name = ""))
        }
        assertRefused(listOf(InstalledComponentProblem.AlreadyRemoved), transactions = 1) {
            replace.run(InstalledComponentId("h-old"), replacement(name = "Example Element"))
        }
        assertRefused(listOf(InstalledComponentProblem.SupplyItemArchived), transactions = 1) {
            replace.run(InstalledComponentId("h-tray"), replacement(name = "Example Element", supplyId = "s9"))
        }
        assertRefused(listOf(InstalledComponentProblem.RemovedBeforeInstalled("replacedOn")), transactions = 1) {
            replace.run(InstalledComponentId("h-tray"), replacement(replacedOn = "2026-08-20", name = "Example Element"))
        }
        val before = tables()
        val thrown = assertFailsWith<AssetTransferredOut> {
            replace.run(InstalledComponentId("h-tray"), replacement(name = "Example Element", supplyId = "s1", composition = listOf(entry("s2", "4"))))
        }
        assertEquals(AssetId("h1"), thrown.assetId)
        assertEquals(before, tables(), "nothing written: no successor, the predecessor and its subtree current")
    }

    // ---- row 36: update (C19; R47-3, R47-15) ------------------------------------------------------------------------

    private fun edit(
        name: String = "Example Fan",
        supplyId: String? = null,
        composition: List<CompositionInput> = emptyList(),
        serialOrLot: String = "",
        installedOn: String? = "2026-09-25",
        notes: String = "",
        sortOrder: Int = 0,
    ) = UpdateInstalledComponentCommand(
        name = name,
        supplyId = supplyId?.let(::SupplyId),
        composition = composition,
        serialOrLot = serialOrLot,
        installedOn = installedOn,
        notes = notes,
        sortOrder = sortOrder,
    )

    /** [row]'s editable set as stored, each entry sent with its own id: an edit that changes nothing. */
    private fun editOf(row: InstalledComponent) = UpdateInstalledComponentCommand(
        name = row.name,
        supplyId = row.supplyId,
        composition = row.composition.map { CompositionInput(it.id, it.supplyId, it.quantity.toString(), it.unit) },
        serialOrLot = row.serialOrLot,
        installedOn = row.installedOn,
        notes = row.notes,
        sortOrder = row.sortOrder,
    )

    @Test
    fun editableFieldsMoveUpdatedAt() = runTest {
        seedTree()
        val fan = stored.get(InstalledComponentId("u"))!!
        now = 12_000L
        val result = ok(
            update.run(
                InstalledComponentId("u"),
                edit(
                    name = " Example Fan Assembly ", supplyId = "s2", serialOrLot = " SN-0009 ", installedOn = "2026-09-24",
                    notes = " left side ", sortOrder = 3,
                ),
            ),
        )
        val expected = fan.copy(
            name = "Example Fan Assembly", supplyId = SupplyId("s2"), serialOrLot = "SN-0009", installedOn = "2026-09-24",
            notes = "left side", sortOrder = 3, updatedAt = 12_000L,
        )
        assertEquals(expected, result.row, "trimmed; the asset, parent, removal date, replacesId and createdAt kept")
        assertEquals(expected, stored.get(InstalledComponentId("u")))
        assertNull(result.replaced)
        assertEquals(emptyList(), result.closed)
        assertEquals(1, uow.writesEntered, "one write")
        assertEquals(1, counted.writes, "one update")

        now = 13_000L
        val cleared = ok(update.run(InstalledComponentId("u"), edit(name = "Example Fan Assembly", installedOn = null, sortOrder = 3))).row
        assertEquals(expected.copy(supplyId = null, serialOrLot = "", installedOn = null, notes = "", updatedAt = 13_000L), cleared)
    }

    @Test
    fun anEditChangingNothingIsUnchangedAndWritesNothing() = runTest {
        seedTree()
        val tray = stored.get(InstalledComponentId("t"))!!
        val same = editOf(tray).let { it.copy(name = "  ${it.name} ", notes = " ", serialOrLot = "\t") }
        val before = tables()
        now = 12_000L
        assertEquals(listOf(InstalledComponentProblem.Unchanged), refused(update.run(tray.id, same)))
        assertEquals(0, counted.writes, "the write counter: nothing reached the port")
        assertEquals(0, minted, "nothing minted")
        assertEquals(before, tables(), "nothing written")

        // A change to the composition alone is a change: e1's quantity.
        val recounted = same.copy(composition = same.composition.mapIndexed { i, input -> if (i == 0) input.copy(quantity = "5") else input })
        val written = ok(update.run(tray.id, recounted)).row
        assertEquals(listOf("e1" to 5.0, "e2" to 1.0), written.composition.map { it.id to it.quantity }, "ids kept")
        assertEquals(12_000L, written.updatedAt)
        assertEquals(1, counted.writes)

        // The same entries sent without their ids are new entries: written, ids minted.
        val renewed = ok(update.run(tray.id, recounted.copy(composition = recounted.composition.map { it.copy(id = null) }))).row
        assertEquals(listOf("ic-01", "ic-02"), renewed.composition.map { it.id })
        assertEquals(2, counted.writes)
    }

    @Test
    fun aRemovedRowMayBeCorrected() = runTest {
        seedTree()
        val gone = stored.get(InstalledComponentId("p3"))!!
        now = 12_000L
        val result = ok(
            update.run(gone.id, edit(name = "Position 3 (old)", installedOn = "2026-09-05", notes = "fitted the day it left", supplyId = "s1")),
        )
        val expected = gone.copy(
            name = "Position 3 (old)", installedOn = "2026-09-05", notes = "fitted the day it left", supplyId = SupplyId("s1"),
            updatedAt = 12_000L,
        )
        assertEquals(expected, result.row, "corrected; still removed on 2026-09-05, still under t")
        assertEquals(expected, stored.get(gone.id))
    }

    @Test
    fun anInstallDateAfterTheRemovalDateIsRefused() = runTest {
        seedTree()
        assertRefused(listOf(InstalledComponentProblem.RemovedBeforeInstalled("installedOn")), transactions = 1) {
            update.run(InstalledComponentId("p3"), edit(name = "Position 3", installedOn = "2026-09-06"))
        }
        // A current row has no removal date to be after.
        assertEquals("2026-09-29", ok(update.run(InstalledComponentId("u"), edit(installedOn = "2026-09-29"))).row.installedOn)
    }

    @Test
    fun anEditShapeRefusalOpensNoTransaction() = runTest {
        seedTree()
        assertRefused(
            listOf(
                InstalledComponentProblem.NameRequired,
                InstalledComponentProblem.BadDate("installedOn"),
                InstalledComponentProblem.QuantityInvalid(1),
            ),
            transactions = 0,
        ) {
            update.run(InstalledComponentId("c404"), edit(name = "", installedOn = "25/09/2026", composition = listOf(entry("s1"), entry("s9", "-1"))))
        }
        assertRefused(listOf(InstalledComponentProblem.BadDate("installedOn")), transactions = 0) {
            update.run(InstalledComponentId("u"), edit(installedOn = ""))
        }
        assertEquals(0, uow.writesEntered, "no transaction for any of them")
        assertRefused(listOf(InstalledComponentProblem.NoSuchInstalledComponent), transactions = 1) {
            update.run(InstalledComponentId("c404"), edit())
        }
    }

    @Test
    fun aKeptArchivedLinkOrEntryIsAccepted() = runTest {
        seedTree()
        stored.insert(
            installedComponentOf(
                "old-pack", assetId = "x1", name = "Example Old Pack", supplyId = "s9", installedOn = "2026-09-01",
                composition = listOf(compositionEntryOf("e6", "s9", 4.0), compositionEntryOf("e7", "s1", sortOrder = 1)),
            ),
        )
        // The stored link stays; a new entry naming s9 is taken because the stored composition already names s9 (by
        // SupplyItem, not by entry id).
        val kept = ok(
            update.run(
                InstalledComponentId("old-pack"),
                edit(
                    name = "Example Old Pack", supplyId = "s9", installedOn = "2026-09-01",
                    composition = listOf(entry("s9", "4", id = "e6"), entry("s9", "2"), entry("s1", id = "e7")),
                ),
            ),
        ).row
        assertEquals(SupplyId("s9"), kept.supplyId)
        assertEquals(listOf("e6" to "s9", "ic-01" to "s9", "e7" to "s1"), kept.composition.map { it.id to it.supplyId.value })
        // p2a's composition names s9 (e3): a correction keeping it is taken.
        val terminal = stored.get(InstalledComponentId("p2a"))!!
        assertEquals(
            listOf("e3"),
            ok(update.run(terminal.id, editOf(terminal).copy(notes = "checked"))).row.composition.map { it.id },
        )
    }

    @Test
    fun aNewArchivedLinkOrEntryIsRefused() = runTest {
        seedTree()
        stored.insert(installedComponentOf("direct-only", assetId = "x1", name = "Example Old Cell", supplyId = "s9", installedOn = "2026-09-01"))
        assertRefused(listOf(InstalledComponentProblem.SupplyItemArchived), transactions = 1) {
            update.run(InstalledComponentId("u"), edit(supplyId = "s9"))
        }
        assertRefused(listOf(InstalledComponentProblem.EntrySupplyItemArchived(1)), transactions = 1) {
            update.run(InstalledComponentId("u"), edit(composition = listOf(entry("s1"), entry("s9"))))
        }
        // p2a's composition names s9, its direct link does not: a direct s9 is new.
        val terminal = stored.get(InstalledComponentId("p2a"))!!
        assertRefused(listOf(InstalledComponentProblem.SupplyItemArchived), transactions = 1) {
            update.run(terminal.id, editOf(terminal).copy(supplyId = SupplyId("s9")))
        }
        // direct-only's link names s9, its composition does not: an entry naming s9 is new.
        assertRefused(listOf(InstalledComponentProblem.EntrySupplyItemArchived(0)), transactions = 1) {
            update.run(InstalledComponentId("direct-only"), edit(name = "Example Old Cell", supplyId = "s9", installedOn = "2026-09-01", composition = listOf(entry("s9"))))
        }
        assertRefused(listOf(InstalledComponentProblem.SupplyItemMissing), transactions = 1) {
            update.run(InstalledComponentId("u"), edit(supplyId = "s404", composition = listOf(entry("s404"))))
        }
        assertRefused(listOf(InstalledComponentProblem.EntrySupplyItemMissing(0)), transactions = 1) {
            update.run(InstalledComponentId("u"), edit(composition = listOf(entry("s404"))))
        }
    }

    @Test
    fun theCompositionIsReplacedWholeKeepingOwnedIds() = runTest {
        seedTree()
        val tray = stored.get(InstalledComponentId("t"))!!
        val base = editOf(tray)
        // e2 moved first and recounted, a new entry, e1, and p2a's e3: an id another row owns is minted fresh.
        val result = ok(
            update.run(
                tray.id,
                base.copy(composition = listOf(entry("s2", "3", " m ", id = "e2"), entry("s1", "2"), entry("s1", "4", id = "e1"), entry("s1", id = "e3"))),
            ),
        ).row
        val expected = listOf(
            compositionEntryOf("e2", "s2", quantity = 3.0, unit = "m", sortOrder = 0),
            compositionEntryOf("ic-01", "s1", quantity = 2.0, unit = "ea", sortOrder = 1),
            compositionEntryOf("e1", "s1", quantity = 4.0, unit = "ea", sortOrder = 2),
            compositionEntryOf("ic-02", "s1", quantity = 1.0, unit = "ea", sortOrder = 3),
        )
        assertEquals(expected, result.composition)
        assertEquals(expected, stored.get(tray.id)?.composition)
        assertEquals(InstalledComponentId("p2a"), stored.entries.getValue("e3").componentId, "e3 stays on p2a")
        assertEquals(setOf("e1", "e2", "ic-01", "ic-02"), stored.entries.filterValues { it.componentId == tray.id }.keys)

        // An empty list empties it, and only its own entries go.
        assertEquals(emptyList(), ok(update.run(tray.id, base.copy(composition = emptyList()))).row.composition)
        assertEquals(setOf("e3"), stored.entries.keys)
    }

    @Test
    fun aRepeatedEntryIdKeepsTheFirstAndMintsTheRest() = runTest {
        seedTree()
        val tray = stored.get(InstalledComponentId("t"))!!
        val result = ok(
            update.run(tray.id, editOf(tray).copy(composition = listOf(entry("s1", "4", id = "e1"), entry("s1", "4", id = "e1"), entry("s2", id = "e2")))),
        ).row
        assertEquals(listOf("e1", "ic-01", "e2"), result.composition.map { it.id }, "C-7: the first occurrence keeps it")
        assertEquals(setOf("e1", "ic-01", "e2"), stored.entries.filterValues { it.componentId == tray.id }.keys)
    }

    @Test
    fun updatingOnAHeldAssetThrowsAfterEveryCheck() = runTest {
        seed()
        stored.insert(installedComponentOf("h-tray", assetId = "h1", name = "Example Element", installedOn = "2026-09-01"))
        stored.insert(installedComponentOf("h-old", assetId = "h1", name = "Example Old Element", removedOn = "2026-08-01"))
        install.transfers.append(transferOf("out-h1", assetId = "h1"))
        val element = stored.get(InstalledComponentId("h-tray"))!!
        assertRefused(listOf(InstalledComponentProblem.NameRequired), transactions = 0) {
            update.run(element.id, editOf(element).copy(name = " "))
        }
        assertRefused(listOf(InstalledComponentProblem.RemovedBeforeInstalled("installedOn")), transactions = 1) {
            update.run(InstalledComponentId("h-old"), edit(name = "Example Old Element", installedOn = "2026-08-02"))
        }
        assertRefused(listOf(InstalledComponentProblem.SupplyItemArchived), transactions = 1) {
            update.run(element.id, editOf(element).copy(supplyId = SupplyId("s9")))
        }
        assertRefused(listOf(InstalledComponentProblem.Unchanged), transactions = 1) { update.run(element.id, editOf(element)) }
        val before = tables()
        val thrown = assertFailsWith<AssetTransferredOut> { update.run(element.id, editOf(element).copy(notes = "checked")) }
        assertEquals(AssetId("h1"), thrown.assetId)
        assertEquals(before, tables(), "nothing written")
    }

    @Test
    fun aRowInstalledAfterTodayOnAnotherPhoneTakesANotesOnlyEdit() = runTest {
        seed()
        // A restore carries the install date as written, even one after this phone's today: unchanged, it is not judged.
        stored.insert(installedComponentOf("early", assetId = "x1", name = "Example Charger", installedOn = "2026-10-03"))
        val row = stored.get(InstalledComponentId("early"))!!
        now = 12_000L
        val result = ok(update.run(row.id, editOf(row).copy(notes = "checked")))
        assertEquals(row.copy(notes = "checked", updatedAt = 12_000L), result.row, "the stored install date is not judged again")
        assertEquals(result.row, stored.get(row.id))
    }

    @Test
    fun aChangedInstallDateAfterTodayIsStillRefused() = runTest {
        seedTree()
        stored.insert(installedComponentOf("early", assetId = "x1", name = "Example Charger", installedOn = "2026-10-03"))
        // A changed date is judged against today; telling it from the stored one needs the row, so inside the write.
        assertRefused(listOf(InstalledComponentProblem.AfterToday("installedOn")), transactions = 1) {
            update.run(InstalledComponentId("u"), edit(installedOn = "2026-10-01"))
        }
        assertRefused(listOf(InstalledComponentProblem.AfterToday("installedOn")), transactions = 1) {
            update.run(InstalledComponentId("early"), edit(name = "Example Charger", installedOn = "2026-10-04"))
        }
        val moved = ok(update.run(InstalledComponentId("early"), edit(name = "Example Charger", installedOn = "2026-09-30"))).row
        assertEquals("2026-09-30", moved.installedOn, "a changed date of today or earlier is taken")
    }

    @Test
    fun aRowRemovedAfterTodayOnAnotherPhoneIsStillEditable() = runTest {
        seed()
        // A restore carries the removal date as written, even one after this phone's today (no today on restore).
        stored.insert(
            installedComponentOf("later", assetId = "x1", name = "Example Inverter", installedOn = "2026-09-01", removedOn = "2026-10-05"),
        )
        val row = stored.get(InstalledComponentId("later"))!!
        now = 12_000L
        val result = ok(update.run(row.id, editOf(row).copy(name = "Example Inverter (spare)")))
        assertEquals(row.copy(name = "Example Inverter (spare)", updatedAt = 12_000L), result.row, "the stored removal date is not judged")
        assertEquals(result.row, stored.get(row.id))
    }

    // ---- row 39: no inference ---------------------------------------------------------------------------------------

    @Test
    fun installingCreatesNoApplicabilityEventOrLineAndApplicabilityCreatesNoComponent() = runTest {
        seed()
        val catalog = install.supplyItems.all()
        // A link and a composition, through install, replace and edit: nothing but installed components is written.
        val pack = installed(
            command(name = "Example Battery Pack", supplyId = "s2", composition = listOf(entry("s1", "4")), installedOn = "2026-09-01"),
        )
        val successor = ok(
            replace.run(pack.id, replacement(name = "Example Battery Pack", supplyId = "s2", composition = listOf(entry("s1", "4")))),
        ).row
        ok(update.run(successor.id, editOf(successor).copy(composition = editOf(successor).composition + entry("s2", "1"))))
        assertTrue(install.assetSupplies.rows.isEmpty(), "no applicability row")
        assertTrue(install.events.rows.isEmpty(), "no event, so no event line")
        assertTrue(install.profiles.all().isEmpty(), "no quick action line")
        assertEquals(catalog, install.supplyItems.all(), "the catalog byte-equal: no SupplyItem written")

        // A row named as a SupplyItem is named is not linked to it.
        val namesake = installed(command(name = "Example 12 V Battery"))
        assertNull(namesake.supplyId)
        assertEquals(emptyList(), namesake.composition)

        // Applicability creates no installed component.
        val rows = stored.all()
        val addAssetSupply = AddAssetSupply(install.assets, install.supplyItems, install.assetSupplies, uow, ids, clock)
        assertTrue(addAssetSupply.run(AddAssetSupplyCommand(AssetId("x2"), SupplyId("s1"), "Battery")) is AssetSupplyResult.Ok)
        assertEquals(rows, stored.all(), "no installed component")
        assertTrue(stored.forAsset(AssetId("x2")).isEmpty())
    }
}

/** #47 (B3b) — [inner], counting the writes that reach it: an edit that changes nothing must reach none. */
private class CountingInstalledComponents(
    private val inner: InstalledComponentRepository,
) : InstalledComponentRepository by inner {
    var writes = 0
        private set

    override suspend fun insert(row: InstalledComponent) {
        writes += 1
        inner.insert(row)
    }

    override suspend fun update(row: InstalledComponent) {
        writes += 1
        inner.update(row)
    }
}
