package com.loosecannon.servicetag.ui.loan

import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.model.LoanStanding
import com.loosecannon.servicetag.ui.condition.displayDate
import java.time.LocalDate

/** #72 (C16): what the open block offers, in the order it draws them. */
enum class LoanAction { OPEN_CONTACT, CHOOSE_FROM_CONTACTS, MARK_RETURNED, EDIT_LOAN }

/**
 * #72 (C16): the open loan as the Lending section draws it — its badge ([standing]: P72-1 or P72-2),
 * then [lines] (P72-4, P72-5, and P72-6, P72-7 or P72-8), the reminder line (P72-9 or P72-10, only
 * with a due date and a mode), the notes, the contact line (P72-19 under "Open contact", or P72-17 for
 * a name-only loan) and [actions]. The block works on every lifecycle (R72-12): a retired or archived
 * asset's loan stays visible, returnable and correctable.
 */
data class OpenLoanBlock(
    val loanId: String,
    val standing: LoanStanding,
    val lines: List<String>,
    /** Whether the loan has a due-back day; without one the third line is the quiet "no due date" (#102: drawn by this, not by its words). */
    val hasDueDate: Boolean,
    val reminderLine: String?,
    val notes: String,
    /** The stored link — "Open contact" hands it to the opener; null for a name-only loan. */
    val lookupUri: String?,
    /** P72-19 when linked, P72-17 when name-only. */
    val contactLine: String,
    val actions: List<LoanAction>,
)

/**
 * #72 (C16; R72-18): one returned loan in "Past loans" — P72-4, P72-5, P72-16 and the notes. Frozen:
 * [actions] is always empty, so nothing on a returned loan can be pressed.
 */
data class PastLoanRow(
    val loanId: String,
    val lines: List<String>,
    val notes: String,
    val actions: List<LoanAction> = emptyList(),
)

/**
 * #72 (C16): the Lending section's facts, from one `observeForAsset` read and `Today` — never the
 * clock. [inService] is the asset's own lifecycle (active and not retired): "Lend out" is offered only
 * there (R72-11), and the section is drawn there or wherever the asset holds any loan.
 */
data class LoanFacts(
    val open: OpenLoanBlock? = null,
    val standing: LoanStanding? = null,
    val history: List<PastLoanRow> = emptyList(),
    val inService: Boolean = true,
) {
    /** C16: in service, or holding any loan — open or returned. */
    val shown: Boolean get() = inService || open != null || history.isNotEmpty()

    /** P72-14, only in service and only with nothing lent (R72-11). */
    val offersLendOut: Boolean get() = inService && open == null
}

/**
 * #72 (C16): [loans] — one asset's, as `observeForAsset` gives them — on [today]. The open loan (at most
 * one, C2) becomes the block, whatever the asset's lifecycle; the returned ones become "Past loans",
 * newest return first, then by id.
 */
fun loanFactsOf(loans: List<AssetLoan>, today: LocalDate, inService: Boolean): LoanFacts {
    val open = loans.firstOrNull { it.isOpen }
    val standing = open?.standingOn(today)
    return LoanFacts(
        open = open?.let { loan -> standing?.let { openBlockOf(loan, it) } },
        standing = standing,
        history = loans
            .filter { !it.isOpen }
            .sortedWith(compareByDescending<AssetLoan> { it.returnedOn }.thenBy { it.id.value })
            .map(::pastRowOf),
        inService = inService,
    )
}

private fun openBlockOf(loan: AssetLoan, standing: LoanStanding): OpenLoanBlock {
    val due = loan.dueOn?.let(::dayOrNull)
    val dueLine = when {
        due == null -> NO_DUE_DATE
        standing == LoanStanding.OVERDUE -> wasDueBackLine(displayDate(due))
        else -> dueBackLine(displayDate(due))
    }
    val linked = loan.contactLookupUri != null
    return OpenLoanBlock(
        loanId = loan.id.value,
        standing = standing,
        lines = listOf(lentTo(loan.borrowerName), lentOnLine(shown(loan.lentOn)), dueLine),
        hasDueDate = due != null,
        reminderLine = due?.let { reminderLineOf(loan.reminderMode) },
        notes = loan.notes,
        lookupUri = loan.contactLookupUri,
        contactLine = if (linked) IF_THE_CONTACT_DOES_NOT_OPEN else NO_CONTACT_LINKED,
        actions = buildList {
            if (linked) add(LoanAction.OPEN_CONTACT)
            add(LoanAction.CHOOSE_FROM_CONTACTS)
            add(LoanAction.MARK_RETURNED)
            add(LoanAction.EDIT_LOAN)
        },
    )
}

/** P72-9 for Once, P72-10 for Until returned; none for None. */
private fun reminderLineOf(mode: LoanReminderMode): String? = when (mode) {
    LoanReminderMode.NONE -> null
    LoanReminderMode.ONCE -> REMINDER_WHEN_DUE_BACK
    LoanReminderMode.UNTIL_RETURNED -> REMINDER_EVERY_DAY_UNTIL_RETURNED
}

private fun pastRowOf(loan: AssetLoan): PastLoanRow = PastLoanRow(
    loanId = loan.id.value,
    lines = listOfNotNull(
        lentTo(loan.borrowerName),
        lentOnLine(shown(loan.lentOn)),
        loan.returnedOn?.let { returnedOnLine(shown(it)) },
    ),
    notes = loan.notes,
)

/** A stored day in the shipped display shape; a day that does not parse is shown as stored. */
private fun shown(day: String): String = dayOrNull(day)?.let(::displayDate) ?: day

private fun dayOrNull(day: String): LocalDate? = runCatching { LocalDate.parse(day) }.getOrNull()
