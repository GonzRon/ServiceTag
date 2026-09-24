package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.OperationalCondition
import java.time.DateTimeException
import java.time.ZoneId

/**
 * One condition to record (spec §5; master plan §9). [occurredOn] defaults to today and may not be
 * later; [occurredTime] is `HH:MM` or null when only the day is known; [tzId] is the zone it was
 * entered in; [reason] may be empty; [eventId] is the journal event it follows from, a soft link that
 * must name an event of the same asset.
 */
data class ConditionCommand(
    val condition: OperationalCondition,
    val occurredOn: String? = null,
    val occurredTime: String? = null,
    val tzId: String,
    val reason: String = "",
    val eventId: EventId? = null,
)

/**
 * One thing wrong with a [ConditionCommand]. **Every member is a 422** — the remedy is the body that
 * was sent — so they are collected into one [ConditionValidation]. The three coded ones are spec
 * §9.2's; the three malformed-value ones keep the shipped validation shape, `Bad…(field=…)`.
 */
sealed interface ConditionProblem {
    /** `CONDITION_DATE_IN_FUTURE`: [ConditionCommand.occurredOn] is later than today. */
    data object DateInFuture : ConditionProblem

    /** `CONDITION_REASON_TOO_LONG`: the trimmed reason is longer than [limit] characters. */
    data class ReasonTooLong(val limit: Int = MAX_CONDITION_REASON) : ConditionProblem

    /** `FOREIGN_EVENT`: the linked event is another asset's, or does not exist. */
    data class ForeignEvent(val eventId: EventId) : ConditionProblem

    /** Not an ISO `YYYY-MM-DD` date. */
    data class BadDate(val field: String) : ConditionProblem

    /** Not an `HH:MM` time of day. */
    data class BadTime(val field: String) : ConditionProblem

    /** Not a zone id the platform can resolve. */
    data class BadTimeZone(val field: String) : ConditionProblem
}

/** A condition command was refused; every problem found, collected once. */
class ConditionValidation(val problems: List<ConditionProblem>) :
    IllegalArgumentException("condition rejected: ${problems.joinToString()}")

/** The most characters a condition's reason may hold (spec §5.1). */
const val MAX_CONDITION_REASON = 500

private val TIME = Regex("^\\d{2}:\\d{2}$")

/**
 * The shape of one condition fact, whatever wrote it: an ISO date, an `HH:MM` time or none, a zone
 * the platform resolves, and a reason of at most [MAX_CONDITION_REASON] characters. [RecordCondition]
 * asks this for the command it was sent, and the format-8 content check asks it for every restored
 * row, so a restore refuses exactly the shapes the command refuses (master plan §5, B03's concern 3).
 * Whether the date is in the future is [RecordCondition]'s alone: it needs a today.
 */
internal fun conditionFactProblems(
    occurredOn: String,
    occurredTime: String?,
    tzId: String,
    reason: String,
): List<ConditionProblem> {
    val problems = mutableListOf<ConditionProblem>()
    if (parseDate(occurredOn) == null) problems += ConditionProblem.BadDate("occurredOn")
    if (occurredTime != null && !isTimeOfDay(occurredTime)) problems += ConditionProblem.BadTime("occurredTime")
    if (!isZoneId(tzId)) problems += ConditionProblem.BadTimeZone("tzId")
    if (reason.length > MAX_CONDITION_REASON) problems += ConditionProblem.ReasonTooLong()
    return problems
}

private fun isTimeOfDay(value: String): Boolean =
    TIME.matches(value) && value.substring(0, 2).toInt() < 24 && value.substring(3, 5).toInt() < 60

private fun isZoneId(value: String): Boolean = try {
    ZoneId.of(value)
    true
} catch (e: DateTimeException) {
    false
}
