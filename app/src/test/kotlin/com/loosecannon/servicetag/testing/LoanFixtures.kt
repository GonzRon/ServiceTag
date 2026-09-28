package com.loosecannon.servicetag.testing

import com.loosecannon.servicetag.contacts.ContactRow
import com.loosecannon.servicetag.contacts.ContactRowQuery
import com.loosecannon.servicetag.contacts.PickedContactReader
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.model.LoanReminderMode

/** #72: a fictional linked borrower's pick, and the lookup URI the codec makes of it. */
const val SAMPLE_PICK = "content://com.android.contacts/contacts/lookup/0r5-EXAMPLEKEY/5"
const val RENTALS_PICK = "content://com.android.contacts/contacts/lookup/0r9-EXAMPLERENTALS/9"
const val NAMELESS_PICK = "content://com.android.contacts/contacts/lookup/0r6-NONAME/6"
const val DENIED_PICK = "content://com.android.contacts/contacts/lookup/0r7-DENIED/7"

/**
 * #72: the fake contact seam every loan view-model test reads through — a person, a company-only
 * contact (its organisation is its display name), a nameless one, and one whose read is refused as a
 * pick without a grant is. Every name and key is fictional.
 */
fun fakeContactReader(): PickedContactReader = PickedContactReader(
    ContactRowQuery { uri ->
        when (uri) {
            SAMPLE_PICK -> ContactRow(5, "0r5-EXAMPLEKEY", "Sample Borrower")
            RENTALS_PICK -> ContactRow(9, "0r9-EXAMPLERENTALS", "Example Rentals Ltd")
            NAMELESS_PICK -> ContactRow(6, "0r6-NONAME", " ")
            DENIED_PICK -> throw SecurityException("Permission Denial")
            else -> null
        }
    },
)

/** #72: one loan row as the repository stores it, for a test that seeds loans directly. */
fun loanRow(
    id: String,
    assetId: String,
    lentOn: String,
    dueOn: String? = null,
    returnedOn: String? = null,
    mode: LoanReminderMode = LoanReminderMode.NONE,
    borrower: String = "Sample Borrower",
    lookupUri: String? = SAMPLE_PICK,
    notes: String = "",
): AssetLoan = AssetLoan(
    id = AssetLoanId(id),
    assetId = AssetId(assetId),
    borrowerName = borrower,
    contactLookupUri = lookupUri,
    lentOn = lentOn,
    dueOn = dueOn,
    returnedOn = returnedOn,
    reminderMode = mode,
    notes = notes,
    createdAt = 1L,
    updatedAt = 1L,
)
