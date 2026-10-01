package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetSupply
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryAssetSupplyRepository
import com.loosecannon.servicetag.core.testing.InMemorySupplyItemRepository
import com.loosecannon.servicetag.core.testing.RecordingUnitOfWork
import com.loosecannon.servicetag.core.testing.assetSupplyOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.supplyItemOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #15 (C17; R15-3, R15-5, R15-6) — applicability: add, re-role and remove, each one refusal in a fixed step order and
 * nothing written before the last check answers. The in-memory port enforces **no** unique index and no foreign key
 * (`InMemoryAssetSupplyRepository` refuses only a repeated id), so every refusal here is the use case's own check, not
 * a double's. Fictional throughout ("Example RO System").
 */
class AssetSupplyUseCasesTest {

    private val assets = InMemoryAssetRepository()
    private val rows = InMemoryAssetSupplyRepository()
    private val items = InMemorySupplyItemRepository(rows)
    private val uow = RecordingUnitOfWork(assets, items, rows)
    private var minted = 0
    private val ids = IdGenerator { "as-%02d".format(++minted) }
    private var now = 9_000L
    private val clock = Clock { now }
    private val add = AddAssetSupply(assets, items, rows, uow, ids, clock)
    private val update = UpdateAssetSupply(rows, uow, clock)
    private val remove = RemoveAssetSupply(rows, uow)

    private val system = AssetId("x1")
    private val stage = AssetId("x2")
    private val prefilter = SupplyId("s1")
    private val retired = SupplyId("s2")

    /** "Example RO System" (x1) and its child "Example RO Stage Housing" (x2); a live item s1 and an archived s2. */
    private suspend fun seed() {
        assets.upsert(plainAssetOf("x1", "Example RO System"))
        assets.upsert(plainAssetOf("x2", "Example RO Stage Housing").copy(parentAssetId = system))
        items.upsert(supplyItemOf("s1", "Example Prefilter Cartridge"))
        items.upsert(supplyItemOf("s2", "Example RO Membrane", archivedAt = 3_000L))
    }

    private fun refusal(result: AssetSupplyResult): AssetSupplyProblem {
        assertTrue(result is AssetSupplyResult.Refused, "expected a refusal, got $result")
        return result.problem
    }

    private fun ok(result: AssetSupplyResult): AssetSupply {
        assertTrue(result is AssetSupplyResult.Ok, "expected a row, got $result")
        return result.row
    }

    /** [problem], with no transaction opened, no id minted and the rows as they were. */
    private suspend fun assertRefusedWritingNothing(problem: AssetSupplyProblem, result: suspend () -> AssetSupplyResult) {
        val before = rows.all()
        val writes = uow.writesEntered
        val mintedBefore = minted
        assertEquals(problem, refusal(result()))
        assertEquals(writes, uow.writesEntered, "$problem: no transaction")
        assertEquals(mintedBefore, minted, "$problem: no id minted")
        assertEquals(before, rows.all(), "$problem: nothing written")
    }

    // ---- row 36: add, in step order -------------------------------------------------------------------------------

    @Test
    fun anUnknownAssetIsOwnerMissing() = runTest {
        seed()
        assertRefusedWritingNothing(AssetSupplyProblem.OwnerMissing) {
            add.run(AddAssetSupplyCommand(AssetId("x404"), prefilter, "Prefilter"))
        }
        assertRefusedWritingNothing(AssetSupplyProblem.OwnerMissing) {
            add.run(AddAssetSupplyCommand(AssetId("x404"), SupplyId("s404"), " "))
        }
    }

    @Test
    fun anUnknownItemIsSupplyItemMissing() = runTest {
        seed()
        assertRefusedWritingNothing(AssetSupplyProblem.SupplyItemMissing) {
            add.run(AddAssetSupplyCommand(system, SupplyId("s404"), " "))
        }
    }

    @Test
    fun anArchivedItemIsRefusedForANewRow() = runTest {
        seed()
        assertRefusedWritingNothing(AssetSupplyProblem.SupplyItemArchived) {
            add.run(AddAssetSupplyCommand(system, retired, "Membrane"))
        }
        assertRefusedWritingNothing(AssetSupplyProblem.SupplyItemArchived) {
            add.run(AddAssetSupplyCommand(system, retired, ""))
        }
        items.setArchived(retired, archivedAt = null, updatedAt = 4_000L)
        assertEquals("Membrane", ok(add.run(AddAssetSupplyCommand(system, retired, "Membrane"))).role, "unarchived, it is taken again")
    }

    @Test
    fun aBlankRoleIsRoleRequired() = runTest {
        seed()
        for (role in listOf("", "   ", " ​⁠", "﻿\t")) {
            assertRefusedWritingNothing(AssetSupplyProblem.RoleRequired) { add.run(AddAssetSupplyCommand(system, prefilter, role)) }
        }
    }

    @Test
    fun theRoleIsStoredCleaned() = runTest {
        seed()

        val row = ok(add.run(AddAssetSupplyCommand(system, prefilter, " Stage​ 1   pre filter ")))

        assertEquals("Stage 1 pre filter", row.role, "CategoryKey.display: no-break space, zero-width space, doubled spaces")
        assertEquals(AssetSupply("as-01", system, prefilter, "Stage 1 pre filter", createdAt = 9_000L, updatedAt = 9_000L), row)
        assertEquals(listOf(row), rows.all(), "stored as answered")
        assertEquals(1, uow.writesEntered)
    }

    @Test
    fun theSameCleanedTripleIsTaken() = runTest {
        seed()
        ok(add.run(AddAssetSupplyCommand(system, prefilter, "Prefilter")))

        assertRefusedWritingNothing(AssetSupplyProblem.Taken) { add.run(AddAssetSupplyCommand(system, prefilter, " Prefilter​")) }
        assertRefusedWritingNothing(AssetSupplyProblem.Taken) { add.run(AddAssetSupplyCommand(system, prefilter, "Prefilter")) }

        // AC4 and limit 9: another role, another item, another asset, and another casing are each a new row.
        ok(add.run(AddAssetSupplyCommand(system, prefilter, "Spare")))
        ok(add.run(AddAssetSupplyCommand(stage, prefilter, "Prefilter")))
        ok(add.run(AddAssetSupplyCommand(system, prefilter, "prefilter")))
        items.upsert(supplyItemOf("s3", "Example Post-filter"))
        ok(add.run(AddAssetSupplyCommand(system, SupplyId("s3"), "Prefilter")))
        assertEquals(5, rows.all().size)
    }

    @Test
    fun aChildAssetTakesApplicability() = runTest {
        seed()

        val row = ok(add.run(AddAssetSupplyCommand(stage, prefilter, "Cartridge")))

        assertEquals(stage, row.assetId)
        assertEquals(listOf(row), rows.forAsset(stage))
        assertEquals(emptyList(), rows.forAsset(system), "the parent takes nothing by it")
    }

    /** One refusal, the first in C17's order, when a command is wrong several ways. */
    @Test
    fun theAddStepOrderIsOwnerItemArchivedRoleTaken() = runTest {
        seed()
        ok(add.run(AddAssetSupplyCommand(system, prefilter, "Prefilter")))

        assertEquals(AssetSupplyProblem.OwnerMissing, refusal(add.run(AddAssetSupplyCommand(AssetId("x404"), retired, ""))))
        assertEquals(AssetSupplyProblem.SupplyItemMissing, refusal(add.run(AddAssetSupplyCommand(system, SupplyId("s404"), ""))))
        assertEquals(AssetSupplyProblem.SupplyItemArchived, refusal(add.run(AddAssetSupplyCommand(system, retired, ""))))
        assertEquals(AssetSupplyProblem.RoleRequired, refusal(add.run(AddAssetSupplyCommand(system, prefilter, ""))))
        assertEquals(AssetSupplyProblem.Taken, refusal(add.run(AddAssetSupplyCommand(system, prefilter, "Prefilter"))))
    }

    // ---- row 38: update, remove, suggestions ----------------------------------------------------------------------

    @Test
    fun reRoleMovesUpdatedAt() = runTest {
        seed()
        val row = ok(add.run(AddAssetSupplyCommand(system, prefilter, "Prefilter")))

        now = 9_500L
        val reRoled = ok(update.run(row.id, UpdateAssetSupplyCommand("  Stage 1 prefilter")))

        assertEquals(row.copy(role = "Stage 1 prefilter", updatedAt = 9_500L), reRoled, "the cleaned role; createdAt held")
        assertEquals(reRoled, rows.get(row.id))
        assertEquals(2, uow.writesEntered)
    }

    @Test
    fun theSameCleanedRoleIsUnchanged() = runTest {
        seed()
        val row = ok(add.run(AddAssetSupplyCommand(system, prefilter, "Prefilter")))

        now = 9_500L
        assertRefusedWritingNothing(AssetSupplyProblem.Unchanged) { update.run(row.id, UpdateAssetSupplyCommand(" Prefilter​ ")) }
        assertEquals(9_000L, rows.get(row.id)!!.updatedAt, "updatedAt held")
    }

    @Test
    fun reRoleOntoATakenTripleIsTaken() = runTest {
        seed()
        val first = ok(add.run(AddAssetSupplyCommand(system, prefilter, "Prefilter")))
        val second = ok(add.run(AddAssetSupplyCommand(system, prefilter, "Spare")))
        val elsewhere = ok(add.run(AddAssetSupplyCommand(stage, prefilter, "Cartridge")))

        assertRefusedWritingNothing(AssetSupplyProblem.Taken) { update.run(second.id, UpdateAssetSupplyCommand(" Prefilter ")) }
        assertEquals("Prefilter", ok(update.run(elsewhere.id, UpdateAssetSupplyCommand("Prefilter"))).role, "another asset's triple is free")
        assertEquals(first, rows.get(first.id))
    }

    @Test
    fun anArchivedItemsRowMayBeReRoled() = runTest {
        seed()
        rows.insert(assetSupplyOf("as9", "x1", "s2", "Membrane"))

        val reRoled = ok(update.run("as9", UpdateAssetSupplyCommand("Stage 2 membrane")))

        assertEquals("Stage 2 membrane", reRoled.role, "a re-role is not a new link (R15-6)")
        assertEquals(retired, reRoled.supplyId)
    }

    @Test
    fun anUpdateOfAnUnknownRowOrToABlankRoleIsRefused() = runTest {
        seed()
        rows.insert(assetSupplyOf("as9", "x1", "s1", "Prefilter"))

        assertRefusedWritingNothing(AssetSupplyProblem.NoSuchAssetSupply) { update.run("as404", UpdateAssetSupplyCommand("")) }
        assertRefusedWritingNothing(AssetSupplyProblem.RoleRequired) { update.run("as9", UpdateAssetSupplyCommand("​ ")) }
    }

    @Test
    fun removeDeletes() = runTest {
        seed()
        val row = ok(add.run(AddAssetSupplyCommand(system, prefilter, "Prefilter")))
        val kept = ok(add.run(AddAssetSupplyCommand(system, prefilter, "Spare")))

        assertEquals(row, ok(remove.run(row.id)), "the removed row is answered")

        assertEquals(listOf(kept), rows.all())
        assertEquals(supplyItemOf("s1", "Example Prefilter Cartridge"), items.get(prefilter), "the item stays")
    }

    @Test
    fun removeOfAnUnknownRowIsRefused() = runTest {
        seed()
        assertRefusedWritingNothing(AssetSupplyProblem.NoSuchAssetSupply) { remove.run("as404") }
    }

    @Test
    fun suggestionsAreTheDistinctCleanedRolesInUse() {
        val inUse = listOf(
            assetSupplyOf("as1", "x1", "s1", "Prefilter"),
            assetSupplyOf("as2", "x2", "s1", "Prefilter"),
            assetSupplyOf("as3", "x2", "s2", "membrane"),
            assetSupplyOf("as4", "x3", "s3", "Anode kit"),
            assetSupplyOf("as5", "x3", "s3", "prefilter"),
            assetSupplyOf("as6", "x4", "s1", " Prefilter​"),
        )

        assertEquals(
            listOf("Anode kit", "membrane", "Prefilter", "prefilter"),
            AssetSupplyRoles.suggestions(inUse),
            "distinct on the exact cleaned text (limit 9), case-insensitively ordered, every asset's",
        )
        assertEquals(emptyList(), AssetSupplyRoles.suggestions(emptyList()))
    }

    // ---- AC11, the shape B3 can show: one treatment asset, several replaceable items, no child asset -----------------

    @Test
    fun aTreatmentAssetTakesSeveralItemsEachWithItsOwnIdentitySpecsAndRole() = runTest {
        assets.upsert(plainAssetOf("x1", "Example RO System"))
        val save = SaveSupplyItem(items, uow, IdGenerator { "item-%02d".format(++minted) }, clock)
        fun item(name: String, partNumber: String, vararg specs: Pair<String, String>) = SupplyItemCommand(
            name, "Water treatment", "Example Filters Co.", "", partNumber, "ea", "",
            specs.map { (label, value) -> SpecificationInput(id = null, key = "", label = label, value = value, unit = "") },
        )
        val cartridge = save.run(null, item("Example Prefilter Cartridge", "EF-PF10-5", "Micron rating" to "5", "Length" to "10 in")).item
        val membrane = save.run(null, item("Example RO Membrane", "EF-RO75", "Capacity" to "75 gpd")).item
        val polish = save.run(null, item("Example Post-filter", "EF-PO10", "Media" to "Carbon", "Length" to "10 in")).item

        val stages = listOf(cartridge to "Stage 1 prefilter", membrane to "Stage 2 membrane", polish to "Stage 3 post-filter")
        stages.forEach { (supply, role) -> ok(add.run(AddAssetSupplyCommand(system, supply.id, role))) }

        assertEquals(
            listOf(
                "Example Prefilter Cartridge" to "Stage 1 prefilter",
                "Example RO Membrane" to "Stage 2 membrane",
                "Example Post-filter" to "Stage 3 post-filter",
            ),
            rows.forAsset(system).map { items.get(it.supplyId)!!.name to it.role },
        )
        assertEquals(3, listOf(cartridge, membrane, polish).map { it.id }.toSet().size, "three identities")
        assertEquals(
            listOf(listOf("micron_rating", "length"), listOf("capacity"), listOf("media", "length")),
            listOf(cartridge, membrane, polish).map { s -> s.specifications.map { it.key } },
            "each item its own specifications, keys unique per item only",
        )
        assertEquals(listOf("x1"), assets.all().map { it.id.value }, "no child asset was created")
    }
}
