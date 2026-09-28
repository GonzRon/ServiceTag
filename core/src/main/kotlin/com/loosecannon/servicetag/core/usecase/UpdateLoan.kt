package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Replaces an open loan's terms in full (#72, C3; R72-17): the lent date, the due date, the reminder
 * mode and the notes, trimmed, over the stored row, in one write of that row alone. "Edit loan" is how a
 * mistyped lent date is corrected short of a return. It **never** moves the asset, the borrower, the
 * contact link, the return date or `createdAt`, and terms equal to the stored ones write nothing, not
 * even the stamp.
 *
 * A missing loan is [NoSuchLoan]; a returned loan is frozen (R72-18) and is [LoanReturned], whatever was
 * sent; otherwise every problem is collected before the write into one [LoanValidation] — a mode
 * without a due date is refused, never reset (N10).
 */
class UpdateLoan(
    private val loans: AssetLoanRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
    private val today: Today,
) {
    suspend fun run(loanId: AssetLoanId, terms: LoanTerms): AssetLoan = uow.write {
        val stored = loans.get(loanId) ?: throw NoSuchLoan(loanId)
        if (!stored.isOpen) throw LoanReturned(loanId)
        val clean = terms.trimmed()
        val problems = loanProblems(
            stored.borrowerName, stored.contactLookupUri, clean.lentOn, clean.dueOn, clean.reminderMode,
            returnedOn = null, today = today.localDate(),
        )
        if (problems.isNotEmpty()) throw LoanValidation(problems)

        val replaced = stored.copy(
            lentOn = clean.lentOn,
            dueOn = clean.dueOn,
            reminderMode = clean.reminderMode,
            notes = clean.notes,
        )
        if (replaced == stored) return@write stored
        val row = replaced.copy(updatedAt = clock.nowMillis())
        loans.upsert(row)
        row
    }
}
