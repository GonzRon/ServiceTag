package com.loosecannon.servicetag.ui.loan

// #72, the phone's lending words, RATIFIED verbatim (R72-23; plan §6). Each literal has this one home
// and each sits on a line of its own. P72-20 lives beside the contact opener (`contacts/`), and P72-23
// "Due back" has two homes, the reminder's title in core and the form label here (the #79 "Warranty"
// precedent). The reused words — "None", "Not now", "The date cannot be later than today." — are drawn
// through their shipped homes; "Enter a date as YYYY-MM-DD", "Notes", "OK" and "Cancel" inline, as the
// shipped editors write them.

/** P72-1: the plate, list and open-block badge while the loan is out (`StatusBadge` draws LENT OUT). */
const val LENT_OUT = "Lent out"

/** P72-2: the same badge after the due day, and the Dashboard row's — never the bare OVERDUE. */
const val LOAN_OVERDUE = "Loan overdue"

/** P72-3: the section. */
const val LENDING = "Lending"

/** P72-4, "Lent to <name>": the open block, a past loan, and the opening of P72-44 — its one home. */
fun lentTo(name: String): String = "Lent to $name"

/** P72-5, "Lent <date>", the date in the shipped `d MMM yyyy`. */
fun lentOnLine(date: String): String = "Lent $date"

/** P72-6, "Due back <date>", through the due day. */
fun dueBackLine(date: String): String = "Due back $date"

/** P72-7, "Was due back <date>", after it, beside P72-2. */
fun wasDueBackLine(date: String): String = "Was due back $date"

/** P72-8: an open loan with no due date. */
const val NO_DUE_DATE = "No due date"

/** P72-9: a Once reminder, on P79-6's shape. */
const val REMINDER_WHEN_DUE_BACK = "Reminder: when it is due back"

/** P72-10 (R72-7): an Until returned reminder. */
const val REMINDER_EVERY_DAY_UNTIL_RETURNED = "Reminder: every day until returned"

/** P72-11: the open block's link to Contacts. */
const val OPEN_CONTACT = "Open contact"

/** P72-12: the open block's way out, and the return dialog's confirm. */
const val MARK_RETURNED = "Mark returned"

/** P72-13: the open block's correction, and the editor's title on an edit. */
const val EDIT_LOAN = "Edit loan"

/** P72-14: the section's action on an asset in service with nothing lent, and the editor's title. */
const val LEND_OUT = "Lend out"

/** P72-15: the history label. */
const val PAST_LOANS = "Past loans"

/** P72-16, "Returned <date>": a past loan's return. */
fun returnedOnLine(date: String): String = "Returned $date"

/** P72-17: a name-only loan (every API loan until it is linked on the phone). */
const val NO_CONTACT_LINKED = "No contact linked"

/** P72-18: the editor's Borrower action, and the open block's relink. */
const val CHOOSE_FROM_CONTACTS = "Choose from Contacts"

/** P72-19 (R72-4): under "Open contact", since whether the link still resolves cannot be known. */
const val IF_THE_CONTACT_DOES_NOT_OPEN = "If the contact does not open, choose it again."

/** P72-21: the editor's borrower label. */
const val BORROWER = "Borrower"

/** P72-22: the editor's lent date. */
const val LENT_ON = "Lent on"

/** P72-23: the editor's due date label (its second home; the first is the reminder title in core). */
const val DUE_BACK = "Due back"

/** P72-24: the editor's reminder label. */
const val REMINDER = "Reminder"

/** P72-25: the Once chip. */
const val ONCE = "Once"

/** P72-26: the Until returned chip. */
const val UNTIL_RETURNED = "Until returned"

/** P72-27: under the chips while there is no due date; the chips are disabled. */
const val ADD_A_DUE_DATE_TO_GET_A_REMINDER = "Add a due date to get a reminder."

/** P72-28: the editor's save. */
const val SAVE_LOAN = "Save loan"

/** P72-29: a new loan with no borrower picked. */
const val CHOOSE_A_BORROWER = "Choose a borrower"

/** P72-30: a pick whose contact has no name to show. */
const val THIS_CONTACT_HAS_NO_NAME_TO_SHOW = "This contact has no name to show."

/** P72-31: a due date before the lent date. */
const val THE_DUE_DATE_CANNOT_BE_BEFORE_LENT = "The due date cannot be before the day it was lent."

/** P72-32: a save that failed for no reason the form can mark (logged). */
const val COULD_NOT_SAVE_THIS_LOAN = "Could not save this loan."

/** P72-34: the return dialog's title. */
const val MARK_RETURNED_QUESTION = "Mark returned?"

/** P72-35: the return dialog's date. */
const val RETURNED_ON = "Returned on"

/** P72-36: a return date before the lent date. */
const val THE_RETURN_DATE_CANNOT_BE_BEFORE_LENT = "The return date cannot be before the day it was lent."

/** P72-37: a stale form — the asset was lent meanwhile. */
const val THIS_ASSET_IS_ALREADY_LENT_OUT = "This asset is already lent out."

/** P72-44's separator, between `lentTo(name)` and the due date. */
private const val DUE_BACK_SEPARATOR = " · due back "

/** P72-44 (R72-14 b): the Dashboard row's line, composed from P72-4's one home. */
fun dashboardLoanLine(name: String, date: String): String = lentTo(name) + DUE_BACK_SEPARATOR + date

/** P72-45: a phone with no contact picker. */
const val NO_APP_CAN_PICK_A_CONTACT = "No app can pick a contact"

/** P72-46: a pick that could not be read. */
const val COULD_NOT_READ_THIS_CONTACT = "Could not read this contact."

/** The shipped date refusal, inline as the shipped editors write it. */
internal const val ENTER_A_DATE = "Enter a date as YYYY-MM-DD"
