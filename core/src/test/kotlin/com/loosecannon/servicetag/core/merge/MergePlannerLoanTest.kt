package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.Backup
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.backupOf
import com.loosecannon.servicetag.core.testing.loanOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * #72 (C7; R72-18, AC 11; K3): a loan is its own row, identity by its id, and a merge only ever inserts
 * one. Every incoming archive here goes through the real `BackupCodec.decode`, graph check included, so
 * an archive holds at most one open loan per asset before the planner sees it.
 *
 * - A loan here with the same id: every field equal → IDENTICAL; any difference → CONFLICT
 *   `CONTENT_DIFFERS`. There is **no UPDATE**: a return, a re-date, a mode change, a relink or a note made
 *   on one phone after the other received the loan conflicts on re-merge, for the owner to resolve.
 * - Its asset neither here nor inserted by this plan → `OWNER_NOT_AVAILABLE`.
 * - Open, and its asset holds a **different open loan here** → `ASSET_ALREADY_LENT`, naming that loan.
 * - Otherwise INSERT — a returned loan beside a local open one included, since history never blocks.
 *
 * The names and links are fictional.
 */
class MergePlannerLoanTest {

    private val drill = plainAssetOf("a1", "Example Drill")
    private val ladder = plainAssetOf("a2", "Example Ladder")

    private fun decoded(loans: List<AssetLoan>, assets: List<Asset> = listOf(drill)): Backup = BackupCodec.decode(
        archiveOf(
            BackupData(
                assets = assets.map { it.toDto() }, nfcTags = emptyList(), externalLinks = emptyList(),
                assetLoans = loans.map { it.toDto() },
            ),
        ),
    )

    private fun planOf(incoming: Backup, assets: List<Asset> = listOf(drill), loans: List<AssetLoan> = emptyList()): MergePlan =
        mergePlanOf(incoming, MergeSnapshot(assets = assets, loans = loans, attachmentStoreConfigured = true))

    private fun MergePlan.loans() = decisions.filter { it.table == MergeTable.LOANS }

    private fun insert(id: String) = MergeDecision(MergeTable.LOANS, id, MergeVerdict.INSERT)
    private fun identical(id: String) = MergeDecision(MergeTable.LOANS, id, MergeVerdict.IDENTICAL)
    private fun differs(id: String) = MergeDecision(MergeTable.LOANS, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
    private fun noOwner(id: String, asset: String) =
        MergeDecision(MergeTable.LOANS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, asset)
    private fun alreadyLent(id: String, holder: String) =
        MergeDecision(MergeTable.LOANS, id, MergeVerdict.CONFLICT, MergeReason.ASSET_ALREADY_LENT, holder)

    private val open = loanOf("l1")
    private val returned = loanOf("l0", lentOn = "2026-08-01", dueOn = "2026-08-15", returnedOn = "2026-08-14")

    @Test
    fun anEqualLoanIsIdentical() {
        val plan = planOf(decoded(listOf(open, returned)), loans = listOf(open, returned))

        assertEquals(listOf(identical("l0"), identical("l1")), plan.loans())
        assertTrue(plan.applicable)
        assertEquals(emptyList(), plan.writes.loans)
    }

    /** R72-18 both ways: returned on one phone, still open on the other — a disagreement, never an update. */
    @Test
    fun returnedHereOpenThereConflicts() {
        val returnedHere = open.copy(returnedOn = "2026-09-23", updatedAt = open.updatedAt + 1)

        val openThere = planOf(decoded(listOf(open)), loans = listOf(returnedHere))
        assertEquals(listOf(differs("l1")), openThere.loans())
        assertFalse(openThere.applicable)

        val returnedThere = planOf(decoded(listOf(returnedHere)), loans = listOf(open))
        assertEquals(listOf(differs("l1")), returnedThere.loans())
        assertFalse(returnedThere.applicable)
        assertEquals(MergeWrites(), returnedThere.writes, "no partial merge")
    }

    /** No UPDATE: a re-date, a mode change, a relink or a note elsewhere all conflict (P79 §18's re-merge rule). */
    @Test
    fun aRedatedOrRelinkedLoanConflicts() {
        val elsewhere = listOf(
            "re-dated" to open.copy(dueOn = "2026-10-11", updatedAt = open.updatedAt + 1),
            "a new mode" to open.copy(reminderMode = LoanReminderMode.UNTIL_RETURNED, updatedAt = open.updatedAt + 1),
            "relinked" to open.copy(
                borrowerName = "Example Rentals Ltd",
                contactLookupUri = "content://com.android.contacts/contacts/lookup/0r2-OTHERKEY/9",
                updatedAt = open.updatedAt + 1,
            ),
            "linked here, name-only there" to open.copy(contactLookupUri = null),
            "a note" to open.copy(notes = "Charger too", updatedAt = open.updatedAt + 1),
            "the stamp alone" to open.copy(updatedAt = open.updatedAt + 1),
        )
        for ((what, there) in elsewhere) {
            assertEquals(listOf(differs("l1")), planOf(decoded(listOf(there)), loans = listOf(open)).loans(), what)
        }
    }

    /** R72-2's fourth layer: the asset here is out on another loan, so the incoming one cannot land. */
    @Test
    fun anIncomingOpenLoanMeetingADifferentLocalOpenLoanIsAssetAlreadyLent() {
        val there = loanOf("l2", borrowerName = "Example Rentals Ltd", lentOn = "2026-09-22")

        val plan = planOf(decoded(listOf(there)), loans = listOf(open))

        assertEquals(listOf(alreadyLent("l2", "l1")), plan.loans())
        assertFalse(plan.applicable)
        assertEquals(listOf(alreadyLent("l2", "l1")), plan.conflicts)

        // Another asset's open loan is its own business.
        val elsewhere = planOf(decoded(listOf(loanOf("l3", assetId = "a2")), listOf(drill, ladder)), assets = listOf(drill, ladder), loans = listOf(open))
        assertEquals(listOf(insert("l3")), elsewhere.loans())
    }

    @Test
    fun aReturnedIncomingLoanInsertsBesideALocalOpenOne() {
        val plan = planOf(decoded(listOf(returned)), loans = listOf(open))

        assertEquals(listOf(insert("l0")), plan.loans())
        assertTrue(plan.applicable)
        assertEquals(listOf(returned), plan.writes.loans, "the returned loan as the archive has it")
    }

    /**
     * The everyday re-lend (dev ↔ production): a loan returned here, and the asset lent again on the other
     * phone. History never blocks — only an open loan here does — so the new loan inserts, whether or not
     * the archive also carries the returned one.
     */
    @Test
    fun anIncomingOpenLoanInsertsBesideLocalReturnedOnes() {
        val relent = loanOf("l2", lentOn = "2026-09-22")

        val plan = planOf(decoded(listOf(relent)), loans = listOf(returned))
        assertEquals(listOf(insert("l2")), plan.loans())
        assertTrue(plan.applicable)
        assertEquals(listOf(relent), plan.writes.loans)

        val withHistory = planOf(decoded(listOf(returned, relent)), loans = listOf(returned))
        assertEquals(listOf(identical("l0"), insert("l2")), withHistory.loans())
        assertEquals(listOf(relent), withHistory.writes.loans)
    }

    /**
     * The owner rule. Through the real decode a loan's asset is always in the file, and an archive asset
     * the plan refuses is the only way it is unavailable: here the asset's parent sits under it in a
     * snapshot the schema could never hold — the planner guard, reached through a decoded archive. A
     * hand-built [Backup] naming an asset in no file reaches the same arm.
     */
    @Test
    fun anUnavailableAssetIsOwnerNotAvailable() {
        val parent = plainAssetOf("p1", "Example Workbench")
        val child = plainAssetOf("a2", "Example Ladder").copy(parentAssetId = AssetId("p1"))
        val cyclingHere = parent.copy(parentAssetId = AssetId("a2"))
        val plan = planOf(decoded(listOf(loanOf("l2", assetId = "a2")), listOf(parent, child)), assets = listOf(cyclingHere))
        assertEquals(listOf(noOwner("l2", "a2")), plan.loans())

        val handBuilt = mergePlanOf(
            backupOf(
                BackupData(
                    assets = listOf(drill.toDto()), nfcTags = emptyList(), externalLinks = emptyList(),
                    assetLoans = listOf(loanOf("l9", assetId = "a9").toDto()),
                ),
            ),
            MergeSnapshot(assets = listOf(drill), attachmentStoreConfigured = true),
        )
        assertEquals(listOf(noOwner("l9", "a9")), handBuilt.loans())
    }

    @Test
    fun anAssetThisPlanInsertsCarriesItsLoan() {
        val plan = planOf(decoded(listOf(open.copy(assetId = AssetId("a2"))), listOf(drill, ladder)), assets = listOf(drill))

        assertEquals(listOf(insert("l1")), plan.loans())
        assertEquals(listOf(ladder), plan.writes.assets)
        assertEquals(listOf(open.copy(assetId = AssetId("a2"))), plan.writes.loans)
    }

    /** Three different counts — 2, 3 and 1 — so a report wired to the wrong verdict or table fails. */
    @Test
    fun theReportTalliesLoans() {
        val here = listOf(open, returned, loanOf("l4", assetId = "a2"), loanOf("l5", assetId = "a2", returnedOn = "2026-09-21"))
        val incoming = listOf(
            open, returned, loanOf("l4", assetId = "a2"), // IDENTICAL × 3
            loanOf("l5", assetId = "a2", returnedOn = "2026-09-22"), // CONFLICT
            loanOf("l6", lentOn = "2026-07-01", returnedOn = "2026-07-02"), // INSERT
            loanOf("l7", assetId = "a2", lentOn = "2026-07-01", returnedOn = "2026-07-03"), // INSERT
        )
        val report = planOf(decoded(incoming, listOf(drill, ladder)), assets = listOf(drill, ladder), loans = here).report()

        assertEquals(MergeTally(insert = 2, identical = 3, conflict = 1, skipped = 0), report.loans)
        assertEquals(MergeTally(insert = 0, identical = 2, conflict = 0, skipped = 0), report.assets)
    }
}
