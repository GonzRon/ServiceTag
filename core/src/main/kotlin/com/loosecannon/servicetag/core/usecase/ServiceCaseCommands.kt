package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CaseCoverage
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.CaseType
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.Money
import com.loosecannon.servicetag.core.model.ServiceCaseId
import com.loosecannon.servicetag.core.model.isCode
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.warranty.WarrantyStatus
import com.loosecannon.servicetag.core.warranty.warrantyStatusOf
import java.time.LocalDate

/**
 * A service case's **editable header** (#79, C14): what [OpenServiceCase] opens and what
 * [UpdateServiceCase] replaces in full. It carries no asset, Incident, status, `closedOn` or stamp:
 * the asset and the Incident are fixed when the case is opened, and the status and `closedOn` move only
 * by a status entry ([AddServiceCaseEntry]).
 *
 * [resolutionEventId] has **no default**, deliberately: an edit that forgot to send the loaded link
 * would otherwise unlink the repair record without anyone asking for it. [costMinor] is in
 * [currency]'s minor units — null is no cost recorded, 0 is no charge (R79-7) — and any coverage may
 * carry one.
 */
data class ServiceCaseCommand(
    val title: String,
    val type: CaseType,
    /** ISO `YYYY-MM-DD`; not after today. */
    val openedOn: String,
    val coverage: CaseCoverage,
    val resolutionEventId: EventId?,
    val provider: String = "",
    val contact: String = "",
    val caseRef: String = "",
    val outboundTracking: String = "",
    val outboundCarrier: String = "",
    val returnTracking: String = "",
    val returnCarrier: String = "",
    val costMinor: Long? = null,
    val currency: String? = null,
    val notes: String = "",
)

/**
 * One entry to add to a case's timeline (#79, C14). A note, a status, or both; [status] null is a
 * note-only entry, which touches nothing but its own row (R79-5).
 */
data class CaseEntryCommand(
    /** ISO `YYYY-MM-DD`; not after today. */
    val occurredOn: String,
    /** `HH:MM`, or null when only the day is known. */
    val occurredTime: String?,
    val tzId: String,
    val note: String,
    val status: CaseStatus?,
)

/**
 * One thing wrong with a case command or an entry. **Every member is a 422** — the remedy is the body
 * that was sent — so each use case collects them all, before any write, into one
 * [ServiceCaseValidation].
 */
sealed interface ServiceCaseProblem {
    /** The trimmed title is empty. */
    data object TitleRequired : ServiceCaseProblem

    /** Not an ISO `YYYY-MM-DD` date. */
    data class BadDate(val field: String) : ServiceCaseProblem

    /** Not an `HH:MM` time of day. */
    data class BadTime(val field: String) : ServiceCaseProblem

    /**
     * Not a zone id: malformed, or — for an entry added here — one this device's time-zone data does
     * not know. A restored entry is judged by the id's form alone (`wellFormedZone`), as a condition is.
     */
    data class BadTimeZone(val field: String) : ServiceCaseProblem

    /** The case's `openedOn` is later than today. */
    data object OpenedAfterToday : ServiceCaseProblem

    /** A cost below zero. Zero is "no charge" and is kept. */
    data object NegativeCost : ServiceCaseProblem

    /** A cost with no currency to read it in. */
    data object CostWithoutCurrency : ServiceCaseProblem

    /** A currency that is not a three-letter code, or — beside a cost — one no currency table knows. */
    data object BadCurrency : ServiceCaseProblem

    /** The originating event is not a non-completion INCIDENT of this case's asset. */
    data class IncidentInvalid(val eventId: EventId) : ServiceCaseProblem

    /** The resolving event is not a MAINTENANCE or REPLACEMENT of this case's asset. */
    data class ResolutionInvalid(val eventId: EventId) : ServiceCaseProblem

    /** An entry with neither a note nor a status. */
    data object EntryEmpty : ServiceCaseProblem

    /** An entry dated later than today. */
    data object EntryAfterToday : ServiceCaseProblem
}

/** A case command or an entry was refused; every problem found, collected once. */
class ServiceCaseValidation(val problems: List<ServiceCaseProblem>) :
    IllegalArgumentException("service case rejected: ${problems.joinToString()}")

/** No case has this id. */
class NoSuchServiceCase(val id: ServiceCaseId) : IllegalArgumentException("no service case ${id.value}")

/**
 * The shape of a case header, whatever wrote it, and nothing about other rows. The use cases ask it of
 * the command they were sent, with their [today]; the backup content check asks it of every restored
 * case with none, so a restore refuses what a command refuses (the B03 ruling) but never judges a row by
 * the importing device's date. The currency rule is the asset's price rule, word for word.
 */
internal fun serviceCaseHeaderProblems(
    title: String,
    openedOn: String,
    costMinor: Long?,
    currency: String?,
    today: LocalDate? = null,
): List<ServiceCaseProblem> {
    val problems = mutableListOf<ServiceCaseProblem>()
    if (title.isBlank()) problems += ServiceCaseProblem.TitleRequired
    val opened = parseDate(openedOn)
    if (opened == null) {
        problems += ServiceCaseProblem.BadDate("openedOn")
    } else if (today != null && opened > today) {
        problems += ServiceCaseProblem.OpenedAfterToday
    }
    if (currency != null && !Money.isCode(currency)) {
        problems += ServiceCaseProblem.BadCurrency
    } else if (costMinor != null && currency != null && Money.fractionDigits(currency) == null) {
        problems += ServiceCaseProblem.BadCurrency
    }
    if (costMinor != null && currency == null) problems += ServiceCaseProblem.CostWithoutCurrency
    if (costMinor != null && costMinor < 0) problems += ServiceCaseProblem.NegativeCost
    return problems
}

/**
 * The shape of one timeline entry, whatever wrote it: a note or a status, an ISO date, an `HH:MM` time
 * or none, and a zone id [zone] accepts — the condition fact's rule (`conditionFactProblems`):
 * [AddServiceCaseEntry] asks with `resolvesHere`, the zone data of this device, and the backup content
 * check with `wellFormedZone`, the id's form alone. Whether it is dated after today is the use case's
 * alone: it needs a today.
 */
internal fun caseEntryProblems(
    occurredOn: String,
    occurredTime: String?,
    tzId: String,
    note: String,
    status: CaseStatus?,
    zone: (String) -> Boolean,
): List<ServiceCaseProblem> {
    val problems = mutableListOf<ServiceCaseProblem>()
    if (note.isBlank() && status == null) problems += ServiceCaseProblem.EntryEmpty
    if (parseDate(occurredOn) == null) problems += ServiceCaseProblem.BadDate("occurredOn")
    if (occurredTime != null && !isTimeOfDay(occurredTime)) problems += ServiceCaseProblem.BadTime("occurredTime")
    if (!zone(tzId)) problems += ServiceCaseProblem.BadTimeZone("tzId")
    return problems
}

/**
 * The coverage a new case's form suggests (#79, C15; R79-6) — a **form default only**: coverage is
 * the owner's stated fact, and nothing stores this answer on its own.
 *
 * The basis is the originating Incident's date, because warranty turns on when the unit failed, and
 * [openedOn] when there is no Incident. No warranty date — or one that does not parse — is UNKNOWN;
 * otherwise the shipped warranty rule decides, so the expiry day itself is still IN_WARRANTY. It is
 * never PARTLY_COVERED: that is only ever the owner's answer. It reads no clock.
 */
fun suggestCoverage(expiresOn: String?, incidentOn: String?, openedOn: String): CaseCoverage {
    val basis = parseDate(incidentOn ?: openedOn) ?: return CaseCoverage.UNKNOWN
    return when (warrantyStatusOf(expiresOn, basis)) {
        WarrantyStatus.IN_WARRANTY -> CaseCoverage.IN_WARRANTY
        WarrantyStatus.OUT_OF_WARRANTY -> CaseCoverage.OUT_OF_WARRANTY
        WarrantyStatus.NOT_RECORDED -> CaseCoverage.UNKNOWN
    }
}

/**
 * The resolving event rule (#79, C14; R79-10), shared by [OpenServiceCase] and [UpdateServiceCase]: a
 * link must name a MAINTENANCE or REPLACEMENT event of [assetId]. [stored] is the link the case already
 * holds: sent back unchanged it passes whatever it names now — a deleted event leaves a readable
 * dangling id (R79-4), and the link was checked when it was made. The event is read, never written.
 */
internal suspend fun resolutionProblem(
    events: EventRepository,
    assetId: AssetId,
    resolution: EventId?,
    stored: EventId?,
): ServiceCaseProblem? {
    if (resolution == null || resolution == stored) return null
    val event = events.get(resolution)
    val repair = event != null && event.assetId == assetId &&
        (event.kind == EventKind.MAINTENANCE || event.kind == EventKind.REPLACEMENT)
    return if (repair) null else ServiceCaseProblem.ResolutionInvalid(resolution)
}

/** [cmd]'s text trimmed, blank text `""` and a blank currency null — the one rule both writers store. */
internal fun ServiceCaseCommand.trimmed(): ServiceCaseCommand = copy(
    title = title.trim(),
    openedOn = openedOn.trim(),
    provider = provider.trim(),
    contact = contact.trim(),
    caseRef = caseRef.trim(),
    outboundTracking = outboundTracking.trim(),
    outboundCarrier = outboundCarrier.trim(),
    returnTracking = returnTracking.trim(),
    returnCarrier = returnCarrier.trim(),
    currency = currency.blankToNull(),
    notes = notes.trim(),
)
