package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Lends an asset (#72, C3; R72-1–R72-3, R72-11, R72-19): **one loan row, and nothing else** — open, with
 * no return date, stamped created and updated now. It writes no event, condition, schedule or health
 * row, and reads the asset only to know it exists: any lifecycle may be lent (the phone offers "Lend
 * out" in service only, R72-11; the API accepts a retired or archived asset).
 *
 * [borrowerName] is the display-name snapshot, trimmed; [contactLookupUri] is the link the phone
 * picked, or null for a name-only loan, which every API loan is (R72-3, R72-4).
 *
 * In one `uow.write`, in this order: a missing asset is the shipped [NoSuchAsset]; an asset that already
 * holds an open loan is [AssetAlreadyLent] (C2 i — no field of the command could change that answer);
 * then every problem is collected into one [LoanValidation]. The open-loan read and the write share the
 * transaction, so the schema's one-open-loan index is never what refuses a second loan here (C2 ii): it
 * stays the database's last word.
 */
class LendAsset(
    private val assets: AssetRepository,
    private val loans: AssetLoanRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val today: Today,
) {
    suspend fun run(
        assetId: AssetId,
        borrowerName: String,
        terms: LoanTerms,
        contactLookupUri: String? = null,
    ): AssetLoan = uow.write {
        assets.get(assetId) ?: throw NoSuchAsset(assetId)
        loans.openFor(assetId)?.let { throw AssetAlreadyLent(assetId, it.id) }
        val borrower = borrowerName.trim()
        val clean = terms.trimmed()
        val problems = loanProblems(
            borrower, contactLookupUri, clean.lentOn, clean.dueOn, clean.reminderMode, returnedOn = null,
            today = today.localDate(),
        )
        if (problems.isNotEmpty()) throw LoanValidation(problems)

        val now = clock.nowMillis()
        val row = AssetLoan(
            id = AssetLoanId(ids.newId()),
            assetId = assetId,
            borrowerName = borrower,
            contactLookupUri = contactLookupUri,
            lentOn = clean.lentOn,
            dueOn = clean.dueOn,
            returnedOn = null,
            reminderMode = clean.reminderMode,
            notes = clean.notes,
            createdAt = now,
            updatedAt = now,
        )
        loans.upsert(row)
        row
    }
}
