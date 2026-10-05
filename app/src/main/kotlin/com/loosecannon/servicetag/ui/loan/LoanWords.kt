package com.loosecannon.servicetag.ui.loan

import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.l10n.datePlaceholder
import com.loosecannon.servicetag.l10n.localized

// #72, the phone's lending words, RATIFIED verbatim (R72-23; plan §6). Each literal has this one home
// and each sits on a line of its own. P72-20 lives beside the contact opener (`contacts/`), and P72-23
// "Due back" has two homes, the reminder's title in core and the form label here (the #79 "Warranty"
// precedent). The reused words — "None", "Not now", "The date cannot be later than today." — are drawn
// through their shipped homes; "Enter a date as YYYY-MM-DD", "Notes", "OK" and "Cancel" inline, as the
// shipped editors write them.
//
// #102: the English text lives in `res/values/strings_records.xml` (`loan_*`); each name here reads it when
// drawn, so a language pack renders it in the owner's language. The borrower's name and the dates are arguments.

/** P72-1: the plate, list and open-block badge while the loan is out (`StatusBadge` draws LENT OUT). */
val LENT_OUT: String get() = localized(R.string.loan_lent_out)

/** P72-2: the same badge after the due day, and the Dashboard row's — never the bare OVERDUE. */
val LOAN_OVERDUE: String get() = localized(R.string.loan_overdue)

/** P72-3: the section. */
val LENDING: String get() = localized(R.string.loan_section)

/** P72-4, "Lent to <name>": the open block and a past loan; P72-44 opens with the same words. */
fun lentTo(name: String): String = localized(R.string.loan_lent_to, name)

/** P72-5, "Lent <date>", the date in the shipped `d MMM yyyy`. */
fun lentOnLine(date: String): String = localized(R.string.loan_lent_on_line, date)

/** P72-6, "Due back <date>", through the due day. */
fun dueBackLine(date: String): String = localized(R.string.loan_due_back_line, date)

/** P72-7, "Was due back <date>", after it, beside P72-2. */
fun wasDueBackLine(date: String): String = localized(R.string.loan_was_due_back_line, date)

/** P72-8: an open loan with no due date. */
val NO_DUE_DATE: String get() = localized(R.string.loan_no_due_date)

/** P72-9: a Once reminder, on P79-6's shape. */
val REMINDER_WHEN_DUE_BACK: String get() = localized(R.string.loan_reminder_when_due)

/** P72-10 (R72-7): an Until returned reminder. */
val REMINDER_EVERY_DAY_UNTIL_RETURNED: String get() = localized(R.string.loan_reminder_until_returned)

/** P72-11: the open block's link to Contacts. */
val OPEN_CONTACT: String get() = localized(R.string.loan_open_contact)

/** P72-12: the open block's way out, and the return dialog's confirm. */
val MARK_RETURNED: String get() = localized(R.string.loan_mark_returned)

/** P72-13: the open block's correction, and the editor's title on an edit. */
val EDIT_LOAN: String get() = localized(R.string.loan_edit)

/** P72-14: the section's action on an asset in service with nothing lent, and the editor's title. */
val LEND_OUT: String get() = localized(R.string.loan_lend_out)

/** P72-15: the history label. */
val PAST_LOANS: String get() = localized(R.string.loan_past_loans)

/** P72-16, "Returned <date>": a past loan's return. */
fun returnedOnLine(date: String): String = localized(R.string.loan_returned_on_line, date)

/** P72-17: a name-only loan (every API loan until it is linked on the phone). */
val NO_CONTACT_LINKED: String get() = localized(R.string.loan_no_contact_linked)

/** P72-18: the editor's Borrower action, and the open block's relink. */
val CHOOSE_FROM_CONTACTS: String get() = localized(R.string.loan_choose_from_contacts)

/** P72-19 (R72-4): under "Open contact", since whether the link still resolves cannot be known. */
val IF_THE_CONTACT_DOES_NOT_OPEN: String get() = localized(R.string.loan_if_contact_does_not_open)

/** P72-21: the editor's borrower label. */
val BORROWER: String get() = localized(R.string.loan_field_borrower)

/** P72-22: the editor's lent date. */
val LENT_ON: String get() = localized(R.string.loan_field_lent_on)

/** P72-23: the editor's due date label (its second home; the first is the reminder title in core). */
val DUE_BACK: String get() = localized(R.string.loan_field_due_back)

/** P72-24: the editor's reminder label. */
val REMINDER: String get() = localized(R.string.loan_field_reminder)

/** P72-25: the Once chip. */
val ONCE: String get() = localized(R.string.loan_reminder_once)

/** P72-26: the Until returned chip. */
val UNTIL_RETURNED: String get() = localized(R.string.loan_reminder_until_returned_chip)

/** P72-27: under the chips while there is no due date; the chips are disabled. */
val ADD_A_DUE_DATE_TO_GET_A_REMINDER: String get() = localized(R.string.loan_add_due_date_for_reminder)

/** P72-28: the editor's save. */
val SAVE_LOAN: String get() = localized(R.string.loan_save)

/** P72-29: a new loan with no borrower picked. */
val CHOOSE_A_BORROWER: String get() = localized(R.string.loan_choose_a_borrower)

/** P72-30: a pick whose contact has no name to show. */
val THIS_CONTACT_HAS_NO_NAME_TO_SHOW: String get() = localized(R.string.loan_contact_has_no_name)

/** P72-31: a due date before the lent date. */
val THE_DUE_DATE_CANNOT_BE_BEFORE_LENT: String get() = localized(R.string.loan_due_before_lent)

/** P72-32: a save that failed for no reason the form can mark (logged). */
val COULD_NOT_SAVE_THIS_LOAN: String get() = localized(R.string.loan_could_not_save)

/** P72-34: the return dialog's title. */
val MARK_RETURNED_QUESTION: String get() = localized(R.string.loan_mark_returned_question)

/** P72-35: the return dialog's date. */
val RETURNED_ON: String get() = localized(R.string.loan_field_returned_on)

/** P72-36: a return date before the lent date. */
val THE_RETURN_DATE_CANNOT_BE_BEFORE_LENT: String get() = localized(R.string.loan_return_before_lent)

/** P72-37: a stale form — the asset was lent meanwhile. */
val THIS_ASSET_IS_ALREADY_LENT_OUT: String get() = localized(R.string.loan_already_lent_out)

/**
 * P72-44 (R72-14 b): the Dashboard row's line, "Lent to <name> · due back <date>" — P72-4's words with the due
 * date, one sentence (#102) so a language can order it as its own.
 */
fun dashboardLoanLine(name: String, date: String): String = localized(R.string.loan_dashboard_line, name, date)

/** P72-45: a phone with no contact picker. */
val NO_APP_CAN_PICK_A_CONTACT: String get() = localized(R.string.loan_no_contact_picker)

/** P72-46: a pick that could not be read. */
val COULD_NOT_READ_THIS_CONTACT: String get() = localized(R.string.loan_could_not_read_contact)

/** The shipped date refusal, inline as the shipped editors write it. */
internal val ENTER_A_DATE: String get() = localized(R.string.loan_enter_a_date, datePlaceholder())
