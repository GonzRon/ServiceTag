package com.loosecannon.servicetag.core.reminders

import java.security.MessageDigest
import java.time.LocalDate

/**
 * The hash a [ReminderSubject] carries, over exactly the fields that change what a provider should
 * show.
 *
 * It is a **narrow** hash on purpose. Hashing the schedule row would make every unrelated edit — a
 * note, a description, a re-save that changed nothing — look like a change, and a provider told a
 * subject changed has to act on it. So the input is the six display-bearing fields and nothing
 * else: no id, no timestamp, no provenance, nothing about where a measurement came from.
 *
 * The canonical form is the fields in a fixed order separated by the ASCII unit separator, with an
 * explicit marker for every absent value. The separator is the reason `title` and `body` cannot be
 * confused for one another by concatenation, and the markers are the reason a null date and the
 * empty string do not collide.
 */
object ContentHash {

    private const val SEP = '\u001F'
    private const val ABSENT = "\u0000"

    fun of(
        title: String,
        body: String,
        dueOn: LocalDate?,
        leadDays: Int,
        state: SubjectState,
        rule: RuleFacts?,
    ): String = sha256(
        listOf(
            title,
            body,
            dueOn?.toString() ?: ABSENT,
            leadDays.toString(),
            render(state),
            rule?.let(::render) ?: ABSENT,
        ).joinToString(SEP.toString()),
    )

    /**
     * States are rendered by name, and [SubjectState.Parked] by name **and** re-entry date: two
     * parked subjects that come back on different days are not the same thing to show.
     */
    private fun render(state: SubjectState): String = when (state) {
        SubjectState.Active -> "ACTIVE"
        SubjectState.Completed -> "COMPLETED"
        SubjectState.Withdrawn -> "WITHDRAWN"
        is SubjectState.Parked -> "PARKED:${state.reentryOn?.toString() ?: ABSENT}"
    }

    private fun render(rule: RuleFacts): String = listOf(
        rule.basis?.name ?: ABSENT,
        rule.interval?.toString() ?: ABSENT,
        rule.unit?.name ?: ABSENT,
        rule.hasMeter.toString(),
        rule.seasonal.toString(),
    ).joinToString("|")

    private fun sha256(canonical: String): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
