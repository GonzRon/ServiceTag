package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.Backup
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetSuccession
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.backupOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.successionOf
import com.loosecannon.servicetag.core.testing.transferOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * #86 (B1, row 4; C4; R86-19) — a succession is its own row, identity by its id, and a merge only ever inserts one.
 * With S′ = this phone's rows and the plan's rows still INSERT, in the order MS1 → MS2 → MS3 → MS4 → M2:
 *
 * - **MS1** the id here: every field equal → IDENTICAL; any difference → CONFLICT `CONTENT_DIFFERS` (no UPDATE);
 * - **MS2** an end neither here nor inserted by this plan → `OWNER_NOT_AVAILABLE`, naming that asset;
 * - **MS3** another row of S′ naming its predecessor or its successor → `SUCCESSION_TAKEN`, naming the holder's row
 *   — this phone's, or one earlier in the plan;
 * - **MS4** an inserted row on a cycle of S′ → `SUCCESSION_CYCLE` on each inserted row of it, naming every row on it;
 * - **M2 (#77)** an insert naming a held asset at either end → `ASSET_TRANSFERRED_OUT`.
 *
 * An in-plan holder or cycle cannot come from a decoded archive (the codec refuses both), so those cases build the
 * [Backup] directly; every other archive goes through the real decode. The names are fictional.
 */
class MergePlannerSuccessionTest {

    private val heater = plainAssetOf("a1", "Example Water Heater")
    private val newHeater = plainAssetOf("a2", "Example Water Heater, second")
    private val thirdHeater = plainAssetOf("a3", "Example Water Heater, third")
    private val pump = plainAssetOf("a4", "Sample Pool Pump")
    private val estate = listOf(heater, newHeater, thirdHeater, pump)

    /** The three first lists by position — the assets, then no tags and no 2.6 tombstones. */
    private fun data(assets: List<Asset>, rows: List<AssetSuccession>, records: List<TransferRecord> = emptyList()) = BackupData(
        assets.map { it.toDto() }, emptyList(), emptyList(),
        assetSuccessions = rows.map { it.toDto() }, transferRecords = records.map { it.toDto() },
    )

    private fun decoded(rows: List<AssetSuccession>, assets: List<Asset> = estate): Backup =
        BackupCodec.decode(archiveOf(data(assets, rows)))

    private fun planOf(
        incoming: Backup,
        assets: List<Asset> = estate,
        successions: List<AssetSuccession> = emptyList(),
        transfers: List<TransferRecord> = emptyList(),
    ): MergePlan = mergePlanOf(
        incoming,
        MergeSnapshot(assets = assets, successions = successions, transfers = transfers, attachmentStoreConfigured = true),
    )

    private fun MergePlan.successions() = decisions.filter { it.table == MergeTable.SUCCESSIONS }

    private fun insert(id: String) = MergeDecision(MergeTable.SUCCESSIONS, id, MergeVerdict.INSERT)
    private fun identical(id: String) = MergeDecision(MergeTable.SUCCESSIONS, id, MergeVerdict.IDENTICAL)
    private fun conflict(id: String, reason: MergeReason, detail: String) =
        MergeDecision(MergeTable.SUCCESSIONS, id, MergeVerdict.CONFLICT, reason, detail)

    private val replaced = successionOf("s1", predecessor = "a1", successor = "a2")

    // --- MS1 ---------------------------------------------------------------------------------------

    @Test
    fun anEqualSuccessionIsIdenticalAndADifferentOneConflicts() {
        val same = planOf(decoded(listOf(replaced)), successions = listOf(replaced))
        assertEquals(listOf(identical("s1")), same.successions())
        assertTrue(same.applicable)
        assertEquals(emptyList(), same.writes.successions)

        for (there in listOf(replaced.copy(replacedOn = "2026-09-21"), replaced.copy(createdAt = replaced.createdAt + 1))) {
            val differs = planOf(decoded(listOf(there)), successions = listOf(replaced))
            assertEquals(listOf(conflict("s1", MergeReason.CONTENT_DIFFERS, "s1")), differs.successions(), there.toString())
            assertFalse(differs.applicable)
            assertEquals(MergeWrites(), differs.writes, "no partial merge")
        }
    }

    // --- MS2 ---------------------------------------------------------------------------------------

    @Test
    fun anEndNeitherHereNorInsertedIsOwnerNotAvailable() {
        val noSuccessor = planOf(decoded(listOf(replaced), assets = listOf(heater, newHeater)), assets = listOf(heater))
        assertEquals(listOf(insert("s1")), noSuccessor.successions(), "the plan inserts a2, so the row stands")
        assertEquals(listOf(replaced), noSuccessor.writes.successions)
        assertEquals(listOf(newHeater), noSuccessor.writes.assets)

        // Built directly: the codec would refuse an archive whose row names an asset it does not carry.
        val predecessorGone = planOf(backupOf(data(listOf(newHeater), listOf(replaced))), assets = listOf(newHeater))
        assertEquals(listOf(conflict("s1", MergeReason.OWNER_NOT_AVAILABLE, "a1")), predecessorGone.successions())
        val successorGone = planOf(backupOf(data(listOf(heater), listOf(replaced))), assets = listOf(heater))
        assertEquals(listOf(conflict("s1", MergeReason.OWNER_NOT_AVAILABLE, "a2")), successorGone.successions())
        assertFalse(successorGone.applicable)
    }

    // --- MS3 ---------------------------------------------------------------------------------------

    /** Taken by this phone's row, on each side: the holder is named. */
    @Test
    fun aPredecessorOrSuccessorTakenByALocalRowIsSuccessionTaken() {
        val local = successionOf("s0", predecessor = "a1", successor = "a3")

        val predecessor = planOf(decoded(listOf(replaced)), successions = listOf(local))
        assertEquals(listOf(conflict("s1", MergeReason.SUCCESSION_TAKEN, "s0")), predecessor.successions())
        assertFalse(predecessor.applicable)

        val successor = planOf(decoded(listOf(successionOf("s1", predecessor = "a4", successor = "a3"))), successions = listOf(local))
        assertEquals(listOf(conflict("s1", MergeReason.SUCCESSION_TAKEN, "s0")), successor.successions())

        // The same pair under a second id is taken too: the phone mints the successor, so only a hand-built file has it.
        val twin = planOf(decoded(listOf(local.copy(id = "s9"))), successions = listOf(local))
        assertEquals(listOf(conflict("s9", MergeReason.SUCCESSION_TAKEN, "s0")), twin.successions())
    }

    /** Taken by an earlier row of the same plan, on each side (built directly: the codec refuses such a file). */
    @Test
    fun aPredecessorOrSuccessorTakenByAnInPlanRowIsSuccessionTaken() {
        val predecessor = planOf(
            backupOf(data(estate, listOf(replaced, successionOf("s2", predecessor = "a1", successor = "a3")))),
        )
        assertEquals(listOf(insert("s1"), conflict("s2", MergeReason.SUCCESSION_TAKEN, "s1")), predecessor.successions())
        assertFalse(predecessor.applicable)

        val successor = planOf(
            backupOf(data(estate, listOf(replaced, successionOf("s2", predecessor = "a4", successor = "a2")))),
        )
        assertEquals(listOf(insert("s1"), conflict("s2", MergeReason.SUCCESSION_TAKEN, "s1")), successor.successions())
        assertEquals(MergeWrites(), successor.writes)
    }

    // --- MS4 ---------------------------------------------------------------------------------------

    /** A cycle made by the union: each row alone is fine on either phone, together they come back round. */
    @Test
    fun twoAndThreeRowCyclesByUnionAreSuccessionCycle() {
        val two = planOf(decoded(listOf(replaced)), successions = listOf(successionOf("s0", predecessor = "a2", successor = "a1")))
        assertEquals(listOf(conflict("s1", MergeReason.SUCCESSION_CYCLE, "s0, s1")), two.successions())
        assertFalse(two.applicable)

        val three = planOf(
            decoded(listOf(successionOf("s3", predecessor = "a3", successor = "a1"))),
            successions = listOf(successionOf("s1", predecessor = "a1", successor = "a2"), successionOf("s2", predecessor = "a2", successor = "a3")),
        )
        assertEquals(listOf(conflict("s3", MergeReason.SUCCESSION_CYCLE, "s1, s2, s3")), three.successions())

        // Every inserted row of the cycle, when the plan brings more than one of it (built directly).
        val inPlan = planOf(
            backupOf(data(estate, listOf(successionOf("s1", predecessor = "a1", successor = "a2"), successionOf("s2", predecessor = "a2", successor = "a3")))),
            successions = listOf(successionOf("s0", predecessor = "a3", successor = "a1")),
        )
        assertEquals(
            listOf(conflict("s1", MergeReason.SUCCESSION_CYCLE, "s0, s1, s2"), conflict("s2", MergeReason.SUCCESSION_CYCLE, "s0, s1, s2")),
            inPlan.successions(),
        )
        assertEquals(MergeWrites(), inPlan.writes)
    }

    // --- M2 ----------------------------------------------------------------------------------------

    /** Either end held here: a plan never calls applicable an insert the write guard would refuse. */
    @Test
    fun anInsertNamingAHeldAssetAtEitherEndIsAssetTransferredOut() {
        for ((held, row) in listOf("a1" to replaced, "a2" to replaced)) {
            val plan = planOf(decoded(listOf(row), assets = listOf(heater, newHeater)), assets = listOf(heater, newHeater), transfers = listOf(transferOf("r1", assetId = held)))
            assertEquals(listOf(conflict("s1", MergeReason.ASSET_TRANSFERRED_OUT, held)), plan.successions(), held)
            assertFalse(plan.applicable)
        }
    }

    // --- the dev ↔ production limit (R86-19), and the report ------------------------------------------

    /**
     * A replacement made on one install retires the predecessor there; merged into an install that still holds it
     * unretired, the predecessor's row differs, so the plan conflicts on it and nothing lands — no UPDATE, never
     * weakened. The owner replaces on the target, or takes a Replace restore from the source.
     */
    @Test
    fun theDevProductionLimitConflictsOnThePredecessor() {
        val retiredThere = heater.copy(retiredOn = "2026-09-20", updatedAt = heater.updatedAt + 1)
        val dev = decoded(listOf(replaced.copy(replacedOn = "2026-09-20")), assets = listOf(retiredThere, newHeater))

        val plan = planOf(dev, assets = listOf(heater))

        assertEquals(
            MergeDecision(MergeTable.ASSETS, "a1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "a1"),
            plan.decisions.first { it.table == MergeTable.ASSETS && it.id == "a1" },
        )
        assertEquals(listOf(insert("s1")), plan.successions(), "the row itself is sound; the predecessor is not")
        assertFalse(plan.applicable)
        assertEquals(MergeWrites(), plan.writes, "nothing lands")
    }

    @Test
    fun successionsTally() {
        val local = successionOf("s0", predecessor = "a3", successor = "a4")
        val plan = planOf(
            decoded(listOf(local, replaced, successionOf("s2", predecessor = "a2", successor = "a3", replacedOn = "2026-09-21"))),
            successions = listOf(local, successionOf("s2", predecessor = "a2", successor = "a3")),
        )

        assertEquals(listOf(identical("s0"), insert("s1"), conflict("s2", MergeReason.CONTENT_DIFFERS, "s2")), plan.successions())
        assertEquals(MergeTally(insert = 1, identical = 1, conflict = 1, skipped = 0), plan.report().successions)
        assertEquals(MergeTable.entries.last(), MergeTable.SUCCESSIONS)
    }
}
