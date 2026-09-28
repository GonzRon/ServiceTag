package com.loosecannon.servicetag.reminders

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.DeadlineLocalDelivery
import com.loosecannon.servicetag.core.ports.DeadlineLocalDeliveryRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.reminders.DeadlineKind
import com.loosecannon.servicetag.core.reminders.LoanSubjectId
import com.loosecannon.servicetag.core.reminders.SubjectKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** #79 (C6): what a deadline's warning needs about its subject that the port does not carry. */
fun interface DeadlineFactsSource {
    suspend fun factsFor(key: SubjectKey.Deadline): DeadlineFacts?
}

/**
 * The [DeadlineFactsSource] over the real store: a warranty's owner is its asset, named in the
 * title's `<asset>` slot, and the window is measured on [Today] — the same day the sweep built the
 * subjects on. An asset gone between the list being built and this read has no facts, and its
 * warning is forgotten.
 *
 * #72 (C10, R72-7): a loan's owner is its asset too — whatever its lifecycle, since custody is
 * independent of service (R72-10) — and its facts add the borrower and the instants that anchor a
 * loan's day to the owner's [digestHour] rather than to midnight: today at that hour (a loan is held
 * before it) and the latest such instant at or before the [clock]'s now (an Until-returned period
 * began there), and the start of the day after the due day (its words turn there). All are taken in [zone], the device's, read on every call so a zone change is
 * followed; an hour a spring-forward gap skips is moved forward by the gap, as `java.time` resolves
 * it. No column is added for any of this. A loan returned or gone, its asset gone, or an id that is
 * not this loan's has no facts, so its reminder is taken down and forgotten.
 */
class DeadlineDeliveryFacts(
    private val assets: AssetRepository,
    private val today: Today,
    private val loans: AssetLoanRepository,
    private val clock: Clock,
    private val digestHour: () -> Int,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : DeadlineFactsSource {
    override suspend fun factsFor(key: SubjectKey.Deadline): DeadlineFacts? = when (key.kind) {
        DeadlineKind.WARRANTY_EXPIRY ->
            assets.get(AssetId(key.subjectId))?.let { DeadlineFacts(it.name, today.localDate()) }
        DeadlineKind.LOAN_DUE_BACK -> loanFactsFor(key.subjectId)
    }

    private suspend fun loanFactsFor(subjectId: String): DeadlineFacts? {
        val assetId = LoanSubjectId.assetOf(subjectId) ?: return null
        val loanId = LoanSubjectId.loanOf(subjectId) ?: return null
        val loan = loans.get(loanId)?.takeIf { it.isOpen && it.assetId == assetId } ?: return null
        val dueOn = loan.dueOn?.let(::parsedOrNull) ?: return null
        val asset = assets.get(assetId) ?: return null
        val zone = zone()
        val hour = digestHour()
        val nowMillis = clock.nowMillis()
        val localToday = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val todaysHour = localToday.atHour(hour, zone)
        return DeadlineFacts(
            ownerName = asset.name,
            today = today.localDate(),
            borrower = loan.borrowerName,
            cadenceSince = if (todaysHour <= nowMillis) todaysHour else localToday.minusDays(1).atHour(hour, zone),
            dayOpensAt = todaysHour,
            wordsTurnAt = dueOn.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }

    /** This day at [hour]:00 in [zone], as epoch millis; a skipped hour resolves forward by the gap. */
    private fun LocalDate.atHour(hour: Int, zone: ZoneId): Long =
        atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun parsedOrNull(text: String): LocalDate? = try {
        LocalDate.parse(text)
    } catch (e: DateTimeParseException) {
        null
    }
}

/**
 * The stamps of a provider handed no deadline facts: it never announces a warning, so it has
 * nothing to remember and nothing to forget.
 */
internal object NoDeadlineStamps : DeadlineLocalDeliveryRepository {
    override suspend fun get(kind: String, subjectId: String): DeadlineLocalDelivery? = null
    override suspend fun upsert(row: DeadlineLocalDelivery) = Unit
    override suspend fun delete(kind: String, subjectId: String) = Unit
    override suspend fun all(): List<DeadlineLocalDelivery> = emptyList()
    override suspend fun deleteAll() = Unit
}
