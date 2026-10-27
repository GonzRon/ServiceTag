package com.loosecannon.servicetag.core.usecase

import java.time.LocalDate

/**
 * O-16 as amended (C39): a schedule's completion dated after [today]. Raised by [CompleteSchedule],
 * [CompleteGroupMembers] and an [UpdateEvent] whose result names a schedule, before anything is written. Both dates
 * are ISO `YYYY-MM-DD`, the second the phone's own today, so a refusal says which day decided it.
 */
class CompletionDateInFuture(val occurredOn: String, val today: String) :
    IllegalArgumentException("completion dated $occurredOn is after today, $today")

/**
 * Throws [CompletionDateInFuture] when [occurredOn] is a date after [today]. An [occurredOn] that is not a date is
 * left alone: `buildEvent` refuses it as `FieldProblem.BadDate`, so a call is refused once, for its date's first
 * problem.
 */
fun refuseFutureCompletion(occurredOn: String, today: LocalDate) {
    val on = parseDate(occurredOn) ?: return
    if (on > today) throw CompletionDateInFuture(occurredOn, today.toString())
}
