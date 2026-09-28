package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.model.CONTACT_LOOKUP_URI
import com.loosecannon.servicetag.core.model.LoanReminderMode
import java.time.LocalDate

/**
 * A loan's **terms** (#72, C3): what [LendAsset] writes beside the borrower and what [UpdateLoan]
 * replaces in full while the loan is open. It carries no asset, borrower, contact link or return date:
 * the asset and the borrower are fixed when the loan is made — a relink ([RelinkLoanContact]) replaces
 * the link and the name together, never one of them — and only [ReturnLoan] sets the return date.
 *
 * A [reminderMode] other than NONE needs a [dueOn]: a command asking for both a reminder and no due
 * date is refused as `ReminderWithoutDueDate`, never quietly reset to NONE (N10) — stricter than the
 * warranty lead's "clearing the date clears the lead", on purpose, because the refusal is the one that
 * cannot lose what the owner asked for.
 */
data class LoanTerms(
    /** ISO `YYYY-MM-DD`; not after today. */
    val lentOn: String,
    /** ISO `YYYY-MM-DD`, not before [lentOn]; null or blank for no due date. */
    val dueOn: String?,
    val reminderMode: LoanReminderMode,
    val notes: String = "",
)

/**
 * One thing wrong with a loan command. **Every member is a 422** — the remedy is the body that was
 * sent — so each loan use case collects them all, before any write, into one [LoanValidation], in the
 * loan's field order: the borrower, the link, the lent date, the due date, the reminder, the return
 * date.
 */
sealed interface LoanProblem {
    /** The trimmed borrower name is empty. */
    data object BorrowerRequired : LoanProblem

    /** The contact link does not match [CONTACT_LOOKUP_URI]. */
    data object ContactLinkInvalid : LoanProblem

    /** Not an ISO `YYYY-MM-DD` date: [field] is `lentOn`, `dueOn` or `returnedOn`. */
    data class BadDate(val field: String) : LoanProblem

    /** The lent date is later than today. */
    data object LentAfterToday : LoanProblem

    /** The due date is before the lent date. */
    data object DueBeforeLent : LoanProblem

    /** A reminder was asked for with no due date to remind about. */
    data object ReminderWithoutDueDate : LoanProblem

    /** The return date is before the lent date. */
    data object ReturnedBeforeLent : LoanProblem

    /** The return date is later than today. */
    data object ReturnedAfterToday : LoanProblem
}

/** A loan command was refused; every problem found, collected once. */
class LoanValidation(val problems: List<LoanProblem>) :
    IllegalArgumentException("loan rejected: ${problems.joinToString()}")

/** No loan has this id. A 404. */
class NoSuchLoan(val id: AssetLoanId) : IllegalArgumentException("no loan ${id.value}")

/**
 * The asset already holds an open loan, [openLoanId] (#72, C2 i; R72-2). A **refusal**, not a validation
 * problem — the command is well formed and the store's state is what forbids it, the
 * `AssetMembershipReferenced` precedent — so a 409 on the wire.
 */
class AssetAlreadyLent(val assetId: AssetId, val openLoanId: AssetLoanId) :
    IllegalStateException("asset ${assetId.value} is already lent out (loan ${openLoanId.value})")

/**
 * The loan has been returned, and a returned loan is frozen (R72-18): no edit, no second return and no
 * relink. A refusal, so a 409.
 */
class LoanReturned(val id: AssetLoanId) : IllegalStateException("loan ${id.value} has been returned")

/**
 * The shape of a loan, whatever wrote it, and nothing about other rows (#72, C3, C6). The use cases ask
 * it of the command they were sent, with their [today]; the backup content check asks it of every
 * restored loan with none, so a restore refuses what a command refuses but never judges a row by the
 * importing device's date (P79 C18). The one-open-loan rule is about other rows and is not here: it is
 * each writer's, the graph check's and the merge planner's.
 */
internal fun loanProblems(
    borrowerName: String,
    contactLookupUri: String?,
    lentOn: String,
    dueOn: String?,
    reminderMode: LoanReminderMode,
    returnedOn: String?,
    today: LocalDate? = null,
): List<LoanProblem> {
    val problems = mutableListOf<LoanProblem>()
    if (borrowerName.isBlank()) problems += LoanProblem.BorrowerRequired
    if (contactLookupUri != null && !CONTACT_LOOKUP_URI.matches(contactLookupUri)) problems += LoanProblem.ContactLinkInvalid
    val lent = parseDate(lentOn)
    if (lent == null) {
        problems += LoanProblem.BadDate("lentOn")
    } else if (today != null && lent > today) {
        problems += LoanProblem.LentAfterToday
    }
    if (dueOn != null) {
        val due = parseDate(dueOn)
        if (due == null) {
            problems += LoanProblem.BadDate("dueOn")
        } else if (lent != null && due < lent) {
            problems += LoanProblem.DueBeforeLent
        }
    } else if (reminderMode != LoanReminderMode.NONE) {
        problems += LoanProblem.ReminderWithoutDueDate
    }
    if (returnedOn != null) problems += returnProblems(lent, returnedOn, today)
    return problems
}

/** The return date's rules: a date, not before the lent date, and — for a command — not after [today]. */
internal fun returnProblems(lent: LocalDate?, returnedOn: String, today: LocalDate?): List<LoanProblem> {
    val returned = parseDate(returnedOn) ?: return listOf(LoanProblem.BadDate("returnedOn"))
    return listOfNotNull(
        LoanProblem.ReturnedBeforeLent.takeIf { lent != null && returned < lent },
        LoanProblem.ReturnedAfterToday.takeIf { today != null && returned > today },
    )
}

/** [terms] trimmed, a blank due date null — the one rule every loan writer stores. */
internal fun LoanTerms.trimmed(): LoanTerms = copy(
    lentOn = lentOn.trim(),
    dueOn = dueOn.blankToNull(),
    notes = notes.trim(),
)
