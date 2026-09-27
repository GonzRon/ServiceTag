package com.loosecannon.servicetag.core.reminders

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.isRetired
import com.loosecannon.servicetag.core.ports.AssetRepository
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * #79 (C5, R79-15): the deadline subjects every provider should be holding, derived from the
 * assets' warranty facts and `today` and **nothing a provider remembers** — the same load-bearing
 * property [BuildReminderSubjects] has, so a provider that lost its bookkeeping is handed the same
 * list and rebuilds itself from it.
 *
 * A warranty subject exists exactly while all four hold:
 * - the asset is **in service** — `status == ACTIVE` and not retired, the rule a schedule's target
 *   is judged by (`targetInService`);
 * - its warranty date parses;
 * - a lead is set;
 * - `today` is not past the expiry (the expiry day is still in warranty).
 *
 * Otherwise it is **absent**, and absence is the withdrawal: the port's contract is that a subject
 * missing from the list is one the provider must stop holding. So there is no Withdrawn, Parked or
 * Completed deadline — only a present one, which is Active, or none.
 *
 * It reads **no season and no break**: an asset out of season still gets its warning, because the
 * warranty runs out on its date whatever the asset is doing (R79-15). The window the lead opens is
 * the provider's business, not this class's: the subject carries the date and the lead, and is in
 * the list from the day the lead is set.
 */
class BuildDeadlineSubjects(
    private val assets: AssetRepository,
) {

    /**
     * The deadline subjects [provider] should be holding on [today]. Every provider is handed the
     * same list — a warranty has no per-provider rows — and the order is by asset id, so two
     * consecutive answers can be compared.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun forProvider(provider: ProviderId, today: LocalDate): List<ReminderSubject> =
        assets.all()
            .sortedBy { it.id.value }
            .mapNotNull { warrantySubjectOf(it, today) }

    private fun warrantySubjectOf(asset: Asset, today: LocalDate): ReminderSubject? {
        if (asset.status != AssetStatus.ACTIVE || asset.isRetired) return null
        val lead = asset.warrantyReminderLeadDays ?: return null
        val expiry = asset.warrantyExpiresOn?.let(::parsedOrNull) ?: return null
        if (today.isAfter(expiry)) return null
        return ReminderSubject(
            key = SubjectKey.Deadline(DeadlineKind.WARRANTY_EXPIRY, asset.id.value),
            title = WARRANTY_TITLE,
            body = "",
            dueOn = expiry,
            leadDays = lead,
            state = SubjectState.Active,
            rule = null,
            contentHash = ContentHash.of(WARRANTY_TITLE, "", expiry, lead, SubjectState.Active, null, DeadlineRepeat.ONCE),
            repeat = DeadlineRepeat.ONCE,
        )
    }

    /** Strict ISO parsing: a date that is not a real day (`2031-02-30`) is no date at all. */
    private fun parsedOrNull(text: String): LocalDate? = try {
        LocalDate.parse(text)
    } catch (e: DateTimeParseException) {
        null
    }

    private companion object {
        /**
         * The reused word "Warranty" (#79 §6), in its core home: the asset detail's section header
         * and the `<title>` of the warning's `<asset> — <title>` line.
         */
        const val WARRANTY_TITLE = "Warranty"
    }
}
