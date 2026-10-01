package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.RecordingUnitOfWork
import com.loosecannon.servicetag.core.testing.compositionEntryOf
import com.loosecannon.servicetag.core.testing.installedComponentOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.supplyItemOf
import com.loosecannon.servicetag.core.testing.transferOf
import com.loosecannon.servicetag.core.transfer.AssetTransferredOut
import com.loosecannon.servicetag.core.transfer.HeldWriteGuard
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #47 (C15–C17; R47-3, R47-6, R47-8, R47-11) — install and remove. Command-only problems are collected before any
 * transaction, so a shape refusal opens none; the other rows are checked inside the one write, one step at a time,
 * before anything is minted or written. Every write goes through the guarded port over the core double
 * ([BackupInstall]'s, which refuses what the two tables refuse), so a held asset's write throws after every check.
 * Fictional throughout ("Example UPS").
 */
class InstalledComponentUseCasesTest {

    private val install = BackupInstall()
    private val guard = HeldWriteGuard(
        install.transfers, install.events, install.definitions, install.profiles, install.groups, install.schedules,
        install.serviceCases, install.links,
    )
    private val components = guard.installedComponents(install.installedComponents)
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
    private val installComponent = InstallComponent(install.assets, install.supplyItems, components, uow, ids, clock, today)
    private val remove = RemoveInstalledComponent(components, uow, clock, today)

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
}
