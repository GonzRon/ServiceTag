package com.loosecannon.servicetag.core.reminders

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.testing.InMemoryAssetLoanRepository
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryTransferRecordRepository
import com.loosecannon.servicetag.core.testing.loanOf
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * #72 (C9; R72-10, R72-20): which due-back dates a provider is asked to hold.
 *
 * A subject exists exactly while the loan is **open**, has a due date and a reminder mode other than
 * None; otherwise it is **absent**, and absence is how a provider is told to let go — a returned loan
 * is never a `Completed` subject. Nothing about the asset (its lifecycle, season or break) and nothing
 * about the day gates it: custody is independent of service, and a loan long past its due date is
 * still out until it is marked returned.
 */
class BuildLoanSubjectsTest {

    private val loans = InMemoryAssetLoanRepository()
    private val builder = BuildLoanSubjects(loans, InMemoryTransferRecordRepository())

    private suspend fun lend(loan: AssetLoan): AssetLoan = loan.also { loans.upsert(it) }

    private suspend fun subjectsOn(today: LocalDate) = builder.forProvider(ProviderId.LOCAL, today)

    /** Every field C9 names, for Once and for Until returned. */
    @Test
    fun everyFieldOfAOnceAndAnUntilReturnedSubject() = runTest {
        lend(loanOf("l1", assetId = "a1", dueOn = "2026-10-04", reminderMode = LoanReminderMode.ONCE))
        lend(loanOf("l2", assetId = "a2", dueOn = "2026-10-11", reminderMode = LoanReminderMode.UNTIL_RETURNED))

        assertEquals(
            listOf(
                expected("a1/l1", LocalDate.parse("2026-10-04"), DeadlineRepeat.ONCE),
                expected("a2/l2", LocalDate.parse("2026-10-11"), DeadlineRepeat.UNTIL_CLEARED),
            ),
            subjectsOn(LocalDate.parse("2026-10-01")),
        )
    }

    /** A returned loan, an open one without a due date and one whose mode is None are all absent. */
    @Test
    fun absentWhenReturnedWithoutADueDateOrNone() = runTest {
        lend(loanOf("l1", assetId = "a1", returnedOn = "2026-09-30"))
        lend(loanOf("l2", assetId = "a2", dueOn = null, reminderMode = LoanReminderMode.NONE))
        lend(loanOf("l3", assetId = "a3", reminderMode = LoanReminderMode.NONE))
        lend(loanOf("l4", assetId = "a4", reminderMode = LoanReminderMode.UNTIL_RETURNED, returnedOn = "2026-09-30"))

        assertEquals(emptyList(), subjectsOn(LocalDate.parse("2026-10-01")))
    }

    /**
     * R72-10: a retired or archived asset's open loan is still a subject — the asset is still out.
     * The builder is never handed the assets at all (see [itReadsNoClockAndNoAsset]), so its answer
     * cannot depend on them; the assets here are the scenario, not an input.
     */
    @Test
    fun aRetiredOrArchivedAssetsOpenLoanIsStillASubject() = runTest {
        val assets = InMemoryAssetRepository()
        assets.upsert(Asset(id = AssetId("a1"), name = "Example Drill", createdAt = 1L, updatedAt = 1L, retiredOn = "2026-09-25"))
        assets.upsert(Asset(id = AssetId("a2"), name = "Example Ladder", status = AssetStatus.ARCHIVED, createdAt = 1L, updatedAt = 1L))
        lend(loanOf("l1", assetId = "a1"))
        lend(loanOf("l2", assetId = "a2", reminderMode = LoanReminderMode.UNTIL_RETURNED))

        assertEquals(
            listOf("a1/l1", "a2/l2"),
            subjectsOn(LocalDate.parse("2026-10-01")).map { (it.key as SubjectKey.Deadline).subjectId },
        )
    }

    /** No end while open: years past its due date the loan is still a subject, with its date unchanged. */
    @Test
    fun aLoanLongPastItsDueDateIsStillASubject() = runTest {
        lend(loanOf("l1", assetId = "a1", dueOn = "2026-10-04", reminderMode = LoanReminderMode.ONCE))
        lend(loanOf("l2", assetId = "a2", dueOn = "2026-10-04", reminderMode = LoanReminderMode.UNTIL_RETURNED))

        assertEquals(
            listOf(expected("a1/l1", LocalDate.parse("2026-10-04"), DeadlineRepeat.ONCE),
                expected("a2/l2", LocalDate.parse("2026-10-04"), DeadlineRepeat.UNTIL_CLEARED)),
            subjectsOn(LocalDate.parse("2029-03-01")),
        )
    }

    /**
     * By loan id, whatever order the port answers in: l1 on asset z comes before l2 on asset a,
     * which a sort on the subject id (`a/l2` < `z/l1`) would reverse.
     */
    @Test
    fun sortedByLoanId() = runTest {
        val shuffled = ShuffledLoans(loans)
        lend(loanOf("l3", assetId = "m"))
        lend(loanOf("l1", assetId = "z"))
        lend(loanOf("l2", assetId = "a"))

        assertEquals(
            listOf("z/l1", "a/l2", "m/l3"),
            BuildLoanSubjects(shuffled, InMemoryTransferRecordRepository()).forProvider(ProviderId.LOCAL, LocalDate.parse("2026-10-01"))
                .map { (it.key as SubjectKey.Deadline).subjectId },
        )
    }

    /**
     * Derived from the loans alone: the builder is handed nothing but the loan port — no asset port
     * and no clock — and the day it is asked on does not change its answer. #77 (C11) adds the transfer
     * records, custody and not lifecycle: still no asset port, still no clock.
     */
    @Test
    fun itReadsNoClockAndNoAsset() = runTest {
        assertEquals(
            listOf(listOf(AssetLoanRepository::class.java, TransferRecordRepository::class.java)),
            BuildLoanSubjects::class.java.constructors.map { it.parameterTypes.toList() },
        )
        lend(loanOf("l1", assetId = "a1", dueOn = "2026-10-04"))

        val early = subjectsOn(LocalDate.parse("2001-01-01"))
        assertTrue(early.isNotEmpty())
        assertEquals(early, subjectsOn(LocalDate.parse("2026-10-04")))
        assertEquals(early, subjectsOn(LocalDate.parse("2099-12-31")))
    }

    private fun expected(subjectId: String, dueOn: LocalDate, repeat: DeadlineRepeat) = ReminderSubject(
        key = SubjectKey.Deadline(DeadlineKind.LOAN_DUE_BACK, subjectId),
        title = "Due back",
        body = "",
        dueOn = dueOn,
        leadDays = 0,
        state = SubjectState.Active,
        rule = null,
        contentHash = ContentHash.of("Due back", "", dueOn, 0, SubjectState.Active, null, repeat),
        repeat = repeat,
    )

    /** A port that answers its open loans newest-inserted first, so only the builder's own sort can order them. */
    private class ShuffledLoans(private val inner: InMemoryAssetLoanRepository) : AssetLoanRepository by inner {
        override suspend fun open(): List<AssetLoan> = inner.rows.values.filter { it.isOpen }.reversed()
    }
}
