package com.loosecannon.servicetag.core.model

import java.time.LocalDate
import java.time.format.DateTimeParseException

@JvmInline
value class AssetLoanId(val value: String)

/**
 * The reminder an owner asks for when a loan is due back (#72, R72-6, R72-7): none, one reminder, or
 * one every day until the loan is returned. Any mode but [NONE] needs a due date — a loan with no due
 * date has nothing to remind about, and a command that asks for both is refused, never reset.
 */
enum class LoanReminderMode { NONE, ONCE, UNTIL_RETURNED }

/**
 * One loan of an asset to a person or an organisation (#72, C1; R72-1): the **custody** concept, the
 * eighth beside the seven of SPEC14 §2 and coupled to none of them. A loan is open while [returnedOn]
 * is null; "Mark returned" is its only exit (R72-17), and a returned loan is frozen, notes included
 * (R72-18) — the returned rows are the asset's lending history. An asset holds at most one open loan.
 *
 * [borrowerName] is the display-name **snapshot**, trimmed and never blank: it is what every surface
 * shows, whether or not the link still opens. [contactLookupUri] is the Android Contacts link the
 * phone picked, and matches [CONTACT_LOOKUP_URI]; null is a name-only loan, which is every loan the
 * API makes (R72-3, R72-4). The dates are ISO `YYYY-MM-DD` days, never times (R72-19).
 *
 * Whether a loan is lent out or overdue is [standingOn]'s answer at read time: it is never stored, never
 * on a backup row, and never a maintenance status or a health value (AC 13).
 */
data class AssetLoan(
    val id: AssetLoanId,
    val assetId: AssetId,
    /** The display-name snapshot; trimmed, never blank. */
    val borrowerName: String,
    /** Null for a name-only loan; otherwise it matches [CONTACT_LOOKUP_URI]. */
    val contactLookupUri: String?,
    /** ISO `YYYY-MM-DD`; not after the day it was written. */
    val lentOn: String,
    /** ISO `YYYY-MM-DD`, not before [lentOn]; null when the loan has no due date. */
    val dueOn: String?,
    /** ISO `YYYY-MM-DD`, between [lentOn] and the day it was written; null while the loan is open. */
    val returnedOn: String?,
    val reminderMode: LoanReminderMode,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** Open until "Mark returned" sets [returnedOn]. */
    val isOpen: Boolean get() = returnedOn == null

    /**
     * Where this loan stands on [today], derived and never stored: a returned loan has no standing; an
     * open one is [LoanStanding.OVERDUE] once [today] is **after** its due date — the due day itself is
     * still [LoanStanding.LENT_OUT] — and a loan with no due date is never overdue.
     */
    fun standingOn(today: LocalDate): LoanStanding? {
        if (!isOpen) return null
        val due = dueOn?.let(::dayOrNull) ?: return LoanStanding.LENT_OUT
        return if (today.isAfter(due)) LoanStanding.OVERDUE else LoanStanding.LENT_OUT
    }
}

/**
 * An open loan's standing (#72, C1): the words the plate, the Lending section, the Assets list and the
 * Dashboard draw. Derived at read time by [AssetLoan.standingOn]; never stored, never on a DTO, and never
 * a maintenance status — an overdue loan is not an overdue job (AC 13).
 */
enum class LoanStanding { LENT_OUT, OVERDUE }

/**
 * The one rule for a stored Android Contacts link (#72, C1; R72-4): a lookup URI on the platform
 * provider's authority, `content://com.android.contacts/contacts/lookup/<key>` with an optional
 * `/<contact id>`, and nothing else — no other authority, no bare `/contacts/<id>`, no query, no
 * fragment, and no `/` inside the key. The loan commands (C3), the backup content check (C6) and the
 * phone's pick codec and open policy (C15) all ask this one expression.
 */
val CONTACT_LOOKUP_URI = Regex("""^content://com\.android\.contacts/contacts/lookup/[^/?#]+(/[0-9]+)?$""")

private fun dayOrNull(value: String): LocalDate? = try {
    LocalDate.parse(value)
} catch (e: DateTimeParseException) {
    null
}
