package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetLoanRepository
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.SAMPLE_LOOKUP_URI
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.loanOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * #72 (C2 i, C3; R72-2, R72-3, R72-5, R72-11, R72-17–R72-19): the loan aggregate's four writers.
 *
 * - [LendAsset] writes one open row for an asset of any lifecycle, refusing a second open loan
 *   (`AssetAlreadyLent`) inside its one write.
 * - [UpdateLoan] replaces an open loan's four terms in full and never moves the asset, the borrower, the
 *   link or the return date; equal terms write nothing.
 * - [ReturnLoan] sets the return date and keeps the row as history.
 * - [RelinkLoanContact] replaces the link and the snapshot together, on an open loan only.
 *
 * A returned loan is frozen. Dates are days only: `lentOn ≤ today`, `dueOn ≥ lentOn`,
 * `lentOn ≤ returnedOn ≤ today`; a reminder without a due date is refused, never reset. Every problem is
 * collected before any write. No loan writer touches another table. The names and links are fictional.
 */
class LoanUseCasesTest {

    private val assets = InMemoryAssetRepository()
    private val loanRows = InMemoryAssetLoanRepository()
    private val uow = FakeUnitOfWork(assets, loanRows)

    /** Every loan write, so "writes nothing" is a count and not an inference. */
    private var loanUpserts = 0
    private val loans = object : AssetLoanRepository by loanRows {
        override suspend fun upsert(loan: AssetLoan) = loanRows.upsert(loan).also { loanUpserts++ }
    }

    private var seq = 0
    private val ids = IdGenerator { "loan-%03d".format(++seq) }
    private var now = dayMillis("2026-09-24") + 1_000L
    private val clock = Clock { now }
    private val today = Today { LocalDate.parse("2026-09-24") }

    private val lend = LendAsset(assets, loans, uow, ids, clock, today)
    private val update = UpdateLoan(loans, uow, clock, today)
    private val giveBack = ReturnLoan(loans, uow, clock, today)
    private val relink = RelinkLoanContact(loans, uow, clock)

    init {
        assets.rows["a1"] = plainAssetOf("a1", "Example Drill")
        assets.rows["a2"] = plainAssetOf("a2", "Example Ladder").copy(status = AssetStatus.ARCHIVED)
        assets.rows["a3"] = plainAssetOf("a3", "Example Saw").copy(retiredOn = "2026-06-01")
    }

    private fun terms(
        lentOn: String = "2026-09-20",
        dueOn: String? = "2026-10-04",
        mode: LoanReminderMode = LoanReminderMode.ONCE,
        notes: String = "With the spare battery",
    ) = LoanTerms(lentOn, dueOn, mode, notes)

    /** A stored loan, laid down directly so only the command under test writes. */
    private fun stored(loan: AssetLoan): AssetLoan = loan.also { loanRows.rows[it.id.value] = it }

    // --- lend -------------------------------------------------------------------------------------

    @Test
    fun lendWritesOneOpenRow() = runBlocking<Unit> {
        now += 5
        val loan = lend.run(AssetId("a1"), "  Sample Borrower ", terms(notes = "  With the spare battery  "), SAMPLE_LOOKUP_URI)

        val expected = AssetLoan(
            id = AssetLoanId("loan-001"), assetId = AssetId("a1"), borrowerName = "Sample Borrower",
            contactLookupUri = SAMPLE_LOOKUP_URI, lentOn = "2026-09-20", dueOn = "2026-10-04", returnedOn = null,
            reminderMode = LoanReminderMode.ONCE, notes = "With the spare battery", createdAt = now, updatedAt = now,
        )
        assertEquals(expected, loan)
        assertEquals(listOf(expected), loanRows.all(), "one open row and nothing else")
        assertEquals(1, loanUpserts)
        assertEquals(1, uow.commits)

        // A name-only loan — every API loan — with no due date and no reminder.
        val nameOnly = lend.run(AssetId("a3"), "Example Rentals Ltd", terms(dueOn = null, mode = LoanReminderMode.NONE, notes = ""))
        assertNull(nameOnly.contactLookupUri)
        assertNull(nameOnly.dueOn)
        assertTrue(nameOnly.isOpen)
    }

    @Test
    fun aSecondOpenLoanIsAssetAlreadyLent() = runBlocking<Unit> {
        stored(loanOf("l-open", assetId = "a1"))

        val refusal = assertFailsWith<AssetAlreadyLent> {
            lend.run(AssetId("a1"), "Example Rentals Ltd", terms(), null)
        }

        assertEquals(AssetLoanId("l-open"), refusal.openLoanId)
        assertEquals(AssetId("a1"), refusal.assetId)
        assertEquals(0, uow.commits)
        assertEquals(0, loanUpserts)
        assertEquals(listOf("l-open"), loanRows.rows.keys.toList())
    }

    @Test
    fun aReturnedLoanDoesNotBlockTheNext() = runBlocking<Unit> {
        stored(loanOf("l-old", assetId = "a1", lentOn = "2026-08-01", dueOn = "2026-08-15", returnedOn = "2026-08-14"))
        stored(loanOf("l-older", assetId = "a1", lentOn = "2026-07-01", dueOn = null, returnedOn = "2026-07-02", reminderMode = LoanReminderMode.NONE))

        val next = lend.run(AssetId("a1"), "Sample Borrower", terms(), SAMPLE_LOOKUP_URI)

        assertEquals(next, loanRows.openFor(AssetId("a1")))
        assertEquals(listOf(next.id.value, "l-old", "l-older"), loanRows.forAsset(AssetId("a1")).map { it.id.value }, "newest lent first")
    }

    @Test
    fun problemsAreReportedTogether() = runBlocking<Unit> {
        val shape = assertFailsWith<LoanValidation> {
            lend.run(AssetId("a1"), "   ", terms(lentOn = "2026-09-25", dueOn = "2026-09-20"), "content://media/external/images/media/7")
        }
        assertEquals(
            listOf(LoanProblem.BorrowerRequired, LoanProblem.ContactLinkInvalid, LoanProblem.LentAfterToday, LoanProblem.DueBeforeLent),
            shape.problems,
        )

        val dates = assertFailsWith<LoanValidation> {
            lend.run(AssetId("a1"), "Sample Borrower", terms(lentOn = "20 Sept", dueOn = "2026-13-01"), null)
        }
        assertEquals(listOf(LoanProblem.BadDate("lentOn"), LoanProblem.BadDate("dueOn")), dates.problems)

        assertEquals(0, uow.commits)
        assertEquals(0, loanUpserts)
        assertTrue(loanRows.rows.isEmpty())
    }

    /** N10: a reminder asked for with no due date is refused — never quietly reset to None. */
    @Test
    fun aModeWithoutADueDateIsRefused() = runBlocking<Unit> {
        for ((mode, due) in listOf(LoanReminderMode.ONCE to null, LoanReminderMode.UNTIL_RETURNED to "  ")) {
            val refusal = assertFailsWith<LoanValidation>("$mode") {
                lend.run(AssetId("a1"), "Sample Borrower", terms(dueOn = due, mode = mode), null)
            }
            assertEquals(listOf(LoanProblem.ReminderWithoutDueDate), refusal.problems)
        }
        assertTrue(loanRows.rows.isEmpty())

        val open = stored(loanOf("l1", dueOn = "2026-10-04", reminderMode = LoanReminderMode.UNTIL_RETURNED))
        val onEdit = assertFailsWith<LoanValidation> {
            update.run(open.id, terms(dueOn = null, mode = LoanReminderMode.UNTIL_RETURNED))
        }
        assertEquals(listOf(LoanProblem.ReminderWithoutDueDate), onEdit.problems)
        assertEquals(open, loanRows.get(open.id), "the stored loan keeps its date and its mode")

        // No reminder and no due date is a plain loan.
        val plain = lend.run(AssetId("a2"), "Sample Borrower", terms(dueOn = null, mode = LoanReminderMode.NONE), null)
        assertEquals(LoanReminderMode.NONE, plain.reminderMode)
    }

    /** R72-11: the use case — and the API over it — lends an asset of any lifecycle. */
    @Test
    fun aRetiredOrArchivedAssetIsAccepted() = runBlocking<Unit> {
        val archived = lend.run(AssetId("a2"), "Sample Borrower", terms(), null)
        val retired = lend.run(AssetId("a3"), "Example Rentals Ltd", terms(), SAMPLE_LOOKUP_URI)

        assertTrue(archived.isOpen && retired.isOpen)
        assertEquals(2, loanRows.open().size)
        assertFailsWith<NoSuchAsset> { lend.run(AssetId("a9"), "Sample Borrower", terms(), null) }
    }

    @Test
    fun aLinkFailingTheRuleIsRefused() = runBlocking<Unit> {
        for (link in listOf(
            "content://com.android.contacts/contacts/7",
            "content://media/external/images/media/7",
            "content://com.android.contacts/contacts/lookup/0r1-EXAMPLEKEY/7?directory=0",
            "",
        )) {
            val refusal = assertFailsWith<LoanValidation>(link) { lend.run(AssetId("a1"), "Sample Borrower", terms(), link) }
            assertEquals(listOf(LoanProblem.ContactLinkInvalid), refusal.problems, link)
        }
        assertTrue(loanRows.rows.isEmpty())
    }

    // --- update, return, relink -------------------------------------------------------------------

    @Test
    fun anUpdateNeverMovesAssetBorrowerLinkOrReturnedOn() = runBlocking<Unit> {
        val open = stored(loanOf("l1"))
        now += 60_000

        val edited = update.run(
            open.id,
            terms(lentOn = " 2026-09-19 ", dueOn = "2026-10-11", mode = LoanReminderMode.UNTIL_RETURNED, notes = " Charger too "),
        )

        assertEquals(
            open.copy(lentOn = "2026-09-19", dueOn = "2026-10-11", reminderMode = LoanReminderMode.UNTIL_RETURNED, notes = "Charger too", updatedAt = now),
            edited,
        )
        assertEquals(edited, loanRows.get(open.id))
        assertEquals(1, loanUpserts)

        // The lent date is held to its rules on an edit too.
        val refusal = assertFailsWith<LoanValidation> { update.run(open.id, terms(lentOn = "2026-09-30", dueOn = "2026-09-29")) }
        assertEquals(listOf(LoanProblem.LentAfterToday, LoanProblem.DueBeforeLent), refusal.problems)
        assertFailsWith<NoSuchLoan> { update.run(AssetLoanId("l-none"), terms()) }
    }

    @Test
    fun anEqualCommandWritesNothing() = runBlocking<Unit> {
        val open = stored(loanOf("l1"))
        now += 60_000

        val same = update.run(open.id, terms(lentOn = " 2026-09-20", dueOn = "2026-10-04 ", notes = " With the spare battery "))

        assertEquals(open, same)
        assertEquals(0, loanUpserts)
        assertEquals(open.updatedAt, loanRows.get(open.id)!!.updatedAt, "not even the stamp")
    }

    @Test
    fun returnKeepsTheRowWithItsDate() = runBlocking<Unit> {
        val open = stored(loanOf("l1"))
        stored(loanOf("l0", lentOn = "2026-08-01", returnedOn = "2026-08-05"))
        now += 60_000

        val returned = giveBack.run(open.id, " 2026-09-23 ")

        assertEquals(open.copy(returnedOn = "2026-09-23", updatedAt = now), returned)
        assertEquals(listOf("l1", "l0"), loanRows.forAsset(AssetId("a1")).map { it.id.value }, "the row stays, as history")
        assertNull(loanRows.openFor(AssetId("a1")))
        assertEquals(returned, loanRows.get(open.id))
        assertEquals(1, loanUpserts)
    }

    @Test
    fun returnedBeforeLentOrAfterTodayIsRefused() = runBlocking<Unit> {
        val open = stored(loanOf("l1", lentOn = "2026-09-20"))
        val cases = listOf(
            "2026-09-19" to listOf(LoanProblem.ReturnedBeforeLent),
            "2026-09-25" to listOf(LoanProblem.ReturnedAfterToday),
            "yesterday" to listOf(LoanProblem.BadDate("returnedOn")),
            "" to listOf(LoanProblem.BadDate("returnedOn")),
        )
        for ((day, expected) in cases) {
            val refusal = assertFailsWith<LoanValidation>(day) { giveBack.run(open.id, day) }
            assertEquals(expected, refusal.problems, day)
        }
        assertEquals(open, loanRows.get(open.id))
        assertEquals(0, loanUpserts)

        // The lent day itself and today are both allowed.
        assertEquals("2026-09-20", giveBack.run(open.id, "2026-09-20").returnedOn)
        val second = stored(loanOf("l2", assetId = "a2", lentOn = "2026-09-20"))
        assertEquals("2026-09-24", giveBack.run(second.id, "2026-09-24").returnedOn)
        assertFailsWith<NoSuchLoan> { giveBack.run(AssetLoanId("l-none"), "2026-09-24") }
    }

    /** R72-18: a returned loan is frozen — no edit, no second return and no relink. */
    @Test
    fun aReturnedLoanRefusesUpdateReturnAndRelink() = runBlocking<Unit> {
        val returned = stored(loanOf("l1", returnedOn = "2026-09-22"))

        assertEquals(returned.id, assertFailsWith<LoanReturned> { update.run(returned.id, terms(notes = "Late note")) }.id)
        assertFailsWith<LoanReturned> { giveBack.run(returned.id, "2026-09-24") }
        assertFailsWith<LoanReturned> { relink.run(returned.id, SAMPLE_LOOKUP_URI, "Example Rentals Ltd") }

        assertEquals(returned, loanRows.get(returned.id))
        assertEquals(0, loanUpserts)
        assertEquals(0, uow.commits)
    }

    @Test
    fun relinkReplacesLinkAndSnapshotTogether() = runBlocking<Unit> {
        // An API loan, made by name only, linked later on the phone.
        val nameOnly = stored(loanOf("l1", borrowerName = "Example Rentals", contactLookupUri = null))
        now += 60_000
        val relinked = relink.run(nameOnly.id, SAMPLE_LOOKUP_URI, "  Example Rentals Ltd ")
        assertEquals(nameOnly.copy(borrowerName = "Example Rentals Ltd", contactLookupUri = SAMPLE_LOOKUP_URI, updatedAt = now), relinked)

        // A linked loan moved to another contact: the name comes with the link.
        val other = "content://com.android.contacts/contacts/lookup/0r2-OTHERKEY"
        now += 60_000
        val moved = relink.run(nameOnly.id, other, "Sample Borrower")
        assertEquals(relinked.copy(borrowerName = "Sample Borrower", contactLookupUri = other, updatedAt = now), moved)
        assertEquals(moved, loanRows.get(nameOnly.id))

        val refusal = assertFailsWith<LoanValidation> { relink.run(nameOnly.id, "content://com.android.contacts/contacts/7", " ") }
        assertEquals(listOf(LoanProblem.BorrowerRequired, LoanProblem.ContactLinkInvalid), refusal.problems)
        assertEquals(moved, loanRows.get(nameOnly.id))
        assertFailsWith<NoSuchLoan> { relink.run(AssetLoanId("l-none"), SAMPLE_LOOKUP_URI, "Sample Borrower") }
    }

    @Test
    fun anEqualRelinkWritesNothing() = runBlocking<Unit> {
        val linked = stored(loanOf("l1"))
        now += 60_000

        assertEquals(linked, relink.run(linked.id, SAMPLE_LOOKUP_URI, " Sample Borrower "))
        assertEquals(0, loanUpserts)
    }

    // --- the write gate ---------------------------------------------------------------------------

    /**
     * C4 from the constructors: no loan writer is handed an event, condition, schedule, closure, derived
     * state or health port, so none can write one — whatever a later edit adds inside `run`. The four
     * writers' collaborators are the asset read (lend only), the loan port, the transaction, ids, the
     * clock and today. `CrossConceptWriteTest` watches the same four by their writes.
     */
    @Test
    fun noLoanWriterTouchesEventConditionScheduleOrHealth() {
        val allowed = setOf(
            AssetRepository::class.java, AssetLoanRepository::class.java, UnitOfWork::class.java,
            IdGenerator::class.java, Clock::class.java, Today::class.java,
        )
        for (writer in listOf(LendAsset::class.java, UpdateLoan::class.java, ReturnLoan::class.java, RelinkLoanContact::class.java)) {
            val collaborators = writer.declaredConstructors.flatMap { it.parameterTypes.toList() }.toSet()
            assertTrue(collaborators.isNotEmpty(), writer.simpleName)
            assertEquals(emptySet(), collaborators - allowed, "${writer.simpleName} holds more than its own table")
        }
    }
}
