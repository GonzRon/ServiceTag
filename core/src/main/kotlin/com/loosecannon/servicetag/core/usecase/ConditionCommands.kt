package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.OperationalCondition
import java.time.DateTimeException
import java.time.ZoneId
import java.time.zone.ZoneRulesException

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

    /**
     * Not a zone id: malformed, or — for a condition recorded here — one this device's time-zone
     * data does not know. A restored row is judged by the id's form alone ([wellFormedZone]).
     */
    data class BadTimeZone(val field: String) : ConditionProblem
}

/** A condition command was refused; every problem found, collected once. */
class ConditionValidation(val problems: List<ConditionProblem>) :
    IllegalArgumentException("condition rejected: ${problems.joinToString()}")

/** The most characters a condition's reason may hold (spec §5.1). */
const val MAX_CONDITION_REASON = 500

/**
 * The shape of one condition fact, whatever wrote it: an ISO date, an `HH:MM` time or none, a zone id
 * [zone] accepts, and a reason of at most [MAX_CONDITION_REASON] characters. [RecordCondition] asks
 * this for the command it was sent, with [resolvesHere]; the format-8 content check asks it for every
 * restored row, with [wellFormedZone], so a restore refuses the shapes the command refuses (master
 * plan §5, B03's concern 3) but never judges a row by the importing device's zone data. Whether the
 * date is in the future is [RecordCondition]'s alone: it needs a today.
 */
internal fun conditionFactProblems(
    occurredOn: String,
    occurredTime: String?,
    tzId: String,
    reason: String,
    zone: (String) -> Boolean,
): List<ConditionProblem> {
    val problems = mutableListOf<ConditionProblem>()
    if (parseDate(occurredOn) == null) problems += ConditionProblem.BadDate("occurredOn")
    if (occurredTime != null && !isTimeOfDay(occurredTime)) problems += ConditionProblem.BadTime("occurredTime")
    if (!zone(tzId)) problems += ConditionProblem.BadTimeZone("tzId")
    if (reason.length > MAX_CONDITION_REASON) problems += ConditionProblem.ReasonTooLong()
    return problems
}

/** A zone this device resolves: the rule for a condition recorded here, in the zone it is recorded in. */
internal fun resolvesHere(value: String): Boolean = try {
    ZoneId.of(value)
    true
} catch (e: DateTimeException) {
    false
}

/**
 * A well-formed zone id, whether or not this device's time-zone data knows the region: the rule for a
 * restored row (the controller's ruling on B06-F7). The archive is judged by its own contents — a
 * region added by newer zone data than this device carries is still the zone the row was recorded
 * in, and nothing computes with it — so only an id no zone data could ever hold is refused.
 */
internal fun wellFormedZone(value: String): Boolean = try {
    ZoneId.of(value)
    true
} catch (e: ZoneRulesException) {
    true
} catch (e: DateTimeException) {
    false
}
