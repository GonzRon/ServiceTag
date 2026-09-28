package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * "Mark returned" (#72, C3; R72-17–R72-19): the one exit from a loan. It sets the return date and the
 * stamp on the loan's own row, and the row **stays** — a returned loan is the asset's lending history,
 * frozen from here on. Nothing is deleted, and no event, condition, schedule or health row is written;
 * the reminder that was waiting on the loan goes by its absence at the next sweep.
 *
 * A missing loan is [NoSuchLoan] and one already returned is [LoanReturned]; otherwise [returnedOn] must
 * be a date on or after the lent date and not after today (`BadDate`, `ReturnedBeforeLent`,
 * `ReturnedAfterToday`), collected into one [LoanValidation].
 */
class ReturnLoan(
    private val loans: AssetLoanRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
    private val today: Today,
) {
    suspend fun run(loanId: AssetLoanId, returnedOn: String): AssetLoan = uow.write {
        val stored = loans.get(loanId) ?: throw NoSuchLoan(loanId)
        if (!stored.isOpen) throw LoanReturned(loanId)
        val day = returnedOn.trim()
        val problems = returnProblems(parseDate(stored.lentOn), day, today.localDate())
        if (problems.isNotEmpty()) throw LoanValidation(problems)

        val row = stored.copy(returnedOn = day, updatedAt = clock.nowMillis())
        loans.upsert(row)
        row
    }
}
