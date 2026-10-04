package com.loosecannon.servicetag.core.reminders

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * #72 (C9; R72-10, R72-20): the due-back subjects every provider should be holding, derived from the
 * open loans and **nothing a provider remembers** — the property [BuildReminderSubjects] and
 * [BuildDeadlineSubjects] have, so a provider that lost its bookkeeping is handed the same list and
 * rebuilds itself from it.
 *
 * A loan subject exists exactly while all three hold:
 * - the loan is **open** (not yet marked returned);
 * - it has a due date that parses;
 * - its reminder mode is Once or Until returned.
 *
 * Otherwise it is **absent**, and absence is the withdrawal: a return, a cleared date or a mode set to
 * None takes the reminder down. A returned loan is never a `Completed` subject — the reserved state
 * keeps no producer — and never a withdrawn one: it is simply not in the list.
 *
 * It reads **no asset** — so no lifecycle, season or break gates it: an asset retired or archived
 * while lent is still out, and custody is independent of service (R72-10). The one asset fact it asks
 * is the transfer records' (#77, C11, R77-20): a loan of an asset transferred out from this phone is no
 * subject — the sender no longer answers for it. #77 refuses a transfer while a loan is open, so this
 * only ever drops a loan merged history left open — and **no clock**: `today`
 * is not consulted, so a loan long past its due date is still a subject until it is returned. When
 * a subject is announced, and how often, is the provider's business: the subject carries the date and
 * the repeat.
 */
class BuildLoanSubjects(
    private val loans: AssetLoanRepository,
    /** #77 (C11): the transfer records, read once per answer for the held set. */
    private val transfers: TransferRecordRepository,
) {

    /**
     * The loan subjects [provider] should be holding. Every provider is handed the same list — a
     * loan has no per-provider rows — ordered by loan id, so two consecutive answers can be compared.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun forProvider(provider: ProviderId, today: LocalDate): List<ReminderSubject> {
        val held = transfers.heldIds()
        return loans.open()
            .filter { it.assetId !in held }
            .sortedBy { it.id.value }
            .mapNotNull(::loanSubjectOf)
    }

    private fun loanSubjectOf(loan: AssetLoan): ReminderSubject? {
        val repeat = when (loan.reminderMode) {
            LoanReminderMode.NONE -> return null
            LoanReminderMode.ONCE -> DeadlineRepeat.ONCE
            LoanReminderMode.UNTIL_RETURNED -> DeadlineRepeat.UNTIL_CLEARED
        }
        val dueOn = loan.dueOn?.let(::parsedOrNull) ?: return null
        return ReminderSubject(
            key = SubjectKey.Deadline(DeadlineKind.LOAN_DUE_BACK, LoanSubjectId.of(loan.assetId, loan.id)),
            title = DUE_BACK_TITLE,
            body = "",
            dueOn = dueOn,
            leadDays = 0,
            state = SubjectState.Active,
            rule = null,
            contentHash = ContentHash.of(DUE_BACK_TITLE, "", dueOn, 0, SubjectState.Active, null, repeat),
            repeat = repeat,
        )
    }

    /** Strict ISO parsing: a date that is not a real day is no date at all. */
    private fun parsedOrNull(text: String): LocalDate? = try {
        LocalDate.parse(text)
    } catch (e: DateTimeParseException) {
        null
    }

    private companion object {
        /**
         * #72, P72-23 (RATIFIED verbatim, R72-23), in its core home: the `<title>` of a loan
         * reminder's `<asset> — <title>` line. The lend form's "Due back" label keeps its own UI home
         * (the #79 "Warranty" precedent).
         *
         * #102: the subject's **canonical** title — a [ContentHash] input, so it stays this English in
         * every language and is never translated (a translated title would move every loan's hash on a
         * language change). The provider draws the kind's title in the owner's language from
         * [DeadlineKind.LOAN_DUE_BACK], never from this field.
         */
        const val DUE_BACK_TITLE = "Due back" // l10n-ok: canonical hash input, never shown (#102)
    }
}

/**
 * #72 (C9, C12): a loan subject's id, `<assetId>/<loanId>`.
 *
 * The asset comes first so that "Open" can aim at the asset straight from the id, with no read; the
 * loan comes second so that a returned loan's stamp can never be carried into an identical re-lend of
 * the same asset, which an asset-only id would allow whenever no sweep ran between the two.
 *
 * Split at the **last** `/`: a loan id never holds one — ids are minted, and a restore refuses a loan
 * id that does — so an asset id may hold any number of them and still round-trip. An id with no `/`,
 * or with nothing on either side of the last one, is no loan subject's: both halves are null.
 */
object LoanSubjectId {

    fun of(assetId: AssetId, loanId: AssetLoanId): String = "${assetId.value}$SEPARATOR${loanId.value}"

    fun assetOf(subjectId: String): AssetId? = halvesOf(subjectId)?.first?.let(::AssetId)

    fun loanOf(subjectId: String): AssetLoanId? = halvesOf(subjectId)?.second?.let(::AssetLoanId)

    private fun halvesOf(subjectId: String): Pair<String, String>? {
        val at = subjectId.lastIndexOf(SEPARATOR)
        if (at <= 0 || at == subjectId.lastIndex) return null
        return subjectId.substring(0, at) to subjectId.substring(at + 1)
    }

    private const val SEPARATOR = '/'
}
