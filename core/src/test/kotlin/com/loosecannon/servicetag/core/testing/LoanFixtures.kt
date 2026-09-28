package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.model.LoanReminderMode

/** A fictional contact link on the platform provider's authority; the key and the id are made up. */
const val SAMPLE_LOOKUP_URI = "content://com.android.contacts/contacts/lookup/0r1-EXAMPLEKEY/7"

/**
 * #72 — a loan as a restore or a merge sees one: every field set to something a test could be wrong
 * about, open unless [returnedOn] is given, with a due date and a Once reminder. The names are fictional.
 */
fun loanOf(
    id: String,
    assetId: String = "a1",
    borrowerName: String = "Sample Borrower",
    contactLookupUri: String? = SAMPLE_LOOKUP_URI,
    lentOn: String = "2026-09-20",
    dueOn: String? = "2026-10-04",
    returnedOn: String? = null,
    reminderMode: LoanReminderMode = LoanReminderMode.ONCE,
    notes: String = "With the spare battery",
    createdAt: Long = 1_758_500_000_000L,
    updatedAt: Long = 1_758_600_000_000L,
): AssetLoan = AssetLoan(
    id = AssetLoanId(id),
    assetId = AssetId(assetId),
    borrowerName = borrowerName,
    contactLookupUri = contactLookupUri,
    lentOn = lentOn,
    dueOn = dueOn,
    returnedOn = returnedOn,
    reminderMode = reminderMode,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
