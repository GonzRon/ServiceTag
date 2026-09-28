package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.model.CONTACT_LOOKUP_URI
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * "Choose from Contacts" on an open loan (#72, C3; R72-4, R72-5), the phone's only: the picked
 * contact's lookup URI **and** its display name replace the stored link and snapshot together, in one
 * write of the loan's row — never one without the other, so the name shown is always the name of the
 * contact the link opens. It also links an API loan made by name only. The dates, mode and notes do not
 * move, and a link and name equal to the stored ones write nothing.
 *
 * A missing loan is [NoSuchLoan]; a returned loan is frozen (R72-18) and is [LoanReturned]; otherwise a
 * blank name (`BorrowerRequired`) and a link failing [CONTACT_LOOKUP_URI] (`ContactLinkInvalid`) are
 * collected into one [LoanValidation].
 */
class RelinkLoanContact(
    private val loans: AssetLoanRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
) {
    suspend fun run(loanId: AssetLoanId, lookupUri: String, displayName: String): AssetLoan = uow.write {
        val stored = loans.get(loanId) ?: throw NoSuchLoan(loanId)
        if (!stored.isOpen) throw LoanReturned(loanId)
        val name = displayName.trim()
        val problems = listOfNotNull(
            LoanProblem.BorrowerRequired.takeIf { name.isEmpty() },
            LoanProblem.ContactLinkInvalid.takeIf { !CONTACT_LOOKUP_URI.matches(lookupUri) },
        )
        if (problems.isNotEmpty()) throw LoanValidation(problems)

        val relinked = stored.copy(borrowerName = name, contactLookupUri = lookupUri)
        if (relinked == stored) return@write stored
        val row = relinked.copy(updatedAt = clock.nowMillis())
        loans.upsert(row)
        row
    }
}
